package de.tedoki.amelia.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.tedoki.amelia.ui.theme.glass
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------ Formatierung

object Fmt {
    private val time = SimpleDateFormat("HH:mm", Locale.GERMANY)
    private val weekday = SimpleDateFormat("EEE", Locale.GERMANY)
    private val dayMonth = SimpleDateFormat("d. MMM", Locale.GERMANY)
    private val full = SimpleDateFormat("d. MMM yyyy", Locale.GERMANY)
    private val long = SimpleDateFormat("EEEE, d. MMMM yyyy, HH:mm", Locale.GERMANY)

    fun short(ts: Long): String {
        val now = Calendar.getInstance()
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        val sameYear = now.get(Calendar.YEAR) == c.get(Calendar.YEAR)
        val days = daysBetween(c, now)
        return when {
            days == 0 -> time.format(Date(ts))
            days == 1 -> "Gestern"
            days in 2..6 -> weekday.format(Date(ts))
            sameYear -> dayMonth.format(Date(ts))
            else -> full.format(Date(ts))
        }
    }

    fun long(ts: Long): String = long.format(Date(ts))

    fun timeOnly(ts: Long): String = time.format(Date(ts))

    private fun daysBetween(a: Calendar, b: Calendar): Int {
        val x = a.clone() as Calendar
        val y = b.clone() as Calendar
        listOf(x, y).forEach { it.set(Calendar.HOUR_OF_DAY, 0); it.set(Calendar.MINUTE, 0); it.set(Calendar.SECOND, 0); it.set(Calendar.MILLISECOND, 0) }
        return ((y.timeInMillis - x.timeInMillis) / 86_400_000L).toInt()
    }

    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.GERMANY, "%.1f MB", bytes / 1_048_576.0)
    }

    fun initials(name: String): String {
        val clean = name.replace(Regex("[^\\p{L}\\p{N} ]"), " ").trim()
        val parts = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts[0].take(2).uppercase(Locale.GERMANY)
            else -> (parts[0].take(1) + parts[parts.size - 1].take(1)).uppercase(Locale.GERMANY)
        }
    }
}

// ------------------------------------------------------------------ Avatar

private val avatarPalette = listOf(
    Color(0xFF2A4C8C), Color(0xFF4A7A5C), Color(0xFFB2641A), Color(0xFF7A4E9C),
    Color(0xFF1F7A86), Color(0xFFA6435A), Color(0xFF5A6B2E), Color(0xFF3D5DB5)
)

@Composable
fun Avatar(name: String, key: String, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val base = avatarPalette[(key.lowercase().hashCode() and 0x7fffffff) % avatarPalette.size]
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.36f))
            .background(Brush.linearGradient(listOf(base.copy(alpha = 0.95f), base.copy(alpha = 0.70f)))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            Fmt.initials(name),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.36f).sp
        )
    }
}

// ------------------------------------------------------------------ Buttons

/** Runde Glas-Schaltfläche mit Feder-Rückmeldung beim Drücken. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "s")
    Box(
        modifier
            .scale(scale)
            .size(size)
            .glass(CircleShape)
            .clickable(source, null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.46f))
    }
}

/**
 * Expressive Hauptaktion: abgerundetes Quadrat, das beim Drücken zur Pille morpht.
 */
@Composable
fun MorphFab(
    icon: ImageVector,
    label: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val radius by animateDpAsState(if (pressed) 32.dp else 20.dp, spring(Spring.DampingRatioMediumBouncy), label = "r")
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "s")
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .scale(scale)
            .height(64.dp)
            .clip(RoundedCornerShape(radius))
            .background(scheme.primary)
            .clickable(source, null, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (label == null) 20.dp else 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, label, tint = scheme.onPrimary, modifier = Modifier.size(26.dp))
        if (label != null) {
            Spacer(Modifier.size(10.dp))
            Text(label, color = scheme.onPrimary, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Auswahl-Pille (z. B. Zeitraum). */
@Composable
fun Pill(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val shape = if (selected) RoundedCornerShape(14.dp) else RoundedCornerShape(50)
    val background = when {
        accent != null && selected -> Modifier.clip(shape).background(accent)
        accent != null -> Modifier.clip(shape).background(accent.copy(alpha = 0.32f))
        selected -> Modifier.clip(shape).background(scheme.primary)
        else -> Modifier.glass(shape)
    }
    Box(
        modifier
            .height(36.dp)
            .then(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                accent != null && selected -> Color(0xFF2B1A00)
                accent != null -> scheme.onSurface
                selected -> scheme.onPrimary
                else -> scheme.onSurface
            }
        )
    }
}

// ------------------------------------------------------------------ Ladeanzeige

/** Expressiver Ladeindikator: eine sich drehende Form, die zwischen Quadrat und Kreis morpht. */
@Composable
fun MorphLoader(modifier: Modifier = Modifier, size: Dp = 40.dp, color: Color = MaterialTheme.colorScheme.primary) {
    val t = rememberInfiniteTransition(label = "loader")
    val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing)), label = "rot")
    val morph by t.animateFloat(0.18f, 0.5f, infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "m")
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension * 0.72f
        rotate(rot) {
            drawRoundRect(
                color = color,
                topLeft = Offset((this.size.width - s) / 2, (this.size.height - s) / 2),
                size = Size(s, s),
                cornerRadius = CornerRadius(s * morph, s * morph)
            )
        }
    }
}

// ------------------------------------------------------------------ Leerer Zustand

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(84.dp).glass(RoundedCornerShape(30.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

@Composable
fun AccountDot(color: Int, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).clip(CircleShape).background(Color(color)))
}

// ------------------------------------------------------------------ Wischaktionen

/** Farbe, Symbol und Beschriftung einer Wischaktion. */
data class SwipeVisual(val label: String, val icon: ImageVector, val color: Color)

fun swipeVisual(action: de.tedoki.amelia.data.SwipeAction, seen: Boolean = true, flagged: Boolean = false): SwipeVisual? = when (action) {
    de.tedoki.amelia.data.SwipeAction.TRASH -> SwipeVisual("Papierkorb", Icons.Outlined.DeleteOutline, Color(0xFFE5534B))
    de.tedoki.amelia.data.SwipeAction.ARCHIVE -> SwipeVisual("Archivieren", Icons.Outlined.Archive, Color(0xFF4A7A5C))
    de.tedoki.amelia.data.SwipeAction.SNOOZE -> SwipeVisual("Später", Icons.Outlined.Schedule, Color(0xFFE0A030))
    de.tedoki.amelia.data.SwipeAction.TOGGLE_READ -> SwipeVisual(
        if (seen) "Ungelesen" else "Gelesen",
        if (seen) Icons.Outlined.MarkEmailUnread else Icons.Outlined.MarkEmailRead,
        Color(0xFF2A4C8C)
    )
    de.tedoki.amelia.data.SwipeAction.FLAG -> SwipeVisual(
        if (flagged) "Markierung weg" else "Markieren",
        Icons.Outlined.StarOutline, Color(0xFFC98A1E)
    )
    de.tedoki.amelia.data.SwipeAction.SPAM -> SwipeVisual("Spam", Icons.Outlined.Report, Color(0xFF8A5A3C))
    de.tedoki.amelia.data.SwipeAction.MOVE -> SwipeVisual("Verschieben", Icons.Outlined.Folder, Color(0xFF5B6B9E))
    de.tedoki.amelia.data.SwipeAction.NONE -> null
}
