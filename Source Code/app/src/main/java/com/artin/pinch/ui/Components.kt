package com.artin.pinch.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.artin.pinch.engine.Quality
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Text with an explicit colour, always. */
@Composable
fun Txt(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign? = null,
    brush: Brush? = null,
) {
    val st = if (brush != null) style.copy(brush = brush) else style.copy(color = color)
    BasicText(
        text, modifier, style = if (align != null) st.copy(textAlign = align) else st,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Every tap in the app is a liquid press. [scale] below 1 is read as "how much it reacts":
 * 0.94 swells to 1.06, 0.98 to 1.02.
 */
fun Modifier.press(enabled: Boolean = true, scale: Float = 0.94f, radius: Dp = 24.dp, onClick: () -> Unit): Modifier =
    liquidPress(enabled, (2f - scale).coerceIn(1.02f, 1.1f), radius, onClick)

@Composable
fun Icon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp, mirror: Boolean = false) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Image(
        icon, null,
        modifier.size(size).then(if (mirror && rtl) Modifier.graphicsLayer { scaleX = -1f } else Modifier),
        colorFilter = ColorFilter.tint(tint),
    )
}

@Composable
fun IconBtn(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color? = null, mirror: Boolean = false) {
    val p = LocalPal.current
    Box(
        modifier.size(40.dp).press(scale = 0.9f, radius = 20.dp, onClick = onClick).glass(20.dp, depth = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, tint ?: p.text, size = 20.dp, mirror = mirror) }
}

/** The app mark: the launcher icon's two layers, cropped to its visible circle. */
@Composable
fun Logo(size: Dp, modifier: Modifier = Modifier) {
    // Adaptive layers are 108 units with a 72-unit visible area, so they are drawn 1.5x and centred.
    val crop = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.5f; scaleY = 1.5f }
    Box(modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape)) {
        Image(androidx.compose.ui.res.painterResource(com.artin.pinch.R.mipmap.ic_launcher_background), null, crop)
        Image(androidx.compose.ui.res.painterResource(com.artin.pinch.R.mipmap.ic_launcher_foreground), null, crop)
    }
}

/** One shared header for every top-level screen: small round logo, name, actions. */
@Composable
fun Header(title: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    val p = LocalPal.current
    val s = LocalStr.current
    val t = LocalType.current
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Logo(30.dp)
        Spacer(Modifier.width(10.dp))
        Txt(title ?: s.app, t.title, p.text, Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/** Primary: a glossy blue drop. Secondary: clear glass. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 54.dp,
) {
    val p = LocalPal.current
    val t = LocalType.current
    val fg = if (primary) p.onAccent else p.text
    Row(
        modifier
            .height(height)
            .press(enabled, 0.95f, height / 2, onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .then(
                if (primary) Modifier.clip(RoundedCornerShape(50)).drawBehind {
                    drawRect(p.hBrush)
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, p.accent3.copy(alpha = 0.35f))))
                    glassRim(false, size.height / 2, strong = true)
                } else Modifier.glass(height / 2),
            )
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, fg, size = 20.dp)
            Spacer(Modifier.width(8.dp))
        }
        Txt(text, t.bodyB, fg)
    }
}

/**
 * Glass track with a glass droplet for the choice. The droplet lifts, stretches with its speed on
 * the way and lands with a small wobble.
 */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPal.current
    val t = LocalType.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val n = options.size
    BoxWithConstraints(modifier.height(38.dp).glass(19.dp, depth = 6.dp).padding(3.dp)) {
        val seg = maxWidth / n
        val segPx = with(LocalDensity.current) { seg.toPx() }
        fun visual(i: Int) = if (rtl) n - 1 - i else i
        val x = remember { Animatable(visual(selected) * segPx) }
        val lift = remember { Animatable(1f) }
        LaunchedEffect(selected, segPx, rtl) {
            val target = visual(selected) * segPx
            if (abs(x.value - target) < 1f) return@LaunchedEffect
            coroutineScope {
                launch { lift.animateTo(1.12f, tween(110)); lift.animateTo(1f, spring(0.32f, 300f)) }
                x.animateTo(target, spring(0.68f, 200f))
            }
        }
        Box(
            Modifier.align(AbsoluteAlignment.TopLeft)
                .absoluteOffset { IntOffset(x.value.roundToInt(), 0) }
                .width(seg).fillMaxHeight()
                .dropletMotion(x, segPx, lift)
                .clip(RoundedCornerShape(50))
                .drawBehind {
                    drawRect(if (p.dark) p.line.copy(alpha = 0.85f) else p.surface.copy(alpha = 0.92f))
                    glassRim(p.dark, size.height / 2)
                },
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { i, o ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50))
                        .clickable(remember { MutableInteractionSource() }, null) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) { Txt(o, t.small, if (i == selected) p.text else p.text2) }
            }
        }
    }
}

/** Stretch along the motion, squash across it, and swell by [lift]. */
fun Modifier.dropletMotion(x: Animatable<Float, *>, segPx: Float, lift: Animatable<Float, *>): Modifier =
    graphicsLayer {
        val v = abs(x.velocity as Float)
        val stretch = (1f + v / (segPx * 9f)).coerceIn(1f, 1.35f)
        scaleX = lift.value * stretch
        scaleY = lift.value / sqrt(stretch)
    }

/** A glass switch: the bead stretches while it slides. */
@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPal.current
    val travel = with(LocalDensity.current) { 20.dp.toPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val on = if (rtl) 0f else travel
    val off = if (rtl) travel else 0f
    val x = remember { Animatable(if (checked) on else off) }
    val lift = remember { Animatable(1f) }
    LaunchedEffect(checked, rtl) {
        coroutineScope {
            launch { lift.animateTo(1.15f, tween(100)); lift.animateTo(1f, spring(0.3f, 320f)) }
            x.animateTo(if (checked) on else off, spring(0.55f, 520f))
        }
    }
    Box(
        Modifier.size(48.dp, 28.dp)
            .then(
                if (checked) Modifier.clip(RoundedCornerShape(50)).drawBehind {
                    drawRect(p.hBrush); glassRim(false, size.height / 2)
                } else Modifier.glass(14.dp, depth = 5.dp),
            )
            .clickable(remember { MutableInteractionSource() }, null) { onChange(!checked) }
            .padding(3.dp),
    ) {
        Box(
            Modifier.align(AbsoluteAlignment.CenterLeft)
                .absoluteOffset { IntOffset(x.value.roundToInt(), 0) }
                .size(22.dp)
                .dropletMotion(x, travel * 2, lift)
                .clip(RoundedCornerShape(50))
                .drawBehind {
                    drawRect(if (checked) p.onAccent else p.text2.copy(alpha = if (p.dark) 0.9f else 0.55f))
                    drawRect(Brush.radialGradient(listOf(Color.Transparent, p.accent.copy(alpha = 0.12f)), center, size.minDimension / 2))
                    glassRim(p.dark && !checked, size.height / 2)
                },
        )
    }
}

/** The selected / primary look: a glossy blue drop. */
fun Modifier.glossy(): Modifier = composed {
    val p = LocalPal.current
    this.clip(RoundedCornerShape(50)).drawBehind {
        drawRect(p.hBrush)
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, p.accent3.copy(alpha = 0.35f))))
        glassRim(false, size.height / 2, strong = true)
    }
}

/**
 * A plain list row straight on the scene: no glass of its own, a hairline under it. Lists stay
 * light so the glass controls around them stand out.
 */
@Composable
fun ListRow(divider: Boolean, enabled: Boolean = true, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val p = LocalPal.current
    androidx.compose.foundation.layout.Column {
        Row(
            Modifier.fillMaxWidth().press(enabled, 0.98f, 14.dp, onClick).padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        if (divider) Spacer(
            Modifier.padding(start = 64.dp).fillMaxWidth().height(1.dp)
                .drawBehind { drawRect(p.line.copy(alpha = if (p.dark) 0.5f else 0.8f)) },
        )
    }
}

/** Measured quality: a coloured dot and the score, with its word when [words] is set. */
@Composable
fun QualityTag(score: Float, modifier: Modifier = Modifier, words: Boolean = false) {
    val p = LocalPal.current
    val s = LocalStr.current
    val t = LocalType.current
    val c = p.scoreColor(score)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(7.dp)) { drawCircle(c) }
        Spacer(Modifier.width(5.dp))
        Txt(s.score(score) + if (words) " · " + s.bands[Quality.band(score)] else "", t.tiny, c)
    }
}

fun Palette.scoreColor(score: Float): Color = when (Quality.band(score)) {
    0, 1 -> good
    2 -> accent
    3 -> warn
    else -> bad
}

/** A raised glass card; in light mode a soft navy shadow lifts it off the page. */
fun Modifier.card(@Suppress("UNUSED_PARAMETER") color: Color, radius: Dp = 20.dp): Modifier = composed {
    val p = LocalPal.current
    val lift = if (p.dark) Modifier else Modifier.shadow(
        8.dp, RoundedCornerShape(radius), ambientColor = CARD_SHADOW.copy(alpha = 0.14f), spotColor = CARD_SHADOW.copy(alpha = 0.24f),
    )
    this.then(lift).then(if (radius <= 16.dp) Modifier.glassLite(radius) else Modifier.glass(radius))
}

private val CARD_SHADOW = Color(0xFF14254A)
