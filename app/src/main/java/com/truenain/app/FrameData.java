package com.truenain.app;

import android.graphics.Bitmap;

/**
 * Immutable cache entry for one decoded video frame.
 *
 * Once wrapped here the bitmap is owned by the FrameCacheManager — callers must
 * never recycle it.  Evicted bitmaps return to the shared BitmapPool; the entry
 * for the currently displayed frame is pinned and simply dropped (the UI layer
 * returns it to the pool when the next frame replaces it on screen).
 */
final class FrameData {
    final long sourceId;
    final Bitmap bitmap;
    final long ptsUs;
    final int frameIndex;
    /**
     * Orientation state the entry was decoded under.  Decoded pixels are raw
     * (metadata rotation is applied at render/save time), so this records the
     * video's metadata rotation; manual picture rotation is deliberately excluded
     * because it is a pure render-time transform and must not duplicate entries.
     */
    final int rotationState;

    FrameData(long sourceId, Bitmap bitmap, long ptsUs, int frameIndex, int rotationState) {
        this.sourceId = sourceId;
        this.bitmap = bitmap;
        this.ptsUs = ptsUs;
        this.frameIndex = frameIndex;
        this.rotationState = ((rotationState % 360) + 360) % 360;
    }

    int byteCount() {
        return bitmap != null && !bitmap.isRecycled() ? bitmap.getAllocationByteCount() : 0;
    }

    boolean isValid() {
        return bitmap != null && !bitmap.isRecycled();
    }
}
