# TRUE NAIN – updated source (2026-10-21)

Base: TRUE_NAIN_COMPLETE_SOURCE_HANDOFF_2026-10-21.zip (source content equivalent to earlier Drive handoff), patched with the three Android Lint fixes verified by the user on Windows.

## Implemented source changes
- Frame Extractor added as a distinct Home-menu destination; video selection remains through Video Library.
- Frame selection: all frames or custom indexes/ranges (1-based, comma-separated, inclusive) with bounds checks.
- JPEG (quality 94, smaller/faster encode) or PNG output, original bitmap resolution, existing independent decoder, cancel, progress, failure report.
- Video Library retains MediaStore scanning and adds asynchronous, scaled video thumbnails, memory cache, scrolling and 2-line labels.
- Media Player gets visible side-mounted Trim shortcut, preserving existing TrimActivity; side-mounted portrait/landscape toggle.
- Home menu gets extractor entry and larger title. Video Library is NOT removed.
- Restores Lint fixes in ExactTrimTranscoder.java, TrimExporter.java and TrimActivity.java using UTF-8 without BOM.

## Limits / honest status
- NOT COMPILED in this container: Android SDK and Gradle distribution unavailable. Re-run Windows Android Studio :app:assembleDebug :app:assembleRelease :app:lint. Do not call this a production-tested release.
- Source extraction still uses random-access ExactFrameDecoder and MediaStore for EACH image. JPEG reduces encoding size/cost but end-to-end throughput improvement is NOT measured. Sequential decoder optimization is still needed for some long videos.
- Source sample count may not equal all presentation frames on some unusual codecs. VFR/B-frame parity is NOT proven by tests.
- Device orientation toggle is a compact fixed-position button and changes screen orientation, not pixels.
- UI redesign is incremental; there is no full visual/device accessibility QA. Full freedom dragging of buttons is not implemented.
- Actual rotated/zoomed viewport behavior, trim workflow and media-permission behavior require device checks.
- If video thumbnails are unavailable or denied, thumbnail placeholder remains.
- Original signing keystore/passwords are NOT included.

## Build command (Windows PowerShell from project directory)
` .\gradlew.bat clean :app:compileDebugJavaWithJavac :app:assembleDebug :app:assembleRelease :app:lint --console=plain `

Build successful on user's PC applied only to PREVIOUS source before these new changes; do NOT transfer that certification to this ZIP.
