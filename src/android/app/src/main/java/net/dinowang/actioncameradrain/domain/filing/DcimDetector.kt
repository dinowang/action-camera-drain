package net.dinowang.actioncameradrain.domain.filing

/**
 * Filename / folder pattern detection — ported from filing-poc/filing.py
 * (Phase 1; doesn't open files).
 */
object DcimDetector {

    private val GOPRO_FNAME = Regex("^(GH|GX|GP|GOPR|GS)\\d+\\.(MP4|JPG|LRV|THM)$", RegexOption.IGNORE_CASE)
    private val INSTA360_360_FNAME = Regex("^VID_\\d{8}_\\d{6}_(00|10)_\\d+\\.insv$", RegexOption.IGNORE_CASE)
    private val INSTA360_FLAT_FNAME = Regex("^(VID|LRV)_\\d{8}_\\d{6}_01_\\d+\\.mp4$", RegexOption.IGNORE_CASE)
    private val DJI_FNAME = Regex("^DJI_\\d+\\.(MP4|JPG)$", RegexOption.IGNORE_CASE)

    /** Return a Device for this bucket, or null if it doesn't look like a known camera. */
    fun detect(folderName: String, fileNames: List<String>): Device? {
        val exts = fileNames.mapTo(mutableSetOf()) { extOf(it).lowercase() }

        if (fileNames.any { GOPRO_FNAME.matches(it) }) return Device(brand = "GoPro")
        if (folderName.uppercase().endsWith("GOPRO")) return Device(brand = "GoPro")

        if (".insv" in exts || ".insp" in exts) return Device(brand = "Insta360", lens = "360")
        if (fileNames.any { INSTA360_FLAT_FNAME.matches(it) }) return Device(brand = "Insta360", lens = "flat")

        if (fileNames.any { DJI_FNAME.matches(it) }) return Device(brand = "DJI")

        return null
    }

    private fun extOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot < 0) "" else name.substring(dot)
    }
}
