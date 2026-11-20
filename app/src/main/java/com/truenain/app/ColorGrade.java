package com.truenain.app;

import android.graphics.Bitmap;

/**
 * Quality Pack Phase 1: editor-style color grading — pure Java math (no
 * third-party libs, no internet).
 *
 * Preset params order: bright(-0.5..0.5) contrast(0.5..2) sat(0..2)
 * warm(-1..1) shadowLift(0..1) highlightTame(0..1) vignette(0..1) sharpen(0..1.5)
 *
 * v2.4.6 PHASE 2 (pack): processing order FIXED —
 *   1. light denoise / deblock first (kills compression blocks BEFORE sharpening)
 *   2. then light sharpen (strength DEPENDS on zoom: less sharpen at high zoom)
 *   3. then colour adjustments + vignette
 * Previously sharpen ran on raw noisy pixels (amplified blocks at zoom).
 *
 * Used by: Frame Mode SAVE (Bitmap grading), settled zoom crops (zoom-aware)
 * and the trim re-encode shader (ExactTrimTranscoder reads the same table via params()).
 */
final class ColorGrade {
    private ColorGrade() {}

    static final int NONE = 0, HDR = 1, CINEMATIC = 2, VIVID = 3, WARM = 4, COOL = 5;

    private static final float[][] PRESETS = {
            { 0f,     1f,    1f,    0f,     0f,    0f,    0f,    0f    },  // NONE
            { 0.015f, 1.15f, 1.18f, 0.06f,  0.30f, 0.22f, 0.10f, 0.60f },  // HDR+
            { 0f,     1.22f, 0.95f, -0.25f, 0.35f, 0.28f, 0.20f, 0.35f },  // CINEMATIC
            { 0f,     1.18f, 1.35f, 0.05f,  0.15f, 0.12f, 0f,    0.50f },  // VIVID
            { 0.03f,  1f,    1.08f, 0.40f,  0f,    0f,    0f,    0f    },  // WARM
            { 0f,     1f,    1.05f, -0.40f, 0f,    0f,    0f,    0f    },  // COOL
    };

    static String name(int preset) {
        switch (preset) {
            case HDR: return "HDR+";
            case CINEMATIC: return "Cinematic";
            case VIVID: return "Vivid";
            case WARM: return "Warm";
            case COOL: return "Cool";
            default: return "Original";
        }
    }

    /** Returns the 8-param table for a preset id (clamped). */
    static float[] params(int preset) {
        return PRESETS[Math.max(0, Math.min(PRESETS.length - 1, preset))];
    }

    /** Zoom-unaware overload (save paths, trim) — zoom treated as 1.0. */
    static Bitmap grade(Bitmap src, int preset) {
        return grade(src, preset, 1f);
    }

    /**
     * Grades a bitmap (v2.4.6 order: denoise → sharpen(zoom-aware) → colour → vignette).
     * Returns a NEW bitmap (or the original reference when preset ≤ 0).  Caller owns the result.
     */
    static Bitmap grade(Bitmap src, int preset, float zoom) {
        return grade(src, preset, zoom, true);
    }

    /** L2: vignette=false for zoomed crops (a crop is not the whole frame — no centre/edge vignette). */
    static Bitmap grade(Bitmap src, int preset, float zoom, boolean vignette) {
        if (src == null || src.isRecycled() || preset <= 0) return src;
        float[] p = params(preset);
        float bright = p[0], contrast = p[1], sat = p[2], warm = p[3];
        float shadow = p[4], high = p[5], vig = vignette ? p[6] : 0f;
        float sharp = p[7] * sharpenScale(zoom);
        float denoise = zoom >= 2.0f ? 0f : 0.12f;
        int w = src.getWidth(), h = src.getHeight();
        if (w <= 0 || h <= 0) return src;
        int[] px = new int[w * h];
        try {
            src.getPixels(px, 0, w, 0, 0, w, h);
        } catch (RuntimeException rte) {
            return src;
        }

        // ── PASS 1: light denoise / deblock (3×3 box blend, source-preserving) ──
        if (denoise > 0.01f) {
            int[] dn = new int[px.length];
            for (int y = 0; y < h; y++) {
                int row = y * w, up = (y > 0 ? y - 1 : 0) * w, dnw = (y < h - 1 ? y + 1 : y) * w;
                for (int x = 0; x < w; x++) {
                    int c = px[row + x];
                    int xl = px[row + Math.max(0, x - 1)], xr = px[row + Math.min(w - 1, x + 1)];
                    int pu = px[up + x], pd = px[dnw + x];
                    int br = (((c >>> 16) & 255) + ((xl >>> 16) & 255) + ((xr >>> 16) & 255)
                            + ((pu >>> 16) & 255) + ((pd >>> 16) & 255)
                            + ((xl >>> 16) & 255) + ((xr >>> 16) & 255)
                            + ((pu >>> 16) & 255) + ((pd >>> 16) & 255)) / 9;
                    int bg = (((c >>> 8) & 255) + ((xl >>> 8) & 255) + ((xr >>> 8) & 255)
                            + ((pu >>> 8) & 255) + ((pd >>> 8) & 255)
                            + ((xl >>> 8) & 255) + ((xr >>> 8) & 255)
                            + ((pu >>> 8) & 255) + ((pd >>> 8) & 255)) / 9;
                    int bb = ((c & 255) + (xl & 255) + (xr & 255) + (pu & 255) + (pd & 255)
                            + (xl & 255) + (xr & 255) + (pu & 255) + (pd & 255)) / 9;
                    int r = Math.round(((c >>> 16) & 255) * (1f - denoise) + br * denoise);
                    int g = Math.round(((c >>> 8) & 255) * (1f - denoise) + bg * denoise);
                    int b = Math.round((c & 255) * (1f - denoise) + bb * denoise);
                    dn[row + x] = 0xff000000 | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
                }
            }
            px = dn;
        }

        // ── PASS 2: unsharp sharpen (on DENOISED pixels — zoom-scaled strength) ──
        if (sharp > 0.01f) {
            int[] sp = new int[px.length];
            for (int y = 0; y < h; y++) {
                int row = y * w, up = (y > 0 ? y - 1 : 0) * w, dnw = (y < h - 1 ? y + 1 : y) * w;
                for (int x = 0; x < w; x++) {
                    int c = px[row + x];
                    int xl = px[row + Math.max(0, x - 1)], xr = px[row + Math.min(w - 1, x + 1)];
                    int pu = px[up + x], pd = px[dnw + x];
                    int sr = (((xl >>> 16) & 255) + ((xr >>> 16) & 255) + ((pu >>> 16) & 255) + ((pd >>> 16) & 255)) >> 2;
                    int sg = (((xl >>> 8) & 255) + ((xr >>> 8) & 255) + ((pu >>> 8) & 255) + ((pd >>> 8) & 255)) >> 2;
                    int sb = ((xl & 255) + (xr & 255) + (pu & 255) + (pd & 255)) >> 2;
                    int cr = (c >>> 16) & 255, cg = (c >>> 8) & 255, cb = c & 255;
                    int r = cr + Math.round(sharp * (cr - sr));
                    int g = cg + Math.round(sharp * (cg - sg));
                    int b = cb + Math.round(sharp * (cb - sb));
                    sp[row + x] = 0xff000000 | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
                }
            }
            px = sp;
        }

        // ── PASS 3: colour adjustments + vignette ──
        int cx = w / 2, cy = h / 2;
        float maxR = (float) Math.sqrt((float) cx * cx + (float) cy * cy);
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int c = px[row + x];
                int r = (c >>> 16) & 255, g = (c >>> 8) & 255, b = c & 255;
                r += bright * 255f; g += bright * 255f; b += bright * 255f;
                r = Math.round((r - 128) * contrast + 128);
                g = Math.round((g - 128) * contrast + 128);
                b = Math.round((b - 128) * contrast + 128);
                float lum = 0.299f * r + 0.587f * g + 0.114f * b;
                r = Math.round(lum + (r - lum) * sat);
                g = Math.round(lum + (g - lum) * sat);
                b = Math.round(lum + (b - lum) * sat);
                r += warm * 20f; b -= warm * 20f;
                float l01 = lum / 255f;
                float sMask = 1f - smooth(0f, 0.45f, l01);
                float hMask = smooth(0.55f, 1f, l01);
                r += shadow * sMask * 26f; g += shadow * sMask * 26f; b += shadow * sMask * 26f;
                r -= high * hMask * 26f; g -= high * hMask * 26f; b -= high * hMask * 26f;
                float dx = x - cx, dy = y - cy;
                float v = vig * smooth(0.20f, 0.80f, (float) Math.sqrt(dx * dx + dy * dy) / maxR);
                r -= v * 120f; g -= v * 120f; b -= v * 120f;
                px[row + x] = 0xff000000 | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
            }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    /** v2.4.6: sharpen strength shrinks as zoom grows (less ringing on big crops). */
    private static float sharpenScale(float zoom) {
        return Math.max(0.85f, Math.min(1.25f, 1.0f + 0.04f * Math.max(0f, zoom - 1f)));
    }

    private static float smooth(float a, float b, float v) {
        float t = Math.max(0f, Math.min(1f, (v - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }

    private static int clamp255(int v) { return v < 0 ? 0 : Math.min(255, v); }
}
