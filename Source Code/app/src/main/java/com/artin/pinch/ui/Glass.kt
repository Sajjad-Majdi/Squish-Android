package com.artin.pinch.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.min

/** The soft blue scene behind every page; glass surfaces bend and tint it. */
val LocalBackdrop = staticCompositionLocalOf<GraphicsLayer?> { null }

/**
 * Liquid glass: what is behind is bent at the rim like a thick lens (with a hint of colour
 * fringing) and optionally blurred. Android 13+ gets the lens; 12 gets the blur only.
 */
object Glass {
    fun effect(size: IntSize, blurPx: Float, depthPx: Float, radiusPx: Float): androidx.compose.ui.graphics.RenderEffect? {
        if (size.width <= 0 || size.height <= 0 || Build.VERSION.SDK_INT < 31) return null
        val blur = if (blurPx > 0.5f) RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP) else null
        if (Build.VERSION.SDK_INT < 33) return blur?.asComposeRenderEffect()
        val lens = lens(size, depthPx, radiusPx)
        return (if (blur != null) RenderEffect.createChainEffect(blur, lens) else lens).asComposeRenderEffect()
    }

    @RequiresApi(33)
    private fun lens(size: IntSize, depthPx: Float, radiusPx: Float): RenderEffect {
        val sh = RuntimeShader(LENS)
        sh.setFloatUniform("size", size.width.toFloat(), size.height.toFloat())
        sh.setFloatUniform("radius", radiusPx.coerceIn(1f, min(size.width, size.height) / 2f))
        sh.setFloatUniform("depth", depthPx)
        return RenderEffect.createRuntimeShaderEffect(sh, "content")
    }

    private const val LENS = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float depth;
half4 main(float2 p) {
    float2 inner = clamp(p, float2(radius, radius), size - float2(radius, radius));
    float2 v = p - inner;
    float d = length(v);
    float edge = clamp(d / radius, 0.0, 1.0);
    float2 n = d > 0.001 ? v / d : float2(0.0, 0.0);
    float2 o = -n * edge * edge * edge * depth;
    half4 c = content.eval(p + o);
    half r = content.eval(p + o * 1.15).r;
    half b = content.eval(p + o * 0.85).b;
    return half4(r, c.g, b, c.a);
}
"""
}

/**
 * Specular highlight on the top edge, a sheen over the upper half and bounce light below. A thin
 * outline under the light keeps the shape readable where glass and page are close in tone.
 */
fun DrawScope.glassRim(dark: Boolean, radiusPx: Float, strong: Boolean = false) {
    val r = CornerRadius(min(radiusPx, min(size.width, size.height) / 2))
    val w = 1.2.dp.toPx()
    val inset = Offset(w / 2, w / 2)
    val sz = Size(size.width - w, size.height - w)
    // Faint at the top where the highlight sits, firm at the bottom where the shape meets the page.
    drawRoundRect(
        if (dark) Brush.verticalGradient(listOf(EDGE_DARK, EDGE_DARK))
        else Brush.verticalGradient(0f to EDGE_LIGHT.copy(alpha = 0.16f), 1f to EDGE_LIGHT.copy(alpha = 0.55f)),
        inset, sz, r, style = Stroke(w),
    )
    drawRect(Brush.verticalGradient(0f to SHEEN.copy(alpha = if (dark) 0.07f else 0.22f), 0.48f to Color.Transparent))
    drawRoundRect(
        Brush.verticalGradient(0f to SHEEN.copy(alpha = if (dark) 0.45f else if (strong) 1f else 0.9f), 0.5f to Color.Transparent),
        inset, sz, r, style = Stroke(w),
    )
    drawRoundRect(
        Brush.verticalGradient(0.55f to Color.Transparent, 1f to BOUNCE.copy(alpha = if (dark) 0.24f else 0.3f)),
        inset, sz, r, style = Stroke(w),
    )
}

private val SHEEN = Color(0xFFF6FAFF)
private val EDGE_LIGHT = Color(0xFF5A75AA)
private val EDGE_DARK = Color(0x1F8FA6D6)
private val BOUNCE = Color(0xFFBFDBFF)

/**
 * A glass surface: the backdrop seen through a lens at the rim, a translucent tint and a
 * specular rim. [radius] larger than half the height gives a pill.
 */
fun Modifier.glass(radius: Dp, tint: Color? = null, depth: Dp = 10.dp, blur: Dp = 0.dp): Modifier = composed {
    val p = LocalPal.current
    val bd = LocalBackdrop.current
    val d = LocalDensity.current
    val layer = rememberGraphicsLayer()
    var pos by remember { mutableStateOf(Offset.Zero) }
    var sz by remember { mutableStateOf(IntSize.Zero) }
    val rPx = with(d) { radius.toPx() }
    val effect = remember(sz, rPx, depth, blur) {
        with(d) { Glass.effect(sz, blur.toPx(), depth.toPx(), min(rPx, min(sz.width, sz.height) / 2f)) }
    }
    val fill = tint ?: p.glass.copy(alpha = if (p.dark) 0.5f else 0.62f)
    this
        .onGloballyPositioned { pos = it.positionInRoot(); sz = it.size }
        .clip(androidx.compose.foundation.shape.RoundedCornerShape(radius))
        .drawBehind {
            if (bd != null && effect != null) {
                val o = pos
                layer.renderEffect = effect
                layer.record(sz) { translate(-o.x, -o.y) { drawLayer(bd) } }
                drawLayer(layer)
            }
            drawRect(fill)
            glassRim(p.dark, rPx)
        }
}

/** Cheap glass for long lists: tint and rim only, no lens. */
fun Modifier.glassLite(radius: Dp, tint: Color? = null): Modifier = composed {
    val p = LocalPal.current
    val rPx = with(LocalDensity.current) { radius.toPx() }
    val fill = tint ?: p.glass.copy(alpha = if (p.dark) 0.5f else 0.64f)
    this.clip(androidx.compose.foundation.shape.RoundedCornerShape(radius)).drawBehind {
        drawRect(fill)
        glassRim(p.dark, rPx)
    }
}

/** Draws the backdrop as a page background (for pushed screens that cover the tabs). */
fun Modifier.backdrop(): Modifier = composed {
    val bd = LocalBackdrop.current
    val p = LocalPal.current
    this.drawBehind { if (bd != null) drawLayer(bd) else drawRect(p.bg) }
}

/**
 * Liquid press: the element swells under the finger, a light blooms where it was touched, and on
 * release the two axes settle out of step, like jelly. [swell] is the peak scale.
 */
fun Modifier.liquidPress(enabled: Boolean = true, swell: Float = 1.06f, radius: Dp = 24.dp, onClick: () -> Unit): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    val sx = remember { Animatable(1f) }
    val sy = remember { Animatable(1f) }
    val glow = remember { Animatable(0f) }
    var touch by remember { mutableStateOf(Offset.Unspecified) }
    val scope = rememberCoroutineScope()
    val click by rememberUpdatedState(onClick)
    val rPx = with(LocalDensity.current) { radius.toPx() }
    this
        .graphicsLayer { scaleX = sx.value; scaleY = sy.value }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onPress = { o ->
                    touch = o
                    scope.launch { glow.animateTo(1f, tween(120)) }
                    scope.launch { sx.animateTo(swell, spring(0.55f, 520f)) }
                    scope.launch { sy.animateTo(swell, spring(0.55f, 520f)) }
                    tryAwaitRelease()
                    scope.launch { glow.animateTo(0f, tween(480)) }
                    scope.launch { sx.animateTo(1f, spring(0.3f, 280f), initialVelocity = -(swell - 1f) * 18f) }
                    scope.launch { sy.animateTo(1f, spring(0.3f, 280f), initialVelocity = (swell - 1f) * 18f) }
                },
                onTap = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    click()
                },
            )
        }
        .drawWithContent {
            drawContent()
            val g = glow.value
            if (g > 0.01f) {
                val c = if (touch.isSpecified) touch else center
                drawRoundRect(
                    Brush.radialGradient(listOf(SHEEN.copy(alpha = 0.38f * g), Color.Transparent), c, size.maxDimension * 0.65f),
                    cornerRadius = CornerRadius(min(rPx, min(size.width, size.height) / 2)),
                )
            }
        }
}
