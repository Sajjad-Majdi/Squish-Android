package com.artin.pinch.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface

/** Codec setup shared by the transcoder, the quality probes and the quality check. */
object CodecKit {
    private const val TAG = "PinchCodec"

    fun track(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true) return i
        }
        return -1
    }

    /**
     * A surface-input encoder: VBR, I-frame every 10 s, SDR BT.709. With [bFrames], up to two
     * B-frames between references (Android 10+); an encoder that refuses them is set up without.
     */
    fun encoder(e: Codecs.Enc, w: Int, h: Int, fps: Int, bitrate: Int, bFrames: Boolean = false): MediaCodec {
        fun format(extras: Boolean, bf: Boolean = false) = MediaFormat.createVideoFormat(e.mime, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // Longer gaps between key frames save a little more; players still seek fine.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
            if (e.caps.encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            if (extras) {
                // Android 13+ lifts low VBR bitrates to a "quality floor"; level 0 opts out of it.
                setInteger("android._encoding-quality-level", 0)
                setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                if (!e.hevc) {
                    val high = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                    val level = e.caps.profileLevels.filter { it.profile == high }.maxOfOrNull { it.level }
                    if (level != null) {
                        setInteger(MediaFormat.KEY_PROFILE, high)
                        setInteger(MediaFormat.KEY_LEVEL, level)
                    }
                } else if (bf) {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                }
                if (bf && Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 2)
            }
        }
        val codec = MediaCodec.createByCodecName(e.info.name)
        val tries = if (bFrames) listOf(true to true, true to false, false to false) else listOf(true to false, false to false)
        for ((n, t) in tries.withIndex()) {
            try {
                codec.configure(format(t.first, t.second), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                break
            } catch (x: Exception) {
                if (n == tries.lastIndex) throw x
                Log.w(TAG, "encoder setup refused (extras=${t.first}, bframes=${t.second})", x)
                codec.reset()
            }
        }
        return codec
    }

    /** A decoder rendering to [surface], frames left in stored orientation. */
    fun decoder(inFmt: MediaFormat, surface: Surface): MediaCodec {
        val mime = inFmt.getString(MediaFormat.KEY_MIME)!!
        val name = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(inFmt) }.getOrNull()
        val codec = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(mime)
        // A decoder rendering to a surface applies the rotation tag itself; the muxer's orientation
        // hint already carries it, so the decoder must not rotate too.
        val plain = MediaFormat(inFmt).apply { setInteger(MediaFormat.KEY_ROTATION, 0) }
        try {
            val f = MediaFormat(plain).apply {
                setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, 0)
            }
            codec.configure(f, surface, null, 0)
        } catch (t: Exception) {
            Log.w(TAG, "decoder extras refused", t)
            codec.reset()
            codec.configure(plain, surface, null, 0)
        }
        return codec
    }
}
