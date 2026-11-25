# TRUE NAIN — FINAL LOCKED REQUIREMENTS

Status: LOCKED. Current chat decisions override older mockups/README text.

## 1. Startup
- v1.3-style startup, not a library-first home.
- Home shows exactly two modes: **Media Player** and **Frame Mode**.
- Tap a mode → system video picker → selected video opens in that same mode.
- Opening a new video from inside Player/Frame is allowed through `⋮`.
- If the picker is cancelled/backed out, keep the same currently open video and mode.

## 2. Source orientation / view / fullscreen
- Source orientation first: portrait source → portrait; landscape source → landscape.
- **Change View** is an explicit manual control.
- **Fullscreen** is separate and never forces orientation or changes playback state.
- User can change the view after opening the video.

## 3. Media Player
- Video-first dark graphite/navy UI with compact controls and no decorative clutter.
- Direct: timeline, current/duration, -5s/+5s, Previous/Next session video, Play/Pause, Frame, Trim, Speed, Change View, Fullscreen.
- Horizontal finger drag = scrub/seek with time feedback.
- Double tap left/right = -/+5 seconds.
- Long press while playing = temporary 0.25× slow preview; release restores selected speed.
- Fullscreen vertical swipe: left brightness / right volume.
- Single tap = hide/show controls.
- Auto-hide = **30 seconds** inactivity.
- Any active tap/drag/seek/menu interaction resets or suspends auto-hide.
- Speeds exactly: 0.05×, 0.10×, 0.25×, 0.50×, 1.00×, 1.50×, 2.00×.
- A–B Repeat, Clean View, Gesture Lock, Quick Screenshot.

## 4. Frame Mode
- Direct controls: `-10 | -1 | Play/Pause | +1 | +10`.
- `+1 / -1 / +10 / -10` must **not change Play/Pause state**.
  - Playing stays playing after the requested frame jump.
  - Paused stays paused after the requested frame jump.
- Long-press `+1` = continuous forward frame stepping.
- Long-press `-1` = continuous backward frame stepping.
- Release stops continuous stepping; normal Play/Pause state remains what it was.
- Long-press repeat follows selected Frame FPS; AUTO follows source timing/FPS estimate.
- Direct FPS AUTO/manual, original PNG Save, zoom -/+, Change View, Fullscreen.
- Single tap hide/show controls; 30-second inactivity auto-hide.
- Controls never disappear while user is actively pressing/holding/dragging.
- Save must use source-decoded frame, not transformed display screenshot.
- Show frame number/total, PTS timestamp, duration and source FPS compactly.

## 5. Trim + Merge — FINAL
- Set Start.
- Set End.
- `+ Add Range`.
- Repeat for unlimited practical ranges in the same video.
- Preview All.
- Export all selected ranges as **one merged new video**.
- Never overwrite source.
- Preserve source orientation/resolution/streams where stream-copy permits.

## 6. Menus / UI structure
- Repeated actions stay direct.
- Secondary actions are grouped; do not use one huge flat menu.
- New Video is available from the current Player/Frame `⋮` flow.
- No library-first redesign.
- No giant title card / oversized gradient home screen.
- Visual family: modern dark media-player UI, compact rounded controls, restrained blue/cyan accent.

## 7. Privacy / security
- Fully offline.
- No INTERNET permission.
- No Firebase, ads, analytics, tracking, WebView, cloud or login.
- No broad storage/media permission.
- SAF for source video selection; MediaStore only for generated output.
- `allowBackup=false`.
- Release `debuggable=false`, minify/resource shrink enabled.
- Never bundle private JKS, real keystore.properties, local.properties, secrets or build folders.
