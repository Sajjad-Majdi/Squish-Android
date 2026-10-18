package com.artin.pinch.engine

import android.content.Context
import android.os.Build

/**
 * Whether this phone's encoder really makes B-frames (about a quarter fewer bits at the same
 * quality). Nobody documents it per chip, so quality probes try them and remember the answer per
 * encoder and system build; full encodes use them only once a probe proved them. An encoder that
 * ignores the request is not asked again; one whose B-frame output broke twice is given up on.
 */
object BFrames {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("pinch_codecs", Context.MODE_PRIVATE)
    private fun key(e: Codecs.Enc) = e.info.name + "|" + Build.FINGERPRINT

    private fun works(ctx: Context, e: Codecs.Enc): Boolean? =
        sp(ctx).let { if (it.contains("bf|" + key(e))) it.getBoolean("bf|" + key(e), false) else null }

    private fun fails(ctx: Context, e: Codecs.Enc) = sp(ctx).getInt("bffail|" + key(e), 0)

    /** A probe may try them: Android 10+, not known to be ignored, and not broken twice. */
    fun mayTry(ctx: Context, e: Codecs.Enc) = Build.VERSION.SDK_INT >= 29 && works(ctx, e) != false && fails(ctx, e) < 2

    /** A full encode uses them only after a probe saw real reordered frames. */
    fun use(ctx: Context, e: Codecs.Enc) = mayTry(ctx, e) && works(ctx, e) == true

    /** A probe finished: [reordered] tells whether the encoder really made B-frames. */
    fun probed(ctx: Context, e: Codecs.Enc, reordered: Boolean) {
        sp(ctx).edit().putBoolean("bf|" + key(e), reordered).apply()
    }

    /** B-frame output could not be read back or looked broken. */
    fun failed(ctx: Context, e: Codecs.Enc) {
        sp(ctx).edit().putInt("bffail|" + key(e), fails(ctx, e) + 1).apply()
    }
}
