# TRUE NAIN — Advanced Implementation Update (source only)

## Source
Reconstructed from the newer `TRUE_NAIN_v1.4.0_FINAL_LOCKED_COMPLETE` project in connected Google Drive. Existing user source was not modified. Core Java files, Gradle wrapper and JAR were obtained from that project; the same 537-byte `styles.xml` was copied from the conversation-supplied v1.4.0 source ZIP (Drive copy also reports 537 bytes). The resulting folder is a new, separate Android Studio source project.

## Implemented changes
- `ExactFrameDecoder.java`: bounded 24 MiB recent-original-frame cache with bitmap ownership separated from caller; backward/repeated stepping can reuse recently decoded frames without restarting the codec. Large bitmaps are not cached. Existing exact PTS pipeline retained.
- `ExactFrameDecoder.java`: YUV full/limited-range and BT.601/BT.709/BT.2020 integer conversion selected from source metadata when present; unknown defaults to prior BT.601 video range. Actual display color must be device-tested.
- `MainActivity.java`: drop obsolete, superseded asynchronous seek frames; do not display frames from an older video source; safely recycle abandoned bitmaps and completed frame-save bitmaps; tie player screenshot to its original source.
- `MainActivity.java`: menu action `Find Sharper Frame (nearby)` added to BOTH player and frame tools. Scores up to nine ORIGINAL source frames in a +/-1 second search by edge energy and navigates to the best candidate. It is a sharpness heuristic, not recovered detail, not object recognition and NOT a guarantee of legibility. During search navigation/decoding may be delayed.
- `ZoomableImageView.java`: disable smoothing on 1:1 or magnified viewing, retain bilinear filtering for downsampling, preserving actual pixel values at inspection zoom.
- `SharpFrameScorer.java`: separate bounded image-sharpness heuristic.

## Kept unchanged
- v1.3-style two-mode startup (Media Player and Frame Inspector), picker-cancel behavior, source orientation, fullscreen/change-view controls, FPS and stepping semantics, 30-second auto-hide, offline security posture and trim/merge modules.
- No new permissions, network components, analytics or logging services.
- Original source project and private signing keys are untouched.

## Not yet implemented or verified
- This is **not** a finished, device-tested full-advanced release. No device video playback or codec compatibility tests were performed.
- UI-level ROI tracking, original/enhanced comparisons, automatic stabilization and adaptive low-speed frame player fallback are not implemented by this update. Other advanced features in the existing project remain as they were.
- Source-code static checks and a standalone Java unit test of `PlaybackMath` and `SharpFrameScorer` passed (using a synthetic Bitmap stub). This is **not** an Android Gradle build test.
- The environment has no installed Android SDK/Gradle distribution or network access, so assembleDebug/assembleRelease and APK signing were not verified. Do not represent this as a tested APK.

## Build on Windows
Open the `TRUE_NAIN_FINAL_LOCKED` folder in Android Studio. With your installed SDK/JDK/Gradle and local private keystore config, run Gradle sync, then `gradlew.bat :app:assembleDebug` and/or `gradlew.bat :app:assembleRelease`. Keep signing files outside of shared ZIPs.

## SHA-256
See `SOURCE_SHA256.txt` for file integrity checks.
