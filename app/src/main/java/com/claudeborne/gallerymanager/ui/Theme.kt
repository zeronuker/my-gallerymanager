package com.claudeborne.gallerymanager.ui

import androidx.compose.foundation.background
import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.claudeborne.gallerymanager.data.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.gallerymanager.R

// Ported from my-brandkit/static/css/brand.css (ClaudeBorne brand stylesheet v0.1)

@Immutable
class CbColors(
    val surface0: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val mint: Color,
    val blue: Color,
    val violet: Color,
    val ink: Color,
    val ink2: Color,
    val inkDim: Color,
    val line: Color,
    val lineStrong: Color,
    val gridLine: Color,
    val isDark: Boolean,
) {
    val grad = Brush.linearGradient(listOf(mint, blue, violet))
}

val DarkCb = CbColors(
    surface0 = Color(0xFF0A1020), surface1 = Color(0xFF141A2E),
    surface2 = Color(0xFF1B2340), surface3 = Color(0xFF232C4D),
    mint = Color(0xFF3FE0C5), blue = Color(0xFF3B8DFF), violet = Color(0xFF5B6BFF),
    ink = Color(0xFFE8ECF5), ink2 = Color(0xFFB8C0D4), inkDim = Color(0xFF7C87A3),
    line = Color(0x12FFFFFF), lineStrong = Color(0x1FFFFFFF), gridLine = Color(0x0AFFFFFF),
    isDark = true,
)

val LightCb = CbColors(
    surface0 = Color(0xFFF4F6FB), surface1 = Color(0xFFFFFFFF),
    surface2 = Color(0xFFEBEEF7), surface3 = Color(0xFFDFE3F0),
    mint = Color(0xFF1CC2A3), blue = Color(0xFF1F76E8), violet = Color(0xFF4554DA),
    ink = Color(0xFF0A1020), ink2 = Color(0xFF3A4258), inkDim = Color(0xFF6B7488),
    line = Color(0x140A1020), lineStrong = Color(0x290A1020), gridLine = Color(0x0F0A1020),
    isDark = false,
)

val LocalCb = staticCompositionLocalOf { DarkCb }

val Tourney = FontFamily(
    Font(R.font.tourney_400, FontWeight.Normal),
    Font(R.font.tourney_700, FontWeight.Bold),
)
val Inter = FontFamily(
    Font(R.font.inter_400, FontWeight.Normal),
    Font(R.font.inter_600, FontWeight.SemiBold),
)
val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono_400, FontWeight.Normal))

private val CbTypography = Typography(
    bodyLarge = TextStyle(fontFamily = Inter, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontSize = 14.sp),
    titleLarge = TextStyle(fontFamily = Tourney, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 0.44.sp),
    labelSmall = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 2.6.sp),
)

/** Blueprint grid on surface-0, the brand's signature background. */
fun Modifier.cbGrid(cb: CbColors): Modifier = background(cb.surface0).drawBehind {
    val step = 32.dp.toPx()
    var x = 0f
    while (x < size.width) {
        drawLine(cb.gridLine, Offset(x, 0f), Offset(x, size.height), 1f)
        x += step
    }
    var y = 0f
    while (y < size.height) {
        drawLine(cb.gridLine, Offset(0f, y), Offset(size.width, y), 1f)
        y += step
    }
}

@Composable
fun ClaudeBorneTheme(content: @Composable () -> Unit) {
    val dark = when (Settings.theme) { 1 -> true; 2 -> false; else -> isSystemInDarkTheme() }
    val cb = if (dark) DarkCb else LightCb
    val view = LocalView.current
    SideEffect { // icons in the bars must contrast with the chosen theme, not the phone's
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
    }
    val scheme = if (cb.isDark) {
        darkColorScheme(
            primary = cb.mint, onPrimary = cb.surface0, secondary = cb.blue, tertiary = cb.violet,
            background = cb.surface0, onBackground = cb.ink, surface = cb.surface1, onSurface = cb.ink,
            surfaceVariant = cb.surface2, onSurfaceVariant = cb.ink2, outline = cb.lineStrong,
        )
    } else {
        lightColorScheme(
            primary = cb.mint, onPrimary = DarkCb.surface0, secondary = cb.blue, tertiary = cb.violet,
            background = cb.surface0, onBackground = cb.ink, surface = cb.surface1, onSurface = cb.ink,
            surfaceVariant = cb.surface2, onSurfaceVariant = cb.ink2, outline = cb.lineStrong,
        )
    }
    CompositionLocalProvider(LocalCb provides cb) {
        MaterialTheme(colorScheme = scheme, typography = CbTypography, content = content)
    }
}
