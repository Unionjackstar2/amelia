package de.tedoki.amelia.mail.smtp

import de.tedoki.amelia.mail.mime.Address
import de.tedoki.amelia.mail.net.MailException
import de.tedoki.amelia.mail.net.MailSocket
import de.tedoki.amelia.mail.net.Security
import de.tedoki.amelia.mail.net.ServerConfig
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID

class SmtpConnection(private val config: ServerConfig) : Closeable {

    private val socket = MailSocket(config.host, config.port, config.security, readTimeoutMs = 120_000)
    private var extensions: List<String> = emptyList()

    private data class Reply(val code: Int, val lines: List<String>) {
        val text get() = lines.joinToString(" ") { it.drop(4) }
    }

    private fun readReply(): Reply {
        val lines = mutableListOf<String>()
        while (true) {
            val line = socket.readLine()
            lines.add(line)
            if (line.length < 4 || line[3] != '-') {
                return Reply(line.take(3).toIntOrNull() ?: 0, lines)
            }
        }
    }

    private fun cmd(text: String, vararg expected: Int, sensitive: Boolean = false): Reply {
        socket.writeLine(text)
        val r = readReply()
        if (expected.isNotEmpty() && r.code !in expected) {
            val shown = if (sensitive) text.substringBefore(' ') else text.take(60)
            throw MailException("SMTP-Fehler bei $shown: ${r.code} ${r.text.take(200)}")
        }
        return r
    }

    fun open() {
        socket.connect()
        val greeting = readReply()
        if (greeting.code != 220) throw MailException("Unerwartete SMTP-Begrüßung: ${greeting.text.take(150)}")
        ehlo()
        if (config.security == Security.STARTTLS) {
            if (extensions.none { it.equals("STARTTLS", true) }) {
                throw MailException("Der Postausgangsserver unterstützt kein STARTTLS.", kind = MailException.Kind.TLS)
            }
            cmd("STARTTLS", 220)
            socket.upgradeToTls()
            ehlo()
        }
        authenticate()
    }

    private fun ehlo() {
        val r = cmd("EHLO [127.0.0.1]")
        extensions = if (r.code == 250) {
            r.lines.drop(1).map { it.drop(4).trim() }
        } else {
            cmd("HELO [127.0.0.1]", 250)
            emptyList()
        }
    }

    private fun authenticate() {
        val auth = extensions.firstOrNull { it.uppercase(Locale.US).startsWith("AUTH") }?.uppercase(Locale.US)?.split(' ', '=')
            ?: return // Server verlangt keine Anmeldung
        if (config.username.isBlank()) return
        val result = when {
            "PLAIN" in auth -> {
                val token = b64("\u0000${config.username}\u0000${config.password}")
                cmd("AUTH PLAIN $token", sensitive = true)
            }
            "LOGIN" in auth -> {
                cmd("AUTH LOGIN", 334)
                cmd(b64(config.username), 334, sensitive = true)
                cmd(b64(config.password), sensitive = true)
            }
            else -> throw MailException("Der Postausgangsserver bietet kein unterstütztes Anmeldeverfahren an.")
        }
        if (result.code != 235) {
            throw MailException(
                "Anmeldung am Postausgangsserver fehlgeschlagen.\n${result.code} ${result.text.take(200)}",
                kind = MailException.Kind.AUTH
            )
        }
    }

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    fun send(from: String, recipients: List<String>, message: ByteArray) {
        val sizeExt = extensions.firstOrNull { it.uppercase(Locale.US).startsWith("SIZE ") }
        val max = sizeExt?.substringAfter(' ')?.trim()?.toLongOrNull() ?: 0L
        if (max > 0 && message.size > max) {
            throw MailException("Die Nachricht ist zu groß für den Server (max. ${max / 1_048_576} MB).")
        }
        cmd("MAIL FROM:<$from>", 250)
        var accepted = 0
        val rejected = mutableListOf<String>()
        for (rcpt in recipients) {
            val r = cmd("RCPT TO:<$rcpt>")
            if (r.code == 250 || r.code == 251) accepted++ else rejected.add("$rcpt (${r.code})")
        }
        if (accepted == 0) {
            cmd("RSET")
            throw MailException("Kein Empfänger wurde akzeptiert: ${rejected.joinToString()}")
        }
        cmd("DATA", 354)
        socket.write(dotStuff(message))
        socket.write(".\r\n")
        val r = readReply()
        if (r.code != 250) throw MailException("Versand abgelehnt: ${r.code} ${r.text.take(200)}")
        if (rejected.isNotEmpty()) {
            throw PartialDeliveryException("Gesendet, aber nicht zugestellt an: ${rejected.joinToString()}")
        }
    }

    fun quit() {
        try {
            if (socket.isConnected) {
                socket.writeLine("QUIT")
                readReply()
            }
        } catch (_: Exception) {
        } finally {
            close()
        }
    }

    override fun close() = socket.close()

    companion object {
        fun dotStuff(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(data.size + 64)
            var lineStart = true
            for (b in data) {
                if (lineStart && b == '.'.code.toByte()) out.write('.'.code)
                out.write(b.toInt())
                lineStart = b == '\n'.code.toByte()
            }
            if (!lineStart) out.write("\r\n".toByteArray())
            return out.toByteArray()
        }
    }
}

class PartialDeliveryException(message: String) : MailException(message)

data class OutgoingAttachment(val name: String, val mimeType: String, val data: ByteArray)

data class OutgoingMessage(
    val from: Address,
    val to: List<Address>,
    val cc: List<Address> = emptyList(),
    val bcc: List<Address> = emptyList(),
    val subject: String,
    val text: String,
    val inReplyTo: String? = null,
    val references: String? = null,
    val attachments: List<OutgoingAttachment> = emptyList(),
    val date: Date = Date()
) {
    val messageId: String = "<${UUID.randomUUID()}@${from.email.substringAfter('@', "amelia.local").ifBlank { "amelia.local" }}>"
    val allRecipients: List<String> get() = (to + cc + bcc).map { it.email }.distinct()
}

/** Baut eine standardkonforme MIME-Nachricht (UTF-8, quoted-printable, Base64-Anhänge). */
object MessageBuilder {

    fun build(msg: OutgoingMessage): ByteArray {
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
        header(sb, "Date", dateFormat.format(msg.date))
        header(sb, "From", formatAddress(msg.from))
        if (msg.to.isNotEmpty()) header(sb, "To", msg.to.joinToString(", ") { formatAddress(it) })
        if (msg.cc.isNotEmpty()) header(sb, "Cc", msg.cc.joinToString(", ") { formatAddress(it) })
        header(sb, "Subject", encodeWord(msg.subject))
        header(sb, "Message-ID", msg.messageId)
        msg.inReplyTo?.takeIf { it.isNotBlank() }?.let { header(sb, "In-Reply-To", it) }
        msg.references?.takeIf { it.isNotBlank() }?.let { header(sb, "References", it) }
        header(sb, "MIME-Version", "1.0")
        header(sb, "User-Agent", "Amelia (Tedoki Labs)")

        val textPart = "Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\n" +
            encodeQuotedPrintable(msg.text) + "\r\n"

        if (msg.attachments.isEmpty()) {
            sb.append(textPart)
            return sb.toString().toByteArray(Charsets.UTF_8)
        }
        val boundary = "=_amelia_" + UUID.randomUUID().toString().replace("-", "")
        sb.append("Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n\r\n")
        sb.append("This is a multi-part message in MIME format.\r\n")
        sb.append("--$boundary\r\n").append(textPart)
        for (att in msg.attachments) {
            sb.append("--$boundary\r\n")
            val safeName = att.name.replace("\"", "'")
            val asciiName = if (safeName.all { it.code in 0x20..0x7e }) "\"$safeName\"" else "\"${encodeWord(safeName)}\""
            val extName = "UTF-8''" + URLEncoder.encode(safeName, "UTF-8").replace("+", "%20")
            sb.append("Content-Type: ${att.mimeType.ifBlank { "application/octet-stream" }}; name=$asciiName\r\n")
            sb.append("Content-Transfer-Encoding: base64\r\n")
            sb.append("Content-Disposition: attachment; filename=$asciiName; filename*=$extName\r\n\r\n")
            val encoded = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(att.data)
            sb.append(encoded).append("\r\n")
        }
        sb.append("--$boundary--\r\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun header(sb: StringBuilder, name: String, value: String) {
        sb.append(fold("$name: $value")).append("\r\n")
    }

    /** Faltet lange Header an Leerzeichen (max. ~78 Zeichen pro Zeile). */
    private fun fold(line: String): String {
        if (line.length <= 78) return line
        val sb = StringBuilder()
        var current = StringBuilder()
        for (word in line.split(' ')) {
            if (current.isNotEmpty() && current.length + 1 + word.length > 76) {
                sb.append(current).append("\r\n")
                current = StringBuilder(" ")
            } else if (current.isNotEmpty()) {
                current.append(' ')
            }
            current.append(word)
        }
        sb.append(current)
        return sb.toString()
    }

    fun formatAddress(a: Address): String {
        if (a.name.isBlank()) return a.email
        val name = when {
            a.name.any { it.code > 0x7e } -> encodeWord(a.name)
            a.name.any { it in "()<>[]:;@\\,.\"" } -> "\"" + a.name.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            else -> a.name
        }
        return "$name <${a.email}>"
    }

    /** RFC 2047 B-Kodierung, nur wenn nötig; teilt an Zeichengrenzen. */
    fun encodeWord(text: String): String {
        if (text.all { it.code in 0x20..0x7e }) return text
        val words = mutableListOf<String>()
        val chunk = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val s = String(Character.toChars(cp))
            val len = s.toByteArray(Charsets.UTF_8).size
            if (bytes + len > 42 && chunk.isNotEmpty()) {
                words.add(chunk.toString())
                chunk.setLength(0)
                bytes = 0
            }
            chunk.append(s)
            bytes += len
            i += Character.charCount(cp)
        }
        if (chunk.isNotEmpty()) words.add(chunk.toString())
        return words.joinToString(" ") { "=?UTF-8?B?" + Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) + "?=" }
    }

    fun encodeQuotedPrintable(text: String): String {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val out = StringBuilder()
        for ((index, line) in normalized.split('\n').withIndex()) {
            if (index > 0) out.append("\r\n")
            val bytes = line.toByteArray(Charsets.UTF_8)
            var lineLen = 0
            for ((i, b) in bytes.withIndex()) {
                val v = b.toInt() and 0xFF
                val isLast = i == bytes.size - 1
                val token = when {
                    v == '='.code -> "=3D"
                    (v == ' '.code || v == '\t'.code) && isLast -> "=%02X".format(v)
                    v in 33..126 || v == ' '.code || v == '\t'.code -> v.toChar().toString()
                    else -> "=%02X".format(v)
                }
                if (lineLen + token.length > 75) {
                    out.append("=\r\n")
                    lineLen = 0
                }
                // Punkt am Zeilenanfang kodieren (manche Server sind empfindlich)
                if (lineLen == 0 && token == ".") {
                    out.append("=2E")
                    lineLen += 3
                    continue
                }
                out.append(token)
                lineLen += token.length
            }
        }
        return out.toString()
    }
}
