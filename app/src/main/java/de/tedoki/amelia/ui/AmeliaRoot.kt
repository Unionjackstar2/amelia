package de.tedoki.amelia.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import de.tedoki.amelia.ui.compose.ComposeScreen
import de.tedoki.amelia.ui.desk.DeskScreen
import de.tedoki.amelia.ui.detail.DetailScreen
import de.tedoki.amelia.ui.inbox.InboxScreen
import de.tedoki.amelia.ui.settings.AccountEditorScreen
import de.tedoki.amelia.ui.settings.SettingsScreen
import de.tedoki.amelia.ui.settings.WelcomeScreen
import de.tedoki.amelia.ui.theme.AuroraBackground
import de.tedoki.amelia.ui.theme.glass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AmeliaRoot(vm: AmeliaViewModel) {
    val accounts by vm.accounts.collectAsState()
    val stack by vm.stack.collectAsState()
    val mode by vm.mode.collectAsState()
    val query by vm.searchQuery.collectAsState()
    val startInbox by vm.settings.startInInbox.collectAsState()

    val defaultMode = if (startInbox) MainMode.INBOX else MainMode.DESK
    BackHandler(enabled = stack.isNotEmpty() || query.isNotEmpty() || mode != defaultMode) { vm.handleBack() }

    CrashReportDialog()

    AuroraBackground {
        when {
            accounts == null -> {}
            accounts!!.isEmpty() -> WelcomeScreen { vm.push(Screen.AccountEditor(null)) }
            else -> MainArea(vm, mode)
        }

        // Überlagerte Ansichten (Lesen, Schreiben, Einstellungen …)
        AnimatedContent(
            targetState = stack,
            contentKey = { it.lastOrNull() },
            transitionSpec = {
                if (targetState.size >= initialState.size) {
                    (slideInHorizontally(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)) { it } + fadeIn())
                        .togetherWith(fadeOut(tween(220)))
                } else {
                    fadeIn(tween(200)).togetherWith(slideOutHorizontally(tween(260)) { it } + fadeOut(tween(260)))
                }
            },
            label = "overlay",
            modifier = Modifier.zIndex(10f)
        ) { s ->
            val top = s.lastOrNull()
            if (top != null) {
                AuroraBackground {
                    when (top) {
                        is Screen.Detail -> DetailScreen(vm, top.id)
                        is Screen.Compose -> ComposeScreen(vm, top.args)
                        Screen.Settings -> SettingsScreen(vm)
                        is Screen.AccountEditor -> AccountEditorScreen(vm, top.id)
                    }
                }
            }
        }

        UndoAndToasts(vm, Modifier.align(Alignment.BottomCenter).zIndex(20f))
    }
}

/**
 * Schreibtisch und Posteingang liegen übereinander. Der Posteingang lässt sich mit
 * dem Finger von unten heraufziehen und wieder hinunterschieben.
 */
@Composable
private fun MainArea(vm: AmeliaViewModel, mode: MainMode) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val progress = remember { Animatable(if (mode == MainMode.INBOX) 1f else 0f) }
        val scope = rememberCoroutineScope()
        var dragging by remember { mutableStateOf(false) }

        LaunchedEffect(mode) {
            if (!dragging) progress.animateTo(if (mode == MainMode.INBOX) 1f else 0f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow))
        }

        fun settle(velocityUp: Boolean?) {
            dragging = false
            val target = when (velocityUp) {
                true -> 1f
                false -> 0f
                null -> if (progress.value > 0.5f) 1f else 0f
            }
            scope.launch {
                progress.animateTo(target, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow))
                vm.setMode(if (target == 1f) MainMode.INBOX else MainMode.DESK)
            }
        }

        val drag = Modifier.pointerInput(Unit) {
            var last = 0f
            detectVerticalDragGestures(
                onDragStart = { dragging = true; last = 0f },
                onDragEnd = {
                    val p = progress.value
                    settle(
                        when {
                            last < -6f && p > 0.08f -> true
                            last > 6f && p < 0.92f -> false
                            else -> null
                        }
                    )
                },
                onDragCancel = { settle(null) },
                onVerticalDrag = { change, amount ->
                    change.consume()
                    last = amount
                    scope.launch { progress.snapTo((progress.value - amount / height).coerceIn(0f, 1f)) }
                }
            )
        }

        val p = progress.value
        if (p < 1f) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                translationY = -p * height * 0.25f
                val s = 1f - 0.06f * p
                scaleX = s; scaleY = s
                alpha = 1f - 0.6f * p
            }) {
                DeskScreen(vm, dockDrag = drag, onOpenInbox = { vm.setMode(MainMode.INBOX) })
            }
        }
        if (p > 0f) {
            AuroraBackground(Modifier.graphicsLayer { translationY = (1f - p) * height }) {
                InboxScreen(vm, headerDrag = drag, onBackToDesk = { vm.setMode(MainMode.DESK) })
            }
        }
    }
}

@Composable
private fun UndoAndToasts(vm: AmeliaViewModel, modifier: Modifier) {
    val undo by vm.undo.collectAsState()
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        vm.toasts.collect { t ->
            toast = t
            delay(3_200)
            if (toast == t) toast = null
        }
    }
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
        AnimatedVisibility(
            visible = undo != null,
            enter = slideInVertically(spring(Spring.DampingRatioMediumBouncy)) { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            val u = undo
            Row(
                Modifier.fillMaxWidth().glass(RoundedCornerShape(26.dp), elevation = 12.dp, opacity = 1.4f)
                    .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(u?.text ?: "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::undoLast) { Text("Rückgängig", fontWeight = FontWeight.Bold) }
            }
        }
        AnimatedVisibility(
            visible = toast != null && undo == null,
            enter = slideInVertically(spring(Spring.DampingRatioMediumBouncy)) { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            Box(
                Modifier.fillMaxWidth().glass(RoundedCornerShape(26.dp), elevation = 12.dp, opacity = 1.4f).padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(toast ?: "", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Zeigt nach einem Absturz einmalig den Fehlerbericht an (zum Kopieren und Weitergeben). */
@Composable
private fun CrashReportDialog() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val file = remember { java.io.File(context.filesDir, de.tedoki.amelia.AmeliaApplication.CRASH_FILE) }
    var report by remember { mutableStateOf(if (file.exists()) file.readText() else null) }
    val text = report ?: return
    fun close() {
        file.delete()
        report = null
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { close() },
        title = { Text("Amelia wurde unerwartet beendet") },
        text = {
            androidx.compose.foundation.layout.Column(
                Modifier.verticalScrollable()
            ) {
                Text(
                    "Mit diesem Bericht lässt sich der Fehler finden. Tippe auf „Kopieren“ und schicke ihn weiter.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text.take(6000),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                close()
            }) { Text("Kopieren") }
        },
        dismissButton = { TextButton(onClick = { close() }) { Text("Schließen") } }
    )
}

@Composable
private fun Modifier.verticalScrollable(): Modifier =
    this.verticalScroll(androidx.compose.foundation.rememberScrollState())
