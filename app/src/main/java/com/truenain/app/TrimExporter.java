package com.truenain.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Non-destructive multi-range MP4 merger.
 *
 * Safe path: compressed stream-copy when every requested video boundary is a
 * decodable sync boundary. Exact fallback: MediaExtractor + MediaCodec video
 * decode/re-encode for ranges that begin/end inside a GOP. The source file is
 * never modified.
 */
final class TrimExporter {
    static final class Range {
        final long startMs, endMs;
        Range(long s, long e) { startMs = s; endMs = e; }
    }

    interface Callback {
        void onDone(Uri uri, boolean alignedToKeyframes);
        void onError(Exception e);
    }

    static void exportMerged(Context context, Uri source, List<Range> ranges, Callback cb) {
        new Thread(() -> {
            Uri out = null;
            try {
                validate(ranges);
                ContentResolver cr = context.getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.Video.Media.DISPLAY_NAME,
                        "TRUE_NAIN_Merged_" + System.currentTimeMillis() + ".mp4");
                v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                v.put(MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/TRUE NAIN");
                v.put(MediaStore.Video.Media.IS_PENDING, 1);
                out = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
                if (out == null) throw new IOException("Cannot create output video");

                boolean safeCopy = allRangesStreamCopySafe(context, source, ranges);
                try (android.os.ParcelFileDescriptor pfd = cr.openFileDescriptor(out, "rw")) {
                    if (pfd == null) throw new IOException("Cannot open output video");
                    if (safeCopy) {
                        muxExactSafeRanges(context, source, ranges, pfd.getFileDescriptor());
                    } else {
                        // Exact-boundary fallback: only the video is re-encoded; audio is
                        // clipped at its native compressed sample boundaries.
                        ExactTrimTranscoder.transcode(context, source, ranges, pfd.getFileDescriptor());
                    }
                }

                ContentValues done = new ContentValues();
                done.put(MediaStore.Video.Media.IS_PENDING, 0);
                cr.update(out, done, null, null);
                cb.onDone(out, false);
            } catch (Exception e) {
                if (out != null) {
                    try { context.getContentResolver().delete(out, null, null); }
                    catch (RuntimeException ignored) {}
                }
                cb.onError(e);
            }
        }, "TrueNainMerge").start();
    }

    private static void validate(List<Range> ranges) throws IOException {
        if (ranges == null || ranges.isEmpty()) throw new IOException("No trim ranges");
        for (Range r : ranges) {
            if (r == null || r.startMs < 0 || r.endMs <= r.startMs)
                throw new IOException("Invalid trim range");
        }
    }

    /** Stream-copy is safe only when each requested start/end is a video sync boundary (or EOS). */
    private static boolean allRangesStreamCopySafe(Context context, Uri source,
                                                   List<Range> ranges) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(context, source, null);
            int vt = findVideoTrack(ex);
            if (vt < 0) throw new IOException("No video track");
            MediaFormat vf = ex.getTrackFormat(vt);
            long durationUs = vf.containsKey(MediaFormat.KEY_DURATION) ? vf.getLong(MediaFormat.KEY_DURATION) : Long.MAX_VALUE;
            ex.selectTrack(vt);
            for (Range r : ranges) {
                long start = r.startMs * 1000L;
                long end = r.endMs * 1000L;
                if (!isExactSync(ex, start)) return false;
                if (end < durationUs - 1_000L && !isExactSync(ex, end)) return false;
            }
            return true;
        } finally {
            try { ex.release(); } catch (RuntimeException ignored) {}
        }
    }

    private static boolean isExactSync(MediaExtractor ex, long requestedUs) {
        ex.seekTo(requestedUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        long t = ex.getSampleTime();
        if (t < 0 || Math.abs(t - requestedUs) > 1_000L) return false;
        return (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
    }

    /**
     * Hardening spec S3.1: cumulative A/V timeline hardening for multi-range merge.
     * Every range anchors BOTH tracks to that range's first video sample
     * ({@code firstVideoPts}); audio samples before the anchor are skipped.  This
     * removes the cumulative drift the old per-track independent anchors produced
     * (video starts at its GOP sync frame while audio started immediately at the
     * cut — every cut shifted audio earlier relative to video).  Independent
     * monotonic counters per track keep {@code MediaMuxer.writeSampleData} happy on
     * strict OEM muxers, and the residual audio-over-video skew from previous cuts
     * (> 10 ms) is compensated by dropping leading audio of the next range.
     */
    private static void muxExactSafeRanges(Context context, Uri source, List<Range> ranges,
                                           FileDescriptor outFd) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mux = null;
        boolean started = false;
        try {
            ex.setDataSource(context, source, null);
            int trackCount = ex.getTrackCount();
            int[] map = new int[trackCount];
            int maxInput = 1 << 20;
            int rotation = 0;
            int videoTrack = -1;

            mux = new MediaMuxer(outFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            for (int i = 0; i < trackCount; i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"))) {
                    map[i] = mux.addTrack(f);
                    if (mime.startsWith("video/")) {
                        videoTrack = i;
                        if (f.containsKey(MediaFormat.KEY_ROTATION))
                            rotation = f.getInteger(MediaFormat.KEY_ROTATION);
                    }
                    if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                        maxInput = Math.max(maxInput, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                } else map[i] = -1;
            }
            if (videoTrack < 0) throw new IOException("No video track");
            if (rotation != 0) mux.setOrientationHint(rotation);
            mux.start();
            started = true;

            ByteBuffer buffer = ByteBuffer.allocateDirect(maxInput);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long[] lastTrackPts = new long[trackCount];
            for (int i = 0; i < trackCount; i++) lastTrackPts[i] = -1L;
            long baseVideoPtsUs = 0L;               // S3.1: cumulative output counters
            long baseAudioPtsUs = 0L;
            long outputBaseUs = 0L;

            for (Range range : ranges) {
                long startUs = range.startMs * 1000L;
                long endUs = range.endMs * 1000L;

                // Pass 1 — video anchor: first video sample at/after the cut point.
                selectMappedTracks(ex, map, trackCount);
                ex.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                long firstVideoPts = -1L;
                while (true) {
                    int track = ex.getSampleTrackIndex();
                    if (track < 0) break;
                    long pts = ex.getSampleTime();
                    if (track == videoTrack && pts >= startUs) { firstVideoPts = pts; break; }
                    if (!ex.advance()) break;
                }
                if (firstVideoPts < 0) throw new IOException("No video samples in trim range");

                // Pass 2 — interleave-write, both tracks anchored to firstVideoPts.
                selectMappedTracks(ex, map, trackCount);
                ex.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                long segmentMax = outputBaseUs;
                boolean wroteVideo = false;
                boolean wroteAudio = false;
                // S3.1: if audio ran past video in earlier cuts (> 10 ms), drop this
                // range's leading audio until it catches back up to the video base.
                long audioSkewUs = baseAudioPtsUs - baseVideoPtsUs;

                while (true) {
                    int track = ex.getSampleTrackIndex();
                    if (track < 0) break;
                    long pts = ex.getSampleTime();
                    if (pts < startUs) { if (!ex.advance()) break; continue; }
                    if (pts >= endUs) break;
                    if (map[track] < 0) { if (!ex.advance()) break; continue; }
                    boolean isVideo = track == videoTrack;
                    if (!isVideo && pts < firstVideoPts) {   // anchor audio to the video start
                        if (!ex.advance()) break;
                        continue;
                    }
                    buffer.clear();
                    int size = ex.readSampleData(buffer, 0);
                    if (size < 0) break;
                    long outPts = outputBaseUs + Math.max(0L, pts - firstVideoPts);
                    if (audioSkewUs > 10_000L && !isVideo && outPts < baseVideoPtsUs) {
                        if (!ex.advance()) break;
                        continue;
                    }
                    if (lastTrackPts[track] >= 0 && outPts <= lastTrackPts[track])
                        outPts = lastTrackPts[track] + 1L;   // strictly monotonic per track
                    int sampleFlags = ex.getSampleFlags();
                    int codecFlags = (sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    info.set(0, size, outPts, codecFlags);
                    mux.writeSampleData(map[track], buffer, info);
                    lastTrackPts[track] = outPts;
                    segmentMax = Math.max(segmentMax, outPts);
                    if (isVideo) wroteVideo = true; else wroteAudio = true;
                    if (!ex.advance()) break;
                }
                if (!wroteVideo) throw new IOException("No samples written for trim range");
                baseVideoPtsUs = lastTrackPts[videoTrack] + 1_000L;
                if (wroteAudio) {
                    for (int i = 0; i < trackCount; i++) {
                        if (map[i] >= 0 && i != videoTrack) baseAudioPtsUs = lastTrackPts[i] + 1_000L;
                    }
                } else {
                    baseAudioPtsUs = baseVideoPtsUs;        // no audio this range — stay locked
                }
                outputBaseUs = segmentMax + 1_000L;
            }

            mux.stop();
            started = false;
        } finally {
            try { ex.release(); } catch (RuntimeException ignored) {}
            if (mux != null) {
                if (started) { try { mux.stop(); } catch (RuntimeException ignored) {} }
                try { mux.release(); } catch (RuntimeException ignored) {}
            }
        }
    }

    private static void selectMappedTracks(MediaExtractor ex, int[] map, int trackCount) {
        for (int i = 0; i < trackCount; i++) {
            try { ex.unselectTrack(i); } catch (RuntimeException ignored) {}
            if (map[i] >= 0) ex.selectTrack(i);
        }
    }

    private static int findVideoTrack(MediaExtractor ex) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) return i;
        }
        return -1;
    }
}
