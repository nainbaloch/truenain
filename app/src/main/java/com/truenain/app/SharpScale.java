package com.truenain.app;

import android.graphics.Bitmap;

/**
 * v2.4.6 PHASE 1/2 (pack requirement): Catmull-Rom BICUBIC scaler — the settled
 * sharp-crop path must NOT use bilinear (createScaledBitmap), because zoomed
 * frames upscaled bilinear look soft/dirty (user complaint).
 *
 * Separable two-pass Catmull-Rom (horizontal then vertical) — quality close to
 * Lanczos for this use, ~2x faster, bounded memory (intermediate buffer only).
 * Pure Java pixel work — call on a worker thread, never on the UI thread.
 */
final class SharpScale {
    private SharpScale() {}

    /** Returns a new ARGB_8888 bitmap scaled to dw×dh with bicubic weights, or null on failure/OOM. */
    static Bitmap scale(Bitmap src, int dw, int dh) {
        if (src == null || src.isRecycled() || dw <= 0 || dh <= 0) return null;
        try {
            int sw = src.getWidth(), sh = src.getHeight();
            if (sw <= 0 || sh <= 0 || sw > 4096 || sh > 4096) return null;
            if (dw == sw && dh == sh) return src.copy(Bitmap.Config.ARGB_8888, false);
            int[] in = new int[sw * sh];
            src.getPixels(in, 0, sw, 0, 0, sw, sh);
            // Pass 1 (horizontal): sw×sh → dw×sh
            int[] mid = resampleH(in, sw, sh, dw);
            // Pass 2 (vertical): dw×sh → dw×dh
            int[] out = resampleV(mid, dw, sh, dh);
            Bitmap bmp = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
            bmp.setPixels(out, 0, dw, 0, 0, dw, dh);
            return bmp;
        } catch (OutOfMemoryError oom) {
            return null;   // pack rule 6: never crash on 4K — caller falls back to the fast path
        } catch (RuntimeException rte) {
            return null;
        }
    }

    /** Horizontal Catmull-Rom: sw×sh → dw×sh. */
    private static int[] resampleH(int[] src, int sw, int sh, int dw) {
        int[] dst = new int[dw * sh];
        float scale = (float) sw / dw;
        for (int y = 0; y < sh; y++) {
            int row = y * sw, outRow = y * dw;
            for (int x = 0; x < dw; x++) {
                float fx = (x + 0.5f) * scale - 0.5f;
                int x0 = (int) Math.floor(fx);
                float t = fx - x0;
                dst[outRow + x] = sample4(src, sw,
                        clampi(x0 - 1, 0, sw - 1), clampi(x0, 0, sw - 1),
                        clampi(x0 + 1, 0, sw - 1), clampi(x0 + 2, 0, sw - 1), t, row);
            }
        }
        return dst;
    }

    /** Vertical Catmull-Rom: dw×sh → dw×dh. */
    private static int[] resampleV(int[] src, int dw, int sh, int dh) {
        int[] dst = new int[dw * dh];
        float scale = (float) sh / dh;
        for (int y = 0; y < dh; y++) {
            float fy = (y + 0.5f) * scale - 0.5f;
            int y0 = (int) Math.floor(fy);
            float t = fy - y0;
            int r0 = clampi(y0 - 1, 0, sh - 1), r1 = clampi(y0, 0, sh - 1);
            int r2 = clampi(y0 + 1, 0, sh - 1), r3 = clampi(y0 + 2, 0, sh - 1);
            int outRow = y * dw;
            for (int x = 0; x < dw; x++) {
                int p0 = src[r0 * dw + x], p1 = src[r1 * dw + x];
                int p2 = src[r2 * dw + x], p3 = src[r3 * dw + x];
                float w0 = cubic(t + 1f), w1 = cubic(t), w2 = cubic(1f - t), w3 = cubic(2f - t);
                int r = clamp255(Math.round(w0 * ((p0 >>> 16) & 255) + w1 * ((p1 >>> 16) & 255)
                        + w2 * ((p2 >>> 16) & 255) + w3 * ((p3 >>> 16) & 255)));
                int g = clamp255(Math.round(w0 * ((p0 >>> 8) & 255) + w1 * ((p1 >>> 8) & 255)
                        + w2 * ((p2 >>> 8) & 255) + w3 * ((p3 >>> 8) & 255)));
                int b = clamp255(Math.round(w0 * (p0 & 255) + w1 * (p1 & 255)
                        + w2 * (p2 & 255) + w3 * (p3 & 255)));
                dst[outRow + x] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }
        return dst;
    }

    /** 4-tap weighted sample of one row at (xa..xd) with fractional blend t between b and c. */
    private static int sample4(int[] src, int sw, int xa, int xb, int xc, int xd, float t, int row) {
        int pa = src[row + xa], pb = src[row + xb], pc = src[row + xc], pd = src[row + xd];
        float w0 = cubic(t + 1f), w1 = cubic(t), w2 = cubic(1f - t), w3 = cubic(2f - t);
        int r = clamp255(Math.round(w0 * ((pa >>> 16) & 255) + w1 * ((pb >>> 16) & 255)
                + w2 * ((pc >>> 16) & 255) + w3 * ((pd >>> 16) & 255)));
        int g = clamp255(Math.round(w0 * ((pa >>> 8) & 255) + w1 * ((pb >>> 8) & 255)
                + w2 * ((pc >>> 8) & 255) + w3 * ((pd >>> 8) & 255)));
        int b = clamp255(Math.round(w0 * (pa & 255) + w1 * (pb & 255)
                + w2 * (pc & 255) + w3 * (pd & 255)));
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    /** Catmull-Rom kernel (B=0, C=0.5). */
    private static float cubic(float x) {
        x = Math.abs(x);
        if (x < 1f) return 1.5f * x * x * x - 2.5f * x * x + 1f;
        if (x < 2f) return -0.5f * x * x * x + 2.5f * x * x - 4f * x + 2f;
        return 0f;
    }

    private static int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
    private static int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
}
