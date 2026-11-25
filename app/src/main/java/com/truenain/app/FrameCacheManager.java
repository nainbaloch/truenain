package com.truenain.app;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Trace;
import android.util.LruCache;

import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * V2.0 frame cache: a bounded LRU of decoded frames with a smart window around the
 * current position, background prefetching, request versioning for stale-decode
 * prevention, and bitmap-pool backed eviction.
 *
 * Ownership model
 * ---------------
 * Bitmaps wrapped in FrameData belong to this manager.  Evicted entries return to
 * the shared BitmapPool, except the currently displayed frame, which is pinned and
 * handed back to the pool by the UI layer once it is no longer on screen.
 * UI code must call {@link #release(Bitmap)} instead of recycling any bitmap that
 * may be cache-resident.
 *
 * Threading
 * ---------
 * - UI thread: cache lookups and bitmap swaps only — never decodes.
 * - Display decodes: the activity's decoder executor (unchanged pipeline).
 * - Frame-mode prefetch: this manager's own single-thread executor backed by a
 *   dedicated FrameSource/MediaCodec instance, so prefetch never contends with
 *   seek decoding on the same codec.
 * - Slow-motion ahead-buffer: runnables submitted to the caller's executor, so
 *   playback decode order is preserved without cross-codec contention.
 */
final class FrameCacheManager {
    private static final int DEFAULT_WINDOW = 50;               // spec default: ±50 frames
    private static final int MIN_WINDOW = 16;
    private static final long MIN_BUDGET_BYTES = 24L * 1024 * 1024;
    private static final long MAX_BUDGET_BYTES = 128L * 1024 * 1024;

    private final LruCache<String, FrameData> cache;
    private final HashMap<Bitmap, FrameData> owned = new HashMap<>();
    private final Object ownedLock = new Object();
    private final long memoryBudgetBytes;
    private final int window;

    /** Monotonic seek-request version for stale-decode prevention. */
    private final AtomicLong requestVersion = new AtomicLong(0);
    /** Monotonic generation; bumped on invalidation so in-flight prefetch dies quietly. */
    private final AtomicLong generation = new AtomicLong(0);

    private final ExecutorService prefetchExecutor;
    private final Object prefetchSourceLock = new Object();
    private final Object pendingLock = new Object();
    private final HashMap<Integer, Boolean> pendingPrefetch = new HashMap<>();  // coalescing
    private FrameSource prefetchSource;
    private boolean prefetchBroken;

    private volatile long avgFrameBytes = 0L;   // EMA of decoded frame sizes (P1-04)
    private final long createdElapsedMs;        // prefetch start gate (video-open feels instant)
    private static final long PREFETCH_START_DELAY_MS = 1500;

    /** v2.4.6 PHASE 3 (audit fix 3): REAL source frame size se prefetch budget seed karo —
     *  pehle 1080p assumption (8.3MB) thi, 4K (~33MB) par budget thrash/crash karta tha. */
    void primeFrameSize(int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (avgFrameBytes <= 0) avgFrameBytes = (long) w * h * 4L;
    }

    /** v2.4.6 PHASE 3 (audit fix 2): memory pressure par poora cache khaali
     *  (displayed pin respected — entryRemoved on-screen bitmap pool nahi karta). */
    void trimAll() {
        cache.evictAll();
    }

    private volatile long displayedFrameIndex = -1;
    private volatile Bitmap displayedBitmap;

    private final Context applicationContext;

    FrameCacheManager(Context context) {
        this.applicationContext = context.getApplicationContext();
        this.createdElapsedMs = android.os.SystemClock.elapsedRealtime();
        ActivityManager am =
                (ActivityManager) context.getApplicationContext()
                        .getSystemService(Context.ACTIVITY_SERVICE);
        int memoryClassMb = am != null ? am.getMemoryClass() : 48;
        memoryBudgetBytes = Math.max(MIN_BUDGET_BYTES,
                Math.min(MAX_BUDGET_BYTES, (memoryClassMb / 2L) * 1024L * 1024L));

        // Scale the window with the memory budget. 4K frames are ~33 MB each, so the
        // byte budget — not the frame count — is the real constraint; the LruCache
        // byte accounting handles eviction either way.
        long perFrameBytes = 1920L * 1080L * 4L;                 // 1080p assumption for scaling
        if (memoryBudgetBytes >= 64L * 1024 * 1024) window = DEFAULT_WINDOW;
        else if (memoryBudgetBytes >= 40L * 1024 * 1024) window = 32;
        else window = Math.max(MIN_WINDOW,
                (int) Math.min(DEFAULT_WINDOW, memoryBudgetBytes / perFrameBytes));

        cache = new LruCache<String, FrameData>((int) Math.min(Integer.MAX_VALUE, memoryBudgetBytes)) {
            @Override protected int sizeOf(String key, FrameData fd) {
                return Math.max(1, fd.byteCount());
            }

            @Override protected void entryRemoved(boolean evicted, String key,
                                                  FrameData oldValue, FrameData newValue) {
                if (oldValue == null || oldValue == newValue) return;
                synchronized (ownedLock) { owned.remove(oldValue.bitmap); }
                Bitmap shown = displayedBitmap;
                if (oldValue.bitmap != shown) {
                    BitmapPool.release(oldValue.bitmap);          // safe: not displayed
                }
                // Displayed bitmap: intentionally left alive; the UI returns it to the
                // pool via release() once the next frame replaces it on screen.
            }
        };

        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "tn-prefetch");
            t.setPriority(Thread.MIN_PRIORITY + 1);
            t.setDaemon(true);
            return t;
        };
        prefetchExecutor = Executors.newSingleThreadExecutor(factory);
    }

    // ─────────────────────────────────── request versioning (stale-decode guard)

    /** Bumps and returns the latest seek-request version. */
    long beginRequest() {
        return requestVersion.incrementAndGet();
    }

    /** True when {@code version} is still the most recent request. */
    boolean isCurrent(long version) {
        return requestVersion.get() == version;
    }

    // ─────────────────────────────────── cache access

    private static String key(long sourceId, int frameIndex, int rotationState) {
        return sourceId + "|" + frameIndex + "|" + rotationState;
    }

    /** Synchronous lookup — safe on the UI thread (no decode happens here). */
    FrameData get(long sourceId, int frameIndex, int rotationState) {
        return cache.get(key(sourceId, frameIndex, rotationState));
    }

    /** Stores a decoded frame. Ownership of the bitmap transfers to the manager. */
    void put(FrameData fd) {
        if (fd == null || !fd.isValid()) return;
        int bytes = fd.byteCount();
        if (bytes > 0) {
            long avg = avgFrameBytes;
            avgFrameBytes = avg == 0 ? bytes : (avg * 7 + bytes) / 8;   // P1-04 budget input
        }
        synchronized (ownedLock) { owned.put(fd.bitmap, fd); }
        cache.put(key(fd.sourceId, fd.frameIndex, fd.rotationState), fd);
    }

    /** Marks the frame currently shown on screen; its bitmap stays pinned. */
    void markDisplayed(long sourceId, int frameIndex, int rotationState) {
        FrameData fd = cache.get(key(sourceId, frameIndex, rotationState));
        if (fd != null && fd.isValid()) {
            displayedFrameIndex = frameIndex;
            displayedBitmap = fd.bitmap;
        }
    }

    /**
     * UI-layer release for a bitmap that might be cache-owned.  Cached bitmaps are
     * left untouched (the LRU evicts them later); anything else goes to the pool.
     */
    void release(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        if (bitmap == displayedBitmap) return;                    // still on screen
        synchronized (ownedLock) {
            if (owned.containsKey(bitmap)) return;                // cache owns it
        }
        BitmapPool.release(bitmap);
    }

    // ─────────────────────────────────── frame-mode prefetch

    /** Frames closer than this to the display position are never staleness-cancelled. */
    private static final int NEAR_ZONE = 8;

    /**
     * Prefetches a smart window around {@code centerIndex}.  ±window is a LOGICAL
     * candidate window (P1-04): submissions are capped by the memory budget using
     * the measured average frame size, ordered by seek direction, and coalesced so
     * repeated seeks do not accumulate obsolete work.  Uses the dedicated prefetch
     * decoder, so seeks on the main pipeline are never blocked.
     */
    void prefetchAround(long sourceId, Uri videoUri, int centerIndex, int frameCount,
                        int rotationState, boolean forwardFirst) {
        if (prefetchBroken) return;
        final long gen = generation.get();
        final long rv = requestVersion.get();
        int forward = Math.min(window, Math.max(0, frameCount - 1 - centerIndex));
        int backward = Math.min(window, centerIndex);

        // P1-04: cap live prefetch by the byte budget with the measured frame size.
        long perFrame = avgFrameBytes > 0 ? avgFrameBytes : 1920L * 1080L * 4L;
        int budgetFrames = (int) Math.max(2L, memoryBudgetBytes / Math.max(1L, perFrame));
        int total = Math.min(forward + backward, budgetFrames);
        if (total <= 0) return;
        int forwardShare = Math.min(forward, (total * 6) / 10);   // 60/40 split
        int backwardShare = Math.min(backward, total - forwardShare);

        Runnable submit = () -> {
            if (forwardFirst) {
                for (int i = 1; i <= forwardShare; i++) submitPrefetch(sourceId, videoUri, centerIndex + i, rotationState, gen, rv, i);
                for (int i = 1; i <= backwardShare; i++) submitPrefetch(sourceId, videoUri, centerIndex - i, rotationState, gen, rv, i);
            } else {
                for (int i = 1; i <= backwardShare; i++) submitPrefetch(sourceId, videoUri, centerIndex - i, rotationState, gen, rv, i);
                for (int i = 1; i <= forwardShare; i++) submitPrefetch(sourceId, videoUri, centerIndex + i, rotationState, gen, rv, i);
            }
        };
        submit.run();
    }

    private void submitPrefetch(long sourceId, Uri videoUri, int index, int rotationState,
                                long gen, long rvAtSubmit, int distance) {
        if (index < 0) return;
        Integer boxed = index;
        synchronized (pendingLock) {
            if (pendingPrefetch.containsKey(boxed)) return;       // P1-01 coalescing
            pendingPrefetch.put(boxed, Boolean.TRUE);
        }
        String k = key(sourceId, index, rotationState);
        if (cache.get(k) != null) {
            synchronized (pendingLock) { pendingPrefetch.remove(boxed); }
            return;                                               // already cached
        }
        prefetchExecutor.execute(() -> {
            try {
                if (gen != generation.get() || prefetchBroken) return;
                // V2.0.4 perf: don't compete with video-open — the dedicated prefetch
                // decoder (another MediaCodec + sample scan) waits for the UI to settle.
                long waitMs = createdElapsedMs + PREFETCH_START_DELAY_MS
                        - android.os.SystemClock.elapsedRealtime();
                if (waitMs > 0) android.os.SystemClock.sleep(Math.min(waitMs, PREFETCH_START_DELAY_MS));
                if (gen != generation.get() || prefetchBroken) return;
                // P1-01: far-window work from an older seek self-cancels; near frames
                // (most valuable for scrubbing) always run.
                if (distance > NEAR_ZONE && requestVersion.get() != rvAtSubmit) return;
                FrameData existing = cache.get(k);
                if (existing != null && existing.isValid()) return;
                Trace.beginSection("TN:prefetch");
                try {
                    FrameSource src = ensurePrefetchSource(videoUri);
                    if (src == null || index >= src.frameCount) return;
                    Bitmap bmp = src.getFrame(index);
                    if (bmp != null && !bmp.isRecycled() && gen == generation.get()) {
                        put(new FrameData(sourceId, bmp, src.ptsUs[index], index, rotationState));
                    } else if (bmp != null && !bmp.isRecycled()) {
                        BitmapPool.release(bmp);                   // stale generation
                    }
                } finally {
                    Trace.endSection();
                }
            } catch (RuntimeException | OutOfMemoryError e) {
                prefetchBroken = true;                             // disable, never crash UI
            } finally {
                synchronized (pendingLock) { pendingPrefetch.remove(boxed); }
            }
        });
    }

    /** Opens (once per generation) a dedicated decoder used only for prefetching. */
    private FrameSource ensurePrefetchSource(Uri videoUri) {
        synchronized (prefetchSourceLock) {
            if (prefetchSource != null) return prefetchSource;
            if (prefetchBroken || videoUri == null) return null;
            try {
                prefetchSource = FrameSource.open(applicationContext, videoUri, null);
                return prefetchSource;
            } catch (Exception e) {
                prefetchBroken = true;
                return null;
            }
        }
    }

    // ─────────────────────────────────── slow-motion ahead-buffer

    /**
     * Submits decode-ahead work for slow-motion playback to the caller's executor
     * (the slow decoder thread), keeping upcoming frames cache-ready before the
     * playback tick reaches them.  Frames already cached are skipped cheaply.
     *
     * @param isStale supplier; ahead-work submitted for an older playback position
     *                self-cancels once the next tick advances the generation.
     */
    void prefetchAhead(long sourceId, FrameSource playbackSource, int fromIndex,
                       int count, ExecutorService on, BooleanSupplier isStale) {
        if (on == null || playbackSource == null || count <= 0) return;
        final int last = playbackSource.frameCount - 1;
        for (int i = fromIndex; i < fromIndex + count && i <= last; i++) {
            final int idx = i;
            final String k = key(sourceId, idx, 0);
            if (cache.get(k) != null) continue;
            on.execute(() -> {
                if (isStale.getAsBoolean()) return;
                FrameData existing = cache.get(k);
                if (existing != null && existing.isValid()) return;
                Trace.beginSection("TN:slowAhead");
                try {
                    Bitmap bmp = playbackSource.getFrame(idx);
                    if (bmp != null && !bmp.isRecycled()) {
                        put(new FrameData(sourceId, bmp, playbackSource.ptsUs[idx], idx, 0));
                    }
                } catch (RuntimeException | OutOfMemoryError ignored) {
                    // ahead-buffer failure must never break playback
                } finally {
                    Trace.endSection();
                }
            });
        }
    }

    // ─────────────────────────────────── invalidation / lifecycle

    /** Drops every cache entry for one source (e.g. when slow-motion stops). */
    void evictSource(long sourceId) {
        String prefix = sourceId + "|";
        for (String k : cache.snapshot().keySet()) {
            if (k.startsWith(prefix)) cache.remove(k);
        }
    }

    /** Shuts the manager down: cancels prefetch, closes the dedicated decoder. */
    void close() {
        generation.incrementAndGet();
        // Evict while the displayed pin is still set so the on-screen bitmap is NOT
        // pooled behind the caller's back (it may equal savingBitmap mid-save).
        // The caller disposes of that last bitmap itself.
        cache.evictAll();
        synchronized (ownedLock) { owned.clear(); }
        displayedFrameIndex = -1;
        displayedBitmap = null;
        synchronized (prefetchSourceLock) {
            FrameSource src = prefetchSource;
            prefetchSource = null;
            if (src != null) {
                prefetchExecutor.execute(src::close);
            }
        }
        prefetchExecutor.shutdown();
    }
}
