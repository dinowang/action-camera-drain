package net.dinowang.actioncameradrain.domain.filing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceKeyTest {
    @Test fun normalizeSegmentBasics() {
        assertEquals("hero11-black", DeviceKey.normalizeSegment("HERO11 Black"))
        assertEquals("one-rs", DeviceKey.normalizeSegment("ONE RS"))
        assertEquals("gopro", DeviceKey.normalizeSegment("GoPro"))
    }

    @Test fun deviceIdShortDeterministic() {
        val a = DeviceKey.deviceIdShort("C3251234567890")
        val b = DeviceKey.deviceIdShort("C3251234567890")
        assertEquals(a, b)
        assertEquals(8, a.length)
        assertTrue(a.all { it in "abcdefghijklmnopqrstuvwxyz234567" })
    }

    @Test fun deviceKeyComposition() {
        assertEquals("gopro", Device(brand = "GoPro").key())
        assertEquals("gopro-hero11-black", Device(brand = "GoPro", model = "HERO11 Black").key())
        val full = Device(brand = "Insta360", model = "ONE RS", lens = "360", serial = "ABC123").key()!!
        assertTrue(full, full.startsWith("insta360-one-rs-lens-360-"))
        assertEquals(8, full.substringAfterLast('-').length)
    }

    @Test fun stripRedundantBrandPrefix() {
        // model "Insta360 OneRS" should not yield "insta360-insta360-oners"
        val k = Device(brand = "Insta360", model = "Insta360 OneRS").key()
        assertEquals("insta360-oners", k)
    }

    @Test fun unknownBrandReturnsNull() {
        assertNull(Device(brand = null).key())
        assertNull(Device(brand = "").key())
    }
}

class DcimDetectorTest {
    @Test fun goproByFilename() {
        val d = DcimDetector.detect("100GOPRO", listOf("GH010001.MP4", "GH010001.LRV"))
        assertEquals("GoPro", d?.brand)
    }

    @Test fun goproByFolderSuffix() {
        val d = DcimDetector.detect("100GOPRO", listOf("RANDOM.MP4"))
        assertEquals("GoPro", d?.brand)
    }

    @Test fun insta360_360Lens() {
        val d = DcimDetector.detect("Camera01", listOf("VID_20240101_120000_00_001.insv"))
        assertEquals("Insta360", d?.brand)
        assertEquals("360", d?.lens)
    }

    @Test fun insta360FlatLens() {
        val d = DcimDetector.detect("Camera01", listOf("VID_20240101_120000_01_001.mp4"))
        assertEquals("Insta360", d?.brand)
        assertEquals("flat", d?.lens)
    }

    @Test fun djiByFilename() {
        val d = DcimDetector.detect("100MEDIA", listOf("DJI_0001.MP4"))
        assertEquals("DJI", d?.brand)
    }

    @Test fun unknown() {
        assertNull(DcimDetector.detect("MISC", listOf("note.txt")))
    }
}

class IngestPlannerTest {

    private fun plan(vararg files: Pair<String, ByteArray?>): IngestPlan {
        val list = files.map { (path, bytes) ->
            FakeMediaFile(pathSegments = path.split("/"), bytes = bytes ?: ByteArray(0))
        }
        val planner = IngestPlanner(enableMp4Probe = false)
        return planner.plan(FakeMediaSource("card", list))
    }

    @Test fun goproPlanBasic() {
        val p = plan(
            "DCIM/100GOPRO/GH010001.MP4" to null,
            "DCIM/100GOPRO/GH010001.LRV" to null,
        )
        assertEquals(2, p.fileCount)
        val first = p.items.first()
        assertEquals("gopro/100GOPRO/GH010001.MP4", first.blobName)
    }

    @Test fun hiddenFilesAreFiltered() {
        val p = plan(
            "DCIM/100GOPRO/GH010001.MP4" to null,
            ".Spotlight-V100/foo" to null,
            "DCIM/.thumb/_x" to null,
        )
        assertEquals(1, p.fileCount)
    }

    @Test fun rootInheritsWhenSingleDevice() {
        val p = plan(
            "DCIM/100GOPRO/GH010001.MP4" to null,
            "STRAY.MP4" to null,
        )
        val strayItem = p.items.first { it.file.name == "STRAY.MP4" }
        assertTrue(strayItem.blobName.startsWith("gopro/"))
        assertEquals(Device.Source.INHERITED, strayItem.device?.source)
    }

    @Test fun conflictsAreDetected() {
        val p = plan(
            "DCIM/100GOPRO/GH010001.MP4" to null,
            "DCIM/101GOPRO/GH010001.MP4" to null,
        )
        // Different source subdirs → different blob names, no conflict.
        assertEquals(0, p.conflicts.size)
    }

    @Test fun blobPathOmitsSubdirAtRoot() {
        val p = plan("STRAY.MP4" to null)
        val item = p.items.first()
        assertEquals("unknown/STRAY.MP4", item.blobName)
    }

    @Test fun dcimAtRootIsStripped() {
        val p = plan("DCIM/100GOPRO/GH010001.MP4" to null)
        assertEquals("gopro/100GOPRO/GH010001.MP4", p.items.first().blobName)
    }
}
