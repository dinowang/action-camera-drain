package net.dinowang.actioncameradrain.domain.filing

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MP4 metadata probe — ported from filing-poc/mp4probe.py.
 *
 * Walks an MP4-shaped stream and pulls make / model / firmware / serial from
 * either the `moov/udta` 4-cc atoms (©mak / ©mod / ©swr) or the modern
 * `moov/[udta/]meta` keys + ilst structure used by Apple, GoPro, DJI and Insta360.
 *
 * Reads only the moov atom (typically a few MB or less); never touches mdat.
 */
object Mp4Probe {

    private const val MOOV_READ_CAP = 16 * 1024 * 1024 // 16 MiB

    fun probe(input: InputStream): Map<String, String> {
        try {
            val ds = CountingInput(input)
            // Walk top-level looking for moov; skip everything else.
            while (true) {
                val hdr = readBoxHeader(ds) ?: break
                if (hdr.type == "moov") {
                    if (hdr.contentSize < 0 || hdr.contentSize > MOOV_READ_CAP) return emptyMap()
                    val body = ds.readFully(hdr.contentSize.toInt())
                    return parseMoov(body)
                }
                if (hdr.contentSize < 0) break
                ds.skipFully(hdr.contentSize)
            }
        } catch (_: Throwable) {
            return emptyMap()
        }
        return emptyMap()
    }

    private fun parseMoov(moov: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        iterBoxBuf(moov) { btype, content ->
            when (btype) {
                "udta" -> {
                    out.putAll(parseUdta(content))
                    iterBoxBuf(content) { inner, body ->
                        if (inner == "meta") out.putAll(parseMeta(body))
                    }
                }
                "meta" -> out.putAll(parseMeta(content))
            }
        }
        return out
    }

    private fun parseUdta(body: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        iterBoxBuf(body) { btype, content ->
            if (btype.isNotEmpty() && btype[0].code == 0xA9) {
                parseUdtaStringBox(content)?.let { out["udta:$btype"] = it }
            }
        }
        return out
    }

    private fun parseUdtaStringBox(body: ByteArray): String? {
        if (body.size >= 4) {
            val slen = u16be(body, 0)
            if (4 + slen <= body.size) {
                val s = String(body, 4, slen, Charsets.UTF_8).trimEnd('\u0000').trim()
                if (s.isNotEmpty()) return s
            }
        }
        val nullIdx = body.indexOf(0.toByte()).let { if (it < 0) body.size else it }
        val s = String(body, 0, nullIdx, Charsets.UTF_8).trim()
        return s.ifEmpty { null }
    }

    private fun parseMeta(body: ByteArray): Map<String, String> {
        if (body.size < 8) return emptyMap()
        // Heuristic: detect whether the leading 4 bytes are version+flags or
        // straight into child boxes.
        val firstSize = u32be(body, 0).toLong() and 0xffffffffL
        val firstType = safeAscii(body, 4, 4)
        val offset = if (firstSize in 8..body.size.toLong() &&
            firstType.all { c -> c.code in 0x20..0x7e }
        ) 0 else 4
        if (offset >= body.size) return emptyMap()

        var keys: List<String> = emptyList()
        var ilstBody: ByteArray? = null
        var handlerType: String? = null
        iterBoxBuf(body, offset) { btype, content ->
            when (btype) {
                "hdlr" -> if (content.size >= 12) handlerType = safeAscii(content, 8, 4)
                "keys" -> keys = parseKeysBox(content)
                "ilst" -> ilstBody = content
            }
        }
        if (handlerType != null && handlerType != "mdta") return emptyMap()
        val ilst = ilstBody ?: return emptyMap()
        if (keys.isEmpty()) return emptyMap()
        return parseIlst(ilst, keys)
    }

    private fun parseKeysBox(content: ByteArray): List<String> {
        if (content.size < 8) return emptyList()
        val count = u32be(content, 4)
        val out = mutableListOf<String>()
        var i = 8
        while (out.size < count && i + 8 <= content.size) {
            val size = u32be(content, i)
            if (size < 8 || i + size > content.size) break
            out += String(content, i + 8, size - 8, Charsets.UTF_8)
            i += size
        }
        return out
    }

    private fun parseIlst(content: ByteArray, keys: List<String>): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var i = 0
        while (i + 8 <= content.size) {
            val size = u32be(content, i)
            if (size < 8 || i + size > content.size) break
            val idx = u32be(content, i + 4)
            val entryBody = content.copyOfRange(i + 8, i + size)
            iterBoxBuf(entryBody) inner@{ innerT, innerC ->
                if (innerT == "data" && innerC.size >= 8) {
                    val payload = innerC.copyOfRange(8, innerC.size)
                    val v = String(payload, Charsets.UTF_8).trimEnd('\u0000').trim()
                    if (v.isNotEmpty() && idx in 1..keys.size) out[keys[idx - 1]] = v
                    // Take first `data` only; iterBoxBuf consumes all but we
                    // simply ignore subsequent ones because the assignment
                    // would just overwrite with another value if present.
                }
            }
            i += size
        }
        return out
    }

    // -- box walker ---------------------------------------------------------

    private data class BoxHeader(val contentSize: Long, val type: String)

    private fun readBoxHeader(ds: CountingInput): BoxHeader? {
        val hdr = ds.readUpTo(8)
        if (hdr.size < 8) return null
        val size = u32be(hdr, 0).toLong() and 0xffffffffL
        val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
        return when (size) {
            1L -> {
                val ext = ds.readUpTo(8)
                if (ext.size < 8) null
                else BoxHeader(ByteBuffer.wrap(ext).order(ByteOrder.BIG_ENDIAN).long - 16, type)
            }
            0L -> BoxHeader(-1, type)
            else -> BoxHeader(size - 8, type)
        }
    }

    private inline fun iterBoxBuf(buf: ByteArray, start: Int = 0, action: (String, ByteArray) -> Unit) {
        var i = start
        val n = buf.size
        while (i + 8 <= n) {
            var size = u32be(buf, i).toLong() and 0xffffffffL
            val type = String(buf, i + 4, 4, Charsets.ISO_8859_1)
            val bodyStart: Int
            if (size == 1L) {
                if (i + 16 > n) return
                size = ByteBuffer.wrap(buf, i + 8, 8).order(ByteOrder.BIG_ENDIAN).long
                bodyStart = i + 16
            } else if (size == 0L) {
                action(type, buf.copyOfRange(i + 8, n))
                return
            } else {
                bodyStart = i + 8
            }
            if (size < 8 || i + size > n) return
            action(type, buf.copyOfRange(bodyStart, (i + size).toInt()))
            i = (i + size).toInt()
        }
    }

    private fun u16be(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff)

    private fun u32be(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 24) or
            ((b[off + 1].toInt() and 0xff) shl 16) or
            ((b[off + 2].toInt() and 0xff) shl 8) or
            (b[off + 3].toInt() and 0xff)

    private fun safeAscii(b: ByteArray, off: Int, len: Int): String =
        String(b, off, len, Charsets.ISO_8859_1)

    // -- camera-field extraction -------------------------------------------

    private val MAKE_KEYS = listOf("com.apple.quicktime.make", "udta:\u00A9mak", "com.gopro.make")
    private val MODEL_KEYS = listOf("com.apple.quicktime.model", "udta:\u00A9mod", "com.gopro.model")
    private val FIRMWARE_KEYS = listOf("com.apple.quicktime.software", "udta:\u00A9swr", "com.gopro.firmware")
    private val SERIAL_KEYS = listOf("com.apple.quicktime.cameraserialnumber", "com.gopro.serial")

    private val BRAND_MAP = listOf(
        "gopro" to "GoPro",
        "arashi vision" to "Insta360",
        "insta360" to "Insta360",
        "dji" to "DJI",
        "sz dji" to "DJI",
        "sony" to "Sony",
    )

    fun normalizeBrand(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val low = raw.trim().lowercase()
        for ((needle, canon) in BRAND_MAP) if (needle in low) return canon
        return null
    }

    fun extractCameraFields(meta: Map<String, String>): CameraFields {
        val makeRaw = firstOf(meta, MAKE_KEYS)
        return CameraFields(
            brand = normalizeBrand(makeRaw),
            makeRaw = makeRaw,
            model = firstOf(meta, MODEL_KEYS),
            firmware = firstOf(meta, FIRMWARE_KEYS),
            serial = firstOf(meta, SERIAL_KEYS),
        )
    }

    private fun firstOf(d: Map<String, String>, keys: List<String>): String? =
        keys.firstNotNullOfOrNull { k -> d[k]?.takeIf { it.isNotBlank() } }

    data class CameraFields(
        val brand: String? = null,
        val makeRaw: String? = null,
        val model: String? = null,
        val firmware: String? = null,
        val serial: String? = null,
    )
}

/** InputStream helper that supports forward-only skip & exact-size read. */
private class CountingInput(private val s: InputStream) {
    fun readUpTo(n: Int): ByteArray {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = s.read(out, read, n - read)
            if (r < 0) return out.copyOf(read)
            read += r
        }
        return out
    }

    fun readFully(n: Int): ByteArray {
        val out = readUpTo(n)
        if (out.size < n) throw java.io.EOFException()
        return out
    }

    fun skipFully(bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = s.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            // skip() may return 0 even when data is available; force a read.
            val r = s.read()
            if (r < 0) throw java.io.EOFException()
            remaining -= 1
        }
    }
}
