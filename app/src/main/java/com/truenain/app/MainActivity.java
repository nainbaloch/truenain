package com.truenain.app;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaMetadataRetriever;
import android.util.LruCache;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Trace;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TRUE NAIN 1.4.0 - two tools in one offline APK:
 * MEDIA PLAYER (clean playback) and FRAME MODE (exact source-frame inspection).
 * Pure framework code: no AndroidX, no third-party libraries, no network.
 *
 * Both modes work on the same selected video. Switching modes carries the current
 * timestamp across without asking for the file again.
 *
 * UI: Modern dark forensics aesthetic — deep navy bg, cyan/blue accents,
 *     glass panels, gradient buttons, glowing pill labels.
 */
public class MainActivity extends Activity {

    private static final int MODE_HOME   = 0;
    private static final int MODE_PLAYER = 1;
    private static final int MODE_FRAME  = 2;
    private static final int MODE_LIBRARY = 3;
    private static final int MODE_EXTRACTOR = 4;
    private final LruCache<String, Bitmap> thumbCache = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount(); }
    };
    private final ExecutorService thumbnailWorkers = Executors.newFixedThreadPool(2);
    private final ArrayList<String> libraryLabels = new ArrayList<>();
    private volatile boolean exportCancelled = false;
    private boolean exportRunning = false;
    private int libraryScrollY = 0;

    private static final int REQ_OPEN_VIDEO = 1001;
    private static final int REQ_GALLERY_ROTATE = 1002;   // V2.0: rotate any Gallery photo
    private static final int REQ_LIBRARY_PERMISSION = 1002;
    private final ArrayList<Uri> deviceVideos = new ArrayList<>();
    private boolean deviceScanDone = false;
    private boolean libraryScanRunning = false;
    private static final String STATE_URI  = "uri";
    private static final String STATE_POS  = "pos";
    private static final String STATE_MODE = "mode";

    // ── Modern colour palette ──────────────────────────────────────────────
    private static final int COLOR_BG          = 0xFF020810; // near-black navy
    private static final int COLOR_BG2         = 0xFF060F1E; // slightly lighter bg
    private static final int COLOR_GLASS       = 0x1AFFFFFF; // white tint for glass panels
    private static final int COLOR_GLASS_DARK  = 0xCC060F1E; // dark semi-opaque
    private static final int COLOR_BORDER      = 0x33FFFFFF; // subtle white border
    private static final int COLOR_TEXT        = 0xFFF0F6FF; // near-white text
    private static final int COLOR_MUTED       = 0xFF7E93AE; // muted slate — 6.4:1 on BG (WCAG AA+)
    private static final int COLOR_DIM         = 0xFF3A4D66; // dim accent (decorative only)
    private static final int COLOR_ON_ACCENT   = 0xFF0B1220; // dark text on bright accent fills (12:1)
    // Player accent: electric blue
    private static final int COLOR_PLAYER_A    = 0xFF1976D2; // blue 700 — white text 4.6:1 ✓
    private static final int COLOR_PLAYER_B    = 0xFF0D47A1;
    private static final int COLOR_PLAYER_GLOW = 0x661E88E5;
    // Frame accent: neon cyan-teal
    private static final int COLOR_FRAME_A     = 0xFF00E5C8;
    private static final int COLOR_FRAME_B     = 0xFF00897B;
    private static final int COLOR_FRAME_GLOW  = 0x6600E5C8;
    // Danger / save accent
    private static final int COLOR_SAVE_A      = 0xFFFF6F00;
    private static final int COLOR_SAVE_B      = 0xFFE65100;
    // Overlay
    private static final int COLOR_OVERLAY     = 0xCC020810;

    private static final double[] PLAYBACK_SPEEDS = {0.05, 0.10, 0.25, 0.50, 1.00, 1.50, 2.00};
    private static final String[] VIEW_LABELS     = {"FIT", "FILL", "1:1"};

    private final Handler         ui      = new Handler(Looper.getMainLooper());
    private final AtomicInteger targetIndex   = new AtomicInteger(-1);
    private final AtomicBoolean decodeRunning = new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<Integer> exactFrameQueue = new ConcurrentLinkedQueue<>();
    private volatile int         lastDecodedIndex = -1;
    private volatile FrameSource source;

    // ── V2.0 frame cache engine ─────────────────────────────────────────────
    private volatile FrameCacheManager frameCache;   // P0-03: volatile; never nulled mid-flight
    private final AtomicLong frameRequestSeq = new AtomicLong(0);   // stale-decode guard
    private volatile long frameSourceId = 0L;                       // unique per opened video
    private volatile long slowSourceId = 0L;                        // unique per slow-motion session
    private boolean pendingOpenTrim;                                // V2.0.7: VIDEO EDITOR home card → Trim
    private TextView seekFlash;                                     // V2.0.6: ±5s double-tap indicator
    private TextView scrubPill;                                     // center floating time pill during finger scrub
    private ImageView zoomOverlay;                                  // V2.0.8: full-res paused-zoom
    private Bitmap zoomOverlayBmp;
    private int zoomOverlayKey = Integer.MIN_VALUE;
    private boolean zoomOverlayDecoding;
    private float playerTotalScale = 1f;
    // Hardening spec S1: viewport-region decode + gesture-end/pan-pause refresh.
    private boolean zoomOverlayCropMode;                            // overlay currently shows a viewport CROP
    private int zoomCropX, zoomCropY;                               // crop origin in source pixels
    private final Runnable zoomPanRefresh = () -> refreshZoomOverlay(playerTotalScale, true);
    // Hardening spec S2.1: 0.5x hardware-first with a stall watchdog → decoded fallback.
    private long hwStallSinceMs = 0L;
    private int lastHwPosMs = Integer.MIN_VALUE;
    private float hwSpeedApplied = 1f;   // 1.0x par setPlaybackParams skip (black/green churn fix)
    private int slowRetryCount;                                     // V2.0.8: slow decode retries
    private volatile long lastDisplayedPtsUs = Long.MIN_VALUE;      // anchor for timeline remap
    private volatile long slowCurrentPtsUs = Long.MIN_VALUE;        // anchor for slow-timeline remap
    private volatile Bitmap      savingBitmap;

    private volatile ExecutorService decoder;
    private volatile ExecutorService saver;
    private volatile ExecutorService slowDecoder;

    private static final ThreadFactory decoderThreadFactory = r -> {
        Thread t = new Thread(r, "TrueNain-Decoder");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    };
    private static final ThreadFactory saverThreadFactory = r -> {
        Thread t = new Thread(r, "TrueNain-Saver");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    };
    private static final ThreadFactory slowThreadFactory = r -> {
        Thread t = new Thread(r, "TrueNain-Slow");
        t.setPriority(Thread.NORM_PRIORITY - 2);
        return t;
    };

    private ExecutorService getDecoder() {
        if (decoder == null || decoder.isShutdown() || decoder.isTerminated()) {
            synchronized (this) {
                if (decoder == null || decoder.isShutdown() || decoder.isTerminated()) {
                    decoder = Executors.newSingleThreadExecutor(decoderThreadFactory);
                }
            }
        }
        return decoder;
    }

    private ExecutorService getSaver() {
        if (saver == null || saver.isShutdown() || saver.isTerminated()) {
            synchronized (this) {
                if (saver == null || saver.isShutdown() || saver.isTerminated()) {
                    saver = Executors.newSingleThreadExecutor(saverThreadFactory);
                }
            }
        }
        return saver;
    }

    private ExecutorService getSlowDecoder() {
        if (slowDecoder == null || slowDecoder.isShutdown() || slowDecoder.isTerminated()) {
            synchronized (this) {
                if (slowDecoder == null || slowDecoder.isShutdown() || slowDecoder.isTerminated()) {
                    slowDecoder = Executors.newSingleThreadExecutor(slowThreadFactory);
                }
            }
        }
        return slowDecoder;
    }

    // Perf fix: video OPEN used to share the decoder executor with Frame-Mode frame
    // decodes and zoom-overlay decodes — a new video queued behind all of them and
    // loading felt slow.  Open now has its own dedicated thread.
    private volatile ExecutorService opener;
    private static final ThreadFactory openerThreadFactory = r -> {
        Thread t = new Thread(r, "TrueNain-Opener");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    };

    private ExecutorService getOpener() {
        if (opener == null || opener.isShutdown() || opener.isTerminated()) {
            synchronized (this) {
                if (opener == null || opener.isShutdown() || opener.isTerminated()) {
                    opener = Executors.newSingleThreadExecutor(openerThreadFactory);
                }
            }
        }
        return opener;
    }

    // ── shared state ───────────────────────────────────────────────────────
    private int          mode        = MODE_HOME;
    private int          pendingMode = MODE_HOME;
    private boolean      landscape   = false;
    private boolean      fullscreen  = false;
    private boolean      loading     = false;
    private FrameSource  uiSource;
    private Uri          currentUri;
    private String       currentName;
    private long         syncPositionUs = 0;

    // ── frame mode state ───────────────────────────────────────────────────
    private int     requestedIndex      = 0;
    private int     displayedIndex      = -1;
    private Bitmap  currentBitmap;
    private Bitmap  shownBitmap;              // Quality Pack: graded display copy (original cache mein rehta hai)
    private boolean firstFrameAfterOpen = true;
    private boolean autoPlaying         = false;
    private int     playbackFps         = 0;
    private int     speedIndex          = 4;
    private int     displayMode         = ZoomableImageView.MODE_FIT;
    private int     displayRotation     = 0;
    private int     pictureRotation     = 0;   // Manual picture rotation (0, 90, 180, 270)
    private float   frameFineRotation   = 0f;  // v2.4.4: free-angle rotation (tirchhi video seedha karne ke liye)
    private int     videoRotation       = 0;   // Video rotation metadata from source
    private boolean frameBuiltLandscape;
    private int     frameGradePreset;          // Quality Pack: SAVE par lagne wala look
    private final Runnable autoPlayRunnable = this::autoPlayTick;

    // ── player mode state ──────────────────────────────────────────────────
    // Fix 02: decoded-frame presentation for speeds below MediaPlayer's reliable range.
    // Runs on a separate decoder, never on the UI thread.
    private final AtomicInteger slowGeneration = new AtomicInteger();
    private ImageView slowImage;
    private FrameSource slowSource;
    private Bitmap slowBitmap;
    private boolean slowActive, slowPlaying;
    private int slowFrameIndex;
    private final Runnable slowFrameTick = this::renderSlowFrame;
    private GradedVideoView videoView;   // dual-engine: GL live look (≤1440p) / direct (2K-4K)
    private MediaPlayer mediaPlayer;
    private ZoomController playerZc;     // v2.4.6 PHASE 1: single zoom engine (player)
    private final java.util.HashMap<String, java.util.ArrayList<View>> controlViews = new java.util.HashMap<>();
    private void registerControl(String key, View v) {
        java.util.ArrayList<View> views = controlViews.get(key);
        if (views == null) { views = new java.util.ArrayList<>(); controlViews.put(key, views); }
        views.add(v);
        v.setVisibility(controlEnabled(key) ? View.VISIBLE : View.GONE);
        if (v instanceof Button) {
            v.setOnLongClickListener(ignored -> {
                if (!(v.getParent() instanceof LinearLayout)) return false;
                final LinearLayout row = (LinearLayout) v.getParent();
                final int position = row.indexOfChild(v);
                new AlertDialog.Builder(this).setTitle("Move: " + ((Button)v).getText())
                        .setItems(new String[]{"Move left", "Move right"}, (d, choice) -> {
                            int target = position + (choice == 0 ? -1 : 1);
                            if (target < 0 || target >= row.getChildCount()) return;
                            View other = row.getChildAt(target);
                            ViewGroup.LayoutParams mine = v.getLayoutParams();
                            row.removeView(v);
                            row.addView(v, target, mine);
                            // Save the resulting per-row visual order by stable button labels.
                            android.content.SharedPreferences.Editor edit = getSharedPreferences("control_orders", MODE_PRIVATE).edit();
                            for (int j = 0; j < row.getChildCount(); j++) {
                                View child = row.getChildAt(j);
                                if (child instanceof Button) edit.putInt("b_" + ((Button)child).getText().toString(), j);
                            }
                            edit.apply();
                        }).show();
                return true;
            });
        }
    }
    private void applyControlOrder(LinearLayout row) {
        java.util.ArrayList<View> children = new java.util.ArrayList<>();
        for (int i=0;i<row.getChildCount();i++) children.add(row.getChildAt(i));
        android.content.SharedPreferences order = getSharedPreferences("control_orders", MODE_PRIVATE);
        java.util.Collections.sort(children, (a,b) -> Integer.compare(
            a instanceof Button ? order.getInt("b_"+((Button)a).getText().toString(), 500) : 500,
            b instanceof Button ? order.getInt("b_"+((Button)b).getText().toString(), 500) : 500));
        row.removeAllViews();
        for (View child : children) row.addView(child);
    }
    private void refreshControls() {
        for (java.util.Map.Entry<String, java.util.ArrayList<View>> entry : controlViews.entrySet())
            for (View v : entry.getValue()) v.setVisibility(controlEnabled(entry.getKey()) ? View.VISIBLE : View.GONE);
    }
    private boolean     playerPrepared;
    private boolean     startPlayingOnPrepared;
    private boolean     userSeeking;
    private boolean     holdSlow;
    private boolean     controlsVisible = true;
    private boolean     gestureLocked = false;
    private boolean     frameLocked = false;
    private int         playerDisplayMode = ZoomableImageView.MODE_FIT;
    private int         playerContentRotation = 0;
    private float       playerFineRotation    = 0f;   // v2.4.4: free-angle (fine) rotation — player + frame dono
    private boolean     fineRotateMode        = false; // v2.4.5: drag/arrow rotate mode (⟲ icon ON/OFF)
    private float       rotStartTouch         = 0f;    // drag start par finger ka angle
    private float       rotStartRot           = 0f;    // drag start par video ka total rotation
    private FrameLayout rotateOverlayFrame    = null;  // Frame Mode ka drag overlay
    private FrameLayout rotateOverlayPlayer   = null;  // Player ka drag overlay
    private float       playerZoom = 1f;
    private float       playerPanX = 0f;
    private float       playerPanY = 0f;
    private long        abStartMs = -1L;
    private long        abEndMs = -1L;
    private boolean     abRepeatEnabled = false;
    private final ArrayList<Uri> videoLibrary = new ArrayList<>();
    private int preferredLibraryMode = MODE_PLAYER;
    private int currentVideoIndex = -1;
    private final Runnable playerControlsAutoHide = () -> {
        if (mode == MODE_PLAYER && videoView != null && playerPrepared)
            setPlayerControlsVisible(false);
    };

    // ── views ──────────────────────────────────────────────────────────────
    private FrameLayout      rootFrame;
    private FrameLayout      modeContainer;
    private ProgressBar      busyView;
    private View             playerTop;
    private View             playerBottom;
    private Button           playerPlayButton;
    private Button           speedPillBtn;         // glass pill "1.0x" speed button
    private Button           lookPillBtn;          // glass pill "🎨" Quality-Look button
    private TextView         playerCurrent;
    private TextView         playerDuration;
    private SeekBar          playerSeek;
    private ZoomableImageView viewer;
    private TextView         frameInfo;
    private SeekBar          frameSeek;
    private Button           framePlayButton;
    private Button           fpsButton;
    private Button           playerOrientButton;   // legacy ref (↻ VIEW removed — kept null-safe)
    private Button           frameOrientButton;
    private View             frameTop;
    private View             frameBottom;
    private boolean          frameControlsVisible = true;
    private boolean          frameRepeatActive = false;
    private boolean          frameRepeatLong = false;
    private boolean          frameRepeatResumeAuto = false;
    private int              frameRepeatDelta = 0;
    private final Runnable frameControlsAutoHide = () -> {
        if (mode == MODE_FRAME) setFrameControlsVisible(false);
    };
    private final Runnable frameRepeatRunnable = new Runnable() {
        @Override public void run() {
            if (!frameRepeatActive || mode != MODE_FRAME || uiSource == null) return;
            frameRepeatLong = true;
            // Never queue over an undecoded frame: exact sequence is more important than
            // pretending to hit a cadence the decoder cannot sustain.
            if (!decodeRunning.get() && displayedIndex == requestedIndex) {
                manualStep(frameRepeatDelta);
                ui.postDelayed(this, frameRepeatDelayMs());
            } else {
                ui.postDelayed(this, 8L);
            }
        }
    };

    // ══════════════════════════════════════════════════════════════ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        buildRoot();
        restoreLibrary();
        showHome();
        if (savedInstanceState != null) {
            String s = savedInstanceState.getString(STATE_URI);
            if (s != null) {
                pendingMode = savedInstanceState.getInt(STATE_MODE, MODE_HOME);
                openVideo(Uri.parse(s), savedInstanceState.getLong(STATE_POS, 0L), false);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (currentUri != null) {
            outState.putString(STATE_URI, currentUri.toString());
            outState.putLong(STATE_POS, capturePositionUs());
            outState.putInt(STATE_MODE, mode);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // v2.4.4: stale `landscape` field ki jagah ASLI orientation se compare —
        // device rotate par Frame UI foran rebuild (nav/panel galat orientation
        // mein side/klip nahi honge).
        if (mode == MODE_FRAME && frameBuiltLandscape != isLandscapeNow()) {
            buildFrameUi();
        }
        rootFrame.requestApplyInsets();
    }

    @Override
    protected void onPause() {
        saveResumePosition();
        stopAutoPlay();
        if (slowActive) slowPlaying = false;
        if (videoView != null && playerPrepared && videoView.isPlaying()) {
            videoView.pause();
            updatePlayerPlayButton();
        }
        if (slowActive) { slowPlaying = false; ui.removeCallbacks(slowFrameTick); }
        if (videoView != null) videoView.glPause();   // v2.4.6 PHASE 2 (audit fix 5)
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // v2.4.6 PHASE 2 (audit fix 5): home/back/screen-off ke baad GL restart —
        // black/frozen screen na ho; surface recreate par player re-attach hota hai.
        if (videoView != null) videoView.glResume();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // v2.4.6 PHASE 3 (audit fix 2): low memory par frame cache foran chhota
        if (level >= TRIM_MEMORY_RUNNING_LOW && frameCache != null) frameCache.trimAll();
        if (level >= TRIM_MEMORY_COMPLETE) BitmapPool.clear();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        autoPlaying = false;
        releasePlayer();
        super.onDestroy();
        // V2.0: shut the frame cache down (cancels prefetch, closes its decoder)
        if (frameCache != null) {
            frameCache.close();
            frameCache = null;
        }
        BitmapPool.clear();
        getDecoder().execute(() -> {
            FrameSource s = source;
            source = null;
            if (s != null) s.close();
        });
        shutdownExecutors();
    }

    /**
     * v2.4.6 PHASE 3 (audit fix 1): shutdown BACKGROUND thread par — pehle main
     * thread awaitTermination(3s) × 4 = 12s ANR risk tha.  Queued codec closes
     * ab bhi complete hote hain (daemon nahi), bas UI block nahi hota.
     */
    private void shutdownExecutors() {
        final ExecutorService op = opener, dec = decoder, sav = saver, slow = slowDecoder;
        opener = null; decoder = null; saver = null; slowDecoder = null;
        Thread t = new Thread(() -> {
            awaitShutdown(op); awaitShutdown(dec); awaitShutdown(sav); awaitShutdown(slow);
            thumbnailWorkers.shutdownNow();
        }, "TN-Shutdown");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void awaitShutdown(ExecutorService pool) {
        if (pool == null) return;
        pool.shutdown();
        try {
            if (!pool.awaitTermination(3L, TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && immersive()) setSystemBarsHidden(true);
    }

    // ══════════════════════════════════════════════════ helpers / dp / draw

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private boolean immersive() {
        return fullscreen && mode != MODE_HOME;
    }

    /** v2.4.4: stale flag nahi — ASLI configuration orientation (Notes #7/#8/#9 fix). */
    private boolean isLandscapeNow() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    // ── drawable factories ─────────────────────────────────────────────────

    /** Rounded rect with solid fill + optional stroke. */
    private GradientDrawable roundRect(int fill, int radius, int strokeW, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radius));
        if (strokeW > 0) d.setStroke(dp(strokeW), strokeColor);
        return d;
    }

    /** Horizontal linear-gradient rounded rect. */
    private GradientDrawable gradRect(int colorA, int colorB, int radius) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{colorA, colorB});
        d.setCornerRadius(dp(radius));
        return d;
    }

    /** V2.0.7: sheet-style panel with rounded TOP corners only (bottom control bars). */
    private GradientDrawable roundTopRect(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        float r = dp(radius);
        d.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return d;
    }

    /** Glass card: semi-transparent fill + white border. */
    private GradientDrawable glassRect(int radius) {
        return roundRect(COLOR_GLASS, radius, 1, COLOR_BORDER);
    }

    // ── text / button factories ────────────────────────────────────────────

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Plain dark glass button — 48dp touch target, 13sp medium, ripple feedback. */
    private Button makeButton(String label, int heightDp, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(COLOR_TEXT);
        b.setTextSize(13);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        int h = Math.max(heightDp, 48);                 // accessible touch target (Material 48dp)
        b.setMinHeight(dp(h));
        b.setMinimumHeight(dp(h));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        b.setElevation(0f);
        android.content.res.ColorStateList ripple =
                android.content.res.ColorStateList.valueOf(0x33FFFFFF);
        b.setBackground(new android.graphics.drawable.RippleDrawable(
                ripple, roundRect(0xFF0D1B2E, 14, 1, 0xFF1E3A5F), null));
        b.setOnClickListener(l);
        b.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                if (mode == MODE_PLAYER) ui.removeCallbacks(playerControlsAutoHide);
                if (mode == MODE_FRAME) ui.removeCallbacks(frameControlsAutoHide);
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                if (mode == MODE_PLAYER && controlsVisible) schedulePlayerControlsHide();
                if (mode == MODE_FRAME && frameControlsVisible && !frameRepeatActive) scheduleFrameControlsHide();
            }
            return false;
        });
        return b;
    }

    /** Gradient accent button (e.g. PLAY, SAVE FRAME). */
    private Button makeGradButton(String label, int heightDp,
                                   int colorA, int colorB, View.OnClickListener l) {
        Button b = makeButton(label, heightDp, l);
        b.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                gradRect(colorA, colorB, 14), null));
        // WCAG: bright fills get dark text, deep fills get white — auto, per gradient.
        b.setTextColor(luminance(colorA) > 0.30 ? COLOR_ON_ACCENT : Color.WHITE);
        return b;
    }

    /** WCAG relative luminance of an ARGB color (0 = black, 1 = white). */
    private static double luminance(int color) {
        double r = Color.red(color) / 255.0, g = Color.green(color) / 255.0, bl = Color.blue(color) / 255.0;
        r = r <= 0.03928 ? r / 12.92 : Math.pow((r + 0.055) / 1.055, 2.4);
        g = g <= 0.03928 ? g / 12.92 : Math.pow((g + 0.055) / 1.055, 2.4);
        bl = bl <= 0.03928 ? bl / 12.92 : Math.pow((bl + 0.055) / 1.055, 2.4);
        return 0.2126 * r + 0.7152 * g + 0.0722 * bl;
    }

    private LinearLayout.LayoutParams weight(int heightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(Math.max(heightDp, 48)), 1f);
        lp.setMargins(dp(2), dp(3), dp(2), dp(3));
        return lp;
    }

    // ── seekbar styling ────────────────────────────────────────────────────

    private void styleSeekBar(SeekBar s, int accentColor) {
        // Thumb tint
        if (Build.VERSION.SDK_INT >= 16) {
            GradientDrawable thumb = new GradientDrawable();
            thumb.setShape(GradientDrawable.OVAL);
            thumb.setColor(accentColor);
                thumb.setSize(dp(20), dp(20));   // accessible thumb size
            s.setThumb(thumb);
        }
    }

    // ── top bar ────────────────────────────────────────────────────────────

    /**
     * Builds the translucent top bar:
     *   [● MODE LABEL pill]  ──spacer──  [title]  ──spacer──  [⋮]
     */
    private LinearLayout buildTopBar(String modeLabel, int accentA, int accentB,
                                     boolean translucent, View.OnClickListener menuClick) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(translucent ? 0xCC020810 : 0xFF070F1C);
        bar.setPadding(dp(6), dp(4), dp(6), dp(4));

        Button back = makeButton("‹", 36, v -> onBackPressed());
        back.setTextSize(22);
        back.setContentDescription("Back");
        registerControl("top_back", back);   // Customize Controls: "Top Bar: Back"
        bar.addView(back, new LinearLayout.LayoutParams(dp(40), dp(36)));

        TextView title = text(currentName == null ? modeLabel.replace("● ", "") : currentName,
                12, COLOR_TEXT, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, dp(36), 1f);
        titleLp.leftMargin = dp(6);
        titleLp.rightMargin = dp(6);
        bar.addView(title, titleLp);

        Button more = makeButton("⋮", 36, menuClick);
        more.setTextSize(20);
        more.setContentDescription("More options");
        registerControl("top_menu", more);   // Customize Controls: "Top Bar: ⋮ Menu"
        bar.addView(more, new LinearLayout.LayoutParams(dp(40), dp(36)));
        return bar;
    }

    // ══════════════════════════════════════════════════════════ root layout

    @SuppressWarnings("deprecation")
    private void buildRoot() {
        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(COLOR_BG);

        modeContainer = new FrameLayout(this);
        rootFrame.addView(modeContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Glowing spinner
        busyView = new ProgressBar(this);
        busyView.setVisibility(View.GONE);
        rootFrame.addView(busyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        rootFrame.setOnApplyWindowInsetsListener((v, insets) -> {
            if (immersive()) {
                v.setPadding(0, 0, 0, 0);
            } else if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(i.left, i.top, i.right, i.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(rootFrame);
    }

    // ══════════════════════════════════════════════════════════════════ HOME

    private void showHome() {
        if (mode == MODE_PLAYER || mode == MODE_FRAME) syncPositionUs = capturePositionUs();
        stopAutoPlay();
        releasePlayer();
        ui.removeCallbacks(frameControlsAutoHide);
        ui.removeCallbacks(frameRepeatRunnable);
        frameRepeatActive = false;
        mode = MODE_HOME;
        fullscreen = false;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        setSystemBarsHidden(false);
        viewer = null;
        modeContainer.removeAllViews();

        // V2.0.7 full-body layout: hero header + 2×2 mode card grid + settings footer.
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(20), dp(32), dp(20), dp(28));
        root.setBackgroundColor(COLOR_BG);
        scroll.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("TRUE NAIN", 34, COLOR_TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setLetterSpacing(0.12f);
        root.addView(title);

        TextView sub = text("OFFLINE VIDEO PLAYER • FRAME INSPECTOR", 10, COLOR_MUTED, true);
        sub.setGravity(Gravity.CENTER);
        sub.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(8); subLp.bottomMargin = dp(6);
        root.addView(sub, subLp);

        android.view.View divider = new android.view.View(this);
        divider.setBackgroundColor(COLOR_BORDER);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(dp(56), dp(2));
        divLp.topMargin = dp(10); divLp.bottomMargin = dp(24);
        root.addView(divider, divLp);

        // Row 1 — playback modes
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(homeCard("▶", "MEDIA PLAYER", "Playback · speeds", COLOR_PLAYER_A, COLOR_PLAYER_B,
                v -> { preferredLibraryMode = MODE_PLAYER; showLibrary(); }));
        row1.addView(homeCard("▣", "FRAME MODE", "Frame inspection", COLOR_FRAME_A, COLOR_FRAME_B,
                v -> { preferredLibraryMode = MODE_FRAME; showLibrary(); }));
        root.addView(row1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(124)));

        // Row 2 — tools
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(124));
        row2Lp.topMargin = dp(12);
        row2.addView(homeCard("▤", "EXTRACTOR", "Batch frame export", COLOR_FRAME_A, COLOR_FRAME_B,
                v -> { preferredLibraryMode = MODE_EXTRACTOR; showLibrary(); }));
        row2.addView(homeCard("✂", "VIDEO EDITOR", "Trim & merge clips", COLOR_SAVE_A, COLOR_SAVE_B,
                v -> { pendingOpenTrim = true; preferredLibraryMode = MODE_PLAYER; showLibrary(); }));
        root.addView(row2, row2Lp);

        // Library — full-width card
        LinearLayout libraryCard = homeCard("▦", "VIDEO LIBRARY", "All your videos",
                COLOR_PLAYER_A, COLOR_PLAYER_B, v -> showLibrary());
        LinearLayout.LayoutParams libLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(76));
        libLp.topMargin = dp(12);
        root.addView(libraryCard, libLp);

        // Settings — full-width dark glass card
        LinearLayout settings = homeCard("⚙", "CUSTOMIZE CONTROLS", "Buttons · layout · order",
                COLOR_FRAME_B, COLOR_BG2, v -> showControlSettings());
        LinearLayout.LayoutParams setLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(76));
        setLp.topMargin = dp(12);
        root.addView(settings, setLp);

        TextView hint = text("100% offline · no internet · no ads", 11, COLOR_MUTED, false);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(20);
        root.addView(hint, hintLp);

        modeContainer.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rootFrame.requestApplyInsets();
    }

    /** V2.0.7: large tappable home card — icon + title + subtitle on a gradient tile. */
    private LinearLayout homeCard(String icon, String label, String subtitle,
                                   int colorA, int colorB, View.OnClickListener l) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(10), dp(12), dp(10), dp(12));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(0, -1, 1f);
        cardLp.setMargins(dp(6), 0, dp(6), 0);
        card.setLayoutParams(cardLp);
        card.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                gradRect(colorA, colorB, 20), null));
        card.setOnClickListener(l);

        TextView ic = text(icon, 26, Color.WHITE, true);
        ic.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        icLp.bottomMargin = dp(6);
        card.addView(ic, icLp);

        TextView lbl = text(label, 13, Color.WHITE, true);
        lbl.setGravity(Gravity.CENTER);
        lbl.setLetterSpacing(0.04f);
        card.addView(lbl);

        TextView sub = text(subtitle, 10, 0xE6FFFFFF, false);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(3);
        card.addView(sub, subLp);
        return card;
    }

    private void restoreLibrary() {
        android.content.SharedPreferences pref = getSharedPreferences("library_v2", MODE_PRIVATE);
        int count = Math.min(1000, pref.getInt("count", 0));
        for (int i = 0; i < count; i++) {
            String uriText = pref.getString("uri_" + i, null);
            if (uriText != null) {
                Uri u = Uri.parse(uriText);
                if (!videoLibrary.contains(u)) videoLibrary.add(u);
            }
        }
    }

    private void persistLibrary() {
        android.content.SharedPreferences.Editor e = getSharedPreferences("library_v2", MODE_PRIVATE).edit();
        int n = Math.min(1000, videoLibrary.size());
        e.putInt("count", n);
        for (int i = 0; i < n; i++) e.putString("uri_" + i, videoLibrary.get(i).toString());
        e.apply();
    }

    /** Query visible device videos without touching videos owned by other apps. */
    private void scanDeviceLibrary() {
        if (libraryScanRunning) return;
        final String permission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{permission}, REQ_LIBRARY_PERMISSION);
            return;
        }
        libraryScanRunning = true;
        getSaver().execute(() -> {
            final ArrayList<Uri> found = new ArrayList<>();
            try (Cursor c = getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Video.Media._ID}, null, null,
                    MediaStore.Video.Media.DATE_ADDED + " DESC")) {
                if (c != null) while (c.moveToNext() && found.size() < 5000) {
                    found.add(android.content.ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)));
                }
            } catch (RuntimeException ignored) {
                // Picker-added entries are still available if media scan is restricted.
            }
            ui.post(() -> {
                libraryScanRunning = false;
                deviceScanDone = true;
                deviceVideos.clear();
                deviceVideos.addAll(found);
                if (mode == MODE_LIBRARY && !isFinishing() && !isDestroyed()) showLibrary();
            });
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_LIBRARY_PERMISSION) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) scanDeviceLibrary();
            else Toast.makeText(this, "Library permission denied; use ADD VIDEO", Toast.LENGTH_LONG).show();
        }
    }

    private boolean controlEnabled(String name) {
        return getSharedPreferences("control_custom", MODE_PRIVATE).getBoolean(name, true);
    }
    private void showControlSettings() {
        // V2.0: every button across Media Player, Frame Mode and Frame Extractor
        // is now toggleable from the same dialog (home "CUSTOMIZE CONTROLS").
        String[] labels = {
                "Top Bar: Back (‹)", "Top Bar: Menu (⋮)",
                "Player: Play/Pause",
                "Player: Frame Shortcut",
                "Frame: Step Buttons (±1/±10)", "Frame: Play/Pause", "Frame: SAVE Button",
                "Frame: FPS Button", "Frame: Zoom Buttons",
                "Extractor: Export All", "Extractor: Custom Range",
                "Extractor: Photo Rotate", "Extractor: Library Button"};
        String[] keys = {
                "top_back", "top_menu",
                "player_play", "player_frame",
                "frame_nav", "frame_play", "frame_save", "frame_fps", "frame_zoom",
                "extract_all", "extract_custom", "extract_rotate", "extract_library"};
        boolean[] checked = new boolean[keys.length];
        for (int i = 0; i < keys.length; i++) checked[i] = controlEnabled(keys[i]);
        new AlertDialog.Builder(this).setTitle("Customize Controls")
                .setMultiChoiceItems(labels, checked, (d, which, value) -> checked[which] = value)
                .setPositiveButton("Save", (d, w) -> {
                    android.content.SharedPreferences.Editor e = getSharedPreferences("control_custom", MODE_PRIVATE).edit();
                    for (int i = 0; i < keys.length; i++) e.putBoolean(keys[i], checked[i]);
                    e.apply();
                    refreshControls(); // Preserve the current playback and frame position; never rebuild the player.
                }).setNegativeButton("Cancel", null).show();
    }

    private void showLibrary() {
        stopAutoPlay();
        releasePlayer();
        ui.removeCallbacks(playerTick);
        ui.removeCallbacks(frameControlsAutoHide);
        mode = MODE_LIBRARY;
        fullscreen = false;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        setSystemBarsHidden(false);
        modeContainer.removeAllViews();
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(18), dp(16), dp(12));
        column.setBackgroundColor(COLOR_BG);
        TextView title = text("VIDEO LIBRARY", 22, COLOR_TEXT, true);
        column.addView(title);
        TextView note = text("Choose video for " + (preferredLibraryMode == MODE_FRAME ? "FRAME MODE" : preferredLibraryMode == MODE_EXTRACTOR ? "FRAME EXTRACTOR" : "MEDIA PLAYER"), 12, COLOR_MUTED, false);
        column.addView(note);
        Button add = makeButton("+ ADD VIDEO FROM DEVICE", 50, v -> {
            pendingMode = preferredLibraryMode;
            openVideoPicker();
        });
        column.addView(add);
        Button rescan = makeButton("⟳ REFRESH DEVICE VIDEOS", 44, v -> scanDeviceLibrary());
        column.addView(rescan);
        if (!deviceScanDone && !libraryScanRunning) scanDeviceLibrary();
        Button change = makeButton("SWITCH TO " + (preferredLibraryMode == MODE_FRAME ? "MEDIA PLAYER" : "FRAME MODE"), 44,
                v -> { preferredLibraryMode = preferredLibraryMode == MODE_PLAYER ? MODE_FRAME : MODE_PLAYER; showLibrary(); });
        column.addView(change);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL);
        ArrayList<Uri> displayedLibrary = new ArrayList<>(videoLibrary);
        for (Uri scanned : deviceVideos) if (!displayedLibrary.contains(scanned)) displayedLibrary.add(scanned);
        for (int i = 0; i < displayedLibrary.size(); i++) {
            final Uri uri = displayedLibrary.get(i);
            String label = queryDisplayName(uri);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(8), dp(7), dp(8), dp(7));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(COLOR_BG2);
            bg.setCornerRadius(dp(12));
            bg.setStroke(dp(1), COLOR_BORDER);
            card.setBackground(bg);
            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackgroundColor(0xFF162238);
            card.addView(thumb, new LinearLayout.LayoutParams(dp(112), dp(70)));
            TextView name = text(label, 14, COLOR_TEXT, true);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, -2, 1f);
            nameLp.leftMargin = dp(12);
            card.addView(name, nameLp);
            card.setOnClickListener(v -> {
                libraryScrollY = scroll.getScrollY();
                pendingMode = preferredLibraryMode;
                if (!videoLibrary.contains(uri)) { videoLibrary.add(uri); persistLibrary(); }
                currentVideoIndex = videoLibrary.indexOf(uri);
                openVideo(uri, 0L, true);
            });
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
            cardLp.bottomMargin = dp(8);
            items.addView(card, cardLp);
            loadLibraryThumbnail(uri, thumb);
        }
        if (displayedLibrary.isEmpty()) items.addView(text("No videos visible. Grant video access or tap ADD VIDEO.", 13, COLOR_MUTED, false));
        scroll.addView(items);
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        Button home = makeButton("‹ MAIN MENU", 46, v -> showHome());
        column.addView(home);
        modeContainer.addView(column, new FrameLayout.LayoutParams(-1, -1));
        scroll.post(() -> scroll.scrollTo(0, libraryScrollY));
    }

    private void loadLibraryThumbnail(Uri uri, ImageView target) {
        final String key = uri.toString();
        target.setTag(key);
        Bitmap cached = thumbCache.get(key);
        if (cached != null && !cached.isRecycled()) { target.setImageBitmap(cached); return; }
        thumbnailWorkers.execute(() -> {
            Bitmap bitmap = null;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(getApplicationContext(), uri);
                // Scaled thumbnails avoid allocating a video-sized bitmap for every row.
                if (Build.VERSION.SDK_INT >= 27) {
                    bitmap = retriever.getScaledFrameAtTime(500000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 224, 140);
                } else {
                    Bitmap raw = retriever.getFrameAtTime(500000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (raw != null) {
                        bitmap = Bitmap.createScaledBitmap(raw, 224, 140, true);
                        if (raw != bitmap) raw.recycle();
                    }
                }
            } catch (Exception ignored) { }
            finally { try { retriever.release(); } catch (Exception ignored) { } }
            if (bitmap != null) {
                final Bitmap ready = bitmap;
                ui.post(() -> {
                    thumbCache.put(key, ready);
                    if (key.equals(target.getTag()) && target.isAttachedToWindow()) target.setImageBitmap(ready);
                });
            }
        });
    }

    private void chooseMode(int target) {
        if (loading) {
            Toast.makeText(this, "Loading video…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentUri == null || uiSource == null) {
            pendingMode = target;
            openVideoPicker();
            return;
        }
        enterMode(target, syncPositionUs, false);
    }

    // ══════════════════════════════════════════════════════ mode switching

    private long capturePositionUs() {
        if (mode == MODE_PLAYER && slowActive && uiSource != null)
            return uiSource.ptsUs[Math.max(0, Math.min(slowFrameIndex, uiSource.frameCount - 1))];
        if (mode == MODE_PLAYER && videoView != null && playerPrepared)
            return Math.max(0, videoView.getCurrentPosition()) * 1000L;
        if (mode == MODE_FRAME && uiSource != null) {
            int i = displayedIndex >= 0 ? displayedIndex : requestedIndex;
            i = Math.max(0, Math.min(uiSource.frameCount - 1, i));
            return uiSource.ptsUs[i];
        }
        return syncPositionUs;
    }

    private void saveResumePosition() {
        if (currentUri == null) return;
        long pos = capturePositionUs();
        getSharedPreferences("resume_positions", MODE_PRIVATE)
                .edit().putLong(currentUri.toString(), Math.max(0L, pos)).apply();
    }

    private long savedResumePositionUs() {
        if (currentUri == null) return 0L;
        return getSharedPreferences("resume_positions", MODE_PRIVATE)
                .getLong(currentUri.toString(), 0L);
    }

    private void resumeSavedPosition() {
        if (currentUri == null || uiSource == null) return;
        long pos = savedResumePositionUs();
        if (pos <= 0L) {
            Toast.makeText(this, "No saved resume position", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mode == MODE_PLAYER && mediaPlayer != null && playerPrepared) {
            long ms = Math.min(videoView.getDuration(), pos / 1000L);
            mediaPlayer.seekTo(ms, MediaPlayer.SEEK_CLOSEST);
            playerSeek.setProgress((int) ms);
            playerCurrent.setText(fmtClock(ms));
        } else if (mode == MODE_FRAME) {
            goTo(PlaybackMath.nearestIndex(uiSource.ptsUs, pos));
        }
    }

    private void switchMode(int target) {
        if (loading || uiSource == null || target == mode) return;
        enterMode(target, capturePositionUs(), false);
    }

    private void enterMode(int target, long positionUs, boolean freshVideo) {
        stopAutoPlay();
        releasePlayer();
        fineRotateMode = false;   // v2.4.5: mode switch par rotate mode OFF
        mode = target;
        syncPositionUs = positionUs;
        if (target == MODE_PLAYER) showPlayer(freshVideo);
        else showFrame();
        setSystemBarsHidden(immersive());
        rootFrame.requestApplyInsets();
    }

    // ══════════════════════════════════════════ manual landscape / fullscreen

    private void toggleLandscape() { setLandscape(!landscape); }

    private void setLandscape(boolean on) {
        landscape = on;
        setRequestedOrientation(on
                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        if (mode == MODE_FRAME && frameBuiltLandscape != on) buildFrameUi();
        refreshOrientationButtons();
        rootFrame.requestApplyInsets();
    }


    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        setSystemBarsHidden(fullscreen);
        // Fullscreen only changes system chrome. It never forces orientation or playback state.
        rootFrame.requestApplyInsets();
        // v2.4.5 (user request): Frame fullscreen par navig CONTROLS HIDE — clean
        // frame, koi buttons nazar na aaye.  Tap karne par wapas aate hain
        // (compact size mein).  Gestures (double-tap ±1, hold ±5) chalte rehte hain.
        if (mode == MODE_FRAME) {
            buildFrameUi();
            if (fullscreen) setFrameControlsVisible(false);
        }
    }

    private void applySourceOrientation() {
        if (uiSource == null) return;
        setLandscape(uiSource.sourceIsLandscape());
    }

    private String orientationLabel() { return landscape ? "↕ VIEW" : "↔ VIEW"; }

    private void refreshOrientationButtons() {
        // ↻ VIEW buttons removed (⋮ > View covers it) — kept as a no-op for old call sites.
        if (playerOrientButton != null) playerOrientButton.setText("↻ VIEW");
        if (frameOrientButton  != null) frameOrientButton.setText("↻ VIEW");
    }

    private void showChangeViewDialog() {
        if (mode == MODE_PLAYER) ui.removeCallbacks(playerControlsAutoHide);
        if (mode == MODE_FRAME) ui.removeCallbacks(frameControlsAutoHide);
        // Orientation State spec: Fullscreen aur Change View alag controls hain, aur
        // ye dialog current view clearly mark karta hai (source-following vs manual).
        boolean followingSource = uiSource != null && landscape == uiSource.sourceIsLandscape();
        String[] items = {
                followingSource ? "● Source orientation  (current)" : "Source orientation",
                !followingSource && !landscape ? "● Portrait  (current)" : "Portrait",
                !followingSource && landscape ? "● Landscape  (current)" : "Landscape"};
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Change View")
                .setItems(items, (d, which) -> {
                    if (which == 0) applySourceOrientation();
                    else setLandscape(which == 2);
                    if (mode == MODE_PLAYER) applyPlayerViewTransform();
                    if (mode == MODE_FRAME) resetFrameControlsTimer();
                })
                .create();
        dialog.setOnDismissListener(d -> {
            if (mode == MODE_PLAYER && controlsVisible) schedulePlayerControlsHide();
            if (mode == MODE_FRAME && frameControlsVisible) scheduleFrameControlsHide();
        });
        dialog.show();
    }

    private void applyPlayerViewTransform() {
        if (videoView == null) return;
        float base = 1f;
        if (playerPrepared && mediaPlayer != null && videoView.getWidth() > 0 && videoView.getHeight() > 0) {
            int vw = Math.max(1, mediaPlayer.getVideoWidth());
            int vh = Math.max(1, mediaPlayer.getVideoHeight());
            boolean swap = playerContentRotation == 90 || playerContentRotation == 270;
            if (swap) { int t = vw; vw = vh; vh = t; }
            float videoAspect = vw / (float) vh;
            float viewAspect = videoView.getWidth() / (float) Math.max(1, videoView.getHeight());
            if (playerDisplayMode == ZoomableImageView.MODE_FILL) {
                base = videoAspect > viewAspect ? videoAspect / viewAspect : viewAspect / videoAspect;
            } else if (playerDisplayMode == ZoomableImageView.MODE_ONE_TO_ONE) {
                float fitW = videoAspect > viewAspect ? videoView.getWidth() : videoView.getHeight() * videoAspect;
                base = vw / Math.max(1f, fitW);
            }
        }
        // Never shrink the underlying VideoView below its fixed viewport: that reveals
        // empty edges and makes the border look as if it has moved.
        float total = Math.max(1f, Math.min(maxPlayerZoom(), base * playerZoom));
        // v2.4.8: GL ko view ka ASLI zoom (total) — ek hi number view+GL dono par.
        videoView.setLiveZoom(total);
        // v2.4.8: ek hi cap — ZC ka maxZoom bhi isi source-pixel cap par (warna ZC zoom
        // aage barhta rahta hai aur view ruk jata hai → pinch ke baad jump).
        if (playerZc != null && Math.abs(maxPlayerZoom() - playerZc.getMaxZoom()) > 0.01f)
            playerZc.setMaxZoom(maxPlayerZoom());
        videoView.setScaleX(total);
        videoView.setScaleY(total);
        videoView.setRotation(playerContentRotation + playerFineRotation);   // v2.4.4: 90-steps + fine angle
        float w = videoView.getWidth(), h = videoView.getHeight();
        // Scale is applied only to video content; navigation controls never transform.
        // Prevent empty gaps outside the fixed viewport when zooming/panning.
        // Content-based pan clamp: pan video ke ASLI edges tak hi — zoom+pan par
        // black area nazar nahi aayega (pehle container-based clamp tha jis se
        // letterbox/band ke bahar pan karke black aata tha — user complaint).
        int cw = Math.round(w), ch = Math.round(h);
        if (uiSource != null && uiSource.width > 0 && uiSource.height > 0) {
            boolean swap = uiSource.rotation == 90 || uiSource.rotation == 270;
            int sw = swap ? uiSource.height : uiSource.width;
            int sh = swap ? uiSource.width : uiSource.height;
            float viewAspect = (float) w / Math.max(1, h);
            float videoAspect = (float) sw / Math.max(1, sh);
            if (videoAspect > viewAspect) { cw = Math.round(w); ch = Math.max(1, Math.round(w / videoAspect)); }
            else { ch = Math.round(h); cw = Math.max(1, Math.round(h * videoAspect)); }
        }
        float rotatedW = (playerContentRotation % 180 == 0) ? cw : ch;
        float rotatedH = (playerContentRotation % 180 == 0) ? ch : cw;
        float maxX = Math.max(0f, (rotatedW * total - w) * 0.5f);
        float maxY = Math.max(0f, (rotatedH * total - h) * 0.5f);
        playerPanX = Math.max(-maxX, Math.min(maxX, playerPanX));
        playerPanY = Math.max(-maxY, Math.min(maxY, playerPanY));
        videoView.setTranslationX(playerPanX);
        videoView.setTranslationY(playerPanY);
        if (slowImage != null) {
            slowImage.setScaleX(total); slowImage.setScaleY(total);
            slowImage.setRotation(playerContentRotation);
            slowImage.setTranslationX(playerPanX); slowImage.setTranslationY(playerPanY);
        }
        playerTotalScale = total;
        refreshZoomOverlay(total);
    }

    /**
     * Hardening spec S1.3: dynamic max-zoom cap — max(4x, 2.5x past native 1:1),
     * hard-clamped to 12x.  Low-res (480p/720p) videos therefore cannot over-magnify
     * into blocky nearest-neighbour artifacts; 4K still allows deep inspection.
     */
    private float maxPlayerZoom() {
        float cap = 8f;
        if (videoView != null && videoView.getWidth() > 0 && uiSource != null && uiSource.width > 0) {
            cap = Math.max(4f, uiSource.width / (float) videoView.getWidth() * 2.5f);
        }
        return Math.min(12f, cap);
    }

    private void resetPlayerZoom() {
        playerZoom = 1f;
        playerPanX = playerPanY = 0f;
        playerTotalScale = 1f;
        // v2.4.4: zoom overlay bhi saaf karo — warna paused+zoomed par purana
        // crop/zoom overlay dikhya reh jata tha (user: "Reset par koi faida nahi").
        if (zoomOverlay != null) zoomOverlay.setVisibility(View.GONE);
        if (zoomOverlayBmp != null && !zoomOverlayBmp.isRecycled()) BitmapPool.release(zoomOverlayBmp);
        zoomOverlayBmp = null;
        zoomOverlayKey = Integer.MIN_VALUE;
        zoomOverlayCropMode = false;
        playerFineRotation = 0f;   // v2.4.4: reset par fine angle bhi seedha
        applyPlayerViewTransform();
    }

    @SuppressWarnings("deprecation")
    private void setSystemBarsHidden(boolean hidden) {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                if (hidden) {
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    c.hide(WindowInsets.Type.systemBars());
                } else {
                    c.show(WindowInsets.Type.systemBars());
                }
            }
        } else {
            View d = w.getDecorView();
            if (hidden) {
                d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            } else {
                d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = hidden
                    ? (Build.VERSION.SDK_INT >= 30
                            ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                            : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES)
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            w.setAttributes(lp);
        }
    }

    // ══════════════════════════════════ opening videos (selected video only)

    @SuppressWarnings("deprecation")
    private void openVideoPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_OPEN_VIDEO);
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_GALLERY_ROTATE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                promptGalleryRotateAngle(data.getData());
            }
            return;
        }
        if (requestCode != REQ_OPEN_VIDEO) return;
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (RuntimeException ignored) {}
            if (!videoLibrary.contains(uri)) videoLibrary.add(uri);
            persistLibrary();
            currentVideoIndex = videoLibrary.indexOf(uri);
            openVideo(uri, 0L, true);
        } else {
            // Cancel/back from the picker must keep the currently open video and mode unchanged.
            if (mode == MODE_LIBRARY) pendingMode = preferredLibraryMode;
            else if (mode == MODE_HOME && currentUri == null) pendingMode = MODE_HOME;
            else pendingMode = mode;
        }
    }

    private void openAnotherVideo() {
        pendingMode = mode;
        openVideoPicker();
    }

    private void previousVideo() {
        if (videoLibrary.isEmpty()) return;
        if (currentVideoIndex < 0) currentVideoIndex = Math.max(0, videoLibrary.indexOf(currentUri));
        currentVideoIndex = (currentVideoIndex - 1 + videoLibrary.size()) % videoLibrary.size();
        pendingMode = MODE_PLAYER;
        openVideo(videoLibrary.get(currentVideoIndex), 0L, true);
    }

    private void nextVideo() {
        if (videoLibrary.isEmpty()) return;
        if (currentVideoIndex < 0) currentVideoIndex = Math.max(0, videoLibrary.indexOf(currentUri));
        currentVideoIndex = (currentVideoIndex + 1) % videoLibrary.size();
        pendingMode = MODE_PLAYER;
        openVideo(videoLibrary.get(currentVideoIndex), 0L, true);
    }

    private void togglePlayerOrientation() {
        int next = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        setRequestedOrientation(next);
    }

    private void openTrim() {
        if (currentUri == null) return;
        // Trim = SAMSUNG ka original Video Editor (user decision 2026-11-15 — frame-
        // accurate cutting wahan pehle se hai).  Pehle Samsung ke editor packages,
        // phir koi bhi ACTION_EDIT handler; dono fail ho to hamari built-in
        // TrimActivity fallback — feature kabhi dead-end nahi hoga.
        if (launchExternalVideoEditor(currentUri)) return;
        Intent i = new Intent(this, TrimActivity.class);
        i.setData(currentUri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(i);
        Toast.makeText(this,
                "Samsung editor unavailable — built-in trim khula", Toast.LENGTH_SHORT).show();
    }

    /** Samsung Video Editor (ya koi bhi video editor) ko video ke sath khoolta hai. */
    private boolean launchExternalVideoEditor(Uri uri) {
        for (String pkg : new String[]{
                "com.sec.android.app.videoeditor",       // Samsung Video Editor
                "com.sec.android.app.veedit",            // older Samsung editor
                "com.samsung.android.videoeditor"}) {    // newer One UI package
            try {
                Intent explicit = new Intent(Intent.ACTION_EDIT);
                explicit.setDataAndType(uri, "video/*");
                explicit.setPackage(pkg);
                explicit.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(explicit);
                return true;
            } catch (RuntimeException ignored) {}       // package nahi mila — agla try
        }
        try {
            Intent edit = new Intent(Intent.ACTION_EDIT);
            edit.setDataAndType(uri, "video/*");
            edit.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(edit);
            return true;
        } catch (RuntimeException ignored) {}           // koi editor nahi — fallback
        return false;
    }


    private void openVideo(final Uri uri, final long startUs, final boolean fresh) {
        stopAutoPlay();
        if (videoView != null && playerPrepared && videoView.isPlaying()) {
            videoView.pause();
            updatePlayerPlayButton();
        }
        loading = true;
        busyView.setVisibility(View.VISIBLE);
        getOpener().execute(() -> {
            FrameSource opened = null;
            String error = null;
            try {
                opened = FrameSource.open(getApplicationContext(), uri);
            } catch (IOException | RuntimeException e) {
                error = e.getMessage();
            }
            final FrameSource result = opened;
            final String err = error;
            FrameSource old = source;
            if (result != null) {
                source = result;
                if (old != null) old.close();
                // V2.0 P0-01: when the full presentation timeline arrives in the
                // background, remap the UI from keyframe-count to real frame count.
                result.setOnFullTimelineReady(() -> ui.post(() -> onFullTimelineReady(result)));
            }
            ui.post(() -> onVideoOpened(uri, startUs, fresh, result, err));
        });
    }

    private void onVideoOpened(Uri uri, long startUs, boolean fresh,
                                FrameSource opened, String error) {
        if (isDestroyed()) return;
        loading = false;
        busyView.setVisibility(View.GONE);
        if (opened == null) {
            Toast.makeText(this,
                    "Could not open video: " + (error == null ? "unknown error" : error),
                    Toast.LENGTH_LONG).show();
            return;
        }
        currentUri  = uri;
        currentName = queryDisplayName(uri);
        int libIndex = videoLibrary.indexOf(uri);
        if (libIndex >= 0) currentVideoIndex = libIndex;
        uiSource    = opened;
        requestedIndex = 0;
        displayedIndex = -1;
        lastDecodedIndex = -1;
        firstFrameAfterOpen = true;
        // Reset picture rotation for new video
        pictureRotation = 0;
        frameFineRotation = 0f;
        playerFineRotation = 0f;
        // Capture video rotation metadata from source
        videoRotation = uiSource.rotation;
        // V2.0: fresh cache generation per video — old entries and the dedicated
        // prefetch decoder are freed; the last displayed bitmap stays alive.
        Bitmap old = currentBitmap;
        currentBitmap = null;
        if (shownBitmap != null && !shownBitmap.isRecycled()) shownBitmap.recycle();
        shownBitmap = null;
        if (frameCache != null) frameCache.close();
        frameCache = new FrameCacheManager(getApplicationContext());
        frameCache.primeFrameSize(opened.width, opened.height);   // v2.4.6 PHASE 3: real 4K budget
        frameSourceId = System.nanoTime();
        if (old != null && old != savingBitmap && !old.isRecycled()) BitmapPool.release(old);
        playbackFps = Math.min(playbackFps, PlaybackMath.maxManualFps(opened.fps));
        syncPositionUs = startUs;

        applySourceOrientation();

        // V2.0.7: VIDEO EDITOR home card — video khulte hi Trim & Merge screen.
        if (pendingOpenTrim) {
            pendingOpenTrim = false;
            enterMode(MODE_PLAYER, startUs, fresh);
            openTrim();
            return;
        }

        int target = pendingMode;
        pendingMode = MODE_HOME;
        if (target == MODE_PLAYER || target == MODE_FRAME) enterMode(target, startUs, fresh);
        else if (target == MODE_EXTRACTOR) showExtractor();
        else if (target == MODE_LIBRARY) showLibrary();   // v2.4.4: crash/restore ke baad library-mode saved ho to wapas library (home par fallback na ho)
        else showHome();
    }

    private String queryDisplayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {}
        return null;
    }

    // ══════════════════════════════════════════════════════════ MEDIA PLAYER

    private void showPlayer(boolean autoStart) {
        viewer = null;
        controlViews.clear();
        modeContainer.removeAllViews();
        startPlayingOnPrepared = autoStart;
        playerPrepared = false;
        controlsVisible = true;
        stopSlowMotion(true);

        FrameLayout p = new FrameLayout(this);
        p.setBackgroundColor(Color.BLACK);

        videoView = new GradedVideoView(this);
        videoView.setKeepScreenOn(true);
        videoView.setClipToOutline(true);
        // RESTORED (user: "quality look wapas le kar ao") — dual-engine:
        //  ≤1440p → GL pipeline (Quality Look LIVE video par)
        //  2K/4K → DIRECT engine (S9 hardware decode — stuck/error nahi, look nahi)
        boolean bigVideo = uiSource != null
                && (long) uiSource.width * (long) uiSource.height > 2560L * 1440L;
        videoView.setDirectMode(bigVideo);
        p.setClipChildren(true);
        p.setClipToPadding(true);
        p.addView(videoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        slowImage = new ImageView(this);
        slowImage.setBackgroundColor(Color.BLACK);
        slowImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        slowImage.setVisibility(View.GONE);
        p.addView(slowImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        // ±5s double-tap feedback — translucent side pill (« 5s / 5s »), YouTube-style.
        seekFlash = new TextView(this);
        seekFlash.setTextColor(Color.WHITE);
        seekFlash.setTextSize(15);
        seekFlash.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        seekFlash.setBackground(roundRect(0xB3020810, 20, 0, 0));
        seekFlash.setPadding(dp(14), dp(7), dp(14), dp(7));
        seekFlash.setGravity(Gravity.CENTER);
        seekFlash.setElevation(dp(6));
        seekFlash.setVisibility(View.GONE);
        p.addView(seekFlash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // YouTube-style scrub: center floating time pill — 01:24 / 05:10  (+00:12)
        scrubPill = new TextView(this);
        scrubPill.setTextColor(Color.WHITE);
        scrubPill.setTextSize(14);
        scrubPill.setTypeface(Typeface.MONOSPACE);
        scrubPill.setBackground(roundRect(0xB3020810, 22, 0, 0));
        scrubPill.setPadding(dp(16), dp(8), dp(16), dp(8));
        scrubPill.setGravity(Gravity.CENTER);
        scrubPill.setElevation(dp(8));   // center transport icons ke UPAR render ho
        scrubPill.setVisibility(View.GONE);
        p.addView(scrubPill, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // V2.0.8: paused + zoom par VideoView ki surface pixel phati hui dikhti hai —
        // ye overlay usi position ka FULL-RES frame smooth scale karke dikhata hai.
        zoomOverlay = new ImageView(this);
        zoomOverlay.setScaleType(ImageView.ScaleType.FIT_CENTER);
        zoomOverlay.setVisibility(View.GONE);
        p.addView(zoomOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        // Gesture layer: modern player controls.
        // Horizontal drag = scrub, double tap left/right = -/+5s,
        // center single tap = play/pause, long press = temporary 0.25x preview.
        View touch = new View(this);
        final float[] downX = {0f};
        final float[] downY = {0f};
        final float[] downBrightness = {0.5f};
        final int[] downVolume = {0};
        final int[] gestureMode = {0}; // 0 undecided, 1 horizontal seek, 2 vertical adjust
        final long[] dragStartMs = {0L};
        final boolean[] dragged = {false};
        final AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        final GestureDetector gestures = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent e) { return true; }
                    // v2.4.6: single-tap ab ZoomController.Listener.onSingleTap handle karta hai
                    // (dono zoom-states mein consistent — double-toggle bug avoided).
                    @Override public boolean onDoubleTap(MotionEvent e) {
                        if (!gestureLocked) {
                            // YouTube-style zones: left 35% = -5s, right 35% = +5s,
                            // center 30% double-tap = no seek (single-tap toggles controls).
                            float x = e.getX();
                            if (x < touch.getWidth() * 0.35f) {
                                seekPlayerBy(-5000L);
                                flashSeek(false);
                            } else if (x > touch.getWidth() * 0.65f) {
                                seekPlayerBy(5000L);
                                flashSeek(true);
                            }
                        }
                        setPlayerControlsVisible(true);
                        return true;
                    }
                    @Override public void onLongPress(MotionEvent e) {
                        if (!gestureLocked) setHoldSlow(true);
                    }
                });
        final float[] lastPanX = {0f}, lastPanY = {0f};
        // Seek-storm fix: throttle scrub seeks (one per 150 ms, snap to keyframe) and
        // run ONE exact CLOSEST seek when the finger lifts.  Previously every drag
        // MOVE fired a CLOSEST seek — the decoder re-synced from the keyframe each
        // time, so the video stuck and then played (user-reported).
        final long[] lastScrubSeekMs = {0L};
        final long[] pendingScrubTarget = {-1L};
        // v2.4.6 PHASE 1: zoom/pan ab ZoomController ke haath mein (pinch, drag, fling,
        // wheel, settled-sharp).  Ye lambda ab FALLBACK hai — sirf NOT-zoomed wale
        // gestures (tap, ±5s double-tap, scrub, brightness/volume, hold-slow).
        View.OnTouchListener oldPlayerTouch = (v, ev) -> {
            gestures.onTouchEvent(ev);
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                lastPanX[0] = ev.getX(); lastPanY[0] = ev.getY();
                downX[0] = ev.getX();
                downY[0] = ev.getY();
                dragStartMs[0] = videoView != null ? videoView.getCurrentPosition() : 0L;
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                downBrightness[0] = lp.screenBrightness >= 0f ? lp.screenBrightness : 0.5f;
                downVolume[0] = audio != null ? audio.getStreamVolume(AudioManager.STREAM_MUSIC) : 0;
                dragged[0] = false;
                gestureMode[0] = 0;
                lastScrubSeekMs[0] = 0L;
                if (scrubPill != null) scrubPill.setVisibility(View.GONE);
                ui.removeCallbacks(playerControlsAutoHide);
            } else if (a == MotionEvent.ACTION_POINTER_UP) {
                // Re-anchor the remaining finger. Otherwise the next MOVE jumps the viewport.
                int survivor = ev.getActionIndex() == 0 ? 1 : 0;
                if (survivor < ev.getPointerCount()) {
                    lastPanX[0] = ev.getX(survivor);
                    lastPanY[0] = ev.getY(survivor);
                    downX[0] = lastPanX[0]; downY[0] = lastPanY[0];
                }
                gestureMode[0] = 0;
                // A pointer swap is not a scrub or a fresh pan delta.
                dragged[0] = true;
            } else if (a == MotionEvent.ACTION_MOVE && playerPrepared && mediaPlayer != null
                    && !gestureLocked) {
                float dx = ev.getX() - downX[0];
                float dy = ev.getY() - downY[0];
                if (gestureMode[0] == 0 && (Math.abs(dx) > dp(18) || Math.abs(dy) > dp(18))) {
                    gestureMode[0] = (fullscreen && Math.abs(dy) > Math.abs(dx)) ? 2 : 1;
                }
                if (gestureMode[0] == 1 && !slowActive) {
                    // YouTube-style Interactive Scrub Mode: 1 finger horizontal drag.
                    // Full screen width = 60s ya 15% duration (jo chhoti ho), clamped.
                    dragged[0] = true;
                    long dur = videoView.getDuration();
                    long span = Math.max(10_000L, Math.min(60_000L, (long) (dur * 0.15f)));
                    long target = dragStartMs[0] + Math.round((dx / Math.max(1f, v.getWidth())) * span);
                    target = Math.max(0L, Math.min(dur, target));
                    playerSeek.setProgress((int) target);
                    long delta = target - dragStartMs[0];
                    scrubPill.setText(String.format(Locale.US, "%s / %s  (%s%s)",
                            fmtClock(target), fmtClock(dur),
                            delta >= 0 ? "+" : "−", fmtClock(Math.abs(delta))));
                    scrubPill.setVisibility(View.VISIBLE);
                    // Preview seek throttled 120ms (keyframe snap — no decoder storm);
                    // final exact seek runs once on ACTION_UP.
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastScrubSeekMs[0] >= 120L) {
                        lastScrubSeekMs[0] = now;
                        pendingScrubTarget[0] = -1L;
                        mediaPlayer.seekTo(target, MediaPlayer.SEEK_CLOSEST_SYNC);
                        seekRefine(target);   // v2.4.6 PHASE 3: idle par exact refine
                    } else {
                        pendingScrubTarget[0] = target;
                    }
                    setPlayerControlsVisible(true);
                } else if (gestureMode[0] == 2) {
                    dragged[0] = true;
                    float change = -dy / Math.max(1f, v.getHeight());
                    if (downX[0] < v.getWidth() / 2f) {
                        WindowManager.LayoutParams lp = getWindow().getAttributes();
                        lp.screenBrightness = Math.max(0.02f, Math.min(1f, downBrightness[0] + change));
                        getWindow().setAttributes(lp);
                    } else if (audio != null) {
                        int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                        int targetVol = Math.max(0, Math.min(max, downVolume[0] + Math.round(change * max)));
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0);
                    }
                    setPlayerControlsVisible(true);
                }
            }
            if (a == MotionEvent.ACTION_MOVE && ev.getPointerCount() == 1) {
                lastPanX[0] = ev.getX(); lastPanY[0] = ev.getY();
            }
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                setHoldSlow(false);
                // Scrub finished: hide the time pill and run ONE final seek for the
                // last pending drag position (keyframe snap — restart-safe).
                if (scrubPill != null) scrubPill.setVisibility(View.GONE);
                if (pendingScrubTarget[0] >= 0L && !slowActive && mediaPlayer != null) {
                    long target = pendingScrubTarget[0];
                    pendingScrubTarget[0] = -1L;
                    mediaPlayer.seekTo(target, MediaPlayer.SEEK_CLOSEST_SYNC);
                    seekRefine(target);   // v2.4.6 PHASE 3: final exact refine
                }
                if (controlsVisible) schedulePlayerControlsHide();
            }
            return true;
        };
        // v2.4.6 PHASE 1: ZoomController owns touch + wheel (pinch/drag/fling/zoom);
        // oldPlayerTouch = fallback (tap, ±5s double-tap, scrub, brightness/volume).
        playerZc = new ZoomController(this, touch, new ZoomController.Listener() {
            @Override public void onTransform(Matrix m, float zoom, boolean interacting) {
                playerZoom = zoom;
                // v2.4.8: setLiveZoom ab applyPlayerViewTransform mein lagta hai — GL ko
                // VIEW par lagne wala ASLI total milta hai (pehle ZC ka raw zoom jata tha
                // — cap/base hone par dono alag numbers → bicubic/sharpen ghalat level
                // par switch hota tha). Ab GL aur view ek hi number par.
                float[] vals = new float[9];
                m.getValues(vals);
                // ZC matrix: MTRANS = vw/2 + pan − (view-size content)·zoom/2 → pan wapas
                playerPanX = vals[Matrix.MTRANS_X] + (touch.getWidth() * zoom) / 2f - touch.getWidth() / 2f;
                playerPanY = vals[Matrix.MTRANS_Y] + (touch.getHeight() * zoom) / 2f - touch.getHeight() / 2f;
                applyPlayerViewTransform();
            }
            @Override public void onSettled(android.graphics.RectF visible, float zoom, int generation) {
                if (playerZc == null || !playerZc.isCurrent(generation)) return;
                ui.removeCallbacks(zoomPanRefresh);
                if (playerPrepared && videoView != null && !videoView.isPlaying()
                        && !slowActive && zoom > 1.001f) {
                    refreshZoomOverlay(playerTotalScale, true);   // full-res viewport crop (paused sharp)
                }
            }
            @Override public void onSingleTap() {
                setPlayerControlsVisible(!controlsVisible);
            }
        });
        playerZc.setDoubleTapZoomEnabled(false);   // double-tap = ±5s seek (approved feature)
        playerZc.setFallback(oldPlayerTouch);
        touch.setOnTouchListener(playerZc);
        touch.setOnGenericMotionListener(playerZc);
        touch.post(() -> { if (playerZc != null) playerZc.setContent(touch.getWidth(), touch.getHeight()); });
        p.addView(touch, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rotateOverlayPlayer = makeRotateOverlay();   // v2.4.5: Fine Rotate drag overlay
        p.addView(rotateOverlayPlayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Glass-style top row: ‹ + title left, ⛶ ↻ ⋮ pill right (screenshot design).
        playerTop = buildPlayerTopBar();
        p.addView(playerTop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // (Floating ⟳/✂ side buttons REMOVED — user request: play area saaf rakho.
        //  Trim ab ⋮ menu → Tools → "Trim + Merge" se, rotate ↻ VIEW dialog se.)
        // (Center transport ⏮ ▶ ⏭ bhi REMOVED — user request: screen ke beech mein
        //  koi button nazar na aaye. Play/pause neeche glass pill mein hai; ±5s aur
        //  scrub gestures se hota hai; prev/next video ‹ → Library se.)

        // Bottom control strip: floating seek row + glass pills (no sheet background).
        LinearLayout bottom = buildPlayerBottomBar();
        playerBottom = bottom;
        p.addView(bottom, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        modeContainer.addView(p, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        videoView.setOnPreparedListener(mp -> {
            mediaPlayer = mp;
            playerPrepared = true;
            int dur = Math.max(1, mp.getDuration());
            playerSeek.setMax(dur);
            playerDuration.setText(fmtClock(dur));
            long ms = (syncPositionUs + 999L) / 1000L;
            // S2 (black-start fix — research-approved): prepare ke baad HAMESHA seek
            // (resume position ya 0) — decoder pehla frame foran render karta hai,
            // 0.5s ka black gap khatam.
            mp.seekTo(ms, MediaPlayer.SEEK_CLOSEST);
            playerSeek.setProgress((int) Math.min(dur, ms));
            playerCurrent.setText(fmtClock(ms));
            holdSlow = false;
            applyPlayerViewTransform();
            if (startPlayingOnPrepared) startPlayer();
            updatePlayerPlayButton();
            ui.removeCallbacks(playerTick);
            ui.post(playerTick);
            schedulePlayerControlsHide();
        });
        videoView.setOnCompletionListener(mp -> {
            updatePlayerPlayButton();
            setPlayerControlsVisible(true);
        });
        videoView.setOnErrorListener((mp, what, extra) -> {
            // v2.4.7 BUG 3: asli error LOG + toast (what/extra + codec/res/fps) —
            // aur automatic fallback: same video Frame mode mein same position par.
            String info = videoInfoForError();
            android.util.Log.e("TNPlayer", "MediaPlayer error what=" + what + " extra=" + extra
                    + " | " + info + " | uri=" + currentUri);
            Toast.makeText(MainActivity.this,
                    "Media Player error " + what + " / " + extra + " (" + info
                            + ") — Frame Mode mein khol raha hoon",
                    Toast.LENGTH_LONG).show();
            if (uiSource != null && !loading) {
                syncPositionUs = capturePositionUs();
                switchMode(MODE_FRAME);   // hamara MediaCodec decoder — 4K fallback
            }
            return true;
        });
        GradedVideoView.setLookPreset(frameGradePreset);   // selected look LIVE video par
        videoView.setVideoURI(currentUri);
        refreshOrientationButtons();
    }

    /** Glass-style player top row: ‹ back + title (left), ⛶ ↻ ⋮ pill (right). */
    private LinearLayout buildPlayerTopBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));

        Button back = pillIcon("‹", v -> onBackPressed());
        back.setTextSize(22);
        back.setContentDescription("Back");
        registerControl("top_back", back);
        row.addView(back, new LinearLayout.LayoutParams(dp(42), dp(40)));

        TextView title = text(currentName == null ? "MEDIA PLAYER" : currentName, 13, Color.WHITE, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        title.setShadowLayer(dp(3), 0, 0, 0xCC020810);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(8);
        tlp.rightMargin = dp(8);
        row.addView(title, tlp);

        LinearLayout pill = glassPill();
        Button fs = pillIcon("⛶", v -> toggleFullscreen());
        fs.setContentDescription("Fullscreen");
        Button rot = pillIcon("↻", v -> cyclePlayerRotation());
        rot.setContentDescription("Rotate content");
        Button fineRot = pillIcon("360", v -> toggleFineRotateMode());   // v2.4.6: font-proof 360 button
        fineRot.setTextSize(11);
        fineRot.setContentDescription("Fine rotate 360");
        Button more = pillIcon("⋮", this::showPlayerMenu);
        more.setContentDescription("More options");
        registerControl("top_menu", more);
        pill.addView(fs, new LinearLayout.LayoutParams(dp(44), dp(40)));
        pill.addView(rot, new LinearLayout.LayoutParams(dp(44), dp(40)));
        pill.addView(fineRot, new LinearLayout.LayoutParams(dp(44), dp(40)));
        pill.addView(more, new LinearLayout.LayoutParams(dp(44), dp(40)));
        row.addView(pill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** v2.4.7 BUG 3: error ke waqt video ki asli info (codec/resolution/fps) — MediaExtractor se. */
    private String videoInfoForError() {
        try {
            android.media.MediaExtractor ex = new android.media.MediaExtractor();
            try {
                ex.setDataSource(getApplicationContext(), currentUri, null);
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    android.media.MediaFormat f = ex.getTrackFormat(i);
                    String mime = f.getString(android.media.MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/")) {
                        String res = f.containsKey(android.media.MediaFormat.KEY_WIDTH)
                                && f.containsKey(android.media.MediaFormat.KEY_HEIGHT)
                                ? f.getInteger(android.media.MediaFormat.KEY_WIDTH) + "x"
                                  + f.getInteger(android.media.MediaFormat.KEY_HEIGHT) : "?";
                        String fps = f.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)
                                ? f.getInteger(android.media.MediaFormat.KEY_FRAME_RATE) + "fps" : "?fps";
                        String profile = f.containsKey(android.media.MediaFormat.KEY_PROFILE)
                                ? "profile " + f.getInteger(android.media.MediaFormat.KEY_PROFILE) : "";
                        return mime + " " + res + " " + fps + " " + profile;
                    }
                }
            } finally {
                ex.release();
            }
        } catch (Exception e) { /* info optional hai */ }
        return "info unavailable";
    }

    private LinearLayout buildPlayerBottomBar() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), 0, dp(10), dp(10));

        // Seek row floats over the video — 00:00 ──●── 05:00 (no sheet background).
        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setOrientation(LinearLayout.HORIZONTAL);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        playerCurrent = text("00:00", 11, Color.WHITE, true);
        playerCurrent.setShadowLayer(dp(2), 0, 0, 0xCC020810);
        playerDuration = text(fmtClock(uiSource != null ? uiSource.durationUs / 1000L : 0L), 11, 0xFFD7E1EE, false);
        playerDuration.setShadowLayer(dp(2), 0, 0, 0xCC020810);
        playerSeek = new SeekBar(this);
        playerSeek.setContentDescription("Playback position");
        playerSeek.setMax(1);
        styleSeekBar(playerSeek, Color.WHITE);
        playerSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                playerCurrent.setText(fmtClock(progress));
                if (slowActive) { slowSeekTo(progress); }
                else if (playerPrepared && mediaPlayer != null) mediaPlayer.seekTo((long) progress, MediaPlayer.SEEK_CLOSEST_SYNC);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { userSeeking = true; ui.removeCallbacks(playerControlsAutoHide); }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                userSeeking = false;
                if (slowActive) slowSeekTo(bar.getProgress());
                else if (playerPrepared && mediaPlayer != null) mediaPlayer.seekTo((long) bar.getProgress(), MediaPlayer.SEEK_CLOSEST_SYNC);
                schedulePlayerControlsHide();
            }
        });
        LinearLayout.LayoutParams curLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); curLp.rightMargin=dp(6);
        LinearLayout.LayoutParams durLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); durLp.leftMargin=dp(6);
        seekRow.addView(playerCurrent,curLp);
        seekRow.addView(playerSeek,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
        seekRow.addView(playerDuration,durLp);
        box.addView(seekRow);

        // Glass pills row: ▣ FRAME + 📷 (left) — 1.0x ↻ 🔒 (right).  ±5 buttons gone
        // (double-tap zones replace them — YouTube standard).
        LinearLayout pills = new LinearLayout(this);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout leftPill = glassPill();
        // Play/Pause neeche pill mein — screen ke beech mein koi button nahi (user request).
        playerPlayButton = pillIcon("▶", v -> togglePlayer());
        playerPlayButton.setContentDescription("Play or pause");
        registerControl("player_play", playerPlayButton);
        Button frame = pillIcon("▣", v -> switchMode(MODE_FRAME));
        frame.setContentDescription("Frame mode");
        registerControl("player_frame", frame);
        Button shot = pillIcon("📷", v -> savePlayerScreenshot());
        shot.setContentDescription("Save screenshot");
        leftPill.addView(playerPlayButton, new LinearLayout.LayoutParams(dp(46), dp(40)));
        leftPill.addView(frame, new LinearLayout.LayoutParams(dp(46), dp(40)));
        leftPill.addView(shot, new LinearLayout.LayoutParams(dp(46), dp(40)));
        pills.addView(leftPill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pills.addView(new android.widget.Space(this), new LinearLayout.LayoutParams(0, 1, 1f));

        LinearLayout rightPill = glassPill();
        // Zoom −/＋ — v2.4.6: ZoomController ke animated zoomIn/zoomOut (single engine).
        Button zoomOut = pillIcon("−", v -> {
            if (playerZc != null) playerZc.zoomOut();
        });
        zoomOut.setContentDescription("Zoom out");
        Button zoomIn = pillIcon("＋", v -> {
            if (playerZc != null) playerZc.zoomIn();
        });
        zoomIn.setContentDescription("Zoom in");
        speedPillBtn = pillIcon("1.0x", v -> showSpeedPopup(v));
        speedPillBtn.setTextSize(13);
        speedPillBtn.setContentDescription("Playback speed");
        // ↻ rotate REMOVED from bottom pill (duplicate — top-right pill ↻ hai).
        // 🔒 lock REMOVED from pill — ab ⋮ → View → Gesture Lock mein (user order).
        // 🎨 Quality Look pill — tap = looks popup (HDR+/Cinematic/…) live video par.
        lookPillBtn = pillIcon("🎨", v -> showLookPopup(v, false));
        lookPillBtn.setContentDescription("Quality look");
        rightPill.addView(zoomOut, new LinearLayout.LayoutParams(dp(46), dp(40)));
        rightPill.addView(zoomIn, new LinearLayout.LayoutParams(dp(46), dp(40)));
        rightPill.addView(speedPillBtn, new LinearLayout.LayoutParams(dp(50), dp(40)));
        rightPill.addView(lookPillBtn, new LinearLayout.LayoutParams(dp(46), dp(40)));
        pills.addView(rightPill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        box.addView(pills, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    /** Glass-style rounded translucent pill container (player controls — screenshot reference). */
    private LinearLayout glassPill() {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(roundRect(0xB3020810, 22, 0, 0));
        pill.setPadding(dp(6), dp(3), dp(6), dp(3));
        return pill;
    }

    /** Small white icon button for glass pills — no background of its own. */
    private Button pillIcon(String glyph, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(glyph);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(17);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setBackground(null);
        b.setPadding(0, 0, 0, 0);
        b.setMinimumWidth(0);
        b.setMinWidth(0);
        b.setMinimumHeight(dp(40));
        b.setOnClickListener(l);
        return b;
    }

    /** Bare big transport icon (center row) — no box, YouTube-style. */
    private Button bigTransportIcon(String glyph, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(glyph);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(26);
        b.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        b.setShadowLayer(dp(2), 0, 0, 0x99020610);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setBackground(null);
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        b.setMinimumWidth(0);
        b.setMinWidth(0);
        b.setMinimumHeight(dp(56));
        b.setOnClickListener(l);
        return b;
    }

    /** Quick speed picker for the bottom-right glass pill (⋮ > Playback bhi hai). */
    private void showSpeedPopup(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        for (int i = 0; i < PLAYBACK_SPEEDS.length; i++) {
            final int idx = i;
            popup.getMenu().add("Speed " + formatSpeed(PLAYBACK_SPEEDS[i]))
                    .setCheckable(true).setChecked(speedIndex == i)
                    .setOnMenuItemClickListener(item -> {
                        speedIndex = idx;
                        if (speedPillBtn != null) speedPillBtn.setText(formatSpeed(PLAYBACK_SPEEDS[speedIndex]));
                        applyPlayerSpeed();
                        if (controlsVisible) schedulePlayerControlsHide();
                        return true;
                    });
        }
        popup.show();
    }

    /** v2.4.4 (user request): data-clear ki zaroorat khatam — ek button se sab
     *  settings default.  Videos, library aur resume positions BAKI rehte hain. */
    private void confirmResetSettings() {
        new AlertDialog.Builder(this).setTitle("Reset Settings")
                .setMessage("Buttons, layout order, Quality Look, speed, FPS, zoom/rotate aur locks sab DEFAULT ho jayenge.\n\nVideos aur library baki rehengi.")
                .setPositiveButton("Reset", (d, w) -> {
                    // 1) Persisted prefs clear (customized buttons + row order)
                    getSharedPreferences("control_custom", MODE_PRIVATE).edit().clear().apply();
                    getSharedPreferences("control_orders", MODE_PRIVATE).edit().clear().apply();
                    // 2) In-memory state default
                    frameGradePreset = 0;
                    GradedVideoView.setLookPreset(0);
                    if (videoView != null) videoView.requestLookRedraw();
                    speedIndex = 4;   // 1.00x
                    if (speedPillBtn != null) speedPillBtn.setText(formatSpeed(PLAYBACK_SPEEDS[speedIndex]));
                    applyPlayerSpeed();
                    playbackFps = 0;   // AUTO
                    updateFrameButtons();
                    displayMode = ZoomableImageView.MODE_FIT;
                    playerDisplayMode = ZoomableImageView.MODE_FIT;
                    displayRotation = 0;
                    pictureRotation = 0;
                    frameFineRotation = 0f;
                    playerContentRotation = 0;
                    playerFineRotation = 0f;
                    gestureLocked = false;
                    frameLocked = false;
                    abRepeatEnabled = false;
                    abStartMs = -1L;
                    abEndMs = -1L;
                    resetPlayerZoom();
                    if (viewer != null) {
                        viewer.setDisplayMode(displayMode);
                        viewer.setDisplayRotation(0);   // andar resetZoom + invalidate
                    }
                    refreshControls();
                    Toast.makeText(this, "Settings reset — sab default ho gaya", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null).show();
    }

    /** Quality Look picker — 🎨 pill: look LIVE chalti video par lag jata hai.
     *  Frame Mode (liveFrameRegrade=true): displayed frame turant re-grade hota hai. */
    private void showLookPopup(View anchor, boolean liveFrameRegrade) {
        PopupMenu popup = new PopupMenu(this, anchor);
        String[] names = {"Original", "HDR+", "Cinematic", "Vivid", "Warm", "Cool"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            popup.getMenu().add(names[idx])
                    .setCheckable(true).setChecked(frameGradePreset == idx)
                    .setOnMenuItemClickListener(item -> {
                        frameGradePreset = idx;
                        GradedVideoView.setLookPreset(idx);
                        // v2.4.4 fix (Note #4): paused/stuck video par look foran
                        // render ho — pehle requestRender call hi missing tha.
                        if (videoView != null) videoView.requestLookRedraw();
                        if (liveFrameRegrade && mode == MODE_FRAME && currentBitmap != null
                                && !currentBitmap.isRecycled() && viewer != null) {
                            final Bitmap orig = currentBitmap;
                            getDecoder().execute(() -> {
                                // v2.4.4 fix: Original (idx==0) par grade() orig ka
                                // SAME reference deta hai — pehle wo shownBitmap mein
                                // store hota tha (cache-owned bitmap ka alias) aur
                                // agla frame usay recycle kar deta tha = cache
                                // corruption.  Ab Original par shownBitmap NULL.
                                Bitmap graded = (idx > 0) ? ColorGrade.grade(orig, idx) : null;
                                ui.post(() -> {
                                    if (isDestroyed() || viewer == null) return;
                                    Bitmap newShown = (graded != null && !graded.isRecycled()) ? graded : null;
                                    Bitmap display  = (newShown != null) ? newShown : orig;
                                    Bitmap oldShown = shownBitmap;
                                    shownBitmap = newShown;
                                    viewer.setFrame(display, true);
                                    if (oldShown != null && oldShown != display && oldShown != orig
                                            && oldShown != currentBitmap && !oldShown.isRecycled())
                                        oldShown.recycle();
                                });
                            });
                        }
                        return true;
                    });
        }
        popup.show();
    }

    /** Rotate cycle: har tap par video 90° ghoomti hai (0→90→180→270→0) —
     *  koi angle-menu nahi; user jo angle acha lage wahan rok leta hai.
     *  v2.4.6 FIX (user recording r_05): rotate+zoom ka combo "ajeeb" view banata
     *  tha (zoomed arm upside-down) — ab rotate par zoom/pan RESET (clean fit). */
    private void cyclePlayerRotation() {
        playerContentRotation = (playerContentRotation + 90) % 360;
        if (playerZc != null) playerZc.reset(false);   // zoom+pan reset — rotate+zoom combo bug fix
        playerZoom = 1f;
        playerPanX = playerPanY = 0f;
        applyPlayerViewTransform();
    }

    private final Runnable playerTick = new Runnable() {
        @Override public void run() {
            if (mode != MODE_PLAYER || videoView == null) return;
            if (playerPrepared && !userSeeking
                    && (scrubPill == null || scrubPill.getVisibility() != View.VISIBLE)) {
                int pos = slowActive && uiSource != null && slowFrameIndex < uiSource.frameCount
                        ? (int) (uiSource.ptsUs[Math.max(0, slowFrameIndex)] / 1000L)
                        : videoView.getCurrentPosition();
                if (abRepeatEnabled && abStartMs >= 0 && abEndMs > abStartMs && pos >= abEndMs && mediaPlayer != null) {
                    mediaPlayer.seekTo(abStartMs, MediaPlayer.SEEK_CLOSEST);
                    pos = (int) abStartMs;
                }
                playerSeek.setProgress(pos);
                playerCurrent.setText(fmtClock(pos));
            }
            updatePlayerPlayButton();
            watchHwSlowSpeed();
            refreshZoomOverlay(playerTotalScale);
            ui.postDelayed(this, 250L);
        }
    };

    private void setPlayerControlsVisible(boolean visible) {
        controlsVisible = visible;
        setControlViewVisible(playerTop, visible);
        setControlViewVisible(playerBottom, visible);
        ui.removeCallbacks(playerControlsAutoHide);
        if (visible) schedulePlayerControlsHide();
    }

    private void setControlViewVisible(View view, boolean visible) {
        if (view == null) return;
        view.animate().cancel();
        view.setTag(visible);          // expected state — stale end-action guard
        view.setVisibility(View.VISIBLE);
        // Touch-blocking fix: hidden controls click-through NA ho sakein — fade shuru
        // hote hi clickable off, fade complete par GONE (alpha-0 invisible buttons
        // kabhi swipe/tap intercept nahi karenge).
        view.setClickable(visible);
        if (visible) {
            view.animate().alpha(1f).setDuration(180L).start();
        } else {
            view.animate().alpha(0f).setDuration(180L)
                    .withEndAction(() -> {
                        if (!Boolean.TRUE.equals(view.getTag())) view.setVisibility(View.GONE);
                    }).start();
        }
    }

    private void schedulePlayerControlsHide() {
        ui.removeCallbacks(playerControlsAutoHide);
        if (mode == MODE_PLAYER && videoView != null && playerPrepared && controlsVisible)
            ui.postDelayed(playerControlsAutoHide, 30_000L);
    }

    private void togglePlayer() {
        if (videoView == null || !playerPrepared) return;
        if (slowActive) {
            slowPlaying = !slowPlaying;
            ui.removeCallbacks(slowFrameTick);
            if (slowPlaying) ui.post(slowFrameTick);
        } else if (videoView.isPlaying()) {
            videoView.pause();
        } else {
            if (videoView.getCurrentPosition() >= videoView.getDuration() - 50)
                mediaPlayer.seekTo(0L, MediaPlayer.SEEK_CLOSEST);
            startPlayer();
        }
        updatePlayerPlayButton();
        if (controlsVisible) schedulePlayerControlsHide();
    }

    private void startPlayer() {
        if (useDecodedSlow()) {
            enableSlowMotion();
            slowPlaying = true;
            ui.removeCallbacks(slowFrameTick);
            ui.post(slowFrameTick);
            return;
        }
        videoView.start();
        applyPlayerSpeed();
    }

    private void updatePlayerPlayButton() {
        if (playerPlayButton != null && videoView != null) {
            boolean playing = slowActive ? slowPlaying : playerPrepared && videoView.isPlaying();
            playerPlayButton.setText(playing ? "⏸" : "▶");   // bare icon — glass look
            playerPlayButton.setContentDescription(playing ? "Pause playback" : "Play video");
            playerPlayButton.setTextColor(Color.WHITE);
        }
    }

    private void seekPlayerBy(long deltaMs) {
        if (videoView == null || !playerPrepared || mediaPlayer == null) return;
        // Robust position read: kuch devices paused/buffering par getCurrentPosition()
        // = 0 dete hain — seekbar progress fallback us quirk ko neutral karta hai.
        long cur = videoView.getCurrentPosition();
        if (cur <= 0L && playerSeek.getProgress() > 0L) cur = playerSeek.getProgress();
        long dur = videoView.getDuration();
        long target = Math.max(0L, Math.min(dur, cur + deltaMs));
        if (slowActive) slowSeekTo(target);
        // SEEK_CLOSEST_SYNC: keyframe par snap — instant, decoder catch-up freeze aur
        // "restart from start" (user-reported OEM CLOSEST bug) dono impossible.
        else { mediaPlayer.seekTo(target, MediaPlayer.SEEK_CLOSEST_SYNC); seekRefine(target); }
        playerSeek.setProgress((int) target);
        playerCurrent.setText(fmtClock(target));
        if (controlsVisible) schedulePlayerControlsHide();
    }

    /** V2.0.6: brief on-screen ±5s indicator for double-tap seeks. */
    private void flashSeek(boolean forward) {
        if (seekFlash == null) return;
        seekFlash.setText(forward ? "5s »" : "« 5s");
        // Pill us side par pop hota hai jahan double-tap hua (left/right 35% zone).
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) seekFlash.getLayoutParams();
        lp.gravity = Gravity.CENTER_VERTICAL | (forward ? Gravity.END : Gravity.START);
        lp.setMargins(dp(24), 0, dp(24), 0);
        seekFlash.setLayoutParams(lp);
        seekFlash.setVisibility(View.VISIBLE);
        ui.removeCallbacks(seekFlashHide);
        ui.postDelayed(seekFlashHide, 500L);
    }

    private final Runnable seekFlashHide = () -> {
        if (seekFlash != null) seekFlash.setVisibility(View.GONE);
    };

    /** V2.0.8: paused + zoomed → full-resolution frame overlay (smooth zoom quality). */
    private void refreshZoomOverlay(float total) { refreshZoomOverlay(total, false); }

    /**
     * Hardening spec S1: paused + zoomed → NATIVE 1:1 decode on TrueNain-Decoder.
     * - playerZoom > 1.5x: crop the decoded frame strictly to the VISIBLE viewport
     *   rect (memory guard for 4K/8K sources) and draw it via an explicit matrix,
     *   so on-screen geometry stays identical to the full-frame path.
     * - force=true (gesture end / 80 ms pan pause): bypass the 200 ms scrub bucket
     *   and refresh immediately.
     */
    private void refreshZoomOverlay(float total, boolean force) {
        if (zoomOverlay == null) return;
        boolean need = mode == MODE_PLAYER && playerPrepared && uiSource != null
                && playerZoom > 1.001f && videoView != null && !videoView.isPlaying() && !slowActive;
        if (!need) {
            zoomOverlay.setVisibility(View.GONE);
            if (zoomOverlayCropMode) {                 // back to the simple transform path
                zoomOverlay.setScaleType(ImageView.ScaleType.FIT_CENTER);
                zoomOverlayCropMode = false;
            }
            return;
        }
        long posUs = Math.max(0L, videoView.getCurrentPosition()) * 1000L;
        int key = (int) (posUs / 200_000L);            // 200 ms bucket — scrub-safe throttle
        if (!force && !zoomOverlayDecoding
                && key == zoomOverlayKey && zoomOverlayBmp != null && !zoomOverlayBmp.isRecycled()) {
            presentZoomOverlay(playerTotalScale);      // pan/zoom may have changed — re-apply
            zoomOverlay.setVisibility(View.VISIBLE);
            return;
        }
        if (zoomOverlayDecoding) return;
        zoomOverlayDecoding = true;
        zoomOverlayKey = key;
        final FrameSource src = uiSource;
        final int idx = PlaybackMath.nearestIndex(src.ptsUs, posUs);
        final Rect cropRect = new Rect();
        final boolean crop = playerZoom > 1.5f
                && computeZoomCropRect(src.width, src.height, total, cropRect);
        final float zoomAtRequest = playerZoom;
        getDecoder().execute(() -> {
            Bitmap full = null;
            try { full = src.getFrame(idx); } catch (RuntimeException ignored) {}
            Bitmap ready = full;
            boolean cropped = false;
            if (crop && full != null && !full.isRecycled()) {
                try {
                    ready = Bitmap.createBitmap(full, cropRect.left, cropRect.top,
                            cropRect.width(), cropRect.height());
                    cropped = true;
                } catch (RuntimeException ignored) { ready = full; }
            }
            // Crisp spec 2.3: subtle unsharp mask on the viewport tile past 3x zoom.
            if (cropped) sharpenCrispTile(ready, zoomAtRequest);
            if (cropped && full != null && !full.isRecycled()) BitmapPool.release(full);
            final Bitmap out = ready;
            final boolean croppedOk = cropped;
            ui.post(() -> {
                zoomOverlayDecoding = false;
                if (out == null || src != uiSource || isDestroyed()) {
                    if (out != null && !out.isRecycled()) BitmapPool.release(out);
                    return;
                }
                zoomOverlayCropMode = croppedOk;
                if (croppedOk) { zoomCropX = cropRect.left; zoomCropY = cropRect.top; }
                Bitmap old = zoomOverlayBmp;
                zoomOverlayBmp = out;
                zoomOverlay.setImageBitmap(out);
                presentZoomOverlay(playerTotalScale);
                zoomOverlay.setVisibility(View.VISIBLE);
                // Pool-or-copy owned (never cache-owned) — pool release is the safe path.
                if (old != null && old != out && !old.isRecycled()) BitmapPool.release(old);
            });
        });
    }

    /**
     * Crisp spec 2.3: lightweight 3×3 unsharp mask on the zoomed viewport tile so
     * fine details stay crisp past 3x zoom without ringing or halos.  Amount ramps
     * linearly 3x→4x (0 → 0.6) and caps there.  Runs on the TrueNain-Decoder thread
     * on the small cropped tile only — never touches UI or cache-owned bitmaps.
     */
    private static void sharpenCrispTile(Bitmap tile, float zoom) {
        if (tile == null || tile.isRecycled()) return;
        int w = tile.getWidth(), h = tile.getHeight();
        if (w < 16 || h < 16) return;
        float amount = Math.max(0f, Math.min(0.6f, (zoom - 3.0f) / 2.0f));
        if (amount <= 0.01f) return;
        int[] px = new int[w * h];
        int[] out = new int[w * h];
        try {
            tile.getPixels(px, 0, w, 0, 0, w, h);
        } catch (RuntimeException ignored) {
            return;
        }
        for (int y = 0; y < h; y++) {
            int up = (y > 0 ? y - 1 : 0) * w;
            int row = y * w;
            int dn = (y < h - 1 ? y + 1 : y) * w;
            for (int x = 0; x < w; x++) {
                int c = row + x;
                int left = row + (x > 0 ? x - 1 : 0);
                int right = row + (x < w - 1 ? x + 1 : x);
                int sc = px[c], su = px[up + x], sd = px[dn + x], sl = px[left], sr = px[right];
                // blur = cheap 4-neighbour box average; sharpen = orig + amount*(orig - blur)
                int br = ((su >>> 16 & 255) + (sd >>> 16 & 255) + (sl >>> 16 & 255) + (sr >>> 16 & 255)) >> 2;
                int bg = ((su >>> 8 & 255) + (sd >>> 8 & 255) + (sl >>> 8 & 255) + (sr >>> 8 & 255)) >> 2;
                int bb = ((su & 255) + (sd & 255) + (sl & 255) + (sr & 255)) >> 2;
                int r = (sc >>> 16 & 255) + Math.round(amount * ((sc >>> 16 & 255) - br));
                int g = (sc >>> 8 & 255) + Math.round(amount * ((sc >>> 8 & 255) - bg));
                int b = (sc & 255) + Math.round(amount * ((sc & 255) - bb));
                r = r < 0 ? 0 : Math.min(255, r);
                g = g < 0 ? 0 : Math.min(255, g);
                b = b < 0 ? 0 : Math.min(255, b);
                out[c] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }
        try {
            tile.setPixels(out, 0, w, 0, 0, w, h);
        } catch (RuntimeException ignored) {}
    }

    /**
     * Applies the current pan/zoom/rotate to the zoom overlay.  Crop mode draws the
     * already-viewport-sized bitmap through a matrix that reproduces exactly the
     * VideoView transform (fit → total scale → content rotation → pan).
     */
    private void presentZoomOverlay(float total) {
        if (zoomOverlay == null) return;
        Bitmap bmp = zoomOverlayBmp;
        if (bmp == null || bmp.isRecycled()) return;
        if (zoomOverlayCropMode) {
            zoomOverlay.setScaleType(ImageView.ScaleType.MATRIX);
            zoomOverlay.setImageMatrix(buildZoomOverlayMatrix(total, bmp));
            zoomOverlay.setScaleX(1f); zoomOverlay.setScaleY(1f);
            zoomOverlay.setRotation(0f);
            zoomOverlay.setTranslationX(0f); zoomOverlay.setTranslationY(0f);
        } else {
            zoomOverlay.setScaleType(ImageView.ScaleType.FIT_CENTER);
            applyPlayerTransformTo(zoomOverlay);
        }
    }

    /**
     * Hardening spec S1.1: visible viewport rect in SOURCE bitmap pixels (0..srcW/H),
     * or null-equivalent ({@code false}) when the whole frame is already on screen.
     * Works by inverting the exact display matrix of the full-frame overlay.
     */
    private boolean computeZoomCropRect(int srcW, int srcH, float total, Rect out) {
        if (zoomOverlay == null) return false;
        int vw = zoomOverlay.getWidth(), vh = zoomOverlay.getHeight();
        if (vw <= 0 || vh <= 0 || srcW <= 0 || srcH <= 0 || total <= 0f) return false;
        Matrix inv = zoomOverlayDisplayMatrix(srcW, srcH, total);
        if (!inv.invert(inv)) return false;
        float[] pts = {0f, 0f, vw, 0f, 0f, vh, vw, vh};
        inv.mapPoints(pts);
        float minX = Math.min(Math.min(pts[0], pts[2]), Math.min(pts[4], pts[6]));
        float maxX = Math.max(Math.max(pts[0], pts[2]), Math.max(pts[4], pts[6]));
        float minY = Math.min(Math.min(pts[1], pts[3]), Math.min(pts[5], pts[7]));
        float maxY = Math.max(Math.max(pts[1], pts[3]), Math.max(pts[5], pts[7]));
        int l = Math.max(0, (int) Math.floor(minX) - 2);
        int t = Math.max(0, (int) Math.floor(minY) - 2);
        int r = Math.min(srcW, (int) Math.ceil(maxX) + 2);
        int b = Math.min(srcH, (int) Math.ceil(maxY) + 2);
        if (r - l < 8 || b - t < 8) return false;
        if (l == 0 && t == 0 && r == srcW && b == srcH) return false;   // whole frame visible
        out.set(l, t, r, b);
        return true;
    }

    /** Exact display matrix of a full-frame overlay bitmap: fit → scale → rotate → pan. */
    private Matrix zoomOverlayDisplayMatrix(int srcW, int srcH, float total) {
        int vw = zoomOverlay.getWidth(), vh = zoomOverlay.getHeight();
        float s0 = Math.min(vw / (float) srcW, vh / (float) srcH);   // FIT_CENTER semantics
        Matrix m = new Matrix();
        m.postScale(s0, s0);
        m.postTranslate((vw - srcW * s0) / 2f, (vh - srcH * s0) / 2f);
        m.postScale(total, total, vw / 2f, vh / 2f);
        m.postRotate(playerContentRotation, vw / 2f, vh / 2f);
        m.postTranslate(playerPanX, playerPanY);
        return m;
    }

    /** Matrix that maps the CROPPED bitmap onto the same screen region as the full frame. */
    private Matrix buildZoomOverlayMatrix(float total, Bitmap crop) {
        Matrix m = uiSource != null && uiSource.width > 0 && uiSource.height > 0
                ? zoomOverlayDisplayMatrix(uiSource.width, uiSource.height, total)
                : zoomOverlayDisplayMatrix(crop.getWidth(), crop.getHeight(), total);
        m.preTranslate(-zoomCropX, -zoomCropY);
        return m;
    }

    /** Same pan/zoom/rotate transform as the VideoView surface, for overlay views. */
    private void applyPlayerTransformTo(View v) {
        v.setScaleX(playerTotalScale);
        v.setScaleY(playerTotalScale);
        v.setRotation(playerContentRotation + playerFineRotation);   // v2.4.4: fine angle bhi
        v.setTranslationX(playerPanX);
        v.setTranslationY(playerPanY);
    }

    private void setHoldSlow(boolean active) {
        if (holdSlow == active) return;
        holdSlow = active;
        applyPlayerSpeed();
    }

    private void applyPlayerSpeed() {
        if (mediaPlayer == null || !playerPrepared || videoView == null) return;
        if (useDecodedSlow()) {
            if (!slowActive) {
                boolean wasPlaying = videoView.isPlaying();
                enableSlowMotion();
                slowPlaying = wasPlaying;
            }
            if (slowPlaying) { ui.removeCallbacks(slowFrameTick); ui.post(slowFrameTick); }
            updatePlayerPlayButton();
            return;
        }
        if (slowActive) {
            boolean resume = slowPlaying;
            long position = slowSource != null && slowFrameIndex < slowSource.frameCount
                    ? slowSource.ptsUs[slowFrameIndex] / 1000L : videoView.getCurrentPosition();
            stopSlowMotion(true);
            videoView.seekTo((int) position);
            if (resume) videoView.start();
        }
        if (!videoView.isPlaying()) return;
        resetHwSpeedWatchdog();
        double speed = PlaybackMath.effectiveSpeed(PLAYBACK_SPEEDS[speedIndex], holdSlow);
        // 1.0x (default) par setPlaybackParams SKIP — MediaPlayer ka default speed
        // 1.0 hai, aur ye call Samsung par decoder pipeline churn = black/green
        // glitches. Sirf tab call karo jab applied speed sach mein badle.
        if (Math.abs((float) speed - hwSpeedApplied) < 0.001f) return;
        try {
            PlaybackParams params = new PlaybackParams();
            params.setSpeed((float) speed);
            mediaPlayer.setPlaybackParams(params);
            hwSpeedApplied = (float) speed;
        } catch (RuntimeException e) {
            // Hardening spec S2.1: setPlaybackParams rejection at 0.5x → decoded engine.
            if (speed <= 0.5) {
                enableSlowMotion();
                slowPlaying = true;
                ui.removeCallbacks(slowFrameTick);
                ui.post(slowFrameTick);
                updatePlayerPlayButton();
            } else {
                Toast.makeText(this, "This speed is not supported on this device", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * Hardening spec S2.1: watchdog for devices where hardware 0.5x plays but the
     * position silently stops advancing.  Two seconds without progress → the decoded
     * engine takes over seamlessly (preserving the user's 0.5x freeze complaint fix).
     */
    private void watchHwSlowSpeed() {
        double speed = PlaybackMath.effectiveSpeed(PLAYBACK_SPEEDS[speedIndex], holdSlow);
        if (slowActive || videoView == null || !playerPrepared || !videoView.isPlaying()
                || Math.abs(speed - 0.5) > 0.001) {
            resetHwSpeedWatchdog();
            return;
        }
        int pos = videoView.getCurrentPosition();
        long now = android.os.SystemClock.uptimeMillis();
        if (pos != lastHwPosMs) {
            lastHwPosMs = pos;
            hwStallSinceMs = 0L;
            return;
        }
        if (hwStallSinceMs == 0L) {
            hwStallSinceMs = now;
            return;
        }
        if (now - hwStallSinceMs >= 2000L) {
            resetHwSpeedWatchdog();
            enableSlowMotion();
            slowPlaying = true;
            ui.removeCallbacks(slowFrameTick);
            ui.post(slowFrameTick);
            updatePlayerPlayButton();
        }
    }

    private void resetHwSpeedWatchdog() {
        hwStallSinceMs = 0L;
        lastHwPosMs = Integer.MIN_VALUE;
    }

    private boolean useDecodedSlow() {
        return useDecodedSlow(PlaybackMath.effectiveSpeed(PLAYBACK_SPEEDS[speedIndex], holdSlow));
    }

    /**
     * Hardening spec S2.1: hardware {@code PlaybackParams} first at ≥ 0.5x (including
     * 0.5x) — 100% native resolution, no CPU decode cost.  The custom decoded engine
     * is reserved for ultra-slow speeds (< 0.5x) and as the runtime fallback (see
     * {@link #watchHwSlowSpeed()} for the stall watchdog that covers devices where
     * setSpeed(0.5) freezes silently instead of throwing).
     */
    private boolean useDecodedSlow(double speed) {
        return speed < 0.5;
    }

    private void enableSlowMotion() {
        if (slowActive || uiSource == null || slowImage == null || videoView == null) return;
        slowActive = true;
        slowPlaying = false;
        int position = videoView.getCurrentPosition();
        videoView.pause();
        slowFrameIndex = PlaybackMath.nearestIndex(uiSource.ptsUs, position * 1000L);
        slowImage.setVisibility(View.VISIBLE);
        final int generation = slowGeneration.incrementAndGet();
        final Uri uri = currentUri;
        getSlowDecoder().execute(() -> {
            FrameSource opened = null;
            try { opened = FrameSource.open(getApplicationContext(), uri); }
            catch (Exception ignored) {}
            final FrameSource ready = opened;
            ui.post(() -> {
                if (generation != slowGeneration.get() || !slowActive) {
                    if (ready != null) getSlowDecoder().execute(ready::close);
                    return;
                }
                slowSource = ready;
                if (ready == null) {
                    stopSlowMotion(true);
                    Toast.makeText(this, "Slow decoder unavailable for this video", Toast.LENGTH_LONG).show();
                } else {
                    slowSourceId = System.nanoTime();   // V2.0: separate cache namespace per session
                    // V2.0 P0-01: remap slow playback onto the full presentation timeline.
                    ready.setOnFullTimelineReady(() -> ui.post(() -> {
                        FrameSource s = slowSource;
                        if (s != ready || !slowActive || isDestroyed()) return;
                        long anchorPts = slowCurrentPtsUs != Long.MIN_VALUE
                                ? slowCurrentPtsUs : syncPositionUs;
                        slowFrameIndex = PlaybackMath.nearestIndex(s.ptsUs, Math.max(0L, anchorPts));
                    }));
                    ui.post(slowFrameTick);
                }
            });
        });
    }

    private void slowSeekTo(long ms) {
        FrameSource src = slowSource != null ? slowSource : uiSource;
        if (src == null) return;
        slowFrameIndex = PlaybackMath.nearestIndex(src.ptsUs, ms * 1000L);
        slowGeneration.incrementAndGet();
        ui.removeCallbacks(slowFrameTick);
        ui.post(slowFrameTick);
    }

    private void renderSlowFrame() {
        if (!slowActive || slowImage == null || slowSource == null || mode != MODE_PLAYER) return;
        final FrameSource src = slowSource;
        final int index = Math.max(0, Math.min(src.frameCount - 1, slowFrameIndex));
        final int generation = slowGeneration.incrementAndGet();
        final long begun = android.os.SystemClock.uptimeMillis();
        final long sid = slowSourceId;
        // V2.0: cache-first — ahead-buffered frames render without touching the codec,
        // which is what removes the black-screen gaps at 0.25x/0.10x/0.05x playback.
        FrameData cached = frameCache != null ? frameCache.get(sid, index, 0) : null;
        if (cached != null && cached.isValid()) {
            final Bitmap ready = cached.bitmap;
            ui.post(() -> {
                applySlowFrame(src, index, generation, ready, begun);
                prefetchSlowAhead(sid, src, index);
            });
            return;
        }
        getSlowDecoder().execute(() -> {
            Bitmap b = null;
            Trace.beginSection("TN:slowDecode");
            try { b = src.getFrame(index); } catch (RuntimeException ignored) {}
            finally { Trace.endSection(); }
            if (b != null && !b.isRecycled() && frameCache != null && slowSource == src) {
                frameCache.put(new FrameData(sid, b, src.ptsUs[index], index, 0));
            }
            final Bitmap ready = b;
            ui.post(() -> {
                applySlowFrame(src, index, generation, ready, begun);
                prefetchSlowAhead(sid, src, index);
            });
        });
    }

    private void applySlowFrame(FrameSource src, int index, int generation,
                                Bitmap ready, long begun) {
        if (generation != slowGeneration.get() || !slowActive || slowSource != src) return;
        if (ready == null) {
            // V2.0.8: ek decoder hiccup playback freeze nahi karega — 2 tak retry.
            if (slowRetryCount < 2) {
                slowRetryCount++;
                ui.postDelayed(slowFrameTick, 300L);
                return;
            }
            slowPlaying = false;
            slowRetryCount = 0;
            Toast.makeText(this, "Unable to decode slow frame", Toast.LENGTH_SHORT).show();
            updatePlayerPlayButton();
            return;
        }
        slowRetryCount = 0;
        Bitmap old = slowBitmap;
        slowImage.setImageBitmap(ready);
        slowBitmap = ready;
        // V2.0 P0-02 (H2): pin the displayed slow frame so eviction cannot pool it
        // while it is on screen, and keep a time anchor for the timeline remap.
        if (frameCache != null) frameCache.markDisplayed(slowSourceId, index, 0);
        if (index < src.ptsUs.length) slowCurrentPtsUs = src.ptsUs[index];
        // V2.0: previous slow frame stays cached if resident (instant rewind);
        // otherwise it returns to the bitmap pool.
        releaseFrameBitmap(old);
        playerSeek.setProgress((int) (src.ptsUs[index] / 1000L));
        playerCurrent.setText(fmtClock(src.ptsUs[index] / 1000L));
        if (slowPlaying && index < src.frameCount - 1) {
            slowFrameIndex = index + 1;
            // Hardening spec S2.3: exact delta-PTS pacing with a one-frame (16.666 ms)
            // floor — VFR-safe and never faster than the display can show.
            long deltaPtsUs = Math.max(16_666L, src.ptsUs[index + 1] - src.ptsUs[index]);
            double speed = PlaybackMath.effectiveSpeed(PLAYBACK_SPEEDS[speedIndex], holdSlow);
            long interval = Math.max(1L, Math.round(deltaPtsUs / 1000.0 / speed));
            long spent = android.os.SystemClock.uptimeMillis() - begun;
            ui.postDelayed(slowFrameTick, Math.max(1L, interval - spent));
        } else if (index == src.frameCount - 1) slowPlaying = false;
        updatePlayerPlayButton();
    }

    /** V2.0: decode-ahead buffer sized by playback speed (0.25x / 0.10x / 0.05x). */
    private void prefetchSlowAhead(long sid, FrameSource src, int currentIndex) {
        if (frameCache == null || !slowActive) return;
        double speed = PlaybackMath.effectiveSpeed(PLAYBACK_SPEEDS[speedIndex], holdSlow);
        int ahead = speed <= 0.05 ? 40 : speed <= 0.10 ? 28 : 16;
        final long genAtSubmit = slowGeneration.get();
        frameCache.prefetchAhead(sid, src, currentIndex + 1, ahead, getSlowDecoder(),
                () -> slowGeneration.get() != genAtSubmit || !slowActive);
    }

    private void stopSlowMotion(boolean reset) {
        ui.removeCallbacks(slowFrameTick);
        slowGeneration.incrementAndGet();
        slowActive = false;
        slowPlaying = false;
        slowCurrentPtsUs = Long.MIN_VALUE;
        if (slowImage != null) { slowImage.setImageDrawable(null); slowImage.setVisibility(View.GONE); }
        releaseFrameBitmap(slowBitmap);     // V2.0: may be cache-resident — no direct recycle
        slowBitmap = null;
        FrameSource old = slowSource;
        slowSource = null;
        if (frameCache != null && old != null) frameCache.evictSource(slowSourceId);  // V2.0
        slowSourceId = 0L;
        if (old != null) getSlowDecoder().execute(old::close);
    }

    private void savePlayerScreenshot() {
        if (uiSource == null || source == null || videoView == null || !playerPrepared) return;
        final FrameSource screenshotSource = uiSource;
        final int index = PlaybackMath.nearestIndex(screenshotSource.ptsUs, Math.max(0L, videoView.getCurrentPosition()) * 1000L);
        getDecoder().execute(() -> {
            if (screenshotSource != source) return;
            Bitmap bmp = null;
            try { bmp = screenshotSource.getFrame(index); } catch (RuntimeException ignored) {}
            final Bitmap result = bmp;
            if (result == null) { ui.post(() -> Toast.makeText(this, "Screenshot failed", Toast.LENGTH_SHORT).show()); return; }
            getSaver().execute(() -> {
                String msg;
                // V2.0 P1-03: screenshot follows the same orientation policy as save.
                Bitmap out = applyRotationToBitmap(result, getEffectivePictureRotation());
                // Quality Pack: selected look baked into the screenshot.
                if (frameGradePreset > 0) {
                    Bitmap graded = ColorGrade.grade(out, frameGradePreset);
                    if (graded != out) {
                        if (out != result && !out.isRecycled()) out.recycle();
                        out = graded;
                    }
                }
                try { writePng(out, index); msg = frameGradePreset > 0
                        ? "Saved screenshot (Look: " + ColorGrade.name(frameGradePreset) + ") → Pictures/TRUE NAIN"
                        : "Saved screenshot → Pictures/TRUE NAIN"; }
                catch (Exception e) { msg = "Screenshot failed: " + e.getMessage(); }
                try {
                    if (out != result) out.recycle();
                    result.recycle();
                } catch (RuntimeException ignored) {}
                final String m = msg; ui.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
            });
        });
    }

    private void releasePlayer() {
        ui.removeCallbacks(playerTick);
        ui.removeCallbacks(playerControlsAutoHide);
        ui.removeCallbacks(zoomPanRefresh);
        holdSlow = false;
        stopSlowMotion(true);
        resetHwSpeedWatchdog();
        hwSpeedApplied = 1f;
        // Zoom overlay bitmap is pool-or-copy owned (never cache-owned) — pool release.
        if (zoomOverlayBmp != null && !zoomOverlayBmp.isRecycled()) BitmapPool.release(zoomOverlayBmp);
        zoomOverlayBmp = null;
        zoomOverlay = null;
        zoomOverlayKey = Integer.MIN_VALUE;
        zoomOverlayCropMode = false;
        slowImage = null;
        if (videoView != null) {
            try { videoView.stopPlayback(); } catch (RuntimeException ignored) {}
            videoView.setKeepScreenOn(false);
            videoView = null;
        }
        mediaPlayer = null;
        playerPrepared = false;
        playerOrientButton = null;
        speedPillBtn = null;
        lookPillBtn = null;
        playerTop    = null;
        playerBottom = null;
    }

    private void showPlayerMenu(View anchor) {
        ui.removeCallbacks(playerControlsAutoHide);
        PopupMenu popup = new PopupMenu(this, anchor);
        Menu menu = popup.getMenu();

        // (Playback submenu REMOVED — user notes: speed pill 1.0x kaafi hai, aur
        //  Resume Position / A-B Repeat kabhi manga nahi gaya tha.)

        SubMenu view = menu.addSubMenu("View");
        view.add("Change View").setOnMenuItemClickListener(i -> { showChangeViewDialog(); return true; });
        view.add("Fine Rotate (Straighten)").setOnMenuItemClickListener(i -> {
            toggleFineRotateMode(); return true;
        });
        // (Fullscreen + Rotate Content REMOVED — duplicates: pill ⛶ aur pill ↻.)
        SubMenu sizing = view.addSubMenu("Fit / Fill / 1:1");
        for (int i = 0; i < VIEW_LABELS.length; i++) {
            final int m = i;
            sizing.add(VIEW_LABELS[i]).setCheckable(true).setChecked(playerDisplayMode == i)
                    .setOnMenuItemClickListener(item -> {
                        playerDisplayMode = m; resetPlayerZoom(); return true;
                    });
        }
        view.add("Reset Zoom / Pan").setOnMenuItemClickListener(i -> {
            if (playerZc != null) playerZc.reset(true);   // v2.4.6: animated reset (single engine)
            resetPlayerZoom();                            // overlay + fine-angle + state cleanup
            return true;
        });
        view.add("Clean View").setOnMenuItemClickListener(i -> { setPlayerControlsVisible(false); return true; });
        view.add(gestureLocked ? "Unlock Gestures" : "Gesture Lock")
                .setOnMenuItemClickListener(i -> { gestureLocked = !gestureLocked; return true; });

        // (Quality Look ⋮ se REMOVED — duplicate. Neeche glass pill 🎨 hi selector hai;
        //  Frame Mode ka apna look selector Frame ke ⋮ mein hai.)

        SubMenu tools = menu.addSubMenu("Tools");
        tools.add("Video Library").setOnMenuItemClickListener(i -> { preferredLibraryMode = mode; showLibrary(); return true; });
        tools.add("Customize Controls").setOnMenuItemClickListener(i -> { showControlSettings(); return true; });
        tools.add("Extract All Frames").setOnMenuItemClickListener(i -> { extractAllFrames(); return true; });
        tools.add("Open New Video").setOnMenuItemClickListener(i -> { openAnotherVideo(); return true; });
        tools.add("Trim + Merge").setOnMenuItemClickListener(i -> { openTrim(); return true; });
        tools.add("Reset Settings").setOnMenuItemClickListener(i -> { confirmResetSettings(); return true; });
        // (Frame Mode + Quick Screenshot REMOVED — duplicates of pill ▣ aur 📷.)
        tools.add("Find Sharper Frame (nearby)").setOnMenuItemClickListener(i -> {
            if (videoView != null && playerPrepared) {
                findSharperNearby(videoView.getCurrentPosition() * 1000L, MODE_PLAYER);
            }
            return true;
        });

        popup.setOnDismissListener(m -> { if (controlsVisible) schedulePlayerControlsHide(); });
        popup.show();
    }

    // (V2.0.6 split-compare / ⇔ CMP REMOVED — user: "main ne kabi nh kaha".
    //  All buttons are now wired into Customize Controls instead.)

    // ══════════════════════════════════════════════════════════ FRAME MODE

    private void showFrame() {
        buildFrameUi();
        startFrameAt(syncPositionUs);
    }

    private void startFrameAt(long positionUs) {
        if (uiSource == null) return;
        int idx = PlaybackMath.nearestIndex(uiSource.ptsUs, positionUs);
        requestedIndex = idx;
        displayedIndex = -1;
        firstFrameAfterOpen = true;
        frameSeek.setMax(Math.max(0, uiSource.frameCount - 1));
        frameSeek.setProgress(idx);
        updateFrameInfo();
        requestFrame(idx);
    }

    private void buildFrameUi() {
        controlViews.clear();
        modeContainer.removeAllViews();
        // v2.4.4 fix (Notes #7/#8/#9): stale `landscape` flag ki jagah ASLI
        // orientation — warna portrait mein landscape-built panel (nav side par)
        // ya landscape mein clipped actions pill ban jata tha.
        boolean land = isLandscapeNow();
        frameBuiltLandscape = land;

        viewer = new ZoomableImageView(this);
        viewer.setBackgroundColor(COLOR_BG);
        viewer.setContentDescription("Video frame");
        viewer.setOnSettledListener(this::onFrameSettledSharp);   // v2.4.6: settled bicubic crop
        viewer.setGestureActions(new ZoomableImageView.GestureActions() {
            @Override public void singleTap() { if (!gestureLocked) setFrameControlsVisible(!frameControlsVisible); }
            // v2.4.4 (user request): RIGHT double-tap = +1 frame, LEFT double-tap = -1 frame.
            @Override public void doubleTap(float x) {
                if (uiSource == null || viewer == null) return;
                manualStep(x < viewer.getWidth() / 2f ? -1 : +1);
                resetFrameControlsTimer();
            }
            // v2.4.4 (user request): press-and-hold = 5 frames us side ki taraf.
            @Override public void hold(boolean active, float x) {
                if (!active || uiSource == null || viewer == null) return;
                manualStep(x < viewer.getWidth() / 2f ? -5 : +5);
                resetFrameControlsTimer();
            }
            @Override public void interaction(boolean active) {
                if (gestureLocked) return;
                if (active) ui.removeCallbacks(frameControlsAutoHide);
                else if (frameControlsVisible) scheduleFrameControlsHide();
            }
        });

        LinearLayout top   = buildFrameTopBar();   // glass style — player jaisa look
        LinearLayout panel = buildFramePanel(land);
        frameTop = top;
        frameBottom = panel;
        frameControlsVisible = true;

        FrameLayout f = new FrameLayout(this);
        // V2.0.6 split-compare: viewer + optional second pane side by side.
        LinearLayout frameRow = new LinearLayout(this);
        frameRow.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout viewerWrap = new FrameLayout(this);
        viewerWrap.addView(viewer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rotateOverlayFrame = makeRotateOverlay();   // v2.4.5: Fine Rotate drag overlay
        viewerWrap.addView(rotateOverlayFrame, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frameRow.addView(viewerWrap, new LinearLayout.LayoutParams(0, -1, 1f));

        if (!land) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(top, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            col.addView(frameRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            col.addView(panel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            f.addView(col, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            f.addView(frameRow, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            f.addView(top, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
            f.addView(panel, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        }
        modeContainer.addView(f, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyFrameState();
        scheduleFrameControlsHide();
    }

    /** Glass-style Frame Mode top row: ‹ back + title (left), ⛶ ⋮ pill (right). */
    private LinearLayout buildFrameTopBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));

        Button back = pillIcon("‹", v -> onBackPressed());
        back.setTextSize(22);
        back.setContentDescription("Back");
        registerControl("top_back", back);
        row.addView(back, new LinearLayout.LayoutParams(dp(42), dp(40)));

        TextView title = text(currentName == null ? "FRAME MODE" : currentName, 13, Color.WHITE, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        title.setShadowLayer(dp(3), 0, 0, 0xCC020810);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(8);
        tlp.rightMargin = dp(8);
        row.addView(title, tlp);

        LinearLayout pill = glassPill();
        Button fs = pillIcon("⛶", v -> toggleFullscreen());
        fs.setContentDescription("Fullscreen");
        pill.addView(fs, new LinearLayout.LayoutParams(dp(44), dp(40)));
        Button look = pillIcon("🎨", v -> showLookPopup(v, true));
        look.setContentDescription("Quality look");
        pill.addView(look, new LinearLayout.LayoutParams(dp(44), dp(40)));
        Button rot = pillIcon("↻", v -> cycleFrameRotation());
        rot.setContentDescription("Rotate frame");
        pill.addView(rot, new LinearLayout.LayoutParams(dp(44), dp(40)));
        Button fineRot = pillIcon("360", v -> toggleFineRotateMode());   // v2.4.6: font-proof label
        fineRot.setTextSize(11);
        fineRot.setContentDescription("Fine rotate 360");
        pill.addView(fineRot, new LinearLayout.LayoutParams(dp(44), dp(40)));   // v2.4.6 FIX: pill mein add missing tha
        Button more = pillIcon("⋮", this::showFrameMenu);
        more.setContentDescription("More options");
        registerControl("top_menu", more);
        pill.addView(more, new LinearLayout.LayoutParams(dp(44), dp(40)));
        row.addView(pill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** Frame rotate cycle: har tap par frame 90° ghoomta hai — koi menu nahi. */
    private void cycleFrameRotation() {
        displayRotation = (displayRotation + 90) % 360;
        if (viewer != null) {
            viewer.setDisplayRotation(displayRotation);
            viewer.setFineRotation(displayRotation + frameFineRotation);   // fine angle qaim rakho
        }
    }

    /** v2.4.5 (user request — Windows-Photos-jaisa magar SAADA): ⟲ icon se ROTATE
     *  MODE ON/OFF.  ON par video par FINGER/MOUSE drag = free rotation (idhar-udhar-
     *  upar-neeche-gol jahan marzi), Arrow keys ← → = 1° steps.  Neeche sirf angle
     *  number — koi slider / extra options nahi.  OFF par angle set rehta hai. */
    private void toggleFineRotateMode() {
        android.util.Log.d("TN360", "toggle: mode=" + mode + " uiSource=" + (uiSource != null)
                + " viewer=" + (viewer != null) + " videoView=" + (videoView != null)
                + " overlayFrame=" + (rotateOverlayFrame != null)
                + " overlayPlayer=" + (rotateOverlayPlayer != null));
        if (mode != MODE_FRAME && mode != MODE_PLAYER) return;
        if (mode == MODE_FRAME && (uiSource == null || viewer == null)) return;
        if (mode == MODE_PLAYER && (uiSource == null || videoView == null)) return;
        fineRotateMode = !fineRotateMode;
        android.util.Log.d("TN360", "toggled=" + fineRotateMode);
        if (fineRotateMode) {
            updateRotateOverlay();
            android.util.Log.d("TN360", "overlayFrame visible="
                    + (rotateOverlayFrame != null && rotateOverlayFrame.getVisibility() == View.VISIBLE)
                    + " overlayPlayer visible="
                    + (rotateOverlayPlayer != null && rotateOverlayPlayer.getVisibility() == View.VISIBLE));
        } else {
            if (rotateOverlayFrame != null) rotateOverlayFrame.setVisibility(View.GONE);
            if (rotateOverlayPlayer != null) rotateOverlayPlayer.setVisibility(View.GONE);
        }
        Toast.makeText(this, fineRotateMode
                ? "Fine Rotate ON — video par drag karo, ← → keys se 1°"
                : "Fine Rotate OFF — angle set", Toast.LENGTH_SHORT).show();
    }

    /** Drag/arrow overlay: poora touch yahan rotate banta hai, neeche angle pill. */
    private FrameLayout makeRotateOverlay() {
        FrameLayout o = new FrameLayout(this);
        View touch = new View(this);
        touch.setContentDescription("Fine rotate drag area");
        TextView ang = text("0.0°", 14, Color.WHITE, true);
        ang.setGravity(Gravity.CENTER);
        ang.setBackground(roundRect(0xB3020810, 20, 0, 0));
        ang.setPadding(dp(14), dp(6), dp(14), dp(6));
        touch.setOnTouchListener(rotateOverlayTouch(ang));
        o.addView(touch, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        alp.bottomMargin = dp(28);
        o.addView(ang, alp);
        o.setVisibility(View.GONE);
        return o;
    }

    /** Center ke gird finger ka angle = rotation dial (Windows-Photos-style, drag se). */
    private View.OnTouchListener rotateOverlayTouch(TextView ang) {
        return (v, e) -> {
            float cx = v.getWidth() / 2f, cy = v.getHeight() / 2f;
            float angle = (float) Math.toDegrees(Math.atan2(e.getY() - cy, e.getX() - cx));
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    rotStartTouch = angle;
                    rotStartRot = currentRotationAngle();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float delta = angle - rotStartTouch;
                    while (delta > 180f) delta -= 360f;
                    while (delta < -180f) delta += 360f;
                    applyRotationAngle(rotStartRot + delta);
                    ang.setText(String.format(Locale.US, "%.1f°", currentRotationAngle()));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
            }
            return true;
        };
    }

    /** Active mode ka overlay dikhao (doosra chhupao) + angle number set karo. */
    private void updateRotateOverlay() {
        FrameLayout target = (mode == MODE_FRAME) ? rotateOverlayFrame : rotateOverlayPlayer;
        FrameLayout other  = (mode == MODE_FRAME) ? rotateOverlayPlayer : rotateOverlayFrame;
        if (other != null) other.setVisibility(View.GONE);
        if (target != null) {
            target.setVisibility(View.VISIBLE);
            TextView ang = (TextView) target.getChildAt(1);
            if (ang != null) ang.setText(String.format(Locale.US, "%.1f°", currentRotationAngle()));
        }
    }

    /** Total rotation angle (90-steps + fine) — active mode ke hisaab se. */
    private float currentRotationAngle() {
        if (mode == MODE_FRAME && viewer != null) return viewer.getRotationDeg();
        if (mode == MODE_PLAYER && videoView != null) return videoView.getRotation();
        return 0f;
    }

    /** Drag/arrow ka total angle apply — Frame (ZoomableImageView) ya Player (VideoView). */
    private void applyRotationAngle(float total) {
        if (mode == MODE_FRAME && viewer != null) {
            frameFineRotation = total - displayRotation;
            viewer.setFineRotation(total);
        } else if (mode == MODE_PLAYER && videoView != null) {
            playerFineRotation = total - playerContentRotation;
            applyPlayerViewTransform();   // naya rotation + pan clamp lagata hai
        }
    }

    private LinearLayout buildFramePanel(boolean land) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        // Glass look: sheet background hata — floating info + glass pills (player jaisa).
        box.setPadding(dp(10), dp(4), dp(10), dp(10));

        frameInfo = new TextView(this);
        frameInfo.setTextSize(11);
        frameInfo.setTextColor(COLOR_FRAME_A);
        frameInfo.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        frameInfo.setSingleLine(true);
        frameInfo.setShadowLayer(dp(2), 0, 0, 0xCC020810);
        frameInfo.setPadding(dp(4), dp(1), dp(4), dp(2));
        box.addView(frameInfo);

        frameSeek = new SeekBar(this);
        styleSeekBar(frameSeek, COLOR_FRAME_A);
        frameSeek.setContentDescription("Frame position");
        frameSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && uiSource != null && !frameLocked) { goTo(progress); resetFrameControlsTimer(); }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { ui.removeCallbacks(frameControlsAutoHide); }
            @Override public void onStopTrackingTouch(SeekBar bar) { scheduleFrameControlsHide(); }
        });
        box.addView(frameSeek);

        // Frame navigation glass pill: -10 -1 ▶ +1 +10 (long-press repeat preserved).
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.HORIZONTAL);
        rows.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout navPill = glassPill();
        // v2.4.4 (user request): fullscreen par step/action buttons CHOTE — normal
        // mode mein normal size.  Visibility Customize Controls se hi aati hai.
        boolean compact = fullscreen;
        int  pillH   = compact ? dp(30) : dp(40);
        int  pillW1  = compact ? dp(34) : dp(46);
        int  pillW10 = compact ? dp(38) : dp(48);
        float pillText = compact ? 11f : 13f;
        Button back10 = pillIcon("-10", v -> { manualStep(-10); resetFrameControlsTimer(); });
        back10.setTextSize(pillText);
        Button back1 = pillIcon("-1", null);
        framePlayButton = pillIcon("▶", v -> { toggleAutoPlay(); resetFrameControlsTimer(); });
        Button next1 = pillIcon("+1", null);
        Button next10 = pillIcon("+10", v -> { manualStep(10); resetFrameControlsTimer(); });
        next10.setTextSize(pillText);
        registerControl("frame_nav", back10);
        registerControl("frame_nav", back1);
        registerControl("frame_play", framePlayButton);
        registerControl("frame_nav", next1);
        registerControl("frame_nav", next10);
        back10.setContentDescription("Back 10 frames");
        back1.setContentDescription("Previous frame");
        next1.setContentDescription("Next frame");
        next10.setContentDescription("Forward 10 frames");
        attachFrameRepeatButton(back1, -1);
        attachFrameRepeatButton(next1, 1);
        navPill.addView(back10, new LinearLayout.LayoutParams(pillW10, pillH));
        navPill.addView(back1, new LinearLayout.LayoutParams(pillW1, pillH));
        navPill.addView(framePlayButton, new LinearLayout.LayoutParams(pillW1, pillH));
        navPill.addView(next1, new LinearLayout.LayoutParams(pillW1, pillH));
        navPill.addView(next10, new LinearLayout.LayoutParams(pillW10, pillH));
        rows.addView(navPill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        rows.addView(new android.widget.Space(this), new LinearLayout.LayoutParams(0, 1, 1f));

        // Actions glass pill: FPS — SAVE — zoom −/＋.
        LinearLayout actionsPill = glassPill();
        fpsButton = pillIcon("FPS", v -> { showFpsDialog(); resetFrameControlsTimer(); });
        fpsButton.setTextSize(compact ? 10f : 12f);
        Button save = pillIcon("SAVE", v -> { saveCurrentFrame(); resetFrameControlsTimer(); });
        save.setTextSize(compact ? 10f : 12f);
        registerControl("frame_save", save);
        Button zoomOut = pillIcon("−", v -> { if (viewer != null) viewer.zoomBy(0.8f); resetFrameControlsTimer(); });
        Button zoomIn = pillIcon("＋", v -> { if (viewer != null) viewer.zoomBy(1.25f); resetFrameControlsTimer(); });
        zoomOut.setContentDescription("Zoom out");
        zoomIn.setContentDescription("Zoom in");
        fpsButton.setContentDescription("Frame rate");
        save.setContentDescription("Save frame");
        actionsPill.addView(fpsButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, pillH));
        actionsPill.addView(save, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, pillH));
        actionsPill.addView(zoomOut, new LinearLayout.LayoutParams(pillW1, pillH));
        actionsPill.addView(zoomIn, new LinearLayout.LayoutParams(pillW1, pillH));
        rows.addView(actionsPill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        box.addView(rows, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        applyControlOrder(navPill);
        applyControlOrder(actionsPill);
        return box;
    }

    private void applyFrameState() {
        viewer.setDisplayMode(displayMode);
        viewer.setDisplayRotation(displayRotation);
        viewer.setFineRotation(displayRotation + frameFineRotation);   // v2.4.4: 90-steps + fine angle dono lagte hain
        if (currentBitmap != null && !currentBitmap.isRecycled()) {
            // Quality Look ON: graded display copy dikhao (original cache/save ke liye).
            if (frameGradePreset > 0 && shownBitmap != null && !shownBitmap.isRecycled())
                viewer.setFrame(shownBitmap, false);
            else
                viewer.setFrame(currentBitmap, false);
        }
        if (uiSource != null) {
            frameSeek.setMax(Math.max(0, uiSource.frameCount - 1));
            frameSeek.setProgress(requestedIndex);
        }
        updateFrameInfo();
        updateFrameButtons();
        refreshOrientationButtons();
    }

    private void updateFrameButtons() {
        if (framePlayButton != null) {
            boolean playing = autoPlaying;
            framePlayButton.setText(playing ? "⏸" : "▶");   // bare icon — glass look
            framePlayButton.setTextColor(Color.WHITE);
            framePlayButton.setContentDescription(playing ? "Pause frame playback" : "Play frames");
        }
        if (fpsButton != null)
            fpsButton.setText("FPS " + (playbackFps <= 0 ? "AUTO" : String.valueOf(playbackFps)));
    }

    /** V2.0.3: frame info moved off the preview into a dialog (⋮ → Frame Info). */
    private void showFrameInfoDialog() {
        if (uiSource == null) { Toast.makeText(this, "Open a video first", Toast.LENGTH_SHORT).show(); return; }
        int shown = Math.max(0, Math.min(uiSource.frameCount - 1,
                displayedIndex >= 0 ? displayedIndex : requestedIndex));
        new AlertDialog.Builder(this).setTitle("Frame Info")
                .setMessage(String.format(Locale.US,
                        "Frame:  %d / %d\nTime:  %s / %s\nSource:  %s fps",
                        shown + 1, uiSource.frameCount,
                        fmtTime(uiSource.ptsUs[shown]), fmtDuration(uiSource.durationUs),
                        fmtFps(uiSource.fps)))
                .setPositiveButton("OK", null).show();
    }

    private void showFrameMenu(View anchor) {
        ui.removeCallbacks(frameControlsAutoHide);
        PopupMenu popup = new PopupMenu(this, anchor);
        Menu menu = popup.getMenu();

        // (Quality Look ⋮ se REMOVED — duplicate. Top glass pill 🎨 hi selector hai,
        //  aur look display par LIVE lagta hai + SAVE par bake hota hai.)

        SubMenu views = menu.addSubMenu("View");
        views.add("Change View").setOnMenuItemClickListener(i -> { showChangeViewDialog(); return true; });
        views.add("Fullscreen").setOnMenuItemClickListener(i -> { toggleFullscreen(); return true; });
        // v2.4.4 (Bug #2): Frame Mode ka bhi reset — rotate+zoom ke baad black
        // areas / ghalt rotation se nikalne ka ek asaan raasta.
        views.add("Reset Zoom / Rotate").setOnMenuItemClickListener(i -> {
            displayRotation = 0;
            frameFineRotation = 0f;
            if (viewer != null) viewer.setDisplayRotation(0);   // andar resetZoom + invalidate — zoom bhi reset
            return true;
        });
        // v2.4.4 (user request): tirchhi videos ko FREE angle par seedha karne ka option.
        views.add("Fine Rotate (Seedha)").setOnMenuItemClickListener(i -> { toggleFineRotateMode(); return true; });
        for (int i = 0; i < VIEW_LABELS.length; i++) {
            final int m = i;
            views.add(VIEW_LABELS[i]).setCheckable(true).setChecked(displayMode == i)
                    .setOnMenuItemClickListener(item -> {
                        displayMode = m; if (viewer != null) viewer.setDisplayMode(m); return true;
                    });
        }
        SubMenu rotations = views.addSubMenu("Rotate Content");
        for (int angle : new int[]{0, 90, 180, 270}) {
            final int a = angle;
            rotations.add(angle + "°").setCheckable(true).setChecked(displayRotation == angle)
                    .setOnMenuItemClickListener(item -> {
                        displayRotation = a;
                        if (viewer != null) {
                            viewer.setDisplayRotation(a);
                            viewer.setFineRotation(a + frameFineRotation);   // v2.4.4: fine angle qaim rakho
                        }
                        return true;
                    });
        }
        views.add("Clean View").setOnMenuItemClickListener(i -> { setFrameControlsVisible(false); return true; });

        SubMenu tools = menu.addSubMenu("Tools");
        tools.add("Switch to Media Player").setOnMenuItemClickListener(i -> { switchMode(MODE_PLAYER); return true; });
        tools.add("Go To Frame").setOnMenuItemClickListener(i -> { goToFrameDialog(); return true; });
        tools.add("Frame Info").setOnMenuItemClickListener(i -> { showFrameInfoDialog(); return true; });
        tools.add("FPS Settings").setOnMenuItemClickListener(i -> { showFpsDialog(); return true; });
        tools.add("Find Sharper Frame (nearby)").setOnMenuItemClickListener(i -> {
            if (uiSource != null && requestedIndex >= 0 && requestedIndex < uiSource.frameCount)
                findSharperNearby(uiSource.ptsUs[requestedIndex], MODE_FRAME);
            return true;
        });
        tools.add(frameLocked ? "Unlock Frame" : "Frame Lock").setOnMenuItemClickListener(i -> {
            frameLocked = !frameLocked;
            if (frameLocked) stopAutoPlay();
            return true;
        });
        tools.add(gestureLocked ? "Unlock Gestures" : "Gesture Lock").setOnMenuItemClickListener(i -> {
            gestureLocked = !gestureLocked; return true;
        });
        tools.add("Video Library").setOnMenuItemClickListener(i -> { preferredLibraryMode = mode; showLibrary(); return true; });
        tools.add("Customize Controls").setOnMenuItemClickListener(i -> { showControlSettings(); return true; });
        tools.add("Extract All Frames").setOnMenuItemClickListener(i -> { extractAllFrames(); return true; });
        tools.add("Open New Video").setOnMenuItemClickListener(i -> { openAnotherVideo(); return true; });
        tools.add("Reset Settings").setOnMenuItemClickListener(i -> { confirmResetSettings(); return true; });

        popup.setOnDismissListener(m -> { if (frameControlsVisible) scheduleFrameControlsHide(); });
        popup.show();
    }

    /** FPS selector: AUTO + 1..source FPS. Spec § 8 & 9. */
    private void showFpsDialog() {
        if (uiSource == null) { Toast.makeText(this, "Open a video first", Toast.LENGTH_SHORT).show(); return; }
        final int max = PlaybackMath.maxManualFps(uiSource.fps);
        String[] items = new String[max + 1];
        items[0] = "AUTO  —  source frame timing";
        for (int i = 1; i <= max; i++) items[i] = i + " fps";
        final int checked = Math.max(0, Math.min(max, playbackFps));
        ui.removeCallbacks(frameControlsAutoHide);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("FPS Selector  ·  source " + fmtFps(uiSource.fps) + " fps")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    playbackFps = which;
                    updateFrameButtons();
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnDismissListener(d -> { if (frameControlsVisible) scheduleFrameControlsHide(); });
        dialog.show();
    }

    private void goToFrameDialog() {
        if (uiSource == null || loading) return;
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("1 – " + uiSource.frameCount);
        ui.removeCallbacks(frameControlsAutoHide);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Go To Frame")
                .setView(input)
                .setPositiveButton("Go", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w -> {
            try {
                int frame = Integer.parseInt(input.getText().toString().trim());
                if (frame < 1 || frame > uiSource.frameCount) throw new NumberFormatException();
                stopAutoPlay();
                goTo(frame - 1);
                dialog.dismiss();
            } catch (NumberFormatException e) {
                input.setError("Enter 1–" + uiSource.frameCount);
            }
        }));
        dialog.setOnDismissListener(d -> { if (frameControlsVisible) scheduleFrameControlsHide(); });
        dialog.show();
    }

    private void seekForwardFiveSeconds() {
        if (uiSource == null || loading) return;
        int index  = displayedIndex >= 0 ? displayedIndex : requestedIndex;
        int target = PlaybackMath.forwardFiveSeconds(uiSource.ptsUs, index);
        boolean resume = autoPlaying;
        stopAutoPlay();
        goTo(target);
        if (resume && target < uiSource.frameCount - 1) toggleAutoPlay();
    }

    /** Bounded sharpness search around the user's current time; no generated pixels. */
    private void findSharperNearby(long centerUs, int requestedMode) {
        final FrameSource selected = uiSource;
        if (selected == null || loading || selected.frameCount == 0) return;
        final long center = centerUs;
        Toast.makeText(this, "Checking nearby source frames…", Toast.LENGTH_SHORT).show();
        getDecoder().execute(() -> {
            final int mid = PlaybackMath.nearestIndex(selected.ptsUs, center);
            // Nine evenly spaced source frames within +/- 1 second. On long GOPs
            // this can take time; no resolution-dependent frame cache growth.
            int bestIndex = -1;
            double bestScore = -1;
            for (int n = 0; n < 9; n++) {
                if (selected != source || isDestroyed()) return;
                long sampleUs = Math.max(0L, center + (n - 4) * 250_000L);
                int index = PlaybackMath.nearestIndex(selected.ptsUs, sampleUs);
                if (index < 0 || index >= selected.frameCount) continue;
                Bitmap frame = selected.getFrame(index);
                if (frame == null) continue;
                try {
                    double score = SharpFrameScorer.score(frame);
                    if (score > bestScore) {
                        bestScore = score;
                        bestIndex = index;
                    }
                } finally {
                    if (!frame.isRecycled()) frame.recycle();
                }
            }
            final int winner = bestIndex;
            ui.post(() -> {
                if (selected != uiSource || mode != requestedMode || isDestroyed()) return;
                if (winner < 0) {
                    Toast.makeText(this, "No candidate frame could be decoded", Toast.LENGTH_LONG).show();
                    return;
                }
                if (requestedMode == MODE_FRAME) {
                    goTo(winner);
                } else if (mediaPlayer != null && playerPrepared) {
                    if (videoView != null && videoView.isPlaying()) videoView.pause();
                    mediaPlayer.seekTo(selected.ptsUs[winner] / 1000L, MediaPlayer.SEEK_CLOSEST);
                    updatePlayerPlayButton();
                }
                Toast.makeText(this, "Sharper candidate at frame " + (winner + 1)
                        + " (edge-score estimate, not restored detail)", Toast.LENGTH_LONG).show();
            });
        });
    }

    // ─────────────────────────────────── frame navigation / decoding

    private void manualStep(int delta) {
        if (uiSource == null) {
            Toast.makeText(this, "Open a video first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (frameLocked) {
            Toast.makeText(this, "Frame is locked", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean wasPlaying = autoPlaying;
        if (wasPlaying) ui.removeCallbacks(autoPlayRunnable);
        int target = Math.max(0, Math.min(uiSource.frameCount - 1, requestedIndex + delta));
        requestedIndex = target;
        if (frameSeek != null) frameSeek.setProgress(target);
        updateFrameInfo();
        if (Math.abs(delta) == 1) requestExactFrame(target);
        else requestFrameWalk(target, delta);   // v2.4.5: ±10/±5 foran move (walk, na rukna)
        // +/-1 and +/-10 never toggle play/pause state. During long-press the normal
        // auto-play ticker is suspended and restored only when the finger is released.
        if (wasPlaying && !frameRepeatActive)
            ui.postDelayed(autoPlayRunnable, playbackDelayMs(requestedIndex));
    }

    private void attachFrameRepeatButton(Button button, int delta) {
        button.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (frameLocked) {
                    Toast.makeText(this, "Frame is locked", Toast.LENGTH_SHORT).show();
                    return true;
                }
                frameRepeatActive = true;
                frameRepeatLong = false;
                frameRepeatDelta = delta;
                frameRepeatResumeAuto = autoPlaying;
                if (frameRepeatResumeAuto) ui.removeCallbacks(autoPlayRunnable);
                ui.removeCallbacks(frameControlsAutoHide);
                ui.removeCallbacks(frameRepeatRunnable);
                ui.postDelayed(frameRepeatRunnable, 350L);
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                boolean wasLong = frameRepeatLong;
                boolean resumeAuto = frameRepeatResumeAuto;
                frameRepeatActive = false;
                frameRepeatResumeAuto = false;
                ui.removeCallbacks(frameRepeatRunnable);
                if (!wasLong && action == MotionEvent.ACTION_UP) manualStep(delta);
                if (resumeAuto && autoPlaying && uiSource != null
                        && requestedIndex < uiSource.frameCount - 1) {
                    ui.removeCallbacks(autoPlayRunnable);
                    ui.postDelayed(autoPlayRunnable, playbackDelayMs(requestedIndex));
                }
                scheduleFrameControlsHide();
                v.performClick();
                return true;
            }
            return true;
        });
    }

    private long frameRepeatDelayMs() {
        double fps = playbackFps > 0 ? playbackFps : (uiSource != null && uiSource.fps > 0 ? uiSource.fps : 10.0);
        fps = Math.max(1.0, Math.min(60.0, fps));
        return Math.max(16L, Math.round(1000.0 / fps));
    }

    private void setFrameControlsVisible(boolean visible) {
        frameControlsVisible = visible;
        setControlViewVisible(frameTop, visible);
        setControlViewVisible(frameBottom, visible);
        ui.removeCallbacks(frameControlsAutoHide);
        if (visible) scheduleFrameControlsHide();
    }

    private void scheduleFrameControlsHide() {
        ui.removeCallbacks(frameControlsAutoHide);
        if (mode == MODE_FRAME && frameControlsVisible && !frameRepeatActive)
            ui.postDelayed(frameControlsAutoHide, 30_000L);
    }

    private void resetFrameControlsTimer() {
        if (!frameControlsVisible) setFrameControlsVisible(true);
        else scheduleFrameControlsHide();
    }

    private void goTo(int index) {
        if (uiSource == null || loading) return;
        int clamped = Math.max(0, Math.min(uiSource.frameCount - 1, index));
        if (clamped == requestedIndex && clamped == displayedIndex) return;
        requestedIndex = clamped;
        if (frameSeek != null) frameSeek.setProgress(clamped);
        updateFrameInfo();
        requestFrame(clamped);
    }

    private void requestFrame(int index) {
        exactFrameQueue.clear();
        targetIndex.set(index);
        frameRequestSeq.incrementAndGet();          // V2.0: version bump → stale decodes self-discard
        if (decodeRunning.compareAndSet(false, true)) getDecoder().execute(this::decodeLoop);
    }

    private void requestExactFrame(int index) {
        targetIndex.set(index);
        exactFrameQueue.offer(index);
        frameRequestSeq.incrementAndGet();
        if (decodeRunning.compareAndSet(false, true)) getDecoder().execute(this::decodeLoop);
    }

    /** v2.4.6 PHASE 1 (pack): settled par visible region ka BICUBIC sharp crop.
     *  v2.4.7 BUG 1 (pack): intersect() ka return value CHECK — rotated visible rect
     *  bitmap se intersect na kare to EMPTY/invalid crop banta tha → BLACK screen.
     *  Ab: empty/invalid/failed crop par overlay draw NAHI hota — normal frame dikhta hai. */
    private void onFrameSettledSharp() {
        if (currentBitmap == null || currentBitmap.isRecycled() || viewer == null) return;
        final Bitmap bmp = currentBitmap;
        final android.graphics.RectF vis = viewer.getBitmapVisibleRect();
        final int gen = viewer.getZcGeneration();
        final float rot = viewer.getRotationDeg();   // L1: crop ko screen ki rotation ke hisaab se ghumana hai
        final int vw = Math.max(8, viewer.getWidth()), vh = Math.max(8, viewer.getHeight());
        // v2.4.7 BUG 1: sanity BEFORE decode — empty/inverted rect → no overlay at all
        if (vis.isEmpty() || vis.width() < 8 || vis.height() < 8
                || vis.right <= 0 || vis.bottom <= 0
                || vis.left >= bmp.getWidth() || vis.top >= bmp.getHeight()) return;
        getDecoder().execute(() -> {
            try {
                if (bmp.isRecycled()) return;
                int l = Math.max(0, (int) Math.floor(vis.left));
                int t = Math.max(0, (int) Math.floor(vis.top));
                int r = Math.min(bmp.getWidth(), (int) Math.ceil(vis.right));
                int b = Math.min(bmp.getHeight(), (int) Math.ceil(vis.bottom));
                if (r - l < 8 || b - t < 8) return;   // degenerate crop → normal frame rahe
                Bitmap region = Bitmap.createBitmap(bmp, l, t, r - l, b - t);
                if (region == null || region.getWidth() <= 0 || region.getHeight() <= 0) return;
                if (region == bmp) region = bmp.copy(Bitmap.Config.ARGB_8888, false);   // L1: cached frame kabhi recycle na ho
                if (region == null) return;
                // L1 FIX (rotate black/ghalat frame): crop bitmap-space ka hai; screen par
                // rotated frame hai — pehle crop ko wahi rotation do, phir scale karo.
                if (Math.round(rot) % 360 != 0) {
                    android.graphics.Matrix rm = new android.graphics.Matrix();
                    rm.postRotate(rot);
                    Bitmap rotated = Bitmap.createBitmap(region, 0, 0,
                            region.getWidth(), region.getHeight(), rm, true);
                    if (rotated != region) region.recycle();
                    region = rotated;
                }
                Bitmap sharp = SharpScale.scale(region, vw, vh);
                region.recycle();
                // v2.4.7 BUG 1: sharp null/invalid/recycled → overlay NAHI — normal frame rahe
                if (sharp == null || sharp.isRecycled()
                        || sharp.getWidth() <= 0 || sharp.getHeight() <= 0) return;
                // v2.4.6 PHASE 2: settled crop par bhi Quality Look (zoom-aware) —
                // warna zoomed frame look ke bina raw dikhta (pack: "run Quality Look, then post")
                if (frameGradePreset > 0 && viewer != null) {
                    Bitmap graded = ColorGrade.grade(sharp, frameGradePreset, viewer.getZoom());
                    if (graded != null && !graded.isRecycled() && graded != sharp) {
                        sharp.recycle();
                        sharp = graded;
                    }
                }
                final Bitmap finalSharp = sharp;
                ui.post(() -> { if (viewer != null) viewer.setSharpCrop(finalSharp, gen); });
            } catch (RuntimeException rte) { /* fast path stays — normal frame dikhta rahe */ }
        });
    }

    /** v2.4.5 (user request): ±10 click par frame RUKNA nahi — foran MOVE hona.
     *  Intermediate frames queue mein decode hotay huay DISPLAY hote hain (counter
     *  1-1 karke chalta hai), target par ruk kar break.  Naya request aaye to queue
     *  clear ho jati hai (requestFrame) — koi stale decode nahi. */
    private void requestFrameWalk(int target, int delta) {
        exactFrameQueue.clear();
        targetIndex.set(target);
        frameRequestSeq.incrementAndGet();
        int from = lastDecodedIndex;
        if (from < 0 || Math.abs(target - from) > Math.abs(delta) + 4) {
            requestFrame(target);       // door ka jump: walk bekaar — seedha target
            return;
        }
        int step = delta >= 0 ? 1 : -1;
        for (int i = from + step; i != target + step; i += step) exactFrameQueue.offer(i);
        if (decodeRunning.compareAndSet(false, true)) getDecoder().execute(this::decodeLoop);
    }

    /** V2.0: safe release for a bitmap that may be owned by the frame cache. */
    private void releaseFrameBitmap(Bitmap b) {
        if (b == null || b.isRecycled() || b == savingBitmap) return;
        FrameCacheManager fc = frameCache;
        if (fc != null) fc.release(b);
        else b.recycle();
    }

    // v2.4.6 PHASE 3 (audit fix 4): keyframe-first seek — SYNC foran (hang nahi),
    // 350 ms idle ke baad CLOSEST refine (paused par exact frame).  Naya seek purane
    // refine ko cancel karta hai (generation guard).
    private long playerSeekGen = 0;
    private Runnable playerSeekRefine;

    private void seekRefine(long targetMs) {
        final long gen = ++playerSeekGen;
        ui.removeCallbacks(playerSeekRefine);
        playerSeekRefine = () -> {
            if (gen == playerSeekGen && !slowActive && playerPrepared
                    && mediaPlayer != null && videoView != null && !videoView.isPlaying())
                mediaPlayer.seekTo(targetMs, MediaPlayer.SEEK_CLOSEST);
        };
        ui.postDelayed(playerSeekRefine, 350L);
    }

    private void decodeLoop() {
        try {
            while (true) {
                Integer queued = exactFrameQueue.poll();
                int t = queued != null ? queued : targetIndex.get();
                FrameSource s = source;
                long sid = frameSourceId;
                if (s == null || t < 0 || t >= s.frameCount) break;
                if (queued == null && t == lastDecodedIndex) break;
                long myVersion = frameRequestSeq.get();
                Bitmap bmp;
                Trace.beginSection("TN:decodeLoop");
                try {
                    // V2.0: cache-first — a prepared frame renders without touching the codec.
                    FrameData cached = frameCache != null ? frameCache.get(sid, t, 0) : null;
                    if (cached != null && cached.isValid()) {
                        bmp = cached.bitmap;
                    } else {
                        bmp = s.getFrame(t);
                        if (bmp != null && !bmp.isRecycled() && frameCache != null
                                && sid == frameSourceId) {
                            // Ownership transfers to the cache (never recycled by the UI).
                            frameCache.put(new FrameData(sid, bmp, s.ptsUs[t], t, 0));
                        }
                    }
                } finally {
                    Trace.endSection();
                }
                final int idx = t;
                lastDecodedIndex = t;
                boolean superseded = queued == null
                        && (t != targetIndex.get()
                            || frameRequestSeq.get() != myVersion
                            || s != source);
                if (superseded) {
                    // A newer seek superseded this decode (task spec: seek 100 → 700 → 1500
                    // must only render 1500). The frame is already cache-resident for
                    // scrub-back; without a cache it must be freed.
                    if (frameCache == null && bmp != null && !bmp.isRecycled()) bmp.recycle();
                } else {
                    // Quality Pack: display copy par selected look (background thread —
                    // cache ORIGINAL rakhta hai, UI thread par grading ka jank nahi).
                    Bitmap toShow = bmp;
                    if (frameGradePreset > 0 && bmp != null && !bmp.isRecycled()) {
                        Bitmap graded = ColorGrade.grade(bmp, frameGradePreset);
                        if (graded != bmp) toShow = graded;
                    }
                    final Bitmap show = toShow;
                    ui.post(() -> onFrameDecoded(idx, bmp, show, s));
                }
                if (queued != null) continue;
                if (targetIndex.get() == t) break;
            }
        } finally {
            decodeRunning.set(false);
        }
        if ((!exactFrameQueue.isEmpty() || targetIndex.get() != lastDecodedIndex)
                && decodeRunning.compareAndSet(false, true)) {
            getDecoder().execute(this::decodeLoop);
        }
    }

    private void onFrameDecoded(int index, Bitmap bmp, Bitmap gradedToShow, FrameSource decodedSource) {
        if (isDestroyed() || decodedSource != source || decodedSource != uiSource) {
            // Bitmap is cache-owned (or pooled); the LRU evicts it — never recycle here.
            if (gradedToShow != null && gradedToShow != bmp && !gradedToShow.isRecycled()) gradedToShow.recycle();
            return;
        }
        // V2.0 P1-01: render-time generation check — an older result must never
        // publish to the UI after a newer seek (stale-frame flash).
        if (mode == MODE_FRAME && exactFrameQueue.isEmpty() && index != targetIndex.get()) {
            if (gradedToShow != null && gradedToShow != bmp && !gradedToShow.isRecycled()) gradedToShow.recycle();
            return;   // frame stays cache-resident for scrub-back
        }
        if (bmp == null) {
            if (gradedToShow != null && !gradedToShow.isRecycled()) gradedToShow.recycle();
            Toast.makeText(this, "This frame could not be decoded", Toast.LENGTH_SHORT).show();
            return;
        }
        int prevDisplayed = displayedIndex;
        Bitmap old = currentBitmap;
        currentBitmap  = bmp;      // ORIGINAL — cache-owned; SAVE isse grade karta hai
        displayedIndex = index;
        if (decodedSource.ptsUs != null && index < decodedSource.ptsUs.length) {
            lastDisplayedPtsUs = decodedSource.ptsUs[index];   // time anchor for timeline remap
        }
        if (viewer != null && mode == MODE_FRAME) {
            Bitmap shown = (gradedToShow != null && !gradedToShow.isRecycled()) ? gradedToShow : bmp;
            Bitmap oldShown = shownBitmap;
            shownBitmap = (gradedToShow != null) ? gradedToShow : null;
            viewer.setFrame(shown, !firstFrameAfterOpen);
            firstFrameAfterOpen = false;
            // v2.4.4 guard: oldShown cache-owned bhi ho sakta hai (Original-look
            // alias ke case mein) — cache bitmap (`old`) ya naya display bitmap
            // (`currentBitmap`) kabhi yahan recycle na ho, warna LRU corrupt.
            if (oldShown != null && oldShown != shown && oldShown != old && oldShown != currentBitmap
                    && !oldShown.isRecycled()) oldShown.recycle();
            updateFrameInfo();
        }
        // V2.0: previous frame stays cached if still resident (instant scrub-back);
        // otherwise it returns to the bitmap pool. Never recycled while displayed.
        releaseFrameBitmap(old);
        if (frameCache != null && uiSource != null) {
            frameCache.markDisplayed(frameSourceId, index, 0);
            // V2.0: background prefetch window — direction-aware, budget-capped,
            // on the dedicated prefetch decoder; never blocks the UI or the seek path.
            boolean forward = prevDisplayed < 0 || index >= prevDisplayed;
            frameCache.prefetchAround(frameSourceId, currentUri, index, uiSource.frameCount, 0, forward);
        }
    }

    /**
     * V2.0 P0-01: remaps navigation onto the full presentation timeline when the
     * background indexer replaces the keyframe-only index (60 keyframes → 1,800 frames).
     */
    private void onFullTimelineReady(FrameSource s) {
        if (isDestroyed() || isFinishing() || s != uiSource || s != source) return;
        long anchorPts = lastDisplayedPtsUs != Long.MIN_VALUE ? lastDisplayedPtsUs : syncPositionUs;
        int newIdx = PlaybackMath.nearestIndex(s.ptsUs, Math.max(0L, anchorPts));
        // Cache entries are keyed by the old index space — start fresh.
        if (frameCache != null) frameCache.close();
        frameCache = new FrameCacheManager(getApplicationContext());
        requestedIndex   = newIdx;
        displayedIndex   = newIdx;     // on-screen bitmap matches this time position
        lastDecodedIndex = newIdx;
        targetIndex.set(newIdx);
        firstFrameAfterOpen = false;
        if (frameSeek != null) {
            frameSeek.setMax(Math.max(0, s.frameCount - 1));
            frameSeek.setProgress(newIdx);
        }
        updateFrameInfo();
        requestFrame(newIdx);          // re-fetch through the full-timeline pipeline
    }

    private void updateFrameInfo() {
        if (frameInfo == null) return;
        if (uiSource == null) { frameInfo.setText("Open a video to begin"); return; }
        int shown = displayedIndex >= 0 ? displayedIndex : requestedIndex;
        shown = Math.max(0, Math.min(uiSource.frameCount - 1, shown));
        frameInfo.setText(String.format(Locale.US, "F %d / %d  ·  %s fps\n%s / %s",
                shown + 1, uiSource.frameCount,
                fmtFps(uiSource.fps),
                fmtTime(uiSource.ptsUs[shown]),
                fmtDuration(uiSource.durationUs)));
    }

    // ─────────────────────────────────── frame auto-play

    private void toggleAutoPlay() {
        if (autoPlaying) { stopAutoPlay(); return; }
        if (uiSource == null || uiSource.frameCount == 0) {
            Toast.makeText(this, "Open a video first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (requestedIndex >= uiSource.frameCount - 1) goTo(0);
        autoPlaying = true;
        updateFrameButtons();
        ui.removeCallbacks(autoPlayRunnable);
        ui.post(autoPlayRunnable);
    }


    private void stopAutoPlay() {
        ui.removeCallbacks(autoPlayRunnable);
        if (autoPlaying) autoPlaying = false;
        updateFrameButtons();
    }

    private void autoPlayTick() {
        if (!autoPlaying || uiSource == null || loading || isFinishing() || isDestroyed()) return;
        if (displayedIndex != requestedIndex || decodeRunning.get()) {
            ui.postDelayed(autoPlayRunnable, 8L);
            return;
        }
        int current = displayedIndex >= 0 ? displayedIndex : requestedIndex;
        if (current >= uiSource.frameCount - 1) { stopAutoPlay(); return; }
        long delayMs = playbackDelayMs(current);
        goTo(current + 1);
        ui.postDelayed(autoPlayRunnable, delayMs);
    }

    private long playbackDelayMs(int currentIndex) {
        double baseMs;
        if (playbackFps > 0) {
            baseMs = 1000.0 / playbackFps;
        } else if (uiSource != null && currentIndex + 1 < uiSource.frameCount) {
            long deltaUs = uiSource.ptsUs[currentIndex + 1] - uiSource.ptsUs[currentIndex];
            if (deltaUs > 0) baseMs = deltaUs / 1000.0;
            else if (uiSource.fps > 0) baseMs = 1000.0 / uiSource.fps;
            else baseMs = 33.0;
        } else {
            baseMs = 33.0;
        }
        return Math.max(1L, Math.round(baseMs));
    }

    // ─────────────────────────────────── picture rotation

    private void rotatePicture(int delta) {
        if (uiSource == null) {
            Toast.makeText(this, "Open a video first", Toast.LENGTH_SHORT).show();
            return;
        }
        pictureRotation = (pictureRotation + delta) % 360;
        if (pictureRotation < 0) pictureRotation += 360;
        applyPictureRotation();
        Toast.makeText(this, "Picture rotation: " + pictureRotation + "°", Toast.LENGTH_SHORT).show();
    }

    private void resetPictureRotation() {
        pictureRotation = 0;
        applyPictureRotation();
        Toast.makeText(this, "Picture rotation reset", Toast.LENGTH_SHORT).show();
    }

    private void applyPictureRotation() {
        if (viewer != null && currentBitmap != null && !currentBitmap.isRecycled()) {
            // Preview rotation must match saved image orientation:
            // videoRotation (metadata) + pictureRotation (manual) + displayRotation (view preference)
            int previewRotation = (videoRotation + pictureRotation + displayRotation) % 360;
            viewer.setDisplayRotation(previewRotation);
        }
        updateFrameInfo();
    }

    /**
     * Get the effective picture rotation combining video rotation metadata and manual rotation.
     * ExactFrameDecoder outputs raw decoded pixels (no metadata rotation applied), so the
     * saved image needs videoRotation + pictureRotation; preview additionally adds displayRotation.
     */
    private int getEffectivePictureRotation() {
        return (videoRotation + pictureRotation) % 360;
    }

    /** Apply rotation to a bitmap, returning a new bitmap (or the source if rotation is 0). */
    private Bitmap applyRotationToBitmap(Bitmap source, int rotationDegrees) {
        if (source == null || source.isRecycled() || rotationDegrees == 0) return source;
        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        Bitmap rotated = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        return rotated != null ? rotated : source;
    }

    // ─────────────────────────────────── save (original frame only)

    private void saveCurrentFrame() {
        final Bitmap bmp = currentBitmap;
        final int idx = displayedIndex;
        if (bmp == null || idx < 0 || bmp.isRecycled()) {
            Toast.makeText(this, "Open a video first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (savingBitmap != null) { Toast.makeText(this, "Save in progress…", Toast.LENGTH_SHORT).show(); return; }
        savingBitmap = bmp;
        getSaver().execute(() -> {
            String message;
            try {
                // Apply picture rotation to the saved bitmap so saved orientation
                // matches the preview (videoRotation + pictureRotation).
                Bitmap rotatedBmp = applyRotationToBitmap(bmp, getEffectivePictureRotation());
                writePng(rotatedBmp, idx);
                if (rotatedBmp != bmp && !rotatedBmp.isRecycled()) rotatedBmp.recycle();
                message = "Saved frame " + (idx + 1) + " → Pictures/TRUE NAIN";
            } catch (IOException | RuntimeException e) {
                message = "Save failed: " + e.getMessage();
            }
            final String m = message;
            ui.post(() -> {
                savingBitmap = null;
                releaseFrameBitmap(bmp);    // V2.0: cached bitmaps stay cached, others pool
                if (!isDestroyed()) Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void writePng(Bitmap bmp, int index) throws IOException {
        String name = String.format(Locale.US, "TRUE_NAIN_Frame_%06d.png", index + 1);
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME,   name);
        v.put(MediaStore.Images.Media.MIME_TYPE,      "image/png");
        v.put(MediaStore.Images.Media.RELATIVE_PATH,  Environment.DIRECTORY_PICTURES + "/TRUE NAIN");
        v.put(MediaStore.Images.Media.IS_PENDING,     1);
        ContentResolver cr = getContentResolver();
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("Could not create image file");
        try (OutputStream out = cr.openOutputStream(uri)) {
            if (out == null) throw new IOException("Could not open image file");
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out))
                throw new IOException("Could not write frame");
        } catch (IOException | RuntimeException e) {
            cr.delete(uri, null, null);
            throw e;
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Images.Media.IS_PENDING, 0);
        cr.update(uri, done, null, null);
    }

    // ══════════════════════════════════════════════════════════ formatting

    private static String fmtFps(double fps) {
        if (fps <= 0) return "-";
        if (Math.abs(fps - Math.rint(fps)) < 0.005)
            return String.format(Locale.US, "%d", (long) Math.rint(fps));
        return String.format(Locale.US, "%.2f", fps);
    }

    private static String fmtTime(long us) {
        long ms = Math.max(0, us) / 1000L;
        return String.format(Locale.US, "%02d:%02d:%02d.%03d",
                ms / 3_600_000L, (ms / 60_000L) % 60, (ms / 1000L) % 60, ms % 1000L);
    }

    private static String fmtDuration(long us) {
        long s = Math.max(0, Math.round(us / 1_000_000.0));
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    private static String fmtClock(long ms) {
        long s = Math.max(0, ms) / 1000L;
        if (s >= 3600) return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    private static String formatSpeed(double v) {
        return String.format(Locale.US, "%.2fx", v);
    }

    private void showExtractor() {
        if (uiSource == null) { preferredLibraryMode = MODE_EXTRACTOR; showLibrary(); return; }
        mode = MODE_EXTRACTOR;
        modeContainer.removeAllViews();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(25), dp(22), dp(16));
        root.setBackgroundColor(COLOR_BG);
        root.addView(text("FRAME EXTRACTOR", 25, COLOR_TEXT, true));
        root.addView(text(currentName + "  •  " + uiSource.frameCount + " samples", 12, COLOR_MUTED, false));
        Button all = makeButton("EXPORT ALL FRAMES", 56, v -> exportFrameDialog(false));
        registerControl("extract_all", all);
        root.addView(all);
        Button custom = makeButton("CUSTOM FRAME RANGE / SELECTION", 56, v -> exportFrameDialog(true));
        registerControl("extract_custom", custom);
        root.addView(custom);
        // Photo Rotate (Samsung orientation fix): sets the manual rotation that the
        // shared orientation pipeline applies to every exported frame.
        Button rotate = makeButton("⟲ PHOTO ROTATE", 56, v -> showPhotoRotateDialog());
        registerControl("extract_rotate", rotate);
        root.addView(rotate);
        extractorRotateStatus = text("", 12, COLOR_MUTED, false);
        updateExtractorRotateStatus();
        root.addView(extractorRotateStatus);
        root.addView(text("Custom examples: 1,5,10,25-100 (1-based)", 12, COLOR_MUTED, false));
        Button back = makeButton("‹ VIDEO LIBRARY", 50, v -> showLibrary());
        registerControl("extract_library", back);
        root.addView(back);
        modeContainer.addView(root, new FrameLayout.LayoutParams(-1, -1));
    }

    private TextView extractorRotateStatus;

    /**
     * Photo Rotate (Samsung orientation fix): manual rotation shared by preview,
     * single save, batch export and screenshots.  Also offers rotating any photo
     * already in the Gallery (pick → choose angle → saved as a rotated copy in
     * Pictures/TRUE NAIN — the original is never modified).
     */
    private void showPhotoRotateDialog() {
        CharSequence[] options;
        if (uiSource != null) {
            options = new CharSequence[]{
                    "↶ Rotate Left 90°  (video frames + exports)",
                    "↷ Rotate Right 90°  (video frames + exports)",
                    "↻ Rotate 180°  (video frames + exports)",
                    "⟲ Reset Rotation",
                    "🖼 Rotate a Gallery photo…" };
        } else {
            options = new CharSequence[]{ "🖼 Rotate a Gallery photo…" };
        }
        new AlertDialog.Builder(this)
                .setTitle("Photo Rotate  ·  current export rotation: "
                        + (uiSource != null ? getEffectivePictureRotation() + "°" : "—"))
                .setItems(options, (d, which) -> {
                    // Shift the index so the LAST item is always the gallery picker,
                    // whether or not video-rotation options are shown.
                    int op = uiSource != null ? which : which + 4;
                    if (op == 4) { launchGalleryRotatePicker(); return; }
                    if (uiSource == null) return;
                    switch (op) {
                        case 0: rotatePicture(-90); break;
                        case 1: rotatePicture(90); break;
                        case 2: rotatePicture(180); break;
                        default: resetPictureRotation(); break;
                    }
                    updateExtractorRotateStatus();
                }).show();
    }

    /** Opens the system picker so the user can choose any Gallery image to rotate.
     *  V2.0.6: OPEN_DOCUMENT + write grant — the photo can then be rotated IN PLACE
     *  (no duplicate copies filling storage). */
    private void launchGalleryRotatePicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_GALLERY_ROTATE);
    }

    private void promptGalleryRotateAngle(final Uri src) {
        CharSequence[] angles = {"↷ Rotate Right 90°", "↶ Rotate Left 90°", "↻ Rotate 180°"};
        new AlertDialog.Builder(this)
                .setTitle("Rotate photo — where to save?")
                .setMessage("Rotated copy will be saved in Pictures/TRUE NAIN (original stays untouched).")
                .setItems(angles, (d, which) -> {
                    int angle = which == 0 ? 90 : which == 1 ? -90 : 180;
                    Toast.makeText(this, "Rotating photo…", Toast.LENGTH_SHORT).show();
                    getSaver().execute(() -> {
                        String msg;
                        try { msg = rotateGalleryPhoto(src, angle); }
                        catch (Exception e) { msg = "Rotate failed: " + e.getMessage(); }
                        final String m = msg;
                        ui.post(() -> { if (!isDestroyed()) Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show(); });
                    });
                }).show();
    }

    /** Decodes a gallery image, bakes the rotation into pixels, saves a new copy. */
    private String rotateGalleryPhoto(Uri src, int angle) throws IOException {
        String name = "photo";
        String mime = "image/jpeg";
        android.database.Cursor c = getContentResolver().query(
                src, new String[]{android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                        android.provider.MediaStore.Images.Media.MIME_TYPE},
                null, null, null);
        if (c != null) {
            try { if (c.moveToFirst()) {
                name = c.getString(0) != null ? c.getString(0) : name;
                mime = c.getString(1) != null ? c.getString(1) : mime;
            } } finally { c.close(); }
        }
        boolean png = mime != null && mime.contains("png");

        // Bounds-first decode with a 4096px safety cap so huge photos cannot OOM.
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (java.io.InputStream in = getContentResolver().openInputStream(src)) {
            android.graphics.BitmapFactory.decodeStream(in, null, o);
        }
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new IOException("Not a readable image");
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 4096) sample *= 2;
        android.graphics.BitmapFactory.Options d = new android.graphics.BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap bmp;
        try (java.io.InputStream in = getContentResolver().openInputStream(src)) {
            bmp = android.graphics.BitmapFactory.decodeStream(in, null, d);
        }
        if (bmp == null) throw new IOException("Image could not be decoded");

        Bitmap out = bmp;
        try {
            Matrix m = new Matrix();
            m.postRotate(angle);
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (rotated != null && rotated != bmp) { out = rotated; bmp.recycle(); }
        } catch (OutOfMemoryError e) {
            // fall through: save unrotated rather than fail — but signal it
            throw new IOException("Photo too large to rotate on this device");
        }
        try {
            // V2.0.6: IN-PLACE first — overwrite the picked photo itself so storage
            // is not multiplied by a new copy on every rotation.  Falls back to a
            // copy only when the provider refuses write access.
            boolean wroteInPlace = false;
            try (OutputStream rw = getContentResolver().openOutputStream(src, "rw")) {
                if (rw != null) {
                    wroteInPlace = out.compress(png ? Bitmap.CompressFormat.PNG
                            : Bitmap.CompressFormat.JPEG, png ? 100 : 95, rw);
                }
            } catch (RuntimeException rwError) {
                wroteInPlace = false;
            }
            if (wroteInPlace) return "Photo rotated in place ✓ (same photo updated)";
            // Fallback: provider is read-only — save a rotated copy instead.
            String base = name.replaceAll("\\.[^.]+$", "");
            base = base.length() > 40 ? base.substring(0, 40) : base;
            String ext = png ? ".png" : ".jpg";
            String outName = String.format(Locale.US, "ROTATED_%s_%d_%d%s",
                    base, ((angle % 360) + 360) % 360, System.currentTimeMillis(), ext);
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, outName);
            v.put(MediaStore.Images.Media.MIME_TYPE, png ? "image/png" : "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TRUE NAIN");
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            ContentResolver cr = getContentResolver();
            Uri dst = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (dst == null) throw new IOException("Could not create image file");
            try (OutputStream os = cr.openOutputStream(dst)) {
                if (os == null) throw new IOException("Could not open output");
                if (!out.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG,
                        png ? 100 : 95, os))
                    throw new IOException("Could not write rotated photo");
            } catch (IOException | RuntimeException e) {
                cr.delete(dst, null, null);
                throw e;
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(dst, done, null, null);
            return "Rotated copy saved → Pictures/TRUE NAIN (provider read-only)";
        } finally {
            if (!out.isRecycled()) out.recycle();
        }
    }

    private void updateExtractorRotateStatus() {
        if (extractorRotateStatus != null)
            extractorRotateStatus.setText("Export rotation (video + photo): " + getEffectivePictureRotation() + "°");
    }

    private void extractAllFrames() { exportFrameDialog(false); }

    private void exportFrameDialog(boolean custom) {
        if (currentUri == null || uiSource == null || exportRunning) return;
        int count = uiSource.frameCount;
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("1,5,10,25-100");
        final String[] formats = {"JPEG (faster/smaller)", "PNG (lossless/larger)"};
        final Spinner formatSpinner = new Spinner(this);
        ArrayAdapter<String> formatAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, formats);
        formatSpinner.setAdapter(formatAdapter);
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(18), dp(10), dp(18), 0);
        fields.addView(text("Source samples: " + count, 14, COLOR_TEXT, false));
        if (custom) fields.addView(input);
        fields.addView(formatSpinner);
        new AlertDialog.Builder(this)
                .setTitle(custom ? "Custom Frame Export" : "Export All Frames")
                .setView(fields)
                .setPositiveButton("START", (d, w) -> {
                    try {
                        int[] indexes = custom ? parseFrameSelection(input.getText().toString(), count) : null;
                        beginFrameExport(indexes, formatSpinner.getSelectedItemPosition() == 1);
                    } catch (IllegalArgumentException e) {
                        new AlertDialog.Builder(this).setTitle("Invalid frames")
                                .setMessage(e.getMessage()).setPositiveButton("OK", null).show();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private static int[] parseFrameSelection(String spec, int count) {
        java.util.TreeSet<Integer> chosen = new java.util.TreeSet<>();
        if (spec.trim().isEmpty()) throw new IllegalArgumentException("Enter frame numbers or ranges.");
        for (String part : spec.split(",")) {
            String[] span = part.trim().split("-");
            if (span.length < 1 || span.length > 2) throw new IllegalArgumentException("Invalid range: " + part);
            int a, b;
            try { a = Integer.parseInt(span[0].trim()); b = span.length == 2 ? Integer.parseInt(span[1].trim()) : a; }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid frame: " + part); }
            if (a < 1 || b < a || b > count) throw new IllegalArgumentException("Frame range must be 1 to " + count);
            if (b - a > 500000) throw new IllegalArgumentException("Range too large");
            for (int j = a; j <= b; j++) chosen.add(j - 1);
        }
        if (chosen.isEmpty()) throw new IllegalArgumentException("No frames selected");
        int[] frames = new int[chosen.size()]; int n = 0;
        for (int index : chosen) frames[n++] = index;
        return frames;
    }

    private void beginFrameExport(int[] selected, boolean png) {
        exportRunning = true;
        exportCancelled = false;
        final int total = selected == null ? uiSource.frameCount : selected.length;
        final ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(total);
        final TextView status = text("0 / " + total, 14, COLOR_TEXT, true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(20), dp(20), dp(20));
        box.addView(status); box.addView(progress);
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Exporting Frames")
                .setView(box).setNegativeButton("Cancel", (d, w) -> exportCancelled = true).create();
        dlg.setCancelable(false); dlg.show();
        String safe = currentName == null ? "VIDEO" : currentName.replaceAll("[^a-zA-Z0-9_-]", "_");
        safe = safe.length() > 45 ? safe.substring(0, 45) : safe;
        final String folder = Environment.DIRECTORY_PICTURES + "/TRUE NAIN/" + safe + "_frames_" + System.currentTimeMillis();
        final Uri exportUri = currentUri;
        // V2.0 P1-03: one shared orientation policy — batch export must match the
        // preview exactly (videoRotation + pictureRotation), captured on the UI thread.
        final int exportRotation = getEffectivePictureRotation();
        getSaver().execute(() -> {
            int saved = 0, failed = 0;
            String error = null;
            FrameSource exportSource = null;
            try {
                // V2.0.4 perf: reuse the ALREADY-INDEXED full timeline when it matches
                // this video — skips the multi-second re-scan that made exports feel
                // stuck.  Fall back to a synchronous full scan otherwise.
                if (uiSource != null && uiSource.hasFullTimeline()
                        && exportUri != null && exportUri.equals(currentUri)) {
                    exportSource = FrameSource.openSharedTimeline(
                            getApplicationContext(), exportUri, uiSource.ptsUs);
                } else {
                    exportSource = FrameSource.openFull(getApplicationContext(), exportUri);
                }
                for (int n = 0; n < total && !exportCancelled; n++) {
                    int frameIndex = selected == null ? n : selected[n];
                    if (frameIndex >= exportSource.frameCount) { error = "Source frame count changed"; break; }
                    String suffix = png ? ".png" : ".jpg";
                    String filename = String.format(Locale.US, "frame_%08d_%012d%s", frameIndex + 1,
                            exportSource.ptsUs[frameIndex], suffix);
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
                    values.put(MediaStore.Images.Media.MIME_TYPE, png ? "image/png" : "image/jpeg");
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, folder);
                    values.put(MediaStore.Images.Media.IS_PENDING, 1);
                    Uri output = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    boolean success = false;
                    try {
                        if (output != null) try (OutputStream stream = getContentResolver().openOutputStream(output)) {
                            if (stream == null) throw new IOException("Could not open output");
                            success = exportSource.writeFrame(frameIndex, stream, png, exportRotation);
                            if (!success && !exportCancelled) {
                                exportSource.close();
                                exportSource = FrameSource.openFull(getApplicationContext(), exportUri);
                                if (frameIndex < exportSource.frameCount) {
                                    success = exportSource.writeFrame(frameIndex, stream, png, exportRotation);
                                }
                            }                        }
                    } catch (Exception saveError) {
                        error = saveError.getMessage();
                    } finally {
                        if (output != null) {
                            if (success) {
                                ContentValues done = new ContentValues();
                                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                                getContentResolver().update(output, done, null, null);
                            } else getContentResolver().delete(output, null, null);
                        }
                    }
                    if (!success) {
                        failed++;
                        if (error == null) error = "Decode failure at " + (frameIndex + 1);
                        break;
                    }
                    saved++;
                    final int completed = saved;
                    if (n % 5 == 0 || n + 1 == total) ui.post(() -> {
                        progress.setProgress(completed); status.setText(completed + " / " + total);
                    });
                }
            } catch (Exception e) { error = e.getMessage(); }
            finally { if (exportSource != null) exportSource.close(); }
            final int resultSaved = saved, resultFailed = failed;
            final String resultError = error;
            ui.post(() -> {
                exportRunning = false; dlg.dismiss();
                new AlertDialog.Builder(this).setTitle("Frame Export")
                        .setMessage("Saved: " + resultSaved + " / " + total + "\nDecode failures: " + resultFailed
                                + "\nFolder: " + folder + (exportCancelled ? "\nCancelled" : "")
                                + (resultError == null ? "" : "\nError: " + resultError))
                        .setPositiveButton("OK", null).show();
            });
        });
    }

    // ══════════════════════════════════════════════ hardware keys / back

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // v2.4.5: Fine Rotate mode — ← → keys se 1° rotate (DeX/mouse/keyboard)
        if (fineRotateMode
                && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            applyRotationAngle(currentRotationAngle()
                    + (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1f : -1f));
            updateRotateOverlay();
            return true;
        }
        if (mode == MODE_FRAME && landscape && uiSource != null) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP)   { manualStep(1);  return true; }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { manualStep(-1); return true; }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (mode == MODE_FRAME && landscape
                && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN))
            return true;
        return super.onKeyUp(keyCode, event);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (fullscreen)           toggleFullscreen();
        else if (mode == MODE_PLAYER || mode == MODE_FRAME || mode == MODE_EXTRACTOR) {
            if (mode != MODE_EXTRACTOR) saveResumePosition(); preferredLibraryMode = mode;
            showLibrary();
        }
        else if (mode == MODE_LIBRARY) showHome();
        else                      super.onBackPressed();
    }
}
