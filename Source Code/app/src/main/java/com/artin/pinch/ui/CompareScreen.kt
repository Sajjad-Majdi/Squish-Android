package com.artin.pinch.ui

import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.artin.pinch.engine.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Before / after with a draggable split; pinch to zoom both at once. Videos compare the same frame. */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun CompareScreen(job: Job) {
    val p = LocalPal.current
    val s = LocalStr.current
    val t = LocalType.current
    val ctx = LocalContext.current
    val m = job.m
    val out = job.outUri
    if (out == null) {
        LaunchedEffect(Unit) { Nav.route = null }
        return
    }
    var before by remember { mutableStateOf<ImageBitmap?>(null) }
    var after by remember { mutableStateOf<ImageBitmap?>(null) }
    var pos by remember { mutableFloatStateOf(0.3f) }
    val one = remember { Dispatchers.IO.limitedParallelism(1) }

    if (!m.video) {
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                runCatching { Thumbs.decodeFit(ctx, m.uri, 2560) }.getOrNull()?.let { before = it.asImageBitmap() }
                runCatching { Thumbs.decodeFit(ctx, out, 2560) }.getOrNull()?.let { after = it.asImageBitmap() }
            }
        }
    } else {
        val r = remember { arrayOfNulls<MediaMetadataRetriever>(2) }
        DisposableEffect(Unit) { onDispose { r.forEach { runCatching { it?.release() } } } }
        LaunchedEffect(pos) {
            delay(90)
            withContext(one) {
                runCatching {
                    if (r[0] == null) r[0] = MediaMetadataRetriever().apply { setDataSource(ctx, m.uri) }
                    if (r[1] == null) r[1] = MediaMetadataRetriever().apply { setDataSource(ctx, out) }
                    val dur = m.info?.durationUs
                        ?: ((r[0]!!.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000)
                    val at = (pos * dur).toLong()
                    r[0]!!.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST, 1920, 1920)?.let { before = it.asImageBitmap() }
                    r[1]!!.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST, 1920, 1920)?.let { after = it.asImageBitmap() }
                }
            }
        }
    }

    val mime = if (m.video) "video/mp4" else ctx.contentResolver.getType(out) ?: "image/*"
    Column(Modifier.fillMaxSize().backdrop().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.back, { Nav.route = null }, mirror = true)
            Spacer(Modifier.width(10.dp))
            Txt(m.name, t.bodyB, p.text, Modifier.weight(1f))
            IconBtn(Ic.share, { shareUris(ctx, listOf(out), mime) })
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).glass(24.dp, depth = 14.dp)) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                SplitView(before, after, s.before, s.after)
            }
        }
        if (m.video) {
            Spacer(Modifier.height(10.dp))
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Scrub(pos, { pos = it }, Modifier.padding(horizontal = 16.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        // Same sides as the picture above: before on the left, after on the right.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt(s.before, t.small, p.text2)
                Txt(s.size(m.size), t.title, p.text)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(p.good.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 6.dp),
                ) { Txt(s.drop(1f - job.outSize.toFloat() / m.size), t.bodyB, p.good) }
                if (job.score >= 0f) {
                    Spacer(Modifier.height(6.dp))
                    QualityTag(job.score, words = true)
                }
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Txt(s.after, t.small, p.text2)
                Txt(s.size(job.outSize), t.title, p.text, brush = p.brush)
            }
        }
        }
        Spacer(Modifier.height(12.dp))
        PillButton(s.open, { openUri(ctx, out, mime) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), primary = false, icon = Ic.open)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SplitView(before: ImageBitmap?, after: ImageBitmap?, beforeLabel: String, afterLabel: String) {
    val p = LocalPal.current
    val t = LocalType.current
    var split by remember { mutableFloatStateOf(0.5f) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    val mx = size.width * (scale - 1) / 2
                    val my = size.height * (scale - 1) / 2
                    offset = Offset((offset.x + pan.x).coerceIn(-mx, mx), (offset.y + pan.y).coerceIn(-my, my))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1.01f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            },
    ) {
        val w = constraints.maxWidth.toFloat()
        val layer = Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
        }
        if (after != null) Image(after, null, layer, contentScale = ContentScale.Fit)
        if (before != null) Box(
            Modifier.fillMaxSize().drawWithContent { clipRect(right = size.width * split) { this@drawWithContent.drawContent() } },
        ) { Image(before, null, layer, contentScale = ContentScale.Fit) }
        if (before == null || after == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ProgressRing(0.25f, Modifier.size(36.dp), 4f)
        }
        Label(beforeLabel, Modifier.align(Alignment.TopStart).padding(10.dp), t)
        Label(afterLabel, Modifier.align(Alignment.TopEnd).padding(10.dp), t)
        val handle = with(LocalDensity.current) { 22.dp.toPx() }
        Box(
            Modifier.fillMaxHeight().width(44.dp)
                .offset { IntOffset((w * split - handle).roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { ch, dx -> ch.consume(); split = (split + dx / w).coerceIn(0f, 1f) }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxHeight().width(2.dp).background(HANDLE))
            Box(Modifier.size(38.dp).clip(CircleShape).background(HANDLE), contentAlignment = Alignment.Center) {
                Row {
                    Icon(Ic.back, p.accent, size = 14.dp)
                    Icon(Ic.forward, p.accent, size = 14.dp)
                }
            }
        }
    }
}

@Composable
private fun Label(text: String, modifier: Modifier, t: Type) {
    Box(modifier.clip(RoundedCornerShape(50)).background(Color(0x99140F2A)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Txt(text, t.tiny, HANDLE)
    }
}

@Composable
private fun Scrub(f: Float, onChange: (Float) -> Unit, modifier: Modifier) {
    val p = LocalPal.current
    Box(
        modifier.fillMaxWidth().height(32.dp)
            .pointerInput(Unit) {
                detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { ch, _ -> ch.consume(); onChange((ch.position.x / size.width).coerceIn(0f, 1f)) }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height / 2
            val h = 6.dp.toPx()
            drawRoundRect(p.surface2, Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(size.width, h), androidx.compose.ui.geometry.CornerRadius(h))
            drawRoundRect(p.hBrush, Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(size.width * f, h), androidx.compose.ui.geometry.CornerRadius(h))
            val x = (size.width * f).coerceIn(9.dp.toPx(), size.width - 9.dp.toPx())
            drawCircle(Color.Black.copy(alpha = 0.18f), 10.dp.toPx(), Offset(x, y + 1.5.dp.toPx()))
            drawCircle(p.surface, 9.dp.toPx(), Offset(x, y))
            drawCircle(p.accent2, 4.dp.toPx(), Offset(x, y))
        }
    }
}

private val HANDLE = Color(0xFFF7F4FF)
