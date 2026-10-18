# Squish 1.1: noise-aware quality target (calibration)

Date: 2026-10-18. Goal: replace the fixed SSIM-dB → score table with `needDb(vmaf, features)` so "no visible loss" means the same VMAF on every kind of clip.

## Result in one view

| model | features the app must measure | target VMAF 95 (leave-one-out, 16 slices): mean reached | mean abs error | worst | slices < 93 | inverse (dB → VMAF) MAE |
|---|---|---|---|---|---|---|
| 1. 2-D table (as asked) | noiseDb | 94.67 | 3.26 | **83.8** | **6/16** | 2.67 (max error −11 / +14) |
| 1 + 0.8 dB margin | noiseDb | 96.30 | 3.18 | 83.8 | 1/16 | — |
| **2. noise + motion (recommended)** | noiseDb + motion | 94.73 | **1.37** | 89.2 | 2/16 | **1.49** (max error −4.3 / +6.8) |
| **2 + 0.5 dB margin (recommended setting)** | noiseDb + motion | 96.36 | 1.86 | **92.6** | **1/16** | (inverse uses no margin) |

- noiseDb alone leaves ~1.2 dB rms error (≈ ±3 VMAF). Adding one motion number cuts it to ~0.45 dB rms (LOO, VMAF 95 row: max error 2.9 → 1.0 dB). Motion matters because VMAF (and the eye) forgive more on moving content.
- Use model 2 with `MARGIN_DB = 0.5f` for encoding ("No visible loss" = VMAF 95 → reaches ~96.4 on average, worst 92.6 of 16). Use the inverse (no margin) to show the score.
- If grabbing a frame pair is not possible, use model 1 with `MARGIN_DB = 0.8f`, but expect rare big misses (one dark, still, smooth slice landed at VMAF 83.8).

## Kotlin (paste-ready)

```kotlin
import kotlin.math.ln

/** Squish 1.1 quality map: SSIM dB (Quality.db of Squish's 8x8 box SSIM) <-> VMAF v0.6.1, per clip. */
object VmafMap {
    /** VMAF grid (rows). */
    val VM = floatArrayOf(85f, 88f, 91f, 93f, 95f, 97f, 98.5f)

    // ---- Model 2 (recommended): needDb = C0 + C1*noiseDb + C2*ln(motion) on each VMAF row ----
    private val C0 = floatArrayOf(8.8343f, 9.4923f, 10.5694f, 11.6479f, 13.0287f, 14.5559f, 16.7965f)
    private val C1 = floatArrayOf(0.3847f, 0.3857f, 0.3748f, 0.3599f, 0.3299f, 0.3091f, 0.2620f)
    private val C2 = floatArrayOf(-1.3186f, -1.4137f, -1.5502f, -1.7138f, -1.8431f, -2.0319f, -2.2124f)
    const val MARGIN_DB = 0.5f   // safety for encoding only

    // ---- Model 1 (noiseDb only): NEED_DB[row = VM][col = ND] ----
    val ND = floatArrayOf(6f, 9f, 12f, 15f, 18f, 21f, 24f)
    val NEED_DB = arrayOf(
        floatArrayOf( 7.95f,  9.10f, 10.26f, 11.42f, 12.57f, 13.73f, 14.89f), // VMAF 85
        floatArrayOf( 8.38f,  9.54f, 10.70f, 11.86f, 13.02f, 14.18f, 15.34f), // 88
        floatArrayOf( 9.06f, 10.19f, 11.32f, 12.44f, 13.57f, 14.70f, 15.83f), // 91
        floatArrayOf( 9.65f, 10.74f, 11.82f, 12.90f, 13.99f, 15.07f, 16.15f), // 93
        floatArrayOf(10.54f, 11.53f, 12.53f, 13.52f, 14.51f, 15.51f, 16.50f), // 95
        floatArrayOf(11.49f, 12.42f, 13.35f, 14.28f, 15.21f, 16.14f, 17.07f), // 97
        floatArrayOf(13.01f, 13.80f, 14.59f, 15.38f, 16.17f, 16.96f, 17.75f), // 98.5
    )
    const val MARGIN_DB_MODEL1 = 0.8f

    /** Model-2 column: SSIM dB needed for each VM row on this clip (non-decreasing). */
    private fun column(noiseDb: Float, motion: Float): FloatArray {
        val n = noiseDb.coerceIn(7f, 24f)          // calibrated range 7.2..23.8
        val lm = ln(motion.coerceIn(4f, 26f))      // calibrated range 4.0..25.4
        val c = FloatArray(VM.size) { C0[it] + C1[it] * n + C2[it] * lm }
        for (i in 1 until c.size) if (c[i] < c[i - 1]) c[i] = c[i - 1]
        return c
    }

    /** Model-1 column by linear interpolation over noiseDb. */
    private fun column1(noiseDb: Float): FloatArray {
        val n = noiseDb.coerceIn(ND.first(), ND.last())
        var j = 0
        while (j < ND.size - 2 && n > ND[j + 1]) j++
        val t = (n - ND[j]) / (ND[j + 1] - ND[j])
        return FloatArray(VM.size) { NEED_DB[it][j] + (NEED_DB[it][j + 1] - NEED_DB[it][j]) * t }
    }

    /** SSIM dB the encode must reach for [vmaf] (85..98.5); includes the safety margin. */
    fun needDb(vmaf: Float, noiseDb: Float, motion: Float): Float = lerp(VM, column(noiseDb, motion), vmaf) + MARGIN_DB
    fun needDb1(vmaf: Float, noiseDb: Float): Float = lerp(VM, column1(noiseDb), vmaf) + MARGIN_DB_MODEL1

    /** Estimated VMAF of a result from its measured SSIM dB (shown as the quality score). */
    fun estVmaf(db: Float, noiseDb: Float, motion: Float): Float = invert(column(noiseDb, motion), db)
    fun estVmaf1(db: Float, noiseDb: Float): Float = invert(column1(noiseDb), db)

    private fun invert(c: FloatArray, db: Float): Float {
        val n = c.size
        val v = when {
            db <= c[0] -> VM[0] + (db - c[0]) * (VM[1] - VM[0]) / maxOf(1e-3f, c[1] - c[0])
            db >= c[n - 1] -> VM[n - 1] + (db - c[n - 1]) * (VM[n - 1] - VM[n - 2]) / maxOf(1e-3f, c[n - 1] - c[n - 2])
            else -> lerp(c, VM, db)
        }
        return v.coerceIn(0f, 100f)
    }

    /** Piecewise-linear y(x); xs ascending; clamps outside. */
    private fun lerp(xs: FloatArray, ys: FloatArray, x: Float): Float {
        if (x <= xs[0]) return ys[0]
        for (i in 1 until xs.size) if (x <= xs[i]) {
            val d = xs[i] - xs[i - 1]
            return if (d <= 0f) ys[i] else ys[i - 1] + (ys[i] - ys[i - 1]) * (x - xs[i - 1]) / d
        }
        return ys.last()
    }
}
```

### How the app must measure the two features (must match the calibration)
- Frames: luma of the **source** at the compare size (`VideoQuality.compareSize`: short side ≤ 1080, /4 rounded), the same frames the probe/check already grabs (calibration used 8 frames spread over the slice; 3 for the 3 s slice).
- **noiseDb** = mean over the sampled frames of `Quality.ssim(Y, box3(Y))`, then `Quality.db(...)`. `box3` = 3×3 mean with edge clamp, rounded to a byte.
- **motion** = mean over the sampled frames of `mean(|box3(Y[t+1]) − box3(Y[t])|)` where `Y[t+1]` is the **next** frame (1/30 s later). For 50/60 fps sources take the frame 2 later (keep ~33 ms spacing); for 24/25 fps use the next frame. Units: 8-bit luma levels (calibrated range 4–25).
- **db** (encode check) = `Quality.db(mean Quality.ssim(src, out))` exactly as today; just stop feeding it to `Quality.score` and use `VmafMap.estVmaf` instead.
- Planning: `VideoQuality.Curve.planFor` should use `VmafMap.needDb(targetVmaf, noiseDb, motion)` as `tDb` (instead of `Quality.dbFor(target)`), with level targets e.g. Pristine 97 / No visible loss 95 / Balanced 91 / Compact 85.

## Per-slice results (leave-one-out: each slice is predicted by a model fitted on the other 15)

16 slices from Sajjad's 7 usable camera recordings (all 1080p30 H.264 ~20 Mbps, one phone; no other phone-camera mp4 exists on the PC except files in the Recycle Bin, which I did not use). Chosen by farthest-point spread over brightness and motion. Faces were not checked (I did not view frames, for privacy). "kbps@95" = bitrate this encoder needed for VMAF 95.

| slice | source (time) | start s | len s | luma | noiseDb | motion | kbps@95 | model 1: needDb → VMAF | model 2: needDb → VMAF | model 2 +0.5 dB → VMAF |
|---|---|---|---|---|---|---|---|---|---|---|
| S01 | 2025-05-06 00:14 | 16 | 8 | 23 (very dark) | 23.8 | 4.0 | 1 863 | 15.92 → **83.8** | 18.02 → 91.2 | 93.9 |
| S02 | 2025-04-29 22:03 | 64 | 8 | 117 | 20.4 | 23.5 | 1 963 | 15.45 → 99.1 | 13.99 → 96.1 | 97.9 |
| S03 | 2025-04-29 22:03 | 12 | 8 | 127 | 19.3 | 6.3 | 3 673 | 14.88 → 92.4 | 16.06 → 96.1 | 97.0 |
| S04 | 2025-05-06 00:14 | 8 | 8 | 68 | 16.4 | 14.7 | 2 841 | 14.02 → 96.2 | 13.48 → 94.6 | 96.1 |
| S05 | 2025-04-29 22:28 | 16 | 8 | 77 | 20.1 | 5.6 | 3 190 | 15.12 → 90.9 | 16.52 → 95.5 | 96.7 |
| S06 | 2025-05-29 23:06 | 40 | 8 | 108 | 16.0 | 15.6 | 5 050 | 13.88 → 97.3 | 13.22 → 94.8 | 96.7 |
| S07 | 2025-06-09 19:59 | 8 | 8 | 58 (dim, noisy) | 7.4 | 12.7 | 14 488 | 11.28 → 96.9 | 10.93 → 96.0 | 97.2 |
| S08 | 2025-04-29 22:03 | 28 | 8 | 82 | 21.0 | 12.6 | 1 853 | 15.58 → 97.7 | 15.34 → 96.8 | 98.6 |
| S09 | 2025-05-29 23:06 | 52 | 8 | 130 | 16.3 | 14.7 | 4 730 | 14.01 → 97.7 | 13.47 → 95.8 | 97.6 |
| S10 | 2025-04-29 22:28 | 8 | 8 | 78 | 19.9 | 6.3 | 3 134 | 15.07 → 91.7 | 16.25 → 95.6 | 96.9 |
| S11 | 2025-04-29 22:28 | 24 | 8 | 92 | 20.9 | 13.8 | 2 721 | 15.54 → 97.0 | 15.11 → 95.8 | 97.1 |
| S12 | 2025-05-29 23:06 | 12 | 8 | 128 | 13.3 | 8.9 | 5 883 | 12.96 → 94.6 | 13.44 → 96.0 | 97.0 |
| S13 | 2025-04-29 22:28 | 0 | 8 | 93 | 16.6 | 8.9 | 3 498 | 13.98 → 91.6 | 14.43 → 93.6 | 95.3 |
| S14 | 2025-06-09 19:59 | 0 | 8 | 58 (dim, noisy) | 7.2 | 7.9 | 14 136 | 10.27 → 91.2 | 11.29 → 93.6 | 94.5 |
| S15 | 2025-05-08 20:22 | 12 | 8 | 118 | 19.5 | 25.4 | 3 018 | 15.14 → 98.6 | 13.52 → 95.2 | 96.6 |
| S16 | 2025-05-26 12:46 (day) | 0 | 3 | 118 | 21.7 | 24.9 | 3 957 | 15.82 → 98.1 | 13.99 → **89.2** | **92.6** |

Summary at target VMAF 95 (LOO):
- Model 1 (noiseDb table): mean 94.67, mean |error| 3.26, worst 83.8, **6/16 below 93**; with +0.8 dB: mean 96.30, worst 83.8, 1/16 below 93.
- Model 2 (noise + motion): mean 94.73, mean |error| 1.37, worst 89.2, **2/16 below 93**; with +0.5 dB: mean 96.36, worst 92.6, **1/16 below 93**; with +0.3 dB: mean 95.72, worst 91.3, 2/16.
- Other targets, model 2, no margin: VMAF 93 → mean 92.69 (worst 87.3); VMAF 97 → mean 96.81 (worst 92.4, 1/16 below 93).
- Inverse (estimated VMAF from measured dB, LOO, 78 encodes with VMAF 80–99.5): model 2 bias −0.03, MAE 1.49, worst +6.8 / −4.3; model 1 MAE 2.67, worst +14.1 / −11.0.
- In-sample fit quality per VMAF row (model 1): rms 0.81–1.31 dB, max 2.2–2.8 dB.

## Method
1. Slices: 16 × 8 s (one 3 s daylight clip) cut from the 7 camera recordings, decoded to Y4M (limited range BT.709, like Squish's GL path), kept only in the scratchpad and deleted after use.
2. Encodes: `hevc_nvenc -preset p4 -tune hq -rc vbr -b:v R -maxrate 2R -bufsize 2R -bf 2 -g 300` (VBR, 2 B-frames, GOP 10 s), 7 rates 1.5–26 Mbps, extended automatically until each slice covers VMAF ≤85 to ≥98.7 (7–9 encodes per slice, 123 encodes total).
3. Per encode: VMAF v0.6.1 (libvmaf, every 3rd frame) and Squish's SSIM (exact re-implementation of `Quality.ssim`, 8×8 box windows, stride 4) on luma at the compare size, on every 30th frame; dB = −10·log10(1−SSIM).
4. Per slice: noiseDb and motion from the source as defined above.
5. Fit: for each VMAF row, the SSIM dB each slice needs (interpolated on its curve) was fitted by least squares: model 1 linear in noiseDb (sampled on the grid); model 2 linear in noiseDb and ln(motion). Rows are forced non-decreasing in VMAF. Errors are leave-one-out: each slice is predicted from the other 15, then its own curve gives the VMAF it would really reach at that dB.

## Limits
- One phone, 7 recordings, 16 slices; the dark-and-still case (S01) and the 3 s daylight clip (S16) are the worst misses. Add 10–20 clips from other phones/scenes before trusting the extremes.
- Encoder = NVENC proxy, not `c2.qti.hevc.encoder`. The SSIM↔VMAF link depends mostly on content, but Qualcomm artefacts may shift it a little; re-check 3–5 clips encoded on the phone when one is available.
- VMAF default (TV) model; the phone model would allow lower bitrates.
- Squish computes luma from RGBA with BT.601 weights after GL; here luma came from the Y plane. Expect a small offset (well under the 0.5 dB margin).

Scripts: `bench_cal.py` (encodes + metrics), `feat_cal.py` (motion/luma), `fit_cal.py` (model 1), `fit_cal2.py` (model 2); data `cal/results_w*.jsonl`, `cal/features.json`, `cal/table.json`, `cal/model2.json`.
