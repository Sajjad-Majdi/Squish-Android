package com.artin.pinch.engine

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One picked photo or video. */
@Stable
class Media(
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
    val video: Boolean,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val dateTaken: Long,
    /** The MediaStore entry behind [uri], when there is one; only those can go to the trash. */
    val storeUri: Uri?,
) {
    /** Filled in the background for videos; the size estimate waits for it. */
    var info by mutableStateOf<VideoInfo?>(null)
    var probeFailed by mutableStateOf(false)
    val key: String get() = uri.toString()
}

class VideoInfo(
    val mime: String,
    /** Stored (unrotated) frame size. */
    val width: Int,
    val height: Int,
    val rotation: Int,
    val durationUs: Long,
    val fps: Float,
    val videoBitrate: Long,
    val audioBytes: Long,
    val hasAudio: Boolean,
    val transfer: Int,
)

object Probe {
    /** Reads what Pinch needs from any content uri (shares, the file picker). Null when it is not a photo or video. */
    fun fromUri(ctx: Context, uri: Uri): Media? {
        val cr = ctx.contentResolver
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        if (size <= 0) size = runCatching { cr.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1
        if (size <= 0) return null
        val mime = cr.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: return null
        if (!supported(mime)) return null
        val video = mime.startsWith("video/")
        var w = 0
        var h = 0
        if (!video) runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            w = o.outWidth; h = o.outHeight
        }
        val store = storeUriOf(ctx, uri)
        var taken = 0L
        if (store != null) runCatching {
            cr.query(store, arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) taken = c.getLong(0)
            }
        }
        return Media(uri, name, mime, size, video, w, h, 0, taken, store)
    }

    fun supported(mime: String): Boolean =
        (mime.startsWith("image/") || mime.startsWith("video/")) && mime != "image/gif" && !mime.contains("svg")

    private fun storeUriOf(ctx: Context, uri: Uri): Uri? {
        if (uri.authority == MediaStore.AUTHORITY && "picker" !in uri.pathSegments) return uri
        return runCatching { MediaStore.getMediaUri(ctx, uri) }.getOrNull()
    }

    fun video(ctx: Context, m: Media): VideoInfo {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, m.uri, null)
            var vf: MediaFormat? = null
            var audio = -1
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (vf == null && mime.startsWith("video/")) vf = f
                else if (audio < 0 && mime.startsWith("audio/")) audio = i
            }
            val f = vf ?: error("no video track")
            val w = f.getInteger(MediaFormat.KEY_WIDTH)
            val h = f.getInteger(MediaFormat.KEY_HEIGHT)
            var rotation = f.getInteger(MediaFormat.KEY_ROTATION, -1)
            var durationUs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
            var fps = if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) f.getNumber(MediaFormat.KEY_FRAME_RATE)?.toFloat() ?: 0f else 0f
            if (rotation < 0 || durationUs <= 0 || fps <= 0f) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, m.uri)
                    if (rotation < 0) rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (durationUs <= 0) durationUs = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
                    if (fps <= 0f) {
                        val frames = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() ?: 0L
                        if (frames > 0 && durationUs > 0) fps = (frames * 1_000_000.0 / durationUs).toFloat()
                    }
                } catch (_: Exception) {
                } finally {
                    r.release()
                }
            }
            if (rotation < 0) rotation = 0
            if (fps !in 1f..480f) fps = 30f
            // Exact audio size: walk the sample table without reading any data.
            var audioBytes = 0L
            if (audio >= 0) {
                ex.selectTrack(audio)
                while (true) {
                    val s = ex.sampleSize
                    if (s < 0) break
                    audioBytes += s
                    if (!ex.advance()) break
                }
            }
            val videoBytes = (m.size - audioBytes).coerceAtLeast(m.size / 10)
            val bitrate = if (durationUs > 0) (videoBytes * 8.0 * 1_000_000 / durationUs).toLong() else 8_000_000L
            return VideoInfo(
                f.getString(MediaFormat.KEY_MIME) ?: "", w, h, rotation, durationUs.coerceAtLeast(1), fps,
                bitrate, audioBytes, audio >= 0, f.getInteger(MediaFormat.KEY_COLOR_TRANSFER, 0),
            )
        } finally {
            ex.release()
        }
    }
}
