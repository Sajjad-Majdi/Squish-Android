package com.artin.pinch.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** Small in-memory thumbnail cache; MediaStore thumbnails first, decoding as a fallback. */
object Thumbs {
    private val cache = object : LruCache<String, ImageBitmap>(40 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(4)

    fun peek(key: String): ImageBitmap? = cache.get(key)

    suspend fun load(ctx: Context, uri: Uri, video: Boolean, px: Int): ImageBitmap? = withContext(io) {
        val key = "$uri@$px"
        cache.get(key)?.let { return@withContext it }
        val b = runCatching { ctx.contentResolver.loadThumbnail(uri, Size(px, px), null) }.getOrNull()
            ?: runCatching { if (video) frame(ctx, uri, px) else decode(ctx, uri, px) }.getOrNull()
        b?.asImageBitmap()?.also { cache.put(key, it) }
    }

    fun decode(ctx: Context, uri: Uri, px: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val f = px.toDouble() / minOf(w, h)
            if (f < 1) d.setTargetSize(max(1, (w * f).roundToInt()), max(1, (h * f).roundToInt()))
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    /** Decodes so the longer side is at most [max] pixels. */
    fun decodeFit(ctx: Context, uri: Uri, maxSide: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val f = maxSide.toDouble() / maxOf(w, h)
            if (f < 1) d.setTargetSize(max(1, (w * f).roundToInt()), max(1, (h * f).roundToInt()))
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    private fun frame(ctx: Context, uri: Uri, px: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, px * 2, px * 2)
        } finally {
            r.release()
        }
    }
}

@Composable
fun Thumb(uri: Uri, video: Boolean, modifier: Modifier, px: Int = 240) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val key = "$uri@$px"
    var img by remember(key) { mutableStateOf(Thumbs.peek(key)) }
    LaunchedEffect(key) { if (img == null) img = Thumbs.load(ctx, uri, video, px) }
    val a by animateFloatAsState(if (img != null) 1f else 0f, tween(220), label = "thumb")
    Box(modifier.background(p.surface2)) {
        img?.let {
            Image(it, null, Modifier.fillMaxSize().graphicsLayer { alpha = a }, contentScale = ContentScale.Crop)
        }
    }
}
