// Action Camera Catch — server-side jobs with reconnectable browser views.

const $ = (sel) => document.querySelector(sel);

const jobViews = new Map();
let selectedContainer = null;

const escapeHTML = (value) => String(value ?? "")
  .replaceAll("&", "&amp;")
  .replaceAll("<", "&lt;")
  .replaceAll(">", "&gt;")
  .replaceAll('"', "&quot;")
  .replaceAll("'", "&#039;");

const fmtBytes = (value) => {
  let n = Number(value) || 0;
  const units = ["B", "KB", "MB", "GB", "TB"];
  let i = 0;
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024;
    i++;
  }
  return `${n.toFixed(i === 0 ? 0 : 1)} ${units[i]}`;
};

const fmtBps = (value) => `${fmtBytes(value)}/s`;
const fmtTime = (value) => value ? new Date(value).toLocaleString() : "—";
const isTerminal = (state) => ["done", "failed", "cancelled"].includes(state);

async function readJSON(response) {
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.error ?? `HTTP ${response.status}`);
  return body;
}

async function loadContainers() {
  const listNode = $("#containers-list");
  listNode.innerHTML = '<div class="empty-state">載入 containers…</div>';
  try {
    const list = await readJSON(await fetch("/api/containers"));
    $("#containers-summary").textContent = `${list.length} containers`;
    listNode.innerHTML = "";
    if (!list.length) {
      listNode.innerHTML = '<div class="empty-state">目前沒有可存取的 container。</div>';
      return;
    }
    for (const container of list) {
      const card = document.createElement("article");
      card.className = "container-card";
      card.dataset.name = container.name;
      if (container.name === selectedContainer) card.classList.add("selected");
      card.innerHTML = `
        <button class="container-select" data-action="select-container" data-name="${escapeHTML(container.name)}">
          <span class="container-name">📁 ${escapeHTML(container.name)}</span>
          <span class="container-metrics">
            <span>${container.remoteCount} 遠端</span>
            <span>${container.pendingCount} 待下載</span>
            <span>${container.skippedCount} 已存在</span>
            <span>${fmtBytes(container.pendingBytes)}</span>
          </span>
        </button>
        <div class="container-actions">
          <button data-action="sync" data-name="${escapeHTML(container.name)}">同步</button>
          <button
            data-action="delete-cloud"
            data-name="${escapeHTML(container.name)}"
            class="danger"
            ${container.pendingCount === 0 ? "" : 'disabled title="仍有檔案尚未在 NAS 完整驗證"'}
          >刪除雲端</button>
        </div>
      `;
      listNode.appendChild(card);
    }
  } catch (error) {
    listNode.innerHTML = `<div class="error-state">${escapeHTML(error.message)}</div>`;
  }
}

async function loadBlobs(container) {
  selectedContainer = container;
  document.querySelectorAll(".container-card").forEach((card) => {
    card.classList.toggle("selected", card.dataset.name === container);
  });
  $("#blobs-title").textContent = `Blob Entries Details · ${container}`;
  $("#blobs-summary").textContent = "載入中…";
  $("#blobs-empty").className = "empty-state";
  $("#blobs-empty").hidden = false;
  $("#blobs-empty").textContent = "載入 blobs…";
  $("#blobs-table").hidden = true;
  try {
    const list = await readJSON(
      await fetch(`/api/containers/${encodeURIComponent(container)}/blobs`)
    );
    const tbody = $("#blobs-table tbody");
    tbody.innerHTML = "";
    for (const blob of list) {
      const row = document.createElement("tr");
      row.innerHTML = `
        <td><span class="state ${escapeHTML(blob.status)}">${escapeHTML(blob.status)}</span></td>
        <td class="blob-name">${escapeHTML(blob.name)}</td>
        <td class="num">${fmtBytes(blob.size)}</td>
        <td class="muted">${escapeHTML(blob.reason ?? "")}</td>
      `;
      tbody.appendChild(row);
    }
    $("#blobs-summary").textContent = `${list.length} blobs`;
    $("#blobs-empty").className = "empty-state";
    $("#blobs-empty").hidden = list.length > 0;
    $("#blobs-empty").textContent = "此 container 目前沒有 blobs。";
    $("#blobs-table").hidden = list.length === 0;
  } catch (error) {
    $("#blobs-summary").textContent = "讀取失敗";
    $("#blobs-empty").hidden = false;
    $("#blobs-empty").className = "error-state";
    $("#blobs-empty").textContent = error.message;
  }
}

async function loadJobs() {
  try {
    const jobs = await readJSON(await fetch("/api/jobs"));
    const ids = new Set(jobs.map((job) => job.id));
    for (const id of jobViews.keys()) {
      if (!ids.has(id)) removeJobView(id);
    }
    jobs.forEach(upsertJob);
    jobs.forEach((job) => {
      const view = jobViews.get(job.id);
      if (view) $("#jobs-list").appendChild(view.card);
    });
    updateJobsSummary();
  } catch (error) {
    $("#jobs-list").innerHTML = `<div class="error-state">${escapeHTML(error.message)}</div>`;
  }
}

function upsertJob(snapshot) {
  let view = jobViews.get(snapshot.id);
  if (!view) {
    const card = document.createElement("article");
    card.className = "job-card";
    card.dataset.jobId = snapshot.id;
    card.innerHTML = `
      <div class="job-card-header">
        <div>
          <strong class="job-containers"></strong>
          <span class="job-time muted"></span>
        </div>
        <div class="job-actions">
          <span class="state job-state"></span>
          <button data-action="cancel-job" class="job-cancel">取消</button>
        </div>
      </div>
      <div class="job-metrics muted"></div>
      <progress value="0" max="1"></progress>
      <details>
        <summary>事件記錄</summary>
        <ul class="job-log"></ul>
      </details>
    `;
    $("#jobs-list").prepend(card);
    view = { card, source: null, snapshot };
    jobViews.set(snapshot.id, view);
  }
  view.snapshot = { ...view.snapshot, ...snapshot };
  renderJob(view);
  if (!isTerminal(view.snapshot.state) && !view.source) connectJob(view);
  if (isTerminal(view.snapshot.state) && view.source) {
    view.source.close();
    view.source = null;
  }
  updateJobsSummary();
}

function renderJob(view) {
  const { card, snapshot } = view;
  card.querySelector(".job-containers").textContent =
    snapshot.containers?.join(", ") || "All containers";
  card.querySelector(".job-time").textContent = fmtTime(snapshot.createdAtMillis);
  const state = card.querySelector(".job-state");
  state.textContent = snapshot.state;
  state.className = `state job-state ${snapshot.state}`;
  card.querySelector(".job-cancel").hidden = isTerminal(snapshot.state);
  card.querySelector(".job-metrics").textContent =
    `${snapshot.filesDone ?? 0}/${snapshot.filesTotal ?? 0} 檔案 · ` +
    `${fmtBytes(snapshot.bytesDone)} / ${fmtBytes(snapshot.bytesTotal)} · ` +
    `${snapshot.currentConcurrency ?? 0} threads · ${fmtBps(snapshot.bytesPerSecond)}` +
    (snapshot.message ? ` · ${snapshot.message}` : "");
  const progress = card.querySelector("progress");
  progress.max = Math.max(Number(snapshot.bytesTotal) || 0, 1);
  progress.value = Math.min(Number(snapshot.bytesDone) || 0, progress.max);
}

function connectJob(view) {
  const source = new EventSource(`/api/jobs/${encodeURIComponent(view.snapshot.id)}/events`);
  view.source = source;
  const listen = (type, handler) => source.addEventListener(type, (event) => {
    const data = JSON.parse(event.data);
    handler(data);
    renderJob(view);
  });
  listen("file-start", (data) => appendJobLog(view, `▶ ${data.blob}`));
  listen("file-skip", (data) => appendJobLog(view, `⏭ ${data.blob}`));
  listen("file-warning", (data) => appendJobLog(view, `⚠ ${data.blob}: ${data.reason ?? ""}`));
  listen("file-done", (data) => {
    Object.assign(view.snapshot, data);
    appendJobLog(view, `✓ ${data.blob} (${fmtBytes(data.size)})`);
  });
  listen("file-failed", (data) => {
    Object.assign(view.snapshot, data);
    appendJobLog(view, `✗ ${data.blob}: ${data.reason}`);
  });
  listen("concurrency", (data) => {
    view.snapshot.currentConcurrency = data.concurrency;
    view.snapshot.bytesPerSecond = data.bps;
  });
  listen("job-done", (data) => {
    view.snapshot.state = data.state;
    view.snapshot.message = data.reason ?? "";
    appendJobLog(view, `— job ${data.state}${data.reason ? `: ${data.reason}` : ""}`);
    source.close();
    view.source = null;
    loadContainers();
    refreshJobSnapshot(view.snapshot.id);
  });
  source.onerror = async () => {
    source.close();
    view.source = null;
    await refreshJobSnapshot(view.snapshot.id);
  };
}

async function refreshJobSnapshot(id) {
  try {
    upsertJob(await readJSON(await fetch(`/api/jobs/${encodeURIComponent(id)}`)));
  } catch {
    removeJobView(id);
  }
}

function appendJobLog(view, text) {
  const item = document.createElement("li");
  item.textContent = `[${new Date().toLocaleTimeString()}] ${text}`;
  const log = view.card.querySelector(".job-log");
  log.appendChild(item);
  while (log.children.length > 100) log.firstElementChild.remove();
  log.scrollTop = log.scrollHeight;
}

function removeJobView(id) {
  const view = jobViews.get(id);
  if (!view) return;
  view.source?.close();
  view.card.remove();
  jobViews.delete(id);
}

function updateJobsSummary() {
  const jobs = [...jobViews.values()].map((view) => view.snapshot);
  const active = jobs.filter((job) => !isTerminal(job.state)).length;
  $("#jobs-summary").textContent = `${active} active · ${jobs.length - active} recent`;
  if (!jobs.length) {
    $("#jobs-list").innerHTML = '<div class="empty-state">目前沒有背景下載工作。</div>';
  } else {
    $("#jobs-list .empty-state")?.remove();
  }
}

async function startJob(containers) {
  try {
    const result = await readJSON(await fetch("/api/jobs", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ containers }),
    }));
    await refreshJobSnapshot(result.id);
  } catch (error) {
    alert(`啟動 job 失敗：${error.message}`);
  }
}

async function cancelJob(id) {
  try {
    await readJSON(await fetch(`/api/jobs/${encodeURIComponent(id)}`, { method: "DELETE" }));
  } catch (error) {
    if (!error.message.includes("Unexpected end")) alert(`取消失敗：${error.message}`);
  }
}

async function deleteCloudContainer(button) {
  const container = button.dataset.name;
  if (!window.confirm(
    `確定要永久刪除 Azure container「${container}」及其中所有檔案嗎？\n\n` +
    "Catch 會先重新驗證 NAS 上的每個檔案；驗證未通過時不會刪除。"
  )) return;
  const originalText = button.textContent;
  button.disabled = true;
  button.textContent = "驗證中…";
  try {
    const result = await readJSON(await fetch(
      `/api/containers/${encodeURIComponent(container)}`,
      { method: "DELETE" }
    ));
    alert(
      `已刪除 Azure container「${container}」。\n` +
      `刪除前已驗證 ${result.verifiedFiles} 個檔案（${fmtBytes(result.verifiedBytes)}）。`
    );
    if (selectedContainer === container) {
      selectedContainer = null;
      $("#blobs-title").textContent = "Blob Entries Details";
      $("#blobs-summary").textContent = "請先選擇左側 container";
      $("#blobs-table").hidden = true;
      $("#blobs-empty").hidden = false;
      $("#blobs-empty").textContent = "選擇一個 container 以查看遠端檔案與 NAS 狀態。";
    }
    await loadContainers();
  } catch (error) {
    alert(`刪除失敗：${error.message}`);
    button.disabled = false;
    button.textContent = originalText;
  }
}

document.addEventListener("click", (event) => {
  const button = event.target.closest("button");
  if (!button) return;
  const { action, name } = button.dataset;
  if (action === "select-container") loadBlobs(name);
  if (action === "sync") startJob([name]);
  if (action === "delete-cloud") deleteCloudContainer(button);
  if (action === "cancel-job") cancelJob(button.closest(".job-card").dataset.jobId);
});

$("#refresh-btn").addEventListener("click", () => Promise.all([loadContainers(), loadJobs()]));
$("#refresh-jobs-btn").addEventListener("click", loadJobs);
$("#sync-all-btn").addEventListener("click", () => startJob(["*"]));

Promise.all([loadContainers(), loadJobs()]);
