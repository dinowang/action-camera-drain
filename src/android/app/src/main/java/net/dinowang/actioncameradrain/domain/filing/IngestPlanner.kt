package net.dinowang.actioncameradrain.domain.filing

/**
 * Builds an ingest plan (sourceFile -> blobName) from a [MediaSource], using
 * filename heuristics + optional MP4 / Insta360 fileinfo metadata.
 *
 * Ported from filing-poc/filing.py.
 */
class IngestPlanner(
    private val enableMp4Probe: Boolean = true,
    private val probeSamplesPerBucket: Int = 2,
) {

    companion object {
        const val BLOB_NAME_MAX_BYTES = 1024
        private const val TAG = "IngestPlanner"
        private val DCIM_DIR_NAMES = setOf("DCIM")
        private val PROBE_EXTS = setOf(".mp4", ".insv", ".mov")
        private const val ROOT_SUBDIR = ""
        private const val INSTA360_FILEINFO_NAME = "fileinfo_list.list"
        private val INSTA360_FILEINFO_PARENT_HINTS = listOf(emptyList<String>(), listOf("DCIM"))
    }

    fun plan(source: MediaSource): IngestPlan {
        val allFiles = source.files().filterNot { isHiddenPath(it) }.toList()
        android.util.Log.d(TAG, "plan: ${allFiles.size} files; rootLabel=${source.rootLabel}")
        // Insta360 catalog files exist purely for camera-side bookkeeping. Use
        // them for detection (Phase 2a) but don't queue them for upload — they
        // would otherwise spawn a lens-less bucket alongside Camera0X buckets.
        val files = allFiles.filterNot { it.name == INSTA360_FILEINFO_NAME }

        val buckets: LinkedHashMap<String, MutableList<MediaFile>> = linkedMapOf()
        for (f in files) buckets.getOrPut(sourceSubdirFor(f)) { mutableListOf() }.add(f)

        // Phase 1: filename pattern detection per bucket.
        val bucketDevice = LinkedHashMap<String, Device?>()
        for ((sub, fl) in buckets) {
            val sample = fl.take(3).map { it.name }
            val det = DcimDetector.detect(sub, fl.map { it.name })
            android.util.Log.d(TAG, "phase1: sub='$sub' count=${fl.size} sample=$sample -> $det")
            bucketDevice[sub] = det
        }

        // Phase 2a: Insta360 fileinfo_list.list (single shot per card).
        if (enableMp4Probe) {
            val fileinfo = findInsta360Fileinfo(allFiles)
            android.util.Log.d(TAG, "phase2a: fileinfo=${fileinfo?.pathSegments}")
            if (fileinfo != null) {
                val data = runCatching { fileinfo.openInputStream().use { it.readBytes() } }
                    .getOrNull()
                val result = data?.let { Insta360Probe.probe(it) }
                android.util.Log.d(TAG, "phase2a: probe bytes=${data?.size} result=$result")
                if (result != null && result.brand != null) {
                    for ((sub, dev) in bucketDevice.toMap()) {
                        // fileinfo_list.list is Insta360-exclusive; applying it
                        // to buckets Phase 1 left unknown is safe. Only skip
                        // when Phase 1 confidently identified a non-Insta360
                        // brand (e.g. GoPro filename prefix).
                        if (dev?.brand != null &&
                            !dev.brand.equals("Insta360", ignoreCase = true)
                        ) continue
                        bucketDevice[sub] = Device(
                            brand = result.brand,
                            model = result.model,
                            lens = dev?.lens,
                            serial = result.serial,
                            source = Device.Source.DETECTED_FILEINFO,
                        )
                    }
                }
            }

            // Phase 2b: per-bucket MP4 atom probe (GoPro / DJI).
            for ((sub, fl) in buckets) {
                val samples = fl.filter { extOf(it.name).lowercase() in PROBE_EXTS }
                    .take(probeSamplesPerBucket)
                if (samples.isEmpty()) continue
                val merged = mutableMapOf<String, String?>()
                for (s in samples) {
                    val fields = runCatching {
                        s.openInputStream().use { Mp4Probe.extractCameraFields(Mp4Probe.probe(it)) }
                    }.getOrNull() ?: continue
                    putIfMissing(merged, "brand", fields.brand)
                    putIfMissing(merged, "makeRaw", fields.makeRaw)
                    putIfMissing(merged, "model", fields.model)
                    putIfMissing(merged, "firmware", fields.firmware)
                    putIfMissing(merged, "serial", fields.serial)
                }
                if (merged.isEmpty()) continue
                val base = bucketDevice[sub]
                val probedBrand = merged["brand"]
                if (base != null && base.brand != null && probedBrand != null &&
                    !probedBrand.equals(base.brand, ignoreCase = true)
                ) continue
                // Don't downgrade an already-detected model (e.g. via fileinfo).
                if (base != null && base.model != null && merged["model"] == null) continue
                val newBrand = base?.brand ?: probedBrand
                if (newBrand != null) {
                    val src = if (base == null || base.brand == null) Device.Source.PROBED
                    else Device.Source.DETECTED_PROBED
                    bucketDevice[sub] = Device(
                        brand = newBrand,
                        model = merged["model"] ?: base?.model,
                        lens = base?.lens,
                        serial = merged["serial"] ?: base?.serial,
                        source = src,
                    )
                }
            }
        }

        // Root inheritance: if every non-root bucket points to the same Device, root inherits it.
        if (ROOT_SUBDIR in buckets && bucketDevice[ROOT_SUBDIR] == null) {
            val others = buckets.keys.filter { it != ROOT_SUBDIR }
                .mapNotNull { bucketDevice[it] }
            val keys = others.mapNotNull { it.key() }.toSet()
            if (keys.size == 1 && others.isNotEmpty()) {
                val origin = others.first()
                bucketDevice[ROOT_SUBDIR] = origin.copy(source = Device.Source.INHERITED)
            }
        }

        // Build per-file plan.
        val items = mutableListOf<PlannedItem>()
        val occurrences = linkedMapOf<String, MutableList<MediaFile>>()
        val effectiveBuckets = linkedMapOf<String, Device?>()
        for (f in files) {
            val sub = sourceSubdirFor(f)
            val bucketDev = bucketDevice[sub]
            val dev = refineForFile(bucketDev, f.name)
            val blob = planBlobName(dev, sub, f.name)
            items += PlannedItem(file = f, blobName = blob, device = dev, subdir = sub)
            occurrences.getOrPut(blob) { mutableListOf() }.add(f)
            // Reflect per-file lens splits in the displayed bucket map.
            val bucketKey = dev?.key() ?: "unknown"
            if (bucketKey !in effectiveBuckets) effectiveBuckets[bucketKey] = dev
        }

        val conflicts = occurrences.filterValues { it.size > 1 }
            .map { (blob, srcs) -> Conflict(blob, srcs.toList()) }
        val oversized = items.filter { it.blobName.toByteArray(Charsets.UTF_8).size > BLOB_NAME_MAX_BYTES }

        return IngestPlan(
            items = items,
            buckets = effectiveBuckets.toMap(),
            conflicts = conflicts,
            oversized = oversized,
        )
    }

    /**
     * Insta360 mixes flat-lens MP4s and 360-lens INSVs in the same folder. The
     * bucket-level lens tag is whichever fired first — refine per file so each
     * lens lands in its own bucket on the remote side.
     *
     * On the Insta360 OneRS both lenses share the `_00_` segment number, so
     * the *extension* — not the filename pattern — is the reliable signal:
     * `.insv`/`.insp` are 360-only; everything else under an Insta360 bucket
     * (mp4 / lrv main + proxy) belongs to the flat lens.
     */
    private fun refineForFile(bucketDev: Device?, fileName: String): Device? {
        if (bucketDev?.brand?.equals("Insta360", ignoreCase = true) != true) return bucketDev
        val ext = extOf(fileName).lowercase()
        val lensFromName: String = when (ext) {
            ".insv", ".insp" -> "360"
            else -> "flat"
        }
        return if (lensFromName != bucketDev.lens) bucketDev.copy(lens = lensFromName)
        else bucketDev
    }

    // -- helpers ------------------------------------------------------------

    private fun isHiddenPath(file: MediaFile): Boolean =
        file.pathSegments.any { it.startsWith(".") }

    private fun sourceSubdirFor(file: MediaFile): String {
        val parts = file.pathSegments.dropLast(1).toMutableList()
        if (parts.isNotEmpty() && parts.first() in DCIM_DIR_NAMES) parts.removeAt(0)
        return if (parts.isEmpty()) ROOT_SUBDIR else parts.first()
    }

    private fun findInsta360Fileinfo(files: List<MediaFile>): MediaFile? {
        for (hint in INSTA360_FILEINFO_PARENT_HINTS) {
            val match = files.firstOrNull { f ->
                f.name == INSTA360_FILEINFO_NAME && f.pathSegments.dropLast(1) == hint
            }
            if (match != null) return match
        }
        return null
    }

    private fun extOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot < 0) "" else name.substring(dot)
    }

    private fun putIfMissing(m: MutableMap<String, String?>, k: String, v: String?) {
        if (v.isNullOrBlank()) return
        if (m[k] == null) m[k] = v
    }

    private fun planBlobName(device: Device?, subdir: String, fileName: String): String {
        val bucket = device?.key() ?: "unknown"
        return if (subdir.isNotEmpty()) "$bucket/$subdir/$fileName"
        else "$bucket/$fileName"
    }
}

data class PlannedItem(
    val file: MediaFile,
    val blobName: String,
    val device: Device?,
    val subdir: String,
)

data class Conflict(val blobName: String, val sources: List<MediaFile>)

data class IngestPlan(
    val items: List<PlannedItem>,
    /** sub-dir -> resolved Device (or null = unknown). */
    val buckets: Map<String, Device?>,
    val conflicts: List<Conflict>,
    val oversized: List<PlannedItem>,
) {
    val totalBytes: Long get() = items.sumOf { it.file.sizeBytes }
    val fileCount: Int get() = items.size

    fun summaryByDevice(): Map<String, DeviceSummary> {
        val out = linkedMapOf<String, DeviceSummary>()
        for (item in items) {
            val key = item.device?.key() ?: "unknown"
            val cur = out[key] ?: DeviceSummary(key, 0, 0L, item.device)
            out[key] = cur.copy(fileCount = cur.fileCount + 1, totalBytes = cur.totalBytes + item.file.sizeBytes)
        }
        return out
    }
}

data class DeviceSummary(
    val deviceKey: String,
    val fileCount: Int,
    val totalBytes: Long,
    val device: Device?,
)
