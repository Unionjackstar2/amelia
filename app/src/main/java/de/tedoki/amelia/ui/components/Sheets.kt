package de.tedoki.amelia.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.Weekend
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import de.tedoki.amelia.ui.theme.Brand
import de.tedoki.amelia.ui.theme.glass
import java.util.Calendar

/** Unteres Glas-Panel mit abgedunkeltem Hintergrund. */
@Composable
fun GlassSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onDismiss)
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visibleState = visible,
            enter = slideInVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)) { it } + fadeIn()
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(10.dp)
                    .navigationBarsPadding()
                    .glass(RoundedCornerShape(34.dp), opacity = 1.45f)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Box(
                    Modifier.align(Alignment.CenterHorizontally).size(40.dp, 5.dp).clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                Spacer(Modifier.height(14.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }
}

@Composable
fun SheetRow(icon: ImageVector, title: String, subtitle: String? = null, tint: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun at(daysAhead: Int, hour: Int): Long = Calendar.getInstance().apply {
    add(Calendar.DAY_OF_YEAR, daysAhead)
    set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Parken: Nachricht verschwindet vom Schreibtisch und kommt zum gewählten Zeitpunkt zurück. */
@Composable
fun SnoozeSheet(onDismiss: () -> Unit, onUnsnooze: (() -> Unit)? = null, onPick: (Long) -> Unit) {
    val now = System.currentTimeMillis()
    val cal = Calendar.getInstance()
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    val evening = if (hour < 17) at(0, 18) else now + 3 * 3_600_000L
    val tomorrow = at(1, 8)
    val dow = cal.get(Calendar.DAY_OF_WEEK)
    val toSaturday = ((Calendar.SATURDAY - dow) + 7) % 7
    val weekend = at(if (toSaturday == 0) 7 else toSaturday, 9)
    val toMonday = ((Calendar.MONDAY - dow) + 7) % 7
    val nextWeek = at(if (toMonday == 0) 7 else toMonday, 8)

    GlassSheet(if (onUnsnooze != null) "Neu parken bis …" else "Parken bis …", onDismiss) {
        if (onUnsnooze != null) {
            SheetRow(Icons.Outlined.Unarchive, "Jetzt zurückholen", "Wieder auf den Schreibtisch legen", tint = Brand.Spark, onClick = onUnsnooze)
        }
        SheetRow(Icons.Outlined.Schedule, "In 3 Stunden", Fmt.timeOnly(now + 3 * 3_600_000L)) { onPick(now + 3 * 3_600_000L) }
        SheetRow(Icons.Outlined.DarkMode, if (hour < 17) "Heute Abend" else "Später heute", Fmt.short(evening).let { if (it.contains(':')) it else "$it, ${Fmt.timeOnly(evening)}" }) { onPick(evening) }
        SheetRow(Icons.Outlined.WbSunny, "Morgen früh", "Morgen, 08:00") { onPick(tomorrow) }
        SheetRow(Icons.Outlined.Weekend, "Am Wochenende", "${Fmt.short(weekend)}, 09:00") { onPick(weekend) }
        SheetRow(Icons.Outlined.CalendarMonth, "Nächste Woche", "${Fmt.short(nextWeek)}, 08:00") { onPick(nextWeek) }
    }
}
