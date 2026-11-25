# TRUE NAIN — Architecture

Single-module Android app (`app`), pure framework APIs (no third-party libraries, no internet).

## Components

- **MainActivity** (single Activity, all UI) — home, library, Media Player, Frame Mode, Extractor screens are rebuilt inside one `modeContainer` FrameLayout. Modes: HOME / PLAYER / FRAME / LIBRARY / EXTRACTOR.
- **TrimActivity** — built-in trim UI fallback (Samsung Video Editor is attempted first via intents).
- **GradedVideoView** — the Media Player view: dual-engine GL/direct (below).
- **ZoomableImageView** — Frame Mode drawing surface (ZC-driven zoom/pan/rotation).
- **ZoomController** — shared zoom/pan gesture+state engine (both modes).
- **FrameSource / ExactFrameDecoder / FrameCacheManager / FrameData / BitmapPool** — frame pipeline.
- **ColorGrade / SharpScale** — bitmap Quality Look pipeline + bicubic scaler.
- **TrimExporter** — MediaMuxer-based trim/merge export.

## Threads and executors (MainActivity)

| Executor / thread | Purpose |
|---|---|
| `opener` | Opens videos (FrameSource.open) — never queued behind frame decodes |
| `decoder` (single thread) | `decodeLoop`: exact-frame decode via `exactFrameQueue` + `targetIndex` |
| `saver` | PNG save / screenshot work |
| `slowDecoder` | Slow-motion playback frame decode |
| `thumbnailWorkers` (2) | Library thumbnails |
| ZC `handler` | ZoomController settle task (300 ms) + fling task |
| FrameSource static `INDEXER` | Real PTS index walk (background, daemon) |
| FrameCacheManager `prefetchExecutor` | Direction-aware frame prefetch (dedicated FrameSource/MediaCodec) |
| `TN-Shutdown` thread | Background executor shutdown in onDestroy (v2.4.6 — no main-thread ANR) |

All executor shutdown now runs on the `TN-Shutdown` background thread in onDestroy (v2.4.6).

## Media Player pipeline

```
MainActivity.showPlayer
  └─ GradedVideoView (dual engine)
       ├─ GL engine (≤1440p): GLSurfaceView + SurfaceTexture <- MediaPlayer
       │    shader: external-OES sample (Catmull-Rom bicubic 16-tap when uZoom>1.3)
       │    uniforms: look preset (ColorGrade.params) + uZoom + texel + sharpen
       │    glUseProgram(program) bound in onDrawFrame  (v2.4.6 critical fix)
       │    letterbox viewport by video aspect; frame rotation at view level
       └─ DIRECT engine (>1440p or GL error, one-shot fallback): SurfaceView
  ZoomController(playerZc) owns pinch/drag/fling/wheel (fallback = old gestures)
  onTransform -> applyPlayerViewTransform() (scale/translate/rotate the view)
  onSettled -> refreshZoomOverlay(): full-res crop + bicubic overlay when paused+zoomed
  Seek: keyframe-first (SYNC) -> 350 ms CLOSEST refine (playerSeekGen guard)
```

## Frame Mode pipeline

```
FrameSource.open  (MediaExtractor: virtual timeline instantly; real PTS index in background)
  └─ requestFrame / requestFrameWalk -> decodeLoop (decoder executor)
       ├─ frameCache.get (LRU hit -> render without codec)
       └─ ExactFrameDecoder.getFrame (codec seek+drain; ImageReader surface)
            -> BitmapPool.obtain bitmap -> frameCache.put
       -> onFrameDecoded -> ColorGrade grade (look) -> viewer.setFrame
ZoomableImageView draws via ZoomController matrix + rotation composition;
settled (300 ms) -> getBitmapVisibleRect -> crop -> SharpScale bicubic -> sharp overlay.
```

## Frame cache

`FrameCacheManager` = byte-budgeted LruCache (budget from memoryClass, clamp 24–128 MB).
Entries keyed by `sourceId|frameIndex|rotationState`. Eviction releases to BitmapPool
(displayed bitmap pinned). Prefetch: direction-aware, dedup by `pendingPrefetch`, budget =
`memoryBudgetBytes / realFrameBytes` (primed from source width×height×4 in v2.4.6).
`trimAll()` on onTrimMemory(RUNNING_LOW).

## Quality Look

- **GL (video):** one shader does sampling (linear or bicubic-16-tap by uZoom) then
  brightness → contrast → saturation → warmth → shadow-lift → highlight-tame → vignette.
  Preset table = `ColorGrade.params()` (same as bitmap path). `glUseProgram` bound each frame (v2.4.6 fix).
- **Bitmap (frames + settled crops):** ColorGrade.grade = denoise (3×3 blend, zoom-scaled,
  OFF at zoom ≥ 2 by owner tuning) → unsharp (sharpenScale by owner tuning) → colour → vignette.
- **Original preset (0)** returns the source untouched in both pipelines.

## Lifecycle

- onResume → `glResume()` (GLSurfaceView.onResume + requestRender)
- onPause → playback pause + `glPause()`
- onSurfaceCreated → texture recreated + `reattachPlayerSurface` (player re-binds the new surface — prevents black-after-resume)
- onDestroy → releasePlayer + background `TN-Shutdown` (executors + thumbnailWorkers)
- onTrimMemory → frameCache.trimAll + (COMPLETE) BitmapPool.clear
- onSaveInstanceState → uri/position/mode (activity restore reopens the video)

## Signing

`keystore.properties` (gitignored) drives the signing config; APKs signed v2+v3 with the
v2 key + v3 lineage (rotation min SDK 29). Certificate SHA-256:
`328c046496e1c22a93d014da6346eee2244b5889e8d5917442456b33f0a0138b`.
