package com.truenain.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable source metadata plus exact MediaCodec-backed source-frame access.
 *
 * V2.0 timeline model: {@link #open(Context, Uri, OnFirstFrameReady)} returns a
 * usable source quickly using a keyframe-only index (instant first preview), then
 * upgrades itself in the background to the FULL presentation timeline — every
 * decoded frame's PTS, CFR and VFR alike, sorted in presentation order — via
 * {@link #installFullTimeline(long[])}.  Frame Mode, Slow Motion and Frame Export
 * therefore all operate on the complete frame count, not just keyframes.
 */
final class FrameSource implements AutoCloseable {
    // ── timeline state (volatile: upgraded in place by the background indexer) ──
    // Kept as public volatile fields for drop-in compatibility with existing call
    // sites (MainActivity/TrimActivity read .ptsUs / .frameCount / .fps directly).
    public volatile long[] ptsUs;
    public volatile int frameCount;
    public volatile long durationUs;
    public volatile double fps;
    private volatile ExactFrameDecoder decoder;
    private volatile boolean fullTimelineLoaded;

    final int width;
    final int height;
    final int rotation;
    final long[] keyframePtsUs;
    final int[] keyframeIndices;

    private final Context appContext;
    private final Uri sourceUri;
    private final int videoTrackIndex;
    private final MediaFormat videoFormat;

    private final Object timelineLock = new Object();
    private final Object callbackLock = new Object();
    private Runnable fullTimelineCallback;
    private volatile boolean released;

    /**
     * V2.0.6 stuck-fix: ALL real-index walks run on ONE serialized background
     * thread.  Previously every opened source (main + prefetch + slow-motion)
     * spawned its own full-file walker — on a phone 2-3 concurrent walkers starved
     * I/O and mid-video seeks (e.g. jump to 5 min of a 20 min video) crawled.
     * Jobs are also delayed (UI settles first), cancellable via {@link #released},
     * and abort mid-walk every 512 samples.
     */
    private static final java.util.concurrent.ExecutorService INDEXER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "tn-pts-index");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY + 1);
                return t;
            });

    private FrameSource(Context appContext, Uri uri, int videoTrackIndex, MediaFormat videoFormat,
                        long[] initialPts, long[] keyframePts, int[] keyframeIndices,
                        long durationUs, double fps, int width, int height,
                        int rotation, ExactFrameDecoder decoder) {
        this.appContext = appContext.getApplicationContext();
        this.sourceUri = uri;
        this.videoTrackIndex = videoTrackIndex;
        this.videoFormat = videoFormat;
        this.ptsUs = initialPts;
        this.keyframePtsUs = keyframePts;
        this.keyframeIndices = keyframeIndices;
        this.frameCount = initialPts.length;
        this.durationUs = durationUs;
        this.fps = fps;
        this.width = width;
        this.height = height;
        this.rotation = ((rotation % 360) + 360) % 360;
        this.decoder = decoder;
    }

    /**
     * Opens video and builds minimal metadata quickly.  A keyframe-only index gives
     * an instant first preview; the full frame timeline is indexed in the background
     * and swapped in when ready (see {@link #setOnFullTimelineReady(Runnable)}).
     */
    static FrameSource open(Context context, Uri uri) throws IOException {
        return open(context, uri, null);
    }

    static FrameSource open(Context context, Uri uri, OnFirstFrameReady listener) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(context, uri, null);
            int videoTrack = -1;
            MediaFormat vf = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = i;
                    vf = f;
                    break;
                }
            }
            if (videoTrack < 0 || vf == null) throw new IOException("No video track");

            int width = vf.containsKey(MediaFormat.KEY_WIDTH) ? vf.getInteger(MediaFormat.KEY_WIDTH) : 0;
            int height = vf.containsKey(MediaFormat.KEY_HEIGHT) ? vf.getInteger(MediaFormat.KEY_HEIGHT) : 0;
            int rotation = vf.containsKey(MediaFormat.KEY_ROTATION) ? vf.getInteger(MediaFormat.KEY_ROTATION) : 0;
            long durationUs = vf.containsKey(MediaFormat.KEY_DURATION) ? vf.getLong(MediaFormat.KEY_DURATION) : 0L;
            double declaredFps = vf.containsKey(MediaFormat.KEY_FRAME_RATE) ? vf.getInteger(MediaFormat.KEY_FRAME_RATE) : 0.0;

            // V2.0.5 perf: NO sample pass on the open path.  Stepping through every
            // MediaExtractor sample made 4K/long videos take minutes to load.  We now
            // build an instant VIRTUAL timeline (duration × fps) and decode by exact
            // time (ceiling match) — the REAL PTS index is still built in the
            // background and swapped in when ready.
            ex.selectTrack(videoTrack);

            double fps = declaredFps > 0 ? declaredFps : 30.0;
            long[] virtualPts;
            int frameCountEst;
            if (durationUs > 0) {
                frameCountEst = (int) Math.max(1, Math.round(durationUs * fps / 1_000_000.0));
                virtualPts = new long[frameCountEst];
                for (int i = 0; i < frameCountEst; i++) {
                    virtualPts[i] = (long) (i * 1_000_000.0 / fps);
                }
            } else {
                // Unknown duration (rare): fall back to a real sample pass.
                List<Long> times = new ArrayList<>();
                while (true) {
                    long t = ex.getSampleTime();
                    if (t < 0) break;
                    times.add(t);
                    if (!ex.advance()) break;
                }
                if (times.isEmpty()) throw new IOException("Video has no decodable samples");
                Collections.sort(times);
                virtualPts = new long[times.size()];
                for (int i = 0; i < virtualPts.length; i++) virtualPts[i] = times.get(i);
                durationUs = virtualPts[virtualPts.length - 1];
                frameCountEst = virtualPts.length;
                fps = declaredFps > 0 ? declaredFps
                        : (frameCountEst > 1 ? (frameCountEst - 1) * 1_000_000.0
                                / Math.max(1L, durationUs) : 30.0);
            }

            double fpsOut = fps;
            long durationOut = durationUs > 0 ? durationUs : virtualPts[virtualPts.length - 1];

            ExactFrameDecoder decoder = new ExactFrameDecoder(
                    context, uri, videoTrack, vf, virtualPts, width, height);

            FrameSource source = new FrameSource(context, uri, videoTrack, vf,
                    virtualPts, new long[0], new int[0],
                    durationOut, fpsOut, width, height, rotation, decoder);

            if (listener != null) listener.onFirstFrameReady(source);

            // Real presentation-PTS index (CFR/VFR/B-frame exact) still built in the
            // background; swapped in via installFullTimeline when ready.
            startRealIndexing(source, durationUs, declaredFps);

            return source;
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage(), e);
        } finally {
            try { ex.release(); } catch (RuntimeException ignored) {}
        }
    }

    /** Background: walks every sample once and swaps in the REAL presentation PTS. */
    private static void startRealIndexing(FrameSource source, long durationUs, double declaredFps) {
        INDEXER.execute(() -> {
            MediaExtractor ex = new MediaExtractor();
            try {
                // Let the UI (first frames, seek bar) settle before heavy I/O begins.
                Thread.sleep(4000);
                if (source.released) return;
                ex.setDataSource(source.appContext, source.sourceUri, null);
                ex.selectTrack(source.videoTrackIndex);
                ArrayList<Long> times = new ArrayList<>();
                int sinceCheck = 0;
                while (true) {
                    long t = ex.getSampleTime();
                    if (t < 0) break;
                    times.add(t);
                    if (!ex.advance()) break;
                    if (++sinceCheck >= 512) {           // abort promptly on video switch
                        sinceCheck = 0;
                        if (source.released) return;
                    }
                }
                if (times.isEmpty() || source.released) return;
                Collections.sort(times);
                long[] full = new long[times.size()];
                for (int i = 0; i < full.length; i++) full[i] = times.get(i);
                source.installFullTimeline(full, durationUs, declaredFps);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException | IOException | OutOfMemoryError ignored) {
                // keep the virtual timeline rather than failing the whole source
            } finally {
                try { ex.release(); } catch (RuntimeException ignored) {}
            }
        });
    }

    /** Swaps the source onto the complete presentation timeline (CFR + VFR + B-frames). */
    private void installFullTimeline(long[] sortedPts, long declaredDurationUs, double declaredFps) {
        if (sortedPts == null || sortedPts.length == 0) return;
        synchronized (timelineLock) {
            if (fullTimelineLoaded) return;
            long newDuration = declaredDurationUs;
            if (newDuration <= 0) newDuration = sortedPts[sortedPts.length - 1];
            double newFps = declaredFps;
            if (newFps <= 0 && sortedPts.length > 1) {
                long span = Math.max(1L, sortedPts[sortedPts.length - 1] - sortedPts[0]);
                newFps = (sortedPts.length - 1) * 1_000_000.0 / span;
            }
            // V2.0.5 perf: swap the timeline IN-PLACE — no second MediaCodec pipeline.
            // Presentation order is stable for duplicate PTS: sort is stable, so equal
            // timestamps keep their decode order deterministically.
            ExactFrameDecoder d = decoder;
            if (d == null) return;
            d.swapTimeline(sortedPts);
            ptsUs = sortedPts;
            frameCount = sortedPts.length;
            durationUs = newDuration;
            fps = newFps;
            fullTimelineLoaded = true;
        }
        Runnable cb;
        synchronized (callbackLock) {
            cb = fullTimelineCallback;
            fullTimelineCallback = null;
        }
        if (cb != null) cb.run();
    }

    /**
     * V2.0.4 perf: builds an export source from an ALREADY-INDEXED timeline
     * (skips the whole sample-scan pass — on long videos that scan is the main
     * "extract shuru hone mein der" cost).  Header is still validated.
     */
    static FrameSource openSharedTimeline(Context context, Uri uri, long[] sharedPts) throws IOException {
        if (sharedPts == null || sharedPts.length == 0) throw new IOException("No shared timeline");
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(context, uri, null);
            int videoTrack = -1;
            MediaFormat vf = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) { videoTrack = i; vf = f; break; }
            }
            if (videoTrack < 0 || vf == null) throw new IOException("No video track");

            int width = vf.containsKey(MediaFormat.KEY_WIDTH) ? vf.getInteger(MediaFormat.KEY_WIDTH) : 0;
            int height = vf.containsKey(MediaFormat.KEY_HEIGHT) ? vf.getInteger(MediaFormat.KEY_HEIGHT) : 0;
            int rotation = vf.containsKey(MediaFormat.KEY_ROTATION) ? vf.getInteger(MediaFormat.KEY_ROTATION) : 0;
            long durationUs = vf.containsKey(MediaFormat.KEY_DURATION) ? vf.getLong(MediaFormat.KEY_DURATION) : 0L;
            double declaredFps = vf.containsKey(MediaFormat.KEY_FRAME_RATE) ? vf.getInteger(MediaFormat.KEY_FRAME_RATE) : 0.0;

            long[] pts = new long[sharedPts.length];
            System.arraycopy(sharedPts, 0, pts, 0, sharedPts.length);
            if (durationUs <= 0) durationUs = pts[pts.length - 1];
            double fps = declaredFps;
            if (fps <= 0 && pts.length > 1) {
                long span = Math.max(1L, pts[pts.length - 1] - pts[0]);
                fps = (pts.length - 1) * 1_000_000.0 / span;
            }

            ExactFrameDecoder decoder = new ExactFrameDecoder(
                    context, uri, videoTrack, vf, pts, width, height);
            FrameSource source = new FrameSource(context, uri, videoTrack, vf,
                    pts, pts, null, durationUs, fps, width, height, rotation, decoder);
            source.fullTimelineLoaded = true;
            return source;
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage(), e);
        } finally {
            try { ex.release(); } catch (RuntimeException ignored) {}
        }
    }

    // For backward compatibility - loads full PTS synchronously (used by exports).
    static FrameSource openFull(Context context, Uri uri) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(context, uri, null);
            int videoTrack = -1;
            MediaFormat vf = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = i;
                    vf = f;
                    break;
                }
            }
            if (videoTrack < 0 || vf == null) throw new IOException("No video track");

            int width = vf.containsKey(MediaFormat.KEY_WIDTH) ? vf.getInteger(MediaFormat.KEY_WIDTH) : 0;
            int height = vf.containsKey(MediaFormat.KEY_HEIGHT) ? vf.getInteger(MediaFormat.KEY_HEIGHT) : 0;
            int rotation = vf.containsKey(MediaFormat.KEY_ROTATION) ? vf.getInteger(MediaFormat.KEY_ROTATION) : 0;
            long durationUs = vf.containsKey(MediaFormat.KEY_DURATION) ? vf.getLong(MediaFormat.KEY_DURATION) : 0L;
            double declaredFps = vf.containsKey(MediaFormat.KEY_FRAME_RATE) ? vf.getInteger(MediaFormat.KEY_FRAME_RATE) : 0.0;

            ex.selectTrack(videoTrack);
            ArrayList<Long> times = new ArrayList<>();
            while (true) {
                long t = ex.getSampleTime();
                if (t < 0) break;
                times.add(t);
                if (!ex.advance()) break;
            }
            if (times.isEmpty()) throw new IOException("Video has no decodable samples");
            Collections.sort(times);
            long[] pts = new long[times.size()];
            for (int i = 0; i < pts.length; i++) pts[i] = times.get(i);
            if (durationUs <= 0 && pts.length > 1) durationUs = pts[pts.length - 1];

            double fps = declaredFps;
            if (fps <= 0 && pts.length > 1) {
                long span = Math.max(1L, pts[pts.length - 1] - pts[0]);
                fps = (pts.length - 1) * 1_000_000.0 / span;
            }

            ExactFrameDecoder decoder = new ExactFrameDecoder(
                    context, uri, videoTrack, vf, pts, width, height);
            FrameSource source = new FrameSource(context, uri, videoTrack, vf,
                    pts, pts, null, durationUs, fps, width, height, rotation, decoder);
            source.fullTimelineLoaded = true;
            return source;
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage(), e);
        } finally {
            try { ex.release(); } catch (RuntimeException ignored) {}
        }
    }

    /** Callback interface for when minimal metadata is ready for immediate preview. */
    interface OnFirstFrameReady {
        void onFirstFrameReady(FrameSource source);
    }

    /**
     * Registers a callback for the full-timeline upgrade.  If the upgrade already
     * happened, the callback runs immediately on the caller's thread.
     */
    void setOnFullTimelineReady(Runnable r) {
        if (r == null) return;
        boolean runNow;
        synchronized (callbackLock) {
            runNow = fullTimelineLoaded;
            if (!runNow) fullTimelineCallback = r;
        }
        if (runNow) r.run();
    }

    boolean hasFullTimeline() { return fullTimelineLoaded; }

    Bitmap getFrame(int index) {
        ExactFrameDecoder d = decoder;
        return d != null ? d.getFrame(index) : null;
    }

    /**
     * Decodes, applies {@code rotationDegrees} (shared preview/export orientation
     * policy), compresses to the stream and returns success.  The decoded bitmap
     * always returns to the shared pool.
     */
    boolean writeFrame(int index, OutputStream output, boolean png, int rotationDegrees) throws IOException {
        ExactFrameDecoder d = decoder;
        if (d == null) return false;
        Bitmap bmp = d.getFrame(index);
        if (bmp == null) return false;
        Bitmap out = bmp;
        int rot = ((rotationDegrees % 360) + 360) % 360;
        if (rot != 0) {
            try {
                Matrix m = new Matrix();
                m.postRotate(rot);
                Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0,
                        bmp.getWidth(), bmp.getHeight(), m, true);
                if (rotated != null) out = rotated;
            } catch (OutOfMemoryError ignored) {
                out = bmp;                                     // export unrotated rather than fail
            }
        }
        boolean ok;
        try {
            ok = out.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG,
                    png ? 100 : 94, output);
        } finally {
            if (out != bmp) out.recycle();
            BitmapPool.release(bmp);
        }
        return ok;
    }

    boolean writeFrame(int index, OutputStream output, boolean png) throws IOException {
        return writeFrame(index, output, png, 0);
    }

    long[] pts() { return ptsUs; }

    boolean sourceIsLandscape() {
        int rw = width, rh = height;
        if (rotation == 90 || rotation == 270) {
            int x = rw; rw = rh; rh = x;
        }
        return rw > rh;
    }

    @Override public void close() {
        released = true;                           // cancels queued/running index walks
        synchronized (timelineLock) {
            ExactFrameDecoder d = decoder;
            decoder = null;
            if (d != null) {
                try { d.close(); } catch (RuntimeException ignored) {}
            }
        }
    }
}
