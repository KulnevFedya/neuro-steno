package com.nuclearhack.steno

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Палитра: глубокий индиго (основной), коралловый (запись), тёплые серые (нейтральные).
private val Light = lightColorScheme(
    primary = Color(0xFF4A3AB8), onPrimary = Color.White,
    primaryContainer = Color(0xFFE4DFFF), onPrimaryContainer = Color(0xFF170F5C),
    secondary = Color(0xFF5E5873), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E0F3), onSecondaryContainer = Color(0xFF1C1A2B),
    tertiary = Color(0xFFC8462F), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDAD3), onTertiaryContainer = Color(0xFF410000),
    error = Color(0xFFBA1A1A), onError = Color.White,
    background = Color(0xFFFBF8F6), onBackground = Color(0xFF1D1B1A),
    surface = Color(0xFFFBF8F6), onSurface = Color(0xFF1D1B1A),
    surfaceVariant = Color(0xFFEBE5E1), onSurfaceVariant = Color(0xFF4D4744),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F2EF),
    surfaceContainer = Color(0xFFF0EBE8), surfaceContainerHigh = Color(0xFFEAE5E2),
    surfaceContainerHighest = Color(0xFFE4DFDC),
    outline = Color(0xFF7E7672), outlineVariant = Color(0xFFD3CBC6),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFC6BFFF), onPrimary = Color(0xFF261A80),
    primaryContainer = Color(0xFF3D2FA3), onPrimaryContainer = Color(0xFFE4DFFF),
    secondary = Color(0xFFC9C2DD), onSecondary = Color(0xFF312D41),
    secondaryContainer = Color(0xFF474358), onSecondaryContainer = Color(0xFFE6E0F3),
    tertiary = Color(0xFFFFB4A6), onTertiary = Color(0xFF5F150A),
    tertiaryContainer = Color(0xFF8A2A1C), onTertiaryContainer = Color(0xFFFFDAD3),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    background = Color(0xFF151312), onBackground = Color(0xFFE9E2DE),
    surface = Color(0xFF151312), onSurface = Color(0xFFE9E2DE),
    surfaceVariant = Color(0xFF4D4744), onSurfaceVariant = Color(0xFFCFC7C2),
    surfaceContainerLowest = Color(0xFF100E0D), surfaceContainerLow = Color(0xFF1D1B1A),
    surfaceContainer = Color(0xFF221F1E), surfaceContainerHigh = Color(0xFF2C2928),
    surfaceContainerHighest = Color(0xFF373433),
    outline = Color(0xFF988F8B), outlineVariant = Color(0xFF4D4744),
)

/**
 * Цвета спикеров: в светлой теме — тёмные (текст ≥ 4.5:1 на фоне, белый инициал на аватаре),
 * в тёмной — светлые (текст ≥ 4.5:1 на фоне, тёмный инициал на аватаре).
 */
private val SpeakersLight = listOf(
    Color(0xFF4338CA), Color(0xFFB4372A), Color(0xFF00796B),
    Color(0xFF8E24AA), Color(0xFF8F5400), Color(0xFF2E7D32),
)
private val SpeakersDark = listOf(
    Color(0xFFA5B4FC), Color(0xFFFFB4A6), Color(0xFF5EDBC8),
    Color(0xFFE1A6F0), Color(0xFFF5C062), Color(0xFF8FD694),
)

@Immutable
data class StenoExtra(val speakers: List<Color>, val onSpeaker: Color, val brand: Brush)

val LocalStenoExtra = staticCompositionLocalOf {
    StenoExtra(SpeakersLight, Color.White, Brush.linearGradient(listOf(Color(0xFF6D5DF0), Color(0xFF3A2A9E))))
}

private val Unbounded = FontFamily(
    Font(R.font.unbounded_semibold, FontWeight.SemiBold),
    Font(R.font.unbounded_bold, FontWeight.Bold),
)
private val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
)

private val Base = Typography()
private fun TextStyle.head(w: FontWeight = FontWeight.SemiBold) = copy(fontFamily = Unbounded, fontWeight = w, letterSpacing = 0.sp)
private fun TextStyle.body(w: FontWeight = FontWeight.Normal) = copy(fontFamily = Manrope, fontWeight = w)

private val StenoTypography = Typography(
    displaySmall = Base.displaySmall.head(FontWeight.Bold).copy(fontSize = 30.sp, lineHeight = 38.sp),
    headlineLarge = Base.headlineLarge.head(FontWeight.Bold).copy(fontSize = 28.sp, lineHeight = 36.sp),
    headlineMedium = Base.headlineMedium.head(FontWeight.Bold).copy(fontSize = 24.sp, lineHeight = 32.sp),
    headlineSmall = Base.headlineSmall.head().copy(fontSize = 20.sp, lineHeight = 28.sp),
    titleLarge = Base.titleLarge.head().copy(fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = Base.titleMedium.body(FontWeight.SemiBold),
    titleSmall = Base.titleSmall.body(FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.body().copy(lineHeight = 24.sp),
    bodyMedium = Base.bodyMedium.body(),
    bodySmall = Base.bodySmall.body(),
    labelLarge = Base.labelLarge.body(FontWeight.SemiBold),
    labelMedium = Base.labelMedium.body(FontWeight.SemiBold),
    labelSmall = Base.labelSmall.body(FontWeight.Medium),
)

private val StenoShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun StenoTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val extra = StenoExtra(
        speakers = if (dark) SpeakersDark else SpeakersLight,
        onSpeaker = if (dark) Color(0xFF1A1414) else Color.White,
        brand = Brush.linearGradient(listOf(Color(0xFF6D5DF0), Color(0xFF3A2A9E))),
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalStenoExtra provides extra) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            typography = StenoTypography,
            shapes = StenoShapes,
            content = content,
        )
    }
}

/** Цвет спикера по номеру speaker_N (с 1), циклически по 6 цветам. */
@Composable
fun speakerColor(id: String): Color {
    val c = LocalStenoExtra.current.speakers
    return c[((id.substringAfter('_').toIntOrNull() ?: 1) - 1).mod(c.size)]
}
