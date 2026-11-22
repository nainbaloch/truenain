package com.truenain.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

/**
 * TRUE NAIN ZoomController: one zoom/pan engine for Media Player AND Frame mode.
 *
 * HOW TO USE (3 steps):
 *  1. ZoomController zc = new ZoomController(context, targetView, listener);
 *     targetView.setOnTouchListener(zc); targetView.setOnGenericMotionListener(zc);
 *  2. After the video/frame is known (and after every rotation change):
 *     zc.setContent(displayedSourceWidth, displayedSourceHeight);   // real source pixels, AFTER rotation
 *  3. In Listener.onTransform apply the matrix (ImageView with ScaleType.MATRIX: iv.setImageMatrix(m);
 *     for GL / TextureView use the same matrix in your renderer).
 *     In Listener.onSettled render the SHARP full-resolution crop of 'visible' (source pixel rect),
 *     then show it. Ignore the result if zc.isCurrent(generation) is false (stale-frame protection).
 *
 * Fast path while fingers move, sharp path 300 ms after you stop.
 * If you already have your own single-finger gestures (seek swipe etc.), pass them with
 * zc.setFallback(oldTouchListener): they keep working when NOT zoomed in; when zoomed, one finger pans.
 *
 * STATUS: written without the Android SDK. CODE ONLY, BUILD NOT VERIFIED, DEVICE NOT VERIFIED.
 */
public final class ZoomController implements View.OnTouchListener, View.OnGenericMotionListener {

    public interface Listener {
        /** Every change. m maps source pixels (0..contentW, 0..contentH) to view pixels. */
        void onTransform(Matrix m, float zoom, boolean interacting);
        /** ~300 ms after the last change. 'visible' = visible area in source pixels. Render the sharp crop here. */
        void onSettled(RectF visible, float zoom, int generation);
        default void onSingleTap() {}
    }

    private static final float DOUBLE_TAP_ZOOM = 2.5f;
    private static final float OVER = 0.15f;      // rubber-band allowance while pinching
    private static final long SETTLE_MS = 300;

    private final View host;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private final OverScroller scroller;
    private final Matrix matrix = new Matrix();
    private final RectF visible = new RectF();

    private View.OnTouchListener fallback;
    private ValueAnimator anim;

    private float vw, vh;            // view size
    private float cw, ch;            // content size in source pixels (after rotation)
    private float base = 1f;         // fit-to-view scale
    private float baseFactor = 1f;   // v2.4.8: host display-base multiplier (Frame FILL/1:1)
    private float zoom = 1f;         // 1 = fit
    private float maxZoom = 8f;
    private Float overrideMaxZoom = null;   // v2.4.8: host-set cap (Player maxPlayerZoom) — relayout par bhi barkarar
    private float panX, panY;        // offset of content centre from view centre, in view px
    private float cxN = 0.5f, cyN = 0.5f; // content point at view centre (0..1), survives rotation/resize
    private float lastFx, lastFy;
    private boolean pinching, dragging;
    private int generation;

    public ZoomController(Context ctx, View host, Listener listener) {
        this.host = host;
        this.listener = listener;
        scroller = new OverScroller(ctx);

        scaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) {
                stopMotion();
                pinching = true;
                generation++;
                lastFx = d.getFocusX() - vw / 2f;
                lastFy = d.getFocusY() - vh / 2f;
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector d) {
                float fx = d.getFocusX() - vw / 2f, fy = d.getFocusY() - vh / 2f;
                panX += fx - lastFx;           // two-finger pan
                panY += fy - lastFy;
                lastFx = fx; lastFy = fy;
                // v2.5.1: rubber-band OVERSHOOT HATAA — pinch hamesha 1x..maxZoom ke andar
                // (pehle 15% bahar jata tha aur ungli uthate hi wapas animate hota tha —
                // user ko "zoom apne aap in/out" lagta tha).
                float nz = clamp(zoom * d.getScaleFactor(), 1f, maxZoom);
                zoomAbout(zoom, panX, panY, nz, fx, fy);
                dispatch(true);
                return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector d) {
                pinching = false;
                settleBounds();
            }
        });
        scaleDetector.setQuickScaleEnabled(false);

        gestureDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { listener.onSingleTap(); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (!doubleTapZoomEnabled) return false;   // v2.4.6: approved double-tap features keep working
                float fx = e.getX() - vw / 2f, fy = e.getY() - vh / 2f;
                animateZoom(nextDoubleTapLevel(), fx, fy, 240);
                return true;
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (pinching || zoom <= 1.001f) return false;
                dragging = true;
                generation++;
                panX -= dx; panY -= dy;
                clampPan(zoom);
                dispatch(true);
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (pinching || zoom <= 1.001f) return false;
                int lx = (int) limX(zoom), ly = (int) limY(zoom);
                scroller.fling((int) panX, (int) panY, (int) vx, (int) vy, -lx, lx, -ly, ly);
                host.postOnAnimation(flingTask);
                return true;
            }
        });

        host.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> setViewSize(r - l, b - t));
        if (host.getWidth() > 0) setViewSize(host.getWidth(), host.getHeight());
    }

    // ---------------------------------------------------------------- public API

    public void setFallback(View.OnTouchListener f) { fallback = f; }

    /** v2.4.6 integration flag (pack rule 10, safest option): double-tap is already
     *  used by approved features (Player = ±5s seek, Frame = ±1 frame step).  Set
     *  false so ZoomController's zoom-cycle double-tap stays off and the old
     *  double-tap handler keeps working via the fallback listener. */
    private boolean doubleTapZoomEnabled = true;
    public void setDoubleTapZoomEnabled(boolean on) { doubleTapZoomEnabled = on; }

    /** Displayed content size in SOURCE pixels, after rotation. Call again when rotation or video changes.
     *  v2.4.7 BUG 2: no-op when unchanged (prevents relayout/settle loops). */
    public void setContent(int srcW, int srcH) {
        if (srcW == cw && srcH == ch && cw > 0) return;
        cw = srcW; ch = srcH; relayout();
    }

    /** v2.4.8 FIX (auto-zoom jump ka asli jarr): host ka ASLI display base factor.
     *  ZC hamesha FIT-min base maanta tha, lekin Frame drawing FILL (max) / 1:1 (1)
     *  base use karti hai — FILL/1:1 mein ZC ka visible-rect GHALAT banta tha →
     *  settled sharp-crop ghalat region ka → har settle par zoom jump
     *  (user: "zoom apne aap in/out"). Factor lagane par dono ka base barabar. */
    public void setBaseFactor(float f) {
        if (f <= 0f || Float.isNaN(f) || Float.isInfinite(f)) f = 1f;
        if (Math.abs(f - baseFactor) < 0.0001f) return;
        baseFactor = f;
        relayout();
    }

    /** v2.4.8: host-set zoom cap (Player = maxPlayerZoom) — relayout par bhi barkarar,
     *  warna har layout change generic rule se cap overwrite ho jata tha. */
    public void setMaxZoom(float m) {
        if (Float.isNaN(m) || Float.isInfinite(m)) return;
        float nm = clamp(m, 1f, 12f);
        if (overrideMaxZoom != null && Math.abs(nm - overrideMaxZoom) < 0.0001f) return;
        overrideMaxZoom = nm;
        if (Math.abs(nm - maxZoom) < 0.0001f) return;
        maxZoom = nm;
        zoom = clamp(zoom, 1f, maxZoom);
        clampPan(zoom);
        dispatchQuiet();
    }

    public void zoomIn()  { zoomBy(1.5f); }
    public void zoomOut() { zoomBy(1f / 1.5f); }
    public void zoomBy(float factor) { animateZoom(zoom * factor, 0, 0, 180); }
    public void reset(boolean animate) {
        if (animate) animateZoom(1f, 0, 0, 220);
        else { stopMotion(); zoom = 1f; panX = panY = 0; cxN = cyN = 0.5f; dispatch(false); }
    }

    /** L1: view detach hone par pending settle/animation band (leak + late callback se bachao). */
    public void destroy() {
        handler.removeCallbacks(settleTask);
        stopMotion();
        generation++;
    }

    public float getZoom() { return zoom; }
    public float getMaxZoom() { return maxZoom; }
    /** Zoom level where 1 source pixel = 1 screen pixel. */
    public float pixelPerfectZoom() { return base > 0 ? 1f / base : 1f; }
    public boolean isZoomed() { return zoom > 1.001f; }
    public boolean isCurrent(int gen) { return gen == generation; }
    /** Current generation (v2.4.6: stale-guard for async settled crops). */
    public int getGeneration() { return generation; }
    public Matrix getMatrix() { return matrix; }
    /** Visible content rect in content pixels (v2.4.6: settled-crop decoding needs it). */
    public RectF getVisible() { return new RectF(visible); }

    public Bundle saveState() {
        Bundle b = new Bundle();
        b.putFloat("z", zoom); b.putFloat("cx", cxN); b.putFloat("cy", cyN);
        return b;
    }
    public void restoreState(Bundle b) {
        if (b == null) return;
        zoom = b.getFloat("z", 1f); cxN = b.getFloat("cx", 0.5f); cyN = b.getFloat("cy", 0.5f);
        relayout();
    }

    // ---------------------------------------------------------------- touch

    @Override public boolean onTouch(View v, MotionEvent e) {
        if (cw <= 0 || vw <= 0) return fallback != null && fallback.onTouch(v, e);
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) { stopMotion(); generation++; dragging = false; }

        scaleDetector.onTouchEvent(e);
        gestureDetector.onTouchEvent(e);

        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            dragging = false;
            if (!pinching) { clampPan(zoom); dispatch(false); }
        }
        if (zoom > 1.001f || pinching || e.getPointerCount() > 1) {
            if (host.getParent() != null) host.getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        return fallback != null ? fallback.onTouch(v, e) : true;
    }

    @Override public boolean onGenericMotion(View v, MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_SCROLL && (e.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0) {
            float s = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (s == 0) return false;
            animateZoom(zoom * (s > 0 ? 1.2f : 1f / 1.2f), e.getX() - vw / 2f, e.getY() - vh / 2f, 120);
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- internals

    private void setViewSize(int w, int h) {
        if (w == vw && h == vh) return;                     // v2.4.7 BUG 2: no change → no relayout loop
        vw = w; vh = h; relayout();
    }

    private void relayout() {
        if (vw <= 0 || vh <= 0 || cw <= 0 || ch <= 0) return;
        base = Math.min(vw / cw, vh / ch) * baseFactor;     // v2.4.8: host display base
        maxZoom = overrideMaxZoom != null
                ? clamp(overrideMaxZoom, 1f, 12f)           // v2.4.8: host cap barkarar
                : clamp((cw / vw) * 2.5f, 4f, 12f);         // same rule as v2.4.5
        maxZoom = Math.max(maxZoom, Math.min(12f, pixelPerfectZoom()));  // always allow 1:1
        zoom = clamp(zoom, 1f, maxZoom);                    // v2.4.7: only clamped if over max — zoom stays stable
        float s = base * zoom;
        panX = -(cxN - 0.5f) * cw * s;
        panY = -(cyN - 0.5f) * ch * s;
        clampPan(zoom);
        dispatchQuiet();                                    // v2.4.7 BUG 2: relayout NEVER schedules a settle
    }

    private float limX(float z) { return Math.max(0f, (cw * base * z - vw) / 2f); }
    private float limY(float z) { return Math.max(0f, (ch * base * z - vh) / 2f); }

    private void clampPan(float z) {
        panX = clamp(panX, -limX(z), limX(z));
        panY = clamp(panY, -limY(z), limY(z));
    }

    /** Keeps the content point under (fx,fy) fixed while zooming from z0 to z1. fx,fy relative to view centre. */
    private void zoomAbout(float z0, float px0, float py0, float z1, float fx, float fy) {
        float r = z1 / z0;
        zoom = z1;
        panX = fx - (fx - px0) * r;
        panY = fy - (fy - py0) * r;
        clampPan(z1);
    }

    private float nextDoubleTapLevel() {
        float pp = pixelPerfectZoom();
        float[] levels = pp > 3f && pp <= maxZoom
                ? new float[]{DOUBLE_TAP_ZOOM, pp} : new float[]{DOUBLE_TAP_ZOOM};
        for (float lv : levels) if (zoom < lv * 0.95f) return Math.min(lv, maxZoom);
        return 1f;
    }

    private void animateZoom(float target, float fx, float fy, long ms) {
        stopMotion();
        final float t = clamp(target, 1f, maxZoom);
        final float z0 = zoom, px0 = panX, py0 = panY;
        if (Math.abs(t - z0) < 0.001f) { clampPan(zoom); dispatch(false); return; }
        generation++;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(ms);
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            float z = z0 * (float) Math.pow(t / z0, f);       // smooth, geometric zoom
            zoomAbout(z0, px0, py0, z, fx, fy);
            dispatch(true);
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) { dispatch(false); }
        });
        anim.start();
    }

    private void settleBounds() {
        float tz = clamp(zoom, 1f, maxZoom);
        if (Math.abs(tz - zoom) > 0.001f) animateZoom(tz, lastFx, lastFy, 200);
        else { clampPan(zoom); dispatch(false); }
    }

    private void stopMotion() {
        if (anim != null) { anim.removeAllListeners(); anim.cancel(); anim = null; }
        if (!scroller.isFinished()) scroller.abortAnimation();
        host.removeCallbacks(flingTask);
    }

    private final Runnable flingTask = new Runnable() {
        @Override public void run() {
            if (scroller.computeScrollOffset()) {
                panX = scroller.getCurrX(); panY = scroller.getCurrY();
                clampPan(zoom);
                dispatch(true);
                host.postOnAnimation(this);
            } else dispatch(false);
        }
    };

    private final Runnable settleTask = new Runnable() {
        @Override public void run() {
            boolean busy = pinching || dragging || (anim != null && anim.isRunning()) || !scroller.isFinished();
            if (busy) { handler.postDelayed(this, SETTLE_MS); return; }
            listener.onSettled(visible, zoom, generation);
        }
    };

    private void dispatch(boolean interacting) {
        dispatch(interacting, true);
    }

    /** v2.4.7 BUG 2: scheduleSettle=false for relayout — layout/bitmap changes must NOT
     *  re-trigger the 300ms settle (sharp render) → no relayout/settle loop. */
    private void dispatch(boolean interacting, boolean scheduleSettle) {
        float s = base * zoom;
        if (s <= 0) return;
        matrix.reset();
        matrix.postTranslate(-cw / 2f, -ch / 2f);
        matrix.postScale(s, s);
        matrix.postTranslate(vw / 2f + panX, vh / 2f + panY);

        visible.set(
            clamp(cw / 2f + (-vw / 2f - panX) / s, 0, cw),
            clamp(ch / 2f + (-vh / 2f - panY) / s, 0, ch),
            clamp(cw / 2f + ( vw / 2f - panX) / s, 0, cw),
            clamp(ch / 2f + ( vh / 2f - panY) / s, 0, ch));
        cxN = 0.5f - panX / (s * cw);
        cyN = 0.5f - panY / (s * ch);

        listener.onTransform(matrix, zoom, interacting);
        if (scheduleSettle) {
            handler.removeCallbacks(settleTask);
            handler.postDelayed(settleTask, SETTLE_MS);
        }
    }

    /** Matrix/visible update WITHOUT scheduling a settle (layout changes only). */
    private void dispatchQuiet() { dispatch(false, false); }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
