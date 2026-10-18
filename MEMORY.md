# Squish (code name Pinch) — project memory

Read this first, then `E:\CodeBase\My Apps\AGENTS.md` and `E:\CodeBase\My Apps\mistake book.md`.

## What it is
- Android photo + video compressor. Replaced the old Flutter "Squish Android" (sent to the Recycle Bin 2026-10-05). The Windows Tauri "Squish Compressor" folder is a different app; left alone.
- Launcher label "Squish Compressor" (both languages); in-app header "فشرده‌ساز اسکوییش" / "Squish Compressor" (user asked, 1.0.3). Launcher icon: pinched glass photo tile, cyan mountain + peach sun, circle first; 15 PNG layers in mipmap densities, 111720 bytes total, all alpha within 66 dp safe circle. Review images, master, prompts and export script: `Source Code/design`; Desktop copy: `Squish Launcher Icon`. In-app `Logo()` still draws the earlier glass drop/plates/chevrons; only launcher resources changed. The 1.0.3 APK carries this icon (pixel-checked 2026-10-08). Package stays `com.artin.pinch` so updates install over. Outputs go to `Pictures/Squish`, `Movies/Squish`, `<orig>-squish.<ext>`; gallery hides both Pinch and Squish folders.
- Package `com.artin.pinch`, minSdk 29, target/compile 36. Native Kotlin + Compose (ui, foundation, animation only; no Material, no icon lib, no media libs).

## Engine (app/src/main/java/com/artin/pinch/engine)
- Video: own MediaCodec pipeline. Hardware decode → `GlPipe` (EGL, box-filter downscale, PQ/HLG → SDR tone map) → hardware HEVC (falls back to H.264 HW, then software) → MediaMuxer. VBR, I-frame 3 s, `android._encoding-quality-level=0` to skip the Android 13 VBR floor.
- Audio is copied sample for sample. Only codecs MP4 can't hold (MP3/AC-3/Vorbis…) go through `AudioTranscoder` to AAC 160k/channel.
- Two modes (Pick screen segmented control): **Quality** (default) and **Size**.
- Quality: 4 levels → target score 97 / 94 / 88 / 80 (`Quality.TARGETS`). Score = SSIM on luma (8×8 windows, stride 4) → dB → piecewise map fitted to libvmaf on the user's real phone clip (x265, 2026-10-06). 94 ≈ no visible loss. Video compared at short side ≤ 1080; photos on 16 full-res tiles.
- Video curve (`VideoQuality.curve`): two probe encodes of three 2 s slices (20/50/80 %) at the guessed bitrates for 96 and 84; SSIM dB is ~linear in log2(bitrate) (fallback 1.9 dB per doubling). `planFor(target)` reads any level off the line, +6 % headroom; ≥ 88 % of source bitrate → keep original. Bitrate guess: 0.065 bpp HEVC (AVC ×1.45) at score 94. Muxer may restart the clock at 0: align by first-sample pts.
- Photo curve (`ImageCompressor.curve`): the 16-tile mosaic encoded at 9 settings (score kept rising, bytes scaled to the whole picture); `qFor(target)` interpolates. Run: one full encode, check, one +5 step if the whole falls > 1 point short.
- Every result (both modes) is checked against the source; the score shows on result rows, the Done card (size-weighted), Compare and History.
- Measuring starts when a file is ticked in the gallery (`Work.measure`), silently; one video worker + one photo worker in parallel; one curve per file (cached per codec/photo format, not per level), so turning the dial is instant. Video details are probed only when its turn comes; unticking or leaving the gallery cancels queued and in-flight work (safe against "select all"). Thumbnails show each file's own %; the run reuses the curve.
- Emulator has only software encoders (weak libavc): quality mode there often keeps videos as "already optimal". Judge video quality numbers on the phone.
- Size mode: dial strength = share of the **video stream** removed. `Planner` drops resolution (ladder 2160…240) and fps (>32 → ~30) only until bits-per-pixel ≥ 0.06 (HEVC) / 0.09 (AVC).
- Photos: WebP (default) or JPEG via `Bitmap.compress`. A ~0.64 MP proxy predicts size vs resolution/quality, so the full image is encoded 2–3 times to land under the byte target. EXIF date/camera kept; GPS only with "Keep location" + ACCESS_MEDIA_LOCATION. Max 25 MP decode.
- Output: MediaStore `Pictures/Pinch`, `Movies/Pinch`, `IS_PENDING` while writing, name `<orig>-pinch.<ext>`. Result ≥ 97 % of the original is dropped ("Already small").
- `CompressService`: foreground (dataSync, mediaProcessing on 35+), wake lock, progress notification with Cancel.

## UI
- No "freed so far" labels anywhere (user removed them). Lists (History, results) are plain rows with hairlines, not glass: glass is kept for controls and cards so levels differ.
- Emulator animation scales must be 1 (they were 0 once and every animation looked like a jump).
- Starts in Persian (RTL), English switch in Settings. Vazirmatn variable font (weights via axis). Own icons in `Icons.kt`.
- Light mode: page `#DCE6F5`, glass `#F4F8FF`; glass rims carry a navy outline (faint top, firm bottom); the bar has a navy shadow, not a blue glow (user: edges and the bar's bottom were hard to see).
- Look: blue liquid glass everywhere (user asked 2026-10-05; red/pink dropped). `BackdropHost` draws three soft blue pools that drift on tab/screen change; `Modifier.glass()` shows them through an AGSL rim lens (API 33+), `glassLite()` for list rows. `liquidPress` = swell, light at the finger, jelly release; used by every `press`.
- Tabs: Compress / History / Settings in a floating glass pill (`GlassScaffold`: scene + page re-drawn blurred and lensed inside it). A glass droplet marks the tab: lifts, stretches with speed, jelly-lands, magnifies the icon under it; drag the bar to scrub. Tab and screen changes slide + scale + blur ("melt"). Pages pad by `LocalBarSpace` (measured, not summed).
- In-app gallery sorted by largest first; files picker; share-in (SEND / SEND_MULTIPLE). Done screen: trash originals via `MediaStore.createTrashRequest` (API 30+). Compare screen: split slider + pinch zoom; videos compare the same frame.

## Build, sign
- AGP 8.13.2, Gradle 8.14.3, Kotlin 2.2.21, Compose BOM 2025.10.01, core 1.17.0 pinned (cache has it). JDK = Android Studio jbr.
- `./gradlew -I C:/Users/Sajjad/.gradle/mirror/aliyun.init.gradle --offline assembleRelease`
- Dex and .so packed compressed (`useLegacyPackaging`) → APK ~0.99 MB. ABIs arm64, armv7, x86_64.
- Keystore `Source Code/android/pinch-release.p12` (alias `pinch`) + `key.properties`, gitignored, never regenerate. SHA-1 `52:6B:8A:14:0E:A0:99:B3:44:51:0C:B0:75:12:2B:64:BE:6B:82:DF`.
- Emulator `Medium_Phone_API_37_1` = `emulator-5554` (1080x2400, 440 dpi). Test media generator: ffmpeg lavfi testsrc2 + noise.

## State
- 2026-10-18: 1.0.4 / versionCode 5, `Executables/Squish-1.0.4.apk` (1.15 MB). Fixes: gallery sort/filter change starts at the top (grid wrapped in `key(filter, largest)` with a fresh `rememberLazyGridState`), optimal badge = check mark, quality mode shows «≈ …» until all files are measured, Done summary card scrolls with results, Persian button «فشرده کن (n)». Public repo github.com/Sajjad-Majdi/Squish-Android (tag v1.0.4), APK also in Sajjad-Apps release `squish-android-v1.0.4` (portfolio links to it). Sent to Myket for review 2026-10-18 (free, category عکس و فیلم, title «اسکوییش: کم‌حجم کردن عکس و ویدیو»); store images + icon in `Advertisment Media/` (made by scratchpad promo.py from emulator shots + AI sample photos).
- 2026-10-18: 1.1.0 TEST build (branch `v1.1-test`, `Executables/Squish-1.1.0-test.apk`, versionCode 6), sent to Sajjad for a phone test; not on Myket, main untouched. `BFrames.kt` (probe per encoder+build, fallback after 2 broken probes, broken full encode redone without), `VideoScore.kt` (VMAF from SSIM dB + source noise + motion, fit in `docs/vmaf-calibration-2026-10-18.md`, +0.5 dB margin), second probe near level 95, GOP 10 s, TARGETS 97/95/88/80. Emulator: ~51% smaller than source at «بدون افت», B-frames present, audio/rotation/60 fps kept. Lesson: MediaMuxer(path) does not truncate an existing file; delete a reused temp file first or the read-back fails.
- 2026-10-19: 1.1.1 TEST (versionCode 7, `Executables/Squish-1.1.1-test.apk`, branch `v1.1-test` 57605e9): size mode keeps full resolution (bpp floor 0.06 -> 0.015 HEVC; 20 % study: full-res beat 900p/720p/540p at equal bytes, screen recordings at 720p fell to VMAF 80), presets 50/75/«۲۰٪ حجم»/90/98. Sajjad tested it on his phone, 2026-10-19 ~02:00: «تست کردم. اوکیه، لازم نیست.» (emulator check cancelled). Not merged to main, not on Myket yet.
- Next (1.1): `Source Code/docs/compression-research-2026-10-18.md`: the "94" target ≈ VMAF 98.6, so re-target to VMAF ≈ 95 (-32..-52 %) and enable HEVC B-frames with a device probe (-24 %); both need real-phone checks and a blind A/B by Sajjad.
- 1.0.3 / versionCode 4, `Executables/Squish-1.0.3.apk`, 2026-10-06. On the phone: HEVC hardware (c2.qti) works, audio copied untouched. Emulator: 90% preset → 65.9 MB to 7 MB.
- Emulator has only software HEVC (small max size), so videos there come out H.264; phones with HW HEVC get HEVC.
