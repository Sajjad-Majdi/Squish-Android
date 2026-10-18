package com.artin.pinch.engine

import kotlin.math.log10
import kotlin.math.max

/**
 * Picture quality on a 0–100 scale. Pinch measures SSIM on the luma of matching frames and maps
 * it to a VMAF-like score; the map was fitted with libvmaf on real phone footage. 94 is about the
 * point where nobody can tell the copy from the original on a phone.
 */
object Quality {
    /** What each quality level aims for: pristine, no visible loss, balanced, compact. */
    val TARGETS = floatArrayOf(97f, 94f, 88f, 80f)

    /** Lower edges of the result words: pristine, no visible loss, slight, visible, heavy. */
    private val BANDS = floatArrayOf(96.5f, 92.5f, 86f, 76f)

    // SSIM in dB (−10·log10(1 − ssim)) → score.
    private val DB = doubleArrayOf(5.0, 8.0, 11.3, 13.0, 14.3, 15.1, 16.2, 16.9, 17.8, 18.8, 21.0)
    private val SC = doubleArrayOf(0.0, 35.0, 63.0, 77.5, 85.7, 90.0, 94.7, 96.8, 98.3, 99.3, 100.0)

    /** Bucket of a measured score for the result word, 0 (pristine) … 4 (heavy loss). */
    fun band(score: Float): Int {
        for (i in BANDS.indices) if (score >= BANDS[i]) return i
        return BANDS.size
    }

    fun db(ssim: Double): Double = -10 * log10(max(1e-6, 1 - ssim))

    fun score(db: Double): Float = lerp(DB, SC, db).toFloat()

    fun dbFor(score: Float): Double = lerp(SC, DB, score.toDouble())

    private fun lerp(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
        if (x <= xs[0]) return ys[0]
        for (i in 1 until xs.size) {
            if (x <= xs[i]) return ys[i - 1] + (ys[i] - ys[i - 1]) * (x - xs[i - 1]) / (xs[i] - xs[i - 1])
        }
        return ys.last()
    }

    /**
     * Mean SSIM of two luma planes of [w]×[h]: 8×8 windows at a 4-pixel stride, built from 4×4
     * cell sums so each pixel is read once.
     */
    fun ssim(a: ByteArray, b: ByteArray, w: Int, h: Int): Double {
        val cw = w / 4
        val ch = h / 4
        if (cw < 2 || ch < 2) return 1.0
        val n = cw * ch
        val sx = IntArray(n); val sy = IntArray(n)
        val sxx = IntArray(n); val syy = IntArray(n); val sxy = IntArray(n)
        for (y in 0 until ch * 4) {
            val row = y * w
            val cell = (y shr 2) * cw
            for (cx in 0 until cw) {
                var p = row + cx * 4
                var ax = 0; var ay = 0; var axx = 0; var ayy = 0; var axy = 0
                repeat(4) {
                    val u = a[p].toInt() and 0xFF
                    val v = b[p].toInt() and 0xFF
                    ax += u; ay += v; axx += u * u; ayy += v * v; axy += u * v
                    p++
                }
                val c = cell + cx
                sx[c] += ax; sy[c] += ay; sxx[c] += axx; syy[c] += ayy; sxy[c] += axy
            }
        }
        val c1 = 6.5025 // (0.01·255)²
        val c2 = 58.5225 // (0.03·255)²
        var sum = 0.0
        for (cy in 0 until ch - 1) {
            for (cx in 0 until cw - 1) {
                val i = cy * cw + cx
                val j = i + cw
                val mx = (sx[i] + sx[i + 1] + sx[j] + sx[j + 1]) / 64.0
                val my = (sy[i] + sy[i + 1] + sy[j] + sy[j + 1]) / 64.0
                val vx = (sxx[i] + sxx[i + 1] + sxx[j] + sxx[j + 1]) / 64.0 - mx * mx
                val vy = (syy[i] + syy[i + 1] + syy[j] + syy[j + 1]) / 64.0 - my * my
                val cxy = (sxy[i] + sxy[i + 1] + sxy[j] + sxy[j + 1]) / 64.0 - mx * my
                sum += ((2 * mx * my + c1) * (2 * cxy + c2)) / ((mx * mx + my * my + c1) * (vx + vy + c2))
            }
        }
        return sum / ((cw - 1) * (ch - 1))
    }

    /** Luma (BT.601 weights, as in the calibration) of RGBA bytes. */
    fun lumaRgba(rgba: ByteArray, out: ByteArray) {
        var j = 0
        for (i in out.indices) {
            val r = rgba[j].toInt() and 0xFF
            val g = rgba[j + 1].toInt() and 0xFF
            val b = rgba[j + 2].toInt() and 0xFF
            out[i] = ((77 * r + 150 * g + 29 * b) shr 8).toByte()
            j += 4
        }
    }

    /** Luma of ARGB ints (Bitmap.getPixels). */
    fun lumaArgb(px: IntArray, out: ByteArray) {
        for (i in out.indices) {
            val c = px[i]
            out[i] = ((77 * (c shr 16 and 0xFF) + 150 * (c shr 8 and 0xFF) + 29 * (c and 0xFF)) shr 8).toByte()
        }
    }
}
