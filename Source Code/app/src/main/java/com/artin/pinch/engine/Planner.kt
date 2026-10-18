package com.artin.pinch.engine

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Encoder lookup. Hardware first; HEVC unless the user asked for H.264. */
object Codecs {
    class Enc(val info: MediaCodecInfo, val mime: String) {
        val caps: MediaCodecInfo.CodecCapabilities = info.getCapabilitiesForType(mime)
        val video: MediaCodecInfo.VideoCapabilities = caps.videoCapabilities
        val hevc get() = mime == MediaFormat.MIMETYPE_VIDEO_HEVC
    }

    private val all by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder } }

    private fun encoders(mime: String): List<Enc> = all
        .filter { i -> i.supportedTypes.any { it.equals(mime, true) } }
        .mapNotNull { runCatching { Enc(it, mime) }.getOrNull() }
        .sortedBy { if (it.info.isHardwareAccelerated) 0 else 1 }

    private val ordered = HashMap<Boolean, List<Enc>>()

    /** Preferred codec in hardware, the other in hardware, then software of each. */
    @Synchronized
    fun candidates(preferHevc: Boolean): List<Enc> = ordered.getOrPut(preferHevc) {
        val a = encoders(if (preferHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC)
        val b = encoders(if (preferHevc) MediaFormat.MIMETYPE_VIDEO_AVC else MediaFormat.MIMETYPE_VIDEO_HEVC)
        a.filter { it.info.isHardwareAccelerated } + b.filter { it.info.isHardwareAccelerated } +
            a.filterNot { it.info.isHardwareAccelerated } + b.filterNot { it.info.isHardwareAccelerated }
    }

    /** Shrinks a frame size until the encoder accepts it, keeping its alignment. */
    fun fit(enc: Enc, w: Int, h: Int): Pair<Int, Int> {
        val wa = max(2, enc.video.widthAlignment)
        val ha = max(2, enc.video.heightAlignment)
        var cw = w.toDouble()
        var ch = h.toDouble()
        repeat(24) {
            val aw = align(cw.toInt(), wa)
            val ah = align(ch.toInt(), ha)
            if (enc.video.isSizeSupported(aw, ah)) return aw to ah
            cw *= 0.92; ch *= 0.92
        }
        return align(w, wa) to align(h, ha)
    }

    private fun align(v: Int, a: Int) = max(a, v / a * a)
}

object Planner {
    class VideoPlan(
        val enc: Codecs.Enc,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
        val estBytes: Long,
        val fits: Boolean,
    )

    private val LADDER = intArrayOf(2160, 1440, 1080, 720, 540, 480, 360, 240)
    private const val MIN_BITRATE = 120_000.0
    private const val MIN_IMAGE_BYTES = 12_000L

    /**
     * The strength is the share of the video stream to remove; audio is copied untouched.
     * Resolution and frame rate drop only as far as needed to keep enough bits per pixel.
     */
    fun video(info: VideoInfo, s: Float, preferHevc: Boolean): VideoPlan? {
        // First encoder that can take the planned frame size without shrinking it much.
        var best: VideoPlan? = null
        for (enc in Codecs.candidates(preferHevc)) {
            val plan = runCatching { plan(enc, info, s) }.getOrNull() ?: continue
            if (plan.fits) return plan
            if (best == null || plan.width * plan.height > best.width * best.height) best = plan
        }
        return best
    }

    private fun plan(enc: Codecs.Enc, info: VideoInfo, s: Float): VideoPlan {
        val durS = info.durationUs / 1e6
        val src = info.videoBitrate.toDouble()
        val target = (src * (1 - s)).coerceAtLeast(MIN_BITRATE).coerceAtMost(max(src, MIN_BITRATE))
        val bppMin = if (enc.hevc) 0.06 else 0.09
        val w = info.width
        val h = info.height
        var fps = info.fps
        if (fps > 32f && target / (w.toDouble() * h * fps) < bppMin) fps /= ceil(fps / 30f)
        val short = min(w, h)
        val long = max(w, h)
        var outShort = short
        val ladder = listOf(short) + LADDER.filter { it < short }
        outShort = ladder.last()
        for (c in ladder) {
            val px = c.toDouble() * (long.toDouble() * c / short)
            if (target / (px * fps) >= bppMin) { outShort = c; break }
        }
        val outLong = (long.toLong() * outShort / short).toInt()
        val (ow, oh) = if (w >= h) outLong to outShort else outShort to outLong
        val (fw, fh) = Codecs.fit(enc, ow, oh)
        val br = target.roundToInt().coerceIn(enc.video.bitrateRange.lower, enc.video.bitrateRange.upper)
        val est = (br * durS / 8 + info.audioBytes + 8_000 + durS * 600).toLong()
        return VideoPlan(enc, fw, fh, fps.roundToInt().coerceAtLeast(1), br, est, fw.toLong() * fh >= ow.toLong() * oh * 0.85)
    }

    /**
     * Quality mode keeps the full frame size and frame rate; only the bitrate is chosen. Without a
     * measured [bitrate] it is guessed from a bits-per-pixel model fitted on phone footage.
     */
    fun quality(info: VideoInfo, preferHevc: Boolean, target: Float, bitrate: Int? = null): VideoPlan {
        var best: VideoPlan? = null
        for (enc in Codecs.candidates(preferHevc)) {
            val plan = runCatching { qualityPlan(enc, info, target, bitrate) }.getOrNull() ?: continue
            if (plan.fits) return plan
            if (best == null || plan.width * plan.height > best.width * best.height) best = plan
        }
        return best ?: error("No video encoder")
    }

    private fun qualityPlan(enc: Codecs.Enc, info: VideoInfo, target: Float, bitrate: Int?): VideoPlan {
        val (fw, fh) = Codecs.fit(enc, info.width, info.height)
        val px = fw.toDouble() * fh
        val fps = info.fps.toDouble()
        // ~0.065 bits per pixel reaches 94 on 1080p30 with phone HEVC hardware; H.264 needs ~45 % more.
        // Bigger frames and higher frame rates need fewer bits per pixel.
        val guess = (if (enc.hevc) 0.065 else 0.094) *
            2.0.pow((Quality.dbFor(target) - Quality.dbFor(94f)) / 1.9) *
            (2_073_600 / px).pow(0.2) * (30.0 / fps).pow(0.3) * px * fps
        val br = (bitrate ?: guess.roundToInt()).coerceIn(enc.video.bitrateRange.lower, enc.video.bitrateRange.upper)
        val durS = info.durationUs / 1e6
        val est = (br * durS / 8 + info.audioBytes + 8_000 + durS * 600).toLong()
        return VideoPlan(enc, fw, fh, fps.roundToInt().coerceAtLeast(1), br, est, fw.toLong() * fh >= info.width.toLong() * info.height * 0.85)
    }

    fun imageTarget(size: Long, s: Float): Long = (size * (1 - s).toDouble()).toLong().coerceAtLeast(MIN_IMAGE_BYTES)

    /** Rough output size in quality mode; the real number comes from measuring each file. */
    fun estimateQuality(m: Media, target: Float, preferHevc: Boolean): Long {
        val est = if (m.video) {
            val i = m.info ?: return m.size
            runCatching { quality(i, preferHevc, target).estBytes }.getOrDefault(m.size)
        } else {
            // Share of a phone JPEG a WebP keeps at each target; lossless sources shrink about twice as much.
            val share = when {
                target >= 97f -> 0.55
                target >= 94f -> 0.36 + (target - 94f) / 3f * 0.19
                target >= 88f -> 0.24 + (target - 88f) / 6f * 0.12
                else -> 0.16 + (target - 80f).coerceAtLeast(0f) / 8f * 0.08
            } * if (m.mime == "image/png" || m.mime == "image/bmp") 0.45 else 1.0
            (m.size * share).toLong()
        }
        return min(est, m.size)
    }

    fun estimate(m: Media, s: Float, preferHevc: Boolean): Long {
        val est = if (m.video) {
            val i = m.info
            if (i != null) video(i, s, preferHevc)?.estBytes ?: m.size else (m.size * (1 - s).toDouble()).toLong()
        } else imageTarget(m.size, s)
        return min(est, m.size)
    }

    /** The result word ([Quality.band]) a size setting will roughly give on phone footage. */
    fun qualityLevel(s: Float): Int = when {
        s < 0.55f -> 0
        s < 0.72f -> 1
        s < 0.85f -> 2
        s < 0.93f -> 3
        else -> 4
    }
}
