package de.tedoki.amelia.mail.mime

import java.util.Locale

/**
 * Zerlegt eine komplette Nachricht (RFC 5322 / MIME) in ihre Teile.
 * Abschnittsnummern folgen IMAP, Teilinhalte werden nur bei Bedarf dekodiert.
 */
class RawMessage(private val data: ByteArray) {

    private class Node(val part: MimePart, val bodyStart: Int, val bodyEnd: Int)

    private val nodes = HashMap<String, Node>()

    val headers: Map<String, String>
    val root: MimePart

    init {
        val split = headerEnd(data, 0, data.size)
        headers = MimeUtil.parseHeaderBlock(data.copyOfRange(0, split.first))
        root = parseEntity(headers, split.second, data.size, section = "", isRoot = true)
    }

    fun headerBytes(): ByteArray = data.copyOfRange(0, headerEnd(data, 0, data.size).first)

    /** Dekodierter Inhalt eines Abschnitts (Transfer-Kodierung bereits aufgelöst). */
    fun partContent(section: String): ByteArray? {
        val key = if (section == "1" && !nodes.containsKey("1")) "" else section
        val node = nodes[key] ?: return null
        val raw = data.copyOfRange(node.bodyStart, node.bodyEnd)
        return MimeUtil.decodeTransfer(raw, node.part.encoding)
    }

    fun partText(section: String): String? {
        val node = nodes[if (section == "1" && !nodes.containsKey("1")) "" else section] ?: return null
        val bytes = partContent(section) ?: return null
        return MimeUtil.decodeText(bytes, node.part.charset)
    }

    private fun parseEntity(h: Map<String, String>, start: Int, end: Int, section: String, isRoot: Boolean): MimePart {
        val (type, params) = MimeUtil.parseValueWithParams(h["content-type"] ?: "text/plain; charset=us-ascii")
        val mimeType = if (type.contains('/')) type else "text/plain"
        val encoding = (h["content-transfer-encoding"] ?: "7bit").trim().lowercase(Locale.US)
        val (disp, dispParams) = h["content-disposition"]?.let { MimeUtil.parseValueWithParams(it) } ?: ("" to emptyMap())
        val children = mutableListOf<MimePart>()
        if (mimeType.startsWith("multipart/")) {
            val boundary = params["boundary"]
            if (!boundary.isNullOrEmpty()) {
                val ranges = splitMultipart(start, end, boundary)
                ranges.forEachIndexed { index, (s, e) ->
                    val childSection = if (section.isEmpty()) "${index + 1}" else "$section.${index + 1}"
                    val hs = headerEnd(data, s, e)
                    val childHeaders = MimeUtil.parseHeaderBlock(data.copyOfRange(s, hs.first))
                    children.add(parseEntity(childHeaders, hs.second, e, childSection, isRoot = false))
                }
            }
        }
        val part = MimePart(
            section = section,
            mimeType = mimeType,
            params = params,
            contentId = h["content-id"],
            encoding = encoding,
            size = (end - start).toLong(),
            disposition = disp.ifBlank { null },
            dispositionParams = dispParams,
            children = children
        )
        nodes[section] = Node(part, start, end)
        if (isRoot && !part.isMultipart) nodes["1"] = Node(part.copy(section = "1"), start, end)
        return if (isRoot && !part.isMultipart) part.copy(section = "1") else part
    }

    private fun splitMultipart(start: Int, end: Int, boundary: String): List<Pair<Int, Int>> {
        val delimiter = "--$boundary".toByteArray(Charsets.ISO_8859_1)
        val result = mutableListOf<Pair<Int, Int>>()
        var pos = start
        var partStart = -1
        while (pos < end) {
            val lineEnd = indexOf(data, '\n'.code.toByte(), pos, end).let { if (it < 0) end else it }
            if (startsWith(data, pos, lineEnd, delimiter)) {
                val after = pos + delimiter.size
                val next = if (after < lineEnd) data[after] else '\n'.code.toByte()
                val isClose = next == '-'.code.toByte() && after + 1 < lineEnd && data[after + 1] == '-'.code.toByte()
                val isDelimiter = isClose || next == '\r'.code.toByte() || next == '\n'.code.toByte() ||
                    next == ' '.code.toByte() || next == '\t'.code.toByte()
                if (!isDelimiter) {
                    pos = lineEnd + 1
                    continue
                }
                if (partStart >= 0) {
                    var e = pos
                    if (e > partStart && data[e - 1] == '\n'.code.toByte()) e--
                    if (e > partStart && data[e - 1] == '\r'.code.toByte()) e--
                    result.add(partStart to e)
                }
                if (isClose) return result
                partStart = minOf(lineEnd + 1, end)
            }
            pos = lineEnd + 1
        }
        if (partStart in 0 until end) result.add(partStart to end)
        return result
    }

    companion object {
        /** Liefert (Ende des Headers, Beginn des Bodys). */
        fun headerEnd(bytes: ByteArray, start: Int, end: Int): Pair<Int, Int> {
            var i = start
            // Teil ohne Header (beginnt direkt mit Leerzeile)
            if (i < end && bytes[i] == '\n'.code.toByte()) return i to i + 1
            if (i + 1 < end && bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte()) return i to i + 2
            while (i < end) {
                if (bytes[i] == '\n'.code.toByte()) {
                    if (i + 1 < end && bytes[i + 1] == '\n'.code.toByte()) return i + 1 to i + 2
                    if (i + 2 < end && bytes[i + 1] == '\r'.code.toByte() && bytes[i + 2] == '\n'.code.toByte()) return i + 1 to i + 3
                }
                i++
            }
            return end to end
        }

        private fun indexOf(bytes: ByteArray, b: Byte, from: Int, to: Int): Int {
            for (i in from until to) if (bytes[i] == b) return i
            return -1
        }

        private fun startsWith(bytes: ByteArray, from: Int, to: Int, prefix: ByteArray): Boolean {
            if (to - from < prefix.size) return false
            for (i in prefix.indices) if (bytes[from + i] != prefix[i]) return false
            return true
        }
    }
}
