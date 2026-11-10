package de.tedoki.amelia.ui.inbox

import androidx.activity.compose.BackHandler
import de.tedoki.amelia.data.db.senderDisplay
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AllInbox
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Attachment
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.tedoki.amelia.data.db.Account
import de.tedoki.amelia.data.db.Folder
import de.tedoki.amelia.data.db.Message
import de.tedoki.amelia.mail.imap.FolderRole
import de.tedoki.amelia.ui.AmeliaViewModel
import de.tedoki.amelia.ui.ComposeArgs
import de.tedoki.amelia.ui.Mailbox
import de.tedoki.amelia.ui.Screen
import de.tedoki.amelia.ui.components.AccountDot
import de.tedoki.amelia.ui.components.Avatar
import de.tedoki.amelia.ui.components.EmptyState
import de.tedoki.amelia.ui.components.Fmt
import de.tedoki.amelia.ui.components.GlassIconButton
import de.tedoki.amelia.ui.components.GlassSheet
import de.tedoki.amelia.ui.components.MorphFab
import de.tedoki.amelia.ui.components.MorphLoader
import de.tedoki.amelia.ui.components.Pill
import de.tedoki.amelia.ui.components.SheetRow
import de.tedoki.amelia.ui.theme.Brand
import de.tedoki.amelia.ui.theme.glass
import java.util.Calendar
import kotlinx.coroutines.launch

fun roleIcon(role: FolderRole): ImageVector = when (role) {
    FolderRole.INBOX -> Icons.Outlined.Inbox
    FolderRole.SENT -> Icons.Outlined.Send
    FolderRole.DRAFTS -> Icons.Outlined.Drafts
    FolderRole.TRASH -> Icons.Outlined.DeleteOutline
    FolderRole.JUNK -> Icons.Outlined.Report
    FolderRole.ARCHIVE -> Icons.Outlined.Archive
    FolderRole.OTHER -> Icons.Outlined.Folder
}

private fun roleOrder(role: FolderRole) = when (role) {
    FolderRole.INBOX -> 0; FolderRole.DRAFTS -> 1; FolderRole.SENT -> 2; FolderRole.ARCHIVE -> 3
    FolderRole.JUNK -> 4; FolderRole.TRASH -> 5; FolderRole.OTHER -> 6
}

fun sortedFolders(list: List<Folder>): List<Folder> =
    list.filter { it.selectable }.sortedWith(compareBy<Folder>({ roleOrder(it.role) }, { it.path.lowercase() }))

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun InboxScreen(vm: AmeliaViewModel, headerDrag: Modifier, onBackToDesk: () -> Unit) {
    val accounts by vm.accounts.collectAsState()
    val folders by vm.folders.collectAsState()
    val unread by vm.unread.collectAsState()
    val mailbox by vm.mailbox.collectAsState()
    val list by vm.mailboxMessages.collectAsState()
    val sync by vm.syncState.collectAsState()
    val query by vm.searchQuery.collectAsState()
    val results by vm.searchResults.collectAsState()

    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var selection by remember { mutableStateOf(setOf<Long>()) }
    var searchOpen by remember { mutableStateOf(false) }
    var moveFor by remember { mutableStateOf<List<Message>?>(null) }
    var confirmEmptyTrash by remember { mutableStateOf<Account?>(null) }

    val accs = accounts ?: emptyList()
    val accountMap = accs.associateBy { it.id }
    val searching = searchOpen && query.trim().length >= 2
    val shown = if (searching) results else list ?: emptyList()
    val selected = shown.filter { it.id in selection }
    val currentFolder = (mailbox as? Mailbox.InFolder)?.let { m -> folders.firstOrNull { it.id == m.folderId } }

    BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = searchOpen && selection.isEmpty()) { searchOpen = false; vm.setSearch("") }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerShape = RoundedCornerShape(topEnd = 34.dp, bottomEnd = 34.dp)
            ) {
                FolderDrawer(
                    accounts = accs,
                    folders = folders,
                    unread = unread,
                    current = mailbox,
                    onPick = { m ->
                        vm.openMailbox(m)
                        selection = emptySet()
                        scope.launch { drawer.close() }
                    },
                    onEmptyTrash = { confirmEmptyTrash = it },
                    onAddAccount = { vm.push(Screen.AccountEditor(null)); scope.launch { drawer.close() } },
                    onSettings = { vm.push(Screen.Settings); scope.launch { drawer.close() } }
                )
            }
        }
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                // Kopfbereich (zum Schreibtisch nach unten ziehbar)
                AnimatedContent(
                    targetState = selection.isNotEmpty(),
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "header"
                ) { selecting ->
                    if (selecting) {
                        SelectionBar(
                            count = selected.size,
                            onClose = { selection = emptySet() },
                            onRead = {
                                val allSeen = selected.all { it.seen }
                                vm.setSeen(selected.map { it.id }, !allSeen); selection = emptySet()
                            },
                            onFlag = {
                                val allFlagged = selected.all { it.flagged }
                                vm.setFlagged(selected.map { it.id }, !allFlagged); selection = emptySet()
                            },
                            onArchive = { vm.archive(selected); selection = emptySet() },
                            onMove = { moveFor = selected },
                            onDelete = { vm.delete(selected); selection = emptySet() }
                        )
                    } else {
                        Column(Modifier.then(headerDrag)) {
                            Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(36.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.outlineVariant))
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                GlassIconButton(Icons.Outlined.Menu, "Ordner") { scope.launch { drawer.open() } }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        vm.mailboxTitle(mailbox, folders, accs),
                                        style = MaterialTheme.typography.headlineMedium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    val count = shown.count { !it.seen }
                                    if (count > 0) {
                                        Text("$count ungelesen", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                GlassIconButton(Icons.Outlined.Search, "Suchen") { searchOpen = !searchOpen; if (!searchOpen) vm.setSearch("") }
                                Spacer(Modifier.width(8.dp))
                                GlassIconButton(Icons.Outlined.ViewCarousel, "Kartenansicht", onClick = onBackToDesk)
                            }
                            if (searchOpen) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).height(50.dp)
                                        .glass(RoundedCornerShape(25.dp)).padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(10.dp))
                                    Box(Modifier.weight(1f)) {
                                        if (query.isEmpty()) Text("In allen Nachrichten suchen", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        BasicTextField(
                                            query, vm::setSearch, singleLine = true,
                                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            } else {
                                Spacer(Modifier.height(10.dp))
                            }
                        }
                    }
                }

                val listState = rememberLazyListState()
                PullToRefreshBox(
                    isRefreshing = sync.isRunning,
                    onRefresh = vm::refresh,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    when {
                        list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MorphLoader(size = 52.dp) }
                        shown.isEmpty() -> Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center
                        ) {
                            EmptyState(
                                icon = when {
                                    searching -> Icons.Outlined.Search
                                    mailbox == Mailbox.Snoozed -> Icons.Outlined.Schedule
                                    mailbox == Mailbox.Flagged -> Icons.Outlined.StarOutline
                                    else -> Icons.Outlined.Inbox
                                },
                                title = when {
                                    searching -> "Nichts gefunden"
                                    mailbox == Mailbox.Snoozed -> "Nichts geparkt"
                                    mailbox == Mailbox.Flagged -> "Nichts markiert"
                                    else -> "Keine Nachrichten"
                                },
                                text = when {
                                    searching -> "Gesucht wird in Betreff, Absender und Text."
                                    mailbox == Mailbox.Snoozed -> "Auf dem Schreibtisch eine Karte nach unten wischen, um sie zu parken."
                                    else -> "Zum Aktualisieren nach unten ziehen."
                                }
                            )
                        }
                        else -> LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 120.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            var lastGroup: String? = null
                            val withHeaders = mutableListOf<Any>()
                            for (m in shown) {
                                val g = if (mailbox == Mailbox.Snoozed) "Geparkt" else dayGroup(m.date)
                                if (g != lastGroup && !searching) { withHeaders.add(g); lastGroup = g }
                                withHeaders.add(m)
                            }
                            items(withHeaders, key = { if (it is Message) it.id else "h_$it" }) { item ->
                                if (item is String) {
                                    Text(
                                        item, style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 12.dp, top = 18.dp, bottom = 8.dp)
                                    )
                                } else {
                                    val m = item as Message
                                    SwipeRow(
                                        message = m,
                                        account = accountMap[m.accountId],
                                        showAccount = accs.size > 1,
                                        selected = m.id in selection,
                                        selectionMode = selection.isNotEmpty(),
                                        onClick = {
                                            if (selection.isNotEmpty()) {
                                                selection = if (m.id in selection) selection - m.id else selection + m.id
                                            } else {
                                                vm.open(m)
                                            }
                                        },
                                        onLongClick = { selection = selection + m.id },
                                        onDelete = { vm.delete(listOf(m)) },
                                        onToggleRead = { vm.setSeen(listOf(m.id), !m.seen) },
                                        onUnsnooze = if (mailbox == Mailbox.Snoozed) ({ vm.unsnooze(m) }) else null
                                    )
                                }
                            }
                            if (!searching && mailbox is Mailbox.InFolder && (currentFolder?.hasMore == true || shown.size >= 150)) {
                                item(key = "more") {
                                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                        if (sync.isRunning) MorphLoader(size = 32.dp)
                                        else Pill("Ältere Nachrichten laden", false, onClick = {
                                            if (shown.size >= 150 && currentFolder?.hasMore != true) vm.showMore() else vm.loadOlder()
                                        })
                                    }
                                }
                            } else if (!searching && shown.size >= 150) {
                                item(key = "more") {
                                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                        Pill("Mehr anzeigen", false, onClick = vm::showMore)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (selection.isEmpty()) {
                MorphFab(
                    Icons.Outlined.Edit, "Schreiben",
                    modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp)
                ) { vm.push(Screen.Compose(ComposeArgs())) }
            }
        }
    }

    moveFor?.let { msgs ->
        val accountId = msgs.first().accountId
        val targets = sortedFolders(folders.filter { it.accountId == accountId && it.id != msgs.first().folderId })
        GlassSheet("Verschieben nach", onDismiss = { moveFor = null }) {
            if (msgs.any { it.accountId != accountId }) {
                Text("Nur Nachrichten eines Kontos lassen sich gemeinsam verschieben.", color = MaterialTheme.colorScheme.error)
            }
            targets.forEach { f ->
                SheetRow(roleIcon(f.role), f.name, f.path.takeIf { it != f.name && !f.isLocal }) {
                    vm.move(msgs, f)
                    moveFor = null
                    selection = emptySet()
                }
            }
        }
    }

    confirmEmptyTrash?.let { acc ->
        AlertDialog(
            onDismissRequest = { confirmEmptyTrash = null },
            title = { Text("Papierkorb leeren?") },
            text = { Text("Alle Nachrichten im Papierkorb von „${acc.label}“ werden endgültig gelöscht – auch auf dem Server.") },
            confirmButton = {
                TextButton(onClick = { vm.emptyTrash(acc.id); confirmEmptyTrash = null }) { Text("Endgültig löschen", color = Brand.Coral) }
            },
            dismissButton = { TextButton(onClick = { confirmEmptyTrash = null }) { Text("Abbrechen") } }
        )
    }
}

private fun dayGroup(ts: Long): String {
    val start = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val day = 86_400_000L
    return when {
        ts >= start -> "Heute"
        ts >= start - day -> "Gestern"
        ts >= start - 6 * day -> "Diese Woche"
        ts >= start - 30 * day -> "Diesen Monat"
        else -> "Älter"
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onRead: () -> Unit,
    onFlag: () -> Unit,
    onArchive: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)
            .glass(RoundedCornerShape(30.dp), tint = MaterialTheme.colorScheme.primary).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassIconButton(Icons.Outlined.Close, "Auswahl beenden", size = 44.dp, onClick = onClose)
        Spacer(Modifier.width(10.dp))
        Text("$count", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        GlassIconButton(Icons.Outlined.MarkEmailRead, "Gelesen umschalten", size = 44.dp, onClick = onRead)
        Spacer(Modifier.width(6.dp))
        GlassIconButton(Icons.Outlined.StarOutline, "Markieren", size = 44.dp, onClick = onFlag)
        Spacer(Modifier.width(6.dp))
        GlassIconButton(Icons.Outlined.Archive, "Archivieren", size = 44.dp, onClick = onArchive)
        Spacer(Modifier.width(6.dp))
        GlassIconButton(Icons.Outlined.Folder, "Verschieben", size = 44.dp, onClick = onMove)
        Spacer(Modifier.width(6.dp))
        GlassIconButton(Icons.Outlined.DeleteOutline, "Löschen", size = 44.dp, tint = Brand.Coral, onClick = onDelete)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SwipeRow(
    message: Message,
    account: Account?,
    showAccount: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    onToggleRead: () -> Unit,
    onUnsnooze: (() -> Unit)?
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> { onDelete(); true }
                SwipeToDismissBoxValue.StartToEnd -> { onToggleRead(); false }
                else -> false
            }
        }
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = !selectionMode,
        enableDismissFromEndToStart = !selectionMode,
        backgroundContent = {
            val dir = state.dismissDirection
            val color by animateColorAsState(
                when (dir) {
                    SwipeToDismissBoxValue.EndToStart -> Brand.Coral
                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primary
                    else -> Color.Transparent
                }, label = "bg"
            )
            Box(
                Modifier.fillMaxSize().padding(vertical = 3.dp).clip(RoundedCornerShape(24.dp)).background(color)
                    .padding(horizontal = 24.dp),
                contentAlignment = if (dir == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                if (dir != SwipeToDismissBoxValue.Settled) {
                    Icon(
                        if (dir == SwipeToDismissBoxValue.EndToStart) Icons.Outlined.DeleteOutline
                        else if (message.seen) Icons.Outlined.MarkEmailUnread else Icons.Outlined.MarkEmailRead,
                        null, tint = Color.White
                    )
                }
            }
        }
    ) {
        MessageRow(message, account, showAccount, selected, onClick, onLongClick, onUnsnooze)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageRow(
    m: Message,
    account: Account?,
    showAccount: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onUnsnooze: (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .glass(RoundedCornerShape(24.dp), tint = if (selected) scheme.primary else Color.Unspecified, opacity = if (m.seen) 0.75f else 1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box {
            Avatar(m.senderDisplay, m.fromEmail.ifBlank { m.senderDisplay }, size = 44.dp)
            if (selected) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(16.dp)).background(scheme.primary),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Outlined.MarkEmailRead, null, tint = scheme.onPrimary) }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!m.seen) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(scheme.primary))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    m.senderDisplay,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (m.seen) FontWeight.Medium else FontWeight.ExtraBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (m.flagged) Icon(Icons.Rounded.Star, "Markiert", tint = Brand.Spark, modifier = Modifier.size(16.dp))
                if (m.hasAttachments) Icon(Icons.Outlined.Attachment, "Anhang", tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (m.snoozedUntil > System.currentTimeMillis()) "bis ${Fmt.short(m.snoozedUntil)}" else Fmt.short(m.date),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (m.seen) scheme.onSurfaceVariant else scheme.primary
                )
            }
            Text(
                m.subject.ifBlank { "(Kein Betreff)" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (m.seen) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                m.snippet,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            if (showAccount && account != null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AccountDot(account.color, size = 6.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(account.label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
            if (onUnsnooze != null) {
                Spacer(Modifier.height(6.dp))
                Pill("Jetzt zurückholen", false, onClick = onUnsnooze)
            }
        }
    }
}

@Composable
private fun FolderDrawer(
    accounts: List<Account>,
    folders: List<Folder>,
    unread: Map<String, Int>,
    current: Mailbox,
    onPick: (Mailbox) -> Unit,
    onEmptyTrash: (Account) -> Unit,
    onAddAccount: () -> Unit,
    onSettings: () -> Unit
) {
    var collapsed by remember { mutableStateOf(setOf<Long>()) }
    Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Amelia", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 16.dp))
        val inboxUnread = folders.filter { it.role == FolderRole.INBOX }.sumOf { unread[it.id] ?: 0 }
        DrawerItem(Icons.Outlined.AllInbox, "Alle Posteingänge", inboxUnread, current == Mailbox.Unified) { onPick(Mailbox.Unified) }
        DrawerItem(Icons.Outlined.StarOutline, "Markiert", 0, current == Mailbox.Flagged) { onPick(Mailbox.Flagged) }
        DrawerItem(Icons.Outlined.Schedule, "Geparkt", 0, current == Mailbox.Snoozed) { onPick(Mailbox.Snoozed) }
        for (a in accounts) {
            HorizontalDivider(Modifier.padding(vertical = 10.dp, horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .clickable { collapsed = if (a.id in collapsed) collapsed - a.id else collapsed + a.id }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AccountDot(a.color, size = 10.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.label, style = MaterialTheme.typography.titleMedium)
                    Text(a.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    a.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2) }
                }
                Icon(Icons.Outlined.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (a.id !in collapsed) {
                for (f in sortedFolders(folders.filter { it.accountId == a.id })) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            DrawerItem(
                                roleIcon(f.role), f.name,
                                if (f.role == FolderRole.TRASH || f.role == FolderRole.JUNK || f.role == FolderRole.SENT) 0 else unread[f.id] ?: 0,
                                (current as? Mailbox.InFolder)?.folderId == f.id
                            ) { onPick(Mailbox.InFolder(f.id)) }
                        }
                        if (f.role == FolderRole.TRASH) {
                            GlassIconButton(Icons.Outlined.DeleteSweep, "Papierkorb leeren", size = 40.dp, tint = Brand.Coral) { onEmptyTrash(a) }
                        }
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp, horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        DrawerItem(Icons.Outlined.PersonAdd, "Konto hinzufügen", 0, false, onAddAccount)
        DrawerItem(Icons.Outlined.Settings, "Einstellungen", 0, false, onSettings)
    }
}

@Composable
private fun DrawerItem(icon: ImageVector, label: String, badge: Int, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (badge > 0) Text("$badge", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
    }
}
