# TRUE NAIN v2.4.5 — TOTAL AUDIT: Buttons • Functions • Keys • Mapping • Linking
*(Source: MainActivity.java + GradedVideoView.java + ZoomableImageView.java — v2.4.5/245, 2026-11-17)*

---

## 1. HOME SCREEN

| Icon | Card | Function (handler) |
|---|---|---|
| ▶ | MEDIA PLAYER | `preferredLibraryMode=PLAYER` + Library kholta hai (video wahan se) |
| ▣ | FRAME MODE | `preferredLibraryMode=FRAME` + Library |
| ▤ | EXTRACTOR | `preferredLibraryMode=EXTRACTOR` + Library |
| ✂ | VIDEO EDITOR | `pendingOpenTrim=true` + Library (video khulte hi Samsung/Trim editor) |
| ▦ | VIDEO LIBRARY | Seedha Library |
| ⚙ | CUSTOMIZE CONTROLS | `showControlSettings()` — buttons ON/OFF dialog |

## 2. VIDEO LIBRARY SCREEN
| Item | Function |
|---|---|
| Video card | `openVideo(uri)` → `preferredLibraryMode` ke mutabiq mode khulta hai |
| + ADD VIDEO FROM DEVICE | System file picker → `onActivityResult` → openVideo |
| ⟳ REFRESH DEVICE VIDEOS | MediaStore re-scan → device videos list |
| SWITCH TO FRAME/MEDIA PLAYER | Mode toggle (dono modes mein ek dikhta hai) |
| ‹ MAIN MENU | `showHome()` |

## 3. MEDIA PLAYER (MODE_PLAYER)

### 3a. Top-right pill: ⛶ ↻ ⟲ ⋮
| Icon | Key (prefs) | Function |
|---|---|---|
| ⛶ | — (fixed) | `toggleFullscreen()` — system bars on/off |
| ↻ | — (fixed) | `cyclePlayerRotation()` — video 90° steps (0→90→180→270) |
| ⟲ | — (fixed) | `toggleFineRotateMode()` — v2.4.5 DRAG-rotate ON/OFF (neeche §3d) |
| ⋮ | `top_menu` | `showPlayerMenu()` — neeche §3c |
| ‹ (top-left) | `top_back` | `onBackPressed()` — pehle screen par wapas |

### 3b. Bottom bar
| Icon | Key (prefs) | Function |
|---|---|---|
| Seek row 00:00 ─●─ 05:00 | — | `mediaPlayer.seekTo(SEEK_CLOSEST_SYNC)` drag scrub + center time-pill |
| ▶/⏸ | `player_play` | `togglePlayer()` — play/pause (0.05–0.25x par decoded slow-motion) |
| ▣ | `player_frame` | `switchMode(MODE_FRAME)` — Frame Mode shortcut |
| 📷 | — (fixed) | `savePlayerScreenshot()` — current frame PNG (look bake hota hai) |
| − / ＋ | — (fixed) | `playerZoom ×0.8 / ×1.25` — pinch-zoom buttons |
| 1.0x | — (fixed) | `showSpeedPopup()` — 0.05/0.10/0.25/0.50/1.00/1.50/2.00 (0.05–0.25x = decoded slow path) |
| 🎨 | — (fixed) | `showLookPopup(false)` — Original/HDR+/Cinematic/Vivid/Warm/Cool — LIVE GL par |

### 3c. ⋮ Menu
**View:**
| Item | Function |
|---|---|
| FIT / FILL / 1:1 | `playerDisplayMode` set + zoom reset |
| Reset Zoom / Pan | `resetPlayerZoom()` — zoom+pan+overlay+fine-angle sab reset |
| Clean View | Controls chhupao |
| Gesture Lock / Unlock | `gestureLocked` toggle — pinch/pan/double-tap band |
**Tools:**
| Item | Function |
|---|---|
| Video Library | `showLibrary()` (mode yaad rehta hai) |
| Customize Controls | `showControlSettings()` |
| Extract All Frames | `extractAllFrames()` |
| Open New Video | Picker |
| Trim + Merge | Samsung editor → fallback TrimActivity |
| **Reset Settings** | Prefs clear + sab default (neeche §7) |
| Find Sharper Frame (nearby) | Aas-paas sharp frame dhoond kar jump |

### 3d. Fine Rotate Mode (⟲ ON par) — v2.4.5
- Poore screen ka **transparent overlay** sab gestures ko pakad leta hai
- **Finger/mouse DRAG** = center ke gird `atan2` dial → video FREE ghoomta (±180° normalized, gol-gol chalta rehta hai)
- **Arrow keys ←/→** = ±1° step
- Neeche angle pill live (e.g. `11.6°`)
- Dubara ⟲ = OFF, angle set rehta hai (undo: View → Reset Zoom / Pan ya Reset Settings)
- Frame field: `playerFineRotation`; apply: `videoView.setRotation(playerContentRotation + playerFineRotation)`

### 3e. Touch gestures (video area)
| Gesture | Function |
|---|---|
| Single tap | Controls on/off |
| Double-tap LEFT/RIGHT | −5s / +5s (SEEK_CLOSEST_SYNC) + «5s» pill |
| Horizontal drag (zoom>1) | Pan (content-clamp) |
| Pinch | Zoom 1×–12× (maxPlayerZoom) |
| Vertical drag (fullscreen) | Brightness (left) / Volume (right) |
| Mouse wheel | Zoom |
| Long-press (speed hold) | 0.5x preview hold-slow |

### 3f. Hardware keys
| Key | Condition | Function |
|---|---|---|
| ←/→ (DPAD) | **Fine Rotate ON** | ±1° rotate (v2.4.5) |

## 4. FRAME MODE (MODE_FRAME)

### 4a. Top-right pill: ⛶ 🎨 ↻ ⟲ ⋮
| Icon | Key | Function |
|---|---|---|
| ⛶ | — | `toggleFullscreen()` — rebuild + **controls HIDE** (v2.4.5); tap = wapas (compact) |
| 🎨 | — | `showLookPopup(true)` — look select + displayed frame LIVE re-grade |
| ↻ | — | `cycleFrameRotation()` — 90° steps (zoom reset hota hai) |
| ⟲ | — | `toggleFineRotateMode()` — drag-rotate (§3d jaisa, `frameFineRotation`) |
| ⋮ | `top_menu` | `showFrameMenu()` — §4c |
| ‹ | `top_back` | Back (Library/Main) |

### 4b. Bottom panel
| Button | Key | Function |
|---|---|---|
| −10 / −1 / ▶ / +1 / +10 | `frame_nav` (sab) + `frame_play` (▶) | `manualStep(±10/±1)` — **v2.4.5: ±10/±5 ab FORAN move (walk)**; ▶ = `toggleAutoPlay()` (FPS Auto ya set FPS) |
| FPS | `frame_fps` | `showFpsDialog()` — AUTO ya 1..source fps |
| SAVE | `frame_save` | `saveCurrentFrame()` — PNG (look bake) |
| − / ＋ | — (fixed) | `viewer.zoomBy(0.8/1.25)` |
| Seek bar | — | `goTo(index)` — frame jump (frameLocked par band) |
| Info line `F N / M · fps · time` | — | Live position |

### 4c. ⋮ Menu
**View:** Change View (FIT/FILL/1:1) • Fullscreen • Reset Zoom / Rotate • **Fine Rotate (Seedha)** • Rotate Content (0/90/180/270°) • FIT/FILL/1:1 • Clean View
**Tools:** Switch to Media Player • Go To Frame • Frame Info • FPS Settings • Find Sharper Frame • Frame Lock/Unlock • Gesture Lock/Unlock • Video Library • Customize Controls • Extract All Frames • Open New Video • **Reset Settings**

### 4d. Touch gestures (v2.4.5)
| Gesture | Function |
|---|---|
| Single tap | Controls on/off |
| **Double-tap LEFT** | −1 frame |
| **Double-tap RIGHT** | +1 frame |
| **HOLD LEFT** | −5 frames |
| **HOLD RIGHT** | +5 frames |
| Pinch | Zoom (maxScale = 2.5× native, cap 12×) |
| Drag (zoom>1) | Pan |
| Mouse wheel | Zoom |

### 4e. Hardware keys
| Key | Condition | Function |
|---|---|---|
| ←/→ (DPAD) | Fine Rotate ON | ±1° rotate |
| Volume ▲/▼ | Frame + landscape | +1/−1 frame |

## 5. FRAME EXTRACTOR (MODE_EXTRACTOR)
| Button | Key | Function |
|---|---|---|
| EXPORT ALL FRAMES | `extract_all` | `exportFrameDialog(false)` — sab frames PNG |
| CUSTOM FRAME RANGE / SELECTION | `extract_custom` | `exportFrameDialog(true)` — "1,5,10,25-100" |
| ⟲ PHOTO ROTATE | `extract_rotate` | `showPhotoRotateDialog()` — export angle fix |
| ‹ VIDEO LIBRARY | `extract_library` | Library wapas |

## 6. CUSTOMIZE CONTROLS — KEY MAPPING (prefs: `control_custom`)
| Key | Label | Controls |
|---|---|---|
| `top_back` | Top Bar: Back (‹) | Player + Frame dono ka ‹ |
| `top_menu` | Top Bar: Menu (⋮) | Player + Frame dono ka ⋮ |
| `player_play` | Player: Play/Pause | ▶/⏸ |
| `player_frame` | Player: Frame Shortcut | ▣ |
| `frame_nav` | Frame: Step Buttons (±1/±10) | −10 −1 +1 +10 |
| `frame_play` | Frame: Play/Pause | ▶ |
| `frame_save` | Frame: SAVE Button | SAVE |
| `frame_fps` | Frame: FPS Button | FPS |
| `frame_zoom` | Frame: Zoom Buttons | − ＋ |
| `extract_all` | Extractor: Export All | EXPORT ALL FRAMES |
| `extract_custom` | Extractor: Custom Range | CUSTOM RANGE |
| `extract_rotate` | Extractor: Photo Rotate | ⟲ PHOTO ROTATE |
| `extract_library` | Extractor: Library Button | ‹ VIDEO LIBRARY |

**Note:** Player ke zoom −/＋, 1.0x, 🎨, 📷 aur Frame ka 🎨/↻/⟲/⛶ abhi CUSTOMIZE se control NAHI hote (fixed) — sirf upar wale 13 keys.

**Button order (drag-reorder):** prefs `control_orders` — key `b_<ButtonLabel>` → row position (long-press button = Move Left/Right).

## 7. RESET SETTINGS (donon ⋮ Tools) — kya reset hota hai
- `control_custom` + `control_orders` prefs **clear** (sab buttons wapas + default order)
- Look → Original • Speed → 1.00x • FPS → AUTO • view mode → FIT • display/picture/fine rotations → 0 • Gesture/Frame Lock → OFF • A-B repeat → OFF • Player zoom/pan/overlay → reset • Viewer → FIT + rotation 0
- **NOT reset:** videos, library, resume positions

## 8. PREFERENCES (SharedPreferences) — pura link
| File | Keys | Kis se likha/jata hai |
|---|---|---|
| `control_custom` | 13 button keys (bool) | Customize Controls → `controlEnabled()` har button ki visibility |
| `control_orders` | `b_<label>` (int) | Long-press Move L/R → `applyControlOrder()` |
| `resume_positions` | uri → ms | Player close par save, open par resume |
| `library_v2` | `uri_0..N` | Video library persist (max 1000) |
| (system) | file picker persistable grants | ADD VIDEO ke URIs |

## 9. INTERNAL LINKS (kaun kisko call karta hai — summary)
- `openVideo()` → `FrameSource.open()` (background) → `onVideoOpened()` → `enterMode()` → `showPlayer()/showFrame()/showExtractor()`
- `enterMode()` → hamesha `releasePlayer()` + `fineRotateMode=false`
- `manualStep()` → `requestExactFrame` (±1) / **`requestFrameWalk` (±10/±5 — v2.4.5)** → `decodeLoop()` → `frameCache.get/put` → `onFrameDecoded()` → `viewer.setFrame()` + prefetch
- Quality Look: 🎨 → `GradedVideoView.setLookPreset()` (GL uniform, live) + `requestLookRedraw()` (paused bhi) + Frame mein `ColorGrade.grade()` re-grade
- Save: `saveCurrentFrame/savePlayerScreenshot` → `ColorGrade.grade` (look bake) → `Pictures/TRUE NAIN`
- Trim: `openTrim()` → Samsung editor packages → fallback `TrimActivity`
