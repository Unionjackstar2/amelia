package de.tedoki.amelia.mail.mime

import java.util.Locale

/**
 * Ein Teil einer Nachricht. Die Abschnittsnummern ([section]) entsprechen IMAP (RFC 3501),
 * damit IMAP- und POP3-Nachrichten gleich behandelt werden können.
 */
data class MimePart(
    val section: String,
    val mimeType: String,
    val params: Map<String, String> = emptyMap(),
    val contentId: String? = null,
    val encoding: String = "7bit",
    val size: Long = 0,
    val disposition: String? = null,
    val dispositionParams: Map<String, String> = emptyMap(),
    val children: List<MimePart> = emptyList()
) {
    val isMultipart: Boolean get() = mimeType.startsWith("multipart/")
    val charset: String? get() = params["charset"]
    val isAttachmentDisposition: Boolean get() = disposition.equals("attachment", ignoreCase = true)

    val fileName: String?
        get() = (dispositionParams["filename"] ?: params["name"])
            ?.let { MimeUtil.decodeEncodedWords(it).trim() }
            ?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.takeIf { it.isNotBlank() }

    /** Anzeigename, auch wenn der Absender keinen Dateinamen mitgeschickt hat. */
    val displayName: String
        get() = fileName ?: when {
            mimeType == "message/rfc822" -> "Weitergeleitete Nachricht.eml"
            mimeType == "text/calendar" -> "Einladung.ics"
            else -> "Anhang-$section.${MimeUtil.guessExtension(mimeType)}"
        }

    val normalizedContentId: String?
        get() = contentId?.trim()?.removePrefix("<")?.removeSuffix(">")?.takeIf { it.isNotBlank() }

    fun find(section: String): MimePart? {
        if (this.section == section) return this
        for (c in children) c.find(section)?.let { return it }
        return null
    }

    /** Die Abschnittsangabe für IMAP-FETCH (Wurzel eines Nicht-Multipart → "1"). */
    val fetchSection: String get() = section.ifBlank { "1" }
}

/** Ergebnis der Analyse: welcher Teil ist der Text, welche sind Anhänge? */
data class BodyLayout(
    val html: MimePart?,
    val plain: MimePart?,
    val extraPlain: List<MimePart>,
    val attachments: List<MimePart>,
    val inline: List<MimePart>
) {
    val primary: MimePart? get() = html ?: plain
}

object BodyAnalyzer {

    fun analyze(root: MimePart): BodyLayout {
        var html: MimePart? = null
        var plain: MimePart? = null
        val extraPlain = mutableListOf<MimePart>()
        val attachments = mutableListOf<MimePart>()
        val inline = mutableListOf<MimePart>()

        fun isBodyCandidate(p: MimePart): Boolean =
            !p.isAttachmentDisposition && (p.fileName == null || p.disposition.equals("inline", true) && p.mimeType.startsWith("text/"))

        fun walk(part: MimePart, insideAlternative: Boolean) {
            if (part.isMultipart) {
                val alt = part.mimeType == "multipart/alternative"
                if (alt) {
                    // Bevorzugt die letzte (reichhaltigste) Variante.
                    val htmlChild = part.children.lastOrNull { containsHtml(it) }
                    val plainChild = part.children.firstOrNull { it.mimeType == "text/plain" && isBodyCandidate(it) }
                    if (html == null && htmlChild != null) {
                        if (htmlChild.isMultipart) walk(htmlChild, true) else html = htmlChild
                    }
                    if (plain == null && plainChild != null) plain = plainChild
                    // Anhänge, die (selten) in einer Alternative stecken
                    part.children.filter { it != htmlChild && it != plainChild && !it.mimeType.startsWith("text/") }
                        .forEach { walk(it, true) }
                    return
                }
                part.children.forEach { walk(it, insideAlternative) }
                return
            }
            val type = part.mimeType
            when {
                type == "text/html" && isBodyCandidate(part) && html == null -> html = part
                type == "text/plain" && isBodyCandidate(part) && plain == null -> plain = part
                type == "text/plain" && isBodyCandidate(part) && !insideAlternative -> extraPlain.add(part)
                type.startsWith("image/") && part.normalizedContentId != null && !part.isAttachmentDisposition -> inline.add(part)
                type == "text/html" && isBodyCandidate(part) -> { /* weitere HTML-Teile ignorieren */ }
                type == "application/pgp-signature" || type == "application/pkcs7-signature" ||
                    type == "application/x-pkcs7-signature" -> { /* Signaturen nicht als Anhang zeigen */ }
                else -> attachments.add(part)
            }
        }
        walk(root, false)
        return BodyLayout(html, plain, if (html != null) emptyList() else extraPlain, attachments, inline)
    }

    private fun containsHtml(p: MimePart): Boolean =
        if (p.isMultipart) p.children.any { containsHtml(it) } else p.mimeType == "text/html"

    fun hasAttachments(root: MimePart): Boolean = analyze(root).attachments.isNotEmpty()

    fun lowerType(type: String?, subtype: String?): String =
        "${type ?: "text"}/${subtype ?: "plain"}".lowercase(Locale.US)
}
