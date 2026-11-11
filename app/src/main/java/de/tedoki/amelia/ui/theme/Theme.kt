package de.tedoki.amelia.ui.theme

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.tedoki.amelia.data.ThemeMode
import kotlin.math.cos
import kotlin.math.sin

// Markenfarben aus dem Amelia-Logo: Tinte, Salbei, Funke
object Brand {
    val Ink = Color(0xFF223F78)
    val InkDeep = Color(0xFF0E1A33)
    val Sage = Color(0xFF6E9E80)
    val Spark = Color(0xFFF2A93B)
    val Coral = Color(0xFFE5534B)
    val Mist = Color(0xFFECF0F6)
    val Night = Color(0xFF0A1120)
}

private val Light = lightColorScheme(
    primary = Color(0xFF2A4C8C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E2FB),
    onPrimaryContainer = Color(0xFF0E1E43),
    secondary = Color(0xFF4A7A5C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2EAD9),
    onSecondaryContainer = Color(0xFF0E2A18),
    tertiary = Color(0xFFA35F00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB0),
    onTertiaryContainer = Color(0xFF341C00),
    background = Brand.Mist,
    onBackground = Color(0xFF151B27),
    surface = Color(0xFFF6F8FC),
    onSurface = Color(0xFF151B27),
    surfaceVariant = Color(0xFFDFE5EF),
    onSurfaceVariant = Color(0xFF4A5264),
    surfaceContainer = Color(0xFFEBEFF6),
    surfaceContainerHigh = Color(0xFFE4E9F1),
    surfaceContainerHighest = Color(0xFFDDE3EC),
    outline = Color(0xFF747C8E),
    outlineVariant = Color(0xFFC4CAD6),
    error = Color(0xFFC0392F),
    errorContainer = Color(0xFFFFDAD5),
    onErrorContainer = Color(0xFF410002)
)

private val Dark = darkColorScheme(
    primary = Color(0xFFAFC6FF),
    onPrimary = Color(0xFF0E2550),
    primaryContainer = Color(0xFF26437A),
    onPrimaryContainer = Color(0xFFD8E2FB),
    secondary = Color(0xFFA6D3B3),
    onSecondary = Color(0xFF103822),
    secondaryContainer = Color(0xFF2D5440),
    onSecondaryContainer = Color(0xFFD2EAD9),
    tertiary = Color(0xFFFFC56E),
    onTertiary = Color(0xFF462A00),
    tertiaryContainer = Color(0xFF633F00),
    onTertiaryContainer = Color(0xFFFFDDB0),
    background = Brand.Night,
    onBackground = Color(0xFFE4E9F3),
    surface = Color(0xFF111A2B),
    onSurface = Color(0xFFE4E9F3),
    surfaceVariant = Color(0xFF283247),
    onSurfaceVariant = Color(0xFFB9C1D3),
    surfaceContainer = Color(0xFF162036),
    surfaceContainerHigh = Color(0xFF1C2740),
    surfaceContainerHighest = Color(0xFF232F4A),
    outline = Color(0xFF8A93A8),
    outlineVariant = Color(0xFF3A4459),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD5)
)

// Expressive Typografie: kräftige, eng gesetzte Überschriften, ruhiger Fließtext
private val AmeliaType = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.8).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.copy(lineHeight = 24.sp)
    )
}

private val AmeliaShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

val LocalIsDark = staticCompositionLocalOf { false }

@Composable
fun AmeliaTheme(mode: ThemeMode, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> {
            val d = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            d.copy(background = if (dark) Brand.Night else d.background)
        }
        dark -> Dark
        else -> Light
    }
    CompositionLocalProvider(LocalIsDark provides dark) {
        MaterialTheme(colorScheme = scheme, typography = AmeliaType, shapes = AmeliaShapes) {
            // Standard-Textfarbe passend zum Hell-/Dunkelmodus
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides scheme.onSurface, content = content)
        }
    }
}

// ------------------------------------------------------------------------- Glas

/**
 * Glasfläche: halbtransparente Füllung mit hellem Lichtrand oben links,
 * damit der Aurora-Hintergrund durchscheint.
 */
@Composable
fun Modifier.glass(
    shape: Shape = MaterialTheme.shapes.large,
    tint: Color = Color.Unspecified,
    elevation: Dp = 0.dp,
    opacity: Float = 1f
): Modifier {
    val dark = LocalIsDark.current
    val base = if (dark) Color(0xFF1B2640).copy(alpha = 0.62f * opacity) else Color.White.copy(alpha = 0.62f * opacity)
    val fill = if (tint == Color.Unspecified) base else tint.copy(alpha = if (dark) 0.22f else 0.16f).compositeOver(base)
    val top = if (dark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.35f)
    val rim = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = if (dark) 0.24f else 0.95f),
            Color.White.copy(alpha = if (dark) 0.05f else 0.35f),
            Color.White.copy(alpha = if (dark) 0.10f else 0.55f)
        )
    )
    var m = this
    if (elevation > 0.dp) {
        m = m.shadow(
            elevation, shape, clip = false,
            ambientColor = Color.Black.copy(alpha = if (dark) 0.5f else 0.18f),
            spotColor = Color(0xFF0E1A33).copy(alpha = if (dark) 0.6f else 0.22f)
        )
    }
    return m.clip(shape)
        .background(fill)
        .background(Brush.verticalGradient(listOf(top, Color.Transparent)))
        .border(1.dp, rim, shape)
}

/** Ruhiger, langsam wandernder Farbschein hinter allen Glasflächen. */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dark = LocalIsDark.current
    val transition = rememberInfiniteTransition(label = "aurora")
    val phase by transition.animateFloat(
        0f, (2 * Math.PI).toFloat(),
        infiniteRepeatable(tween(48_000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )
    val a1 = if (dark) 0.34f else 0.30f
    val blobs = listOf(
        Triple(scheme.primary, Offset(0.15f, 0.12f), a1),
        Triple(scheme.secondary, Offset(0.92f, 0.38f), a1 * 0.9f),
        Triple(scheme.tertiary, Offset(0.25f, 0.88f), a1 * 0.7f)
    )
    Box(
        modifier
            .fillMaxSize()
            .background(scheme.background)
            .drawBehind {
                blobs.forEachIndexed { i, (color, pos, alpha) ->
                    val p = phase + i * 2.1f
                    val center = Offset(
                        size.width * (pos.x + 0.06f * cos(p)),
                        size.height * (pos.y + 0.04f * sin(p * 1.3f))
                    )
                    val radius = size.maxDimension * 0.55f
                    drawCircle(
                        Brush.radialGradient(
                            listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.35f), Color.Transparent),
                            center = center, radius = radius
                        ),
                        radius = radius, center = center
                    )
                }
            },
        content = content
    )
}

val TextStyle.tabular: TextStyle get() = this.copy(fontFeatureSettings = "tnum")
