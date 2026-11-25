# TRUE NAIN — CHANGELOG

All APKs are signed with the same certificate:
SHA-256 `328c046496e1c22a93d014da6346eee2244b5889e8d5917442456b33f0a0138b` (v2 key + v3 lineage).
APK files are attached to **Git/GitHub Releases only** — never committed to the source tree.

---

## v2.4.7 (247) — 2026-11-20 05:12 commit (FINAL, this build)
**What changed (zoom-matrix fix + 3 pack bug fixes):**
- **BUG1 rotate-black**: Frame Mode rotate black-screen fix — visible-rect intersect check, settled refresh, same-rotation crop reuse
- **BUG2 auto-zoom**: relayout never schedules settle; no-op when unchanged; stable zoom across layout changes
- **BUG3 4K**: error toast with what/extra + codec/resolution/fps info + auto Frame-mode fallback when Media Player cannot play
- **Zoom matrix order fix** (Frame draw + getBitmapVisibleRect) — the "zoom ajeeb" shift
- 360 button addView fix (both bars) + rotate resets zoom
- TN360 debug logging

**Known bugs:** see `docs/KNOWN_ISSUES.md` (Bug 1 second-video crash UNCONFIRMED, ghosting UNCONFIRMED, 4K long-video OPEN, landscape pill clip OPEN)

**Status:** CODE REVIEWED + BUILD VERIFIED (this release). DEVICE VERIFIED pending (03_LIVE_TEST_CHECKLIST.md).

---

## v2.4.6 — intermediate builds A/B/C/D + next (2026-11-20)
Build A `ca79cc9f…c8c633` (shader-bug build) → build B `a94499bc…42fe8e` (shader fix) → build C `98c72cff…3356c` (TN360 logging) → build D `48478ee8…55526` (rotate-resets-zoom) → next `2f3eb34d…cc62` (zoom-matrix fix) — **superseded by v2.4.7** which contains all of these + BUG1/2/3 fixes. Details:

**What changed across A→next:**
- **Phase 1 Zoom:** ZoomController (single zoom/pan engine) integrated in BOTH modes; settled bicubic sharp crop (SharpScale); old pinch/pan/wheel removed; fallback keeps approved gestures
- **Phase 2 Quality Look:** ColorGrade denoise→sharpen(zoom-aware)→colour; GL bicubic when zoomed; GL lifecycle fixes
- **360/Fine Rotate:** drag-rotate mode; label changed ⟲ → font-proof `360`
- 360 button addView fix (both bars) + rotate-resets-zoom (came in D/next)

**Known bugs at build D/next:** rotate-black (fixed in 2.4.7 BUG1), auto-zoom loop (fixed 2.4.7 BUG2), 4K long-video (improved 2.4.7 BUG3), second-video crash Bug 1 (OPEN)

**Status:** CODE REVIEWED + BUILD VERIFIED. Gesture tests (±1/±5) PASSED on emulator (frame counter verified).

---

## v2.4.6 — build D (48478ee8…a55526, 2026-11-20 03:17)
**What changed:**
- **360 button FIXED + visible** in Frame Mode AND Media Player top pills — root cause: the button was created but `pill.addView(fineRot,…)` was never called (integration lines lost in an external file overwrite). Label is the font-proof text `360` (the old ⟲ glyph rendered blank on Samsung fonts)
- `cyclePlayerRotation` zoom-reset preparation
- TN360 debug logging groundwork

**Known bugs at this build:**
- Frame Mode zoom shift after seek + settle (fixed in next build, above)
- Rotate + zoom combo produced upside-down close-ups (fixed in next build, above)

**Status:** CODE REVIEWED + BUILD VERIFIED + DEVICE SEEN (owner recording shows the button now renders).

---

## v2.4.6 — build C (98c72cff…3356c, 2026-11-20 02:06)
**What changed:**
- **GL shader crash FIXED** — build D-1 (below) had a duplicate `vec3 c` declaration in the bicubic fragment shader; every video open crashed on the GL thread (`GL shader: ERROR: 0:35: 'c' : redefinition`). Owner hit this on their phone.
- Frame Mode 360 toggle troubleshooting groundwork (TN360 logging came later)

**Known bugs at this build:**
- 360 button still invisible on phone (addView fix not in this build yet — see v2.4.6 build D)

**Status:** CODE REVIEWED + BUILD VERIFIED + zero-crash verified on emulator (video open, GL compile, 10s soak).

---

## v2.4.6 — builds A/B (a94499bc…42fe8e shader-fixed, first build ca79cc9f…c8c633 shader-buggy, 2026-11-18)
**What changed (the pack — 3 phases):**
- **Phase 1 Zoom:** ZoomController (single zoom/pan engine) integrated in BOTH Media Player and Frame Mode; settled (300 ms) bicubic sharp crop (new `SharpScale.java`, Catmull-Rom); old pinch/pan/wheel code removed; fallback pattern keeps approved gestures
- **Phase 2 Quality Look:** ColorGrade reordered denoise → sharpen(zoom-aware) → colour + vignette; GL shader got Catmull-Rom bicubic sampling when zoomed (uZoom > 1.3); GL lifecycle fixes (glResume on activity resume, surface re-attach on recreate)
- **Phase 3 Seek/Memory:** keyframe-first seek (SYNC immediate + 350 ms CLOSEST refine with generation guard); onDestroy shutdown moved to a background thread (12 s main-thread ANR risk removed); onTrimMemory clears frame cache + bitmap pool; prefetch budget seeded with the REAL frame size (4K no longer assumes 1080p)
- **360/Fine Rotate:** drag-rotate mode (⟲ icon toggles; drag = free rotation; arrow keys ±1°)
- 360 button label later changed from the ⟲ glyph to the font-proof text `360`

**Known bugs at these builds:**
- Build A (ca79cc9f): duplicate `vec3 c` shader declaration → crash on every video open (owner hit it on their phone) — fixed in build B/C
- 360 top-pill button invisible on phone — the `pill.addView(fineRot,…)` line was missing in both top bars (fixed in v2.4.6 build D + the addView fix landed 2026-11-20)
- Zoom-shift after seek+settle (fixed 2026-11-20, see "next build")

**Status:** CODE REVIEWED + BUILD VERIFIED. Gesture tests (±1/±5) PASSED on emulator with the Phase-1 build (frame counter 7974↔7973→7968→7973).

---

## v2.4.5 (245) — 93cf68f0…f44642c (2026-11-18)
**What changed:**
- Fine Rotate drag mode (⟲ toggle, drag + arrow keys ±1°) — owner request after a Windows-Photos-style comparison
- Frame Mode fullscreen: nav controls hide (tap brings compact controls back)
- ±10/±5 instant movement: intermediate frames walk through the decode queue (no more stop-then-jump)
- The v2.4.5 state was audited statically by an external agent (no device test): findings recorded in `docs/rules/01_AUDIT_FINDINGS_v2.4.5.md` — including the then-missing `glUseProgram` call that made the whole Quality Look GL pipeline inert

**Known bugs at v2.4.5 (led to the v2.4.6 pack work):**
- Zoom/pan logic spread across MainActivity; no unified zoom engine
- Far seek (1 min → 6 min) could hang; 4K long videos could crash
- onDestroy awaited executors on the main thread (up to ~12 s ANR risk)
- No onTrimMemory handler; prefetch window assumed 1080p frame size
- GLSurfaceView lifecycle: no onResume path (black/frozen risk after home/back/screen-off)
- Quality Look sharpen ran on raw noisy pixels (amplified blocks at zoom)
- Trim & Merge audio retention unverified

**Status:** released + owner-tested on phone (recordings exist); the audit above is CODE REVIEWED only.

---

## v2.0.7 — TRUE_NAIN_v2.0.7_RELEASE (2026-10-21 era)
**What changed (from the audit-fix docs shipped with the source):**
- The four issues from `FOUR_ISSUES_AUDIT_2026-10-21.md` were fixed (see that file + `AUDIT_FIX_REPORT_2026-10-21.md` in the repo)
- Source hand-off notes in `START_HERE_SOURCE_HANDOFF.md` / `UPDATED_SOURCE_NOTES_2026-10-21.md`
- Security posture of the era: offline, minimal permissions, not debuggable, backup off, cleartext off

**Known limitations then:** documented in `REV3_AUDIT_STATUS.md` / `VERIFICATION.md` (repo files).

**Status:** historical release; APK + SHA live in the local release folders (see `TRUE_NAIN_v2.0.7_RELEASE\`).

---

## Earlier versions (v2.0.2 … v2.4.4)
Summaries live in `ADVANCED_UPDATE_NOTES.md`, `IMPLEMENTATION_SUMMARY.md`, `BUILD_STATUS_AND_REQUIREMENTS.md`, `FINAL_UI_UX_LOCK.md` (repo files) and the Git history. Per-version APKs are in the local `Desktop\ZCode\Apk\TRUE_NAIN_vX.X.X_RELEASE\` folders.
