# TRUE NAIN — Testing

## Live test checklist (owner, phone)

Install order for a fix round: new FINAL APK over the previous (Settings must show the new version). Use the same test videos every time. Write the phone model next to each result.

### Media Player
1. Open video: does playback start without black gap? (v2.4.6 seek-on-prepared)
2. Pinch zoom in/out — does the picture stay under the fingers?
3. Zoom to 4x, drag, flick — glides, stops at edges?
4. Seek far (1 min → 6 min): time until correct picture? Hang?
5. Scrub fast left-right: any stale frame appearing late?
6. Rotate ↻ (90/180/270): zoom/pan resets to clean fit?
7. Quality Look: each preset at 1x / 4x — applied live? Original = unchanged?
8. Press home, come back, screen off/on: picture not black/frozen?
9. Saved screenshot matches screen + label says the look used?
10. Second video open after a full first video: crash? (long-standing Bug 1 — needs logcat if it happens)

### Frame Mode
1. ±1 / ±10 / hold-walk: frame moves immediately (no stop-then-jump)?
2. Double-tap LEFT = −1, RIGHT = +1?
3. Hold LEFT/RIGHT = −5/+5?
4. Rotate ↻ then zoom: composition correct after the matrix fix?
5. 360 button: toggle ON → drag rotates any direction; arrow keys ±1°; OFF keeps angle?
6. Settled sharp crop: no zoom-shift after seek+settle?
7. FPS selector: 1 fps walk smooth?
8. SAVE: PNG saved to Pictures/TRUE NAIN with look baked?

### Extract
1. Export all + custom range output count/correctness.
2. Photo Rotate orientation fix applies to export.

## Test videos (kept OUTSIDE the repo — no video files committed)

| File | Resolution | Codec | FPS | Duration | Notes |
|---|---|---|---|---|---|
| test_video_1.mp4 (was `videoplayback.mp4`) | 1920×1080 | h264 | 30 | 05:35 (10,036 frames) | CFR 30, standard vlog scene; frame counter F N/10036 |
| test_video_2.mp4 (Pakistani vlog) | 1920×1080 | h264 | 30 | 15:15 (27,449 frames) | longer content for seek/crash tests |
| (recommended additions) | 2160p (4K) | h264/HEVC | 30 | ~15 min | 4K + long-GOP for Phase 3 checks; portrait clip for rotation |

Codec/fps observed from playback logs (`c2.goldfish.h264.decoder`, frame counter `30 fps`) — bit depth 8-bit (standard 8-bit pipeline).

## Emulator notes (S9Plus AVD)

- SwiftShader GL is slow but RENDERS (after the glUseProgram fix) — visual checks possible
- uiautomator dumps fail during playback ("could not get idle") — pause first or use screenshots + fixed coordinates
- Controls auto-hide after 30 s — every center tap toggles them
- Emulator window opens off-screen (Y=-1138) — PowerShell MoveWindow needed each start
