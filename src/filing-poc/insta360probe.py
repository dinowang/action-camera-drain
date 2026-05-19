"""Insta360 `fileinfo_list.list` probe.

Insta360 cameras maintain an index file on the card at `/DCIM/fileinfo_list.list`
(or sometimes one level up, depending on the camera/firmware). It is a
protobuf-shaped binary blob with one record per media file, each containing —
among other things — the camera **serial number**, **model name**, and
**firmware version** as plain ASCII strings.

This is a different code path from the MP4 atom probe in `mp4probe.py`. The
ONE RS family writes a vendor-private `udta/AMBA` blob with no readable model
string; the `fileinfo_list.list` index is far easier to parse and gives us the
exact same information.

The reader here doesn't do full protobuf parsing. It locates the model field
(`0x12 <len> 'Insta360 ...'`), then reads the serial field that precedes it
(`0x0a <len> <ascii>`) and the firmware field that follows it
(`0x1a <len> <ascii>`). This matches the observed layout on ONE RS firmware
v2.0.11_build3; any future records that don't fit this layout are skipped.
"""
from __future__ import annotations

from collections import Counter
from pathlib import Path


# Standard locations a card might place the index at.
_FILEINFO_NAMES = ("fileinfo_list.list",)
_FILEINFO_PARENTS = (".", "DCIM")


def _read_lp(data: bytes, off: int):
    """At `off`, expect a 1-byte length n (1..127) followed by n printable ASCII.
    Returns (value_bytes, next_off) or None."""
    if off >= len(data):
        return None
    n = data[off]
    if not (1 <= n <= 127):
        return None
    end = off + 1 + n
    if end > len(data):
        return None
    val = data[off + 1 : end]
    if not all(0x20 <= b <= 0x7e for b in val):
        return None
    return val, end


def _scan_records(data: bytes):
    """Yield (serial, model, firmware) for each protobuf-style record found.

    Anchored on the model field because that's the most distinctive (starts
    with the literal `Insta360`). Each yielded triple may have None entries
    if the corresponding adjacent field couldn't be parsed.
    """
    i = 0
    while True:
        j = data.find(b"\x12", i)
        if j < 0:
            return
        i = j + 1
        got = _read_lp(data, j + 1)
        if got is None:
            continue
        model_bytes, after_model = got
        if not model_bytes.startswith(b"Insta360"):
            continue
        # Serial: a field-1 (0x0a) record whose value ends exactly at our 0x12.
        serial = None
        for back in range(2, 64):
            pos = j - back
            if pos < 0:
                break
            if data[pos] != 0x0a:
                continue
            cand = _read_lp(data, pos + 1)
            if cand and pos + 2 + len(cand[0]) == j:
                serial = cand[0].decode("ascii", errors="replace")
                break
        # Firmware: a field-3 (0x1a) record immediately after the model.
        firmware = None
        if after_model < len(data) and data[after_model] == 0x1a:
            cand = _read_lp(data, after_model + 1)
            if cand:
                firmware = cand[0].decode("ascii", errors="replace")
        yield (serial, model_bytes.decode("ascii", errors="replace"), firmware)


def find_fileinfo(src_root: Path) -> Path | None:
    """Locate `fileinfo_list.list` near the given source root, if any."""
    for parent in _FILEINFO_PARENTS:
        for name in _FILEINFO_NAMES:
            candidate = src_root / parent / name
            if candidate.is_file():
                return candidate
    return None


def probe(path: Path) -> dict:
    """Open a `fileinfo_list.list` and return the dominant per-record values.

    Returns {"brand", "model", "firmware", "serial", "record_count"}; any field
    we couldn't extract is None.
    """
    try:
        data = path.read_bytes()
    except OSError:
        return {}

    serials, models, firmwares = Counter(), Counter(), Counter()
    n = 0
    for s, m, fw in _scan_records(data):
        n += 1
        if s:
            serials[s] += 1
        if m:
            models[m] += 1
        if fw:
            firmwares[fw] += 1
    if n == 0:
        return {}

    pick = lambda c: c.most_common(1)[0][0] if c else None
    model = pick(models)
    return {
        "brand": "Insta360" if model and model.startswith("Insta360") else None,
        "model": model,
        "firmware": pick(firmwares),
        "serial": pick(serials),
        "record_count": n,
    }
