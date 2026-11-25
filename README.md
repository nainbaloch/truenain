# TRUE NAIN — Offline Video Player & Frame Inspector

**Package:** `com.truenain.app` · **Current release:** v2.4.7 (versionCode 247) · **Owner:** Zulqarnain Haider (True Nain, Lahore, PK)

TRUE NAIN is an offline Android video player and frame-accurate inspection tool for devices running **Android 10+** (minSdk 29, targetSdk 35). No internet permission, no ads, no analytics, no telemetry.

> 🔒 This repository is **PRIVATE**. Never commit `keystore.properties`, `*.jks`, `local.properties`, or any signing secret (all excluded in `.gitignore`).

## ✨ Features

- **Media Player** — playback 0.05x–2.0x (slow speeds are frame-decoded), ±5s double-tap seek, scrub with time pill, pinch zoom, Quality Look filters applied live through a GL shader
- **Frame Mode** — frame-by-frame inspection of exact source frames (MediaCodec decode), ±1/±10 stepping, hold-to-walk ±5, double-tap ±1, FPS selector 1..source fps, zoom with settled bicubic sharp crop
- **Frame Extract** — export all frames or custom ranges (`1,5,10,25-100`), Picture Rotate for orientation fixes
- **Quality Look** — Original / HDR+ / Cinematic / Vivid / Warm / Cool — GL shader live on video, bitmap pipeline on frames, baked into SAVE/Extract output
- **Fine Rotate (360)** — drag-to-rotate mode + arrow keys (±1°), in both Media Player and Frame Mode
- **Trim & Merge** — Samsung Video Editor hand-off first, built-in TrimActivity + MediaMuxer fallback
- **Zoom** — one ZoomController engine for both modes: pinch/drag/fling/wheel, settled bicubic sharp crop, pan bounded by content, rotation-aware

## Build

Requirements: Android SDK 35, JDK 17. Signing reads `keystore.properties` (gitignored) — must contain `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.

```bat
gradlew.bat :app:assembleRelease   :: signed release APK (R8 on) -> app\build\outputs\apk\release\
gradlew.bat :app:assembleDebug     :: readable debug build (no obfuscation) — release config untouched
```

**R8 mapping:** every release build writes `app\build\outputs\mapping\release\mapping.txt`; copies live in `docs/mapping/<version>/`. De-obfuscate crash traces with the SDK `retrace` tool: `retrace mapping.txt stacktrace.txt`. For a **readable build without obfuscation**, build `assembleDebug` (debug buildType is `minifyEnabled false` by default) — the release build config stays unchanged.

## Permissions

- `READ_MEDIA_VIDEO` — video library access
- `READ_EXTERNAL_STORAGE` (maxSdkVersion 32) — legacy devices

No internet permission. Saves/extracts go through MediaStore inserts (no storage-write permission needed).

## Project structure

```
app/src/main/java/com/truenain/app/
  MainActivity.java        All UI + orchestration: home, library, Media Player, Frame Mode,
                           Extractor, menus, gestures, zoom wiring, Quality Look picker,
                           keyframe-first seek + refine, trim hand-off.
  GradedVideoView.java     Dual-engine player view: GL shader engine (Quality Look live,
                           Catmull-Rom bicubic sampling when zoomed, glUseProgram-corrected
                           uniform pipeline) for ≤1440p; direct SurfaceView engine for
                           2K-4K with one-shot fallback; glResume/glPause + surface
                           re-attach lifecycle; fine rotation at view level.
  ZoomController.java      Shared zoom/pan engine (both modes): pinch, drag, fling, wheel,
                           double-tap cycle (disabled via flag — approved gestures kept),
                           rubber-band + spring-back, content-point tracking,
                           settled callback with generation guard, state save/restore.
  ZoomableImageView.java   Frame Mode drawing view: ZC-driven zoom/pan, rotation
                           (90-steps + fine angle) composed in the draw matrix,
                           FIT/FILL/1:1 via content regions, settled sharp-crop overlay.
  FrameSource.java         MediaExtractor/MediaCodec frame access: virtual timeline opens
                           instantly, real PTS index built on a background indexer.
  ExactFrameDecoder.java   Codec-backed exact frame decode (flush/reseek + drain per request).
  FrameCacheManager.java   Byte-budgeted LRU of decoded frames + direction-aware prefetch
                           executor (real frame-size budget) + generation guards + trimAll.
  FrameData.java           Cache entry (bitmap + PTS + index + rotation state).
  BitmapPool.java          Process-wide bitmap pool (no per-frame allocation churn).
  ColorGrade.java          Quality Look bitmap pipeline: denoise -> sharpen(zoom-aware)
                           -> colour + vignette. Same preset table as the GL shader.
  SharpScale.java          Catmull-Rom bicubic scaler (settled sharp crops).
  PlaybackMath.java        Timeline math (frame index <-> PTS, nearest index).
  SharpFrameScorer.java    Sharpness scoring for "Find Sharper Frame (nearby)".
  TrimActivity.java        Built-in trim UI (fallback when Samsung editor is absent).
  TrimExporter.java        Trim/merge export via MediaMuxer.
docs/
  ARCHITECTURE.md          Deep dive: activities, threads/executors, pipelines, cache,
                           shader, lifecycle.
  TESTING.md               Live test checklist + test video list (no video files committed).
  KNOWN_ISSUES.md          Current known bugs + status.
  DECISIONS.md             Design choices with reasons (from comments + git history).
  AUDIT_BUTTONS_FUNCTIONS_MAP.md  Button -> function -> prefs-key full map.
  mapping/2.4.6/mapping.txt       R8 mapping for the latest release build.
  rules/                   The v2.4.6 task pack (rules + audit findings + zoom guide).
CHANGELOG.md               Per-version history with APK SHA-256.
docs_working_status.json   Machine-readable working status.
```

## Release signing

`keystore.properties`-driven signing config (v2 + v3). Certificate SHA-256 (unchanged since v2.0.7):
`328c046496e1c22a93d014da6346eee2244b5889e8d5917442456b33f0a0138b`

**Never commit** `keystore.properties`, `*.jks`, `keys/`, or `local.properties` — all blocked by `.gitignore`.
