package de.tedoki.amelia.ui

import android.app.Application
import de.tedoki.amelia.data.db.senderDisplay
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.tedoki.amelia.data.AppSettings
import de.tedoki.amelia.data.DeskRange
import de.tedoki.amelia.data.Draft
import de.tedoki.amelia.data.DraftAttachment
import de.tedoki.amelia.data.MailRepository
import de.tedoki.amelia.data.SwipeAction
import de.tedoki.amelia.data.TestResult
import de.tedoki.amelia.data.db.identityList
import de.tedoki.amelia.data.MessageContent
import de.tedoki.amelia.data.SyncMode
import de.tedoki.amelia.data.ThemeMode
import de.tedoki.amelia.data.db.Account
import de.tedoki.amelia.data.db.Contact
import de.tedoki.amelia.data.db.Folder
import de.tedoki.amelia.data.db.Message
import de.tedoki.amelia.data.db.Pending
import de.tedoki.amelia.mail.imap.FolderRole
import de.tedoki.amelia.mail.mime.Address
import de.tedoki.amelia.mail.mime.MimeUtil
import de.tedoki.amelia.notify.Notifier
import de.tedoki.amelia.sync.SyncScheduler
import de.tedoki.amelia.ui.components.Fmt
import java.util.Calendar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class MainMode { DESK, INBOX }

enum class ComposeMode { NEW, REPLY, REPLY_ALL, FORWARD }

data class ComposeArgs(val mode: ComposeMode = ComposeMode.NEW, val sourceId: Long? = null, val to: String? = null)

data class ComposeInit(
    val accountId: Long,
    val to: String,
    val cc: String,
    val subject: String,
    val body: String,
    val attachments: List<DraftAttachment>,
    val replyToId: Long?,
    /** Gewählte Absenderadresse (Identität). */
    val fromEmail: String = ""
)

sealed interface Screen {
    data class Detail(val id: Long) : Screen
    data class Compose(val args: ComposeArgs) : Screen
    data object Settings : Screen
    data class AccountEditor(val id: Long?) : Screen
}

sealed interface Mailbox {
    data object Unified : Mailbox
    data object Flagged : Mailbox
    data object Snoozed : Mailbox
    data class InFolder(val folderId: String) : Mailbox
}

data class UndoInfo(val ids: List<Long>, val text: String, val token: Long)

@OptIn(ExperimentalCoroutinesApi::class)
class AmeliaViewModel(app: Application) : AndroidViewModel(app) {

    val repo = MailRepository.get(app)
    val settings = AppSettings.get(app)
    private val dao = repo.db.messages()

    private fun <T> kotlinx.coroutines.flow.Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    // ---------------------------------------------------------------- Grunddaten

    val accounts: StateFlow<List<Account>?> = repo.flowAccounts().map<List<Account>, List<Account>?> { it }.state(null)
    val folders: StateFlow<List<Folder>> = repo.db.folders().flowAll().state(emptyList())
    val unread: StateFlow<Map<String, Int>> = dao.flowUnreadCounts().map { l -> l.associate { it.folderId to it.count } }.state(emptyMap())
    val syncState = repo.syncState

    /** Minütlicher Takt, damit geparkte Mails pünktlich zurückkommen. */
    private val now: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis() / 60_000L * 60_000L)
            delay(30_000)
        }
    }.state(System.currentTimeMillis())

    // ---------------------------------------------------------------- Navigation

    private val _mode = MutableStateFlow(if (settings.startInInbox.value) MainMode.INBOX else MainMode.DESK)
    val mode: StateFlow<MainMode> = _mode.asStateFlow()

    private val _stack = MutableStateFlow<List<Screen>>(emptyList())
    val stack: StateFlow<List<Screen>> = _stack.asStateFlow()

    fun setMode(m: MainMode) { _mode.value = m }
    fun push(s: Screen) { _stack.value = _stack.value + s }
    fun pop(): Boolean {
        if (_stack.value.isEmpty()) return false
        _stack.value = _stack.value.dropLast(1)
        return true
    }
    fun replaceTop(s: Screen) { _stack.value = _stack.value.dropLast(1) + s }

    fun handleBack(): Boolean {
        if (pop()) return true
        if (searchQuery.value.isNotEmpty()) { setSearch(""); return true }
        val defaultMode = if (settings.startInInbox.value) MainMode.INBOX else MainMode.DESK
        if (_mode.value != defaultMode) { _mode.value = defaultMode; return true }
        return false
    }

    // ---------------------------------------------------------------- Schreibtisch (Karten)

    private val _deskIndex = MutableStateFlow(0)
    val deskIndex: StateFlow<Int> = _deskIndex.asStateFlow()
    fun setDeskIndex(i: Int) { _deskIndex.value = i }

    /** Schreibtisch zeigt statt der Eingänge die geparkten Mails. */
    private val _deskSnoozed = MutableStateFlow(false)
    val deskSnoozed: StateFlow<Boolean> = _deskSnoozed.asStateFlow()
    fun setDeskSnoozed(v: Boolean) {
        _deskSnoozed.value = v
        _deskIndex.value = 0
    }

    val snoozedCount: StateFlow<Int> = now.flatMapLatest { dao.flowSnoozedCount(it) }.state(0)

    val deskMessages: StateFlow<List<Message>> =
        combine(settings.deskRange, settings.deskUnreadOnly, now, _deskSnoozed) { r, u, n, z -> DeskQuery(r, u, n, z) }
            .flatMapLatest { q ->
                if (q.snoozed) dao.flowSnoozed(q.now) else dao.flowDesk(sinceFor(q.range), q.now, q.unreadOnly)
            }
            .state(emptyList())

    private data class DeskQuery(val range: DeskRange, val unreadOnly: Boolean, val now: Long, val snoozed: Boolean)

    private fun sinceFor(range: DeskRange): Long {
        if (range.days < 0) return 0
        return Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -range.days)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    // ---------------------------------------------------------------- Postfach (klassisch)

    private val _mailbox = MutableStateFlow<Mailbox>(Mailbox.Unified)
    val mailbox: StateFlow<Mailbox> = _mailbox.asStateFlow()
    private val limit = MutableStateFlow(150)

    fun openMailbox(m: Mailbox) {
        _mailbox.value = m
        limit.value = 150
        if (m is Mailbox.InFolder) {
            val f = folders.value.firstOrNull { it.id == m.folderId }
            if (f != null && f.lastSync == 0L && !f.isLocal) refresh()
        }
    }

    val mailboxMessages: StateFlow<List<Message>?> = combine(_mailbox, limit, now) { m, l, n -> Triple(m, l, n) }
        .flatMapLatest { (m, l, n) ->
            when (m) {
                Mailbox.Unified -> dao.flowUnifiedInbox(n, l)
                Mailbox.Flagged -> dao.flowFlagged()
                Mailbox.Snoozed -> dao.flowSnoozed(n)
                is Mailbox.InFolder -> dao.flowFolder(m.folderId, n, l)
            }
        }.map<List<Message>, List<Message>?> { it }.state(null)

    fun showMore() { limit.value += 150 }

    fun loadOlder() {
        val m = _mailbox.value as? Mailbox.InFolder ?: return
        viewModelScope.launch {
            limit.value += 60
            repo.loadOlder(m.folderId)?.let { toast(it) }
        }
    }

    fun mailboxTitle(m: Mailbox, folders: List<Folder>, accounts: List<Account>): String = when (m) {
        Mailbox.Unified -> "Posteingang"
        Mailbox.Flagged -> "Markiert"
        Mailbox.Snoozed -> "Geparkt"
        is Mailbox.InFolder -> folders.firstOrNull { it.id == m.folderId }?.let { f ->
            if (f.role == FolderRole.INBOX && accounts.size > 1) accounts.firstOrNull { it.id == f.accountId }?.label ?: f.name else f.name
        } ?: "Ordner"
    }

    // ---------------------------------------------------------------- Suche

    private val _search = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _search.asStateFlow()
    fun setSearch(q: String) { _search.value = q }
    val searchResults: StateFlow<List<Message>> = _search
        .flatMapLatest { q -> if (q.trim().length < 2) flowOf(emptyList()) else dao.flowSearch(q.trim()) }
        .state(emptyList())

    // ---------------------------------------------------------------- Meldungen

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toasts: SharedFlow<String> = _toasts
    fun toast(text: String) { _toasts.tryEmit(text) }

    private val _undo = MutableStateFlow<UndoInfo?>(null)
    val undo: StateFlow<UndoInfo?> = _undo.asStateFlow()
    private var commitJob: Job? = null

    // ---------------------------------------------------------------- Sync

    fun refresh() {
        viewModelScope.launch {
            val m = _mailbox.value
            val error = if (_mode.value == MainMode.INBOX && m is Mailbox.InFolder) repo.syncFolder(m.folderId) else repo.syncAll(notify = false)
            error?.let { toast(it) }
        }
    }

    fun onAppForeground() {
        viewModelScope.launch {
            if (repo.allAccounts().isEmpty()) return@launch
            SyncScheduler.apply(getApplication<Application>(), settings.syncMode.value)
            repo.commitPending()
            repo.syncAll(notify = false)
        }
    }

    // ---------------------------------------------------------------- Aktionen

    fun open(message: Message) {
        push(Screen.Detail(message.id))
        Notifier.cancel(getApplication<Application>(), message.id)
        if (!message.seen) setSeen(listOf(message.id), true)
        // Zurückgekehrte Parkmail gilt mit dem Öffnen als erledigt
        if (message.snoozedUntil in 1..System.currentTimeMillis()) {
            viewModelScope.launch { repo.snooze(message.id, 0) }
        }
    }

    fun openById(id: Long) {
        viewModelScope.launch {
            repo.message(id)?.let { open(it) }
        }
    }

    suspend fun content(id: Long): Result<MessageContent> = runCatching { repo.content(id) }

    fun setSeen(ids: List<Long>, seen: Boolean) {
        viewModelScope.launch {
            repo.setSeen(ids, seen)
            flushSoon()
        }
    }

    fun setFlagged(ids: List<Long>, flagged: Boolean) {
        viewModelScope.launch {
            repo.setFlagged(ids, flagged)
            flushSoon()
        }
    }

    private var flushJob: Job? = null
    private fun flushSoon() {
        flushJob?.cancel()
        flushJob = viewModelScope.launch {
            delay(1_500)
            repo.pushAllFlags()
        }
    }

    /** In den Papierkorb (im Papierkorb: endgültig löschen). */
    fun delete(messages: List<Message>) {
        if (messages.isEmpty()) return
        val trashIds = folders.value.filter { it.role == FolderRole.TRASH }.map { it.id }.toSet()
        val permanent = messages.all { it.folderId in trashIds }
        schedule(
            messages.map { it.id },
            if (permanent) Pending.DELETE else Pending.TRASH,
            null,
            when {
                permanent && messages.size == 1 -> "Endgültig gelöscht"
                permanent -> "${messages.size} Nachrichten endgültig gelöscht"
                messages.size == 1 -> "In den Papierkorb verschoben"
                else -> "${messages.size} Nachrichten im Papierkorb"
            }
        )
    }

    fun archive(messages: List<Message>) {
        val byAccount = messages.groupBy { it.accountId }
        var missing = false
        for ((accountId, list) in byAccount) {
            val archive = folders.value.firstOrNull { it.accountId == accountId && it.role == FolderRole.ARCHIVE }
            if (archive == null) { missing = true; continue }
            schedule(list.map { it.id }, Pending.MOVE, archive.id, if (list.size == 1) "Archiviert" else "${list.size} archiviert")
        }
        if (missing) toast("Für dieses Konto gibt es keinen Archiv-Ordner. Über „Verschieben“ einen Ordner wählen.")
    }

    fun spam(messages: List<Message>) {
        var missing = false
        for ((accountId, list) in messages.groupBy { it.accountId }) {
            val junk = folders.value.firstOrNull { it.accountId == accountId && it.role == FolderRole.JUNK }
            if (junk == null) { missing = true; continue }
            val movable = list.filter { it.folderId != junk.id }
            if (movable.isEmpty()) continue
            schedule(movable.map { it.id }, Pending.MOVE, junk.id, if (movable.size == 1) "Als Spam verschoben" else "${movable.size} als Spam verschoben")
        }
        if (missing) toast("Für dieses Konto gibt es keinen Spam-Ordner.")
    }

    /**
     * Prüft, ob eine Wischaktion die Nachricht aus der Ansicht entfernt.
     * Liefert eine Meldung, falls die Aktion hier nicht möglich ist.
     */
    fun swipeProblem(action: SwipeAction, m: Message): String? = when (action) {
        SwipeAction.ARCHIVE -> folders.value.firstOrNull { it.accountId == m.accountId && it.role == FolderRole.ARCHIVE }
            .let { if (it == null) "Für dieses Konto gibt es keinen Archiv-Ordner." else if (it.id == m.folderId) "Liegt schon im Archiv." else null }
        SwipeAction.SPAM -> folders.value.firstOrNull { it.accountId == m.accountId && it.role == FolderRole.JUNK }
            .let { if (it == null) "Für dieses Konto gibt es keinen Spam-Ordner." else if (it.id == m.folderId) "Liegt schon im Spam-Ordner." else null }
        else -> null
    }

    /** Aktionen, die die Nachricht aus der aktuellen Ansicht nehmen. */
    fun removes(action: SwipeAction) = action == SwipeAction.TRASH || action == SwipeAction.ARCHIVE || action == SwipeAction.SPAM

    /** Führt eine Wischaktion ohne Rückfrage aus (Parken und Verschieben brauchen eine Auswahl in der Oberfläche). */
    fun applySwipe(action: SwipeAction, m: Message) {
        when (action) {
            SwipeAction.TRASH -> delete(listOf(m))
            SwipeAction.ARCHIVE -> archive(listOf(m))
            SwipeAction.SPAM -> spam(listOf(m))
            SwipeAction.TOGGLE_READ -> setSeen(listOf(m.id), !m.seen)
            SwipeAction.FLAG -> setFlagged(listOf(m.id), !m.flagged)
            SwipeAction.SNOOZE, SwipeAction.MOVE, SwipeAction.NONE -> {}
        }
    }

    fun move(messages: List<Message>, target: Folder) {
        val list = messages.filter { it.accountId == target.accountId && it.folderId != target.id }
        if (list.isEmpty()) return
        schedule(list.map { it.id }, Pending.MOVE, target.id, "Nach „${target.name}“ verschoben")
    }

    private fun schedule(ids: List<Long>, action: Int, target: String?, text: String) {
        val token = System.nanoTime()
        viewModelScope.launch {
            repo.markPending(ids, action, target)
            _undo.value = UndoInfo(ids, text, token)
            ids.forEach { Notifier.cancel(getApplication<Application>(), it) }
            commitJob?.cancel()
            commitJob = viewModelScope.launch {
                delay(MailRepository.UNDO_WINDOW_MS + 300)
                if (_undo.value?.token == token) _undo.value = null
                repo.commitPending()?.let { toast("Konnte Server nicht erreichen – wird später nachgeholt. ($it)") }
            }
        }
    }

    fun undoLast() {
        val u = _undo.value ?: return
        _undo.value = null
        viewModelScope.launch { repo.undoPending(u.ids) }
    }

    fun dismissUndo() { _undo.value = null }

    fun snooze(message: Message, until: Long) {
        viewModelScope.launch {
            repo.snooze(message.id, until)
            SyncScheduler.snoozeReminder(getApplication<Application>(), message.id, until)
            toast("Geparkt bis ${Fmt.short(until)}${if (Fmt.short(until).contains(':')) "" else ", " + Fmt.timeOnly(until)}")
        }
    }

    fun unsnooze(message: Message) {
        viewModelScope.launch {
            repo.snooze(message.id, 0)
            SyncScheduler.cancelSnoozeReminder(getApplication<Application>(), message.id)
        }
    }

    fun emptyTrash(accountId: Long) {
        viewModelScope.launch {
            repo.emptyTrash(accountId)?.let { toast(it) } ?: toast("Papierkorb geleert")
        }
    }

    // ---------------------------------------------------------------- Verfassen

    suspend fun contacts(q: String): List<Contact> = if (q.length < 2) emptyList() else repo.contacts(q)

    suspend fun prepareCompose(args: ComposeArgs): ComposeInit {
        val accs = accounts.value?.takeIf { it.isNotEmpty() } ?: repo.allAccounts()
        val source = args.sourceId?.let { repo.message(it) }
        val account = source?.let { s -> accs.firstOrNull { it.id == s.accountId } } ?: accs.firstOrNull()
            ?: return ComposeInit(0, args.to.orEmpty(), "", "", "", emptyList(), null)
        val signature = account.signature.takeIf { it.isNotBlank() }?.let { "\n\n-- \n$it" } ?: ""
        if (source == null || args.mode == ComposeMode.NEW) {
            return ComposeInit(account.id, args.to.orEmpty(), "", "", signature, emptyList(), null)
        }
        val content = runCatching { repo.content(source.id) }.getOrNull()
        val originalText = content?.text ?: content?.html?.let { MimeUtil.htmlToText(it) } ?: source.preview
        val own = accs.flatMap { a -> a.identityList().map { it.email.lowercase() } }.toSet()
        // Antwort von der Adresse, an die die Mail ging (z. B. ein Alias)
        val recipients = (MimeUtil.parseAddressList(source.toList) + MimeUtil.parseAddressList(source.ccList)).map { it.email.lowercase() }
        val fromIdentity = account.identityList().firstOrNull { it.email.lowercase() in recipients }?.email ?: account.email
        return when (args.mode) {
            ComposeMode.REPLY, ComposeMode.REPLY_ALL -> {
                val replyTarget = MimeUtil.parseAddressList(source.replyTo).ifEmpty { listOf(Address(source.fromName, source.fromEmail)) }
                val to = (replyTarget + if (args.mode == ComposeMode.REPLY_ALL) MimeUtil.parseAddressList(source.toList) else emptyList())
                    .filter { it.email.lowercase() !in own || replyTarget.contains(it) }
                    .distinctBy { it.email.lowercase() }
                val cc = if (args.mode == ComposeMode.REPLY_ALL) {
                    MimeUtil.parseAddressList(source.ccList).filter { it.email.lowercase() !in own && to.none { t -> t.email.equals(it.email, true) } }
                } else emptyList()
                val quote = originalText.lines().joinToString("\n") { "> $it" }
                ComposeInit(
                    account.id,
                    to.joinToString(", "),
                    cc.joinToString(", "),
                    prefixed("Re: ", source.subject),
                    "$signature\n\nAm ${Fmt.long(source.date)} schrieb ${source.senderDisplay}:\n$quote",
                    emptyList(),
                    source.id,
                    fromIdentity
                )
            }
            else -> {
                val header = buildString {
                    append("\n\n-------- Weitergeleitete Nachricht --------\n")
                    append("Von: ${Address(source.fromName, source.fromEmail)}\n")
                    append("Datum: ${Fmt.long(source.date)}\n")
                    append("Betreff: ${source.subject}\n")
                    if (source.toList.isNotBlank()) append("An: ${source.toList}\n")
                    append("\n")
                }
                val atts = content?.attachments?.map {
                    DraftAttachment(it.name, it.mimeType, it.size, sourceMessageId = source.id, sourceSection = it.section)
                } ?: emptyList()
                ComposeInit(account.id, args.to.orEmpty(), "", prefixed("Fwd: ", source.subject), signature + header + originalText, atts, null, fromIdentity)
            }
        }
    }

    private fun prefixed(prefix: String, subject: String): String {
        val s = subject.trim()
        val lower = s.lowercase()
        val isReply = listOf("re:", "aw:").any { lower.startsWith(it) }
        val isForward = listOf("fwd:", "wg:", "fw:").any { lower.startsWith(it) }
        return when {
            prefix.startsWith("Re") && isReply -> s
            prefix.startsWith("Fwd") && isForward -> s
            else -> prefix + s
        }
    }

    fun send(draft: Draft, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val result = repo.send(draft)
            when {
                result == null -> { toast("Gesendet"); onDone(null) }
                result.startsWith("!") -> { toast(result.drop(1)); onDone(null) }
                else -> onDone(result)
            }
        }
    }

    // ---------------------------------------------------------------- Konten

    suspend fun testAccount(a: Account, password: String?, outPassword: String?): TestResult = repo.testAccount(a, password, outPassword)

    fun saveAccount(a: Account, password: String?, outPassword: String?, onDone: () -> Unit) {
        viewModelScope.launch {
            val id = try {
                repo.saveAccount(a, password, outPassword)
            } catch (e: Throwable) {
                toast("Konto konnte nicht gespeichert werden: ${e.message ?: e.javaClass.simpleName}")
                return@launch
            }
            onDone()
            SyncScheduler.apply(getApplication<Application>(), settings.syncMode.value)
            repo.syncAccount(id, notify = false)?.let { toast(it) }
        }
    }

    fun deleteAccount(id: Long) {
        viewModelScope.launch {
            repo.deleteAccount(id)
            _mailbox.value = Mailbox.Unified
        }
    }

    // ---------------------------------------------------------------- Einstellungen

    fun setTheme(v: ThemeMode) = settings.setTheme(v)
    fun setDynamic(v: Boolean) = settings.setDynamic(v)
    fun setLoadImages(v: Boolean) = settings.setLoadImages(v)
    fun setStartInInbox(v: Boolean) = settings.setStartInInbox(v)
    fun setRange(v: DeskRange) { settings.setRange(v); _deskSnoozed.value = false; _deskIndex.value = 0 }
    fun setUnreadOnly(v: Boolean) { settings.setUnreadOnly(v); _deskIndex.value = 0 }
    fun setSyncMode(v: SyncMode) {
        settings.setSync(v)
        SyncScheduler.apply(getApplication<Application>(), v)
    }
}
