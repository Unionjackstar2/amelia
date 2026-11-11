package de.tedoki.amelia.ui.desk

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import de.tedoki.amelia.data.CardStyle
import de.tedoki.amelia.ui.theme.LocalIsDark
import de.tedoki.amelia.ui.theme.glass
import kotlin.random.Random

/** Form der Karte je nach Design. Büttenpapier bekommt einen unregelmäßigen, gerissenen Rand. */
fun cardShape(style: CardStyle, seed: Long): Shape = when (style) {
    CardStyle.GLASS -> RoundedCornerShape(34.dp)
    CardStyle.LINED -> RoundedCornerShape(6.dp)
    CardStyle.SPIRAL -> RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp)
    CardStyle.DECKLE -> DeckleShape(seed)
}

/** Innenabstand, damit Text nicht über Randlinie oder Ringbindung läuft. */
fun cardPadding(style: CardStyle): PaddingValues = when (style) {
    CardStyle.GLASS -> PaddingValues(22.dp)
    CardStyle.LINED -> PaddingValues(start = 52.dp, end = 20.dp, top = 22.dp, bottom = 20.dp)
    CardStyle.SPIRAL -> PaddingValues(start = 46.dp, end = 20.dp, top = 22.dp, bottom = 20.dp)
    CardStyle.DECKLE -> PaddingValues(26.dp)
}

/** Papierkarten sind immer hell – auch im Dunkelmodus –, also mit dunkler „Tinte“ beschriften. */
@Composable
fun PaperTheme(style: CardStyle, content: @Composable () -> Unit) {
    if (style == CardStyle.GLASS) {
        content()
        return
    }
    val base: ColorScheme = MaterialTheme.colorScheme
    val ink = base.copy(
        primary = Color(0xFF2A4C8C),
        onPrimary = Color.White,
        onSurface = Color(0xFF1F242E),
        onSurfaceVariant = Color(0xFF5B606C),
        secondaryContainer = Color(0xFFD9E9DD),
        onSecondaryContainer = Color(0xFF173322),
        surface = Color(0xFFFBFAF6)
    )
    CompositionLocalProvider(LocalIsDark provides false) {
        MaterialTheme(colorScheme = ink, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides ink.onSurface, content = content)
        }
    }
}

/** Hintergrund, Schatten und Färbung beim Wischen. */
@Composable
fun Modifier.cardSurface(style: CardStyle, seed: Long, tint: Color, elevation: Dp): Modifier {
    if (style == CardStyle.GLASS) return this.glass(RoundedCornerShape(34.dp), tint = tint, elevation = elevation)
    val shape = cardShape(style, seed)
    val shadowed = if (elevation > 0.dp) {
        this.shadow(elevation, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.3f))
    } else this
    val clipped = shadowed.clip(shape)
    val bordered = if (style == CardStyle.DECKLE) clipped else clipped.border(1.dp, Color.Black.copy(alpha = 0.06f), shape)
    return bordered.drawWithCache {
        val k = (size.width / 300.dp.toPx()).coerceIn(0.25f, 1.2f)
        val random = Random(seed)
        when (style) {
            CardStyle.LINED -> {
                val paper = Color(0xFFFAFAF7)
                val lineColor = Color(0xFF9DBBDF).copy(alpha = 0.75f)
                val marginColor = Color(0xFFE0474C)
                val gap = 28.dp.toPx() * k
                val firstLine = 66.dp.toPx() * k
                val marginX = 38.dp.toPx() * k
                onDrawBehind {
                    drawRect(paper)
                    var y = firstLine
                    while (y < size.height - 4f) {
                        drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = (1.dp.toPx() * k).coerceAtLeast(1f))
                        y += gap
                    }
                    drawLine(marginColor, Offset(marginX, 0f), Offset(marginX, size.height), strokeWidth = (1.6.dp.toPx() * k).coerceAtLeast(1f))
                    if (tint != Color.Unspecified) drawRect(tint.copy(alpha = tint.alpha * 0.30f))
                }
            }
            CardStyle.SPIRAL -> {
                val paper = Color(0xFFF6F1E4)
                val area = size.width * size.height / (density * density)
                val dark = List((area / 260f).toInt()) {
                    Offset(random.nextFloat() * size.width, random.nextFloat() * size.height)
                }
                val light = List((area / 180f).toInt()) {
                    Offset(random.nextFloat() * size.width, random.nextFloat() * size.height)
                }
                val step = 22.dp.toPx() * k
                val holeX = 27.dp.toPx() * k
                onDrawBehind {
                    drawRect(paper)
                    drawPoints(light, PointMode.Points, Color(0xFFB9A98A).copy(alpha = 0.35f), strokeWidth = 1.6.dp.toPx() * k, cap = StrokeCap.Round)
                    drawPoints(dark, PointMode.Points, Color(0xFF6E6250).copy(alpha = 0.45f), strokeWidth = 1.dp.toPx() * k, cap = StrokeCap.Round)
                    // Leichter Schatten entlang der Bindung
                    drawRect(
                        Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.08f), Color.Transparent), startX = 0f, endX = 40.dp.toPx() * k),
                        size = Size(40.dp.toPx() * k, size.height)
                    )
                    var y = step * 0.8f
                    while (y < size.height - step * 0.4f) {
                        // Stanzloch
                        drawCircle(Color(0xFF3A342A).copy(alpha = 0.55f), radius = 3.2.dp.toPx() * k, center = Offset(holeX, y))
                        // Drahtring, der durch das Loch läuft
                        val ringTop = y - 5.dp.toPx() * k
                        drawRoundRect(
                            Color(0xFF1F1F22),
                            topLeft = Offset(-6.dp.toPx() * k, ringTop),
                            size = Size(holeX + 8.dp.toPx() * k, 10.dp.toPx() * k),
                            cornerRadius = CornerRadius(5.dp.toPx() * k),
                            style = Stroke(width = 2.6.dp.toPx() * k)
                        )
                        drawLine(
                            Color.White.copy(alpha = 0.35f),
                            Offset(0f, ringTop + 1.dp.toPx() * k), Offset(holeX - 2.dp.toPx() * k, ringTop + 1.dp.toPx() * k),
                            strokeWidth = 0.8.dp.toPx() * k
                        )
                        y += step
                    }
                    if (tint != Color.Unspecified) drawRect(tint.copy(alpha = tint.alpha * 0.30f))
                }
            }
            CardStyle.DECKLE -> {
                val paper = Color(0xFFF7F0E5)
                val fibers = Path()
                val area = size.width * size.height / (density * density)
                repeat((area / 600f).toInt()) {
                    val x = random.nextFloat() * size.width
                    val y = random.nextFloat() * size.height
                    val len = (6f + random.nextFloat() * 18f) * density * k
                    val angle = random.nextFloat() * Math.PI.toFloat() * 2f
                    val ex = x + kotlin.math.cos(angle) * len
                    val ey = y + kotlin.math.sin(angle) * len
                    val bend = (random.nextFloat() - 0.5f) * len
                    fibers.moveTo(x, y)
                    fibers.quadraticTo((x + ex) / 2 + bend, (y + ey) / 2 - bend, ex, ey)
                }
                val blotches = List(7) {
                    Triple(
                        Offset(random.nextFloat() * size.width, random.nextFloat() * size.height),
                        (60f + random.nextFloat() * 120f) * density * k,
                        random.nextFloat()
                    )
                }
                onDrawBehind {
                    drawRect(paper)
                    blotches.forEach { (c, r, v) ->
                        drawCircle(
                            Brush.radialGradient(listOf(Color(0xFFE8DCC8).copy(alpha = 0.35f + v * 0.2f), Color.Transparent), center = c, radius = r),
                            radius = r, center = c
                        )
                    }
                    drawPath(fibers, Color(0xFF9A8268).copy(alpha = 0.26f), style = Stroke(width = 0.7.dp.toPx() * k, cap = StrokeCap.Round))
                    // Hellerer, faseriger Rand wie bei geschöpftem Papier
                    drawRect(
                        Color.White.copy(alpha = 0.45f), style = Stroke(width = 5.dp.toPx() * k)
                    )
                    if (tint != Color.Unspecified) drawRect(tint.copy(alpha = tint.alpha * 0.30f))
                }
            }
            CardStyle.GLASS -> onDrawBehind {}
        }
    }
}

/** Unregelmäßiger, gerissener Rand für Büttenpapier (immer gleich für dieselbe Karte). */
class DeckleShape(private val seed: Long) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val random = Random(seed)
        val step = 5f * density.density
        val depth = 2.6f * density.density
        val path = Path()
        fun jitter() = random.nextFloat() * depth
        path.moveTo(jitter(), jitter())
        var x = step
        while (x < size.width) { path.lineTo(x, jitter()); x += step }
        var y = step
        while (y < size.height) { path.lineTo(size.width - jitter(), y); y += step }
        x = size.width - step
        while (x > 0) { path.lineTo(x, size.height - jitter()); x -= step }
        y = size.height - step
        while (y > 0) { path.lineTo(jitter(), y); y -= step }
        path.close()
        return Outline.Generic(path)
    }
}

/** Kleine Vorschau zur Auswahl in den Einstellungen. */
@Composable
fun CardStylePreview(style: CardStyle, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(70.dp, 96.dp)
                .then(
                    if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)).padding(5.dp)
                    else Modifier.padding(5.dp)
                )
                .cardSurface(style, seed = 7L, tint = Color.Unspecified, elevation = 3.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            style.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}
