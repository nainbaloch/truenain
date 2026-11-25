# TRUE NAIN — independent source correction audit (21 Oct 2026)

Baseline: TRUE_NAIN_v1.4.1_SOURCE_ZOOM_LIBRARY_FIXES.zip. This copy leaves all existing decoder, trim/transcode and frame-analysis source files unchanged.

## Actual fixes this revision
1. All-frames export previously invoked `selected.getFrame(i)` on the UI's same FrameSource/decoder. Export now opens and closes its own `FrameSource` in the export worker, so frame stepping/navigation and export do not share that decoder. Export preserves timestamp-based PNG filenames, progress, cancellation and private output folder.
2. Saving control settings formerly called `showPlayer(false)` or `showFrame()` and rebuilt playback/frame UI. Saving now updates visibility in place (`refreshControls()`) so it does not deliberately reset a playing video, seek position or zoom.
3. Existing Player navigation/seek/frame/view and Frame zoom/FPS/view options are now attached to saved show/hide settings and can change visibility without player rebuilding. Rebuilt modes still read preferences.

## Evidence and limitations
- Android Studio project/config preserved: package `com.truenain.app`, versionCode 141, Gradle wrapper 8.9, AGP 8.7.3, 9 source Java classes.
- ZIP CRC/structure verified. NO Android SDK or Android Gradle build run in current environment. Earlier successful build evidence applies to earlier revision ONLY.
- No guarantee that VideoView surface crop/clip behavior is fixed on every device: needs real screen tests.
- 0.05x custom frame scheduling remains NOT IMPLEMENTED. Playback still delegates speed to MediaPlayer, which may reject 0.05x on some devices. Do not call it complete.
- Automatic scan of entire device media library remains NOT IMPLEMENTED. User-picked persisted URI library is available without broad storage permissions.
- Drag-and-drop control positions remain NOT IMPLEMENTED; only show/hide preferences.
- All-frame export still depends on accurate FrameSource sample PTS vs decoded output and requires testing CFR/B-frame/VFR; if decoder returns null it stops and reports partial completion. Very long exports require free storage and may run slowly.
- This is a corrected source CANDIDATE; not a 100% final verified app. Do not embed signing keystore or passwords.
