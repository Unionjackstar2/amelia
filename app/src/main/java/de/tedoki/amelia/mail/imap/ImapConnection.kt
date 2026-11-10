package de.tedoki.amelia.mail.imap

import de.tedoki.amelia.mail.mime.MimePart
import de.tedoki.amelia.mail.net.MailException
import de.tedoki.amelia.mail.net.MailSocket
import de.tedoki.amelia.mail.net.Security
import de.tedoki.amelia.mail.net.ServerConfig
import java.io.Closeable
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.Locale

enum class FolderRole { INBOX, SENT, DRAFTS, TRASH, JUNK, ARCHIVE, OTHER }

data class ListedFolder(val path: String, val name: String, val delimiter: String?, val role: FolderRole, val selectable: Boolean)

data class SelectInfo(val exists: Int, val uidValidity: Long, val uidNext: Long, val highestModSeq: Long)

data class UidFlags(val uid: Long, val flags: Set<String>) {
    val seen get() = flags.any { it.equals("\\Seen", true) }
    val flagged get() = flags.any { it.equals("\\Flagged", true) }
    val answered get() = flags.any { it.equals("\\Answered", true) }
    val deleted get() = flags.any { it.equals("\\Deleted", true) }
}

data class HeaderFetch(
    val uid: Long,
    val flags: Set<String>,
    val size: Long,
    val internalDate: String?,
    val header: ByteArray,
    val structure: MimePart?,
    val structureRaw: String
)

class CommandResult(val status: String, val text: String, val untagged: List<ImapResponse>) {
    val ok get() = status == "OK"
}

class ImapCommandException(message: String) : MailException(message)

/**
 * Blockierende IMAP4rev1-Verbindung. Nicht threadsicher – eine Verbindung pro Arbeitsablauf.
 */
class ImapConnection(private val config: ServerConfig) : Closeable {

    private val socket = MailSocket(config.host, config.port, config.security)
    private var tagCounter = 0
    var capabilities: Set<String> = emptySet()
        private set
    var selected: String? = null
        private set

    fun has(cap: String) = capabilities.contains(cap.uppercase(Locale.US))

    val isOpen: Boolean get() = socket.isConnected

    // ------------------------------------------------------------------ Verbindungsaufbau

    fun open() {
        socket.connect()
        val greeting = ImapResponse.read(socket)
        if (greeting.text.startsWith("* BYE", true)) throw MailException("Server lehnt Verbindung ab: ${greeting.text}")
        if (greeting.text.startsWith("* PREAUTH", true)) {
            refreshCapabilities()
            return
        }
        parseCapabilityCode(greeting.text)
        if (config.security == Security.STARTTLS) {
            if (capabilities.isEmpty()) refreshCapabilities()
            val r = command("STARTTLS", allowFail = true)
            if (!r.ok) throw MailException("Der Server unterstützt kein STARTTLS.", kind = MailException.Kind.TLS)
            socket.upgradeToTls()
            capabilities = emptySet()
        }
        if (capabilities.isEmpty()) refreshCapabilities()
        login()
        if (has("ID")) command("ID (\"name\" \"Amelia\" \"vendor\" \"Tedoki Labs\")", allowFail = true)
    }

    private fun login() {
        val user = config.username
        val pass = config.password
        val result = if (has("AUTH=PLAIN") && !has("LOGINDISABLED")) {
            val token = Base64.getEncoder().encodeToString("\u0000$user\u0000$pass".toByteArray(Charsets.UTF_8))
            if (has("SASL-IR")) {
                command("AUTHENTICATE PLAIN $token", allowFail = true, sensitive = true)
            } else {
                commandWithContinuation("AUTHENTICATE PLAIN", token)
            }.let { r ->
                if (r.status == "BAD") loginCommand(user, pass) else r
            }
        } else {
            loginCommand(user, pass)
        }
        if (!result.ok) {
            throw MailException(
                "Anmeldung fehlgeschlagen. Benutzername und Passwort prüfen (bei manchen Anbietern ist ein App-Passwort nötig).\n" +
                    result.text.substringAfter(' ').substringAfter(' ').take(200),
                kind = MailException.Kind.AUTH
            )
        }
        // Viele Server senden neue Capabilities im OK-Code, sonst nachfragen.
        if (!parseCapabilityCode(result.text)) refreshCapabilities()
    }

    private fun loginCommand(user: String, pass: String): CommandResult {
        val parts = listOf(user, pass)
        return if (parts.all { isPlainAscii(it) }) {
            command("LOGIN ${quote(user)} ${quote(pass)}", allowFail = true, sensitive = true)
        } else {
            // Nicht-ASCII-Zugangsdaten als Literale senden
            val tag = nextTag()
            val userBytes = user.toByteArray(Charsets.UTF_8)
            val passBytes = pass.toByteArray(Charsets.UTF_8)
            sendLiteralCommand(tag, "LOGIN ", listOf(userBytes, passBytes))
            readTagged(tag)
        }
    }

    private fun refreshCapabilities() {
        val r = command("CAPABILITY")
        val line = r.untagged.firstOrNull { it.text.startsWith("* CAPABILITY", true) }?.text ?: return
        capabilities = line.substringAfter("CAPABILITY", "").trim().split(' ')
            .filter { it.isNotBlank() }.map { it.uppercase(Locale.US) }.toSet()
    }

    private fun parseCapabilityCode(text: String): Boolean {
        val m = Regex("\\[CAPABILITY ([^\\]]+)\\]", RegexOption.IGNORE_CASE).find(text) ?: return false
        capabilities = m.groupValues[1].split(' ').filter { it.isNotBlank() }.map { it.uppercase(Locale.US) }.toSet()
        return true
    }

    // ------------------------------------------------------------------ Kommandos

    private fun nextTag(): String = "A${++tagCounter}"

    fun command(cmd: String, allowFail: Boolean = false, sensitive: Boolean = false, onUntagged: ((ImapResponse) -> Unit)? = null): CommandResult {
        val tag = nextTag()
        socket.writeLine("$tag $cmd")
        val result = readTagged(tag, onUntagged)
        if (!result.ok && !allowFail) {
            val shown = if (sensitive) cmd.substringBefore(' ') else cmd.take(60)
            throw ImapCommandException("Server-Fehler bei $shown: ${result.text.substringAfter(' ').take(200)}")
        }
        return result
    }

    private fun commandWithContinuation(cmd: String, response: String): CommandResult {
        val tag = nextTag()
        socket.writeLine("$tag $cmd")
        val untagged = mutableListOf<ImapResponse>()
        while (true) {
            val r = ImapResponse.read(socket)
            when {
                r.isContinuation -> socket.writeLine(response)
                r.tag == tag -> return CommandResult(r.status, r.text, untagged)
                else -> untagged.add(r)
            }
        }
    }

    /** Sendet "TAG <prefix>{n}\r\n<daten> {m}\r\n<daten>..." mit korrekter Behandlung synchroner Literale. */
    private fun sendLiteralCommand(tag: String, prefix: String, literals: List<ByteArray>, suffix: String = "") {
        val nonSync = has("LITERAL+")
        var line = "$tag $prefix"
        literals.forEachIndexed { index, bytes ->
            line += if (nonSync) "{${bytes.size}+}" else "{${bytes.size}}"
            socket.writeLine(line)
            if (!nonSync) {
                val r = ImapResponse.read(socket)
                if (!r.isContinuation) throw ImapCommandException("Server lehnt Daten ab: ${r.text.take(200)}")
            }
            socket.write(bytes)
            line = if (index < literals.size - 1) " " else suffix
        }
        socket.writeLine(line)
    }

    private fun readTagged(tag: String, onUntagged: ((ImapResponse) -> Unit)? = null): CommandResult {
        val untagged = mutableListOf<ImapResponse>()
        while (true) {
            val r = ImapResponse.read(socket)
            if (r.tag == tag) return CommandResult(r.status, r.text, untagged)
            if (r.isUntagged && r.text.startsWith("* BYE", true)) {
                throw MailException("Server hat die Verbindung beendet: ${r.text.take(200)}", kind = MailException.Kind.NETWORK)
            }
            if (onUntagged != null) onUntagged(r) else untagged.add(r)
        }
    }

    // ------------------------------------------------------------------ Ordner

    fun listFolders(): List<ListedFolder> {
        val cmd = if (has("SPECIAL-USE")) "LIST \"\" \"*\" RETURN (SPECIAL-USE)" else "LIST \"\" \"*\""
        val r = command(cmd, allowFail = true).let { if (!it.ok) command("LIST \"\" \"*\"") else it }
        val result = mutableListOf<ListedFolder>()
        for (resp in r.untagged) {
            if (!resp.text.startsWith("* LIST", true)) continue
            val v = resp.values
            val flags = (v.getOrNull(1) as? ImapValue.ListV)?.items?.mapNotNull { it.asText?.lowercase(Locale.US) } ?: emptyList()
            val delimiter = v.getOrNull(2)?.asText
            val path = v.getOrNull(3)?.asText ?: continue
            val decoded = ModifiedUtf7.decode(path)
            val leaf = if (delimiter.isNullOrEmpty()) decoded else decoded.substringAfterLast(delimiter)
            val role = detectRole(flags, path, leaf)
            val selectable = flags.none { it == "\\noselect" || it == "\\nonexistent" }
            result.add(ListedFolder(path, if (role == FolderRole.INBOX) "Posteingang" else leaf, delimiter, role, selectable))
        }
        if (result.none { it.role == FolderRole.INBOX }) {
            result.add(0, ListedFolder("INBOX", "Posteingang", "/", FolderRole.INBOX, true))
        }
        return result
    }

    private fun detectRole(flags: List<String>, path: String, leaf: String): FolderRole {
        if (path.equals("INBOX", true)) return FolderRole.INBOX
        when {
            "\\sent" in flags -> return FolderRole.SENT
            "\\trash" in flags -> return FolderRole.TRASH
            "\\drafts" in flags -> return FolderRole.DRAFTS
            "\\junk" in flags || "\\spam" in flags -> return FolderRole.JUNK
            "\\archive" in flags -> return FolderRole.ARCHIVE
        }
        val n = leaf.lowercase(Locale.GERMAN)
        return when (n) {
            "sent", "sent items", "sent messages", "sent mail", "gesendet", "gesendete objekte", "gesendete elemente",
            "gesendete nachrichten", "outbox.sent" -> FolderRole.SENT
            "trash", "deleted", "deleted items", "deleted messages", "papierkorb", "gelöscht", "gelöschte objekte",
            "gelöschte elemente", "bin" -> FolderRole.TRASH
            "drafts", "draft", "entwürfe", "entwurf" -> FolderRole.DRAFTS
            "junk", "spam", "junk e-mail", "junk-e-mail", "spamverdacht", "bulk mail", "unerwünscht" -> FolderRole.JUNK
            "archive", "archiv", "archives" -> FolderRole.ARCHIVE
            else -> FolderRole.OTHER
        }
    }

    fun select(path: String): SelectInfo {
        val condstore = if (has("CONDSTORE")) " (CONDSTORE)" else ""
        val r = command("SELECT ${quote(path)}$condstore", allowFail = true)
        if (!r.ok) {
            selected = null
            throw ImapCommandException("Ordner „${ModifiedUtf7.decode(path)}“ kann nicht geöffnet werden: ${r.text.substringAfter(' ').take(150)}")
        }
        selected = path
        var exists = 0
        var validity = 0L
        var next = 0L
        var modseq = 0L
        for (line in r.untagged.map { it.text } + r.text) {
            Regex("^\\* (\\d+) EXISTS", RegexOption.IGNORE_CASE).find(line)?.let { exists = it.groupValues[1].toInt() }
            Regex("\\[UIDVALIDITY (\\d+)\\]", RegexOption.IGNORE_CASE).find(line)?.let { validity = it.groupValues[1].toLong() }
            Regex("\\[UIDNEXT (\\d+)\\]", RegexOption.IGNORE_CASE).find(line)?.let { next = it.groupValues[1].toLong() }
            Regex("\\[HIGHESTMODSEQ (\\d+)\\]", RegexOption.IGNORE_CASE).find(line)?.let { modseq = it.groupValues[1].toLong() }
        }
        return SelectInfo(exists, validity, next, modseq)
    }

    fun ensureSelected(path: String) {
        if (selected != path) select(path)
    }

    // ------------------------------------------------------------------ Abfragen

    /** UIDs per Sequenzbereich (z. B. die neuesten N Nachrichten). */
    fun uidsForSequenceRange(from: Int, to: Int): List<Long> {
        if (to < 1 || from > to) return emptyList()
        val uids = mutableListOf<Long>()
        command("FETCH ${maxOf(1, from)}:$to (UID)") { r ->
            r.fetchData()?.second?.get("UID")?.asText?.toLongOrNull()?.let { uids.add(it) }
        }
        return uids
    }

    fun uidSearch(criteria: String): List<Long> {
        val result = mutableListOf<Long>()
        command("UID SEARCH $criteria") { r ->
            if (r.text.startsWith("* SEARCH", true)) {
                r.text.substringAfter("SEARCH", "").trim().split(' ').mapNotNullTo(result) { it.toLongOrNull() }
            }
        }
        return result
    }

    fun fetchFlags(uidRange: String): List<UidFlags> {
        val list = mutableListOf<UidFlags>()
        command("UID FETCH $uidRange (UID FLAGS)") { r ->
            val data = r.fetchData()?.second ?: return@command
            val uid = data["UID"]?.asText?.toLongOrNull() ?: return@command
            list.add(UidFlags(uid, flagSet(data["FLAGS"])))
        }
        return list
    }

    fun fetchHeaders(uids: Collection<Long>): List<HeaderFetch> {
        if (uids.isEmpty()) return emptyList()
        val fields = "DATE FROM TO CC REPLY-TO SUBJECT MESSAGE-ID IN-REPLY-TO REFERENCES CONTENT-TYPE LIST-UNSUBSCRIBE"
        val result = mutableListOf<HeaderFetch>()
        command("UID FETCH ${uidSet(uids)} (UID FLAGS INTERNALDATE RFC822.SIZE BODYSTRUCTURE BODY.PEEK[HEADER.FIELDS ($fields)])") { r ->
            val data = r.fetchData()?.second ?: return@command
            val uid = data["UID"]?.asText?.toLongOrNull() ?: return@command
            val structureValue = data["BODYSTRUCTURE"] as? ImapValue.ListV
            val header = (data["BODY[HEADER.FIELDS]"] as? ImapValue.Str)?.bytes ?: ByteArray(0)
            result.add(
                HeaderFetch(
                    uid = uid,
                    flags = flagSet(data["FLAGS"]),
                    size = data["RFC822.SIZE"]?.asText?.toLongOrNull() ?: 0L,
                    internalDate = data["INTERNALDATE"]?.asText,
                    header = header,
                    structure = structureValue?.let { BodyStructure.parse(it) },
                    structureRaw = structureValue?.toImapString() ?: ""
                )
            )
        }
        return result
    }

    /**
     * Lädt einen Abschnitt für mehrere UIDs in einem Kommando.
     * [partialBytes] begrenzt die Länge (für Vorschauen).
     */
    fun fetchSection(uids: Collection<Long>, section: String, partialBytes: Int? = null): Map<Long, ByteArray> {
        if (uids.isEmpty()) return emptyMap()
        val partial = partialBytes?.let { "<0.$it>" } ?: ""
        val key = "BODY[${section.uppercase(Locale.US)}]"
        val result = HashMap<Long, ByteArray>()
        command("UID FETCH ${uidSet(uids)} (UID BODY.PEEK[$section]$partial)") { r ->
            val data = r.fetchData()?.second ?: return@command
            val uid = data["UID"]?.asText?.toLongOrNull() ?: return@command
            val value = data[key] ?: data.entries.firstOrNull { it.key.startsWith("BODY[") }?.value
            when (value) {
                is ImapValue.Str -> result[uid] = value.bytes
                is ImapValue.Nil -> result[uid] = ByteArray(0)
                else -> {}
            }
        }
        return result
    }

    fun fetchSection(uid: Long, section: String): ByteArray? = fetchSection(listOf(uid), section)[uid]

    // ------------------------------------------------------------------ Änderungen

    fun storeFlags(uids: Collection<Long>, add: Boolean, vararg flags: String) {
        if (uids.isEmpty()) return
        val op = if (add) "+FLAGS.SILENT" else "-FLAGS.SILENT"
        command("UID STORE ${uidSet(uids)} $op (${flags.joinToString(" ")})")
    }

    /** Verschiebt Nachrichten; liefert, falls der Server es meldet, die neuen UIDs (alt → neu). */
    fun move(uids: Collection<Long>, target: String): Map<Long, Long> {
        if (uids.isEmpty()) return emptyMap()
        val set = uidSet(uids)
        val r = if (has("MOVE")) {
            command("UID MOVE $set ${quote(target)}")
        } else {
            val copy = command("UID COPY $set ${quote(target)}")
            command("UID STORE $set +FLAGS.SILENT (\\Deleted)")
            if (has("UIDPLUS")) command("UID EXPUNGE $set") else command("EXPUNGE")
            copy
        }
        val texts = r.untagged.map { it.text } + r.text
        for (t in texts) {
            val m = Regex("\\[COPYUID \\d+ ([0-9:,]+) ([0-9:,]+)\\]", RegexOption.IGNORE_CASE).find(t) ?: continue
            val src = expandUidSet(m.groupValues[1])
            val dst = expandUidSet(m.groupValues[2])
            if (src.size == dst.size) return src.zip(dst).toMap()
        }
        return emptyMap()
    }

    fun deletePermanently(uids: Collection<Long>) {
        if (uids.isEmpty()) return
        val set = uidSet(uids)
        command("UID STORE $set +FLAGS.SILENT (\\Deleted)")
        if (has("UIDPLUS")) command("UID EXPUNGE $set") else command("EXPUNGE")
    }

    fun emptyFolder(path: String) {
        val info = select(path)
        if (info.exists == 0) return
        command("STORE 1:* +FLAGS.SILENT (\\Deleted)")
        command("EXPUNGE")
    }

    /** Legt eine Nachricht in einem Ordner ab (z. B. „Gesendet“). Liefert die neue UID, falls bekannt. */
    fun append(path: String, message: ByteArray, flags: String = "(\\Seen)"): Long? {
        val tag = nextTag()
        sendLiteralCommand(tag, "APPEND ${quote(path)} $flags ", listOf(message))
        val r = readTagged(tag)
        if (!r.ok) throw ImapCommandException("Ablegen in „${ModifiedUtf7.decode(path)}“ fehlgeschlagen: ${r.text.substringAfter(' ').take(150)}")
        return Regex("\\[APPENDUID \\d+ (\\d+)\\]", RegexOption.IGNORE_CASE).find(r.text)?.groupValues?.get(1)?.toLongOrNull()
    }

    fun noop() {
        command("NOOP")
    }

    // ------------------------------------------------------------------ IDLE

    /**
     * Wartet per IDLE auf Änderungen im ausgewählten Ordner.
     * @return true, wenn sich etwas geändert hat; false nach Ablauf von [maxMillis].
     */
    fun idle(maxMillis: Long, shouldStop: () -> Boolean = { false }): Boolean {
        val tag = nextTag()
        socket.writeLine("$tag IDLE")
        val first = ImapResponse.read(socket)
        if (!first.isContinuation) {
            if (first.tag == tag) throw ImapCommandException("IDLE abgelehnt: ${first.text.take(150)}")
            // Untagged vor der Bestätigung → als Änderung werten
        }
        val event = Regex("^\\* \\d+ (EXISTS|EXPUNGE|FETCH)", RegexOption.IGNORE_CASE)
        val deadline = System.currentTimeMillis() + maxMillis
        var changed = !first.isContinuation && event.containsMatchIn(first.text)
        val originalTimeout = socket.readTimeoutMs
        try {
            while (!changed && !shouldStop()) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                socket.readTimeoutMs = remaining.coerceIn(1_000L, 60_000L).toInt()
                val r = try {
                    ImapResponse.read(socket)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                if (event.containsMatchIn(r.text)) changed = true
                if (r.text.startsWith("* BYE", true)) throw MailException("Server beendet IDLE", kind = MailException.Kind.NETWORK)
            }
        } finally {
            socket.readTimeoutMs = originalTimeout
        }
        socket.writeLine("DONE")
        val tail = readTagged(tag)
        if (tail.untagged.any { event.containsMatchIn(it.text) }) changed = true
        return changed
    }

    // ------------------------------------------------------------------ Abbau

    fun logout() {
        try {
            if (socket.isConnected) {
                socket.readTimeoutMs = 5_000
                command("LOGOUT", allowFail = true)
            }
        } catch (_: Exception) {
        } finally {
            close()
        }
    }

    override fun close() {
        selected = null
        socket.close()
    }

    // ------------------------------------------------------------------ Hilfen

    companion object {
        fun isPlainAscii(s: String) = s.all { it.code in 0x20..0x7e }

        fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        fun flagSet(value: ImapValue?): Set<String> =
            (value as? ImapValue.ListV)?.items?.mapNotNull { it.asText }?.toSet() ?: emptySet()

        /** 1,2,3,5,7,8 → "1:3,5,7:8" */
        fun uidSet(uids: Collection<Long>): String {
            val sorted = uids.toSortedSet().toList()
            val sb = StringBuilder()
            var i = 0
            while (i < sorted.size) {
                var j = i
                while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
                if (sb.isNotEmpty()) sb.append(',')
                sb.append(sorted[i])
                if (j > i) sb.append(':').append(sorted[j])
                i = j + 1
            }
            return sb.toString()
        }

        fun expandUidSet(set: String): List<Long> {
            val out = mutableListOf<Long>()
            for (part in set.split(',')) {
                val range = part.split(':')
                val a = range[0].toLongOrNull() ?: continue
                val b = range.getOrNull(1)?.toLongOrNull() ?: a
                if (a <= b) for (x in a..b) out.add(x) else for (x in a downTo b) out.add(x)
            }
            return out
        }
    }
}
