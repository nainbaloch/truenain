package com.truenain.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

/**
 * Frame Mode view.  v2.4.6 FINAL: ZoomController = gesture/state engine ONLY
 * (pinch/drag/fling/wheel/settled); the DRAWING uses the PROVEN v2.4.5 matrix
 * structure (translate-center → rotate → scale → translate-view+pan) — the
 * fragile bbox/region composition that caused black/zoomed-frame bugs is GONE.
 *
 * Kept owner-ordered features: single tap = controls, double-tap = ±1 frame,
 * hold = ±5 frames (ZoomController double-tap zoom disabled), FIT/FILL/1:1,
 * rotation (90-steps + fine, normalized), settled bicubic sharp crop.
 */
final class ZoomableImageView extends View {
    static final int MODE_FIT = 0, MODE_FILL = 1, MODE_ONE_TO_ONE = 2;

    interface GestureActions { void singleTap(); void doubleTap(float x); void hold(boolean active, float x); void interaction(boolean active); }
    interface SettledListener { void onFrameSettled(); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final Matrix cropMatrix = new Matrix();   // L2
    private Bitmap bitmap;
    private int displayMode = MODE_FIT;
    private float rotation = 0f;           // total, normalized 0..360 (90-steps + fine)

    // v2.4.6: single zoom engine — gestures/state; zoom/pan fed into the proven draw
    private final ZoomController zc;
    private SettledListener settledListener;
    private Bitmap sharpCrop;              // L2: settled bicubic crop of a BITMAP-SPACE region (not view-sized)
    private int sharpL, sharpT;            // L2: crop's top-left in bitmap pixels
    private float sharpSx = 1f, sharpSy = 1f;   // L2: crop pixels per bitmap pixel
    private GestureDetector fallbackGestures;
    private float lastX, lastY;
    private float zoom = 1f, panX, panY;   // fed from ZoomController onTransform
    private GestureActions actions;
    private int rotationEpoch;             // L1: har rotation badalne par +1 — purana async crop reject

    /** L1: zoom generation + rotation epoch ek token — dono mein se kuch badle to crop stale. */
    private int overlayToken() { return zc.getGeneration() * 1000003 + rotationEpoch; }

    ZoomableImageView(Context c) {
        super(c);
        setFocusable(true);
        zc = new ZoomController(c, this, new ZoomController.Listener() {
            @Override public void onTransform(Matrix m, float z, boolean interacting) {
                float[] vals = new float[9];
                m.getValues(vals);
                zoom = z;
                // v2.4.8 FIX: ZC content = ROTATED dims — pan extraction bhi rotated dims
                // se ho. Pehle unrotated bitmap dims use hote the → rotation 90/270 par
                // panX/panY mein (bw−cw)·s/2 ka farq → zoom ke saath frame drift karta tha.
                boolean swap = ((int) Math.round(rotation) % 180) == 90;
                float cwv = bitmap != null ? (swap ? bitmap.getHeight() : bitmap.getWidth()) : 0f;
                float chv = bitmap != null ? (swap ? bitmap.getWidth() : bitmap.getHeight()) : 0f;
                panX = vals[Matrix.MTRANS_X] + cwv * vals[Matrix.MSCALE_X] / 2f - getWidth() / 2f;
                panY = vals[Matrix.MTRANS_Y] + chv * vals[Matrix.MSCALE_X] / 2f - getHeight() / 2f;
                sharpCrop = null;              // fast path while moving — sharp overlay is stale
                invalidate();
            }
            @Override public void onSettled(RectF visible, float z, int generation) {
                if (!zc.isCurrent(generation) || !zc.isZoomed()) return;   // only when zoomed
                // L2: crop ab bitmap-space region hai aur usi matrix se draw hota hai —
                // tilted frame par bhi bilkul match karta hai, isliye 90-step guard hata diya.
                if (settledListener != null) settledListener.onFrameSettled();
            }
            @Override public void onSingleTap() {
                if (actions != null) actions.singleTap();
            }
        });
        zc.setDoubleTapZoomEnabled(false);     // double-tap = ±1 frame (approved feature)
        zc.setFallback(this::onTouchFallback);
        setOnTouchListener(zc);
        setOnGenericMotionListener(zc);

        // Fallback gestures: only what ZoomController does NOT own (frame-step + hold).
        fallbackGestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (actions != null) actions.doubleTap(e.getX());
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (actions != null) actions.hold(true, e.getX());
            }
        });
    }

    void setGestureActions(GestureActions a) { actions = a; }
    void setOnSettledListener(SettledListener l) { settledListener = l; }

    /** Old touch logic = ZoomController fallback (runs when NOT zoomed). */
    private boolean onTouchFallback(View v, MotionEvent e) {
        fallbackGestures.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX(); lastY = e.getY();
                if (actions != null) actions.interaction(true);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (actions != null) { actions.hold(false, lastX); actions.interaction(false); }
                break;
        }
        return true;
    }

    void setFrame(Bitmap b, boolean keepTransform) {
        bitmap = b;
        sharpCrop = null;
        updateZcContent();
        if (!keepTransform) zc.reset(false);
        invalidate();
    }

    void setDisplayMode(int m) {
        displayMode = m;
        sharpCrop = null;
        syncZcBase();                  // v2.4.8: base mode ke saath badalta hai — ZC ko batao
        invalidate();
    }

    /** v2.4.7 BUG 1: rotation ke baad layout settle hone par hi sharp crop refresh. */
    private final Runnable settledRefresh = new Runnable() {
        @Override public void run() {
            if (zc.isZoomed() && settledListener != null) settledListener.onFrameSettled();
        }
    };

    void setDisplayRotation(int r) {
        float nr = ((r % 360) + 360) % 360;
        if (nr != rotation) rotationEpoch++;
        rotation = nr;
        dropSharpCrop();               // purana overlay foran clear — normal frame dikhe
        updateZcContent();
        removeCallbacks(settledRefresh);
        postDelayed(settledRefresh, 350);   // layout settle hone par hi refresh
        invalidate();
    }

    /** v2.4.5 fine angle (absolute total) — NORMALIZED 0..360 (no unbounded accumulation). */
    void setFineRotation(float deg) {
        float nr = ((deg % 360) + 360) % 360;
        if (nr != rotation) rotationEpoch++;
        rotation = nr;
        dropSharpCrop();               // purana overlay foran clear — normal frame dikhe
        updateZcContent();
        removeCallbacks(settledRefresh);
        postDelayed(settledRefresh, 350);   // layout settle hone par hi refresh
        invalidate();
    }

    float getRotationDeg() { return rotation; }

    private void dropSharpCrop() {
        Bitmap old = sharpCrop;
        sharpCrop = null;
        if (old != null && !old.isRecycled()) old.recycle();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(settledRefresh);
        zc.destroy();
    }

    void zoomBy(float f) { zc.zoomBy(f); }
    void resetZoom() { zc.reset(false); sharpCrop = null; invalidate(); }
    /** v2.4.6: stale-guard generation for the async settled crop. */
    int getZcGeneration() { return overlayToken(); }
    /** v2.4.6 PHASE 2: zoom-aware look grading needs the current zoom. */
    float getZoom() { return zc.getZoom(); }

    /** Settled sharp view (view-sized bicubic crop). Dropped automatically on the next move. */
    void setSharpCrop(Bitmap b, int generation, int left, int top, float sx, float sy) {
        if (b != null && generation != overlayToken()) { b.recycle(); return; }   // stale (zoom ya rotation badla)
        if (b != null && (sx <= 0f || sy <= 0f || Float.isNaN(sx) || Float.isNaN(sy))) { b.recycle(); return; }
        if (sharpCrop != null && sharpCrop != b && !sharpCrop.isRecycled()) sharpCrop.recycle();
        sharpCrop = b;
        sharpL = left; sharpT = top; sharpSx = sx; sharpSy = sy;
        invalidate();
    }

    /** L2: current on-screen scale (bitmap px -> screen px); crop is built at this density. */
    float getScreenScale() { return baseScale() * zoom; }

    /** Visible area in BITMAP pixel coords (for the settled crop decode). UI-thread only.
     *  v2.4.8 FIX: ZC visible = ROTATED content coords — seedha bitmap par lagane se
     *  rotated video (90/180/270) ka crop GHALAT jagah banta tha (5–20% shift wala
     *  masla). Ab corners R(−rotation) se bitmap space mein map hote hain. */
    RectF getBitmapVisibleRect() {
        RectF vis = zc.getVisible();
        if (bitmap == null || bitmap.isRecycled()) return vis;
        int bw = bitmap.getWidth(), bh = bitmap.getHeight();
        boolean swap = ((int) Math.round(rotation) % 180) == 90;
        int cw = swap ? bh : bw, ch = swap ? bw : bh;
        Matrix m = new Matrix();
        m.postTranslate(-cw / 2f, -ch / 2f);
        m.postRotate(-rotation);
        m.postTranslate(bw / 2f, bh / 2f);
        float[] pts = {vis.left, vis.top, vis.right, vis.top, vis.right, vis.bottom, vis.left, vis.bottom};
        m.mapPoints(pts);
        float minX = Math.min(Math.min(pts[0], pts[2]), Math.min(pts[4], pts[6]));
        float maxX = Math.max(Math.max(pts[0], pts[2]), Math.max(pts[4], pts[6]));
        float minY = Math.min(Math.min(pts[1], pts[3]), Math.min(pts[5], pts[7]));
        float maxY = Math.max(Math.max(pts[1], pts[3]), Math.max(pts[5], pts[7]));
        return new RectF(Math.max(0f, minX), Math.max(0f, minY),
                Math.min(bw, maxX), Math.min(bh, maxY));
    }

    /** Keep ZoomController's content = ROTATED bitmap dims (its maxZoom/pan/visible math needs it). */
    private void updateZcContent() {
        if (bitmap == null || bitmap.isRecycled()) return;
        boolean swap = ((int) Math.round(rotation) % 180) == 90;
        int bw = bitmap.getWidth(), bh = bitmap.getHeight();
        zc.setContent(swap ? bh : bw, swap ? bw : bh);
        syncZcBase();                  // v2.4.8: base bhi mode/rotation ke hisaab se
    }

    /** v2.4.8 FIX (auto-zoom jump ka asli jarr): ZC ka base = host ka ASLI display base.
     *  ZC hamesha FIT-min base maanta tha, lekin drawing FILL (max) / 1:1 (1) use karti
     *  hai — FILL/1:1 mein ZC ka visible-rect ghalat → settled sharp-crop ghalat region
     *  ka → har settle par zoom jump. Factor se dono ka base barabar. */
    private void syncZcBase() {
        if (bitmap == null || bitmap.isRecycled()) return;
        int bw = bitmap.getWidth(), bh = bitmap.getHeight();
        boolean swap = ((int) Math.round(rotation) % 180) == 90;
        float rw = swap ? bh : bw, rh = swap ? bw : bh;
        int vw = getWidth(), vh = getHeight();
        if (vw <= 0 || vh <= 0 || rw <= 0 || rh <= 0) return;
        float fitBase = Math.min(vw / rw, vh / rh);
        if (fitBase <= 0f) return;
        zc.setBaseFactor(baseScale() / fitBase);
    }

    /** Display base for the current mode (v2.4.5 proven logic). */
    private float baseScale() {
        if (bitmap == null || bitmap.isRecycled()) return 1f;
        int bw = bitmap.getWidth(), bh = bitmap.getHeight();
        boolean swap = ((int) Math.round(rotation) % 180) == 90;
        float rw = swap ? bh : bw, rh = swap ? bw : bh;
        int vw = getWidth(), vh = getHeight();
        if (vw <= 0 || vh <= 0) return 1f;
        switch (displayMode) {
            case MODE_FILL: return Math.max(vw / rw, vh / rh);
            case MODE_ONE_TO_ONE: return 1f;
            default: return Math.min(vw / rw, vh / rh);   // FIT
        }
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        updateZcContent();             // v2.4.8: view size badla → base sync (FILL/1:1 view-dependent)
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (bitmap == null || bitmap.isRecycled()) return;
        int bw = bitmap.getWidth(), bh = bitmap.getHeight();
        int vw = getWidth(), vh = getHeight();
        // v2.4.6 FINAL: PROVEN v2.4.5 matrix chain + ZoomController zoom/pan:
        //   p → T(-bitmapC) → R(rotation) → S(base·zoom) → T(viewCenter + pan)
        float s = baseScale() * zoom;
        matrix.reset();
        matrix.postTranslate(-bw / 2f, -bh / 2f);
        matrix.postRotate(rotation);
        matrix.postScale(s, s);
        matrix.postTranslate(vw / 2f + panX, vh / 2f + panY);
        c.drawBitmap(bitmap, matrix, paint);
        if (sharpCrop != null && !sharpCrop.isRecycled()) {
            // L2: SAME matrix as the frame — crop px -> bitmap px -> screen. No stretch, no
            // separate rotation logic: rotate/zoom/pan/tilt can never disagree with the frame.
            paint.setFilterBitmap(true);
            cropMatrix.set(matrix);
            cropMatrix.preTranslate(sharpL, sharpT);
            cropMatrix.preScale(1f / sharpSx, 1f / sharpSy);
            c.drawBitmap(sharpCrop, cropMatrix, paint);
        }
    }
}
