package com.artin.pinch.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import kotlin.math.min

/**
 * Only for sources whose audio codec can't go into MP4 as it is (AC-3, Vorbis, MP3 in AVI…):
 * re-encodes to AAC at a high, transparent bitrate. Everything else is copied untouched.
 */
object AudioTranscoder {
    /** True when MediaMuxer takes this track into an MP4 as it is. */
    fun canCopy(ctx: Context, f: MediaFormat): Boolean {
        val tmp = File(ctx.cacheDir, "pinch-probe.mp4")
        var mux: MediaMuxer? = null
        return try {
            mux = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux.addTrack(f)
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { mux?.release() }
            tmp.delete()
        }
    }

    fun toAac(ctx: Context, uri: Uri, track: Int, out: File, cancelled: () -> Boolean) {
        val ex = MediaExtractor()
        var dec: MediaCodec? = null
        var enc: MediaCodec? = null
        var mux: MediaMuxer? = null
        try {
            ex.setDataSource(ctx, uri, null)
            ex.selectTrack(track)
            val inF = ex.getTrackFormat(track)
            val dc = MediaCodec.createDecoderByType(inF.getString(MediaFormat.KEY_MIME)!!).also { dec = it }
            dc.configure(inF, null, null, 0)
            dc.start()
            val mx = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { mux = it }
            var muxTrack = -1
            val dInfo = MediaCodec.BufferInfo()
            val eInfo = MediaCodec.BufferInfo()
            var inDone = false
            var decDone = false
            var encDone = false
            var pend = -1
            var pendOff = 0
            var pendLeft = 0
            var pendPts = 0L
            var pendEos = false
            var rate = 48000
            var channels = 2
            var fed = 0L

            while (!encDone) {
                if (cancelled()) throw Cancelled()
                if (!inDone) {
                    val i = dc.dequeueInputBuffer(2000)
                    if (i >= 0) {
                        val n = ex.readSampleData(dc.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            dc.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                        } else {
                            dc.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                if (!decDone && pend < 0) {
                    val o = dc.dequeueOutputBuffer(dInfo, 2000)
                    if (o >= 0) {
                        pend = o; pendOff = dInfo.offset; pendLeft = dInfo.size
                        pendPts = dInfo.presentationTimeUs
                        pendEos = dInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    }
                }
                if (pend >= 0 && enc == null) {
                    val of = dc.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val ef = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, min(320_000, 160_000 * channels))
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
                    }
                    val ec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { enc = it }
                    ec.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    ec.start()
                }
                val e = enc
                if (pend >= 0 && e != null) {
                    val ei = e.dequeueInputBuffer(2000)
                    if (ei >= 0) {
                        val dst = e.getInputBuffer(ei)!!
                        val n = min(dst.remaining(), pendLeft)
                        if (n > 0) {
                            val src = dc.getOutputBuffer(pend)!!
                            src.position(pendOff); src.limit(pendOff + n)
                            dst.put(src)
                        }
                        val last = n == pendLeft
                        val pts = fed * 1_000_000L / (rate.toLong() * channels * 2)
                        fed += n
                        e.queueInputBuffer(ei, 0, n, maxOf(pts, 0L), if (last && pendEos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        pendOff += n; pendLeft -= n
                        if (last) {
                            dc.releaseOutputBuffer(pend, false)
                            pend = -1
                            if (pendEos) decDone = true
                        }
                    }
                }
                if (e != null) while (true) {
                    val o = e.dequeueOutputBuffer(eInfo, if (decDone) 5000 else 0)
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxTrack = mx.addTrack(e.outputFormat); mx.start(); continue
                    }
                    if (o < 0) continue
                    if (eInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) eInfo.size = 0
                    if (eInfo.size > 0 && muxTrack >= 0) {
                        val b = e.getOutputBuffer(o)!!
                        b.position(eInfo.offset); b.limit(eInfo.offset + eInfo.size)
                        mx.writeSampleData(muxTrack, b, eInfo)
                    }
                    e.releaseOutputBuffer(o, false)
                    if (eInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { encDone = true; break }
                }
                if (decDone && enc == null) break
            }
            mx.stop()
        } finally {
            runCatching { dec?.stop() }; runCatching { dec?.release() }
            runCatching { enc?.stop() }; runCatching { enc?.release() }
            runCatching { mux?.release() }
            ex.release()
        }
    }
}
