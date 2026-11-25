# TRUE NAIN — Android Studio Source Candidate (20 Oct 2026)

## Final owner requirements (all are mandatory targets)

- Home menu visible first; **Video Library must be visible and usable**, with retained list and scroll position. Back: Player or Frame Mode -> Library -> Home -> Exit.
- VLC-inspired uncluttered Media Player, Frame Mode, Settings with persisted on-screen control show/hide preferences.
- Pinch zoom and one-finger pan move only video content; viewport/border/controls remain fixed; panning boundaries clamp. Maintain zoom on frame updates.
- Media Player and Frame Mode show source video/frame detail without unnecessary blur, black flashes or color corruption. At very slow speeds including 0.05x, output must remain clear and stable on device.
- Preserve original frame decoding, source timestamps, 24 MiB byte-bounded cache, stale-result protection, accurate +/-1 and +/-10 and long-press.
- Extract all frames to original-resolution PNG in a video-specific folder with progress and cancel, and preserve timestamp in filename.
- Trim/Merge, change view, fullscreen, source orientation, screenshot and other existing modes are retained.
- Android Studio project opens with Gradle 8.9, AGP 8.7.3, Java 17 language level; release signing key is intentionally not included.

## Files changed in this handoff

- `app/src/main/java/com/truenain/app/MainActivity.java`: persistent Library and navigation, control preference dialog and selected player buttons, zoomed-player drag-to-pan and bounded player translation, all-frames export option.
- `app/src/main/java/com/truenain/app/ZoomableImageView.java`: fixed drawing clip, clamp pan and zoom after pointer gestures and viewport size changes.

## Preserved source

- `ExactFrameDecoder.java`, `FrameSource.java`, `TrimActivity.java`, `TrimExporter.java`, `ExactTrimTranscoder.java`, `SharpFrameScorer.java`, `PlaybackMath.java` remain unmodified from the previous source candidate.

## Honest validation record

- Source ZIP packaging and archive CRC: checked locally.
- Android SDK and Gradle are **not available in this runtime**, so fresh Java compilation, `assembleDebug`, `assembleRelease`, APK signing and phone tests were **NOT RUN on this new revision**. The earlier successful build logs apply to the older ZIP, not this revision.
- Android `VideoView` often renders through a SurfaceView. Its actual clipping/transform behavior and correctness at 0.05x need **device confirmation**. `MediaPlayer.setPlaybackParams` may reject unsupported very low speeds. A guaranteed universal 0.05x player requires a dedicated frame presentation engine and explicit device tests; this handoff does **not** claim that engine is implemented.
- PNG extraction uses `FrameSource.getFrame(i)` sequentially on a worker and saves files through MediaStore. It may be slower than an optimized continuous export pipeline; do not switch video/source during export. Storage usage can be very large. If any frame fails decode/export, export stops with an error count rather than silently skipping frames.
- A permanent gallery/library of ALL device videos is not created; the Library lists videos the user explicitly selects via Android document picker, with persisted URI grants. It is **not** an automatic MediaStore video scan.
- Control customization includes player navigation, seek, frame shortcut and view toggles; frame toolbar custom preferences are listed but **not wired to every frame button**. There is no drag-and-drop control position editor.
- Neither visual playback quality nor complete display lock can be guaranteed until real device tests.

## Commands on user's Android Studio

```powershell
.\gradlew.bat :app:assembleDebug --stacktrace --console=plain
.\gradlew.bat :app:assembleRelease --stacktrace --console=plain
```

Never publish your keystore or signing password. Preserve the previous known-good signed APK.
