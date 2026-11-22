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
 * TRUE NAIN ZoomController v2.6.0 — ONE zoom / pan / rotate engine for Frame mode AND Player.
 *
 * ENGINE KA MODEL (seedha, ek hi sach):
 *   Sirf 3 cheezein state hain:  zoom,  rotation (degrees),  c = (cx, cy).
 *   c = "content ka wo point jo SCREEN KE CENTRE par hai", content ke apne axes mein,
 *       content centre se pixel offset (content px).
 *   Pan / matrix / visible-rect sab isi se NIKALTE hain — kabhi alag store nahi hote,
 *   is liye rotate / resize / zoom / pan aapas mein kabhi desync nahi ho sakte.
 *
 *   screen scale   s   = baseScale * zoom
 *   pan (view px)      = - R(rotation) * c * s            (content centre ka screen-centre se offset)
 *   matrix (content -> view) = T(-cw/2,-ch/2) -> R(rotation) -> S(s) -> T(vw/2+panX, vh/2+panY)
 *
 *   Rotation SCREEN CENTRE ke gird hoti hai: jo point screen ke beech mein hai wahi beech
 *   mein rehta hai (purana bug: rotate par pan jump karta tha).
 *   Clamp EXACT hai: poora view-rect rotated content-rect ke andar rehta hai (tilt par bhi).
 *
 * HOST KE LIYE 4 STEP:
 *   1. zc = new ZoomController(ctx, view, listener); view.setOnTouchListener(zc);
 *   2. zc.setContent(w, h)        — content ka size (Frame: bitmap w,h UNROTATED; Player: fitted video rect, view px)
 *   3. zc.setRotation(deg)        — total rotation (90-steps + fine)
 *      zc.setBaseScale(s)         — zoom==1 par screen scale (FIT / FILL / 1:1 host decide karta hai)
 *   4. onTransform mein zc.getMatrix() / getPanX() / getPanY() / getScale() lagao.
 *      onSettled mein zc.getVisible() (content px) se sharp crop banao; zc.isCurrent(gen) check karo.
 *
 * NOTE: setContent / setRotation / setBaseScale / setMaxZoom KHAMOSH (silent) hain — listener ko
 * call nahi karte (host khud apply ke beech mein hota hai → recursion nahi). Host in ke baad
 * getters se state parh kar khud draw/invalidate kare.
 *
 * STATUS: Android SDK ke baghair likha. CODE ONLY — BUILD NOT VERIFIED, DEVICE NOT VERIFIED.
 */
public final class ZoomController implements View.OnTouchListener, View.OnGenericMotionListener {

    public interface Listener {
        /** Har change. m = content px -> view px (rotation shamil). */
        void onTransform(Matrix m, float zoom, boolean interacting);
        /** ~300 ms baad. 'visible' = content px mein dikhne wala AABB. Sharp crop yahan banao. */
        void onSettled(RectF visible, float zoom, int generation);
        default void onSingleTap() {}
    }

    private static final float DOUBLE_TAP_ZOOM = 2.5f;
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

    private float vw, vh;               // view size
    private float cw, ch;               // content size (host ke units; rotate hone se PEHLE ka)
    private float rotDeg = 0f;          // total rotation, 0..360
    private float cos = 1f, sin = 0f;   // rotation ka cos/sin (exact multiples of 90 snap)
    private float baseScale = 0f;       // host-set absolute scale at zoom==1 (0 = auto fit)
    private float zoom = 1f;            // 1..maxZoom
    private float maxZoom = 8f;
    private Float overrideMaxZoom = null;
    private float cx, cy;               // content point at screen centre (content px from content centre)
    private float lastFx, lastFy;
    private float flingScale = 1f;
    private boolean pinching, dragging;
    private int generation;
    private boolean doubleTapZoomEnabled = true;

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
                panByScreen(fx - lastFx, fy - lastFy);          // do-ungli pan
                lastFx = fx; lastFy = fy;
                // pinch hamesha 1x..maxZoom ke andar (rubber-band overshoot nahi)
                zoomAbout(clamp(zoom * d.getScaleFactor(), 1f, maxZoom), fx, fy);
                dispatch(true);
                return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector d) {
                pinching = false;
                clampC();
                dispatch(false);
            }
        });
        scaleDetector.setQuickScaleEnabled(false);

        gestureDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { listener.onSingleTap(); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (!doubleTapZoomEnabled) return false;   // approved double-tap features (Player ±5s, Frame ±1 frame)
                animateZoom(nextDoubleTapLevel(), e.getX() - vw / 2f, e.getY() - vh / 2f, 240);
                return true;
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (pinching || zoom <= 1.001f) return false;
                dragging = true;
                generation++;
                panByScreen(-dx, -dy);
                clampC();
                dispatch(true);
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (pinching || zoom <= 1.001f) return false;
                float s = scale();
                if (s <= 0f) return false;
                // Fling content-axes mein (u = c*s): screen velocity -> content axes, ulta (content finger se ulta chalta hai)
                float ux = cx * s, uy = cy * s;
                float vux = -(vx * cos + vy * sin);
                float vuy = -(-vx * sin + vy * cos);
                int lx = (int) limU(true, s), ly = (int) limU(false, s);
                flingScale = s;
                scroller.fling(clampInt(Math.round(ux), -lx, lx), clampInt(Math.round(uy), -ly, ly),
                        (int) vux, (int) vuy, -lx, lx, -ly, ly);
                host.postOnAnimation(flingTask);
                return true;
            }
        });

        host.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> setViewSize(r - l, b - t));
        if (host.getWidth() > 0) setViewSize(host.getWidth(), host.getHeight());
    }

    // ---------------------------------------------------------------- public API

    public void setFallback(View.OnTouchListener f) { fallback = f; }

    /** Double-tap pehle se approved features use karte hain (Player ±5s, Frame ±1 frame) → default OFF rakho. */
    public void setDoubleTapZoomEnabled(boolean on) { doubleTapZoomEnabled = on; }

    /** Content size (rotate hone se PEHLE ka). Same size par no-op. Size badle to c proportional rehta hai. */
    public void setContent(int srcW, int srcH) {
        if (srcW <= 0 || srcH <= 0) return;
        if (srcW == cw && srcH == ch) return;
        if (cw > 0 && ch > 0) { cx *= srcW / cw; cy *= srcH / ch; }
        cw = srcW; ch = srcH;
        generation++;                    // purana async crop stale
        relayout();
    }

    /** Total rotation (degrees). Screen centre ke gird: beech wala point beech mein rehta hai. */
    public void setRotation(float deg) {
        float nr = ((deg % 360f) + 360f) % 360f;
        if (Math.abs(nr - rotDeg) < 0.0001f) return;
        rotDeg = nr;
        double r = Math.toRadians(nr);
        cos = snap((float) Math.cos(r));
        sin = snap((float) Math.sin(r));
        stopMotion();
        generation++;                    // purana async crop stale
        relayout();
        scheduleSettle();                // rotation ke baad sharp crop dobara (agar zoomed)
    }

    /** zoom==1 par absolute screen scale (FIT/FILL/1:1 — host decide karta hai). 0 = auto-fit. */
    public void setBaseScale(float s) {
        if (Float.isNaN(s) || Float.isInfinite(s) || s < 0f) s = 0f;
        if (Math.abs(s - baseScale) < 0.00001f) return;
        baseScale = s;
        generation++;                    // purana async crop stale (visible area badal gayi)
        relayout();
    }

    /** Host-set zoom cap (zoom units). */
    public void setMaxZoom(float m) {
        if (Float.isNaN(m) || Float.isInfinite(m)) return;
        float nm = clamp(m, 1f, 12f);
        if (overrideMaxZoom != null && Math.abs(nm - overrideMaxZoom) < 0.0001f) return;
        overrideMaxZoom = nm;
        relayout();
    }

    public void zoomIn()  { zoomBy(1.5f); }
    public void zoomOut() { zoomBy(1f / 1.5f); }
    public void zoomBy(float factor) { animateZoom(zoom * factor, 0, 0, 180); }

    /** Zoom 1x + content centre par. (Rotation reset nahi hoti — host alag karta hai.) */
    public void reset(boolean animate) {
        if (animate) { animateReset(220); return; }
        stopMotion();
        generation++;
        zoom = 1f; cx = cy = 0f;
        clampC();
        dispatch(false);
    }

    /** View detach: pending settle/animation band. */
    public void destroy() {
        handler.removeCallbacks(settleTask);
        stopMotion();
        generation++;
    }

    public float getZoom() { return zoom; }
    public float getMaxZoom() { return maxZoom; }
    /** Ab ka asli screen scale (content px -> view px). */
    public float getScale() { return scale(); }
    /** View centre se content centre ka offset, view px (View.setTranslationX/Y ke barabar). */
    public float getPanX() { return -(cx * cos - cy * sin) * scale(); }
    public float getPanY() { return -(cx * sin + cy * cos) * scale(); }
    public float getRotation() { return rotDeg; }
    /** Zoom jahan 1 content px = 1 screen px. */
    public float pixelPerfectZoom() { float b = base(); return b > 0 ? 1f / b : 1f; }
    public boolean isZoomed() { return zoom > 1.001f; }
    public boolean isCurrent(int gen) { return gen == generation; }
    public int getGeneration() { return generation; }
    /** content px -> view px (rotation shamil). */
    public Matrix getMatrix() { return matrix; }
    /** Visible area ka AABB, content px mein (content ke andar clamp). */
    public RectF getVisible() { return new RectF(visible); }

    public Bundle saveState() {
        Bundle b = new Bundle();
        b.putFloat("z", zoom); b.putFloat("cx", cx); b.putFloat("cy", cy); b.putFloat("rot", rotDeg);
        return b;
    }
    public void restoreState(Bundle b) {
        if (b == null) return;
        zoom = b.getFloat("z", 1f); cx = b.getFloat("cx", 0f); cy = b.getFloat("cy", 0f);
        setRotation(b.getFloat("rot", rotDeg));
        relayout();
        dispatch(false, false);
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
            if (!pinching) { clampC(); dispatch(false); }
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

    // ---------------------------------------------------------------- core math

    private boolean ready() { return vw > 0 && vh > 0 && cw > 0 && ch > 0; }

    /** zoom==1 scale: host ka base, warna auto-fit (nearest-quadrant dims). */
    private float base() {
        if (baseScale > 0f) return baseScale;
        if (!ready()) return 1f;
        boolean swap = sin * sin >= 0.5f;
        float rw = swap ? ch : cw, rh = swap ? cw : ch;
        return Math.min(vw / rw, vh / rh);
    }

    private float scale() { return base() * zoom; }

    /** view-rect ko content-rect ke andar rakhne ki EXACT limit (content axes, screen px units: u = c*s). */
    private float limU(boolean xAxis, float s) {
        float ac = Math.abs(cos), as = Math.abs(sin);
        float v = xAxis ? cw * s / 2f - (ac * vw / 2f + as * vh / 2f)
                        : ch * s / 2f - (as * vw / 2f + ac * vh / 2f);
        return Math.max(0f, v);
    }

    private void clampC() {
        float s = scale();
        if (s <= 0f || !ready()) return;
        float lx = limU(true, s) / s, ly = limU(false, s) / s;
        cx = clamp(cx, -lx, lx);
        cy = clamp(cy, -ly, ly);
    }

    /** Pan screen-space delta (px) — content finger ke saath chalta hai. */
    private void panByScreen(float dx, float dy) {
        float s = scale();
        if (s <= 0f) return;
        cx -= (dx * cos + dy * sin) / s;
        cy -= (-dx * sin + dy * cos) / s;
    }

    /** Focus point (fx,fy = screen centre se offset) ke neeche wala content point fixed rakh kar zoom. */
    private void zoomAbout(float z1, float fx, float fy) {
        float s0 = scale();
        float qx = fx * cos + fy * sin, qy = -fx * sin + fy * cos;   // R(-rot) f
        zoom = z1;
        float s1 = scale();
        if (s0 > 0f && s1 > 0f) {
            cx += qx / s0 - qx / s1;
            cy += qy / s0 - qy / s1;
        }
        clampC();
    }

    private void recomputeMax() {
        if (overrideMaxZoom != null) {
            maxZoom = clamp(overrideMaxZoom, 1f, 12f);
        } else {
            boolean swap = sin * sin >= 0.5f;
            float rw = swap ? ch : cw;
            maxZoom = clamp((rw / vw) * 2.5f, 4f, 12f);
        }
        maxZoom = Math.max(maxZoom, Math.min(12f, pixelPerfectZoom()));   // 1:1 hamesha allowed
    }

    private void relayout() {
        if (!ready()) return;
        recomputeMax();
        zoom = clamp(zoom, 1f, maxZoom);
        clampC();
        rebuild();
    }

    /** Matrix + visible rect state se dobara banao (listener ko call nahi). */
    private void rebuild() {
        float s = scale();
        if (s <= 0f || !ready()) return;
        float panX = -(cx * cos - cy * sin) * s;
        float panY = -(cx * sin + cy * cos) * s;
        matrix.reset();
        matrix.postTranslate(-cw / 2f, -ch / 2f);
        matrix.postRotate(rotDeg);
        matrix.postScale(s, s);
        matrix.postTranslate(vw / 2f + panX, vh / 2f + panY);

        float ac = Math.abs(cos), as = Math.abs(sin);
        float hx = (ac * vw / 2f + as * vh / 2f) / s;
        float hy = (as * vw / 2f + ac * vh / 2f) / s;
        float ccx = cw / 2f + cx, ccy = ch / 2f + cy;
        visible.set(clamp(ccx - hx, 0f, cw), clamp(ccy - hy, 0f, ch),
                    clamp(ccx + hx, 0f, cw), clamp(ccy + hy, 0f, ch));
    }

    private void setViewSize(int w, int h) {
        if (w == vw && h == vh) return;           // no change → no relayout loop
        vw = w; vh = h;
        relayout();
        if (ready()) listener.onTransform(matrix, zoom, false);   // layout badla — host ko batao (settle schedule NAHI)
    }

    private float nextDoubleTapLevel() {
        float pp = pixelPerfectZoom();
        float[] levels = pp > 3f && pp <= maxZoom
                ? new float[]{DOUBLE_TAP_ZOOM, pp} : new float[]{DOUBLE_TAP_ZOOM};
        for (float lv : levels) if (zoom < lv * 0.95f) return Math.min(lv, maxZoom);
        return 1f;
    }

    // ---------------------------------------------------------------- animation / motion

    private void animateZoom(float target, float fx, float fy, long ms) {
        stopMotion();
        final float t = clamp(target, 1f, maxZoom);
        final float z0 = zoom;
        if (Math.abs(t - z0) < 0.001f) { clampC(); dispatch(false); return; }
        generation++;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(ms);
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        // Focus ke neeche ka content point q — animation ke dauran fixed
        final float s0 = scale();
        final float qx0 = fx * cos + fy * sin, qy0 = -fx * sin + fy * cos;
        final float cx0 = cx, cy0 = cy;
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            zoom = z0 * (float) Math.pow(t / z0, f);          // geometric zoom
            float s1 = scale();
            if (s0 > 0f && s1 > 0f) {
                cx = cx0 + qx0 / s0 - qx0 / s1;
                cy = cy0 + qy0 / s0 - qy0 / s1;
            }
            clampC();
            dispatch(true);
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) { dispatch(false); }
        });
        anim.start();
    }

    /** Animated reset: zoom -> 1 AUR centre par wapas. */
    private void animateReset(long ms) {
        stopMotion();
        final float z0 = zoom, cx0 = cx, cy0 = cy;
        if (Math.abs(z0 - 1f) < 0.001f && Math.abs(cx0) < 0.5f && Math.abs(cy0) < 0.5f) {
            reset(false);
            return;
        }
        generation++;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(ms);
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            zoom = z0 * (float) Math.pow(1f / z0, f);
            cx = cx0 * (1f - f);
            cy = cy0 * (1f - f);
            clampC();
            dispatch(true);
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) { dispatch(false); }
        });
        anim.start();
    }

    private void stopMotion() {
        if (anim != null) { anim.removeAllListeners(); anim.cancel(); anim = null; }
        if (!scroller.isFinished()) scroller.abortAnimation();
        host.removeCallbacks(flingTask);
    }

    private final Runnable flingTask = new Runnable() {
        @Override public void run() {
            if (scroller.computeScrollOffset()) {
                if (flingScale > 0f) {
                    cx = scroller.getCurrX() / flingScale;
                    cy = scroller.getCurrY() / flingScale;
                }
                clampC();
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

    private void scheduleSettle() {
        handler.removeCallbacks(settleTask);
        handler.postDelayed(settleTask, SETTLE_MS);
    }

    private void dispatch(boolean interacting) { dispatch(interacting, true); }

    private void dispatch(boolean interacting, boolean scheduleSettle) {
        if (!ready()) return;
        rebuild();
        listener.onTransform(matrix, zoom, interacting);
        if (scheduleSettle) scheduleSettle();
    }

    // ---------------------------------------------------------------- utils

    private static float snap(float v) {
        if (Math.abs(v) < 1e-6f) return 0f;
        if (Math.abs(v - 1f) < 1e-6f) return 1f;
        if (Math.abs(v + 1f) < 1e-6f) return -1f;
        return v;
    }
    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
    private static int clampInt(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
