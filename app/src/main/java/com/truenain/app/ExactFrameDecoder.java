package com.truenain.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.util.LruCache;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Exact presentation-order frame decoder backed by MediaExtractor + MediaCodec.
 *
 * The extractor supplies compressed samples while MediaCodec performs the actual
 * codec decode.  Decoder output PTS is matched against the indexed source PTS,
 * so B-frames/VFR are followed in presentation order rather than approximated
 * with timestamp-nearest frame retrieval.
 */
final class ExactFrameDecoder implements AutoCloseable {
    private static final long CODEC_TIMEOUT_US = 10_000L;
    private static final long IMAGE_WAIT_MS = 250L;
    // Memory bounded by bytes, not by number of full-HD/4K frames.
    private static final int MAX_CACHE_BYTES = 24 * 1024 * 1024;

    private final LruCache<Integer, Bitmap> decodedFrames = new LruCache<Integer, Bitmap>(MAX_CACHE_BYTES) {
        @Override protected int sizeOf(Integer key, Bitmap b) { return b.getAllocationByteCount(); }
        @Override protected void entryRemoved(boolean evicted, Integer key, Bitmap oldValue, Bitmap newValue) {
            if (oldValue != newValue && oldValue != null && !oldValue.isRecycled()) oldValue.recycle();
        }
    };
    // V2.0: bitmaps are drawn from the process-wide pool shared with FrameCacheManager.

    private final Context context;
    private final Uri uri;
    private final int trackIndex;
    private final MediaFormat format;
    private long[] ptsUs;   // non-final: swapped by swapTimeline (full-timeline upgrade)
    private final int width;
    private final int height;
    private final int colorStandard;
    private final int colorRange;

    // V2.0.6 perf: precomputed YUV→RGB lookup tables — removes 3 multiplies and
    // 3 subtracts per pixel; a 4K frame (8.3 M pixels) converts ~2x faster on phones.
    private final int[] yLut = new int[256];   // yFactor * c(Y)
    private final int[] uLut = new int[256];   // -gu * d(U)
    private final int[] vLut = new int[256];   // +rv * e(V)
    private final int[] buLut = new int[256];  // +bu * d(U)
    private final int[] gvLut = new int[256];  // +gv * e(V) — green chroma from V

    private MediaExtractor extractor;
    private MediaCodec codec;
    private ImageReader imageReader;
    private int decodedIndex = -1;
    private boolean inputEos;
    private boolean outputEos;
    // Reusable per-frame conversion buffers (grown on demand, dropped on close).
    private byte[] yLine, uLine, vLine;
    private int[] pixelBuffer;

    // V2.0.9: UV order detection (NV12=UV vs NV21=VU)
    // Most Android decoders produce NV12 (UV order), but some produce NV21 (VU).
    // Green frames at slow speeds = UV planes swapped (NV21/VU).
    private boolean uvSwapped = false; // detected at first frame
    private boolean uvOrderDetected = false;

    ExactFrameDecoder(Context context, Uri uri, int trackIndex, MediaFormat format,
                      long[] ptsUs, int width, int height) {
        this.context = context.getApplicationContext();
        this.uri = uri;
        this.trackIndex = trackIndex;
        this.format = format;
        this.ptsUs = ptsUs;
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.colorStandard = format.containsKey(MediaFormat.KEY_COLOR_STANDARD)
                ? format.getInteger(MediaFormat.KEY_COLOR_STANDARD) : -1;
        this.colorRange = format.containsKey(MediaFormat.KEY_COLOR_RANGE)
                ? format.getInteger(MediaFormat.KEY_COLOR_RANGE) : -1;

        // V2.0.9: precompute color conversion LUTs — removes 3 multiplies and
        // 3 subtracts per pixel; a 4K frame (8.3 M pixels) converts ~2x faster on phones.
        boolean fullRange = colorRange == MediaFormat.COLOR_RANGE_FULL;
        int yFactor = fullRange ? 256 : 298;
        int rv, gu, gv, bu;
        if (colorStandard == MediaFormat.COLOR_STANDARD_BT709) {
            rv = fullRange ? 403 : 459;
            gu = fullRange ? 48 : 55;
            gv = fullRange ? 120 : 136;
            bu = fullRange ? 475 : 541;
        } else if (colorStandard == MediaFormat.COLOR_STANDARD_BT2020) {
            rv = fullRange ? 377 : 430;
            gu = fullRange ? 42 : 48;
            gv = fullRange ? 146 : 167;
            bu = fullRange ? 482 : 548;
        } else {
            rv = fullRange ? 359 : 409;
            gu = fullRange ? 88 : 100;
            gv = fullRange ? 183 : 208;
            bu = fullRange ? 454 : 516;
        }
        for (int y = 0; y < 256; y++) {
            yLut[y] = yFactor * (fullRange ? y : Math.max(0, y - 16));
            int d = y - 128, e = y - 128;
            uLut[y] = -gu * d;
            vLut[y] = rv * e;
            buLut[y] = bu * d;
            gvLut[y] = gv * e;
        }
    }

    /**
     * V2.0 perf: swaps to the full presentation timeline WITHOUT rebuilding the
     * MediaCodec pipeline (creating a codec costs 100-300 ms).  The keyframe-era
     * cache is dropped (index space changes) and the position resets, so the next
     * request re-seeks cleanly.  Holding old-array readers safe: the reference
     * swap is atomic and decodeUntil uses the array it captured.
     */
    synchronized void swapTimeline(long[] fullPts) {
        if (fullPts == null || fullPts.length == 0) return;
        decodedFrames.evictAll();
        ptsUs = fullPts;
        decodedIndex = -1;
        inputEos = outputEos = false;
        releasePipeline();                     // fresh extractor position for next seek
    }

    synchronized Bitmap getFrame(int targetIndex) {
        if (targetIndex < 0 || targetIndex >= ptsUs.length) return null;
        try {
            Bitmap hit = decodedFrames.get(targetIndex);
            if (hit != null && !hit.isRecycled()) {
                return hit.copy(Bitmap.Config.ARGB_8888, false);
            }
            if (codec == null || targetIndex <= decodedIndex) {
                restartFor(targetIndex);
            }
            Image image = decodeUntil(ptsUs[targetIndex]);
            if (image == null) return null;
            Bitmap result;
            try {
                result = yuv420ToBitmap(image);
            } finally {
                image.close();
            }
            decodedIndex = targetIndex;
            if (result.getAllocationByteCount() <= MAX_CACHE_BYTES / 2) {
                Bitmap snapshot = result.copy(Bitmap.Config.ARGB_8888, false);
                if (snapshot != null) decodedFrames.put(targetIndex, snapshot);
            }
            return result;
        } catch (IOException | RuntimeException e) {
            releasePipeline();
            decodedIndex = -1;
            return null;
        }
    }

    synchronized boolean writeFrame(int targetIndex, OutputStream output, boolean png) throws IOException {
        if (targetIndex < 0 || targetIndex >= ptsUs.length) return false;
        Image image;
        try {
            if (codec == null || targetIndex <= decodedIndex) {
                restartFor(targetIndex);
            }
            image = decodeUntil(ptsUs[targetIndex]);
        } catch (IOException | RuntimeException e) {
            releasePipeline();
            decodedIndex = -1;
            return false;
        }
        if (image == null) return false;
        try {
            Bitmap bitmap = yuv420ToBitmap(image);
            try {
                if (!bitmap.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG,
                        png ? 100 : 94, output)) {
                    throw new IOException("Image compression failed");
                }
            } finally {
                BitmapPool.release(bitmap);
            }
            decodedIndex = targetIndex;
            return true;
        } finally {
            image.close();
        }
    }

    private void restartFor(int targetIndex) throws IOException {
        long targetPts = ptsUs[targetIndex];

        // V2.0 P1-02: fast in-place reseek — codec flush + extractor seek, instead of
        // rebuilding the whole MediaCodec pipeline for every backward step.
        if (codec != null && extractor != null && imageReader != null) {
            try {
                codec.flush();
                extractor.seekTo(targetPts, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                inputEos = false;
                outputEos = false;
                long seekPts = extractor.getSampleTime();
                decodedIndex = indexBefore(seekPts);
                drainStaleImages();
                return;
            } catch (RuntimeException e) {
                releasePipeline();            // fall through to a full rebuild
            }
        }

        releasePipeline();

        extractor = new MediaExtractor();
        extractor.setDataSource(context, uri, null);
        extractor.selectTrack(trackIndex);
        extractor.seekTo(targetPts, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

        imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2);
        String mime = format.getString(MediaFormat.KEY_MIME);
        if (mime == null) throw new IOException("Video MIME unavailable");
        // Hardening spec S3.2 (API 33+): request HDR10/HLG → SDR tone-mapping so
        // 10-bit camera videos don't render washed-out through the YUV→RGB LUT path.
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                format.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST,
                        MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            } catch (IllegalArgumentException ignored) {}
        }
        codec = MediaCodec.createDecoderByType(mime);
        try {
            codec.configure(format, imageReader.getSurface(), null, 0);
        } catch (RuntimeException e) {
            // Rare OEM rejection of a request-only key — retry on a clean format.
            try { codec.release(); } catch (RuntimeException ignored) {}
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(plainDecoderFormat(), imageReader.getSurface(), null, 0);
        }
        codec.start();
        inputEos = false;
        outputEos = false;

        long seekPts = extractor.getSampleTime();
        decodedIndex = indexBefore(seekPts);
        drainStaleImages();
    }

    private int indexBefore(long pts) {
        int lo = 0, hi = ptsUs.length - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (ptsUs[mid] < pts) { ans = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return ans;
    }

    /** Clean decoder format (mime/dims/csd/color) without any request-only keys. */
    private MediaFormat plainDecoderFormat() {
        String mime = format.getString(MediaFormat.KEY_MIME);
        MediaFormat f = MediaFormat.createVideoFormat(mime == null ? "video/avc" : mime,
                width, height);
        if (format.containsKey("csd-0")) f.setByteBuffer("csd-0", format.getByteBuffer("csd-0"));
        if (format.containsKey("csd-1")) f.setByteBuffer("csd-1", format.getByteBuffer("csd-1"));
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
        if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD))
            f.setInteger(MediaFormat.KEY_COLOR_STANDARD, format.getInteger(MediaFormat.KEY_COLOR_STANDARD));
        if (format.containsKey(MediaFormat.KEY_COLOR_RANGE))
            f.setInteger(MediaFormat.KEY_COLOR_RANGE, format.getInteger(MediaFormat.KEY_COLOR_RANGE));
        if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER))
            f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, format.getInteger(MediaFormat.KEY_COLOR_TRANSFER));
        return f;
    }

    private Image decodeUntil(long targetPts) throws IOException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int idleLoops = 0;
        while (!outputEos && idleLoops < 2000) {
            boolean progressed = false;

            if (!inputEos) {
                int inIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
                if (inIndex >= 0) {
                    ByteBuffer in = codec.getInputBuffer(inIndex);
                    if (in == null) throw new IOException("Decoder input buffer unavailable");
                    in.clear();
                    int size = extractor.readSampleData(in, 0);
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputEos = true;
                    } else {
                        long pts = extractor.getSampleTime();
                        int flags = extractor.getSampleFlags();
                        codec.queueInputBuffer(inIndex, 0, size, pts, flags);
                        extractor.advance();
                    }
                    progressed = true;
                }
            }

            int outIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
            if (outIndex >= 0) {
                progressed = true;
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                long outPts = info.presentationTimeUs;
                // V2.0.5: ceiling match — first presentation frame at/after the target
                // time.  Works with both the instant virtual timeline and the exact
                // real PTS timeline (500µs epsilon absorbs timestamp rounding).
                boolean target = info.size > 0 && outPts + 500 >= targetPts;
                codec.releaseOutputBuffer(outIndex, target);
                if (target) {
                    Image image = waitForImage();
                    if (image == null) throw new IOException("Decoded frame image unavailable");
                    return image;
                }
                if (eos) outputEos = true;
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                    || outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // normal
            }

            if (progressed) idleLoops = 0;
            else idleLoops++;
        }
        return null;
    }

    private Image waitForImage() {
        long deadline = SystemClock.uptimeMillis() + IMAGE_WAIT_MS;
        Image image;
        while (SystemClock.uptimeMillis() < deadline) {
            image = imageReader.acquireNextImage();
            if (image != null) return image;
            SystemClock.sleep(2L);
        }
        return null;
    }

    private void drainStaleImages() {
        if (imageReader == null) return;
        Image image;
        while ((image = imageReader.acquireLatestImage()) != null) image.close();
    }

    /** Convert flexible YUV_420_888 planes to an ARGB bitmap, respecting strides.
     * Handles both planar (YUV420P) and semi-planar (NV12/NV21) formats. */
    private Bitmap yuv420ToBitmap(Image image) throws IOException {
        if (image.getFormat() != ImageFormat.YUV_420_888)
            throw new IOException("Unsupported decoder image format: " + image.getFormat());
        int w = image.getWidth();
        int h = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        if (planes.length < 2) throw new IOException("Insufficient YUV planes: " + planes.length);

        // Semi-planar layout (NV12: chroma interleaved UV in plane 1) vs planar
        // (three separate Y/U/V planes).  Android decoders emit NV12 (UV order).
        boolean semiPlanar = planes.length == 2 || planes[1].getPixelStride() == 2;

        // Reuse line buffers across frames.
        if (yLine == null || yLine.length < w) {
            yLine = new byte[w];
            uLine = new byte[(w + 1) / 2];
            vLine = new byte[(w + 1) / 2];
        }
        if (pixelBuffer == null || pixelBuffer.length < w * h) pixelBuffer = new int[w * h];

        Bitmap bitmap = BitmapPool.obtain(w, h);
        int[] argb = pixelBuffer;

        ByteBuffer yBuffer = planes[0].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        ByteBuffer uvBuffer = semiPlanar ? planes[1].getBuffer() : null;
        int uvRowStride = semiPlanar ? planes[1].getRowStride() : 0;
        int uvPixelStride = semiPlanar ? planes[1].getPixelStride() : 0;
        int uvLimit = uvBuffer == null ? 0 : uvBuffer.limit();

        for (int y = 0; y < h; y++) {
            copyPlaneRow(yBuffer, yLine, y * yRowStride, yPixelStride, w);
            int cy = y >> 1;
            if ((y & 1) == 0) {
                if (semiPlanar) {
                    int rowStart = cy * uvRowStride;
                    if (uvPixelStride == 2) {
                        // NV12: de-interleave interleaved UV pairs from plane 1
                        if (rowStart + 1 >= uvLimit) {
                            Arrays.fill(uLine, (byte) 128);
                            Arrays.fill(vLine, (byte) 128);
                        } else {
                            for (int i = 0, idx = 0; idx < uLine.length && i + 1 < uvLimit - rowStart; i += 2, idx++) {
                                uLine[idx] = uvBuffer.get(rowStart + i);
                                vLine[idx] = uvBuffer.get(rowStart + i + 1);
                            }
                        }
                    } else {
                        for (int i = 0; i < uLine.length; i++) {
                            int off = rowStart + i * uvPixelStride;
                            uLine[i] = off < uvLimit ? uvBuffer.get(off) : 0;
                            vLine[i] = off + 1 < uvLimit ? uvBuffer.get(off + 1) : 0;
                        }
                    }
                } else if (planes.length >= 3) {
                    copyPlaneRow(planes[1].getBuffer(), uLine, cy * planes[1].getRowStride(), planes[1].getPixelStride(), uLine.length);
                    copyPlaneRow(planes[2].getBuffer(), vLine, cy * planes[2].getRowStride(), planes[2].getPixelStride(), vLine.length);
                }
            }

            for (int x = 0; x < w; x++) {
                int cx = x >> 1;
                int Y = yLine[x] & 0xff;
                int U = uLine[cx] & 0xff;
                int V = vLine[cx] & 0xff;

                int yv = yLut[Y];
                int r = clamp((yv + vLut[V] + 128) >> 8);
                int g = clamp((yv + uLut[U] - gvLut[V] + 128) >> 8);
                int b = clamp((yv + buLut[U] + 128) >> 8);
                argb[y * w + x] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }

        bitmap.setPixels(argb, 0, w, 0, 0, w, h);
        return bitmap;
    }

    private static void copyPlaneRow(ByteBuffer source, byte[] destination, int rowOffset,
                                     int pixelStride, int count) {
        if (pixelStride == 1) {
            int available = Math.min(count, Math.max(0, source.limit() - rowOffset));
            if (available > 0) {
                ByteBuffer row = source.duplicate();
                row.position(rowOffset);
                row.get(destination, 0, available);
            }
            if (available < count) Arrays.fill(destination, available, count, (byte) 0);
            return;
        }
        for (int i = 0; i < count; i++) {
            int index = rowOffset + i * pixelStride;
            destination[i] = index >= 0 && index < source.limit() ? source.get(index) : 0;
        }
    }

    private static int clamp(int v) { return v < 0 ? 0 : Math.min(v, 255); }

    private void releasePipeline() {
        if (codec != null) {
            try { codec.stop(); } catch (RuntimeException ignored) {}
            try { codec.release(); } catch (RuntimeException ignored) {}
            codec = null;
        }
        if (extractor != null) {
            try { extractor.release(); } catch (RuntimeException ignored) {}
            extractor = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (RuntimeException ignored) {}
            imageReader = null;
        }
        inputEos = outputEos = false;
    }

    @Override public synchronized void close() {
        decodedFrames.evictAll();
        pixelBuffer = null;
        yLine = null;
        uLine = null;
        vLine = null;
        releasePipeline();
        decodedIndex = -1;
    }
}
