# Squish: how to compress much more with no visible loss

Date: 2026-10-18. Scope: on-device Android (Kotlin + MediaCodec + GL), target phone Poco X3 class (SD732G/860, `c2.qti.hevc.encoder`, no AV1 encoder).
Measured on this PC with ffmpeg 9.0 (libvmaf, NVENC on RTX 3070 Ti Laptop, Ryzen 9 6900HX). Three real slices from Sajjad's own phone videos (1080p30 H.264, ~20 Mbps): **A** bright indoor (10 s, portrait), **B** dark/low light (10 s, landscape), **C** daylight (3 s, portrait). Three real camera photos (9 MP JPEG). All private media was deleted after the test.

---

## TL;DR (ranked)

| # | Change | Saving at equal quality (measured) | Effort | APK | Licence | Risk |
|---|---|---|---|---|---|---|
| 1 | **Re-target the quality score.** Today "94" = VMAF ≈ 98.6 on bright/daylight clips and is *unreachable* on dark clips (the app keeps the original). Aim at VMAF ≈ 95 instead (still well under 1 JND from the source). | **−32 % (C), −52 % (A)** with the same encoder; dark clip: today 0 % (kept) → −7 % at VMAF 95, −22 % at VMAF 93 | M (new metric or re-calibrated map) | 0 KB (map) or +0.3–0.6 MB (libvmaf, est.) | own code / BSD-2-Clause-Patent | needs a blind check on the phone |
| 2 | **HEVC B-frames** (`KEY_MAX_B_FRAMES = 2`) | **−24 % avg** (−17…−32 %) at VMAF 93/95, all 3 clips | S | 0 | — | Qualcomm support unproven → runtime probe + fallback |
| 1+2 | both together | **A −64 %, C −53 %** vs today's output size | | | | |
| 3 | GOP 3 s → 10 s | −1…−2 % (10 s slices; more on long videos, est. −3…−6 %) | XS (one constant) | 0 | — | slower seeking only |
| 4 | Photos: lossless JPEG Huffman optimisation + progressive (jpegli later) | **−7…−11 %** at identical pixels; jpegli claims −35 % (not measured, tool missing) | M (small native lib) | +0.2–0.5 MB (est.) | BSD/IJG, jpegli BSD-3 | none (still JPEG) |
| 5 | SVT-AV1 software encoder | −15 % avg (p8), but 0 % on the dark clip; less than #2 | L | +2–4 MB/ABI (est.) | BSD-3-Clear + AOM patent | ~6–9 fps on phone; AV1 won't play on older iPhones; Telegram won't compress it |
| 6 | x265 software encoder | medium −4 %, slow −23 % (C only) | L | +2–3 MB/ABI (est.) | **GPL-2 → must publish Squish's source** | 0.5–1 fps on phone |
| 7 | Denoise prefilter (hqdn3d / nlmeans) | **worse**: +3…+12 % *more* bits at equal VMAF | M | 0 | — | phone ISP already denoises |
| 8 | Lower resolution (900p/720p) at "no visible loss" | +8 % worse (A, 900p), −8 % (soft daylight C, 720p) | — | 0 | — | keep only for Size mode |

**Why other apps look "much smaller":** they mostly drop to 720p/480p and use low bitrates. Telegram's own 720p cap (H.264, 2.6 Mbps, 1 s GOP, no B-frames) scores **VMAF 81 (A) and 67 (B)** here: 2–5 JND, clearly visible loss on a close look. Squish should beat them with #1 + #2, not by copying that.

---

## Part B. Measurements

### Method
- Reference = source slice decoded to Y4M, limited-range BT.709 (like Squish's GL → encoder path).
- Phone HW encoder proxy: `hevc_nvenc -preset p4 -tune hq -rc vbr -b:v R -maxrate 2R -bf 0 -g 90` (app-like: VBR, no B-frames, I-frame every 3 s, no look-ahead, no AQ). 5–6 bitrate points per method (2–24 Mbps); CRF sweeps for x265/SVT.
- Quality: libvmaf `vmaf_v0.6.1` (default TV model; the phone model scores higher, so this is conservative), `vmaf_v0.6.1neg` (no enhancement gain), SSIM, and **Squish's own score** re-implemented exactly (8×8 box SSIM, stride 4, full luma at ≤1080 short side, same dB→score table as `Quality.kt`).
- Bitrate at a target = log-interpolated between the two bracketing points. "Saving" = vs `nv_base` at the same target.
- Speeds are from the 6900HX / RTX 3070 Ti (some runs overlapped, so CPU speeds are ±30 %). Phone speeds are estimates (SD732G ≈ 1/6 of the 6900HX multi-core; x265 NEON is weaker than its x86 SIMD).

### 1. Squish's score vs VMAF (the biggest finding)

`nv_base` (today's settings):

| clip | Squish score at VMAF 95 | VMAF at Squish 94 | kbps at Squish 94 | kbps at VMAF 95 | kbps at VMAF 93 |
|---|---|---|---|---|---|
| A bright | 82 | 98.6 | 15 870 | 7 606 (**−52 %**) | 5 944 (−63 %) |
| B dark | 54 | not reachable (> 24 Mbps; app keeps original) | — (0 % saving) | 18 533 (−7 % vs 20 Mbps source) | 15 530 (−22 %) |
| C daylight | 89 | 98.6 | 8 335 | 5 689 (**−32 %**) | 4 772 (−43 %) |

- The SSIM dB needed for the same VMAF moves by ~4 dB between clips (≈ 2× bitrate). Noisy/dark footage is punished hardest: SSIM counts lost sensor noise as damage; VMAF (and the eye) much less.
- I tried SSIM on box-downscaled luma (×2, ×4) as a cheap fix: the spread stays ~4 dB, so downscaling does **not** fix it.
- A noise/texture feature of the *source* does track it: dB(SSIM(source, 3×3 box blur of source)) = 7.1 / 14.5 / 21.7 for B / A / C, and the SSIM dB needed for VMAF 95 = 10.3 / 13.7 / 14.9, monotonic. With 3 clips this is only a hint; it needs a 20–30 clip calibration (see P2). This "noise-aware target" is the new technique proposed here.
- Photos: Squish's photo score is close to VMAF (JPEG at Squish 94 ≈ q82–94, VMAF ≈ 94.5–96), so the photo side does not over-spend much.

### 2. Video encoder settings and tools (kbps at equal VMAF; saving vs nv_base)

VMAF = 95:

| method | A bright | B dark | C day | mean |
|---|---|---|---|---|
| nv_base (today) | 7 606 | 18 533 | 5 689 | 0 % |
| **+ B-frames (bf=2)** | 5 700 (−25 %) | 15 326 (−17 %) | 3 881 (−32 %) | **−25 %** |
| + GOP 10 s | 7 433 (−2 %) | 18 595 (0 %) | — | −1 % |
| + B-frames + GOP 10 s | 5 569 (−27 %) | 15 509 (−16 %) | 3 992 (−30 %) | −24 % |
| hqdn3d light → nvenc | 8 403 (+10 %) | 19 108 (+3 %) | — | +7 % (worse) |
| hqdn3d default → nvenc | (95 not reached at 10 Mbps) | 20 823 (+12 %) | — | worse |
| nlmeans light → nvenc | 7 964 (+5 %) | 18 708 (+1 %) | — | +3 % (worse) |
| hqdn3d light + bf2 + GOP10 | 6 084 (−20 %) | 16 028 (−14 %) | — | −17 % (less than bf2 alone) |
| 1600×900 + upscale | 8 200 (+8 %) | — | — | worse |
| 1280×720 + upscale | — | — | 5 238 (−8 %) | small, content-dependent |
| x265 veryfast | — | — | 5 463 (−4 %) | |
| x265 medium | 7 730 (+2 %) | (crf 13–17 = 50–73 Mbps!) | 5 154 (−9 %) | ≈ −4 % |
| x265 slow | — | — | 4 266 (−25 %) | |
| SVT-AV1 p8 | 5 809 (−24 %) | 18 511 (0 %) | 4 452 (−22 %) | −15 % |
| SVT-AV1 p10 | 7 685 (+1 %) | — | 4 680 (−18 %) | −8 % |

VMAF = 93 gives the same picture: B-frames −24 % (−21…−28 %), SVT p8 −16 %, x265 medium −5 %, 900p +3 % (A) / +41 % (B) worse, denoise +2…+10 % worse. At VMAF-NEG = 93 B-frames give −26 %, so the gain is not a VMAF "enhancement" trick. SSIM at the matched points is equal or higher with B-frames.

Telegram-like reference (VMAF reached at a given bitrate):

| | A bright | B dark | C day |
|---|---|---|---|
| H.264 720p @ 2.6 Mbps (Telegram 720p cap) | 81.4 | 67.3 | — |
| HEVC 1080p @ 2.6 Mbps | 83.1 | 67.6 | 80.3 |
| HEVC 1080p @ 6.8 Mbps (Telegram 1080p cap) | 94.2 | 81.5 | 96.9 |

Encode speed (this PC, 1080p): NVENC 50–110 fps; SVT-AV1 p10 ≈ 90 fps, p8 ≈ 55 fps; x265 veryfast ≈ 13 fps, medium ≈ 4–6 fps, slow ≈ 2–3 fps (dark clip: x265 medium 1.9 fps).
Phone estimate (SD732G): HW HEVC ≥ 120 fps; SVT-AV1 p8 ≈ 6–9 fps, p10 ≈ 12–15 fps; x265 medium ≈ 0.5–1 fps (a 1-minute clip ≈ 30–60 min).

Notes:
- Denoise loses because the phone ISP already denoises (source vs hqdn3d PSNR ≈ 45 dB on every clip) and VMAF measures against the noisy source. Any real gain would need human tests; there is no measured case for it.
- x265 CRF keeps grain (psy-rd): on the dark clip it spent 50–73 Mbps for VMAF 99.7–99.9. CRF without a cap is dangerous on night footage.

### 3. Photos (bytes at equal quality vs plain libjpeg-turbo, 3 camera photos 4000×2250)

Quality tools available: VMAF (luma), SSIM, PSNR-HVS, Squish score. **Missing (not installed, not downloaded): ssimulacra2, butteraugli, cjpegli/jpegli, mozjpeg cjpeg, avifenc, heif-enc.** So photo results are weaker than video results.

| codec | VMAF 95 (3 photos) | PSNR-HVS 42 (photo 1) | Squish 94 (photos 3, 4) |
|---|---|---|---|
| JPEG libjpeg-turbo (≈ Bitmap.compress JPEG) | 0 % (q≈80–90) | 0 % | 0 % |
| **JPEG + optimised Huffman + progressive** | **−8 %** (−7…−11 %) | −10 % | −7 % |
| WebP method 3 (≈ Bitmap.compress WEBP) | +7 % worse (−3…+20 %) | −11 % | −29 % |
| WebP method 6 | +1 % | −13 % | −30 % |
| AVIF (speed 6) | −3 % | −29 % | −45 % |
| JPEG XL (effort 7) | −11 % | −16 % | −40 % |
| HEVC intra (NVENC, HEIC proxy) | +3 % | +21 % worse | −40 % |

- Metrics disagree a lot on photos (VMAF saturates near 96 and is luma-only). Only "optimised Huffman + progressive" wins on every metric, and it is lossless (same pixels).
- The sources are camera JPEGs, so JPEG re-encodes line up with the original 8×8 blocks; this favours JPEG in all metrics.
- At VMAF 95 the JPEG output is already 40–59 % smaller than the camera file (8.5 → 4.6 MB, 5.9 → 2.4 MB, 4.3 → 2.6 MB).

---

## Part A. What others do (web research, with sources)

Labels: **[fact]** source code / official docs / measured study, **[vendor]** maker claim, **[gap]** no reliable source.

**Services and apps**
- Telegram Android [fact, source]: H.264 by default, HEVC only for Stories; `KEY_I_FRAME_INTERVAL = 1`; no B-frames; VBR; caps by short side 1080 → 6.8 Mbps, 720 → 2.6 Mbps, 480 → 1.0 Mbps. https://raw.githubusercontent.com/DrKLO/Telegram/master/TMessagesProj/src/main/java/org/telegram/messenger/MediaController.java , https://raw.githubusercontent.com/DrKLO/Telegram/master/TMessagesProj/src/main/java/org/telegram/messenger/video/MediaCodecVideoConvertor.java
- WhatsApp [fact, press]: 480p by default, 720p in HD mode. https://9to5mac.com/2023/08/24/whatsapp-hd-videos/
- Play Store compressors (Panda, LightCompressor-based apps) [vendor]: presets that cut resolution and bitrate, mostly H.264; LightCompressor is Telegram's code. https://compresto.app/blog/how-to-compress-video-on-android
- FreeConvert / Clideo / VideoSmaller [vendor/gap]: target size or quality slider, H.264/H.265, resize. No quality metric published.
- HandBrake default preset "Fast 1080p30" (x264; RF 22 per third-party guides). https://handbrake.fr/docs/en/1.8.0/technical/official-presets.html
- TinyPNG [vendor]: PNG → 8-bit palette quantisation + metadata strip. https://tinypng.com/ . Squoosh defaults: MozJPEG q75, WebP q75. https://zeroxfab1.onrender.com/tech/tools/cli/squoosh.html

**MediaCodec**
- `KEY_MAX_B_FRAMES` (API 29) is mapped by CCodec to a layer config; AOSP tested it only on `c2.android.avc.encoder` [fact]. https://gerrit.omnirom.org/plugins/gitiles/android_frameworks_av/+/aab2eea323292f18a0ea0d20eea174e7271fd77e%5E%21 . No public proof for `c2.qti.hevc.encoder` on SD732G/860 [gap]; an old Qualcomm Windows driver was buggy with 1 B-frame, fine with 0 or 2 [fact]. https://gitea.1f0.de/LAV/FFmpeg/commit/c52869f36e5902a89317d12d244432d41aff8107
- VBR quality floor (Android 12+) keeps VMAF ≥ ~70; irrelevant at our targets. https://developer.android.com/reference/android/media/MediaCodec
- CQ: Qualcomm's public HEVC rate-control list is CBR/VBR/MBR (no CQ) [fact]. https://dragonwingdocs.qualcomm.com/Technologies/Video/advanced-video-specifications
- Vendor keys: list at run time with `getSupportedVendorParameters()` (API 31); documented ones are initial QP and per-frame QP. https://docs.qualcomm.com/bundle/publicresource/80-56386-10/topics/mediacodec_extensions.md
- Media3 Transformer `experimentalSetEnableHighQualityTargeting` (VBR only). https://developer.android.com/reference/androidx/media3/transformer/VideoEncoderSettings.Builder

**Denoise**: Netflix AV1 film-grain synthesis −36 % at ≥1080p, ~−10 % below [fact]. https://ibc.org/content-management/news/netflix-rolls-out-av1-film-grain-synthesis-for-classic-movies/22018 . Up to −50 % on heavy grain. https://norkin.org/pdf/DCC_2018_AV1_film_grain.pdf . This needs real grain; Sajjad's phone clips are already clean (Part B).

**Software encoders / licences**: x265 GPL-2 or paid licence. https://x265.readthedocs.io/en/stable/introduction.html . SVT-AV1 BSD-3-Clause-Clear + AOM patent licence; Arm code 6× its C path [vendor]. https://aomedia.org/blog%20posts/Software-Implementation-Working-Group-Releases-SVT-AV1-v2_3 . ~2.5 fps at 1440×1080 on an 8-core ARM tablet [anecdote]. https://lists.cinelerra-gg.org/pipermail/cin/2023-December/007434.html . Kvazaar BSD-3, rav1e BSD-2, libaom BSD-2, dav1d BSD-2, libjxl/jpegli BSD-3, libvmaf BSD-2-Clause-Patent.

**Compatibility**
- AV1: Android 10+ software decode (libgav1), Android 14 requires AV1 decode, dav1d via Play updates [fact]. https://www.xda-developers.com/android-14-may-require-av1-decoding/ . iPhone hardware decode only from A17 Pro [fact]. https://bitmovin.com/blog/apple-av1-support . Telegram does not compress AV1 [user report]. https://bugs.telegram.org/c/64017
- HEVC: Android, iPhone, Telegram inline OK; Windows needs the $0.99 HEVC extension [fact]. https://support.microsoft.com/help/4494024 . Squish already outputs HEVC; B-frames do not change compatibility (HEVC Main allows them).
- HEIC: iPhone native; Windows needs the paid HEVC extension; many uploaders reject it. AVIF: Android 12+, modern browsers. JPEG XL: iOS 17+, Chrome only from 145 (flag) / 155. https://developer.chrome.com/blog/jpeg-xl-in-chrome . Only JPEG and WebP open everywhere.

**Photos**: jpegli "~35 % more compression at high quality", plain JPEG output, libjpeg API compatible [vendor]. https://opensource.googleblog.com/2024/04/introducing-jpegli-new-jpeg-coding-library.html . Android `Bitmap.compress(WEBP)` = Skia with method 3, no sharp_yuv [fact]. https://skia.googlesource.com/skia/+/refs/heads/main/src/encode/SkWebpEncoderImpl.cpp . Cloudinary (~300 images, SSIMULACRA2): WebP vs mozjpeg only 3–5 % smaller; AVIF vs WebP 10–15 % [fact]. https://cloudinary.com/blog/jpeg-xl-how-it-started-how-its-going . HeifWriter uses the HW HEVC encoder; v1.2 adds AvifWriter [fact]. https://developer.android.com/jetpack/androidx/releases/heifwriter

**"Visually lossless" thresholds**: VMAF 6 points ≈ 1 JND; ≥ 93 "indistinguishable or not annoying" [fact]. https://streaminglearningcenter.com/blogs/finding-the-just-noticeable-difference-with-netflix-vmaf.html . SSIMULACRA2 90 = cannot tell at 1:1. https://dyn.manpages.debian.org/trixie/libjxl-devtools/ssimulacra2.1 . Butteraugli < 1.0 not noticeable. https://docs.rs/butteraugli

---

## Implementation plan (top 3)

### P1. HEVC B-frames with a runtime probe (−24 %, small change)
1. `CodecKit.encoder()`: for `e.hevc && SDK_INT >= 29` add `KEY_PROFILE = HEVCProfileMain` and `KEY_MAX_B_FRAMES = 2` inside the existing `extras` block (the `catch` already retries without extras if the codec refuses the format).
2. Probe once per device (cache in Prefs, keyed by codec name + build fingerprint): encode ~1 s of the probe slice; B-frames are active if output `presentationTimeUs` is not monotonic. Also check that the probe decodes and its SSIM is not lower than the bf=0 probe at the same bitrate. If any check fails, use bf = 0 on this device.
3. Make the pipeline safe for reordered output: `MediaMuxer` handles B-frames (writes ctts); `pumpAudio(until)` should use the max PTS seen so far, not the current sample's PTS; `VideoQuality.firstPts()` alignment must use the smallest PTS, not the first sample.
4. Lower the HEVC bpp guess in `Planner` (0.065 → ~0.05) when bf is on, so the first probe lands nearer.
5. Test on the Poco X3: `ffprobe -show_frames` on the output (`pict_type=B`); play in Gallery, Telegram, WhatsApp, Windows Photos, iPhone.

### P2. Re-target "no visible loss" to VMAF ≈ 95 (−32…−52 %, the biggest win)
1. **Calibration set (PC, this setup):** 20–30 phone clips (day, night, indoor, motion, faces) encoded *on the phone* by Squish at 6 bitrates (a hidden debug switch that keeps probe outputs). On the PC compute VMAF (default + phone model) per encode; on the phone log Squish's SSIM dB and the source feature `noiseDb = dB(SSIM(src, box3(src)))` (one blur pass over frames that are already grabbed).
2. **Option A (no native code):** fit a 2-D table `dbFor(targetVmaf, noiseDb)` and use it in `Quality.dbFor/score`. Cost: a few ms per frame.
3. **Option B (most robust):** build libvmaf (BSD-2-Clause-Patent) with the NDK: integer VIF/ADM/motion only, built-in `vmaf_v0.6.1` model, arm64-v8a (+armv7). Est. +0.3–0.6 MB per ABI. Run it on the 8 check frames and the probe frames (est. 1–2 s per clip on A76 cores) and show real VMAF.
4. New level targets: Pristine = VMAF 97, **No visible loss = VMAF 95**, Balanced = 91, Compact = 85 (about 1 and 2 JND).
5. Before release: blind A/B on the phone's Compare screen with Sajjad: 5 clips × VMAF 93/95/97 vs original. Ship 95 only if he cannot pick the original.

### P3. Free extras
- `KEY_I_FRAME_INTERVAL` 3 → 10 (−1…−2 % measured on 10 s slices; more on long clips).
- Photos: add a tiny native JPEG step (libjpeg-turbo Huffman optimisation + progressive, lossless, −7…−11 %), then try jpegli (same API) and measure it with ssimulacra2 on the PC before switching. Keep WebP as an option, not the default: with Bitmap.compress (method 3) it was not smaller than JPEG at equal VMAF on camera photos.
- Do not add denoise, SVT-AV1, x265 or HEIC/AVIF/JXL now: no gain, or a gain smaller than P1/P2 with big APK, speed, licence or compatibility costs.

---

## Limits of this study
- NVENC is a proxy for the Qualcomm HW encoder. The Qualcomm encoder is likely weaker, and its B-frame support is unknown: P1 must be verified on the phone.
- Only 3 short slices from one phone; GOP gains are under-measured on 10 s slices.
- VMAF compares against the already-compressed 20 Mbps phone video, not the raw sensor image.
- Photo metrics without ssimulacra2/butteraugli are weak.

Files here: `bench_video.py`, `bench_photo.py`, `bench_metric.py`, `analyze.py`, `analysis.txt`, `results_*.jsonl`, `logs/` (metric numbers only; no media kept).
