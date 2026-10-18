package com.artin.pinch.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch

private const val START = 135f
private const val SWEEP = 270f

/**
 * 270° ring with a draggable knob. [value] runs from [min] to [max] in [step]s; ticks mark
 * [marks]. With coarse steps the knob follows the finger freely and springs into the nearest
 * notch on release, so the stops feel like detents in liquid.
 */
@Composable
fun StrengthDial(
    value: Float,
    min: Float,
    max: Float,
    marks: List<Float>,
    onChange: (Float) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    step: Float = 0.01f,
    inside: @Composable () -> Unit,
) {
    val p = LocalPal.current
    val haptic = LocalHapticFeedback.current
    val frac = ((value - min) / (max - min)).coerceIn(0f, 1f)
    var dragging by remember { mutableStateOf(false) }
    val knob = remember { Animatable(frac) }
    val scope = rememberCoroutineScope()
    val notched = step >= (max - min) / 12
    LaunchedEffect(frac) {
        if (!dragging) knob.animateTo(frac, if (notched) spring(0.55f, 420f) else spring(0.8f, Spring.StiffnessMediumLow))
    }
    val shown = knob.value
    val cur by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)

    fun pick(pos: Offset, size: IntSize, guard: Boolean) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val deg = Math.toDegrees(atan2((pos.y - c.y).toDouble(), (pos.x - c.x).toDouble())).toFloat()
        var a = (deg - START + 720f) % 360f
        if (a > SWEEP) a = if (a > SWEEP + (360f - SWEEP) / 2) 0f else SWEEP
        val f = a / SWEEP
        val old = knob.value
        if (guard && abs(f - old) > 0.4f) return // no jumping across the gap at the bottom
        if (dragging) scope.launch { knob.snapTo(f) }
        val v = (((min + f * (max - min)) / step).roundToInt() * step).coerceIn(min, max)
        if (abs(v - cur) > step / 2) {
            change(v)
            haptic.performHapticFeedback(if (notched) HapticFeedbackType.SegmentTick else HapticFeedbackType.SegmentFrequentTick)
        }
    }

    fun release() {
        dragging = false
        scope.launch { knob.animateTo(((cur - min) / (max - min)).coerceIn(0f, 1f), spring(0.5f, 380f)) }
        onEnd()
    }

    Box(
        modifier
            .pointerInput(min, max, step) {
                detectDragGestures(
                    onDragStart = { dragging = true; pick(it, size, false) },
                    onDragEnd = { release() },
                    onDragCancel = { release() },
                ) { ch, _ -> ch.consume(); pick(ch.position, size, true) }
            }
            .pointerInput(min, max, step) {
                detectTapGestures { pick(it, size, false); onEnd() }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 18.dp.toPx()
            val r = size.minDimension / 2 - stroke - 6.dp.toPx()
            val tl = Offset(center.x - r, center.y - r)
            val sz = Size(r * 2, r * 2)
            // Soft glow behind the active arc.
            drawCircle(
                Brush.radialGradient(listOf(p.accent2.copy(alpha = if (p.dark) 0.18f else 0.12f), Color.Transparent), center, r * 1.15f),
                r * 1.15f,
            )
            // Glass tube: an outline, a translucent body, a bright line along its inner top.
            drawArc(
                p.edge.copy(alpha = if (p.dark) 0.14f else 0.3f), START, SWEEP, false, tl, sz,
                style = Stroke(stroke + 2.dp.toPx(), cap = StrokeCap.Round),
            )
            drawArc(p.glass.copy(alpha = if (p.dark) 0.55f else 0.6f), START, SWEEP, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            val hi = r - stroke * 0.32f
            drawArc(
                Color(0xFFF6FAFF).copy(alpha = if (p.dark) 0.18f else 0.75f), START, SWEEP, false,
                Offset(center.x - hi, center.y - hi), Size(hi * 2, hi * 2), style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round),
            )
            for (m in marks) {
                val mf = (m - min) / (max - min)
                val ang = Math.toRadians((START + SWEEP * mf).toDouble())
                val rr = r + stroke * 0.95f
                drawCircle(if (mf <= shown) p.accent2 else p.line, 2.6.dp.toPx(), Offset(center.x + rr * cos(ang).toFloat(), center.y + rr * sin(ang).toFloat()))
            }
            if (shown > 0.001f) rotate(START, center) {
                val sweep = Brush.sweepGradient(
                    0f to p.accent, 0.38f to p.accent2, 0.75f to p.accent3, 0.9f to p.accent3, 1f to p.accent,
                    center = center,
                )
                drawArc(sweep, 0f, SWEEP * shown, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            knob(shown, r, stroke, p)
        }
        inside()
    }
}

private fun DrawScope.knob(f: Float, r: Float, stroke: Float, p: Palette) {
    val ang = Math.toRadians((START + SWEEP * f).toDouble())
    val k = Offset(center.x + r * cos(ang).toFloat(), center.y + r * sin(ang).toFloat())
    drawCircle(Color.Black.copy(alpha = if (p.dark) 0.35f else 0.16f), stroke * 0.92f, k + Offset(0f, 2.dp.toPx()))
    // A glass bead: tinted body, a blue core and a specular dot up-left.
    drawCircle(p.surface.copy(alpha = 0.92f), stroke * 0.86f, k)
    drawCircle(Brush.radialGradient(listOf(p.accent2, p.accent), k, stroke * 0.4f), stroke * 0.38f, k)
    drawCircle(Color(0xE6F6FAFF), stroke * 0.16f, k + Offset(-stroke * 0.3f, -stroke * 0.32f))
    drawCircle(Color(0xFFF6FAFF).copy(alpha = if (p.dark) 0.3f else 0.9f), stroke * 0.86f, k, style = Stroke(1.2.dp.toPx()))
}

/** Thin progress ring used while a batch runs and in the result summary. */
@Composable
fun ProgressRing(f: Float, modifier: Modifier = Modifier, width: Float = 14f, content: @Composable () -> Unit = {}) {
    val p = LocalPal.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = width.dp.toPx()
            val r = size.minDimension / 2 - stroke / 2 - 2.dp.toPx()
            val tl = Offset(center.x - r, center.y - r)
            val sz = Size(r * 2, r * 2)
            drawArc(p.surface2, 0f, 360f, false, tl, sz, style = Stroke(stroke))
            if (f > 0.001f) rotate(-90f, center) {
                val sweep = Brush.sweepGradient(
                    0f to p.accent, 0.5f to p.accent2, 0.92f to p.accent3, 1f to p.accent, center = center,
                )
                drawArc(sweep, 0f, 360f * f.coerceIn(0f, 1f), false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        content()
    }
}
