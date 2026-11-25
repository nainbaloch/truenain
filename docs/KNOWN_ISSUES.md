# TRUE NAIN — Known Issues

Status labels: OPEN / FIXED (in build X, needs device verify) / WONT-FIX (owner decision) / UNCONFIRMED.

| # | Issue | Status |
|---|---|---|
| 1 | **Frame Mode: black screen after rotate** — owner-reported; suspected draw-matrix composition (v2.4.6 matrix-order fix may have corrected it — needs repro on the latest build) | OPEN (needs repro on latest build) |
| 2 | **Zoom changes by itself** — zoom loop suspected between ZC relayout and the settle path when regions change | OPEN (needs repro; v2.4.6 matrix fix removed one known contributor) |
| 3 | **4K ~15 min video does not play in Media Player** — >1440p routes to the DIRECT engine; long 4K files still fail on the owner's device | OPEN (device-specific; needs phone logcat) |
| 4 | **Quality Look pipeline (as of the external session's tuning, kept by owner):** denoise disabled at zoom ≥ 2 (so no denoise-before-sharpen at high zoom); sharpen strength INCREASES with zoom; prefetch window field `window` is `final` (1080p-era value — impact reduced because the prefetch BUDGET now uses the real frame size via primeFrameSize) | OPEN-BY-TUNING (owner's chosen behaviour; budget part fixed in v2.4.6) |
| 5 | **Second video open after a full first video → crash (Bug 1)** — never reproduced on the emulator; needs phone logcat | OPEN (needs phone logcat) |
| 6 | **Frame Mode ghosting / double-exposure** (owner recording F 4020 — two frames blended) | UNCONFIRMED (source video crossfade vs app blending — source file removed from laptop; re-verify with the original video) |
| 7 | **Frame Mode zoom-shift after seek + settle** (owner recording F 5304–5305 ~5–20% zoomed) | FIXED in 2026-11-20 build (matrix order in onDraw + getBitmapVisibleRect) — needs device verify |
| 8 | **Rotate + zoom combo → wrong close-up** (owner recording r_05: upside-down arm at 180°) | FIXED 2026-11-20 (↻ now resets zoom/pan) — needs device verify |
| 9 | **360 top-pill button invisible** | FIXED 2026-11-20 (pill.addView(fineRot) was missing in both bars) — device verify pending |
| 10 | **Player bottom-right actions pill clipped in landscape** (FPS/SAVE merged, ＋ half-cut at the screen edge) | OPEN (panel width/clip in landscape) |
| 11 | **360 toggle appeared unreliable during emulator automation** | Test artifact suspected (uiautomator dead-zone + controls-toggle flopping); TN360 logging added — needs phone re-test |
| 12 | **Trim & Merge audio retention** | UNCONFIRMED (external audit — needs a test with audio video) |

## Fixed in the v2.4.6 line (for history)

- GL shader crash: duplicate `vec3 c` in the bicubic fragment shader (build D-1 → fixed build C+)
- **glUseProgram never called** — the whole Quality Look GL pipeline was inert since the shader existed (fixed by the external session; also made the emulator player render visibly)
- 360 button missing from both top pills (addView missing) — fixed 2026-11-20
- onDestroy main-thread executor wait (12 s ANR risk) — background shutdown
- No onTrimMemory — cache now trims on memory pressure
- Prefetch budget assumed 1080p — now seeded with the real frame size
- Seek hang on far jumps — keyframe-first + 350 ms exact refine
- Frame Mode layout sideways/clipped in landscape — orientation rebuild fix
- Player black screen on the emulator (SwiftShader) — resolved by the glUseProgram fix (was previously believed to be an emulator-only limitation; the fix made GL render there too)
