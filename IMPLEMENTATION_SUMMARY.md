# TRUE NAIN — implementation summary

- Two-mode v1.3-style startup and SAF picker; picker cancellation retains the current video and mode.
- Source orientation, separate fullscreen and Change View, player controls/gestures, specified speeds, and same-video mode switching.
- Frame +/-1/±10 play-state preservation, held stepping at selected cadence, frame save, zoom, and visibility controls.
- Frame decoding uses MediaExtractor + MediaCodec with exact indexed source PTS matching; no MediaMetadataRetriever frame stepping and no synthetic/interpolated frame timestamps.
- Multiple trim ranges can be previewed, removed, reordered, and merged to a new output.
- Trim uses stream-copy when requested video boundaries are safe sync boundaries; otherwise an exact-boundary MediaCodec video re-encode fallback is used rather than moving the start to a later keyframe.
- Offline manifest with no declared permissions, allowBackup=false, minSdk 29, package com.truenain.app, release debuggable=false and R8/resource shrinking enabled.
- Clean source excludes private signing material, local.properties, build outputs and machine caches.

Verification note: static/config checks completed for this updated source. The current execution environment could not fetch Gradle 9.6.0, so a fresh release build of these final decoder/transcoder changes is not claimed. Device codec regression testing is also still unverified.
