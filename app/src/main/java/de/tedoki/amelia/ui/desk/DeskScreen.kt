package de.tedoki.amelia.ui.desk

import androidx.compose.animation.core.Animatable
import de.tedoki.amelia.data.db.senderDisplay
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Attachment
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import de.tedoki.amelia.data.DeskRange
import de.tedoki.amelia.data.db.Account
import de.tedoki.amelia.data.db.Message
import de.tedoki.amelia.ui.AmeliaViewModel
import de.tedoki.amelia.ui.Screen
import de.tedoki.amelia.ui.ComposeArgs
import de.tedoki.amelia.ui.components.AccountDot
import de.tedoki.amelia.ui.components.Avatar
import de.tedoki.amelia.ui.components.EmptyState
import de.tedoki.amelia.ui.components.Fmt
import de.tedoki.amelia.ui.components.GlassIconButton
import de.tedoki.amelia.ui.components.MorphFab
import de.tedoki.amelia.ui.components.MorphLoader
import de.tedoki.amelia.ui.components.Pill
import de.tedoki.amelia.ui.components.SnoozeSheet
import de.tedoki.amelia.ui.theme.Brand
import de.tedoki.amelia.ui.theme.glass
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun DeskScreen(vm: AmeliaViewModel, dockDrag: Modifier, onOpenInbox: () -> Unit) {
    val desk by vm.deskMessages.collectAsState()
    val query by vm.searchQuery.collectAsState()
    val results by vm.searchResults.collectAsState()
    val range by vm.settings.deskRange.collectAsState()
    val unreadOnly by vm.settings.deskUnreadOnly.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val sync by vm.syncState.collectAsState()
    var snoozeTarget by remember { mutableStateOf<Message?>(null) }

    val searching = query.trim().length >= 2
    val cards = if (searching) results else desk
    val accountMap = (accounts ?: emptyList()).associateBy { it.id }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Kopfzeile: Suche und Einstellungen
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SearchField(query, onChange = vm::setSearch, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                GlassIconButton(Icons.Outlined.Settings, "Einstellungen") { vm.push(Screen.Settings) }
            }
            // Zeitraum
            if (!searching) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DeskRange.values().forEach { r -> Pill(r.label, r == range) { vm.setRange(r) } }
                    Pill(if (unreadOnly) "Nur ungelesen" else "Auch gelesene", unreadOnly) { vm.setUnreadOnly(!unreadOnly) }
                }
            } else {
                Text(
                    "${results.size} Treffer",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp)
                )
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (cards.isEmpty()) {
                    if (accounts.isNullOrEmpty() || (sync.isRunning && desk.isEmpty() && !searching)) {
                        MorphLoader(size = 56.dp)
                    } else {
                        EmptyState(
                            icon = if (searching) Icons.Outlined.Search else Icons.Outlined.MarkEmailRead,
                            title = if (searching) "Nichts gefunden" else "Alles erledigt",
                            text = if (searching) "Andere Suchbegriffe probieren." else
                                "Im Zeitraum „${range.label}“ liegt nichts mehr auf dem Schreibtisch."
                        ) {
                            if (!searching) Pill("Posteingang öffnen", false, onClick = onOpenInbox)
                        }
                    }
                } else {
                    CardCarousel(
                        messages = cards,
                        accounts = accountMap,
                        startIndex = if (searching) 0 else vm.deskIndex.value,
                        onIndex = { if (!searching) vm.setDeskIndex(it) },
                        onOpen = vm::open,
                        onDelete = { vm.delete(listOf(it)) },
                        onSnooze = { snoozeTarget = it }
                    )
                }
            }

            Dock(
                count = cards.size,
                syncing = sync.isRunning,
                dragModifier = dockDrag,
                onSync = vm::refresh,
                onInbox = onOpenInbox,
                onCompose = { vm.push(Screen.Compose(ComposeArgs())) }
            )
        }

        snoozeTarget?.let { m ->
            SnoozeSheet(onDismiss = { snoozeTarget = null }) { until ->
                vm.snooze(m, until)
                snoozeTarget = null
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.height(52.dp).glass(RoundedCornerShape(26.dp)).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Search, null, tint = scheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("Einfach lostippen …", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isNotEmpty()) {
            Icon(Icons.Outlined.Close, "Suche leeren", tint = scheme.onSurfaceVariant,
                modifier = Modifier.clip(CircleShape).clickable { onChange("") }.padding(4.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardCarousel(
    messages: List<Message>,
    accounts: Map<Long, Account>,
    startIndex: Int,
    onIndex: (Int) -> Unit,
    onOpen: (Message) -> Unit,
    onDelete: (Message) -> Unit,
    onSnooze: (Message) -> Unit
) {
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, messages.lastIndex)) { messages.size }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val threshold = with(density) { 120.dp.toPx() }

    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.distinctUntilChanged().collect { onIndex(it) }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.weight(1f, fill = false)) {
            val cardHeight = (maxHeight - 24.dp).coerceAtMost(560.dp)
            HorizontalPager(
                state = pager,
                key = { messages.getOrNull(it)?.id ?: it },
                contentPadding = PaddingValues(horizontal = 44.dp),
                pageSpacing = 0.dp,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxWidth()
            ) { page ->
                val message = messages.getOrNull(page) ?: return@HorizontalPager
                val offset = (page - pager.currentPage) - pager.currentPageOffsetFraction
                val absOffset = offset.absoluteValue.coerceIn(0f, 1f)
                val drag = remember(message.id) { Animatable(0f) }
                val isCurrent = page == pager.currentPage

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(cardHeight)
                        .zIndex(1f - absOffset)
                        .graphicsLayer {
                            val s = 1f - 0.10f * absOffset
                            scaleX = s
                            scaleY = s
                            translationX = -offset * 18.dp.toPx()
                            translationY = drag.value
                            alpha = 1f - 0.35f * absOffset - (abs(drag.value) / (threshold * 4)).coerceIn(0f, 0.5f)
                        }
                        .pointerInput(message.id, isCurrent) {
                            if (!isCurrent) return@pointerInput
                            var armed = false
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    scope.launch {
                                        when {
                                            drag.value < -threshold -> {
                                                drag.animateTo(-size.height * 1.4f, tween(220))
                                                onDelete(message)
                                            }
                                            drag.value > threshold -> {
                                                drag.animateTo(0f, spring(Spring.DampingRatioMediumBouncy))
                                                onSnooze(message)
                                            }
                                            else -> drag.animateTo(0f, spring(Spring.DampingRatioMediumBouncy))
                                        }
                                    }
                                },
                                onDragCancel = { scope.launch { drag.animateTo(0f, spring()) } },
                                onVerticalDrag = { change, amount ->
                                    change.consume()
                                    scope.launch { drag.snapTo(drag.value + amount * if (drag.value > 0) 0.6f else 1f) }
                                    val past = abs(drag.value) > threshold
                                    if (past != armed) {
                                        armed = past
                                        if (past) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                }
                            )
                        }
                ) {
                    MailCard(
                        message = message,
                        account = accounts[message.accountId],
                        dragProgress = (drag.value / threshold).coerceIn(-1.5f, 1.5f),
                        onClick = {
                            if (isCurrent) onOpen(message) else scope.launch { pager.animateScrollToPage(page) }
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "${(pager.currentPage + 1).coerceAtMost(messages.size)} von ${messages.size}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MailCard(message: Message, account: Account?, dragProgress: Float, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val deleteTint = (-dragProgress).coerceIn(0f, 1f)
    val snoozeTint = dragProgress.coerceIn(0f, 1f)
    val tint = when {
        deleteTint > 0f -> Brand.Coral.copy(alpha = deleteTint)
        snoozeTint > 0f -> Brand.Spark.copy(alpha = snoozeTint)
        else -> Color.Unspecified
    }
    Box(
        Modifier
            .fillMaxSize()
            .glass(RoundedCornerShape(34.dp), tint = tint, elevation = 18.dp)
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.fillMaxSize().padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                account?.let {
                    AccountDot(it.color)
                    Spacer(Modifier.width(8.dp))
                    Text(it.label, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                if (message.flagged) Icon(Icons.Rounded.Star, "Markiert", tint = Brand.Spark, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(Fmt.short(message.date), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(message.senderDisplay, message.fromEmail.ifBlank { message.senderDisplay }, size = 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        message.senderDisplay,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (message.seen) FontWeight.Medium else FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (message.fromName.isNotBlank()) {
                        Text(
                            message.fromEmail, style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (!message.seen) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(scheme.primary))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                message.subject.ifBlank { "(Kein Betreff)" },
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 3, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(12.dp))
            Box(Modifier.weight(1f)) {
                Text(
                    message.snippet,
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (message.hasAttachments) {
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(scheme.secondaryContainer.copy(alpha = 0.7f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Attachment, null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Anhang", style = MaterialTheme.typography.labelMedium, color = scheme.onSecondaryContainer)
                }
            }
        }
        // Hinweis während des Wischens
        if (deleteTint > 0.15f || snoozeTint > 0.15f) {
            val deleting = deleteTint > snoozeTint
            Row(
                Modifier
                    .align(if (deleting) Alignment.BottomCenter else Alignment.TopCenter)
                    .padding(18.dp)
                    .alpha(maxOf(deleteTint, snoozeTint).coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(50))
                    .background(if (deleting) Brand.Coral else Brand.Spark)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(if (deleting) Icons.Outlined.DeleteOutline else Icons.Outlined.Schedule, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (deleting) "Papierkorb" else "Später", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Dock(
    count: Int,
    syncing: Boolean,
    dragModifier: Modifier,
    onSync: () -> Unit,
    onInbox: () -> Unit,
    onCompose: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            if (syncing) MorphLoader(size = 30.dp) else GlassIconButton(Icons.Outlined.Refresh, "Aktualisieren", size = 56.dp, onClick = onSync)
        }
        Spacer(Modifier.width(12.dp))
        // Griff zum Posteingang: tippen oder nach oben ziehen
        Column(
            Modifier
                .weight(1f)
                .height(64.dp)
                .glass(RoundedCornerShape(32.dp))
                .then(dragModifier)
                .clickable(onClick = onInbox),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Outlined.KeyboardArrowUp, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Inbox, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (count == 1) "1 Karte" else "$count Karten",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        MorphFab(Icons.Outlined.Edit, null, onClick = onCompose)
    }
}
