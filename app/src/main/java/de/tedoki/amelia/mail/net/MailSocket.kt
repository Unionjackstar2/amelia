package de.tedoki.amelia.mail.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

enum class Security { SSL_TLS, STARTTLS, NONE }

/** Zugangsdaten für einen Server (Posteingang oder Postausgang). */
data class ServerConfig(
    val host: String,
    val port: Int,
    val security: Security,
    val username: String,
    val password: String
)

open class MailException(
    message: String,
    cause: Throwable? = null,
    val kind: Kind = Kind.PROTOCOL
) : IOException(message, cause) {
    enum class Kind { NETWORK, AUTH, TLS, PROTOCOL }
}

/** Übersetzt technische Fehler in kurze, verständliche Meldungen. */
fun Throwable.friendlyMessage(): String = when (this) {
    is MailException -> message ?: "Unbekannter Fehler"
    is UnknownHostException -> "Server nicht gefunden. Adresse und Internetverbindung prüfen."
    is SocketTimeoutException -> "Der Server antwortet nicht (Zeitüberschreitung)."
    is ConnectException, is NoRouteToHostException -> "Verbindung abgelehnt. Port und Verschlüsselung prüfen."
    is SSLHandshakeException -> "Sichere Verbindung fehlgeschlagen. Zertifikat oder Port prüfen."
    is SSLException -> "TLS-Fehler: ${message ?: "unbekannt"}"
    else -> message ?: javaClass.simpleName
}

/**
 * Dünne Hülle um einen TCP-Socket mit zeilenweisem Lesen auf Byte-Ebene.
 * Teilweise gelesene Zeilen bleiben bei einem Timeout erhalten (wichtig für IMAP IDLE).
 */
class MailSocket(
    private val host: String,
    private val port: Int,
    private val security: Security,
    private val connectTimeoutMs: Int = 20_000,
    readTimeoutMs: Int = 60_000
) : Closeable {

    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    private val pending = ByteArrayOutputStream()

    var readTimeoutMs: Int = readTimeoutMs
        set(value) {
            field = value
            socket?.soTimeout = value
        }

    val isConnected: Boolean get() = socket?.let { it.isConnected && !it.isClosed } == true

    fun connect() {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host.trim(), port), connectTimeoutMs)
            raw.soTimeout = readTimeoutMs
            raw.keepAlive = true
            raw.tcpNoDelay = true
            socket = if (security == Security.SSL_TLS) wrapTls(raw) else raw
            bindStreams()
        } catch (e: Exception) {
            try { raw.close() } catch (_: Exception) {}
            throw e
        }
    }

    /** Wird nach erfolgreichem STARTTLS/STLS aufgerufen. */
    fun upgradeToTls() {
        val current = socket ?: throw MailException("Keine Verbindung", kind = MailException.Kind.NETWORK)
        socket = wrapTls(current)
        pending.reset()
        bindStreams()
    }

    private fun wrapTls(plain: Socket): SSLSocket {
        val override = tlsOverride
        val ssl = if (override != null) {
            override(plain, host, port)
        } else {
            val context = SSLContext.getInstance("TLS").apply { init(null, null, null) }
            (context.socketFactory.createSocket(plain, host, port, true) as SSLSocket)
        }
        ssl.soTimeout = readTimeoutMs
        ssl.startHandshake()
        if (override == null) {
            val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
            if (!verifier.verify(host, ssl.session)) {
                try { ssl.close() } catch (_: Exception) {}
                throw MailException(
                    "Das Zertifikat gehört nicht zu $host. Verbindung aus Sicherheitsgründen abgebrochen.",
                    kind = MailException.Kind.TLS
                )
            }
        }
        return ssl
    }

    private fun bindStreams() {
        val s = socket!!
        input = BufferedInputStream(s.getInputStream(), 64 * 1024)
        output = BufferedOutputStream(s.getOutputStream(), 16 * 1024)
    }

    /** Liest eine Zeile ohne CRLF. Wirft eine Exception, wenn die Verbindung geschlossen wurde. */
    fun readLineBytes(maxLength: Int = 8 * 1024 * 1024): ByteArray {
        val stream = input ?: throw MailException("Keine Verbindung", kind = MailException.Kind.NETWORK)
        while (true) {
            val b = stream.read()
            if (b == -1) {
                throw MailException("Der Server hat die Verbindung beendet.", kind = MailException.Kind.NETWORK)
            }
            if (b == '\n'.code) {
                val bytes = pending.toByteArray()
                pending.reset()
                val end = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return if (end == bytes.size) bytes else bytes.copyOf(end)
            }
            pending.write(b)
            if (pending.size() > maxLength) {
                throw MailException("Antwortzeile des Servers ist zu lang.")
            }
        }
    }

    fun readLine(): String = String(readLineBytes(), Charsets.UTF_8)

    fun readExact(count: Int): ByteArray {
        val stream = input ?: throw MailException("Keine Verbindung", kind = MailException.Kind.NETWORK)
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = stream.read(buffer, read, count - read)
            if (n == -1) throw MailException("Verbindung während der Übertragung abgebrochen.", kind = MailException.Kind.NETWORK)
            read += n
        }
        return buffer
    }

    fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    fun write(bytes: ByteArray) {
        val out = output ?: throw MailException("Keine Verbindung", kind = MailException.Kind.NETWORK)
        out.write(bytes)
        out.flush()
    }

    fun writeLine(text: String) = write(text + "\r\n")

    override fun close() {
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
    }

    companion object {
        /** Nur für Tests: ersetzt den TLS-Aufbau (z. B. für selbstsignierte Testserver). */
        @Volatile
        var tlsOverride: ((Socket, String, Int) -> SSLSocket)? = null
    }
}
