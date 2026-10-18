package com.artin.pinch.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Measures how close a compressed video looks to its source, and finds the smallest bitrate that
 * still reaches a quality target. Frames are compared at phone-screen size (short side ≤ 1080).
 */
object VideoQuality {
    private const val TAG = "PinchQuality"
    private const val TOL = 2_000L
    /** dB of SSIM gained per doubling of bitrate; the probes refine it per video. */
    private const val SLOPE = 1.9

    /** Frame size the comparison runs at, in stored orientation. */
    fun compareSize(info: VideoInfo): Pair<Int, Int> {
        val f = min(1.0, 1080.0 / min(info.width, info.height))
        return ((info.width * f).roundToInt() / 4 * 4) to ((info.height * f).roundToInt() / 4 * 4)
    }

    /**
     * Score of the finished file against its source: [count] frames spread over the video, the
     * output's frames first, then the source frames with the same timestamps.
     */
    fun measure(
        ctx: Context,
        m: Media,
        info: VideoInfo,
        openOut: (MediaExtractor) -> Unit,
        cancelled: () -> Boolean,
        count: Int = 8,
    ): Float {
        val (cw, ch) = compareSize(info)
        val times = LongArray(count) { (info.durationUs * (it + 0.5) / count).toLong() }
        val openSrc: (MediaExtractor) -> Unit = { it.setDataSource(ctx, m.uri, null) }
        // The muxer may start the copy's clock at zero; line the two clocks up.
        val shift = firstPts(openSrc) - firstPts(openOut)
        val outPts = ArrayList<Long>(count)
        val outY = ArrayList<ByteArray>(count)
        grab(openOut, times, cw, ch, GlPipe.HDR_NONE, cancelled) { _, pts, y -> outPts += pts + shift; outY += y }
        if (outPts.isEmpty()) return -1f
        var sum = 0.0
        var n = 0
        grab(openSrc, outPts.toLongArray(), cw, ch, GlPipe.hdrOf(info.transfer), cancelled) { i, pts, y ->
            if (abs(pts - outPts[i]) <= 20_000) { sum += Quality.ssim(outY[i], y, cw, ch); n++ }
        }
        if (n == 0) return -1f
        return Quality.score(Quality.db(sum / n))
    }

    /**
     * How quality moves with bitrate for one video: SSIM dB is close to a straight line in
     * log2(bitrate), so two probes give the line and every quality level is read off it at once.
     */
    class Curve(
        private val info: VideoInfo,
        private val preferHevc: Boolean,
        private val b1: Int,
        private val d1: Double,
        private val b2: Int,
        private val d2: Double,
        private val lo: Int,
        private val hi: Int,
    ) : Forecast {
        private val slope = ((d1 - d2) / (ln(b1.toDouble() / b2) / ln(2.0))).let { if (it in 0.6..4.0) it else SLOPE }
        private val cache = HashMap<Float, Planner.VideoPlan?>()

        /** Full-size plan for [target], or null when the source is already that small. */
        @Synchronized
        fun planFor(target: Float): Planner.VideoPlan? = cache.getOrPut(target) {
            val tDb = Quality.dbFor(target)
            // Read off the line from the nearer probe.
            val (b, d) = if (abs(tDb - d1) <= abs(tDb - d2)) b1 to d1 else b2 to d2
            // A little headroom: the full encode has more scene changes than three short slices.
            val br = (b * 2.0.pow((tDb - d) / slope).coerceIn(0.2, 5.0) * 1.06).coerceIn(lo.toDouble(), hi.toDouble()).roundToInt()
            if (br >= info.videoBitrate * 0.88) null else Planner.quality(info, preferHevc, target, br)
        }

        override fun bytes(target: Float, size: Long): Long = planFor(target)?.estBytes?.coerceAtMost(size) ?: size
        override fun keep(target: Float): Boolean = planFor(target) == null
    }

    /**
     * One probe encode of three short slices at the bitrate guessed for score 90, in the middle of
     * the levels; the line through it uses the usual slope. Fast enough to run while the user is
     * still choosing; the check after the real encode reports the exact score.
     */
    fun curve(
        ctx: Context,
        m: Media,
        info: VideoInfo,
        preferHevc: Boolean,
        cancelled: () -> Boolean,
        progress: (Float) -> Unit,
    ): Curve {
        val top = Planner.quality(info, preferHevc, 90f)
        val enc = top.enc
        val ranges = ranges(info.durationUs)
        val (cw, ch) = compareSize(info)
        val every = max(1, (top.fps * 0.3f).roundToInt())
        val refPts = ArrayList<Long>()
        val refY = ArrayList<ByteArray>()
        val tmp = File.createTempFile("probe", ".mp4", ctx.cacheDir)
        val lo = max(enc.video.bitrateRange.lower, 150_000)
        val hi = enc.video.bitrateRange.upper
        var step = 0

        fun probe(b: Int): Double {
            val first = refPts.isEmpty()
            val start = encodeSlices(ctx, m, info, top, b, ranges, tmp, cw, ch, if (first) every else 0, refPts, refY, cancelled) {
                progress(it * 0.8f)
            }
            val openTmp: (MediaExtractor) -> Unit = { it.setDataSource(tmp.path) }
            val shift = start - firstPts(openTmp)
            var sum = 0.0
            var n = 0
            grab(openTmp, LongArray(refPts.size) { refPts[it] - shift }, cw, ch, GlPipe.HDR_NONE, cancelled) { i, pts, y ->
                if (abs(pts + shift - refPts[i]) <= 20_000) { sum += Quality.ssim(refY[i], y, cw, ch); n++ }
            }
            check(n > 0) { "no probe frames" }
            step++
            progress(1f)
            return Quality.db(sum / n)
        }

        try {
            val b1 = top.bitrate
            val d1 = probe(b1)
            Log.i(TAG, "${m.name}: ${b1 / 1000}k → ${"%.2f".format(d1)} dB (source ${info.videoBitrate / 1000}k)")
            return Curve(info, preferHevc, b1, d1, b1, d1, lo, hi)
        } finally {
            tmp.delete()
        }
    }

    /** Three 1.2-second slices at 20 %, 50 % and 80 %; short clips are probed from the start (up to 3.6 s). */
    private fun ranges(dur: Long): List<LongArray> =
        if (dur < 7_000_000) listOf(longArrayOf(0, min(dur, 3_600_000)))
        else listOf(0.2, 0.5, 0.8).map { f ->
            val c = (dur * f).toLong()
            longArrayOf(c - 600_000, c + 600_000)
        }

    /** Earliest presentation time among the first video samples (B-frames come out of order). */
    private fun firstPts(open: (MediaExtractor) -> Unit): Long {
        val ex = MediaExtractor()
        try {
            open(ex)
            ex.selectTrack(CodecKit.track(ex, "video/"))
            var best = Long.MAX_VALUE
            repeat(8) {
                val t = ex.sampleTime
                if (t >= 0) best = min(best, t)
                if (!ex.advance()) return if (best == Long.MAX_VALUE) 0 else best
            }
            return if (best == Long.MAX_VALUE) 0 else best
        } finally {
            ex.release()
        }
    }

    /**
     * Encodes only the [ranges] of the source into [out] (video only). When [every] > 0, every
     * [every]-th frame is also kept as a reference, at [cw]×[ch]. Returns the first frame's time.
     */
    private fun encodeSlices(
        ctx: Context,
        m: Media,
        info: VideoInfo,
        plan: Planner.VideoPlan,
        bitrate: Int,
        ranges: List<LongArray>,
        out: File,
        cw: Int,
        ch: Int,
        every: Int,
        refPts: MutableList<Long>,
        refY: MutableList<ByteArray>,
        cancelled: () -> Boolean,
        progress: (Float) -> Unit,
    ): Long {
        val ex = MediaExtractor()
        var dec: MediaCodec? = null
        var enc: MediaCodec? = null
        var es: android.view.Surface? = null
        var gl: GlPipe? = null
        var mux: MediaMuxer? = null
        try {
            ex.setDataSource(ctx, m.uri, null)
            val vt = CodecKit.track(ex, "video/")
            check(vt >= 0) { "no video track" }
            ex.selectTrack(vt)
            val fmt = ex.getTrackFormat(vt)
            val mx = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { mux = it }
            val ec = CodecKit.encoder(plan.enc, plan.width, plan.height, plan.fps, bitrate).also { enc = it }
            val s = ec.createInputSurface().also { es = it }
            val g = GlPipe(s, plan.width, plan.height, info.width, info.height, GlPipe.hdrOf(info.transfer)).also { gl = it }
            val dc = CodecKit.decoder(fmt, g.inputSurface).also { dec = it }
            ec.start()
            dc.start()
            val total = ranges.sumOf { it[1] - it[0] }.toFloat()
            var doneUs = 0L
            var r = 0
            ex.seekTo(ranges[0][0], MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val dInfo = MediaCodec.BufferInfo()
            val eInfo = MediaCodec.BufferInfo()
            var inDone = false
            var decDone = false
            var encDone = false
            var started = false
            var vOut = -1
            var n = 0
            var start = -1L
            var lastMove = SystemClock.uptimeMillis()

            fun drain(wait: Long) {
                var timeout = wait
                while (true) {
                    val o = ec.dequeueOutputBuffer(eInfo, timeout)
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) return
                    timeout = 0
                    lastMove = SystemClock.uptimeMillis()
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        vOut = mx.addTrack(ec.outputFormat); mx.start(); started = true
                        continue
                    }
                    if (o < 0) continue
                    if (eInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) eInfo.size = 0
                    if (eInfo.size > 0 && started) {
                        val b = ec.getOutputBuffer(o)!!
                        b.position(eInfo.offset); b.limit(eInfo.offset + eInfo.size)
                        mx.writeSampleData(vOut, b, eInfo)
                    }
                    val eos = eInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    ec.releaseOutputBuffer(o, false)
                    if (eos) { encDone = true; return }
                }
            }

            while (!encDone) {
                if (cancelled()) throw Cancelled()
                if (SystemClock.uptimeMillis() - lastMove > 10_000) error("probe stalled")
                if (!inDone) {
                    val i = dc.dequeueInputBuffer(1000)
                    if (i >= 0) {
                        val t = ex.sampleTime
                        val sz = if (t < 0 || t > ranges[r][1] + 600_000) -1 else ex.readSampleData(dc.getInputBuffer(i)!!, 0)
                        if (sz < 0) {
                            dc.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            dc.queueInputBuffer(i, 0, sz, t, 0)
                            ex.advance()
                        }
                        lastMove = SystemClock.uptimeMillis()
                    }
                }
                drain(0)
                if (decDone) { drain(10_000); continue }
                val o = dc.dequeueOutputBuffer(dInfo, 2000)
                if (o < 0) continue
                lastMove = SystemClock.uptimeMillis()
                val pts = dInfo.presentationTimeUs
                val eos = dInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val render = dInfo.size > 0 && pts >= ranges[r][0] && pts <= ranges[r][1]
                dc.releaseOutputBuffer(o, render)
                if (render) {
                    g.awaitFrame()
                    if (start < 0) start = pts
                    if (every > 0 && n % every == every / 2) { refPts += pts; refY += g.grab(cw, ch) }
                    g.draw(pts * 1000)
                    n++
                    progress(((doneUs + pts - ranges[r][0]) / total).coerceIn(0f, 1f))
                }
                if (eos) {
                    doneUs += ranges[r][1] - ranges[r][0]
                    if (r < ranges.lastIndex) {
                        r++
                        ex.seekTo(ranges[r][0], MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        dc.flush()
                        inDone = false
                        ec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                    } else {
                        ec.signalEndOfInputStream()
                        decDone = true
                    }
                }
            }
            check(started) { "probe encoded nothing" }
            mx.stop()
            return start
        } finally {
            runCatching { dec?.stop() }; runCatching { dec?.release() }
            runCatching { enc?.stop() }; runCatching { enc?.release() }
            runCatching { gl?.release() }
            runCatching { es?.release() }
            runCatching { mux?.release() }
            runCatching { ex.release() }
        }
    }

    /**
     * Decodes a video and hands over the luma (at [w]×[h]) of the first frame at or after each of
     * the sorted [wants]. Seeks when the next wanted time is far ahead.
     */
    private fun grab(
        open: (MediaExtractor) -> Unit,
        wants: LongArray,
        w: Int,
        h: Int,
        hdr: Int,
        cancelled: () -> Boolean,
        onFrame: (Int, Long, ByteArray) -> Unit,
    ) {
        if (wants.isEmpty()) return
        val ex = MediaExtractor()
        var dec: MediaCodec? = null
        var gl: GlPipe? = null
        try {
            open(ex)
            val vt = CodecKit.track(ex, "video/")
            check(vt >= 0) { "no video track" }
            ex.selectTrack(vt)
            val fmt = ex.getTrackFormat(vt)
            val g = GlPipe(null, w, h, fmt.getInteger(MediaFormat.KEY_WIDTH), fmt.getInteger(MediaFormat.KEY_HEIGHT), hdr)
                .also { gl = it }
            val dc = CodecKit.decoder(fmt, g.inputSurface).also { dec = it }
            dc.start()
            if (wants[0] > 1_500_000) ex.seekTo(wants[0], MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val info = MediaCodec.BufferInfo()
            var i = 0
            var inDone = false
            var lastMove = SystemClock.uptimeMillis()
            while (i < wants.size) {
                if (cancelled()) throw Cancelled()
                if (SystemClock.uptimeMillis() - lastMove > 10_000) error("decoder stalled")
                if (!inDone) {
                    val ib = dc.dequeueInputBuffer(1000)
                    if (ib >= 0) {
                        val n = ex.readSampleData(dc.getInputBuffer(ib)!!, 0)
                        if (n < 0) {
                            dc.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            dc.queueInputBuffer(ib, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                        lastMove = SystemClock.uptimeMillis()
                    }
                }
                val o = dc.dequeueOutputBuffer(info, 2000)
                if (o < 0) continue
                lastMove = SystemClock.uptimeMillis()
                val pts = info.presentationTimeUs
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val take = info.size > 0 && pts >= 0 && pts >= wants[i] - TOL
                dc.releaseOutputBuffer(o, take)
                if (take) {
                    g.awaitFrame()
                    val y = g.grab(w, h)
                    while (i < wants.size && pts >= wants[i] - TOL) { onFrame(i, pts, y); i++ }
                    if (i < wants.size && wants[i] - pts > 1_500_000) {
                        ex.seekTo(wants[i], MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        dc.flush()
                        inDone = false
                    }
                }
                if (eos) break
            }
        } finally {
            runCatching { dec?.stop() }; runCatching { dec?.release() }
            runCatching { gl?.release() }
            runCatching { ex.release() }
        }
    }
}
