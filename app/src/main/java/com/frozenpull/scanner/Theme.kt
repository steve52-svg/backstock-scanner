package com.frozenpull.scanner

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The same colors as the Frozen Pull List page (light and dark). */
@Immutable
data class Tokens(
    val bg: Color, val surface: Color, val fg: Color, val muted: Color, val line: Color,
    val accent: Color, val accentSoft: Color, val skip: Color, val skipSoft: Color,
    val miss: Color, val missSoft: Color, val hi: Color,
)

val LightTokens = Tokens(
    bg = Color(0xFFEEF2F4), surface = Color(0xFFFFFFFF), fg = Color(0xFF16212A), muted = Color(0xFF5B6B77), line = Color(0xFFD3DCE2),
    accent = Color(0xFF0D6E7A), accentSoft = Color(0xFFD5ECEE), skip = Color(0xFF7A5D2E), skipSoft = Color(0xFFF1E8D8),
    miss = Color(0xFFB3261E), missSoft = Color(0xFFF8DEDB), hi = Color(0xFFFFD400),
)
val DarkTokens = Tokens(
    bg = Color(0xFF0F161B), surface = Color(0xFF172129), fg = Color(0xFFE4ECF0), muted = Color(0xFF93A4B0), line = Color(0xFF26333D),
    accent = Color(0xFF4FC0CC), accentSoft = Color(0xFF163A40), skip = Color(0xFFD9B77C), skipSoft = Color(0xFF3A2F1D),
    miss = Color(0xFFFF8A80), missSoft = Color(0xFF43201E), hi = Color(0xFFFFD400),
)

val LocalTokens = staticCompositionLocalOf { DarkTokens }

/** Shorthand for the current colors. */
val T: Tokens @Composable get() = LocalTokens.current

/** Headings: Barlow Condensed, like the page. */
val Display = FontFamily(Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold), Font(R.font.barlow_condensed_bold, FontWeight.Bold))
/** Body text: Atkinson Hyperlegible, easy to read at a glance. */
val Body = FontFamily(Font(R.font.atkinson_regular, FontWeight.Normal), Font(R.font.atkinson_bold, FontWeight.Bold))

@Composable
fun PullListTheme(content: @Composable () -> Unit) {
    val t = if (isSystemInDarkTheme()) DarkTokens else LightTokens
    val scheme = (if (t == DarkTokens) darkColorScheme() else lightColorScheme()).copy(
        primary = t.accent, onPrimary = t.bg, primaryContainer = t.accentSoft, onPrimaryContainer = t.fg,
        secondary = t.skip, onSecondary = t.bg, secondaryContainer = t.skipSoft, onSecondaryContainer = t.fg,
        error = t.miss, onError = t.bg, errorContainer = t.missSoft,
        background = t.bg, onBackground = t.fg, surface = t.surface, onSurface = t.fg,
        surfaceVariant = t.surface, onSurfaceVariant = t.muted, surfaceContainer = t.surface, surfaceContainerHigh = t.surface,
        surfaceContainerLow = t.surface, surfaceContainerHighest = t.surface, surfaceContainerLowest = t.surface,
        outline = t.line, outlineVariant = t.line,
    )
    val base = Typography()
    val type = Typography(
        displayLarge = base.displayLarge.copy(fontFamily = Display), displayMedium = base.displayMedium.copy(fontFamily = Display),
        displaySmall = base.displaySmall.copy(fontFamily = Display), headlineLarge = base.headlineLarge.copy(fontFamily = Display),
        headlineMedium = base.headlineMedium.copy(fontFamily = Display), headlineSmall = base.headlineSmall.copy(fontFamily = Display),
        titleLarge = base.titleLarge.copy(fontFamily = Display, fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontFamily = Body, fontWeight = FontWeight.Bold), titleSmall = base.titleSmall.copy(fontFamily = Body, fontWeight = FontWeight.Bold),
        bodyLarge = base.bodyLarge.copy(fontFamily = Body), bodyMedium = base.bodyMedium.copy(fontFamily = Body), bodySmall = base.bodySmall.copy(fontFamily = Body),
        labelLarge = base.labelLarge.copy(fontFamily = Body, fontWeight = FontWeight.Bold), labelMedium = base.labelMedium.copy(fontFamily = Body, fontWeight = FontWeight.Bold),
        labelSmall = base.labelSmall.copy(fontFamily = Body),
    )
    CompositionLocalProvider(LocalTokens provides t) {
        MaterialTheme(colorScheme = scheme, typography = type, shapes = MaterialTheme.shapes, content = content)
    }
}

/** Page-style text styles. */
object Type {
    /** "FROZEN PULL LIST" */
    val title = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 30.sp)
    /** Section headings: uppercase, spaced out, muted. */
    val section = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 1.2.sp)
    /** "12 to pull  3 pulled" */
    val tally = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    val name = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    val body = TextStyle(fontFamily = Body, fontSize = 14.sp)
    val small = TextStyle(fontFamily = Body, fontSize = 12.sp)
    val button = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    val tag = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.6.sp)
}
