#!/usr/bin/env python3
"""Filing POC — dry-run for the blob filing layout in docs/flash-filing-logic.md.

Walks a source path, auto-detects the source camera per DCIM bucket using the
filename / folder fingerprints documented in docs/eval-action-camera-flash-detection.md,
and prints what blob name each file would be uploaded as. No Azure I/O.

Auto-detection is filename/folder-pattern only (no MP4 parsing). It pins down
**brand** (and for Insta360, **lens module**), but not model and not serial — so
the resulting deviceKey is at the brand level (e.g. `gopro`,
`insta360-lens-flat`, `dji`). When CLI flags are given they override the
detection.

Usage:
    # Auto-detect everything:
    ./filing.py /Volumes/Untitled

    # Force-override (no detection):
    ./filing.py /path/to/card --brand GoPro --model "HERO11 Black" --serial XXX

    # Disable auto-detect entirely — everything goes to unknown/:
    ./filing.py /path/to/card --no-detect

Exit codes:
    0  plan generated, no conflicts
    2  plan generated, blob-name conflicts or oversized names detected
    1  usage / IO error
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import re
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

import mp4probe
import insta360probe

# --- constants -------------------------------------------------------------

# Azure Blob name byte-length limit. See:
# https://learn.microsoft.com/rest/api/storageservices/naming-and-referencing-containers--blobs--and-metadata
BLOB_NAME_MAX_BYTES = 1024

DCIM_DIR_NAMES = ("DCIM",)
# Internal bucket-grouping key for files that have no source subdir after
# stripping DCIM. Empty string so it never shows up in the blob path.
ROOT_SOURCE_SUBDIR = ""

# File extensions worth opening for MP4 metadata. (.insv is MP4-shaped.)
_PROBE_EXTS = {".mp4", ".insv", ".mov"}
# How many files per bucket the probe will open. 1 is usually enough.
_PROBE_SAMPLE_PER_BUCKET = 2

# --- normalization ---------------------------------------------------------

_NORMALIZE_DROP = re.compile(r"[^a-z0-9-]+")
_NORMALIZE_COLLAPSE = re.compile(r"-{2,}")


def normalize_segment(value: str) -> str:
    s = value.strip().lower()
    s = s.replace("_", "-").replace(" ", "-")
    s = _NORMALIZE_DROP.sub("-", s)
    s = _NORMALIZE_COLLAPSE.sub("-", s)
    return s.strip("-")


def device_id_short(serial: str) -> str:
    """SHA-256(serial) -> base32 -> lower -> first 8 chars. Pure function."""
    digest = hashlib.sha256(serial.encode("utf-8")).digest()
    return base64.b32encode(digest).decode("ascii").lower().rstrip("=")[:8]


# --- device model ----------------------------------------------------------

@dataclass(frozen=True)
class Device:
    brand: str | None
    model: str | None = None
    lens: str | None = None    # Insta360 only
    serial: str | None = None
    source: str = "detected"   # "detected" | "override" | "no-detect"

    def key(self) -> str | None:
        """deviceKey string; None means -> unknown/."""
        if not self.brand:
            return None
        brand_seg = normalize_segment(self.brand)
        parts = [brand_seg]
        if self.model:
            model_seg = normalize_segment(self.model)
            # Strip a redundant brand prefix from the model when the camera's
            # own model string already includes its brand (common: "Insta360
            # OneRS" -> 'insta360-oners' -> would yield 'insta360-insta360-oners').
            if model_seg.startswith(brand_seg + "-"):
                model_seg = model_seg[len(brand_seg) + 1 :]
            elif model_seg == brand_seg:
                model_seg = ""
            if model_seg:
                parts.append(model_seg)
        if self.lens:
            parts.append(f"lens-{normalize_segment(self.lens)}")
        if self.serial:
            parts.append(device_id_short(self.serial))
        return "-".join(p for p in parts if p)


# --- detection (Phase 1: filename + folder heuristics) ---------------------

# Patterns derived from docs/eval-action-camera-flash-detection.md.
# Strong, mutually-exclusive enough to use as first-hit rules.
_GOPRO_FNAME = re.compile(r"^(GH|GX|GP|GOPR|GS)\d+\.(MP4|JPG|LRV|THM)$", re.I)
_INSTA360_360_FNAME = re.compile(r"^VID_\d{8}_\d{6}_(00|10)_\d+\.insv$", re.I)
_INSTA360_FLAT_FNAME = re.compile(r"^(VID|LRV)_\d{8}_\d{6}_01_\d+\.mp4$", re.I)
_DJI_FNAME = re.compile(r"^DJI_\d+\.(MP4|JPG)$", re.I)


def detect_bucket(folder_name: str, file_names: list[str]) -> Device | None:
    """Return a Device for this bucket, or None if it doesn't look like a known
    camera. CLI overrides happen in the caller, not here."""
    exts = {Path(n).suffix.lower() for n in file_names}

    # GoPro — filename prefix is the strongest signal.
    if any(_GOPRO_FNAME.match(n) for n in file_names):
        return Device(brand="GoPro")
    # GoPro fallback by folder suffix.
    if folder_name.upper().endswith("GOPRO"):
        return Device(brand="GoPro")

    # Insta360 — distinguish by lens module:
    #   * 360° module: .insv / .insp present, paired _00_/_10_ halves
    #   * Flat module (4K Mod etc.): only .mp4, file pattern _01_
    if ".insv" in exts or ".insp" in exts:
        return Device(brand="Insta360", lens="360")
    if any(_INSTA360_FLAT_FNAME.match(n) for n in file_names):
        return Device(brand="Insta360", lens="flat")

    # DJI — filename prefix; .LRF as corroboration but not required.
    if any(_DJI_FNAME.match(n) for n in file_names):
        return Device(brand="DJI")
    # Camera01 + only .mp4 + neither GoPro nor DJI prefix → still likely
    # Insta360 flat with unusual filenames; we deliberately do NOT guess.

    return None


# --- bucket / planning -----------------------------------------------------

def is_hidden_path(src_root: Path, file_path: Path) -> bool:
    try:
        rel_parts = file_path.relative_to(src_root).parts
    except ValueError:
        return False
    return any(p.startswith(".") for p in rel_parts)


def source_subdir_for(src_root: Path, file_path: Path) -> str:
    """Pick the source subfolder name preserved in the blob path.

    Accepts source path being card root, DCIM/ itself, or any deeper folder."""
    try:
        rel_parts = file_path.relative_to(src_root).parts
    except ValueError:
        return ROOT_SOURCE_SUBDIR
    dir_parts = rel_parts[:-1]
    # Strip a leading DCIM/ so card-root and DCIM/-source behave the same.
    if dir_parts and dir_parts[0] in DCIM_DIR_NAMES:
        dir_parts = dir_parts[1:]
    if not dir_parts:
        return ROOT_SOURCE_SUBDIR
    return dir_parts[0]


def iter_files(root: Path):
    """Yield regular files under root, sorted, skipping any hidden path."""
    if not root.exists():
        raise FileNotFoundError(root)
    if root.is_file():
        if not root.name.startswith("."):
            yield root
        return
    for p in sorted(root.rglob("*")):
        if p.is_file() and not is_hidden_path(root, p):
            yield p


def plan_blob_name(container: str, device: Device | None,
                   source_subdir: str, file_name: str) -> str:
    bucket = device.key() if device and device.key() else "unknown"
    # Skip the source-subdir segment entirely when it's empty (file was at the
    # card root, or at DCIM/ root). Avoids ugly `<dev>/_root/...` paths.
    if source_subdir:
        return f"{container}/{bucket}/{source_subdir}/{file_name}"
    return f"{container}/{bucket}/{file_name}"


# --- main ------------------------------------------------------------------

def parse_args(argv):
    ap = argparse.ArgumentParser(
        description="Dry-run filing-logic POC (auto-detect by filename heuristics; no Azure I/O).",
        epilog=(
            "Without CLI flags, auto-detection runs per DCIM bucket. CLI flags\n"
            "force-override the detection and apply to ALL buckets.\n\n"
            "See docs/flash-filing-logic.md for the layout this POC implements."
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("source", type=Path, help="card root, DCIM/, or any bucket folder")
    ap.add_argument("--brand", help="override detection (e.g. GoPro, Insta360, DJI)")
    ap.add_argument("--model", help="override detection (e.g. 'HERO11 Black', 'ONE RS')")
    ap.add_argument("--serial", help="device serial (hashed -> 8-char short)")
    ap.add_argument("--lens", choices=("360", "flat"),
                    help="Insta360 lens module (only meaningful with --brand Insta360)")
    ap.add_argument("--container", default="ingest",
                    help="destination Azure Blob container name (default: ingest)")
    ap.add_argument("--no-detect", action="store_true",
                    help="disable auto-detect; without overrides everything -> unknown/")
    ap.add_argument("--mp4-probe", action="store_true",
                    help="open one MP4/insv per bucket and read moov metadata "
                         "(Phase 2: picks up model & serial when present)")
    ap.add_argument("-v", "--verbose", action="store_true",
                    help="also print one line per file (default: profile summary only)")
    return ap.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    src_root = args.source.resolve()
    try:
        files = list(iter_files(src_root))
    except FileNotFoundError:
        print(f"error: source path does not exist: {src_root}", file=sys.stderr)
        return 1
    if not files:
        print(f"error: no files found under: {src_root}", file=sys.stderr)
        return 1

    # CLI override?
    override = None
    if args.brand or args.model or args.lens or args.serial:
        override = Device(
            brand=args.brand,
            model=args.model,
            lens=args.lens,
            serial=args.serial,
            source="override",
        )

    # Group files by source subdir (= bucket).
    buckets: dict[str, list[Path]] = defaultdict(list)
    for f in files:
        buckets[source_subdir_for(src_root, f)].append(f)

    # Decide a Device for each bucket.
    bucket_device: dict[str, Device | None] = {}
    for sub, fl in buckets.items():
        if override is not None:
            bucket_device[sub] = override
        elif args.no_detect:
            bucket_device[sub] = Device(brand=None, source="no-detect")
        else:
            file_names = [f.name for f in fl]
            bucket_device[sub] = detect_bucket(sub, file_names)

    # Phase 2: optional MP4 metadata probe.
    probe_findings: dict[str, dict[str, str | None]] = {}
    probe_notes: dict[str, list[str]] = defaultdict(list)
    if args.mp4_probe and override is None and not args.no_detect:
        # Phase 2a: Insta360 fileinfo_list.list — single shot per card, applies
        # to any bucket Phase 1 tagged as Insta360 (or none at all).
        fileinfo_path = insta360probe.find_fileinfo(src_root)
        fileinfo_data: dict | None = None
        if fileinfo_path is not None:
            fileinfo_data = insta360probe.probe(fileinfo_path)
            if fileinfo_data:
                probe_findings["__fileinfo_list__"] = {
                    "source": str(fileinfo_path.relative_to(src_root)),
                    "records": str(fileinfo_data.get("record_count", "")),
                    **{k: v for k, v in fileinfo_data.items()
                       if k in ("brand", "model", "firmware", "serial")},
                }
                for sub, dev in list(bucket_device.items()):
                    # Only apply to buckets Phase 1 has already tagged as Insta360.
                    # The (card root) bucket is intentionally skipped here so the
                    # inherit logic at the end can copy a complete (lens-aware)
                    # Device from a sibling bucket.
                    if dev is None or not dev.brand:
                        continue
                    if dev.brand.lower() != "insta360":
                        continue
                    bucket_device[sub] = Device(
                        brand=fileinfo_data["brand"] or dev.brand,
                        model=fileinfo_data.get("model"),
                        lens=dev.lens,
                        serial=fileinfo_data.get("serial"),
                        source="detected+fileinfo",
                    )

        # Phase 2b: per-bucket MP4 atom probe (still useful for GoPro/DJI).
        for sub, fl in buckets.items():
            samples = [f for f in fl if f.suffix.lower() in _PROBE_EXTS][:_PROBE_SAMPLE_PER_BUCKET]
            merged: dict[str, str | None] = {}
            for s in samples:
                fields = mp4probe.extract_camera_fields(mp4probe.probe(s))
                for k, v in fields.items():
                    if v and not merged.get(k):
                        merged[k] = v
            if not merged:
                continue
            probe_findings[sub] = merged
            base = bucket_device.get(sub)
            # Cross-check brand: if Phase 1 found one and it disagrees, flag ambiguous.
            probed_brand = merged.get("brand")
            if base is not None and base.brand and probed_brand and probed_brand.lower() != base.brand.lower():
                probe_notes[sub].append(
                    f"brand mismatch: filename heuristic={base.brand!r}, probe={probed_brand!r}"
                )
                continue  # don't trust either — keep Phase 1 result
            # Skip the atom probe's results if fileinfo already supplied a model
            # (otherwise we'd downgrade Insta360 'OneRS' to None).
            if base and base.model and not merged.get("model"):
                continue
            new_brand = (base.brand if base else None) or probed_brand
            new_lens = base.lens if base else None
            if new_brand:
                bucket_device[sub] = Device(
                    brand=new_brand,
                    model=merged.get("model") or (base.model if base else None),
                    lens=new_lens,
                    serial=merged.get("serial") or (base.serial if base else None),
                    source=("probed" if base is None or base.brand is None else "detected+probed"),
                )

    # For _root files: if all non-_root buckets agree on a single Device, inherit it.
    if ROOT_SOURCE_SUBDIR in buckets and bucket_device.get(ROOT_SOURCE_SUBDIR) is None and override is None and not args.no_detect:
        non_root_devices = {bucket_device[k].key() for k in buckets
                            if k != ROOT_SOURCE_SUBDIR and bucket_device[k] is not None}
        if len(non_root_devices) == 1:
            # Use the same Device instance for consistency.
            for k in buckets:
                if k != ROOT_SOURCE_SUBDIR and bucket_device[k] is not None:
                    inherited = bucket_device[k]
                    bucket_device[ROOT_SOURCE_SUBDIR] = Device(
                        brand=inherited.brand, model=inherited.model,
                        lens=inherited.lens, serial=inherited.serial,
                        source="inherited",
                    )
                    break

    # Build the plan.
    plan: list[tuple[Path, str]] = []
    occurrences: dict[str, list[Path]] = defaultdict(list)
    oversized: list[tuple[Path, str, int]] = []
    for f in files:
        sub = source_subdir_for(src_root, f)
        dev = bucket_device.get(sub)
        blob = plan_blob_name(args.container, dev, sub, f.name)
        plan.append((f, blob))
        occurrences[blob].append(f)
        if len(blob.encode("utf-8")) > BLOB_NAME_MAX_BYTES:
            oversized.append((f, blob, len(blob.encode("utf-8"))))

    # --- aggregate per-(profile, source-subdir) for the summary view --------
    # profile = deviceKey (or "unknown"); sub = source subdir as it appears in the blob path
    sizes: dict[Path, int] = {}
    for f in files:
        try:
            sizes[f] = f.stat().st_size
        except OSError:
            sizes[f] = 0
    total_bytes = sum(sizes.values())

    # profile -> list of (sub_label, count, bytes, source_tag, sample_name)
    by_profile: dict[str, list[tuple[str, int, int, str, str]]] = defaultdict(list)
    profile_total: dict[str, tuple[int, int]] = {}  # profile -> (count, bytes)
    for sub in sorted(buckets.keys()):
        dev = bucket_device.get(sub)
        if dev is None or dev.brand is None:
            profile = "unknown"
            source_tag = (dev.source if dev else "no-match")
        else:
            profile = dev.key() or "unknown"
            source_tag = dev.source
        fl = buckets[sub]
        count = len(fl)
        size = sum(sizes[f] for f in fl)
        sub_label = sub if sub else "(card root)"
        sample = fl[0].name if fl else ""
        by_profile[profile].append((sub_label, count, size, source_tag, sample))
        pc, pb = profile_total.get(profile, (0, 0))
        profile_total[profile] = (pc + count, pb + size)

    conflicts = {k: v for k, v in occurrences.items() if len(v) > 1}
    needs_attention: list[str] = []
    if conflicts:
        needs_attention.append(f"{len(conflicts)} blob-name conflict(s)")
    if oversized:
        needs_attention.append(f"{len(oversized)} blob name(s) over {BLOB_NAME_MAX_BYTES} bytes")
    if "unknown" in profile_total:
        c, _ = profile_total["unknown"]
        needs_attention.append(f"{c} file(s) bucket as 'unknown'")

    # --- output: SUMMARY (the actual plan) ---------------------------------
    print()
    print(f"source     : {src_root}")
    print(f"container  : {args.container}")
    print(f"total      : {len(plan)} file(s), {format_size(total_bytes)}")
    print()
    print("Filing plan:")
    # Sort profiles: real profiles first (alpha), 'unknown' last.
    profiles_sorted = sorted(
        profile_total.keys(),
        key=lambda p: (p == "unknown", p),
    )
    for profile in profiles_sorted:
        c, b = profile_total[profile]
        entries = by_profile[profile]
        # Source tag: pick one if all sources agree, else 'mixed'.
        # Treat 'inherited' as equivalent to its origin (detected / detected+probed / probed),
        # because they describe the same fingerprint with different provenance.
        canonical_sources = {s.replace("inherited", "detected") for _, _, _, s, _ in entries
                             if s != "inherited"} or {"detected"}
        if all(e[3] in ("inherited",) for e in entries):
            canonical_sources = {"inherited"}
        src_tag = next(iter(canonical_sources)) if len(canonical_sources) == 1 else "mixed"
        print(f"  {profile:<40} {c:>5} file(s)  {format_size(b):>10}   [{src_tag}]")
        # Only break down subdirs when there's more than one, or when it's 'unknown'.
        if len(entries) > 1 or profile == "unknown":
            for sub_label, sc, sb, _src, sample in entries:
                bullet = f"    └─ {sub_label + '/':<28}"
                detail = f"{sc:>5} file(s)  {format_size(sb):>10}"
                hint = f"  (e.g. {sample})" if sc == 1 else ""
                print(f"{bullet}{detail}{hint}")

    # Per-bucket probe notes (brand mismatches etc.) — surfaced inline above
    # is messy because probe_notes is keyed by sub; print them as a separate
    # block instead.
    flat_notes = [(sub, n) for sub, ns in probe_notes.items() for n in ns]
    if flat_notes:
        print()
        print("Probe notes:")
        for sub, note in flat_notes:
            sub_disp = sub if sub else "(card root)"
            print(f"  - [{sub_disp}] {note}")

    if conflicts or oversized:
        print()
        print("Issues:")
        for blob, srcs in conflicts.items():
            print(f"  CONFLICT  {blob}")
            for s in srcs:
                print(f"            <- {s.relative_to(src_root)}")
        for f, blob, size in oversized:
            print(f"  OVERSIZED ({size} bytes)  {blob}")

    print()
    if needs_attention:
        print("Needs attention: " + "; ".join(needs_attention))
    else:
        print("Looks clean.  No conflicts, no unknowns, no oversized names.")

    # --- verbose: per-file listing ----------------------------------------
    if args.verbose:
        print()
        print("Per-file mapping:")
        width = max(len(str(f.relative_to(src_root))) for f, _ in plan)
        for f, blob in plan:
            rel = str(f.relative_to(src_root))
            print(f"  {rel:<{width}}  ->  {blob}")

    return 2 if (conflicts or oversized) else 0


def format_size(num_bytes: int) -> str:
    """Human-readable size — short form (1.4 KB, 16.4 GB) using 1024 base."""
    if num_bytes < 1024:
        return f"{num_bytes} B"
    for unit in ("KB", "MB", "GB", "TB"):
        num_bytes /= 1024
        if num_bytes < 1024:
            return f"{num_bytes:.1f} {unit}"
    return f"{num_bytes:.1f} PB"


if __name__ == "__main__":
    sys.exit(main())
