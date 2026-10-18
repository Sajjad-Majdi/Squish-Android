package com.artin.pinch.engine

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Video quality on the VMAF scale (what the eye sees), from Squish's own SSIM.
 *
 * Plain SSIM counts lost sensor noise and motion detail as damage, which the eye barely notices,
 * so the SSIM a given VMAF needs depends on the source: grainy night footage or a busy scene reach
 * VMAF 95 at a much lower SSIM than a clean still shot. Two features of the source capture this:
 * [noiseDb] (SSIM of a frame against its own 3×3 blur; low = grainy) and motion (mean change of the
 * blurred luma between frames ~33 ms apart). Each VMAF row is a plane in (noise, ln motion), fitted
 * with libvmaf on 16 real phone clips (123 encodes, HEVC with B-frames; leave-one-out error at
 * VMAF 95: mean 1.4, with the 0.5 dB margin 15 of 16 clips stayed at 92.6 or above).
 */
object VideoScore {
    class Features(val noise: Double, val motion: Double)

    /** A typical daylight phone clip, for when the source could not be sampled. */
    val TYPICAL = Features(15.0, 8.0)

    /** Features measured by the quality probe, so the check after the encode scores on the same scale. */
    private val byFile = ConcurrentHashMap<String, Features>()

    fun remember(m: Media, f: Features) { byFile[m.key] = f }
    fun of(m: Media): Features? = byFile[m.key]

    private val VM = doubleArrayOf(85.0, 88.0, 91.0, 93.0, 95.0, 97.0, 98.5)
    private val C0 = doubleArrayOf(8.8343, 9.4923, 10.5694, 11.6479, 13.0287, 14.5559, 16.7965)
    private val C1 = doubleArrayOf(0.3847, 0.3857, 0.3748, 0.3599, 0.3299, 0.3091, 0.2620)
    private val C2 = doubleArrayOf(-1.3186, -1.4137, -1.5502, -1.7138, -1.8431, -2.0319, -2.2124)
    /** Aim a little above the fitted line when encoding; the shown score uses the line itself. */
    private const val MARGIN_DB = 0.5

    /** SSIM dB needed for each VMAF row on a source with these features (never decreasing). */
    private fun column(f: Features): DoubleArray {
        val n = f.noise.coerceIn(7.0, 24.0)
        val lm = ln(f.motion.coerceIn(4.0, 26.0))
        val c = DoubleArray(VM.size) { C0[it] + C1[it] * n + C2[it] * lm }
        for (i in 1 until c.size) if (c[i] < c[i - 1]) c[i] = c[i - 1]
        return c
    }

    /** SSIM dB the encode must reach so this source looks like [vmaf]. */
    fun needDb(vmaf: Float, f: Features): Double = lerp(VM, column(f), vmaf.toDouble()) + MARGIN_DB

    /** VMAF-like score (0–100) of a copy whose SSIM against this source is [db]. */
    fun score(db: Double, f: Features): Float = lerp(column(f), VM, db).coerceIn(0.0, 100.0).toFloat()

    /** dB of the SSIM between a luma frame and its own 3×3 blur: how much fine grain it carries. */
    fun noiseDb(y: ByteArray, w: Int, h: Int): Double = Quality.db(Quality.ssim(y, box3(y, w, h), w, h))

    /** Mean absolute change of the blurred luma between two frames ~33 ms apart. */
    fun motion(a: ByteArray, b: ByteArray, w: Int, h: Int): Double {
        val x = box3(a, w, h)
        val y = box3(b, w, h)
        var s = 0L
        for (i in x.indices) s += abs((x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF))
        return s.toDouble() / x.size
    }

    private fun box3(src: ByteArray, w: Int, h: Int): ByteArray {
        val tmp = IntArray(w * h)
        for (r in 0 until h) {
            val o = r * w
            for (c in 0 until w) {
                tmp[o + c] = (src[o + max(c - 1, 0)].toInt() and 0xFF) + (src[o + c].toInt() and 0xFF) +
                    (src[o + min(c + 1, w - 1)].toInt() and 0xFF)
            }
        }
        val out = ByteArray(w * h)
        for (r in 0 until h) {
            val up = max(r - 1, 0) * w
            val o = r * w
            val dn = min(r + 1, h - 1) * w
            for (c in 0 until w) out[o + c] = ((tmp[up + c] + tmp[o + c] + tmp[dn + c] + 4) / 9).toByte()
        }
        return out
    }

    /** Piecewise-linear lookup over ascending [xs]; outside the table it continues the end slope. */
    private fun lerp(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
        val n = xs.size
        fun seg(i: Int): Double {
            val d = xs[i] - xs[i - 1]
            return if (d <= 1e-9) ys[i] else ys[i - 1] + (ys[i] - ys[i - 1]) * (x - xs[i - 1]) / d
        }
        if (x <= xs[0]) return seg(1)
        for (i in 1 until n) if (x <= xs[i]) return seg(i)
        return seg(n - 1)
    }
}
