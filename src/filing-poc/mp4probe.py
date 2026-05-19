"""MP4 metadata probe — pull make / model / firmware / serial from a single file.

Only reads as much of the file as needed to walk to and through the `moov` atom.
For a typical action-camera MP4 that's:
  * one 8-byte read for ftyp header
  * one seek + 8-byte read per top-level box until moov is found
  * one read of the moov atom contents (typically 50 KB – a few MB; GoPro is
    larger because of GPMF data)

No third-party dependencies. Targets:

  * Apple-style ItemList in `moov/udta/meta` (handler `mdta`) — the modern
    location used by GoPro, Insta360, DJI Osmo and many phones.
  * Classic 4-cc strings in `moov/udta` (`©mak`, `©mod`, `©swr`) — used by older
    firmware on most cameras.

Anything else (e.g. proprietary `udta/free` blobs) is ignored. Failures are
silent: if a box is malformed we just stop parsing it.
"""
from __future__ import annotations

import struct
from pathlib import Path

# Hard cap: refuse to read more than this from a single moov atom. Mostly a
# safety net against pathological files; real cameras stay well below.
_MOOV_READ_CAP = 16 * 1024 * 1024  # 16 MiB

# Box header is 8 bytes (or 16 with largesize). When size==0 the box extends to
# EOF; that's only legal for the *last* top-level box and we don't care because
# moov is never that box on cameras.


def _read_header(f) -> tuple[int, str, int] | None:
    """(content_size, type, header_size) or None at EOF."""
    hdr = f.read(8)
    if len(hdr) < 8:
        return None
    size = struct.unpack(">I", hdr[:4])[0]
    btype = hdr[4:8].decode("latin1", errors="replace")
    if size == 1:
        ext = f.read(8)
        if len(ext) < 8:
            return None
        size = struct.unpack(">Q", ext)[0]
        return (size - 16, btype, 16)
    if size == 0:
        return (-1, btype, 8)  # to-EOF
    return (size - 8, btype, 8)


def _iter_box_buf(buf: bytes):
    """Yield (type, body_bytes) over a buffer of concatenated boxes."""
    i = 0
    n = len(buf)
    while i + 8 <= n:
        size = struct.unpack(">I", buf[i : i + 4])[0]
        btype = buf[i + 4 : i + 8].decode("latin1", errors="replace")
        if size == 1:
            if i + 16 > n:
                return
            size = struct.unpack(">Q", buf[i + 8 : i + 16])[0]
            body_start = i + 16
        elif size == 0:
            yield btype, buf[i + 8 :]
            return
        else:
            body_start = i + 8
        if size < 8 or i + size > n:
            return
        yield btype, buf[body_start : i + size]
        i += size


# --- udta parsing ----------------------------------------------------------

def _parse_udta_string_box(body: bytes) -> str | None:
    """Apple-style ©xxx box: 2-byte size + 2-byte lang + UTF-8 string.

    Some firmware writes a plain null-terminated UTF-8 string instead; handle
    both by trying the structured form first and falling back.
    """
    if len(body) >= 4:
        slen = struct.unpack(">H", body[:2])[0]
        # lang code at body[2:4] — ignored
        if 4 + slen <= len(body):
            try:
                return body[4 : 4 + slen].decode("utf-8", errors="replace").rstrip("\x00").strip()
            except Exception:
                pass
    # Fallback: treat whole body as a null-terminated string.
    try:
        return body.split(b"\x00", 1)[0].decode("utf-8", errors="replace").strip() or None
    except Exception:
        return None


def _parse_udta(body: bytes) -> dict[str, str]:
    out: dict[str, str] = {}
    for btype, content in _iter_box_buf(body):
        # ©xxx style 4-cc — bytes 0xA9 prefix
        if btype.startswith("\xa9"):
            val = _parse_udta_string_box(content)
            if val:
                out[f"udta:{btype}"] = val
    return out


# --- meta parsing (mdta keys + ilst) ---------------------------------------

def _parse_meta(body: bytes) -> dict[str, str]:
    """Parse `moov/udta/meta` or `moov/meta` with handler `mdta`.

    Structure: 4 bytes (version+flags) THEN child boxes: hdlr, keys, ilst.
    But: GoPro / DJI / Insta360 write the `meta` box *without* the leading
    version+flags (it's not standards-compliant but extremely common). Detect
    by peeking: if the first 4 bytes are not a sane atom size we know the
    leading 4 bytes are version+flags and we skip them.
    """
    if len(body) < 8:
        return {}
    # Heuristic: if first 4 bytes interpreted as a size are sane (>= 8 and
    # <= len(body)) and the next 4 bytes are a printable type, no leading
    # version/flags. Otherwise skip 4.
    first_size = struct.unpack(">I", body[:4])[0]
    first_type = body[4:8].decode("latin1", errors="replace")
    if 8 <= first_size <= len(body) and first_type.isascii() and first_type.isprintable():
        offset = 0
    else:
        offset = 4

    keys: list[str] = []  # 1-indexed in ilst references; keys[0] is index 1
    ilst_body: bytes | None = None
    handler_type: str | None = None
    for btype, content in _iter_box_buf(body[offset:]):
        if btype == "hdlr":
            # hdlr: 4 (vflags) + 4 (predefined) + 4 (handler_type) + 12 (reserved) + name
            if len(content) >= 12:
                handler_type = content[8:12].decode("latin1", errors="replace")
        elif btype == "keys":
            keys = _parse_keys_box(content)
        elif btype == "ilst":
            ilst_body = content

    if handler_type and handler_type != "mdta":
        # Not the Apple/mdta variant — skip
        return {}
    if not keys or ilst_body is None:
        return {}
    return _parse_ilst(ilst_body, keys)


def _parse_keys_box(content: bytes) -> list[str]:
    """keys box: 4-byte version+flags, 4-byte entry count, then entries:
       4-byte size, 4-byte namespace, (size-8) bytes key name."""
    if len(content) < 8:
        return []
    count = struct.unpack(">I", content[4:8])[0]
    keys: list[str] = []
    i = 8
    while len(keys) < count and i + 8 <= len(content):
        size = struct.unpack(">I", content[i : i + 4])[0]
        # namespace = content[i+4:i+8]  # e.g. 'mdta'
        if size < 8 or i + size > len(content):
            break
        name = content[i + 8 : i + size].decode("utf-8", errors="replace")
        keys.append(name)
        i += size
    return keys


def _parse_ilst(content: bytes, keys: list[str]) -> dict[str, str]:
    """ilst contains entries whose container type is the 1-based index into
    keys (encoded as a 4-byte big-endian integer used as the type-cc). Inside
    each entry is a `data` atom whose payload is the value.
    """
    out: dict[str, str] = {}
    i = 0
    while i + 8 <= len(content):
        size = struct.unpack(">I", content[i : i + 4])[0]
        type_bytes = content[i + 4 : i + 8]
        if size < 8 or i + size > len(content):
            break
        idx = struct.unpack(">I", type_bytes)[0]
        entry_body = content[i + 8 : i + size]
        # Inside entry: one or more atoms, look for `data`.
        for inner_t, inner_c in _iter_box_buf(entry_body):
            if inner_t == "data":
                # data atom: 4 (type indicator) + 4 (locale) + payload
                if len(inner_c) >= 8:
                    payload = inner_c[8:]
                    try:
                        val = payload.decode("utf-8", errors="replace").rstrip("\x00").strip()
                    except Exception:
                        val = ""
                    if val and 1 <= idx <= len(keys):
                        out[keys[idx - 1]] = val
                break
        i += size
    return out


# --- top-level ------------------------------------------------------------


def probe(path: Path) -> dict[str, str]:
    """Walk an MP4-shaped file and return its metadata key/value bag.

    Reads only the box headers + the moov body, NOT the mdat (video) payload.
    Returns {} if the file isn't MP4-shaped or has no metadata atoms we know.
    """
    try:
        with path.open("rb") as f:
            # Walk top-level looking for moov.
            moov_body: bytes | None = None
            while True:
                hdr = _read_header(f)
                if hdr is None:
                    break
                content_size, btype, _ = hdr
                if btype == "moov":
                    if content_size < 0 or content_size > _MOOV_READ_CAP:
                        return {}
                    moov_body = f.read(content_size)
                    break
                # Skip non-moov box.
                if content_size < 0:
                    break
                f.seek(content_size, 1)
            if moov_body is None:
                return {}

        out: dict[str, str] = {}
        # Look for udta and meta within moov.
        for btype, content in _iter_box_buf(moov_body):
            if btype == "udta":
                out.update(_parse_udta(content))
                # meta may be nested under udta
                for inner_t, inner_c in _iter_box_buf(content):
                    if inner_t == "meta":
                        out.update(_parse_meta(inner_c))
            elif btype == "meta":
                out.update(_parse_meta(content))
        return out
    except (OSError, struct.error):
        return {}


# --- brand normalization --------------------------------------------------

# Lowercase substring -> canonical brand name.
_BRAND_MAP = (
    ("gopro", "GoPro"),
    ("arashi vision", "Insta360"),
    ("insta360", "Insta360"),
    ("dji", "DJI"),
    ("sz dji", "DJI"),
    ("sony", "Sony"),
)


def normalize_brand(raw: str | None) -> str | None:
    if not raw:
        return None
    low = raw.strip().lower()
    for needle, canon in _BRAND_MAP:
        if needle in low:
            return canon
    return None


# Field-name fallbacks; first hit wins.
_MAKE_KEYS = (
    "com.apple.quicktime.make",
    "udta:\xa9mak",
    "com.gopro.make",
)
_MODEL_KEYS = (
    "com.apple.quicktime.model",
    "udta:\xa9mod",
    "com.gopro.model",
)
_FIRMWARE_KEYS = (
    "com.apple.quicktime.software",
    "udta:\xa9swr",
    "com.gopro.firmware",
)
_SERIAL_KEYS = (
    "com.apple.quicktime.cameraserialnumber",
    "com.gopro.serial",
)


def _first(d: dict[str, str], keys) -> str | None:
    for k in keys:
        if k in d and d[k]:
            return d[k]
    return None


def extract_camera_fields(meta: dict[str, str]) -> dict[str, str | None]:
    """Return a small dict with normalized brand + raw model/firmware/serial."""
    make_raw = _first(meta, _MAKE_KEYS)
    return {
        "brand": normalize_brand(make_raw),
        "make_raw": make_raw,
        "model": _first(meta, _MODEL_KEYS),
        "firmware": _first(meta, _FIRMWARE_KEYS),
        "serial": _first(meta, _SERIAL_KEYS),
    }
