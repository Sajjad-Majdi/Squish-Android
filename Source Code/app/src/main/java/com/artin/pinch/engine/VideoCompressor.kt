package com.artin.pinch.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Hardware decode → GPU scale → hardware HEVC/H.264 encode → MP4. The audio track is copied
 * sample for sample, so sound is never re-compressed.
 */
class VideoCompressor(
    private val ctx: Context,
    private val m: Media,
    private val info: VideoInfo,
    private val plan: Planner.VideoPlan,
    private val keepLocation: Boolean,
    private val cancelled: () -> Boolean,
    private val progress: (Float) -> Unit,
    /** Called once the encode is done and the result is being compared with the source. */
    private val checking: () -> Unit = {},
) {
    class Result(val uri: Uri, val size: Long, val score: Float)

    fun run(): Result? {
        val out = Output.create(ctx, true, m.name, "mp4", "video/mp4", m.dateTaken)
        var keep = false
        try {
            var score = -1f
            val size = ctx.contentResolver.openFileDescriptor(out, "rw")!!.use { pfd ->
                transcode(pfd.fileDescriptor)
                val sz = pfd.statSize
                if (sz > 0 && sz < m.size * 0.97) {
                    checking()
                    score = runCatching {
                        VideoQuality.measure(ctx, m, info, { it.setDataSource(pfd.fileDescriptor) }, cancelled)
                    }.getOrElse { if (it is Cancelled) throw it; Log.w(TAG, "quality check failed", it); -1f }
                }
                sz
            }
            if (size <= 0 || size >= m.size * 0.97) return null
            Output.publish(ctx, out)
            keep = true
            return Result(out, size, score)
        } finally {
            if (!keep) Output.discard(ctx, out)
        }
    }

    private fun transcode(fd: FileDescriptor) {
        val vEx = MediaExtractor()
        var aEx: MediaExtractor? = null
        var dec: MediaCodec? = null
        var enc: MediaCodec? = null
        var encSurface: Surface? = null
        var gl: GlPipe? = null
        var mux: MediaMuxer? = null
        var tmpAudio: File? = null
        try {
            vEx.setDataSource(ctx, m.uri, null)
            val vTrack = CodecKit.track(vEx, "video/")
            check(vTrack >= 0) { "no video track" }
            vEx.selectTrack(vTrack)
            val inFmt = vEx.getTrackFormat(vTrack)
            val srcW = inFmt.getInteger(MediaFormat.KEY_WIDTH)
            val srcH = inFmt.getInteger(MediaFormat.KEY_HEIGHT)

            // Audio: copy as is; only codecs MP4 can't hold are re-encoded (to high-bitrate AAC).
            var aFmt: MediaFormat? = null
            val aTrack = CodecKit.track(vEx, "audio/")
            if (aTrack >= 0) {
                val f = vEx.getTrackFormat(aTrack)
                if (AudioTranscoder.canCopy(ctx, f)) {
                    aEx = MediaExtractor().apply { setDataSource(ctx, m.uri, null); selectTrack(aTrack) }
                    aFmt = f
                } else runCatching {
                    val t = File(ctx.cacheDir, "pinch-audio.m4a").also { tmpAudio = it }
                    AudioTranscoder.toAac(ctx, m.uri, aTrack, t, cancelled)
                    val e = MediaExtractor().apply { setDataSource(t.path); selectTrack(0) }
                    aEx = e
                    aFmt = e.getTrackFormat(0)
                }.onFailure { if (it is Cancelled) throw it; Log.w(TAG, "audio dropped", it) }
            }

            val mx = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { mux = it }
            if (info.rotation != 0) mx.setOrientationHint(info.rotation)
            if (keepLocation) location()?.let { (la, lo) -> runCatching { mx.setLocation(la, lo) } }

            val ec = CodecKit.encoder(plan.enc, plan.width, plan.height, plan.fps, plan.bitrate).also { enc = it }
            val es = ec.createInputSurface().also { encSurface = it }
            val g = GlPipe(es, plan.width, plan.height, srcW, srcH, hdrMode()).also { gl = it }
            val dc = CodecKit.decoder(inFmt, g.inputSurface).also { dec = it }
            ec.start()
            dc.start()

            val dInfo = MediaCodec.BufferInfo()
            val eInfo = MediaCodec.BufferInfo()
            val aInfo = MediaCodec.BufferInfo()
            val aBuf: ByteBuffer? = aFmt?.let {
                ByteBuffer.allocateDirect(max(it.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0), 1 shl 19))
            }
            var inDone = false
            var decDone = false
            var encDone = false
            var started = false
            var vOut = -1
            var aOut = -1
            var aDone = aEx == null
            val interval = if (plan.fps < info.fps * 0.9f) 1_000_000.0 / plan.fps else 0.0
            var nextKeep = Double.NaN
            var lastP = -1f
            var lastMove = SystemClock.uptimeMillis()

            fun pumpAudio(until: Long) {
                val ex = aEx ?: return
                while (!aDone) {
                    val t = ex.sampleTime
                    // -1 is the end; other negative times are encoder priming hidden by an edit list.
                    if (t == -1L) { aDone = true; break }
                    if (t > until) break
                    if (t < 0) { if (!ex.advance()) aDone = true; continue }
                    val n = ex.readSampleData(aBuf!!, 0)
                    if (n < 0) { aDone = true; break }
                    val key = ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                    aInfo.set(0, n, t, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    mx.writeSampleData(aOut, aBuf, aInfo)
                    if (!ex.advance()) aDone = true
                }
            }

            fun drainEncoder(wait: Long) {
                var timeout = wait
                while (true) {
                    val o = ec.dequeueOutputBuffer(eInfo, timeout)
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) return
                    timeout = 0
                    lastMove = SystemClock.uptimeMillis()
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(!started) { "format changed twice" }
                        vOut = mx.addTrack(ec.outputFormat)
                        aFmt?.let { f -> aOut = mx.addTrack(f) }
                        mx.start()
                        started = true
                        continue
                    }
                    if (o < 0) continue
                    if (eInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) eInfo.size = 0
                    if (eInfo.size > 0 && started) {
                        val b = ec.getOutputBuffer(o)!!
                        b.position(eInfo.offset)
                        b.limit(eInfo.offset + eInfo.size)
                        mx.writeSampleData(vOut, b, eInfo)
                        if (aOut >= 0) pumpAudio(eInfo.presentationTimeUs)
                    }
                    val eos = eInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    ec.releaseOutputBuffer(o, false)
                    if (eos) {
                        encDone = true
                        if (started && aOut >= 0) pumpAudio(Long.MAX_VALUE)
                        return
                    }
                }
            }

            while (!encDone) {
                if (cancelled()) throw Cancelled()
                if (SystemClock.uptimeMillis() - lastMove > 15_000) error("codec stalled")
                if (!inDone) {
                    val i = dc.dequeueInputBuffer(1000)
                    if (i >= 0) {
                        val n = vEx.readSampleData(dc.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            dc.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            dc.queueInputBuffer(i, 0, n, vEx.sampleTime, 0)
                            vEx.advance()
                        }
                        lastMove = SystemClock.uptimeMillis()
                    }
                }
                drainEncoder(0)
                if (!decDone) {
                    val o = dc.dequeueOutputBuffer(dInfo, 2000)
                    if (o >= 0) {
                        lastMove = SystemClock.uptimeMillis()
                        val pts = dInfo.presentationTimeUs
                        val eos = dInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        var render = dInfo.size > 0 && pts >= 0
                        if (render && interval > 0) {
                            if (nextKeep.isNaN() || pts >= nextKeep - interval * 0.25) {
                                nextKeep = if (nextKeep.isNaN()) pts + interval else nextKeep + interval
                                if (nextKeep < pts) nextKeep = pts + interval
                            } else render = false
                        }
                        dc.releaseOutputBuffer(o, render)
                        if (render) {
                            g.awaitFrame()
                            g.draw(pts * 1000)
                            val p = (pts.toFloat() / info.durationUs).coerceIn(0f, 0.99f)
                            if (p - lastP >= 0.004f) { lastP = p; progress(p) }
                        }
                        if (eos) {
                            ec.signalEndOfInputStream()
                            decDone = true
                        }
                    }
                } else {
                    drainEncoder(10_000)
                }
            }
            check(started) { "no frames encoded" }
            mx.stop()
        } finally {
            runCatching { dec?.stop() }; runCatching { dec?.release() }
            runCatching { enc?.stop() }; runCatching { enc?.release() }
            runCatching { gl?.release() }
            runCatching { encSurface?.release() }
            runCatching { mux?.release() }
            runCatching { vEx.release() }
            runCatching { aEx?.release() }
            tmpAudio?.delete()
        }
    }

    private fun hdrMode() = when (info.transfer) {
        MediaFormat.COLOR_TRANSFER_ST2084 -> GlPipe.HDR_PQ
        MediaFormat.COLOR_TRANSFER_HLG -> GlPipe.HDR_HLG
        else -> GlPipe.HDR_NONE
    }

    private fun location(): Pair<Float, Float>? {
        val store = m.storeUri ?: return null
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, MediaStore.setRequireOriginal(store))
            val s = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION) ?: return null
            val mm = Regex("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)").find(s) ?: return null
            mm.groupValues[1].toFloat() to mm.groupValues[2].toFloat()
        } catch (_: Exception) {
            null
        } finally {
            r.release()
        }
    }

    companion object {
        private const val TAG = "PinchVideo"
    }
}
