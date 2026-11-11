package de.tedoki.amelia.ui.detail

import android.annotation.SuppressLint
import de.tedoki.amelia.data.db.senderDisplay
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.ReplyAll
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Attachment
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Shortcut
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import de.tedoki.amelia.data.LinkCheck
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import de.tedoki.amelia.data.AttachmentInfo
import de.tedoki.amelia.data.MessageContent
import de.tedoki.amelia.data.db.Message
import de.tedoki.amelia.mail.imap.FolderRole
import de.tedoki.amelia.mail.mime.MimeUtil
import de.tedoki.amelia.ui.AmeliaViewModel
import de.tedoki.amelia.ui.ComposeArgs
import de.tedoki.amelia.ui.ComposeMode
import de.tedoki.amelia.ui.Screen
import de.tedoki.amelia.ui.components.AccountDot
import de.tedoki.amelia.ui.components.Avatar
import de.tedoki.amelia.ui.components.Fmt
import de.tedoki.amelia.ui.components.GlassIconButton
import de.tedoki.amelia.ui.components.GlassSheet
import de.tedoki.amelia.ui.components.MorphLoader
import de.tedoki.amelia.ui.components.SheetRow
import de.tedoki.amelia.ui.components.SnoozeSheet
import de.tedoki.amelia.ui.inbox.roleIcon
import de.tedoki.amelia.ui.inbox.sortedFolders
import de.tedoki.amelia.ui.theme.Brand
import de.tedoki.amelia.ui.theme.glass
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

@Composable
fun DetailScreen(vm: AmeliaViewModel, messageId: Long) {
    val message by remember(messageId) { vm.repo.db.messages().flowById(messageId) }.collectAsState(null)
    val accounts by vm.accounts.collectAsState()
    val folders by vm.folders.collectAsState()
    val alwaysImages by vm.settings.loadImages.collectAsState()
    val trusted by vm.settings.trustedSenders.collectAsState()
    val linkCheck by vm.settings.linkCheck.collectAsState()
    var pendingLink by remember { mutableStateOf<LinkRequest?>(null) }
    var headerCollapsed by remember(messageId) { mutableStateOf(false) }
    var showAttachments by remember(messageId) { mutableStateOf(false) }
    var barVisible by remember(messageId) { mutableStateOf(true) }
    // Scrollen nach unten: Leiste weg, Kopf einklappen; nach oben: Leiste zurück; ganz oben: alles zeigen
    val onContentScroll: (Int, Int) -> Unit = { y, dy ->
        when {
            y <= 0 -> { barVisible = true; headerCollapsed = false }
            dy > 6 -> { barVisible = false; if (y > 48) headerCollapsed = true }
            dy < -6 -> barVisible = true
        }
    }
    var content by remember(messageId) { mutableStateOf<MessageContent?>(null) }
    var error by remember(messageId) { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    var showImages by remember(messageId) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var snooze by remember { mutableStateOf(false) }
    var move by remember { mutableStateOf(false) }
    var attachmentFor by remember { mutableStateOf<AttachmentInfo?>(null) }
    var showDetails by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(messageId, reloadKey) {
        error = null
        vm.content(messageId).onSuccess { content = it }.onFailure { error = it.message ?: "Inhalt konnte nicht geladen werden." }
    }

    val m = message
    if (m == null) {
        // Nachricht wurde gelöscht oder verschoben
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(400); if (vm.repo.message(messageId) == null) vm.pop() }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MorphLoader() }
        return
    }
    val account = accounts?.firstOrNull { it.id == m.accountId }
    val senderWarnings = remember(m.id, m.fromName, m.fromEmail, m.authResult) { LinkSafety.senderWarnings(m) }
    val handleLink: (LinkRequest) -> Unit = { link ->
        val suspicious = LinkSafety.warnings(link).isNotEmpty() || senderWarnings.isNotEmpty()
        when {
            linkCheck == LinkCheck.ALWAYS -> pendingLink = link
            linkCheck == LinkCheck.SUSPICIOUS && suspicious -> pendingLink = link
            else -> openUrl(context, link.url)
        }
    }
    val inTrash = folders.firstOrNull { it.id == m.folderId }?.role == FolderRole.TRASH

    // Platz, den die schwebende Aktionsleiste unten belegt
    val barSpace = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 100.dp

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // Werkzeugleiste
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück", size = 44.dp) { vm.pop() }
            Spacer(Modifier.weight(1f))
            GlassIconButton(
                if (m.flagged) Icons.Rounded.Star else Icons.Outlined.StarOutline,
                if (m.flagged) "Markierung entfernen" else "Markieren",
                tint = if (m.flagged) Brand.Spark else MaterialTheme.colorScheme.onSurface
            ) { vm.setFlagged(listOf(m.id), !m.flagged) }
            Spacer(Modifier.width(8.dp))
            Box {
                GlassIconButton(Icons.Outlined.MoreVert, "Mehr") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem(Icons.Outlined.MarkEmailUnread, "Als ungelesen markieren") { menu = false; vm.setSeen(listOf(m.id), false); vm.pop() }
                    MenuItem(Icons.Outlined.Schedule, "Parken") { menu = false; snooze = true }
                    MenuItem(Icons.Outlined.Archive, "Archivieren") { menu = false; vm.archive(listOf(m)); vm.pop() }
                    MenuItem(Icons.Outlined.Folder, "Verschieben") { menu = false; move = true }
                    if (m.listUnsubscribe.contains("http")) {
                        MenuItem(Icons.Outlined.OpenInNew, "Newsletter abbestellen") {
                            menu = false
                            Regex("<(https?://[^>]+)>").find(m.listUnsubscribe)?.groupValues?.get(1)?.let { openUrl(context, it) }
                        }
                    }
                }
            }
        }

        // Kopf: kompakt; beim Scrollen eingeklappt (nur die erste Betreffzeile), per Tipp umschaltbar
        val atts = content?.attachments.orEmpty()
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp).glass(RoundedCornerShape(24.dp))
                .clickable { headerCollapsed = !headerCollapsed }
                .animateContentSize()
                .padding(horizontal = 14.dp, vertical = if (headerCollapsed) 10.dp else 12.dp)
        ) {
            if (headerCollapsed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (senderWarnings.isNotEmpty()) {
                        Icon(Icons.Outlined.WarningAmber, "Absender prüfen", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        m.subject.ifBlank { "(Kein Betreff)" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (atts.isNotEmpty()) {
                        AttachmentsButton(atts.size, compact = true) { showAttachments = true }
                        Spacer(Modifier.width(6.dp))
                    }
                    Icon(Icons.Outlined.ExpandMore, "Kopf ausklappen", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f)) { ExpandableSubject(m.subject.ifBlank { "(Kein Betreff)" }) }
                    Icon(Icons.Outlined.ExpandLess, "Kopf einklappen", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { showDetails = !showDetails }) {
                    Avatar(m.senderDisplay, m.fromEmail.ifBlank { m.senderDisplay }, size = 34.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.senderDisplay, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // Die echte Adresse ist immer sichtbar
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (m.authResult == "pass") {
                                Icon(Icons.Outlined.VerifiedUser, "Absender vom Mailanbieter bestätigt",
                                    tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                m.fromEmail,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (senderWarnings.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (senderWarnings.isNotEmpty()) FontWeight.Bold else null,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            Fmt.short(m.date).let { if (it.contains(':')) it else "$it, ${Fmt.timeOnly(m.date)}" },
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        account?.let {
                            Spacer(Modifier.height(4.dp))
                            AccountDot(it.color, size = 8.dp)
                        }
                    }
                }
                SenderWarningCard(senderWarnings)
                if (showDetails) {
                    Spacer(Modifier.height(8.dp))
                    DetailLine("Datum", Fmt.long(m.date))
                    if (m.toList.isNotBlank()) DetailLine("An", MimeUtil.parseAddressList(m.toList).joinToString(", ") { it.toString() })
                    if (m.ccList.isNotBlank()) DetailLine("Kopie", MimeUtil.parseAddressList(m.ccList).joinToString(", ") { it.toString() })
                    account?.let { DetailLine("Konto", "${it.label} (${it.email})") }
                }
                if (atts.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    AttachmentsButton(atts.size) { showAttachments = true }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Inhalt
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp)) {
            val c = content
            when {
                error != null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
                    de.tedoki.amelia.ui.components.Pill("Erneut versuchen", false) { reloadKey++ }
                }
                c == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MorphLoader() }
                c.html != null -> {
                    val allowImages = alwaysImages || showImages || m.fromEmail.lowercase() in trusted
                    val hasRemote = remember(c.html) { REMOTE_IMAGE.containsMatchIn(c.html) }
                    Column(Modifier.fillMaxSize()) {
                        if (hasRemote && !allowImages) {
                            Column(
                                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(18.dp))
                                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                                    .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 2.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text("Externe Bilder sind blockiert (Schutz vor Tracking).",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                                }
                                Row {
                                    TextButton(onClick = { showImages = true }) {
                                        Text("Einmal laden", color = MaterialTheme.colorScheme.onTertiaryContainer)
                                    }
                                    if (m.fromEmail.isNotBlank() && senderWarnings.isEmpty()) {
                                        TextButton(onClick = { vm.settings.setTrusted(m.fromEmail, true) }) {
                                            Text("Immer von diesem Absender", color = MaterialTheme.colorScheme.onTertiaryContainer, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                        // HTML immer auf hellem Briefbogen, damit Newsletter lesbar bleiben
                        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color.White)) {
                            MailWebView(
                                html = c.html,
                                allowImages = allowImages,
                                bottomPadding = (barSpace.value + 16).toInt(),
                                resolveCid = { cid -> vm.repo.inlineImage(m.id, cid) },
                                onMailto = { to -> vm.push(Screen.Compose(ComposeArgs(ComposeMode.NEW, to = to))) },
                                onLink = handleLink,
                                onScroll = onContentScroll
                            )
                        }
                    }
                }
                else -> Box(Modifier.fillMaxSize().glass(RoundedCornerShape(26.dp))) {
                    val textScroll = rememberScrollState()
                    LaunchedEffect(textScroll) {
                        var last = 0
                        snapshotFlow { textScroll.value }.collect { v ->
                            onContentScroll(v, v - last)
                            last = v
                        }
                    }
                    SelectionContainer {
                        Text(
                            plainTextAnnotated(c.text.orEmpty(), MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.primary) { url ->
                                handleLink(LinkRequest(url, null))
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxSize().verticalScroll(textScroll)
                                .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 18.dp + barSpace)
                        )
                    }
                }
            }
        }

    }

        // Schwebende Aktionsleiste – verschwindet beim Scrollen nach unten
        AnimatedVisibility(
            visible = barVisible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(14.dp)
                .glass(RoundedCornerShape(32.dp), elevation = 12.dp, opacity = 1.35f).padding(6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            BarAction(Icons.AutoMirrored.Outlined.Reply, "Antworten") { vm.push(Screen.Compose(ComposeArgs(ComposeMode.REPLY, m.id))) }
            BarAction(Icons.AutoMirrored.Outlined.ReplyAll, "Allen") { vm.push(Screen.Compose(ComposeArgs(ComposeMode.REPLY_ALL, m.id))) }
            BarAction(Icons.Outlined.Shortcut, "Weiterleiten") { vm.push(Screen.Compose(ComposeArgs(ComposeMode.FORWARD, m.id))) }
            BarAction(Icons.Outlined.DeleteOutline, if (inTrash) "Endgültig" else "Löschen", Brand.Coral) {
                vm.delete(listOf(m))
                vm.pop()
            }
        }
        }
    }

    if (showAttachments) {
        val atts = content?.attachments.orEmpty()
        GlassSheet(if (atts.size == 1) "1 Anhang" else "${atts.size} Anhänge", onDismiss = { showAttachments = false }) {
            atts.forEach { a ->
                SheetRow(
                    if (a.mimeType.startsWith("image/")) Icons.Outlined.Image else Icons.Outlined.Attachment,
                    a.name,
                    Fmt.size(a.size)
                ) {
                    showAttachments = false
                    attachmentFor = a
                }
            }
        }
    }

    pendingLink?.let { link ->
        LinkDialog(link, onOpen = { pendingLink = null; openUrl(context, link.url) }, onDismiss = { pendingLink = null })
    }

    if (snooze) {
        SnoozeSheet(onDismiss = { snooze = false }) { until ->
            snooze = false
            vm.snooze(m, until)
            vm.pop()
        }
    }
    if (move) {
        GlassSheet("Verschieben nach", onDismiss = { move = false }) {
            sortedFolders(folders.filter { it.accountId == m.accountId && it.id != m.folderId }).forEach { f ->
                SheetRow(roleIcon(f.role), f.name) {
                    move = false
                    vm.move(listOf(m), f)
                    vm.pop()
                }
            }
        }
    }
    attachmentFor?.let { a ->
        GlassSheet(a.name, onDismiss = { attachmentFor = null }) {
            fun withFile(action: (File) -> Unit) {
                attachmentFor = null
                scope.launch {
                    try {
                        Toast.makeText(context, "Wird geladen …", Toast.LENGTH_SHORT).show()
                        action(vm.repo.attachmentFile(m.id, a.section))
                    } catch (e: Exception) {
                        Toast.makeText(context, e.message ?: "Anhang konnte nicht geladen werden.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            SheetRow(Icons.Outlined.OpenInNew, "Öffnen", Fmt.size(a.size)) { withFile { openFile(context, it, a.mimeType) } }
            SheetRow(Icons.Outlined.Share, "Teilen") { withFile { shareFile(context, it, a.mimeType) } }
            SheetRow(Icons.Outlined.Download, "In „Downloads“ speichern") {
                withFile { f ->
                    scope.launch {
                        val ok = saveToDownloads(context, f, a.name, a.mimeType)
                        Toast.makeText(context, if (ok) "In „Downloads“ gespeichert" else "Speichern nicht möglich – über „Teilen“ ablegen.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = tint)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

private val URL = Regex("(https?://[^\\s<>\"')\\]]+)")
private val REMOTE_IMAGE = Regex("(?i)(<img[^>]+src\\s*=\\s*[\"']?\\s*https?:|background\\s*=\\s*[\"']?https?:|url\\(\\s*[\"']?https?:)")

@OptIn(ExperimentalTextApi::class)
private fun plainTextAnnotated(text: String, quoteColor: Color, linkColor: Color, onLink: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { i, line ->
        if (i > 0) append('\n')
        val quoted = line.trimStart().startsWith(">")
        val start = length
        var last = 0
        for (mt in URL.findAll(line)) {
            append(line.substring(last, mt.range.first))
            val url = mt.value
            withLink(
                LinkAnnotation.Clickable(
                    url,
                    TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                ) { onLink(url) }
            ) {
                append(mt.value)
            }
            last = mt.range.last + 1
        }
        append(line.substring(last))
        if (quoted) addStyle(SpanStyle(color = quoteColor), start, length)
    }
}

// ------------------------------------------------------------------ WebView

private const val INLINE_HOST = "amelia.inline"

private fun inlineEncode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

private fun prepareHtml(html: String, bottomPadding: Int): String {
    var h = html
    h = h.replace(Regex("(?is)<script[^>]*>.*?</script>"), "")
    h = h.replace(Regex("(?i)<meta[^>]+(charset|http-equiv)[^>]*>"), "")
    // Eingebettete Bilder (cid:…) über einen internen Host ausliefern
    h = h.replace(Regex("(?i)cid:([^\"'\\s)>]+)")) {
        val raw = it.groupValues[1]
        val cid = try { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { raw }
        "https://$INLINE_HOST/" + inlineEncode(cid)
    }
    // Bilder, die nur über ihren Dateinamen verknüpft sind (z. B. „image001.png“)
    h = h.replace(Regex("(?i)(<img\\b[^>]*?\\bsrc\\s*=\\s*)([\"'])(?!https?:|cid:|data:|//|about:)([^\"']+)\\2")) {
        it.groupValues[1] + it.groupValues[2] + "https://$INLINE_HOST/name/" + inlineEncode(it.groupValues[3].trim()) + it.groupValues[2]
    }
    val head = """<meta name="viewport" content="width=device-width, initial-scale=1"><style>
        html,body{margin:0;padding:0;background:#fff}
        body{padding:16px 16px ${bottomPadding}px 16px;font-family:sans-serif;font-size:15px;line-height:1.5;color:#1b1f27;word-wrap:break-word;overflow-wrap:anywhere}
        img{max-width:100%!important;height:auto!important}
        table{max-width:100%!important}
        pre{white-space:pre-wrap}
        blockquote{margin:0 0 0 8px;padding-left:10px;border-left:3px solid #c9d2e3;color:#4a5264}
        a{color:#2a4c8c}
    </style>"""
    val idx = Regex("(?i)<head[^>]*>").find(h)
    return if (idx != null) h.substring(0, idx.range.last + 1) + head + h.substring(idx.range.last + 1) else head + h
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MailWebView(
    html: String,
    allowImages: Boolean,
    bottomPadding: Int,
    resolveCid: suspend (String) -> Pair<String, ByteArray>?,
    onMailto: (String) -> Unit,
    onLink: (LinkRequest) -> Unit,
    onScroll: (Int, Int) -> Unit
) {
    val scrollCallback by rememberUpdatedState(onScroll)
    val prepared = remember(html, bottomPadding) { LinkSafety.rewriteAnchors(prepareHtml(html, bottomPadding)) }
    val key = "${prepared.hashCode()}_$allowImages"
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(AColor.WHITE)
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.textZoom = 100
                // Bilder über http in einer https-Seite erlauben (nur passiv, keine Skripte)
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                setOnScrollChangeListener { _, _, y, _, oldY -> scrollCallback(y, y - oldY) }
            }
        },
        update = { wv ->
            if (wv.tag != key) {
                wv.tag = key
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val url = request.url
                        if (url.host == INLINE_HOST) {
                            val path = url.encodedPath.orEmpty().removePrefix("/")
                            val key = if (path.startsWith("name/")) {
                                "name/" + URLDecoder.decode(path.removePrefix("name/"), "UTF-8")
                            } else {
                                URLDecoder.decode(path, "UTF-8")
                            }
                            val data = try { runBlocking { resolveCid(key) } } catch (_: Exception) { null } ?: return empty()
                            return WebResourceResponse(data.first, null, ByteArrayInputStream(data.second))
                        }
                        if (!allowImages && (url.scheme == "http" || url.scheme == "https")) return empty()
                        return null
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url
                        val rewritten = LinkSafety.parse(url)
                        when {
                            rewritten != null -> onLink(rewritten)
                            url.scheme.equals("mailto", true) -> onMailto(url.schemeSpecificPart.substringBefore('?'))
                            url.scheme.equals("http", true) || url.scheme.equals("https", true) -> onLink(LinkRequest(url.toString(), null))
                            else -> {}
                        }
                        return true
                    }
                }
                wv.loadDataWithBaseURL("https://amelia.invalid/", prepared, "text/html", "utf-8", null)
            }
        }
    )
}

private fun empty() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

// ------------------------------------------------------------------ Dateien & Links

fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Keine App zum Öffnen gefunden.", Toast.LENGTH_SHORT).show()
    }
}

private fun uriFor(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)

fun openFile(context: Context, file: File, mime: String) {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(context, file), mime.ifBlank { "*/*" })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        shareFile(context, file, mime)
    }
}

fun shareFile(context: Context, file: File, mime: String) {
    val intent = Intent(Intent.ACTION_SEND).setType(mime.ifBlank { "*/*" })
        .putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

suspend fun saveToDownloads(context: Context, file: File, name: String, mime: String): Boolean = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < 29) return@withContext false
    try {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
        context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        true
    } catch (_: Exception) {
        false
    }
}

/** Betreff: kurz und kompakt, bei Überlänge per Tipp ausklappbar. */
@Composable
private fun ExpandableSubject(subject: String) {
    var expanded by remember(subject) { mutableStateOf(false) }
    var overflowing by remember(subject) { mutableStateOf(false) }
    val long = subject.length > 70
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .then(if (overflowing || expanded) Modifier.clickable { expanded = !expanded } else Modifier),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            subject,
            style = if (long) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
            modifier = Modifier.weight(1f).animateContentSize()
        )
        if (overflowing || expanded) {
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                if (expanded) "Betreff einklappen" else "Ganzen Betreff zeigen",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, top = 2.dp)
            )
        }
    }
}

/** Kleiner Knopf statt großer Anhang-Kacheln im Kopfbereich. */
@Composable
private fun AttachmentsButton(count: Int, compact: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 3.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Attachment, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            if (compact) "$count" else if (count == 1) "1 Anhang anzeigen" else "$count Anhänge anzeigen",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}
