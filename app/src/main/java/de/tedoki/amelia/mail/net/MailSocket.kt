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
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

enum class Security { SSL_TLS, STARTTLS, NONE }

/** Zugangsdaten für einen Server (Posteingang oder Postausgang). */
data class ServerConfig(
    val host: String,
    val port: Int,
    val security: Security,
    val username: String,
    val password: String,
    /** SHA-256-Fingerabdrücke von Zertifikaten, denen der Nutzer ausdrücklich vertraut (eigene Server). */
    val pinned: Set<String> = emptySet()
)

/** Angaben zu einem Zertifikat, das nicht automatisch vertrauenswürdig ist. */
data class CertificateInfo(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val subject: String,
    val issuer: String,
    val validFrom: java.util.Date,
    val validUntil: java.util.Date,
    val selfSigned: Boolean,
    val hostnameMismatch: Boolean
)

/** Der Server zeigt ein Zertifikat, dem Android nicht vertraut. Der Nutzer kann es bestätigen. */
class CertificateUntrustedException(val info: CertificateInfo) : MailException(
    if (info.hostnameMismatch) "Das Zertifikat von ${info.host} passt nicht zum Servernamen."
    else "Das Zertifikat von ${info.host} ist nicht von einer bekannten Stelle bestätigt.",
    kind = MailException.Kind.TLS
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
    private val pinned: Set<String> = emptySet(),
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
        if (override != null) {
            return override(plain, host, port).also {
                it.soTimeout = readTimeoutMs
                it.startHandshake()
            }
        }
        val trust = PinningTrustManager(pinned)
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val ssl = context.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        ssl.soTimeout = readTimeoutMs
        try {
            ssl.startHandshake()
        } catch (e: SSLException) {
            try { ssl.close() } catch (_: Exception) {}
            val chain = trust.rejectedChain
            if (chain != null && chain.isNotEmpty()) throw CertificateUntrustedException(describe(chain, hostnameMismatch = false))
            throw e
        }
        // Vom Nutzer bestätigtes Zertifikat: Name muss nicht passen (typisch bei eigenen Servern)
        if (!trust.pinMatched) {
            val verifier = hostnameVerifier ?: HttpsURLConnection.getDefaultHostnameVerifier()
            if (!verifier.verify(host, ssl.session)) {
                val chain = trust.lastChain
                try { ssl.close() } catch (_: Exception) {}
                if (chain != null && chain.isNotEmpty()) throw CertificateUntrustedException(describe(chain, hostnameMismatch = true))
                throw MailException(
                    "Das Zertifikat gehört nicht zu $host. Verbindung aus Sicherheitsgründen abgebrochen.",
                    kind = MailException.Kind.TLS
                )
            }
        }
        return ssl
    }

    private fun describe(chain: Array<X509Certificate>, hostnameMismatch: Boolean): CertificateInfo {
        val leaf = chain[0]
        return CertificateInfo(
            host = host,
            port = port,
            fingerprint = fingerprint(leaf),
            subject = leaf.subjectX500Principal.name,
            issuer = leaf.issuerX500Principal.name,
            validFrom = leaf.notBefore,
            validUntil = leaf.notAfter,
            selfSigned = leaf.subjectX500Principal == leaf.issuerX500Principal,
            hostnameMismatch = hostnameMismatch
        )
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

        /** Nur für Tests: ersetzt die Prüfung des Servernamens. */
        @Volatile
        var hostnameVerifier: javax.net.ssl.HostnameVerifier? = null

        fun fingerprint(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }
    }
}

/**
 * Prüft Zertifikate wie Android selbst. Zusätzlich gelten Zertifikate als vertrauenswürdig,
 * deren Fingerabdruck der Nutzer ausdrücklich bestätigt hat.
 */
private class PinningTrustManager(private val pinned: Set<String>) : X509TrustManager {
    private val system: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers.first { it is X509TrustManager } as X509TrustManager

    @Volatile var pinMatched = false
    @Volatile var rejectedChain: Array<X509Certificate>? = null
    @Volatile var lastChain: Array<X509Certificate>? = null

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        lastChain = chain
        if (chain.isNotEmpty() && MailSocket.fingerprint(chain[0]) in pinned) {
            pinMatched = true
            return
        }
        try {
            system.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            rejectedChain = chain
            throw e
        }
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        system.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
}
