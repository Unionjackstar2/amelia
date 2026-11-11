package de.tedoki.amelia.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import de.tedoki.amelia.data.db.Account
import de.tedoki.amelia.data.db.AmeliaDatabase
import de.tedoki.amelia.data.db.Folder
import de.tedoki.amelia.data.db.Message
import de.tedoki.amelia.data.db.Pending
import de.tedoki.amelia.data.db.Protocol
import de.tedoki.amelia.data.db.pinnedSet
import de.tedoki.amelia.mail.imap.BodyStructure
import de.tedoki.amelia.mail.imap.FolderRole
import de.tedoki.amelia.mail.imap.HeaderFetch
import de.tedoki.amelia.mail.imap.ImapConnection
import de.tedoki.amelia.mail.mime.Address
import de.tedoki.amelia.mail.mime.BodyAnalyzer
import de.tedoki.amelia.mail.mime.MimePart
import de.tedoki.amelia.mail.mime.MimeUtil
import de.tedoki.amelia.mail.mime.RawMessage
import de.tedoki.amelia.mail.net.CertificateInfo
import de.tedoki.amelia.mail.net.CertificateUntrustedException
import de.tedoki.amelia.mail.net.MailException
import de.tedoki.amelia.mail.net.ServerConfig
import de.tedoki.amelia.mail.net.friendlyMessage
import de.tedoki.amelia.mail.pop3.Pop3Connection
import de.tedoki.amelia.mail.smtp.MessageBuilder
import de.tedoki.amelia.mail.smtp.OutgoingAttachment
import de.tedoki.amelia.mail.smtp.OutgoingMessage
import de.tedoki.amelia.mail.smtp.PartialDeliveryException
import de.tedoki.amelia.mail.smtp.SmtpConnection
import de.tedoki.amelia.notify.Notifier
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AttachmentInfo(
    val section: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val contentId: String?
)

data class MessageContent(
    val html: String?,
    val text: String?,
    val attachments: List<AttachmentInfo>,
    val inline: List<AttachmentInfo>
)

data class DraftAttachment(
    val name: String,
    val mimeType: String,
    val size: Long,
    val uri: String? = null,
    val sourceMessageId: Long? = null,
    val sourceSection: String? = null
)

data class Draft(
    val accountId: Long,
    val to: List<Address>,
    val cc: List<Address>,
    val bcc: List<Address>,
    val subject: String,
    val text: String,
    val attachments: List<DraftAttachment>,
    val replyToId: Long? = null,
    /** Absender (Identität). Leer = Hauptadresse des Kontos. */
    val fromName: String? = null,
    val fromEmail: String? = null
)

data class TestResult(val error: String? = null, val certificate: CertificateInfo? = null) {
    val ok: Boolean get() = error == null && certificate == null
}

data class SyncState(val running: Set<Long> = emptySet(), val lastError: String? = null) {
    val isRunning: Boolean get() = running.isNotEmpty()
}

class MailRepository private constructor(private val context: Context) {

    val db = AmeliaDatabase.get(context)
    private val accounts = db.accounts()
    private val folders = db.folders()
    private val messages = db.messages()
    val bodies = BodyStore(context)

    private val locks = ConcurrentHashMap<Long, Mutex>()
    private fun lock(accountId: Long) = locks.getOrPut(accountId) { Mutex() }

    private val _sync = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _sync.asStateFlow()

    // =================================================================== Konten

    fun flowAccounts() = accounts.flowAll()
    suspend fun allAccounts() = accounts.all()
    suspend fun account(id: Long) = accounts.get(id)

    fun incomingConfig(a: Account, passwordOverride: String? = null): ServerConfig {
        val pw = passwordOverride ?: CredentialVault.decrypt(a.passwordEnc)
            ?: throw MailException("Das gespeicherte Passwort ist nicht mehr lesbar. Bitte im Konto neu eingeben.", kind = MailException.Kind.AUTH)
        return ServerConfig(a.inHost, a.inPort, a.inSecurity, a.inUser.ifBlank { a.email }, pw, a.pinnedSet)
    }

    fun outgoingConfig(a: Account, passwordOverride: String? = null): ServerConfig {
        val pw = passwordOverride
            ?: (if (a.outPasswordEnc.isNotEmpty()) CredentialVault.decrypt(a.outPasswordEnc) else CredentialVault.decrypt(a.passwordEnc))
            ?: throw MailException("Das gespeicherte Passwort ist nicht mehr lesbar. Bitte im Konto neu eingeben.", kind = MailException.Kind.AUTH)
        return ServerConfig(a.outHost, a.outPort, a.outSecurity, a.outUser.ifBlank { a.inUser.ifBlank { a.email } }, pw, a.pinnedSet)
    }

    /** Prüft Posteingang und Postausgang. */
    suspend fun testAccount(a: Account, password: String?, outPassword: String?): TestResult = withContext(Dispatchers.IO) {
        try {
            val inCfg = incomingConfig(a, password?.ifEmpty { null })
            when (a.protocol) {
                Protocol.IMAP -> ImapConnection(inCfg).apply { open(); listFolders(); logout() }
                Protocol.POP3 -> Pop3Connection(inCfg).apply { open(); stat(); quit() }
            }
        } catch (e: CertificateUntrustedException) {
            return@withContext TestResult(certificate = e.info)
        } catch (e: Throwable) {
            return@withContext TestResult(error = "Posteingang: ${e.friendlyMessage()}")
        }
        try {
            SmtpConnection(outgoingConfig(a, outPassword?.ifEmpty { null } ?: password?.ifEmpty { null })).apply { open(); quit() }
        } catch (e: CertificateUntrustedException) {
            return@withContext TestResult(certificate = e.info)
        } catch (e: Throwable) {
            return@withContext TestResult(error = "Postausgang: ${e.friendlyMessage()}")
        }
        TestResult()
    }

    suspend fun saveAccount(a: Account, password: String?, outPassword: String?): Long = withContext(Dispatchers.IO) {
        var acc = a
        if (!password.isNullOrEmpty()) acc = acc.copy(passwordEnc = CredentialVault.encrypt(password))
        if (outPassword != null) acc = acc.copy(outPasswordEnc = if (outPassword.isEmpty()) "" else CredentialVault.encrypt(outPassword))
        if (acc.id == 0L) {
            val id = accounts.insert(acc.copy(sortOrder = accounts.all().size))
            if (acc.protocol == Protocol.POP3) ensureLocalPopFolders(id)
            id
        } else {
            accounts.update(acc)
            acc.id
        }
    }

    suspend fun deleteAccount(id: Long) = withContext(Dispatchers.IO) {
        lock(id).withLock {
            bodies.delete(messages.idsForAccount(id))
            messages.deleteForAccount(id)
            folders.deleteForAccount(id)
            accounts.delete(id)
        }
    }

    private suspend fun ensureLocalPopFolders(accountId: Long) {
        folders.insertIgnore(Folder(Folder.idFor(accountId, "INBOX"), accountId, "INBOX", "Posteingang", FolderRole.INBOX))
        folders.insertIgnore(Folder(Folder.idFor(accountId, Folder.LOCAL_SENT), accountId, Folder.LOCAL_SENT, "Gesendet", FolderRole.SENT, isLocal = true))
        folders.insertIgnore(Folder(Folder.idFor(accountId, Folder.LOCAL_TRASH), accountId, Folder.LOCAL_TRASH, "Papierkorb", FolderRole.TRASH, isLocal = true))
    }

    // =================================================================== Sync

    private fun markRunning(id: Long, running: Boolean) = _sync.update {
        it.copy(running = if (running) it.running + id else it.running - id)
    }

    suspend fun syncAll(notify: Boolean): String? = withContext(Dispatchers.IO) {
        var error: String? = null
        for (a in accounts.all()) {
            syncAccount(a.id, notify)?.let { error = "${a.label}: $it" }
        }
        _sync.update { it.copy(lastError = error) }
        error
    }

    /** Synchronisiert ein Konto vollständig. Liefert eine Fehlermeldung oder null. */
    suspend fun syncAccount(accountId: Long, notify: Boolean, onlyFolderId: String? = null): String? = withContext(Dispatchers.IO) {
        val a = accounts.get(accountId) ?: return@withContext null
        markRunning(a.id, true)
        try {
            lock(a.id).withLock {
                when (a.protocol) {
                    Protocol.IMAP -> imap(a) { c ->
                        if (c.has("IDLE") != a.supportsIdle) accounts.setSupportsIdle(a.id, c.has("IDLE"))
                        commitPendingImap(c, a)
                        pushFlags(c, a)
                        val list = if (onlyFolderId == null) refreshFolders(c, a) else folders.forAccount(a.id)
                        val targets = if (onlyFolderId != null) {
                            list.filter { it.id == onlyFolderId }
                        } else {
                            list.filter {
                                it.selectable && (it.role != FolderRole.OTHER || it.lastSync > 0)
                            }.sortedBy { if (it.role == FolderRole.INBOX) 0 else 1 }
                        }
                        for (f in targets) {
                            try {
                                syncImapFolder(c, a, f, notify && f.role == FolderRole.INBOX)
                            } catch (e: MailException) {
                                if (e.kind == MailException.Kind.NETWORK) throw e
                                Log.w(TAG, "Ordner ${f.path}: ${e.message}")
                            }
                        }
                    }
                    Protocol.POP3 -> syncPop3(a, notify)
                }
            }
            accounts.setSyncResult(a.id, System.currentTimeMillis(), null)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Sync ${a.email} fehlgeschlagen", e)
            val msg = if (e is CertificateUntrustedException) {
                "${e.message} Zum Bestätigen das Konto öffnen und „Prüfen und speichern“ tippen."
            } else e.friendlyMessage()
            accounts.setSyncResult(a.id, a.lastSync, msg)
            msg
        } finally {
            markRunning(a.id, false)
        }
    }

    suspend fun syncFolder(folderId: String): String? {
        val f = folders.get(folderId) ?: return null
        return syncAccount(f.accountId, notify = false, onlyFolderId = if (f.isLocal) null else folderId)
    }

    /** Für den Push-Dienst: Posteingang auf einer bestehenden Verbindung abgleichen. */
    suspend fun syncInboxOn(c: ImapConnection, accountId: Long) = withContext(Dispatchers.IO) {
        val a = accounts.get(accountId) ?: return@withContext
        lock(a.id).withLock {
            markRunning(a.id, true)
            try {
                commitPendingImap(c, a)
                pushFlags(c, a)
                val inbox = folders.forAccount(a.id).firstOrNull { it.role == FolderRole.INBOX } ?: return@withLock
                syncImapFolder(c, a, inbox, notify = true)
                accounts.setSyncResult(a.id, System.currentTimeMillis(), null)
            } finally {
                markRunning(a.id, false)
            }
        }
    }

    suspend fun inboxPath(accountId: Long): String =
        folders.forAccount(accountId).firstOrNull { it.role == FolderRole.INBOX }?.path ?: "INBOX"

    private inline fun <T> imap(a: Account, block: (ImapConnection) -> T): T {
        val c = ImapConnection(incomingConfig(a))
        try {
            c.open()
            return block(c)
        } finally {
            c.logout()
        }
    }

    private suspend fun refreshFolders(c: ImapConnection, a: Account): List<Folder> {
        val listed = c.listFolders()
        val existing = folders.forAccount(a.id).associateBy { it.id }
        val seen = HashSet<String>()
        // Nur je eine Rolle vergeben (manche Server melden z. B. zwei Papierkörbe)
        val usedRoles = HashSet<FolderRole>()
        for (l in listed.sortedBy { if (it.role != FolderRole.OTHER && it.path.contains(it.delimiter ?: "/")) 1 else 0 }) {
            val role = if (l.role != FolderRole.OTHER && l.role != FolderRole.INBOX && !usedRoles.add(l.role)) FolderRole.OTHER else l.role
            val id = Folder.idFor(a.id, l.path)
            seen.add(id)
            if (existing.containsKey(id)) {
                folders.updateMeta(id, l.name, role.name, l.delimiter, l.selectable)
            } else {
                folders.insertIgnore(Folder(id, a.id, l.path, l.name, role, l.delimiter, l.selectable))
            }
        }
        val gone = existing.keys.filter { it !in seen && existing[it]?.isLocal != true }
        if (gone.isNotEmpty()) {
            for (id in gone) {
                val ids = messages.idsInFolder(id)
                bodies.delete(ids)
                messages.delete(ids)
            }
            folders.delete(gone)
        }
        return folders.forAccount(a.id)
    }

    private suspend fun syncImapFolder(c: ImapConnection, a: Account, folder: Folder, notify: Boolean) {
        val f = folders.get(folder.id) ?: folder
        val info = c.select(f.path)
        var local = messages.localStates(f.id)

        if (f.uidValidity != 0L && f.uidValidity != info.uidValidity) {
            removeLocal(local.map { it.id })
            local = emptyList()
        }
        if (info.exists == 0) {
            removeLocal(local.map { it.id })
            folders.updateState(f.id, info.uidValidity, info.uidNext, info.highestModSeq, 0, System.currentTimeMillis(), false)
            return
        }
        val unchanged = local.isNotEmpty() && f.uidValidity == info.uidValidity && f.uidNext == info.uidNext &&
            f.messageCount == info.exists && info.highestModSeq > 0 && info.highestModSeq == f.highestModSeq
        if (unchanged) {
            folders.updateState(f.id, info.uidValidity, info.uidNext, info.highestModSeq, info.exists, System.currentTimeMillis(), f.hasMore)
            return
        }

        val isInitial = local.isEmpty()
        var hasMore = f.hasMore
        val newUids: List<Long>
        if (!isInitial) {
            val minUid = local.minOf { it.uid }
            val remote = c.fetchFlags("$minUid:*").filter { it.uid >= minUid }
            val remoteByUid = remote.associateBy { it.uid }
            val localByUid = local.associateBy { it.uid }
            db.withTransaction {
                removeLocal(local.filter { it.uid !in remoteByUid }.map { it.id })
                for (l in local) {
                    val r = remoteByUid[l.uid] ?: continue
                    if (!l.flagsDirty && (r.seen != l.seen || r.flagged != l.flagged || r.answered != l.answered)) {
                        messages.applyServerFlags(l.id, r.seen, r.flagged, r.answered)
                    }
                }
            }
            newUids = remote.filter { it.uid !in localByUid && !it.deleted }.map { it.uid }.sortedDescending().take(MAX_NEW)
            if (remoteByUid.isEmpty()) {
                // Alles Lokale ist weg: frisch beginnen
                return syncImapFolder(c, a, f.copy(uidValidity = 0), notify)
            }
        } else {
            newUids = c.uidsForSequenceRange(info.exists - INITIAL_WINDOW + 1, info.exists)
            hasMore = info.exists > INITIAL_WINDOW
        }

        var complete = true
        val inserted = mutableListOf<Message>()
        for (batch in newUids.sortedDescending().chunked(BATCH)) {
            try {
                inserted += fetchAndStore(c, a, f, batch)
            } catch (e: MailException) {
                if (e.kind == MailException.Kind.NETWORK) throw e
                complete = false
                Log.w(TAG, "Batch in ${f.path}: ${e.message}")
            }
        }
        folders.updateState(
            f.id, info.uidValidity, info.uidNext, if (complete) info.highestModSeq else 0L,
            info.exists, System.currentTimeMillis(), hasMore
        )
        if (notify && !isInitial) {
            val fresh = inserted.filter { !it.seen }
            if (fresh.isNotEmpty()) Notifier.newMail(context, a, fresh)
        }
    }

    /** Ältere Nachrichten eines Ordners nachladen. */
    suspend fun loadOlder(folderId: String): String? = withContext(Dispatchers.IO) {
        val f = folders.get(folderId) ?: return@withContext null
        val a = accounts.get(f.accountId) ?: return@withContext null
        if (a.protocol != Protocol.IMAP) return@withContext null
        markRunning(a.id, true)
        try {
            lock(a.id).withLock {
                imap(a) { c ->
                    c.select(f.path)
                    val min = messages.minUid(f.id) ?: return@imap
                    if (min <= 1) {
                        folders.setHasMore(f.id, false)
                        return@imap
                    }
                    val older = c.uidSearch("UID 1:${min - 1}").filter { it < min }.sortedDescending()
                    val take = older.take(OLDER_WINDOW)
                    for (batch in take.chunked(BATCH)) fetchAndStore(c, a, f, batch)
                    folders.setHasMore(f.id, older.size > take.size)
                }
            }
            null
        } catch (e: Exception) {
            e.friendlyMessage()
        } finally {
            markRunning(a.id, false)
        }
    }

    private suspend fun removeLocal(ids: List<Long>) {
        if (ids.isEmpty()) return
        bodies.delete(ids)
        ids.chunked(500).forEach { messages.delete(it) }
    }

    private class Prepared(val msg: Message, val primary: MimePart?, val layoutComplete: Boolean)

    private suspend fun fetchAndStore(c: ImapConnection, a: Account, f: Folder, uids: List<Long>): List<Message> {
        val headers = c.fetchHeaders(uids)
        val prepared = headers.map { prepare(a, f, it) }

        // Text vorab laden: kleine Teile vollständig, große nur als Vorschau.
        val full = HashMap<Long, ByteArray>()
        val partial = HashMap<Long, ByteArray>()
        prepared.filter { it.primary != null }.groupBy { it.primary!!.fetchSection }.forEach { (section, group) ->
            val (small, big) = group.partition { it.primary!!.size in 0..FULL_BODY_LIMIT }
            if (small.isNotEmpty()) full.putAll(c.fetchSection(small.map { it.msg.uid }, section))
            if (big.isNotEmpty()) partial.putAll(c.fetchSection(big.map { it.msg.uid }, section, PREVIEW_BYTES))
        }

        val result = mutableListOf<Message>()
        val bodiesToWrite = mutableListOf<Triple<Message, String, Boolean>>() // (msg, inhalt, isHtml)
        db.withTransaction {
            for (p in prepared) {
                var m = p.msg
                val part = p.primary
                val bytes = full[m.uid] ?: partial[m.uid]
                var decoded: String? = null
                if (part != null && bytes != null) {
                    decoded = MimeUtil.decodeText(MimeUtil.decodeTransfer(bytes, part.encoding), part.charset)
                    val isHtml = part.mimeType == "text/html"
                    val plain = if (isHtml) MimeUtil.htmlToText(decoded) else decoded
                    m = m.copy(snippet = MimeUtil.snippet(plain), preview = plain.take(PREVIEW_CHARS))
                    val loaded = full.containsKey(m.uid) && p.layoutComplete
                    m = m.copy(bodyLoaded = loaded)
                }
                val id = messages.insertIgnore(m)
                if (id > 0) {
                    val stored = m.copy(id = id)
                    result.add(stored)
                    if (stored.bodyLoaded && decoded != null) bodiesToWrite.add(Triple(stored, decoded, part!!.mimeType == "text/html"))
                }
            }
        }
        for ((m, content, isHtml) in bodiesToWrite) {
            if (isHtml) bodies.saveHtml(m.id, content) else bodies.saveText(m.id, content)
        }
        return result
    }

    private fun prepare(a: Account, f: Folder, h: HeaderFetch): Prepared {
        val hm = MimeUtil.parseHeaderBlock(h.header)
        val from = MimeUtil.parseAddressList(hm["from"]).firstOrNull()
        val layout = h.structure?.let { BodyAnalyzer.analyze(it) }
        val primary = layout?.primary
        val msg = Message(
            accountId = a.id,
            folderId = f.id,
            uid = h.uid,
            messageId = hm["message-id"].orEmpty().trim(),
            inReplyTo = hm["in-reply-to"].orEmpty().trim(),
            references = hm["references"].orEmpty().trim(),
            fromName = from?.name.orEmpty(),
            fromEmail = from?.email.orEmpty(),
            replyTo = MimeUtil.decodeEncodedWords(hm["reply-to"].orEmpty()),
            toList = MimeUtil.decodeEncodedWords(hm["to"].orEmpty()),
            ccList = MimeUtil.decodeEncodedWords(hm["cc"].orEmpty()),
            subject = MimeUtil.decodeEncodedWords(hm["subject"].orEmpty()).trim(),
            date = MimeUtil.parseDate(hm["date"]) ?: MimeUtil.parseDate(h.internalDate) ?: System.currentTimeMillis(),
            size = h.size,
            structure = h.structureRaw,
            hasAttachments = layout?.attachments?.isNotEmpty() == true,
            seen = h.flags.any { it.equals("\\Seen", true) },
            flagged = h.flags.any { it.equals("\\Flagged", true) },
            answered = h.flags.any { it.equals("\\Answered", true) },
            listUnsubscribe = hm["list-unsubscribe"].orEmpty(),
            authResult = MimeUtil.authResult(hm["authentication-results"])
        )
        // Vollständig nur, wenn keine weiteren Textteile zusammengesetzt werden müssen
        val complete = layout != null && (layout.html != null || layout.extraPlain.isEmpty())
        return Prepared(msg, primary, complete)
    }

    private suspend fun pushFlags(c: ImapConnection, a: Account) {
        val dirty = messages.dirty(a.id).filter { it.uid > 0 && it.pendingAction == Pending.NONE }
        if (dirty.isEmpty()) return
        val byFolder = dirty.groupBy { it.folderId }
        val folderMap = folders.forAccount(a.id).associateBy { it.id }
        for ((fid, list) in byFolder) {
            val folder = folderMap[fid] ?: continue
            if (folder.isLocal) continue
            try {
                c.select(folder.path)
                val (seen, unseen) = list.partition { it.seen }
                c.storeFlags(seen.map { it.uid }, true, "\\Seen")
                c.storeFlags(unseen.map { it.uid }, false, "\\Seen")
                val (fl, unfl) = list.partition { it.flagged }
                c.storeFlags(fl.map { it.uid }, true, "\\Flagged")
                c.storeFlags(unfl.map { it.uid }, false, "\\Flagged")
                c.storeFlags(list.filter { it.answered }.map { it.uid }, true, "\\Answered")
            } catch (e: MailException) {
                if (e.kind == MailException.Kind.NETWORK) throw e
            }
        }
        messages.clearDirty(dirty.map { it.id })
    }

    // =================================================================== POP3

    private suspend fun syncPop3(a: Account, notify: Boolean) {
        ensureLocalPopFolders(a.id)
        val inboxId = Folder.idFor(a.id, "INBOX")
        val conn = Pop3Connection(incomingConfig(a))
        try {
            conn.open()
            val count = conn.stat()
            val uidl = conn.uidl(count)
            val known = messages.knownRemoteIds(a.id).toHashSet()
            commitPendingPop3(conn, a, uidl)
            val firstSync = known.isEmpty()
            val newOnes = uidl.entries.filter { it.value !in known }.sortedByDescending { it.key }
                .take(if (firstSync) INITIAL_WINDOW else MAX_NEW)
            val inserted = mutableListOf<Message>()
            val toDelete = mutableListOf<Int>()
            for ((number, remoteId) in newOnes) {
                val raw = try { conn.retrieve(number) } catch (e: MailException) {
                    if (e.kind == MailException.Kind.NETWORK) throw e else continue
                }
                val stored = storeRawMessage(a, inboxId, remoteId, raw, seen = false)
                if (stored != null) inserted.add(stored)
                if (!a.popLeaveOnServer) toDelete.add(number)
            }
            toDelete.forEach { conn.delete(it) }
            conn.quit()
            if (notify && !firstSync && inserted.isNotEmpty()) Notifier.newMail(context, a, inserted)
        } finally {
            conn.close()
        }
    }

    private suspend fun storeRawMessage(a: Account, folderId: String, remoteId: String, raw: ByteArray, seen: Boolean): Message? {
        val rm = RawMessage(raw)
        val h = rm.headers
        val layout = BodyAnalyzer.analyze(rm.root)
        val from = MimeUtil.parseAddressList(h["from"]).firstOrNull()
        val html = layout.html?.let { rm.partText(it.section) }
        val text = if (html == null) {
            (listOfNotNull(layout.plain) + layout.extraPlain).mapNotNull { rm.partText(it.section) }.joinToString("\n\n")
        } else null
        val plain = html?.let { MimeUtil.htmlToText(it) } ?: text.orEmpty()
        val m = Message(
            accountId = a.id,
            folderId = folderId,
            remoteId = remoteId,
            messageId = h["message-id"].orEmpty().trim(),
            inReplyTo = h["in-reply-to"].orEmpty().trim(),
            references = h["references"].orEmpty().trim(),
            fromName = from?.name.orEmpty(),
            fromEmail = from?.email.orEmpty(),
            replyTo = MimeUtil.decodeEncodedWords(h["reply-to"].orEmpty()),
            toList = MimeUtil.decodeEncodedWords(h["to"].orEmpty()),
            ccList = MimeUtil.decodeEncodedWords(h["cc"].orEmpty()),
            subject = MimeUtil.decodeEncodedWords(h["subject"].orEmpty()).trim(),
            date = MimeUtil.parseDate(h["date"]) ?: System.currentTimeMillis(),
            size = raw.size.toLong(),
            snippet = MimeUtil.snippet(plain),
            preview = plain.take(PREVIEW_CHARS),
            bodyLoaded = true,
            hasAttachments = layout.attachments.isNotEmpty(),
            seen = seen,
            listUnsubscribe = h["list-unsubscribe"].orEmpty(),
            authResult = MimeUtil.authResult(h["authentication-results"])
        )
        val id = messages.insertIgnore(m)
        if (id <= 0) return null
        bodies.rawFile(id).writeBytes(raw)
        if (html != null) bodies.saveHtml(id, html) else bodies.saveText(id, text.orEmpty())
        return m.copy(id = id)
    }

    // =================================================================== Inhalte

    suspend fun content(messageId: Long): MessageContent = withContext(Dispatchers.IO) {
        val m = messages.get(messageId) ?: throw MailException("Nachricht nicht gefunden.")
        val a = accounts.get(m.accountId) ?: throw MailException("Konto nicht gefunden.")
        val root = structureOf(m)
        val layout = root?.let { BodyAnalyzer.analyze(it) }
        if (!m.bodyLoaded && a.protocol == Protocol.IMAP && layout != null) {
            val f = folders.get(m.folderId) ?: throw MailException("Ordner nicht gefunden.")
            lock(a.id).withLock {
                imap(a) { c ->
                    c.select(f.path)
                    fun load(p: MimePart): String? = c.fetchSection(m.uid, p.fetchSection)?.let {
                        MimeUtil.decodeText(MimeUtil.decodeTransfer(it, p.encoding), p.charset)
                    }
                    val html = layout.html?.let { load(it) }
                    if (html != null) {
                        bodies.saveHtml(m.id, html)
                        val plain = MimeUtil.htmlToText(html)
                        messages.markBodyLoaded(m.id, MimeUtil.snippet(plain), plain.take(PREVIEW_CHARS))
                    } else {
                        val text = (listOfNotNull(layout.plain) + layout.extraPlain).mapNotNull { load(it) }.joinToString("\n\n")
                        bodies.saveText(m.id, text)
                        messages.markBodyLoaded(m.id, MimeUtil.snippet(text), text.take(PREVIEW_CHARS))
                    }
                }
            }
        }
        val html = bodies.readHtml(m.id)
        val text = if (html == null) bodies.readText(m.id) else null
        fun info(p: MimePart) = AttachmentInfo(
            p.section, p.displayName, p.mimeType,
            if (p.encoding == "base64") p.size * 3 / 4 else p.size, p.normalizedContentId
        )
        fun referenced(p: MimePart): Boolean {
            val cid = p.normalizedContentId
            return html != null && cid != null && html.contains("cid:$cid", ignoreCase = true)
        }
        // Im HTML eingebettete Bilder (z. B. Signaturen) vorab in einem Rutsch laden
        if (html != null && root != null && a.protocol == Protocol.IMAP) {
            try {
                prefetchParts(m, a, flatten(root).filter { referenced(it) || it.normalizedContentId != null && it.mimeType.startsWith("image/") })
            } catch (e: Exception) {
                Log.w(TAG, "Inline-Bilder: ${e.message}")
            }
        }
        val inline = layout?.inline?.map { info(it) } ?: emptyList()
        // Inline-Bilder, die im HTML nicht vorkommen, als normale Anhänge zeigen;
        // umgekehrt keine „Anhänge“ zeigen, die in Wahrheit im Text eingebettet sind
        val unreferenced = layout?.inline?.filter { !referenced(it) }?.map { info(it) } ?: emptyList()
        val realAttachments = layout?.attachments?.filter { !referenced(it) }?.map { info(it) } ?: emptyList()
        MessageContent(html, text ?: if (html == null) "" else null, realAttachments + unreferenced, inline)
    }

    private fun structureOf(m: Message): MimePart? = when {
        m.structure.isNotBlank() -> BodyStructure.parse(m.structure)
        bodies.rawFile(m.id).exists() -> RawMessage(bodies.rawFile(m.id).readBytes()).root
        else -> null
    }

    private fun partFile(m: Message, part: MimePart): File {
        val dir = bodies.attachmentDir(m.id, part.section)
        val safe = part.displayName.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(120).ifBlank { "anhang" }
        return File(dir, safe)
    }

    private fun flatten(p: MimePart): List<MimePart> = if (p.children.isEmpty()) listOf(p) else p.children.flatMap { flatten(it) }

    /** Lädt mehrere Teile über eine einzige Verbindung in den Zwischenspeicher. */
    private suspend fun prefetchParts(m: Message, a: Account, parts: List<MimePart>) {
        if (bodies.rawFile(m.id).exists()) return
        val missing = parts.filter { it.size < 8_000_000 && !partFile(m, it).let { f -> f.exists() && f.length() > 0 } }
            .distinctBy { it.section }
        if (missing.isEmpty()) return
        val f = folders.get(m.folderId) ?: return
        lock(a.id).withLock {
            imap(a) { c ->
                c.select(f.path)
                for ((section, group) in missing.groupBy { it.fetchSection }) {
                    val p = group.first()
                    val bytes = c.fetchSection(m.uid, section) ?: continue
                    partFile(m, p).writeBytes(MimeUtil.decodeTransfer(bytes, p.encoding))
                }
            }
        }
    }

    /** Lädt einen Anhang (oder ein Inline-Bild) und liefert die lokale Datei. */
    suspend fun attachmentFile(messageId: Long, section: String): File = withContext(Dispatchers.IO) {
        val m = messages.get(messageId) ?: throw MailException("Nachricht nicht gefunden.")
        val part = structureOf(m)?.find(section) ?: throw MailException("Anhang nicht gefunden.")
        val file = partFile(m, part)
        if (file.exists() && file.length() > 0) return@withContext file
        val raw = bodies.rawFile(m.id)
        val data = if (raw.exists()) {
            RawMessage(raw.readBytes()).partContent(section) ?: throw MailException("Anhang nicht gefunden.")
        } else {
            val a = accounts.get(m.accountId) ?: throw MailException("Konto nicht gefunden.")
            val f = folders.get(m.folderId) ?: throw MailException("Ordner nicht gefunden.")
            lock(a.id).withLock {
                imap(a) { c ->
                    c.select(f.path)
                    val bytes = c.fetchSection(m.uid, part.fetchSection) ?: throw MailException("Der Server hat den Anhang nicht geliefert.")
                    MimeUtil.decodeTransfer(bytes, part.encoding)
                }
            }
        }
        file.writeBytes(data)
        file
    }

    /**
     * Eingebettetes Bild für die HTML-Ansicht. [key] ist eine Content-ID oder "name/<dateiname>"
     * für Bilder, die nur über ihren Dateinamen verknüpft sind.
     */
    suspend fun inlineImage(messageId: Long, key: String): Pair<String, ByteArray>? = withContext(Dispatchers.IO) {
        val m = messages.get(messageId) ?: return@withContext null
        val root = structureOf(m) ?: return@withContext null
        val parts = flatten(root)
        val part = (if (key.startsWith("name/")) {
            val name = key.removePrefix("name/").substringAfterLast('/').substringBefore('?')
            parts.firstOrNull { it.fileName.equals(name, true) }
                ?: parts.firstOrNull { it.normalizedContentId?.substringBefore('@').equals(name, true) }
        } else {
            val wanted = key.trim().removePrefix("<").removeSuffix(">")
            parts.firstOrNull { it.normalizedContentId.equals(wanted, true) }
                ?: parts.firstOrNull { it.normalizedContentId?.substringBefore('@').equals(wanted.substringBefore('@'), true) }
        }) ?: return@withContext null
        try {
            val bytes = attachmentFile(messageId, part.section).readBytes()
            sniffImageType(bytes, part.mimeType) to bytes
        } catch (e: Exception) {
            Log.w(TAG, "Inline-Bild ${part.section}: ${e.message}")
            null
        }
    }

    /** Manche Programme kennzeichnen Bilder als application/octet-stream – am Dateiinhalt erkennen. */
    private fun sniffImageType(b: ByteArray, declared: String): String {
        if (declared.startsWith("image/")) return declared
        fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0x89 && at(1) == 0x50 -> "image/png"
            at(0) == 0xFF && at(1) == 0xD8 -> "image/jpeg"
            at(0) == 0x47 && at(1) == 0x49 -> "image/gif"
            at(0) == 0x52 && at(1) == 0x49 && at(8) == 0x57 -> "image/webp"
            at(0) == 0x3C -> "image/svg+xml"
            else -> declared
        }
    }

    // =================================================================== Aktionen

    suspend fun setSeen(ids: List<Long>, seen: Boolean) = withContext(Dispatchers.IO) { messages.setSeen(ids, seen) }
    suspend fun setFlagged(ids: List<Long>, flagged: Boolean) = withContext(Dispatchers.IO) { messages.setFlagged(ids, flagged) }
    suspend fun snooze(id: Long, until: Long) = withContext(Dispatchers.IO) { messages.setSnooze(id, until) }

    /** Markiert Nachrichten zum Löschen/Verschieben. Ausgeführt wird erst nach der Rückgängig-Frist. */
    suspend fun markPending(ids: List<Long>, action: Int, targetFolderId: String? = null, immediate: Boolean = false) =
        withContext(Dispatchers.IO) {
            messages.setPending(ids, action, targetFolderId, if (immediate) 0L else System.currentTimeMillis())
        }

    suspend fun message(id: Long): Message? = withContext(Dispatchers.IO) { messages.get(id) }

    /** Lokal geänderte Markierungen sofort zum Server übertragen. */
    suspend fun pushAllFlags() = withContext(Dispatchers.IO) {
        for (a in accounts.all()) {
            if (a.protocol == Protocol.IMAP && messages.dirty(a.id).isNotEmpty()) syncFlagsQuietly(a.id)
        }
    }

    suspend fun undoPending(ids: List<Long>) = withContext(Dispatchers.IO) {
        messages.setPending(ids, Pending.NONE, null, 0)
    }

    suspend fun commitPending(): String? = withContext(Dispatchers.IO) {
        var error: String? = null
        for (accountId in messages.accountsWithPending()) {
            val a = accounts.get(accountId) ?: continue
            try {
                lock(a.id).withLock {
                    when (a.protocol) {
                        Protocol.IMAP -> imap(a) { c -> commitPendingImap(c, a) }
                        Protocol.POP3 -> {
                            val conn = Pop3Connection(incomingConfig(a))
                            try {
                                conn.open()
                                commitPendingPop3(conn, a, conn.uidl(conn.stat()))
                                conn.quit()
                            } finally {
                                conn.close()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                error = e.friendlyMessage()
            }
        }
        error
    }

    private fun ready() = System.currentTimeMillis() - UNDO_WINDOW_MS

    private suspend fun commitPendingImap(c: ImapConnection, a: Account) {
        val pending = messages.pendingFor(a.id, ready())
        if (pending.isEmpty()) return
        val folderMap = folders.forAccount(a.id).associateBy { it.id }
        val trash = folderMap.values.firstOrNull { it.role == FolderRole.TRASH }
        for ((fid, list) in pending.groupBy { it.folderId }) {
            val source = folderMap[fid]
            if (source == null) {
                removeLocal(list.map { it.id })
                continue
            }
            c.select(source.path)
            // Papierkorb: Im Papierkorb selbst (oder ohne Papierkorb) wird endgültig gelöscht
            val (toTrash, rest) = list.partition { it.pendingAction == Pending.TRASH && trash != null && trash.id != fid }
            val (deletes, moves) = rest.partition { it.pendingAction != Pending.MOVE }
            if (deletes.isNotEmpty()) {
                c.deletePermanently(deletes.map { it.uid })
                removeLocal(deletes.map { it.id })
            }
            if (toTrash.isNotEmpty()) moveImap(c, toTrash, trash!!)
            for ((target, group) in moves.groupBy { it.pendingTarget }) {
                val t = target?.let { folderMap[it] }
                if (t == null || t.id == fid) undoPending(group.map { it.id }) else moveImap(c, group, t)
            }
        }
    }

    private suspend fun moveImap(c: ImapConnection, list: List<Message>, target: Folder) {
        val mapping = c.move(list.map { it.uid }, target.path)
        val known = folders.get(target.id)?.uidValidity ?: 0L
        for (m in list) {
            val newUid = mapping[m.uid]
            if (newUid != null && known != 0L) messages.relocate(m.id, target.id, newUid) else removeLocal(listOf(m.id))
        }
    }

    private suspend fun commitPendingPop3(conn: Pop3Connection, a: Account, uidl: Map<Int, String>) {
        val pending = messages.pendingFor(a.id, ready())
        if (pending.isEmpty()) return
        val byRemote = uidl.entries.associate { it.value to it.key }
        val trashId = Folder.idFor(a.id, Folder.LOCAL_TRASH)
        for (m in pending) {
            val inTrash = m.folderId == trashId
            when {
                m.pendingAction == Pending.TRASH && !inTrash -> messages.relocate(m.id, trashId, 0)
                m.pendingAction == Pending.MOVE -> messages.relocate(m.id, m.pendingTarget ?: m.folderId, 0)
                else -> {
                    if (a.popDeleteOnServer) byRemote[m.remoteId]?.let { conn.delete(it) }
                    removeLocal(listOf(m.id))
                }
            }
        }
    }

    suspend fun emptyTrash(accountId: Long): String? = withContext(Dispatchers.IO) {
        val trash = folders.forAccount(accountId).firstOrNull { it.role == FolderRole.TRASH } ?: return@withContext null
        val ids = messages.idsInFolder(trash.id)
        if (ids.isEmpty()) return@withContext null
        messages.setPending(ids, Pending.DELETE, null, 0)
        commitPending()
    }

    // =================================================================== Versand

    suspend fun send(draft: Draft): String? = withContext(Dispatchers.IO) {
        val a = accounts.get(draft.accountId) ?: return@withContext "Absenderkonto nicht gefunden."
        val original = draft.replyToId?.let { messages.get(it) }
        val attachments = try {
            draft.attachments.map { att ->
                val data = when {
                    att.uri != null -> context.contentResolver.openInputStream(Uri.parse(att.uri))?.use { it.readBytes() }
                        ?: throw MailException("Anhang „${att.name}“ ist nicht mehr lesbar.")
                    att.sourceMessageId != null && att.sourceSection != null ->
                        attachmentFile(att.sourceMessageId, att.sourceSection).readBytes()
                    else -> ByteArray(0)
                }
                OutgoingAttachment(att.name, att.mimeType, data)
            }
        } catch (e: Exception) {
            return@withContext e.friendlyMessage()
        }
        val references = original?.let { (it.references + " " + it.messageId).trim() }
        val out = OutgoingMessage(
            from = Address(draft.fromName ?: a.senderName, draft.fromEmail?.takeIf { it.isNotBlank() } ?: a.email),
            to = draft.to, cc = draft.cc, bcc = draft.bcc,
            subject = draft.subject,
            text = draft.text,
            inReplyTo = original?.messageId?.takeIf { it.isNotBlank() },
            references = references?.takeIf { it.isNotBlank() },
            attachments = attachments
        )
        val bytes = MessageBuilder.build(out)
        var warning: String? = null
        try {
            val smtp = SmtpConnection(outgoingConfig(a))
            try {
                smtp.open()
                smtp.send(out.from.email, out.allRecipients, bytes)
                smtp.quit()
            } finally {
                smtp.close()
            }
        } catch (e: PartialDeliveryException) {
            warning = e.message
        } catch (e: Exception) {
            return@withContext e.friendlyMessage()
        }
        original?.let { messages.setAnswered(it.id) }

        when (a.protocol) {
            Protocol.IMAP -> if (a.saveSentCopy) {
                val sent = folders.forAccount(a.id).firstOrNull { it.role == FolderRole.SENT }
                if (sent != null) {
                    try {
                        lock(a.id).withLock { imap(a) { c -> c.append(sent.path, bytes) } }
                        syncAccount(a.id, notify = false, onlyFolderId = sent.id)
                    } catch (e: Exception) {
                        warning = "Gesendet, aber die Kopie in „Gesendet“ konnte nicht abgelegt werden."
                    }
                }
            }
            Protocol.POP3 -> {
                ensureLocalPopFolders(a.id)
                storeRawMessage(a, Folder.idFor(a.id, Folder.LOCAL_SENT), "sent:" + out.messageId, bytes, seen = true)
            }
        }
        if (original != null && a.protocol == Protocol.IMAP) syncFlagsQuietly(a.id)
        warning?.let { "!$it" }
    }

    private suspend fun syncFlagsQuietly(accountId: Long) {
        val a = accounts.get(accountId) ?: return
        try {
            lock(a.id).withLock { imap(a) { c -> pushFlags(c, a) } }
        } catch (_: Exception) {
        }
    }

    suspend fun contacts(q: String) = withContext(Dispatchers.IO) { messages.contacts(q) }

    companion object {
        private const val TAG = "AmeliaRepo"
        private const val INITIAL_WINDOW = 60
        private const val OLDER_WINDOW = 60
        private const val MAX_NEW = 400
        private const val BATCH = 30
        private const val FULL_BODY_LIMIT = 256 * 1024L
        private const val PREVIEW_BYTES = 6000
        private const val PREVIEW_CHARS = 4000
        const val UNDO_WINDOW_MS = 5_000L

        @Volatile private var instance: MailRepository? = null
        fun get(context: Context): MailRepository = instance ?: synchronized(this) {
            instance ?: MailRepository(context.applicationContext).also { instance = it }
        }
    }
}
