# Squish launcher icon

Selected concept: **pinched glass media tile**. The intact mountain and peach sun make the media cue clear. The compressed waist provides one strong silhouette. The blue glass fits the app's existing theme. Circle is the primary mask.

Explored directions, left to right in `concepts.png`:
1. Pinched glass tile — selected for the clearest compression cue and strongest small-size silhouette.
2. Merging media tiles — the smaller output is clear, but overlapping frames add detail at 48 px.
3. Shrinking lens — elegant glass, but the large lens competes with the tiny media tile.

## Deliverables
- Five Android densities: 108, 162, 216, 324 and 432 px.
- Each density contains transparent foreground, opaque background and white monochrome PNGs.
- `play-store-512.png`: full-bleed square, opaque 512 × 512 PNG. Play applies its own outer mask.
- `final-circle-512.png`: convenient circular review image, with transparent corners.
- `mask-preview-1024.png`: circle, squircle and rounded-square masks, at 224 px and native 48 px, on tinted light/dark backgrounds; themed icon samples included.
- `foreground-master.png`: original transparent generated artwork.
- `prompts.json`: the two exact prompts used with built-in image_gen.
- `verification.json`: per-file sizes, SHA-256, alpha and safe-zone checks.

## Validation
Launcher PNGs together: **111720 bytes**, below the strict 150000-byte budget. All visible foreground and monochrome pixels fit inside the central 66 dp circle of the 108 dp canvas. Outer launcher viewport is 72 dp. PNGs have no visible pure white or black, except the requested white monochrome mask.

`ic_launcher.xml` now references all three mipmap layers. The three old launcher drawable XML files were removed after reference checks. No other app source file changed. Project memory was updated. The existing APK was not rebuilt.

Resource compilation passed with Android SDK build-tools 36.0.0:
```powershell
aapt2.exe compile --dir app/src/main/res -o design/resources-check.zip
```
This is resource validation, not a device installation or a full APK build.

The export script uses Node.js and sharp. Set `NODE_PATH` to the installed sharp package directory, then run `node design/package-icon.cjs`. Raster resizing, alpha placement, density export, monochrome reduction and PNG optimization are deterministic. The visual artwork was created with the built-in image generator.

Reference specifications: [Android adaptive icons](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive), [Google Play icons](https://developer.android.com/distribute/google-play/resources/icon-design-specifications).
