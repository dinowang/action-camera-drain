"""Unit tests for insta360probe.

Builds a tiny synthetic `fileinfo_list.list`-shaped blob that matches the
protobuf-style record layout observed on real ONE RS cards.
"""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import insta360probe  # noqa: E402


def _record(serial: str, model: str, firmware: str) -> bytes:
    """One protobuf-shaped record: <0x0a><n1><serial><0x12><n2><model><0x1a><n3><firmware>."""
    return (
        b"\x0a" + bytes([len(serial)]) + serial.encode("ascii")
        + b"\x12" + bytes([len(model)]) + model.encode("ascii")
        + b"\x1a" + bytes([len(firmware)]) + firmware.encode("ascii")
        + b"\x00"  # control byte sentinel; not strictly required
    )


class TestInsta360Probe(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_extracts_serial_model_firmware(self):
        path = self.dir / "DCIM" / "fileinfo_list.list"
        path.parent.mkdir()
        path.write_bytes(_record("IRBEN2204WB7GK", "Insta360 OneRS", "v2.0.11_build3") * 3)
        result = insta360probe.probe(path)
        self.assertEqual(result["brand"], "Insta360")
        self.assertEqual(result["model"], "Insta360 OneRS")
        self.assertEqual(result["serial"], "IRBEN2204WB7GK")
        self.assertEqual(result["firmware"], "v2.0.11_build3")
        self.assertEqual(result["record_count"], 3)

    def test_majority_wins_when_records_disagree(self):
        path = self.dir / "fileinfo_list.list"
        body = (
            _record("MAJORITY1", "Insta360 X3", "fw-1") * 4
            + _record("ODDONE1", "Insta360 X3", "fw-1")
        )
        path.write_bytes(body)
        result = insta360probe.probe(path)
        self.assertEqual(result["serial"], "MAJORITY1")

    def test_other_brand_substring_does_not_match(self):
        path = self.dir / "fileinfo_list.list"
        # Model field that doesn't start with 'Insta360' should be skipped.
        body = b"\x0a\x04ABCD\x12\x05GoPro\x1a\x03v1\x00"
        path.write_bytes(body)
        self.assertEqual(insta360probe.probe(path), {})

    def test_find_fileinfo_at_card_root(self):
        path = self.dir / "fileinfo_list.list"
        path.write_bytes(b"")
        found = insta360probe.find_fileinfo(self.dir)
        self.assertEqual(found, path)

    def test_find_fileinfo_under_dcim(self):
        sub = self.dir / "DCIM"
        sub.mkdir()
        path = sub / "fileinfo_list.list"
        path.write_bytes(b"")
        found = insta360probe.find_fileinfo(self.dir)
        self.assertEqual(found, path)

    def test_find_returns_none_when_missing(self):
        self.assertIsNone(insta360probe.find_fileinfo(self.dir))

    def test_empty_file_returns_empty_dict(self):
        path = self.dir / "fileinfo_list.list"
        path.write_bytes(b"")
        self.assertEqual(insta360probe.probe(path), {})


if __name__ == "__main__":
    unittest.main()
