package net.dinowang.actioncameradrain.domain.filing

/**
 * Insta360 `fileinfo_list.list` reader — ported from filing-poc/insta360probe.py.
 *
 * Locates the model field (`0x12 <len> 'Insta360 ...'`) and the serial /
 * firmware records adjacent to it. Doesn't do full protobuf parsing.
 */
object Insta360Probe {

    data class Result(
        val brand: String?,
        val model: String?,
        val firmware: String?,
        val serial: String?,
        val recordCount: Int,
    )

    fun probe(data: ByteArray): Result? {
        val serials = HashMap<String, Int>()
        val models = HashMap<String, Int>()
        val firmwares = HashMap<String, Int>()
        var n = 0
        scanRecords(data) { s, m, fw ->
            n += 1
            s?.let { serials.merge(it, 1) { a, b -> a + b } }
            m?.let { models.merge(it, 1) { a, b -> a + b } }
            fw?.let { firmwares.merge(it, 1) { a, b -> a + b } }
        }
        if (n == 0) return null
        val model = mostCommon(models)
        return Result(
            brand = if (model?.startsWith("Insta360") == true) "Insta360" else null,
            model = model,
            firmware = mostCommon(firmwares),
            serial = mostCommon(serials),
            recordCount = n,
        )
    }

    private fun mostCommon(c: Map<String, Int>): String? =
        c.maxByOrNull { it.value }?.key

    private inline fun scanRecords(data: ByteArray, emit: (String?, String?, String?) -> Unit) {
        var i = 0
        while (true) {
            val j = indexOf(data, 0x12.toByte(), i)
            if (j < 0) return
            i = j + 1
            val modelLp = readLp(data, j + 1) ?: continue
            val modelBytes = data.copyOfRange(modelLp.start, modelLp.end)
            if (!startsWith(modelBytes, "Insta360".toByteArray())) continue
            val afterModel = modelLp.end

            var serial: String? = null
            for (back in 2..63) {
                val pos = j - back
                if (pos < 0) break
                if (data[pos] != 0x0a.toByte()) continue
                val cand = readLp(data, pos + 1) ?: continue
                if (pos + 2 + (cand.end - cand.start) == j) {
                    serial = String(data, cand.start, cand.end - cand.start, Charsets.US_ASCII)
                    break
                }
            }

            var firmware: String? = null
            if (afterModel < data.size && data[afterModel] == 0x1a.toByte()) {
                val cand = readLp(data, afterModel + 1)
                if (cand != null) {
                    firmware = String(data, cand.start, cand.end - cand.start, Charsets.US_ASCII)
                }
            }

            emit(serial, String(modelBytes, Charsets.US_ASCII), firmware)
        }
    }

    private data class Range(val start: Int, val end: Int)

    private fun readLp(data: ByteArray, off: Int): Range? {
        if (off >= data.size) return null
        val n = data[off].toInt() and 0xff
        if (n !in 1..127) return null
        val end = off + 1 + n
        if (end > data.size) return null
        for (k in off + 1 until end) {
            val b = data[k].toInt() and 0xff
            if (b !in 0x20..0x7e) return null
        }
        return Range(off + 1, end)
    }

    private fun indexOf(data: ByteArray, b: Byte, start: Int): Int {
        for (i in start until data.size) if (data[i] == b) return i
        return -1
    }

    private fun startsWith(a: ByteArray, b: ByteArray): Boolean {
        if (a.size < b.size) return false
        for (i in b.indices) if (a[i] != b[i]) return false
        return true
    }
}
