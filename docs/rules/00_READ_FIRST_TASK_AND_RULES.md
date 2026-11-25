# TRUE NAIN v2.4.5 -> v2.4.6 : TASK FOR CODING AGENT

You are working on the Android app TRUE NAIN (package com.truenain.app), an offline video player and frame inspector.
Read this whole file first. Then read 01_AUDIT_FINDINGS_v2.4.5.md and 02_ZOOM_INTEGRATION.md.
Build 3 phases, in this exact order. Do not stop between phases (the owner tests in the morning).
After each phase save a separate APK:
  TRUE_NAIN_v2.4.6_phase1.apk
  TRUE_NAIN_v2.4.6_phase2.apk
  TRUE_NAIN_v2.4.6_phase3.apk
Each phase builds on top of the previous one, and each APK must install over v2.4.5.
If a phase fails to build, stop there, report the exact error, and do not start the next phase.

## GLOBAL RULES (must follow)
1. Audit before modification. Read the complete existing source first. Change only what each phase needs.
   Do not rewrite or redesign anything else. Do not assume a subsystem must be replaced.
2. Preserve every existing feature and approved UI: Media Player, Frame mode, Frame Extract, Custom Selection,
   Picture Rotate, Fine Rotate, Change View, Trim and Merge, export, Quality Look presets, locks, resume position.
   Do not remove or redesign UI without the owner's permission.
3. Keep package name com.truenain.app and the current signing certificate.
   Certificate SHA-256 must stay: 328c046496e1c22a93d014da6346eee2244b5889e8d5917442456b33f0a0138b
4. Do not overwrite the v2.4.5 APK or source. Keep a backup copy of the source before you start.
5. No new permissions. No internet. App stays 100% offline.
6. Keep memory bounded. Never keep unlimited frames or bitmaps. Catch OutOfMemoryError and fall back to a smaller
   version instead of crashing, especially on 4K video.
7. Never block the main thread. No waiting for executors in onDestroy on the main thread.
8. Do not change the owner's data or settings logic without need. Do not touch anything outside the task.
9. Be honest in the final report. Label every item as CODE REVIEWED, BUILD VERIFIED or DEVICE VERIFIED.
   Never claim a device test that was not performed. A successful build alone is not a performance improvement.
10. Work in small, safe steps. If something is unclear, choose the safest option and write the decision in the report.

## PHASE 1: ZOOM IN / OUT (Media Player AND Frame mode)
Goal: one identical, smooth, advanced zoom in both modes.
- Add src/ZoomController.java (given in this pack) and use it in BOTH Media Player and Frame mode.
  Remove or disable the old zoom/pan gesture code so two zoom systems never fight each other.
  Keep old single-finger gestures (seek swipe etc.) working when not zoomed, by using setFallback().
- Zoom in/out buttons call zoomIn()/zoomOut(). "Reset Zoom / Pan" calls reset(true). Pinch, double tap, drag, fling must work.
- Frame mode (paused frame): on onSettled(), decode the frame at FULL source resolution and crop only the
  visible rect, then scale it with bicubic or Lanczos (not bilinear). Never downscale first and upscale after.
  Show the sharp crop only if zc.isCurrent(generation) is still true. Replace it back to fast path when the user moves again.
- Media Player: fast path while moving or playing; about 300 ms after the user stops or pauses, replace with the
  high quality version. Cancel older refine requests when a new interaction starts.
- Zoom must survive rotation, Fine Rotate, Picture Rotate and video change (use setContent() with the rotated size
  and saveState()/restoreState() in onSaveInstanceState).
- Pan must never leave the video edges. Reset must work when switching video.
- Deliver: changed files list + short note of what was removed.

## PHASE 2: QUALITY LOOK (filter) in Media Player AND Frame mode
Goal: cleaner, more professional zoomed image. The owner added filters because zoomed video looks dirty.
- Use the same Quality Look pipeline and the same preset result in both modes.
- Fix the processing order: light denoise / deblock first, then light sharpen, then colour adjustments.
  Do not sharpen raw compression noise (current sharpen amplifies blocks).
- Sharpen strength must depend on zoom level: less sharpen and slightly more denoise at high zoom.
- Scaling uses bicubic or Lanczos in the shader (not plain linear) when zoomed.
- Original / no filter must remain exactly the original pixels.
- GLSurfaceView lifecycle: onResume must be called when the activity returns (home button, back, screen off/on).
  The view must never stay black or frozen. Check rotation too.
- Saved frame / screenshot must match what is on screen, and the label must say which look was used.
- Optional, only if time allows and only behind a button and clearly labelled "Enhanced": multi-frame
  denoise on a paused frame using 3 to 5 neighbouring frames. Do not add any model or library in this phase.
- Deliver: changed files list + what each preset does now.

## PHASE 3: SEEK / CRASH / MEMORY
Problems seen by the owner: jumping far (for example 1 minute to 6 minutes) hangs the app; 4K can crash.
- While the user drags or jumps, show the nearest keyframe quickly (closest-sync seek). After the user stops,
  refine to the exact frame.
- Every new seek cancels older ones. No old frame may appear after a newer seek (use the generation counter idea).
- onDestroy: do not wait on executors on the main thread (currently awaitTermination up to 3 s per executor, 4 executors).
  Shut them down on a background thread.
- Add onTrimMemory: when memory is low, clear half or all of the frame cache. Also release on onStop if needed.
- Prefetch window = cache budget / real frame size (width x height x 4). The current code assumes a 1080p frame
  (8,294,400 bytes) and keeps 16 to 50 frames, which thrashes the cache on 4K.
- Deliver: changed files list + the new prefetch formula.

## FINAL REPORT (after phase 3)
- For each phase: APK path, SHA-256, versionName, versionCode (use 246 for v2.4.6 and keep the three phase builds
  clearly named), signing certificate fingerprint.
- All modified files, known limitations, unverified features.
- A table-free plain list: CODE REVIEWED / BUILD VERIFIED / DEVICE VERIFIED.
- Anything you could not do, and why.
