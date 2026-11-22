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
 * Frame Mode view — v2.6.0.
 *
 * ZoomController = poora engine (zoom, pan, rotate, clamp, matrix, visible-rect).
 * Ye view sirf: (1) ZC ko bitmap size + rotation + display-base (FIT/FILL/1:1) batata hai,
 * (2) ZC ki matrix se bitmap aur settled sharp-crop DRAW karta hai.
 * Dono ek hi matrix se draw hote hain → rotate / zoom / pan / fine-tilt kabhi diverge nahi karte.
 *
 * Owner-ordered features waise hi: single tap = controls, double-tap = ±1 frame,
 * hold = ±5 frames (ZC double-tap zoom OFF), FIT/FILL/1:1, rotation (90-steps + fine).
 */
final class ZoomableImageView extends View {
    static final int MODE_FIT = 0, MODE_FILL = 1, MODE_ONE_TO_ONE = 2;

    interface GestureActions { void singleTap(); void doubleTap(float x); void hold(boolean active, float x); void interaction(boolean active); }
    interface SettledListener { void onFrameSettled(); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix cropMatrix = new Matrix();
    private Bitmap bitmap;
    private int displayMode = MODE_FIT;
    private float rotation = 0f;           // total, normalized 0..360 (90-steps + fine)

    private final ZoomController zc;
    private SettledListener settledListener;
    private Bitmap sharpCrop;              // settled bicubic crop of a BITMAP-SPACE region
    private int sharpL, sharpT;            // crop ka top-left, bitmap px
    private float sharpSx = 1f, sharpSy = 1f;   // crop px per bitmap px
    private GestureDetector fallbackGestures;
    private float lastX, lastY;
    private GestureActions actions;

    ZoomableImageView(Context c) {
        super(c);
        setFocusable(true);
        zc = new ZoomController(c, this, new ZoomController.Listener() {
            @Override public void onTransform(Matrix m, float z, boolean interacting) {
                dropSharpCrop();               // fast path — sharp overlay ab stale hai
                invalidate();
            }
            @Override public void onSettled(RectF visible, float z, int generation) {
                if (!zc.isCurrent(generation) || !zc.isZoomed()) return;   // sirf zoomed par
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

    /** Purani touch logic = ZC fallback (jab zoomed NAHI). */
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
        dropSharpCrop();
        syncZc();
        if (!keepTransform) zc.reset(false);
        invalidate();
    }

    void setDisplayMode(int m) {
        displayMode = m;
        dropSharpCrop();
        syncZc();                      // base mode ke saath badalta hai
        invalidate();
    }

    /** 90-step rotation. (Total rotation ka hissa — setFineRotation total deta hai.) */
    void setDisplayRotation(int r) {
        rotation = ((r % 360) + 360) % 360;
        dropSharpCrop();
        syncZc();                      // ZC rotate karta hai + settle schedule (sharp crop refresh)
        invalidate();
    }

    /** Total angle (90-steps + fine) — NORMALIZED 0..360. */
    void setFineRotation(float deg) {
        rotation = ((deg % 360f) + 360f) % 360f;
        dropSharpCrop();
        syncZc();
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
        zc.destroy();
    }

    void zoomBy(float f) { zc.zoomBy(f); }
    void resetZoom() { zc.reset(false); dropSharpCrop(); invalidate(); }
    /** Async settled crop ka stale-guard token. */
    int getZcGeneration() { return zc.getGeneration(); }
    float getZoom() { return zc.getZoom(); }

    /** Settled sharp view. Agli move par automatically drop ho jata hai. */
    void setSharpCrop(Bitmap b, int generation, int left, int top, float sx, float sy) {
        if (b != null && generation != zc.getGeneration()) { b.recycle(); return; }   // stale
        if (b != null && (sx <= 0f || sy <= 0f || Float.isNaN(sx) || Float.isNaN(sy))) { b.recycle(); return; }
        if (sharpCrop != null && sharpCrop != b && !sharpCrop.isRecycled()) sharpCrop.recycle();
        sharpCrop = b;
        sharpL = left; sharpT = top; sharpSx = sx; sharpSy = sy;
        invalidate();
    }

    /** Ab ka on-screen scale (bitmap px -> screen px); crop isi density par banta hai. */
    float getScreenScale() { return zc.getScale(); }

    /** Visible area, BITMAP pixel coords (settled crop decode ke liye). ZC content = bitmap,
     *  isliye rotation (90-step ya tilt) par bhi seedha sahi AABB milta hai. */
    RectF getBitmapVisibleRect() {
        if (bitmap == null || bitmap.isRecycled()) return new RectF();
        return zc.getVisible();
    }

    /** ZC ko bitmap size, rotation aur display-base batao. Silent — draw hum khud invalidate karte hain. */
    private void syncZc() {
        if (bitmap == null || bitmap.isRecycled()) return;
        zc.setContent(bitmap.getWidth(), bitmap.getHeight());
        zc.setRotation(rotation);
        zc.setBaseScale(baseScale());
    }

    /** Display base (zoom==1 par scale). Rotation ke saath CONTINUOUS: 0°/180° par asli fit,
     *  90°/270° par swapped fit, beech mein sin² blend — koi scale jump nahi. */
    private float baseScale() {
        if (bitmap == null || bitmap.isRecycled()) return 1f;
        int vw = getWidth(), vh = getHeight();
        if (vw <= 0 || vh <= 0) return 1f;
        float bw = bitmap.getWidth(), bh = bitmap.getHeight();
        if (displayMode == MODE_ONE_TO_ONE) return 1f;
        float a, b;
        if (displayMode == MODE_FILL) {
            a = Math.max(vw / bw, vh / bh);
            b = Math.max(vw / bh, vh / bw);
        } else {
            a = Math.min(vw / bw, vh / bh);
            b = Math.min(vw / bh, vh / bw);
        }
        double sn = Math.sin(Math.toRadians(rotation));
        float w = (float) (sn * sn);
        return a * (1f - w) + b * w;
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        syncZc();                      // view size badla → base dobara
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (bitmap == null || bitmap.isRecycled()) return;
        // EK hi matrix: bitmap px -> T(-c) -> R(rot) -> S(base*zoom) -> T(view centre + pan)
        Matrix m = zc.getMatrix();
        c.drawBitmap(bitmap, m, paint);
        if (sharpCrop != null && !sharpCrop.isRecycled()) {
            // Crop usi matrix se: crop px -> bitmap px -> screen. Alag rotation logic nahi.
            paint.setFilterBitmap(true);
            cropMatrix.set(m);
            cropMatrix.preTranslate(sharpL, sharpT);
            cropMatrix.preScale(1f / sharpSx, 1f / sharpSy);
            c.drawBitmap(sharpCrop, cropMatrix, paint);
        }
    }
}
