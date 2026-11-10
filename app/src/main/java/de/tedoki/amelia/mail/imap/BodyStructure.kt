package de.tedoki.amelia.mail.imap

import de.tedoki.amelia.mail.mime.MimePart
import de.tedoki.amelia.mail.mime.MimeUtil
import java.util.Base64
import java.util.Locale

/** Wandelt eine IMAP-BODYSTRUCTURE in einen [MimePart]-Baum um (RFC 3501, Abschnitt 7.4.2). */
object BodyStructure {

    fun parse(text: String): MimePart? {
        val values = ImapResponse.parse(text)
        val list = values.firstOrNull() as? ImapValue.ListV ?: return null
        return parse(list)
    }

    fun parse(value: ImapValue.ListV): MimePart? = try {
        parsePart(value, "", isRoot = true)
    } catch (_: Exception) {
        null
    }

    private fun parsePart(list: ImapValue.ListV, section: String, isRoot: Boolean): MimePart {
        val items = list.items
        if (items.isNotEmpty() && items[0] is ImapValue.ListV) {
            // Multipart: (teil1)(teil2)... "subtype" [params] [disposition] ...
            val children = mutableListOf<MimePart>()
            var i = 0
            while (i < items.size && items[i] is ImapValue.ListV) {
                val childSection = if (section.isEmpty()) "${i + 1}" else "$section.${i + 1}"
                children.add(parsePart(items[i] as ImapValue.ListV, childSection, isRoot = false))
                i++
            }
            val subtype = items.getOrNull(i)?.asText?.lowercase(Locale.US) ?: "mixed"
            val params = paramList(items.getOrNull(i + 1))
            val (disp, dispParams) = disposition(items.getOrNull(i + 2))
            return MimePart(
                section = section,
                mimeType = "multipart/$subtype",
                params = params,
                disposition = disp,
                dispositionParams = dispParams,
                children = children
            )
        }
        val type = items.getOrNull(0)?.asText?.lowercase(Locale.US) ?: "text"
        val subtype = items.getOrNull(1)?.asText?.lowercase(Locale.US) ?: "plain"
        val params = paramList(items.getOrNull(2))
        val id = items.getOrNull(3)?.asText
        val encoding = items.getOrNull(5)?.asText?.lowercase(Locale.US) ?: "7bit"
        val size = items.getOrNull(6)?.asText?.toLongOrNull() ?: 0L
        // Position der Erweiterungsdaten hängt vom Typ ab
        val extStart = when {
            type == "text" -> 8                                   // + lines
            type == "message" && subtype == "rfc822" -> 10        // + envelope, body, lines
            else -> 7
        }
        // ext: md5, disposition, language, location
        val (disp, dispParams) = disposition(items.getOrNull(extStart + 1))
        return MimePart(
            section = if (isRoot) "1" else section,
            mimeType = "$type/$subtype",
            params = params,
            contentId = id,
            encoding = encoding,
            size = size,
            disposition = disp,
            dispositionParams = dispParams
        )
    }

    private fun paramList(value: ImapValue?): Map<String, String> {
        val list = value as? ImapValue.ListV ?: return emptyMap()
        val raw = LinkedHashMap<String, String>()
        var i = 0
        while (i + 1 < list.items.size) {
            val k = list.items[i].asText?.lowercase(Locale.US)
            val v = list.items[i + 1].asText
            if (k != null && v != null) raw[k] = v
            i += 2
        }
        return MimeUtil.combineRfc2231(raw)
    }

    private fun disposition(value: ImapValue?): Pair<String?, Map<String, String>> {
        val list = value as? ImapValue.ListV ?: return null to emptyMap()
        val type = list.items.getOrNull(0)?.asText?.lowercase(Locale.US)
        return type to paramList(list.items.getOrNull(1))
    }
}

/** Modified UTF-7 (RFC 3501, Abschnitt 5.1.3) für Ordnernamen. */
object ModifiedUtf7 {

    fun decode(input: String): String {
        if (!input.contains('&')) return input
        val sb = StringBuilder()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c != '&') {
                sb.append(c)
                i++
                continue
            }
            val end = input.indexOf('-', i + 1)
            if (end < 0) {
                sb.append(input.substring(i))
                break
            }
            if (end == i + 1) {
                sb.append('&')
            } else {
                val b64 = input.substring(i + 1, end).replace(',', '/')
                val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
                try {
                    val bytes = Base64.getDecoder().decode(padded)
                    sb.append(String(bytes, Charsets.UTF_16BE))
                } catch (_: Exception) {
                    sb.append(input, i, end + 1)
                }
            }
            i = end + 1
        }
        return sb.toString()
    }

    fun encode(input: String): String {
        val sb = StringBuilder()
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isEmpty()) return
            val bytes = buffer.toString().toByteArray(Charsets.UTF_16BE)
            sb.append('&').append(Base64.getEncoder().encodeToString(bytes).trimEnd('=').replace('/', ',')).append('-')
            buffer.setLength(0)
        }
        for (c in input) {
            if (c.code in 0x20..0x7e) {
                flush()
                if (c == '&') sb.append("&-") else sb.append(c)
            } else {
                buffer.append(c)
            }
        }
        flush()
        return sb.toString()
    }
}
