# TRUE NAIN Source — transparent handoff

Based on v1.4.1 UPDATED_SOURCE_CANDIDATE and retaining its existing decoder/Trim code.

## New changes
- Apply saved Frame FPS/Zoom/View visibility toggles when constructing frame toolbar.
- Re-anchor player gesture pan when second finger lifts to reduce viewport jumps.
- Re-anchor Frame Mode zoom gesture when second finger lifts.
- Back exits fullscreen before returning to Library.

## Features carried forward from candidate
- Main Menu; manually selected video Library; player and frame modes.
- Persisted control visibility settings; fixed viewport clipping and bounded zoom/pan.
- PNG Extract All Frames with progress/cancel.
- ExactFrameDecoder, 24 MiB caching and Trim/Merge source classes.

## Not fully implemented / not verified
- Ultra-slow 0.05x relies on device MediaPlayer; no custom low-speed presentation engine.
- Arbitrary drag-and-drop control repositioning not implemented.
- Library requires the user to add videos; no automatic device-wide video indexing.
- Player's actual SurfaceView/VideoView zoom behavior requires on-device testing.
- No Android SDK in this environment; this revision has NOT been Gradle compiled or APK tested.
- Full frame-accuracy/quality tests not run. Source file presence is not proof of playback correctness.

Build the extracted TRUE_NAIN_FINAL_LOCKED directory using `gradlew.bat :app:assembleDebug`.
Do not share release keystore, signing passwords, or private key. The ZIP has none.
