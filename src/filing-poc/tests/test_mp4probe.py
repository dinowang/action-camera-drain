"""Unit tests for mp4probe.

We don't ship a real MP4 sample (gigabytes, copyrighted, brand-specific). Instead
we synthesise tiny MP4-shaped files with exactly the metadata atoms we care
about and verify the probe reads them back correctly.

Run: python3 -m unittest discover -s tests
"""
from __future__ import annotations

import struct
import tempfile
import unittest
from pathlib import Path

# Allow running from the package dir directly.
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import mp4probe  # noqa: E402


# --- box builder helpers --------------------------------------------------

def _box(btype: bytes, body: bytes) -> bytes:
    assert len(btype) == 4
    return struct.pack(">I", len(body) + 8) + btype + body


def _ftyp() -> bytes:
    # major='isom', minor=512, compat='isom','iso2','avc1','mp41'
    return _box(b"ftyp", b"isom" + struct.pack(">I", 512) + b"isomiso2avc1mp41")


def _mdat(payload: bytes = b"\x00" * 16) -> bytes:
    return _box(b"mdat", payload)


def _mvhd() -> bytes:
    # version 0 mvhd is 4 + 4*3 + 4*2 + 2 + 10 + 36 + 24 + 4 = 100 byte body
    return _box(b"mvhd", b"\x00" * 100)


def _classic_udta(make: str | None = None,
                  model: str | None = None,
                  firmware: str | None = None) -> bytes:
    """Build a `udta` box with Apple-style ©mak / ©mod / ©swr children.

    The encoding inside each ©xxx child is:
        2-byte size, 2-byte language packed code, UTF-8 bytes.
    """
    children = b""
    fields = [(b"\xa9mak", make), (b"\xa9mod", model), (b"\xa9swr", firmware)]
    for code, val in fields:
        if val is None:
            continue
        enc = val.encode("utf-8")
        body = struct.pack(">HH", len(enc), 0x55c4) + enc  # lang = und
        children += _box(code, body)
    return _box(b"udta", children)


def _mdta_meta(fields: dict[str, str]) -> bytes:
    """Build a `meta` box with handler 'mdta', a keys table, and an ilst.

    GoPro / DJI / many other firmwares write the meta box WITHOUT the leading
    4 bytes of version+flags — we replicate that here because that's the form
    the probe needs to handle in practice.
    """
    # hdlr: vflags(4) + predefined(4) + handler_type(4='mdta') + reserved(12) + name(null)
    hdlr_body = (
        b"\x00\x00\x00\x00"  # vflags
        + b"\x00\x00\x00\x00"  # predefined
        + b"mdta"  # handler_type
        + b"\x00" * 12  # reserved
        + b"\x00"  # empty name (null-terminated)
    )
    hdlr = _box(b"hdlr", hdlr_body)

    # keys: vflags(4) + count(4) + entries (each: size(4) + namespace(4) + name)
    keys_body = b"\x00\x00\x00\x00" + struct.pack(">I", len(fields))
    for name in fields.keys():
        enc = name.encode("utf-8")
        entry = struct.pack(">I", 8 + len(enc)) + b"mdta" + enc
        keys_body += entry
    keys = _box(b"keys", keys_body)

    # ilst: each entry's type field is the 1-based index of its key.
    # Each entry contains a `data` atom: type(4)='\x00\x00\x00\x01' (utf-8) + locale(4) + payload.
    ilst_body = b""
    for i, val in enumerate(fields.values(), start=1):
        vbytes = val.encode("utf-8")
        data_body = struct.pack(">II", 1, 0) + vbytes
        data_atom = _box(b"data", data_body)
        entry_type = struct.pack(">I", i)
        ilst_body += struct.pack(">I", 8 + len(data_atom)) + entry_type + data_atom
    ilst = _box(b"ilst", ilst_body)

    # No vflags before the children: probe heuristic must detect this.
    return _box(b"meta", hdlr + keys + ilst)


def _build_moov(udta_body: bytes) -> bytes:
    return _box(b"moov", _mvhd() + udta_body)


def _write_mp4(path: Path, *, moov_at_end: bool, udta_body: bytes) -> None:
    """Write a tiny MP4-shaped file with moov either at the start or the end."""
    ftyp = _ftyp()
    mdat = _mdat()
    moov = _build_moov(udta_body)
    with path.open("wb") as f:
        f.write(ftyp)
        if moov_at_end:
            f.write(mdat)
            f.write(moov)
        else:
            f.write(moov)
            f.write(mdat)


# --- tests ----------------------------------------------------------------

class TestProbeClassicUdta(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_classic_udta_moov_at_end(self):
        """GoPro-style ©mak / ©mod / ©swr with moov at the end of the file."""
        p = self.dir / "test.mp4"
        udta = _classic_udta(make="GoPro", model="HERO11 Black",
                             firmware="H22.01.02.32.00")
        _write_mp4(p, moov_at_end=True, udta_body=udta)
        raw = mp4probe.probe(p)
        # Raw keys are prefixed with 'udta:' and include the ©.
        self.assertEqual(raw.get("udta:\xa9mak"), "GoPro")
        self.assertEqual(raw.get("udta:\xa9mod"), "HERO11 Black")
        self.assertEqual(raw.get("udta:\xa9swr"), "H22.01.02.32.00")

        fields = mp4probe.extract_camera_fields(raw)
        self.assertEqual(fields["brand"], "GoPro")
        self.assertEqual(fields["model"], "HERO11 Black")
        self.assertEqual(fields["firmware"], "H22.01.02.32.00")
        self.assertIsNone(fields["serial"])

    def test_classic_udta_moov_at_start(self):
        """Same data, moov at front of file (faststart-style)."""
        p = self.dir / "test.mp4"
        udta = _classic_udta(make="DJI", model="Osmo Action 4")
        _write_mp4(p, moov_at_end=False, udta_body=udta)
        fields = mp4probe.extract_camera_fields(mp4probe.probe(p))
        self.assertEqual(fields["brand"], "DJI")
        self.assertEqual(fields["model"], "Osmo Action 4")


class TestProbeMdta(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_apple_mdta_via_udta_meta(self):
        """mdta keys/ilst nested under moov/udta/meta (Apple convention)."""
        p = self.dir / "test.mp4"
        meta = _mdta_meta({
            "com.apple.quicktime.make": "GoPro",
            "com.apple.quicktime.model": "HERO13 Black",
            "com.apple.quicktime.software": "H23.01.00.01",
            "com.apple.quicktime.cameraserialnumber": "C3441234567890",
        })
        udta = _box(b"udta", meta)
        _write_mp4(p, moov_at_end=True, udta_body=udta)

        raw = mp4probe.probe(p)
        self.assertEqual(raw.get("com.apple.quicktime.make"), "GoPro")
        self.assertEqual(raw.get("com.apple.quicktime.model"), "HERO13 Black")

        fields = mp4probe.extract_camera_fields(raw)
        self.assertEqual(fields["brand"], "GoPro")
        self.assertEqual(fields["model"], "HERO13 Black")
        self.assertEqual(fields["firmware"], "H23.01.00.01")
        self.assertEqual(fields["serial"], "C3441234567890")

    def test_brand_variants_normalize(self):
        """make_raw 'Arashi Vision' / 'SZ DJI' / weird casing should normalize."""
        self.assertEqual(mp4probe.normalize_brand("Arashi Vision"), "Insta360")
        self.assertEqual(mp4probe.normalize_brand("GOPRO"), "GoPro")
        self.assertEqual(mp4probe.normalize_brand("gopro"), "GoPro")
        self.assertEqual(mp4probe.normalize_brand("SZ DJI"), "DJI")
        self.assertEqual(mp4probe.normalize_brand("DJI Inc."), "DJI")
        self.assertEqual(mp4probe.normalize_brand("Sony"), "Sony")
        self.assertIsNone(mp4probe.normalize_brand("AKASO Brave 7"))
        self.assertIsNone(mp4probe.normalize_brand(""))
        self.assertIsNone(mp4probe.normalize_brand(None))


class TestProbeRobust(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_empty_file_returns_empty(self):
        p = self.dir / "empty.mp4"
        p.write_bytes(b"")
        self.assertEqual(mp4probe.probe(p), {})

    def test_garbage_file_returns_empty(self):
        p = self.dir / "garbage.mp4"
        p.write_bytes(b"not an mp4 file" * 100)
        self.assertEqual(mp4probe.probe(p), {})

    def test_no_metadata_returns_empty(self):
        """File is MP4-shaped but has no udta or meta atoms."""
        p = self.dir / "naked.mp4"
        # moov with only mvhd, no udta
        moov = _box(b"moov", _mvhd())
        with p.open("wb") as f:
            f.write(_ftyp())
            f.write(_mdat())
            f.write(moov)
        self.assertEqual(mp4probe.probe(p), {})

    def test_extract_with_no_make_returns_none_brand(self):
        """Have model but no make: brand is None, model still surfaces."""
        p = self.dir / "no-make.mp4"
        udta = _classic_udta(model="Mystery Cam")
        _write_mp4(p, moov_at_end=True, udta_body=udta)
        fields = mp4probe.extract_camera_fields(mp4probe.probe(p))
        self.assertIsNone(fields["brand"])
        self.assertEqual(fields["model"], "Mystery Cam")


if __name__ == "__main__":
    unittest.main()
