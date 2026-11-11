package de.tedoki.amelia.mail.mime

import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class Address(val name: String, val email: String) {
    val display: String get() = name.ifBlank { email }
    override fun toString(): String = if (name.isBlank()) email else "$name <$email>"
}

object MimeUtil {

    private val WINDOWS_1252: Charset = charsetOrNull("windows-1252") ?: Charsets.ISO_8859_1

    // ---------------------------------------------------------------- Zeichensätze

    fun charsetOrNull(name: String?): Charset? {
        if (name.isNullOrBlank()) return null
        val n = name.trim().trim('"', '\'').lowercase(Locale.US)
        val mapped = when (n) {
            "utf8", "utf-8", "unicode-1-1-utf-8" -> "UTF-8"
            "latin1", "latin-1", "iso-8859-1", "iso8859-1", "iso_8859-1" -> "windows-1252"
            "us-ascii", "ascii", "ansi_x3.4-1968" -> "windows-1252"
            "gb2312", "gb_2312-80", "x-gbk" -> "GBK"
            "ks_c_5601-1987", "ks_c_5601", "korean" -> "x-windows-949"
            "cp1252", "x-cp1252" -> "windows-1252"
            "iso-8859-15", "latin9", "latin-9" -> "ISO-8859-15"
            "x-unknown", "unknown", "default", "x-user-defined" -> return null
            else -> n
        }
        return try { Charset.forName(mapped) } catch (_: Exception) {
            if (mapped == "x-windows-949") try { Charset.forName("EUC-KR") } catch (_: Exception) { null } else null
        }
    }

    fun isValidUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: Exception) {
        false
    }

    /** Dekodiert Text robust: unbekannter/ASCII-Zeichensatz → UTF-8, falls gültig, sonst Windows-1252. */
    fun decodeText(bytes: ByteArray, charsetName: String?): String {
        val declared = charsetOrNull(charsetName)
        if (declared == null || declared == WINDOWS_1252) {
            return if (isValidUtf8(bytes)) String(bytes, Charsets.UTF_8) else String(bytes, WINDOWS_1252)
        }
        return String(bytes, declared)
    }

    // ---------------------------------------------------------------- Transfer-Kodierungen

    fun decodeBase64(input: ByteArray): ByteArray {
        val clean = ByteArrayOutputStream(input.size)
        for (b in input) {
            val c = b.toInt().toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/') clean.write(b.toInt())
        }
        var data = clean.toByteArray()
        val rem = data.size % 4
        if (rem == 1) data = data.copyOf(data.size - 1)
        val padded = when (data.size % 4) {
            2 -> data + "==".toByteArray()
            3 -> data + "=".toByteArray()
            else -> data
        }
        return try { Base64.getDecoder().decode(padded) } catch (_: Exception) { ByteArray(0) }
    }

    fun decodeQuotedPrintable(input: ByteArray, underscoreIsSpace: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream(input.size)
        var i = 0
        while (i < input.size) {
            val b = input[i].toInt() and 0xFF
            when {
                b == '='.code -> {
                    if (i + 1 < input.size && (input[i + 1] == '\r'.code.toByte() || input[i + 1] == '\n'.code.toByte())) {
                        i++
                        if (input[i] == '\r'.code.toByte() && i + 1 < input.size && input[i + 1] == '\n'.code.toByte()) i++
                    } else if (i + 2 < input.size && hex(input[i + 1]) >= 0 && hex(input[i + 2]) >= 0) {
                        out.write(hex(input[i + 1]) * 16 + hex(input[i + 2]))
                        i += 2
                    } else {
                        // Soft-Break mit Leerzeichen dahinter ("=  \r\n") tolerieren
                        var j = i + 1
                        while (j < input.size && (input[j] == ' '.code.toByte() || input[j] == '\t'.code.toByte())) j++
                        if (j < input.size && (input[j] == '\r'.code.toByte() || input[j] == '\n'.code.toByte())) {
                            i = j
                            if (input[i] == '\r'.code.toByte() && i + 1 < input.size && input[i + 1] == '\n'.code.toByte()) i++
                        } else {
                            out.write(b)
                        }
                    }
                }
                underscoreIsSpace && b == '_'.code -> out.write(' '.code)
                else -> out.write(b)
            }
            i++
        }
        return out.toByteArray()
    }

    private fun hex(b: Byte?): Int {
        if (b == null) return -1
        return when (val c = b.toInt().toChar()) {
            in '0'..'9' -> c - '0'
            in 'A'..'F' -> c - 'A' + 10
            in 'a'..'f' -> c - 'a' + 10
            else -> -1
        }
    }

    fun decodeTransfer(bytes: ByteArray, encoding: String?): ByteArray = when (encoding?.trim()?.lowercase(Locale.US)) {
        "base64" -> decodeBase64(bytes)
        "quoted-printable" -> decodeQuotedPrintable(bytes)
        else -> bytes
    }

    // ---------------------------------------------------------------- Header

    private val ENCODED_WORD = Regex("=\\?([^?\\s]+)\\?([bBqQ])\\?([^?\\s]*)\\?=")

    /** RFC 2047: =?charset?B|Q?...?= inklusive über mehrere Wörter verteilter Multibyte-Zeichen. */
    fun decodeEncodedWords(input: String): String {
        if (!input.contains("=?")) return input
        val sb = StringBuilder()
        var last = 0
        var pendingCharset: String? = null
        val pendingBytes = ByteArrayOutputStream()
        fun flush() {
            if (pendingCharset != null) {
                sb.append(decodeText(pendingBytes.toByteArray(), pendingCharset!!.substringBefore('*')))
                pendingBytes.reset()
                pendingCharset = null
            }
        }
        for (m in ENCODED_WORD.findAll(input)) {
            val gap = input.substring(last, m.range.first)
            val charset = m.groupValues[1]
            val enc = m.groupValues[2].uppercase(Locale.US)
            val data = m.groupValues[3].toByteArray(Charsets.US_ASCII)
            val bytes = if (enc == "B") decodeBase64(data) else decodeQuotedPrintable(data, underscoreIsSpace = true)
            if (gap.isBlank() && pendingCharset != null) {
                if (!pendingCharset.equals(charset, ignoreCase = true)) {
                    flush()
                    pendingCharset = charset
                }
            } else {
                flush()
                sb.append(gap)
                pendingCharset = charset
            }
            pendingBytes.write(bytes)
            last = m.range.last + 1
        }
        flush()
        sb.append(input.substring(last))
        return sb.toString()
    }

    /** Header-Bytes (evtl. roh UTF-8) in Text umwandeln und Encoded Words dekodieren. */
    fun decodeHeaderValue(raw: ByteArray): String =
        decodeEncodedWords(unfold(decodeText(raw, null))).trim()

    fun unfold(value: String): String = value.replace(Regex("\r?\n[ \t]+"), " ").replace("\r", "").replace("\n", "")

    /** Zerlegt einen Header-Block in eine Map (Schlüssel klein), Werte entfaltet aber noch kodiert. */
    fun parseHeaderBlock(block: ByteArray): Map<String, String> {
        val text = decodeText(block, null)
        val result = LinkedHashMap<String, String>()
        var currentName: String? = null
        val currentValue = StringBuilder()
        fun commit() {
            val name = currentName ?: return
            if (!result.containsKey(name)) result[name] = currentValue.toString().trim()
            currentName = null
            currentValue.setLength(0)
        }
        for (rawLine in text.split('\n')) {
            val line = rawLine.trimEnd('\r')
            if (line.isEmpty()) {
                commit()
                continue
            }
            if ((line[0] == ' ' || line[0] == '\t') && currentName != null) {
                currentValue.append(' ').append(line.trim())
                continue
            }
            commit()
            val colon = line.indexOf(':')
            if (colon > 0) {
                currentName = line.substring(0, colon).trim().lowercase(Locale.US)
                currentValue.append(line.substring(colon + 1).trim())
            }
        }
        commit()
        return result
    }

    /** Header-Wert mit Parametern, z. B. Content-Type. RFC 2231 (Fortsetzungen, Prozent-Kodierung) wird berücksichtigt. */
    fun parseValueWithParams(value: String): Pair<String, Map<String, String>> {
        val parts = splitRespectingQuotes(value, ';')
        val main = parts.firstOrNull()?.trim()?.lowercase(Locale.US).orEmpty()
        val raw = LinkedHashMap<String, String>()
        for (p in parts.drop(1)) {
            val eq = p.indexOf('=')
            if (eq <= 0) continue
            val key = p.substring(0, eq).trim().lowercase(Locale.US)
            var v = p.substring(eq + 1).trim()
            if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) {
                v = v.substring(1, v.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            }
            raw[key] = v
        }
        return main to combineRfc2231(raw)
    }

    fun combineRfc2231(raw: Map<String, String>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val grouped = LinkedHashMap<String, MutableList<Triple<Int, Boolean, String>>>()
        for ((key, value) in raw) {
            val m = Regex("^([^*]+)\\*(?:(\\d+)(\\*)?)?$").find(key)
            if (m == null) {
                result[key] = value
                continue
            }
            val base = m.groupValues[1]
            val index = m.groupValues[2].toIntOrNull() ?: 0
            val extended = m.groupValues[2].isEmpty() || m.groupValues[3] == "*"
            grouped.getOrPut(base) { mutableListOf() }.add(Triple(index, extended, value))
        }
        for ((base, sections) in grouped) {
            sections.sortBy { it.first }
            var charset = "UTF-8"
            val bytes = ByteArrayOutputStream()
            sections.forEachIndexed { i, (_, extended, value) ->
                var v = value
                if (extended) {
                    if (i == 0) {
                        val first = v.indexOf('\'')
                        val second = if (first >= 0) v.indexOf('\'', first + 1) else -1
                        if (first >= 0 && second > first) {
                            charset = v.substring(0, first).ifBlank { "UTF-8" }
                            v = v.substring(second + 1)
                        }
                    }
                    bytes.write(percentDecode(v))
                } else {
                    bytes.write(v.toByteArray(Charsets.UTF_8))
                }
            }
            result[base] = decodeText(bytes.toByteArray(), charset)
        }
        return result
    }

    private fun percentDecode(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val h = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (h != null) {
                    out.write(h)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toByteArray()
    }

    fun splitRespectingQuotes(value: String, separator: Char): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var escape = false
        var depth = 0
        for (c in value) {
            when {
                escape -> { sb.append(c); escape = false }
                c == '\\' && inQuotes -> { sb.append(c); escape = true }
                c == '"' -> { inQuotes = !inQuotes; sb.append(c) }
                !inQuotes && c == '(' -> { depth++; sb.append(c) }
                !inQuotes && c == ')' -> { depth = maxOf(0, depth - 1); sb.append(c) }
                c == separator && !inQuotes && depth == 0 -> { result.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
        }
        result.add(sb.toString())
        return result
    }

    // ---------------------------------------------------------------- Absender-Echtheit

    /**
     * Wertet die oberste "Authentication-Results"-Kopfzeile aus, die der eigene Mailserver
     * beim Empfang einträgt (SPF, DKIM, DMARC). Ergebnis: "pass", "fail" oder "" (unbekannt).
     */
    fun authResult(header: String?): String {
        if (header.isNullOrBlank()) return ""
        val h = header.lowercase(Locale.US)
        fun result(method: String): String? =
            Regex("(^|[\\s;])$method\\s*=\\s*([a-z]+)").find(h)?.groupValues?.get(2)
        val dmarc = result("dmarc")
        val dkim = result("dkim")
        val spf = result("spf")
        return when {
            dmarc == "fail" -> "fail"
            dmarc == "pass" -> "pass"
            dkim in setOf("fail", "neutral", "permerror") && spf in setOf("fail", "softfail") -> "fail"
            dkim == null && spf == "fail" -> "fail"
            else -> ""
        }
    }

    // ---------------------------------------------------------------- Adressen

    fun parseAddressList(header: String?): List<Address> {
        if (header.isNullOrBlank()) return emptyList()
        val result = mutableListOf<Address>()
        for (chunk in splitRespectingQuotes(header, ',')) {
            var part = chunk.trim().trimEnd(';').trim()
            // Gruppen-Syntax "Liste: a@b, c@d;" → Gruppenname entfernen
            val colon = part.indexOf(':')
            if (colon >= 0) {
                val before = part.substring(0, colon)
                if (!before.contains('<') && !before.contains('"') && !before.contains('@')) part = part.substring(colon + 1).trim()
            }
            if (part.isEmpty()) continue
            parseSingleAddress(part)?.takeIf { it.email.isNotBlank() || it.name.isNotBlank() }?.let { result.add(it) }
        }
        return result
    }

    fun parseSingleAddress(input: String): Address? {
        var s = input.trim()
        if (s.isEmpty()) return null
        val lt = s.lastIndexOf('<')
        val gt = s.lastIndexOf('>')
        if (lt >= 0 && gt > lt) {
            val email = s.substring(lt + 1, gt).trim()
            var name = s.substring(0, lt).trim()
            name = stripComments(name).trim()
            if (name.length >= 2 && name.startsWith('"') && name.endsWith('"')) {
                name = name.substring(1, name.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            }
            name = decodeEncodedWords(name).trim()
            if (email.isEmpty() && name.isEmpty()) return null
            return Address(if (name.equals(email, true)) "" else name, email)
        }
        // "a@b (Name)"
        val comment = Regex("\\(([^)]*)\\)").find(s)?.groupValues?.get(1)?.let { decodeEncodedWords(it).trim() } ?: ""
        s = stripComments(s).trim().trim('"')
        if (!s.contains('@')) {
            return if (s.isBlank()) null else Address(decodeEncodedWords(s), "")
        }
        return Address(comment, s)
    }

    private fun stripComments(s: String): String = s.replace(Regex("\\([^)]*\\)"), "")

    fun isValidEmail(email: String): Boolean =
        Regex("^[^\\s@<>(),;:\"]+@[^\\s@<>(),;:\"]+\\.[^\\s@<>(),;:\"]{2,}$").matches(email.trim())

    // ---------------------------------------------------------------- Datum

    private val MONTHS = mapOf(
        "jan" to 0, "feb" to 1, "mar" to 2, "mär" to 2, "apr" to 3, "may" to 4, "mai" to 4, "jun" to 5,
        "jul" to 6, "aug" to 7, "sep" to 8, "oct" to 9, "okt" to 9, "nov" to 10, "dec" to 11, "dez" to 11
    )
    private val ZONES = mapOf(
        "UT" to 0, "UTC" to 0, "GMT" to 0, "Z" to 0, "EST" to -500, "EDT" to -400, "CST" to -600, "CDT" to -500,
        "MST" to -700, "MDT" to -600, "PST" to -800, "PDT" to -700, "CET" to 100, "CEST" to 200,
        "MET" to 100, "MEST" to 200, "BST" to 100, "EET" to 200, "EEST" to 300, "WET" to 0, "WEST" to 100
    )
    private val DATE_REGEX = Regex(
        "(\\d{1,2})[\\s-]+([A-Za-zä]{3})[A-Za-z.]*[\\s-]+(\\d{2,4})\\s+(\\d{1,2}):(\\d{1,2})(?::(\\d{1,2}))?(?:\\.\\d+)?\\s*([+-]\\d{2}:?\\d{2}|[A-Za-z]{1,5})?"
    )
    private val ISO_REGEX = Regex("(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2})(?::(\\d{2}))?")

    /** RFC 2822 / IMAP INTERNALDATE, tolerant gegenüber gängigen Abweichungen. */
    fun parseDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val m = DATE_REGEX.find(value)
        if (m != null) {
            val day = m.groupValues[1].toInt()
            val month = MONTHS[m.groupValues[2].lowercase(Locale.GERMAN)] ?: return null
            var year = m.groupValues[3].toInt()
            if (year < 50) year += 2000 else if (year < 100) year += 1900
            val hour = m.groupValues[4].toInt()
            val minute = m.groupValues[5].toInt()
            val second = m.groupValues[6].toIntOrNull() ?: 0
            val zone = m.groupValues[7]
            val offsetMinutes = when {
                zone.startsWith("+") || zone.startsWith("-") -> {
                    val digits = zone.substring(1).replace(":", "")
                    val h = digits.take(2).toIntOrNull() ?: 0
                    val mi = digits.drop(2).toIntOrNull() ?: 0
                    (h * 60 + mi) * if (zone.startsWith("-")) -1 else 1
                }
                zone.isNotBlank() -> ZONES[zone.uppercase(Locale.US)]?.let { (it / 100) * 60 + (it % 100) } ?: 0
                else -> 0
            }
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
            cal.clear()
            cal.set(year, month, day, hour, minute, second)
            return cal.timeInMillis - offsetMinutes * 60_000L
        }
        val iso = ISO_REGEX.find(value) ?: return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
        cal.clear()
        cal.set(
            iso.groupValues[1].toInt(), iso.groupValues[2].toInt() - 1, iso.groupValues[3].toInt(),
            iso.groupValues[4].toInt(), iso.groupValues[5].toInt(), iso.groupValues[6].toIntOrNull() ?: 0
        )
        return cal.timeInMillis
    }

    // ---------------------------------------------------------------- HTML → Text

    private val ENTITIES = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü", "szlig" to "ß",
        "eacute" to "é", "egrave" to "è", "aacute" to "á", "agrave" to "à", "ccedil" to "ç", "ntilde" to "ñ",
        "euro" to "€", "copy" to "©", "reg" to "®", "trade" to "™", "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "laquo" to "«", "raquo" to "»", "bdquo" to "„", "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’",
        "sbquo" to "‚", "middot" to "·", "bull" to "•", "shy" to "", "zwnj" to "", "zwj" to "", "deg" to "°",
        "para" to "¶", "sect" to "§", "times" to "×", "divide" to "÷", "frac12" to "½", "sup2" to "²"
    )

    fun decodeEntities(text: String): String = Regex("&(#[xX]?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);?").replace(text) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { codePoint(it) } ?: m.value
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { codePoint(it) } ?: m.value
            else -> ENTITIES[e] ?: m.value
        }
    }

    private fun codePoint(cp: Int): String = try { String(Character.toChars(cp)) } catch (_: Exception) { "" }

    fun htmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<(head|style|script|title)[^>]*>.*?</\\1\\s*>"), " ")
        s = s.replace(Regex("(?is)<!--.*?-->"), " ")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|tr|li|h[1-6]|blockquote|table|section|article)\\s*>"), "\n")
        s = s.replace(Regex("(?i)<li[^>]*>"), "• ")
        s = s.replace(Regex("(?s)<[^>]+>"), "")
        s = decodeEntities(s)
        s = s.replace('\u00A0', ' ')
        s = s.replace(Regex("[ \\t\\x0B\\f]+"), " ")
        s = s.replace(Regex(" *\\n *"), "\n")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    fun snippet(text: String, maxLength: Int = 220): String {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith(">") }
            .takeWhile { !(it.startsWith("Am ") && it.endsWith("schrieb:")) && !(it.startsWith("On ") && it.endsWith("wrote:")) }
            .joinToString(" ")
        val collapsed = lines.replace(Regex("\\s+"), " ").replace(Regex("[\\u200B-\\u200F\\u034F\\u00AD]"), "").trim()
        return if (collapsed.length <= maxLength) collapsed else collapsed.take(maxLength).trimEnd() + "…"
    }

    fun guessExtension(mimeType: String): String = when (mimeType.lowercase(Locale.US)) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "application/pdf" -> "pdf"
        "text/calendar" -> "ics"
        "message/rfc822" -> "eml"
        "text/plain" -> "txt"
        "text/html" -> "html"
        "application/zip" -> "zip"
        else -> "bin"
    }

    @Suppress("unused")
    fun urlDecode(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
}
