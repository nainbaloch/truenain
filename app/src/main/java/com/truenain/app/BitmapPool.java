package com.truenain.app;

import android.graphics.Bitmap;

import java.util.ArrayDeque;

/**
 * Process-wide Bitmap pool shared by the frame decoder, the frame cache and the UI
 * layer.  Reusing decoded ARGB_8888 bitmaps avoids per-frame allocations and the
 * GC churn that used to accompany 1080p/4K scrubbing.
 *
 * Safety rules:
 *  - A bitmap must only be released when no view still displays it.  Callers that
 *    hand bitmaps to FrameCacheManager transfer ownership; the manager decides when
 *    a bitmap may return here.
 *  - A bitmap may be pooled at most once; double release is detected and ignored.
 *  - The pool is bounded by total bytes, so large 4K frames cannot accumulate.
 */
final class BitmapPool {
    private static final int MAX_POOL_BYTES = 96 * 1024 * 1024;   // bound by bytes
    private static final int MAX_POOL_COUNT = 16;
    private static final Object LOCK = new Object();
    private static final ArrayDeque<Bitmap> POOL = new ArrayDeque<>();
    private static long pooledBytes = 0L;

    private BitmapPool() {}

    /** Returns a recycled ARGB_8888 bitmap of the requested size, or a fresh one. */
    static Bitmap obtain(int width, int height) {
        synchronized (LOCK) {
            Bitmap bmp;
            while ((bmp = POOL.pollFirst()) != null) {
                pooledBytes -= bmp.getAllocationByteCount();
                if (!bmp.isRecycled()
                        && bmp.getWidth() == width
                        && bmp.getHeight() == height
                        && bmp.getConfig() == Bitmap.Config.ARGB_8888) {
                    return bmp;
                }
                if (!bmp.isRecycled()) bmp.recycle();
            }
        }
        try {
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError oom) {
            clear();
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
    }

    /** Releases a bitmap back to the pool, or recycles it when the pool is full. */
    static void release(Bitmap bmp) {
        if (bmp == null || bmp.isRecycled()) return;
        synchronized (LOCK) {
            if (POOL.contains(bmp)) return;                       // double-release guard
            long bytes = bmp.getAllocationByteCount();
            if (pooledBytes + bytes > MAX_POOL_BYTES || POOL.size() >= MAX_POOL_COUNT) {
                bmp.recycle();
                return;
            }
            pooledBytes += bytes;
            POOL.offerFirst(bmp);
        }
    }

    /** Drops every pooled bitmap; used under memory pressure and on shutdown. */
    static void clear() {
        synchronized (LOCK) {
            Bitmap bmp;
            while ((bmp = POOL.pollFirst()) != null) {
                if (!bmp.isRecycled()) bmp.recycle();
            }
            pooledBytes = 0L;
        }
    }
}
