# TRUE NAIN — Design Decisions

Every entry is sourced from code comments, the git history, or owner recordings/messages. Nothing invented.

## Rendering

- **Dual-engine Media Player (GL ≤1440p, direct SurfaceView for 2K-4K).** Comment: "GL pipeline 2K/4K par S9 par stuck/error deta hai — badi videos direct" (`GL_MAX_PIXELS`, `fallbackToDirect`). Reason: hardware decode on the S9 was proven stable on the plain SurfaceView path, while the GL path with large videos got stuck/errored.
- **`glUseProgram(program)` bound in onDrawFrame (added by the external session, kept).** Without it every glUniform call in onDrawFrame was a no-op — the whole Quality Look GL pipeline never applied. Found via the external session's audit of the v2.4.6 build; the owner then confirmed video rendering became visible even on the emulator.
- **Highp + clamped bicubic UVs + zoom-aware shader sharpen (external session, owner tuning).** Bicubic taps clamped to valid UV range (edge artifacts); shader sharpen floor 0.30 at uZoom > 1.5. Reason: cleaner zoomed image per owner request.
- **ColorGrade tuning: denoise OFF at zoom ≥ 2, sharpenScale increases with zoom.** Owner's chosen behaviour for crisp zoomed frames (inverts the pack's original "less sharpen at high zoom" spec — recorded in the external session's transcript).
- **SharpScale = Catmull-Rom bicubic.** Pack requirement: "scale with bicubic or Lanczos (not bilinear). Never downscale first and upscale after."

## Zoom

- **One ZoomController engine for both modes; old pinch/pan/wheel code removed.** Pack phase 1: "two zoom systems must not fight each other."
- **`setDoubleTapZoomEnabled(false)` flag added (documented deviation).** Double-tap was already an approved feature (Player = ±5 s seek, Frame = ±1 frame step); the pack rule 10 says choose the safest option and record it.
- **Fallback pattern (`setFallback`)** keeps approved single-finger gestures (scrub, ±5 s double-tap zones, brightness/volume) working when not zoomed — per pack guide.
- **Frame Mode FIT/FILL/1:1 via ZC content regions.** The ZC base only fits; modes are implemented by feeding the displayed region as ZC content (FIT = whole rotated image, FILL = cover crop, 1:1 = view-sized window).
- **Rotation composed in the draw matrix (bitmap → rotate about center → bbox → region → ZC).** v2.4.6 fix: the pre/post concatenation order was inverted, which produced wrong draw/visible-rect mapping at non-zero rotation and FILL/1:1.

## Interaction

- **↻ rotate resets zoom/pan.** Owner recording (r_05) showed rotate + persisted zoom producing an upside-down close-up ("zoom ajeeb"). Reset gives a clean fit after every rotation.
- **Frame Mode double-tap ±1 / hold ±5 (owner request, v2.4.4).** Handled through the ZoomableImageView fallback; ZC double-tap zoom disabled.
- **±10/±5 instant movement: `requestFrameWalk`.** Owner: "10 frame par click karo to frame move hone chahiye, ruk kar phir agay jata hai." Intermediate frames walk through the decode queue and display; far jumps still seek directly.
- **360 button uses the text label `360` instead of the ⟲ glyph.** The glyph rendered blank on Samsung fonts (owner: "360 nahi dikhta").

## Seek / Memory

- **Keyframe-first seek + 350 ms CLOSEST refine with a generation guard.** Owner saw far jumps hang (audit finding 4: seeks mostly SEEK_CLOSEST on long-GOP 4K). Every new seek cancels older refines.
- **onDestroy shutdown on a background thread.** External audit finding 1: awaitTermination(3 s) × 4 executors on the main thread = ~12 s ANR risk.
- **onTrimMemory → cache trimAll + BitmapPool.clear.** External audit finding 2: no memory-pressure handler existed.
- **Prefetch budget seeded with the REAL frame size (`primeFrameSize`).** External audit finding 3: the window assumed a 1080p frame and thrashed on 4K. (The `window` field itself is final — the byte budget is what changed.)

## Git / delivery

- **Single FINAL APK per round instead of phase APKs.** Owner: "final 1 apk banani hai" after initially ordering the pack's phase-APK scheme.
- **Secrets never in git.** `.gitignore` (pre-existing from the security-audit round) excludes keystore.properties, *.jks, *.apk, local.properties; verified via git log/ls-files that no secret value ever entered history (only `keystore.properties.example` and property-getter references).
- **Debug logging (`TN360`) kept in release builds** — tiny, off-screen, and enables remote diagnostics of the 360 toggle after owner reports.
