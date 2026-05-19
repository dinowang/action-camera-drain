package net.dinowang.actioncameradrain.domain.filing

import java.security.MessageDigest

/**
 * Ported from filing-poc/filing.py. Deterministic deviceKey construction.
 */
object DeviceKey {

    private val NORMALIZE_DROP = Regex("[^a-z0-9-]+")
    private val NORMALIZE_COLLAPSE = Regex("-{2,}")

    /** Lowercase, collapse whitespace/underscores to dashes, strip leading/trailing dashes. */
    fun normalizeSegment(value: String): String {
        var s = value.trim().lowercase()
        s = s.replace('_', '-').replace(' ', '-')
        s = NORMALIZE_DROP.replace(s, "-")
        s = NORMALIZE_COLLAPSE.replace(s, "-")
        return s.trim('-')
    }

    /** sha256(serial) -> base32 (lowercase, no padding) -> first 8 chars. */
    fun deviceIdShort(serial: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(serial.toByteArray(Charsets.UTF_8))
        return base32Lower(digest).take(8)
    }

    private const val B32_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    private fun base32Lower(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0L
        var bitsLeft = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff).toLong()
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                val idx = ((buffer shr bitsLeft) and 0x1f).toInt()
                sb.append(B32_ALPHABET[idx])
            }
        }
        if (bitsLeft > 0) {
            val idx = ((buffer shl (5 - bitsLeft)) and 0x1f).toInt()
            sb.append(B32_ALPHABET[idx])
        }
        return sb.toString()
    }
}

/**
 * Detected device for a bucket. `brand == null` means "unknown" → goes under `unknown/`.
 */
data class Device(
    val brand: String? = null,
    val model: String? = null,
    val lens: String? = null,
    val serial: String? = null,
    val source: Source = Source.DETECTED,
) {
    enum class Source(val tag: String) {
        DETECTED("detected"),
        OVERRIDE("override"),
        INHERITED("inherited"),
        DETECTED_PROBED("detected+probed"),
        DETECTED_FILEINFO("detected+fileinfo"),
        PROBED("probed"),
        NO_MATCH("no-match"),
    }

    /** deviceKey string for blob path; null means unknown. */
    fun key(): String? {
        if (brand.isNullOrBlank()) return null
        val brandSeg = DeviceKey.normalizeSegment(brand)
        val parts = mutableListOf(brandSeg)
        if (!model.isNullOrBlank()) {
            var modelSeg = DeviceKey.normalizeSegment(model)
            if (modelSeg.startsWith("$brandSeg-")) {
                modelSeg = modelSeg.substring(brandSeg.length + 1)
            } else if (modelSeg == brandSeg) {
                modelSeg = ""
            }
            if (modelSeg.isNotEmpty()) parts += modelSeg
        }
        if (!lens.isNullOrBlank()) parts += "lens-${DeviceKey.normalizeSegment(lens)}"
        if (!serial.isNullOrBlank()) parts += DeviceKey.deviceIdShort(serial)
        return parts.filter { it.isNotEmpty() }.joinToString("-")
    }
}
