package com.truenain.app;

import android.graphics.Bitmap;

/** A heuristic for comparing sharpness of SOURCE frames, not enhancement or OCR. */
final class SharpFrameScorer {
    private SharpFrameScorer() {}

    static double score(Bitmap original) {
        if (original == null || original.isRecycled()) return -1;
        int w = original.getWidth(), h = original.getHeight();
        if (w < 4 || h < 4) return -1;
        // Use the same small downsample bounds for all frames to limit memory/time.
        float factor = Math.min(1f, 192f / Math.max(w, h));
        int sw = Math.max(4, Math.round(w * factor));
        int sh = Math.max(4, Math.round(h * factor));
        Bitmap sample = (sw == w && sh == h) ? original :
                Bitmap.createScaledBitmap(original, sw, sh, true);
        try {
            int[] pixels = new int[sw * sh];
            sample.getPixels(pixels, 0, sw, 0, 0, sw, sh);
            long sum = 0L;
            int count = 0;
            for (int y = 1; y < sh - 1; y++) {
                for (int x = 1; x < sw - 1; x++) {
                    int index = y * sw + x;
                    int center = luminance(pixels[index]);
                    int lap = 4 * center - luminance(pixels[index - 1])
                            - luminance(pixels[index + 1])
                            - luminance(pixels[index - sw])
                            - luminance(pixels[index + sw]);
                    sum += (long) lap * lap;
                    count++;
                }
            }
            return count == 0 ? -1 : (double) sum / count;
        } finally {
            if (sample != original && !sample.isRecycled()) sample.recycle();
        }
    }

    private static int luminance(int color) {
        return (77 * ((color >>> 16) & 255)
                + 150 * ((color >>> 8) & 255) + 29 * (color & 255)) >>> 8;
    }
}
