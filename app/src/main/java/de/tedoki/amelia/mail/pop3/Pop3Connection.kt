package de.tedoki.amelia.mail.pop3

import de.tedoki.amelia.mail.net.MailException
import de.tedoki.amelia.mail.net.MailSocket
import de.tedoki.amelia.mail.net.Security
import de.tedoki.amelia.mail.net.ServerConfig
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/**
 * POP3 (RFC 1939) mit UIDL, damit Nachrichten über Sitzungen hinweg eindeutig erkannt werden.
 */
class Pop3Connection(private val config: ServerConfig) : Closeable {

    private val socket = MailSocket(config.host, config.port, config.security, config.pinned)
    private var capabilities: Set<String> = emptySet()

    fun open() {
        socket.connect()
        val greeting = socket.readLine()
        if (!greeting.startsWith("+OK")) throw MailException("Unerwartete POP3-Begrüßung: ${greeting.take(150)}")
        capabilities = readCapabilities()
        if (config.security == Security.STARTTLS) {
            val r = simple("STLS", allowFail = true)
            if (!r.startsWith("+OK")) throw MailException("Der Server unterstützt kein STLS.", kind = MailException.Kind.TLS)
            socket.upgradeToTls()
            capabilities = readCapabilities()
        }
        authenticate()
    }

    private fun readCapabilities(): Set<String> {
        socket.writeLine("CAPA")
        val first = socket.readLine()
        if (!first.startsWith("+OK")) return emptySet()
        return String(readMultiline(), Charsets.UTF_8).lines().map { it.trim().uppercase(Locale.US) }.filter { it.isNotEmpty() }.toSet()
    }

    private fun authenticate() {
        val saslPlain = capabilities.any { it.startsWith("SASL") && it.split(' ').contains("PLAIN") }
        if (saslPlain) {
            val token = Base64.getEncoder().encodeToString("\u0000${config.username}\u0000${config.password}".toByteArray(Charsets.UTF_8))
            socket.writeLine("AUTH PLAIN")
            val cont = socket.readLine()
            if (cont.startsWith("+") && !cont.startsWith("+OK")) {
                socket.writeLine(token)
                val r = socket.readLine()
                if (r.startsWith("+OK")) return
            } else if (cont.startsWith("+OK")) {
                return
            }
            // Fallback auf USER/PASS
        }
        val u = simple("USER ${config.username}", allowFail = true)
        if (!u.startsWith("+OK")) throw authError(u)
        val p = simple("PASS ${config.password}", allowFail = true, sensitive = true)
        if (!p.startsWith("+OK")) throw authError(p)
    }

    private fun authError(line: String) = MailException(
        "Anmeldung fehlgeschlagen. Benutzername und Passwort prüfen.\n${line.removePrefix("-ERR").trim().take(200)}",
        kind = MailException.Kind.AUTH
    )

    private fun simple(cmd: String, allowFail: Boolean = false, sensitive: Boolean = false): String {
        socket.writeLine(cmd)
        val line = socket.readLine()
        if (!line.startsWith("+OK") && !allowFail) {
            val shown = if (sensitive) cmd.substringBefore(' ') else cmd
            throw MailException("POP3-Fehler bei $shown: ${line.take(200)}")
        }
        return line
    }

    /** Liest eine mehrzeilige Antwort bis "." und entfernt das Dot-Stuffing (auf Byte-Ebene). */
    private fun readMultiline(): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val line = socket.readLineBytes()
            if (line.size == 1 && line[0] == '.'.code.toByte()) break
            val start = if (line.isNotEmpty() && line[0] == '.'.code.toByte()) 1 else 0
            out.write(line, start, line.size - start)
            out.write('\r'.code)
            out.write('\n'.code)
        }
        return out.toByteArray()
    }

    fun stat(): Int {
        val r = simple("STAT")
        return r.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
    }

    /** Nachrichtennummer → eindeutige ID. Fallback ohne UIDL: Hash über die Kopfzeilen. */
    fun uidl(count: Int): Map<Int, String> {
        socket.writeLine("UIDL")
        val first = socket.readLine()
        if (first.startsWith("+OK")) {
            val result = LinkedHashMap<Int, String>()
            String(readMultiline(), Charsets.UTF_8).lines().forEach { line ->
                val parts = line.trim().split(' ', limit = 2)
                val n = parts.getOrNull(0)?.toIntOrNull()
                val id = parts.getOrNull(1)?.trim()
                if (n != null && !id.isNullOrEmpty()) result[n] = id
            }
            return result
        }
        val digest = MessageDigest.getInstance("SHA-1")
        return (1..count).associateWith { n ->
            "h:" + digest.digest(top(n, 0)).joinToString("") { "%02x".format(it) }
        }
    }

    fun sizes(): Map<Int, Long> {
        socket.writeLine("LIST")
        val first = socket.readLine()
        if (!first.startsWith("+OK")) return emptyMap()
        val result = HashMap<Int, Long>()
        String(readMultiline(), Charsets.UTF_8).lines().forEach { line ->
            val parts = line.trim().split(' ')
            val n = parts.getOrNull(0)?.toIntOrNull()
            val s = parts.getOrNull(1)?.toLongOrNull()
            if (n != null && s != null) result[n] = s
        }
        return result
    }

    fun top(n: Int, lines: Int): ByteArray {
        simple("TOP $n $lines")
        return readMultiline()
    }

    fun retrieve(n: Int): ByteArray {
        socket.readTimeoutMs = 120_000
        simple("RETR $n")
        return readMultiline().also { socket.readTimeoutMs = 60_000 }
    }

    fun delete(n: Int) {
        simple("DELE $n")
    }

    /** QUIT bestätigt Löschungen auf dem Server. */
    fun quit() {
        try {
            if (socket.isConnected) simple("QUIT", allowFail = true)
        } catch (_: Exception) {
        } finally {
            close()
        }
    }

    override fun close() = socket.close()
}
