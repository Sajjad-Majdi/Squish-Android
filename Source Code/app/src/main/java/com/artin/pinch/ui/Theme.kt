package com.artin.pinch.ui

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.artin.pinch.R

@Immutable
class Palette(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val text: Color,
    val text2: Color,
    val accent: Color,
    val accent2: Color,
    val accent3: Color,
    val onAccent: Color,
    val good: Color,
    val bad: Color,
    val warn: Color,
    val glass: Color,
    /** Outline that keeps glass edges visible against the page. */
    val edge: Color,
) {
    val brush: Brush get() = Brush.linearGradient(listOf(accent, accent2, accent3))
    val hBrush: Brush get() = Brush.horizontalGradient(listOf(accent, accent2, accent3))
}

// Light mode: every surface carries a blue tint; nothing is pure white.
val LightPal = Palette(
    dark = false,
    bg = Color(0xFFDCE6F5),
    surface = Color(0xFFF3F7FE),
    surface2 = Color(0xFFD2DDF0),
    line = Color(0xFFBCCBE4),
    text = Color(0xFF0F1F3A),
    text2 = Color(0xFF586A88),
    accent = Color(0xFF2D63F5),
    accent2 = Color(0xFF3A9BFF),
    accent3 = Color(0xFF2CCBE0),
    onAccent = Color(0xFFF6FAFF),
    good = Color(0xFF119C77),
    bad = Color(0xFFE5484D),
    warn = Color(0xFFC07A08),
    glass = Color(0xFFF4F8FF),
    edge = Color(0xFF6F88B8),
)

// Dark mode: a navy tint a few steps above black.
val DarkPal = Palette(
    dark = true,
    bg = Color(0xFF16203A),
    surface = Color(0xFF1F2B48),
    surface2 = Color(0xFF2A3858),
    line = Color(0xFF364668),
    text = Color(0xFFE9F0FC),
    text2 = Color(0xFF9EB1D0),
    accent = Color(0xFF5B8CFF),
    accent2 = Color(0xFF4FB2FF),
    accent3 = Color(0xFF3FDCEB),
    onAccent = Color(0xFFF6FAFF),
    good = Color(0xFF36D3A3),
    bad = Color(0xFFFF6B6F),
    warn = Color(0xFFF2B33D),
    glass = Color(0xFF26355A),
    edge = Color(0xFF8FA6D6),
)

val LocalPal = staticCompositionLocalOf { LightPal }
val LocalStr = staticCompositionLocalOf { Strings.of("en") }

// Vazirmatn is a variable font: one file, each weight picked by its axis.
@OptIn(ExperimentalTextApi::class)
private val Vazir = FontFamily(
    Font(R.font.vazirmatn, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.vazirmatn, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.vazirmatn, FontWeight.Black, variationSettings = FontVariation.Settings(FontVariation.weight(900))),
)

/** Text styles; Persian uses Vazirmatn, English the system face. */
@Immutable
class Type(fa: Boolean) {
    private val family = if (fa) Vazir else FontFamily.Default
    private val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    private fun s(size: TextUnit, w: FontWeight, lh: TextUnit) =
        TextStyle(fontFamily = family, fontSize = size, fontWeight = w, lineHeight = lh, lineHeightStyle = trim)

    val hero = s(44.sp, FontWeight.Black, 50.sp)
    val big = s(26.sp, FontWeight.Bold, 32.sp)
    val title = s(18.sp, FontWeight.Bold, 24.sp)
    val body = s(15.sp, FontWeight.Medium, 21.sp)
    val bodyB = s(15.sp, FontWeight.Bold, 21.sp)
    val small = s(13.sp, FontWeight.Medium, 18.sp)
    val tiny = s(11.sp, FontWeight.Bold, 14.sp)
}

private val TypeEn = Type(false)
private val TypeFa = Type(true)
val LocalType = staticCompositionLocalOf { TypeEn }

@Composable
fun PinchTheme(dark: Boolean, lang: String, content: @Composable () -> Unit) {
    val pal = if (dark) DarkPal else LightPal
    val fa = lang == "fa"
    CompositionLocalProvider(
        LocalPal provides pal,
        LocalStr provides Strings.of(lang),
        LocalType provides if (fa) TypeFa else TypeEn,
        LocalLayoutDirection provides if (fa) LayoutDirection.Rtl else LayoutDirection.Ltr,
        LocalTextSelectionColors provides TextSelectionColors(pal.accent, pal.accent.copy(alpha = 0.3f)),
        content = content,
    )
}
