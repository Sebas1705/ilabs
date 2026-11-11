package es.sebas1705.ilabs.demo

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Status tones for outcome badges. Always paired with a text label and a glyph, never colour alone. */
@Immutable
class StatusColors(
    val okBg: Color, val okFg: Color,
    val warnBg: Color, val warnFg: Color,
    val errBg: Color, val errFg: Color,
)

// Neutral token set mirroring demos/web/src/styles.css. No brand yet.
private val LightStatus = StatusColors(
    Color(0xFFE2F3E8), Color(0xFF14602F), Color(0xFFFBEFD2), Color(0xFF6B4700), Color(0xFFFBE3E1), Color(0xFF9A1C14),
)
private val DarkStatus = StatusColors(
    Color(0xFF123221), Color(0xFF8FE0AA), Color(0xFF3A2C0A), Color(0xFFF2C970), Color(0xFF3D1816), Color(0xFFFFA8A0),
)

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF2650C8),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFF6F6F3),
    onBackground = Color(0xFF1A1C1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1F),
    onSurfaceVariant = Color(0xFF50565D),
    outline = Color(0xFF767C84),
    outlineVariant = Color(0xFFD4D6D9),
    surfaceContainer = Color(0xFFFFFFFF),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF9DB3FF),
    onPrimary = Color(0xFF0B1020),
    background = Color(0xFF101214),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF181B1F),
    onSurface = Color(0xFFE8EAED),
    onSurfaceVariant = Color(0xFFA3AAB2),
    outline = Color(0xFF7C848D),
    outlineVariant = Color(0xFF343A41),
    surfaceContainer = Color(0xFF181B1F),
)

private val LocalStatusColors = staticCompositionLocalOf { LightStatus }

object ILabsTheme {
    val status: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current
}

@Composable
fun ILabsTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
    }
}
