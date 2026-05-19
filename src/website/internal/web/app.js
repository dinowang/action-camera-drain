// Action Camera Catch — minimal vanilla JS SPA.
//
// Wire-up:
//   - GET /api/containers → render top table
//   - click row → GET /api/containers/{c}/blobs → render blob table
//   - "同步全部" → POST /api/jobs { containers: ["*"] } → subscribe SSE
//   - row "同步" button → POST /api/jobs { containers: [name] }

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => Array.from(document.querySelectorAll(sel));

const fmtBytes = (n) => {
  if (!n) return "0";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let i = 0;
  while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
  return `${n.toFixed(i === 0 ? 0 : 1)} ${units[i]}`;
};

const fmtBps = (b) => fmtBytes(b) + "/s";

async function loadContainers() {
  const r = await fetch("/api/containers");
  if (!r.ok) {
    alert("讀取 container 失敗：" + r.status);
    return;
  }
  const list = await r.json() ?? [];
  const tbody = $("#containers-table tbody");
  tbody.innerHTML = "";
  for (const c of list) {
    const tr = document.createElement("tr");
    tr.dataset.name = c.name;
    tr.innerHTML = `
      <td>📁</td>
      <td><strong>${c.name}</strong></td>
      <td class="num">${c.remoteCount}</td>
      <td class="num"><span class="state pending">${c.pendingCount}</span></td>
      <td class="num"><span class="state skipped">${c.skippedCount}</span></td>
      <td class="num">${fmtBytes(c.pendingBytes)}</td>
      <td><button data-action="sync" data-name="${c.name}">同步</button></td>
    `;
    tbody.appendChild(tr);
  }
}

async function loadBlobs(container) {
  $("#blobs-section").hidden = false;
  $("#blobs-title").textContent = `📁 ${container}`;
  const tbody = $("#blobs-table tbody");
  tbody.innerHTML = `<tr><td colspan="4" class="muted">載入中…</td></tr>`;
  const r = await fetch(`/api/containers/${encodeURIComponent(container)}/blobs`);
  if (!r.ok) { tbody.innerHTML = `<tr><td colspan="4">錯誤 ${r.status}</td></tr>`; return; }
  const list = await r.json() ?? [];
  tbody.innerHTML = "";
  for (const b of list) {
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td><span class="state ${b.status}">${b.status}</span></td>
      <td>${b.name}</td>
      <td class="num">${fmtBytes(b.size)}</td>
      <td class="muted">${b.reason ?? ""}</td>
    `;
    tbody.appendChild(tr);
  }
}

let currentJob = null;
let currentES = null;

async function startJob(containers) {
  if (currentES) { currentES.close(); currentES = null; }
  const r = await fetch("/api/jobs", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ containers }),
  });
  if (!r.ok) { alert("啟動 job 失敗：" + r.status); return; }
  const { id } = await r.json();
  currentJob = id;
  $("#job-section").hidden = false;
  $("#job-log").innerHTML = "";
  $("#job-progress-bar").value = 0;
  $("#job-state").textContent = "running";
  $("#job-state").className = "state running";

  const es = new EventSource(`/api/jobs/${encodeURIComponent(id)}/events`);
  currentES = es;
  es.addEventListener("file-start", (e) => log("▶ " + JSON.parse(e.data).blob));
  es.addEventListener("file-skip", (e) => log("⏭ " + JSON.parse(e.data).blob));
  es.addEventListener("file-done", (e) => {
    const d = JSON.parse(e.data);
    log(`✓ ${d.blob} (${fmtBytes(d.size)})`);
    if (d.bytesTotal > 0) {
      $("#job-progress-bar").max = d.bytesTotal;
      $("#job-progress-bar").value = d.bytesDone;
      $("#job-progress").textContent = `${d.filesDone}/${d.filesTotal} 檔案，${fmtBytes(d.bytesDone)} / ${fmtBytes(d.bytesTotal)}`;
    }
  });
  es.addEventListener("file-failed", (e) => {
    const d = JSON.parse(e.data);
    log(`✗ ${d.blob}: ${d.reason}`);
  });
  es.addEventListener("concurrency", (e) => {
    const d = JSON.parse(e.data);
    $("#job-bps").textContent = `${d.concurrency} threads · ${fmtBps(d.bps)}`;
  });
  es.addEventListener("job-done", (e) => {
    const d = JSON.parse(e.data);
    $("#job-state").textContent = d.state;
    $("#job-state").className = `state ${d.state}`;
    log(`— job ${d.state}` + (d.reason ? `: ${d.reason}` : ""));
    es.close();
    currentES = null;
    loadContainers();
  });
  es.onerror = () => { /* server closed — handled by job-done */ };
}

function log(text) {
  const li = document.createElement("li");
  li.textContent = `[${new Date().toLocaleTimeString()}] ${text}`;
  const log = $("#job-log");
  log.appendChild(li);
  log.scrollTop = log.scrollHeight;
}

document.addEventListener("click", (e) => {
  const btn = e.target.closest("button");
  if (btn?.dataset.action === "sync") {
    e.stopPropagation();
    startJob([btn.dataset.name]);
    return;
  }
  const row = e.target.closest("tr[data-name]");
  if (row) loadBlobs(row.dataset.name);
});

$("#refresh-btn").addEventListener("click", loadContainers);
$("#sync-all-btn").addEventListener("click", () => startJob(["*"]));
$("#cancel-btn").addEventListener("click", async () => {
  if (!currentJob) return;
  await fetch(`/api/jobs/${encodeURIComponent(currentJob)}`, { method: "DELETE" });
});

loadContainers();
