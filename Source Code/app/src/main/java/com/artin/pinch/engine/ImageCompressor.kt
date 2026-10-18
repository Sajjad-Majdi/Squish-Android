package com.artin.pinch.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.provider.MediaStore
import com.artin.pinch.data.Prefs
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Size mode hits a byte budget with the best quality that fits: a small proxy of the photo
 * predicts how size moves with resolution and quality, so the full-size image is encoded only two
 * or three times. Quality mode keeps the full size and searches the lowest quality setting whose
 * sixteen sample tiles still reach the target score. Both check the result against the original.
 */
object ImageCompressor {
    class Result(val uri: android.net.Uri, val size: Long, val score: Float)

    private const val PROXY_PX = 640_000L
    private const val MIN_PX = 640L * 480
    private const val MAX_PX = 25_000_000L

    private class Bytes : ByteArrayOutputStream(1 shl 16) {
        val data: ByteArray get() = buf
    }

    /** [quality] is the target score in quality mode, null in size mode ([s] is then used). */
    fun run(
        ctx: Context,
        m: Media,
        s: Float,
        quality: Float?,
        photo: Int,
        keepLocation: Boolean,
        cancelled: () -> Boolean,
        progress: (Float) -> Unit,
        /** Quality setting already found by [forecast]; skips the search. */
        knownQ: Int? = null,
    ): Result? {
        if (quality != null) return runQuality(ctx, m, quality, photo, keepLocation, cancelled, progress, knownQ)
        val cr = ctx.contentResolver
        val target = Planner.imageTarget(m.size, s)
        val src = ImageDecoder.createSource(cr, m.uri)

        var srcW = 0
        var srcH = 0
        val proxy = decode(src) { w, h ->
            srcW = w; srcH = h
            min(1.0, sqrt(PROXY_PX.toDouble() / (w.toLong() * h)))
        }
        val jpeg = photo == Prefs.PHOTO_JPEG && !proxy.hasAlpha()
        val format = if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.WEBP_LOSSY
        val qGood = if (jpeg) 82 else 78
        val qMin = if (jpeg) 35 else 30
        val qMax = if (jpeg) 94 else 92
        progress(0.08f)

        val pGood = encode(proxy, format, qGood).size().toDouble()
        val proxyPx = proxy.width.toLong() * proxy.height
        val srcPx = srcW.toLong() * srcH
        // Bytes grow a little slower than pixel count; 0.85 fits phone photos well.
        var px = (proxyPx * (target / pGood).pow(1 / 0.85)).toLong()
        px = px.coerceIn(min(srcPx, MIN_PX), min(srcPx, MAX_PX))
        val scale = sqrt(px.toDouble() / srcPx).let { if (it > 0.93) 1.0 else it }
        if (cancelled()) throw Cancelled()

        var bmp = decode(src) { _, _ -> min(scale, sqrt(MAX_PX.toDouble() / srcPx)) }
        progress(0.3f)
        var q = qGood
        var out = encode(bmp, format, q)
        progress(0.5f)

        if (out.size() > target) {
            q = searchQ(proxy, format, pGood * target / out.size(), qMin, qGood)
            out = encode(bmp, format, q)
            var tries = 0
            while (out.size() > target && q > qMin && tries++ < 3) {
                if (cancelled()) throw Cancelled()
                q = max(qMin, q - 7)
                out = encode(bmp, format, q)
            }
            tries = 0
            while (out.size() > target && tries++ < 4 && bmp.width * bmp.height > MIN_PX / 2) {
                if (cancelled()) throw Cancelled()
                val f = sqrt(target.toDouble() / out.size()) * 0.96
                val next = Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * f).roundToInt()), max(1, (bmp.height * f).roundToInt()), true)
                if (next !== bmp) bmp.recycle()
                bmp = next
                out = encode(bmp, format, q)
            }
        } else if (out.size() < target * 0.8 && q < qMax) {
            val q2 = searchQ(proxy, format, pGood * target * 0.95 / out.size(), qGood, qMax)
            if (q2 > q) {
                val better = encode(bmp, format, q2)
                if (better.size() <= target) { out = better; q = q2 }
            }
        }
        progress(0.8f)
        proxy.recycle()
        val outW = bmp.width
        val outH = bmp.height
        bmp.recycle()
        if (cancelled()) throw Cancelled()
        if (out.size() >= m.size * 0.97) return null

        // The check runs at the original size, so a smaller picture counts as the loss it is.
        val score = runCatching {
            val full = decode(src) { _, _ -> min(1.0, sqrt(MAX_PX.toDouble() / srcPx)) }
            val tiles = Tiles(full.width, full.height)
            val refTiles = tiles.mosaic(full)
            full.recycle()
            val ref = luma(refTiles)
            refTiles.recycle()
            check(ref, tiles, out)
        }.getOrElse { if (it is Cancelled) throw it; -1f }
        progress(0.9f)
        return save(ctx, m, out, jpeg, keepLocation, outW, outH, score, progress)
    }

    /**
     * How quality moves with the setting for one photo: score and size (scaled up from the tiles
     * to the whole picture) at a ladder of settings. Every level is read off it at once.
     */
    class Curve(private val qs: IntArray, private val scores: FloatArray, private val bytes: LongArray, private val size: Long) : Forecast {
        /** Lowest setting that reaches [target], between the measured rungs. */
        fun qFor(target: Float): Int {
            val i = scores.indexOfFirst { it >= target }
            if (i < 0) return qs.last()
            if (i == 0) return qs[0]
            val f = (target - scores[i - 1]) / (scores[i] - scores[i - 1]).coerceAtLeast(0.01f)
            return (qs[i - 1] + f * (qs[i] - qs[i - 1]) + 0.999f).toInt().coerceIn(qs[i - 1], qs[i])
        }

        override fun bytes(target: Float, size: Long): Long {
            val q = qFor(target)
            val i = qs.indexOfFirst { it >= q }.coerceAtLeast(1)
            val f = (q - qs[i - 1]).toFloat() / (qs[i] - qs[i - 1])
            return (bytes[i - 1] + f * (bytes[i] - bytes[i - 1])).toLong().coerceAtMost(size)
        }

        override fun keep(target: Float): Boolean = bytes(target, size) >= size * 0.97
    }

    private val LADDER_WEBP = intArrayOf(35, 55, 70, 80, 88, 95)
    private val LADDER_JPEG = intArrayOf(45, 62, 75, 84, 91, 97)

    fun curve(ctx: Context, m: Media, photo: Int, cancelled: () -> Boolean): Curve {
        val src = ImageDecoder.createSource(ctx.contentResolver, m.uri)
        val bmp = decode(src) { w, h -> min(1.0, sqrt(MAX_PX.toDouble() / (w.toLong() * h))) }
        val jpeg = photo == Prefs.PHOTO_JPEG && !bmp.hasAlpha()
        val format = if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.WEBP_LOSSY
        val tiles = Tiles(bmp.width, bmp.height)
        val refTiles = tiles.mosaic(bmp)
        val scale = bmp.width.toDouble() * bmp.height / (tiles.side.toDouble() * tiles.side)
        bmp.recycle()
        try {
            val ref = luma(refTiles)
            val qs = if (jpeg) LADDER_JPEG else LADDER_WEBP
            val scores = FloatArray(qs.size)
            val bytes = LongArray(qs.size)
            var best = 0f
            for (i in qs.indices) {
                if (cancelled()) throw Cancelled()
                val enc = encode(refTiles, format, qs[i])
                val d = BitmapFactory.decodeByteArray(enc.data, 0, enc.size())
                // Kept rising, so the search between rungs is well defined.
                best = maxOf(best, score(ref, d, tiles.side))
                d.recycle()
                scores[i] = best
                bytes[i] = (enc.size() * scale).toLong()
            }
            return Curve(qs, scores, bytes, m.size)
        } finally {
            refTiles.recycle()
        }
    }

    /** Lowest setting whose tiles still reach [target]. */
    private fun searchQ(
        refTiles: Bitmap,
        ref: ByteArray,
        tiles: Tiles,
        format: Bitmap.CompressFormat,
        target: Float,
        jpeg: Boolean,
        cancelled: () -> Boolean,
        progress: (Float) -> Unit,
    ): Int {
        val qMin = if (jpeg) 40 else 30
        val qMax = if (jpeg) 98 else 97
        var lo = qMin
        var hi = qMax
        while (lo < hi) {
            if (cancelled()) throw Cancelled()
            val mid = (lo + hi) / 2
            val enc = encode(refTiles, format, mid)
            val d = BitmapFactory.decodeByteArray(enc.data, 0, enc.size())
            val sc = score(ref, d, tiles.side)
            d.recycle()
            if (sc >= target) hi = mid else lo = mid + 1
            progress(1f - (hi - lo).toFloat() / (qMax - qMin))
        }
        return lo
    }

    private fun runQuality(
        ctx: Context,
        m: Media,
        target: Float,
        photo: Int,
        keepLocation: Boolean,
        cancelled: () -> Boolean,
        progress: (Float) -> Unit,
        knownQ: Int?,
    ): Result? {
        val src = ImageDecoder.createSource(ctx.contentResolver, m.uri)
        val bmp = decode(src) { w, h -> min(1.0, sqrt(MAX_PX.toDouble() / (w.toLong() * h))) }
        val jpeg = photo == Prefs.PHOTO_JPEG && !bmp.hasAlpha()
        val format = if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.WEBP_LOSSY
        val qMax = if (jpeg) 98 else 97
        progress(0.15f)
        val tiles = Tiles(bmp.width, bmp.height)
        val refTiles = tiles.mosaic(bmp)
        val ref = luma(refTiles)
        var q = knownQ ?: searchQ(refTiles, ref, tiles, format, target, jpeg, cancelled) { progress(0.15f + 0.4f * it) }
        refTiles.recycle()
        var out = encode(bmp, format, q)
        progress(0.7f)
        var score = runCatching { check(ref, tiles, out) }.getOrElse { if (it is Cancelled) throw it; -1f }
        // Tiles and the whole picture can disagree a little; one step up when the whole falls short.
        if (score >= 0f && score < target - 1f && q < qMax) {
            q = min(qMax, q + 5)
            out = encode(bmp, format, q)
            score = runCatching { check(ref, tiles, out) }.getOrElse { if (it is Cancelled) throw it; -1f }
        }
        progress(0.85f)
        val outW = bmp.width
        val outH = bmp.height
        bmp.recycle()
        if (cancelled()) throw Cancelled()
        if (out.size() >= m.size * 0.97) return null
        return save(ctx, m, out, jpeg, keepLocation, outW, outH, score, progress)
    }

    /** Score of the encoded picture [out] against the reference tiles. */
    private fun check(ref: ByteArray, tiles: Tiles, out: Bytes): Float {
        val d = BitmapFactory.decodeByteArray(out.data, 0, out.size()) ?: return -1f
        val mosaic = tiles.mosaic(d)
        d.recycle()
        return score(ref, mosaic, tiles.side).also { mosaic.recycle() }
    }

    private fun score(ref: ByteArray, b: Bitmap, side: Int): Float =
        Quality.score(Quality.db(Quality.ssim(ref, luma(b), side, side)))

    private fun luma(b: Bitmap): ByteArray {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return ByteArray(px.size).also { Quality.lumaArgb(px, it) }
    }

    /**
     * Sixteen tiles spread over a [w]×[h] picture, laid side by side in one bitmap. A picture of a
     * different size is sampled at the same places, scaled to match.
     */
    private class Tiles(val w: Int, val h: Int) {
        private val t = (min(192, min(w, h) / 4) / 16 * 16).coerceAtLeast(16)
        val side = t * 4

        fun mosaic(src: Bitmap): Bitmap {
            val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val sx = src.width.toFloat() / w
            val sy = src.height.toFloat() / h
            for (j in 0..3) for (i in 0..3) {
                val l = (w - t).coerceAtLeast(0) * i / 3
                val tp = (h - t).coerceAtLeast(0) * j / 3
                val sr = Rect((l * sx).roundToInt(), (tp * sy).roundToInt(), ((l + t) * sx).roundToInt(), ((tp + t) * sy).roundToInt())
                c.drawBitmap(src, sr, Rect(i * t, j * t, i * t + t, j * t + t), paint)
            }
            return out
        }
    }

    /** Encoded bytes → temp file (for EXIF) → MediaStore. */
    private fun save(
        ctx: Context,
        m: Media,
        out: Bytes,
        jpeg: Boolean,
        keepLocation: Boolean,
        outW: Int,
        outH: Int,
        score: Float,
        progress: (Float) -> Unit,
    ): Result {
        val cr = ctx.contentResolver
        val ext = if (jpeg) "jpg" else "webp"
        val tmp = File(ctx.cacheDir, "pinch-img.$ext")
        try {
            tmp.outputStream().use { it.write(out.data, 0, out.size()) }
            copyExif(ctx, m, tmp, keepLocation, outW, outH)
            val uri = Output.create(ctx, false, m.name, ext, if (jpeg) "image/jpeg" else "image/webp", m.dateTaken)
            var ok = false
            try {
                cr.openOutputStream(uri)!!.use { o -> tmp.inputStream().use { it.copyTo(o, 1 shl 16) } }
                Output.publish(ctx, uri)
                ok = true
                progress(1f)
                return Result(uri, tmp.length(), score)
            } finally {
                if (!ok) Output.discard(ctx, uri)
            }
        } finally {
            tmp.delete()
        }
    }

    private fun decode(src: ImageDecoder.Source, scaleFor: (Int, Int) -> Double): Bitmap =
        ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val f = scaleFor(w, h)
            if (f < 0.999) dec.setTargetSize(max(1, (w * f).roundToInt()), max(1, (h * f).roundToInt()))
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            dec.isUnpremultipliedRequired = false
        }

    private fun encode(b: Bitmap, f: Bitmap.CompressFormat, q: Int): Bytes =
        Bytes().also { b.compress(f, q, it) }

    /** Largest quality whose proxy size stays under [want] bytes. */
    private fun searchQ(proxy: Bitmap, f: Bitmap.CompressFormat, want: Double, lo0: Int, hi0: Int): Int {
        var lo = lo0
        var hi = hi0
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (encode(proxy, f, mid).size() <= want) lo = mid else hi = mid - 1
        }
        return lo
    }

    private val TAGS = arrayOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_ISO_SPEED_RATINGS, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
    )
    private val GPS = arrayOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
    )

    /** Keeps date and camera data; location only when the user asked for it. Best effort. */
    private fun copyExif(ctx: Context, m: Media, dst: File, keepLocation: Boolean, w: Int, h: Int) {
        runCatching {
            val withGps = keepLocation && m.storeUri != null &&
                ctx.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
            val srcUri = if (withGps) MediaStore.setRequireOriginal(m.storeUri!!) else m.uri
            val inExif = ctx.contentResolver.openInputStream(srcUri)!!.use { ExifInterface(it) }
            val out = ExifInterface(dst.absolutePath)
            for (t in TAGS) inExif.getAttribute(t)?.let { out.setAttribute(t, it) }
            if (withGps) for (t in GPS) inExif.getAttribute(t)?.let { out.setAttribute(t, it) }
            out.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            out.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, w.toString())
            out.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, h.toString())
            out.setAttribute(ExifInterface.TAG_SOFTWARE, "Squish")
            out.saveAttributes()
        }
    }
}
