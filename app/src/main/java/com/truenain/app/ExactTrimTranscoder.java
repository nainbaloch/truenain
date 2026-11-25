package com.truenain.app;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.view.Surface;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * Exact-boundary fallback for TrimExporter.
 * Video is decoded with MediaExtractor+MediaCodec and re-encoded to AVC only when
 * stream-copy is not safe at the requested GOP boundaries. Audio stays compressed
 * and is clipped at its native sample boundaries. Source dimensions and rotation
 * metadata are preserved.
 */
final class ExactTrimTranscoder {
    private static final long TIMEOUT_US = 10_000L;

    static void transcode(Context context, Uri source, List<TrimExporter.Range> ranges,
                          FileDescriptor outFd) throws IOException {
        MediaExtractor meta = new MediaExtractor();
        try {
            meta.setDataSource(context, source, null);
            int videoTrack = findTrack(meta, true);
            if (videoTrack < 0) throw new IOException("No video track");
            int audioTrack = findTrack(meta, false);
            MediaFormat vf = meta.getTrackFormat(videoTrack);
            MediaFormat af = audioTrack >= 0 ? meta.getTrackFormat(audioTrack) : null;

            int width = vf.getInteger(MediaFormat.KEY_WIDTH);
            int height = vf.getInteger(MediaFormat.KEY_HEIGHT);
            int rotation = vf.containsKey(MediaFormat.KEY_ROTATION) ? vf.getInteger(MediaFormat.KEY_ROTATION) : 0;
            int frameRate = vf.containsKey(MediaFormat.KEY_FRAME_RATE)
                    ? Math.max(1, vf.getInteger(MediaFormat.KEY_FRAME_RATE)) : 30;
            int bitrate = vf.containsKey(MediaFormat.KEY_BIT_RATE)
                    ? Math.max(1_000_000, vf.getInteger(MediaFormat.KEY_BIT_RATE))
                    : Math.max(2_000_000, width * height * Math.max(24, frameRate) / 2);

            MediaFormat encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            encFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            encFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            encFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            encFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            // 2026-11-15: B-frames + AVC High profile REMOVED for the trim re-encode —
            // user's S9 merged output showed weird "size change" playback; B-frames
            // + MediaMuxer timestamp tables are the suspect.  Back to the simple
            // proven config (same as pre-hardening releases).
            // S3.2 (API 33+): request HDR10/HLG → SDR tone-mapping on the DECODER so
            // 10-bit camera videos keep their colors through the GL re-encode path.
            // Snapshot a clean format first for the rare configure-rejection fallback.
            MediaFormat decFmtPlain = buildPlainVideoFormat(vf);
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    vf.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST,
                            MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
                } catch (IllegalArgumentException ignored) {}
            }

            MediaCodec encoder = null;
            MediaMuxer muxer = null;
            InputSurface inputSurface = null;
            int muxVideo = -1;
            int muxAudio = -1;
            boolean muxStarted = false;
            try {
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                inputSurface = new InputSurface(encoder.createInputSurface());
                inputSurface.makeCurrent();
                encoder.start();
                // (Quality Pack grade-uniform wiring PAUSED — user: "baad mein". Shader
                // neutral defaults par hai, merge output unaffected.)

                muxer = new MediaMuxer(outFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                if (rotation != 0) muxer.setOrientationHint(rotation);
                if (af != null) muxAudio = muxer.addTrack(af);

                EncoderState enc = new EncoderState(encoder, muxer, muxAudio);
                int rangeCount = ranges.size();
                long[] rangeBaseUs = new long[rangeCount];
                long[] rangeAnchorUs = new long[rangeCount];
                long outputBaseUs = 0L;
                long lastVideoPts = -1L;

                for (int ri = 0; ri < rangeCount; ri++) {
                    TrimExporter.Range r = ranges.get(ri);
                    long startUs = r.startMs * 1000L;
                    long endUs = r.endMs * 1000L;
                    long durationWritten = transcodeRange(context, source, videoTrack, vf,
                            decFmtPlain, startUs, endUs, outputBaseUs, inputSurface, enc,
                            lastVideoPts);
                    muxStarted = enc.muxStarted;
                    muxVideo = enc.muxVideo;
                    lastVideoPts = enc.lastVideoPts;
                    rangeBaseUs[ri] = outputBaseUs;
                    rangeAnchorUs[ri] = enc.firstKeptPtsUs;
                    outputBaseUs += Math.max(1L, durationWritten);
                }

                encoder.signalEndOfInputStream();
                drainEncoder(enc, true);
                muxStarted = enc.muxStarted;
                muxVideo = enc.muxVideo;
                if (!muxStarted || muxVideo < 0) throw new IOException("No encoded video output");

                if (audioTrack >= 0 && muxAudio >= 0) {
                    copyAudio(context, source, audioTrack, ranges, rangeBaseUs, rangeAnchorUs,
                            muxer, muxAudio);
                }

                muxer.stop();
                muxStarted = false;
            } finally {
                if (inputSurface != null) inputSurface.release();
                if (encoder != null) {
                    try { encoder.stop(); } catch (RuntimeException ignored) {}
                    try { encoder.release(); } catch (RuntimeException ignored) {}
                }
                if (muxer != null) {
                    if (muxStarted) { try { muxer.stop(); } catch (RuntimeException ignored) {} }
                    try { muxer.release(); } catch (RuntimeException ignored) {}
                }
            }
        } finally {
            try { meta.release(); } catch (RuntimeException ignored) {}
        }
    }

    private static long transcodeRange(Context context, Uri source, int videoTrack,
                                       MediaFormat vf, MediaFormat decFmtPlain,
                                       long startUs, long endUs,
                                       long outputBaseUs, InputSurface inputSurface,
                                       EncoderState enc, long previousOutPts) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec decoder = null;
        OutputSurface outputSurface = null;
        try {
            ex.setDataSource(context, source, null);
            ex.selectTrack(videoTrack);
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            String mime = vf.getString(MediaFormat.KEY_MIME);
            if (mime == null) throw new IOException("Video MIME unavailable");
            outputSurface = new OutputSurface();
            decoder = MediaCodec.createDecoderByType(mime);
            try {
                decoder.configure(vf, outputSurface.getSurface(), null, 0);
            } catch (RuntimeException e) {
                // Rare OEM rejection (e.g. the tone-map request key) — clean-format retry.
                try { decoder.release(); } catch (RuntimeException ignored) {}
                decoder = MediaCodec.createDecoderByType(mime);
                decoder.configure(decFmtPlain, outputSurface.getSurface(), null, 0);
            }
            decoder.start();

            boolean inputDone = false;
            boolean outputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long firstKeptPts = -1L;
            long lastSourcePts = -1L;
            long lastOut = previousOutPts;
            int idle = 0;

            while (!outputDone && idle < 4000) {
                boolean progressed = false;
                if (!inputDone) {
                    int in = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (in >= 0) {
                        ByteBuffer buf = decoder.getInputBuffer(in);
                        if (buf == null) throw new IOException("Decoder input unavailable");
                        buf.clear();
                        long sampleTime = ex.getSampleTime();
                        if (sampleTime < 0 || sampleTime >= endUs) {
                            decoder.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            buf.clear();
                    int size = ex.readSampleData(buf, 0);
                            if (size < 0) {
                                decoder.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                decoder.queueInputBuffer(in, 0, size, sampleTime, ex.getSampleFlags());
                                ex.advance();
                            }
                        }
                        progressed = true;
                    }
                }

                int out = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                if (out >= 0) {
                    progressed = true;
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    boolean keep = info.size > 0 && info.presentationTimeUs >= startUs
                            && info.presentationTimeUs < endUs;
                    decoder.releaseOutputBuffer(out, keep);
                    if (keep) {
                        outputSurface.awaitNewImage();
                        outputSurface.drawImage();
                        if (firstKeptPts < 0) firstKeptPts = info.presentationTimeUs;
                        long outPts = outputBaseUs + Math.max(0L, info.presentationTimeUs - firstKeptPts);
                        if (outPts <= lastOut) outPts = lastOut + 1L;
                        inputSurface.setPresentationTime(outPts * 1000L);
                        inputSurface.swapBuffers();
                        lastOut = outPts;
                        lastSourcePts = info.presentationTimeUs;
                        drainEncoder(enc, false);
                    }
                    if (eos) outputDone = true;
                }
                if (progressed) idle = 0; else idle++;
            }
            if (firstKeptPts < 0) throw new IOException("No video frames in trim range");
            enc.lastVideoPts = lastOut;
            enc.firstKeptPtsUs = firstKeptPts;   // S3.1: audio anchor = video's first kept PTS
            return Math.max(1L, lastSourcePts - firstKeptPts + 1L);
        } finally {
            if (decoder != null) {
                try { decoder.stop(); } catch (RuntimeException ignored) {}
                try { decoder.release(); } catch (RuntimeException ignored) {}
            }
            if (outputSurface != null) outputSurface.release();
            try { ex.release(); } catch (RuntimeException ignored) {}
        }
    }

    private static void drainEncoder(EncoderState state, boolean end) throws IOException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int idle = 0;
        while (true) {
            int out = state.encoder.dequeueOutputBuffer(info, TIMEOUT_US);
            if (out == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!end || ++idle > 200) return;
                continue;
            }
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (state.muxStarted) throw new IOException("Encoder format changed twice");
                state.muxVideo = state.muxer.addTrack(state.encoder.getOutputFormat());
                state.muxer.start();
                state.muxStarted = true;
                continue;
            }
            if (out >= 0) {
                ByteBuffer data = state.encoder.getOutputBuffer(out);
                if (data == null) throw new IOException("Encoder output unavailable");
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0) {
                    if (!state.muxStarted) throw new IOException("Muxer not started");
                    data.position(info.offset);
                    data.limit(info.offset + info.size);
                    state.muxer.writeSampleData(state.muxVideo, data, info);
                    state.lastVideoPts = Math.max(state.lastVideoPts, info.presentationTimeUs);
                }
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                state.encoder.releaseOutputBuffer(out, false);
                if (eos) return;
            }
        }
    }

    /**
     * Hardening spec S3.1: audio is written on the VIDEO re-encode timeline — each
     * range's samples are offset by that range's video output base and anchored to
     * the video's first kept PTS, so cumulative A/V drift across cuts (AAC frame
     * edges vs GOP boundaries) is structurally impossible.  Leading audio samples
     * before the anchor are skipped, and output PTS stay strictly monotonic.
     */
    private static void copyAudio(Context context, Uri source, int audioTrack,
                                  List<TrimExporter.Range> ranges, long[] rangeBaseUs,
                                  long[] rangeAnchorUs, MediaMuxer muxer,
                                  int muxAudio) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(context, source, null);
            MediaFormat af = ex.getTrackFormat(audioTrack);
            int max = af.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                    ? Math.max(64 * 1024, af.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)) : 1024 * 1024;
            ByteBuffer buf = ByteBuffer.allocateDirect(max);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long last = -1L;
            ex.selectTrack(audioTrack);
            for (int ri = 0; ri < ranges.size(); ri++) {
                TrimExporter.Range r = ranges.get(ri);
                long startUs = r.startMs * 1000L;
                long endUs = r.endMs * 1000L;
                long base = ri < rangeBaseUs.length ? Math.max(0L, rangeBaseUs[ri]) : 0L;
                long anchorUs = (ri < rangeAnchorUs.length && rangeAnchorUs[ri] >= 0L)
                        ? rangeAnchorUs[ri] : startUs;
                ex.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                while (true) {
                    long pts = ex.getSampleTime();
                    if (pts < 0 || pts >= endUs) break;
                    if (pts < startUs || pts < anchorUs) {   // skip audio before the video anchor
                        if (!ex.advance()) break;
                        continue;
                    }
                    buf.clear();
                    int size = ex.readSampleData(buf, 0);
                    if (size < 0) break;
                    long outPts = base + (pts - anchorUs);
                    if (outPts <= last) outPts = last + 1L;  // strict monotonic per track
                    int sampleFlags = ex.getSampleFlags();
                    int codecFlags = (sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    info.set(0, size, outPts, codecFlags);
                    muxer.writeSampleData(muxAudio, buf, info);
                    last = outPts;
                    if (!ex.advance()) break;
                }
            }
        } finally {
            try { ex.release(); } catch (RuntimeException ignored) {}
        }
    }

    /** Clean decoder format (mime/dims/csd/color) without any request-only keys. */
    private static MediaFormat buildPlainVideoFormat(MediaFormat src) {
        String mime = src.getString(MediaFormat.KEY_MIME);
        int w = src.containsKey(MediaFormat.KEY_WIDTH) ? src.getInteger(MediaFormat.KEY_WIDTH) : 0;
        int h = src.containsKey(MediaFormat.KEY_HEIGHT) ? src.getInteger(MediaFormat.KEY_HEIGHT) : 0;
        MediaFormat f = MediaFormat.createVideoFormat(mime == null ? "video/avc" : mime,
                Math.max(1, w), Math.max(1, h));
        if (src.containsKey("csd-0")) f.setByteBuffer("csd-0", src.getByteBuffer("csd-0"));
        if (src.containsKey("csd-1")) f.setByteBuffer("csd-1", src.getByteBuffer("csd-1"));
        if (src.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, src.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
        if (src.containsKey(MediaFormat.KEY_COLOR_STANDARD))
            f.setInteger(MediaFormat.KEY_COLOR_STANDARD, src.getInteger(MediaFormat.KEY_COLOR_STANDARD));
        if (src.containsKey(MediaFormat.KEY_COLOR_RANGE))
            f.setInteger(MediaFormat.KEY_COLOR_RANGE, src.getInteger(MediaFormat.KEY_COLOR_RANGE));
        if (src.containsKey(MediaFormat.KEY_COLOR_TRANSFER))
            f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, src.getInteger(MediaFormat.KEY_COLOR_TRANSFER));
        return f;
    }

    private static int findTrack(MediaExtractor ex, boolean video) {
        String prefix = video ? "video/" : "audio/";
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static final class EncoderState {
        final MediaCodec encoder;
        final MediaMuxer muxer;
        final int muxAudio;
        int muxVideo = -1;
        boolean muxStarted;
        long lastVideoPts = -1L;
        long firstKeptPtsUs = -1L;   // S3.1: per-range video anchor for audio alignment
        EncoderState(MediaCodec e, MediaMuxer m, int a) { encoder = e; muxer = m; muxAudio = a; }
    }

    // Minimal EGL input surface for the encoder.
    private static final class InputSurface {
        private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        private EGLContext context = EGL14.EGL_NO_CONTEXT;
        private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
        private Surface surface;
        InputSurface(Surface s) { surface = s; setup(); }
        private void setup() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw new RuntimeException("EGL init failed");
            int[] attrs = {EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
                    EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,0x3142,1,EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1]; int[] num = new int[1];
            EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, num, 0);
            int[] ctx = {EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE};
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctx, 0);
            int[] surf = {EGL14.EGL_NONE};
            eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, surf, 0);
        }
        void makeCurrent() { EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context); }
        boolean swapBuffers() { return EGL14.eglSwapBuffers(display, eglSurface); }
        void setPresentationTime(long ns) { EGLExt.eglPresentationTimeANDROID(display, eglSurface, ns); }
        void release() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                // Hardening spec S4.2: unbind FIRST, then destroy — guarantees the
                // hardware encoder surface/context pair is released deterministically.
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(display, eglSurface); EGL14.eglDestroyContext(display, context);
                EGL14.eglReleaseThread(); EGL14.eglTerminate(display);
            }
            if (surface != null) surface.release();
            surface = null; display = EGL14.EGL_NO_DISPLAY; context = EGL14.EGL_NO_CONTEXT; eglSurface = EGL14.EGL_NO_SURFACE;
        }
    }

    // Decoder SurfaceTexture + tiny OES renderer.
    private static final class OutputSurface implements SurfaceTexture.OnFrameAvailableListener {
        private final TextureRender render;
        private final SurfaceTexture texture;
        private final Surface surface;
        private final Object lock = new Object();
        private boolean frameAvailable;
        OutputSurface() {
            render = new TextureRender();
            render.surfaceCreated();
            texture = new SurfaceTexture(render.textureId());
            texture.setOnFrameAvailableListener(this);
            surface = new Surface(texture);
        }
        Surface getSurface() { return surface; }
        void awaitNewImage() throws IOException {
            synchronized (lock) {
                long deadline = android.os.SystemClock.uptimeMillis() + 2500L;
                while (!frameAvailable) {
                    long left = deadline - android.os.SystemClock.uptimeMillis();
                    if (left <= 0) throw new IOException("Timed out waiting for decoded frame");
                    try { lock.wait(left); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                }
                frameAvailable = false;
            }
            texture.updateTexImage();
        }
        void drawImage() { render.draw(texture); }
        @Override public void onFrameAvailable(SurfaceTexture st) { synchronized (lock) { frameAvailable = true; lock.notifyAll(); } }
        void release() { surface.release(); texture.release(); }
    }

    private static final class TextureRender {
        private static final float[] VERT = {-1f,-1f,0f, 1f,-1f,0f, -1f,1f,0f, 1f,1f,0f};
        private static final float[] TEX = {0f,1f, 1f,1f, 0f,0f, 1f,0f};
        private final FloatBuffer vb = ByteBuffer.allocateDirect(VERT.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        private final FloatBuffer tb = ByteBuffer.allocateDirect(TEX.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        private final float[] st = new float[16];
        private int program, texId, aPos, aTex, uTexMat;
        private int uTexelLoc, uBrightLoc, uContrastLoc, uSatLoc, uWarmLoc, uShadowLoc, uHighLoc, uVigLoc, uSharpLoc;
        // Quality Pack grade state (ColorGrade table) — set by transcode() per preset.
        private static final float[] GRADE_NEUTRAL = {0f, 1f, 1f, 0f, 0f, 0f, 0f, 0f};
        private static float[] grade = GRADE_NEUTRAL;
        private static float texelX = 1f / 1920f, texelY = 1f / 1080f;
        static void setGradePreset(int preset) { grade = ColorGrade.params(preset); }
        static void setTexel(float x, float y) { texelX = Math.max(1e-6f, x); texelY = Math.max(1e-6f, y); }
        TextureRender() { vb.put(VERT).position(0); tb.put(TEX).position(0); Matrix.setIdentityM(st,0); }
        int textureId() { return texId; }
        void surfaceCreated() {
            String vs = "attribute vec4 aPosition;attribute vec4 aTextureCoord;uniform mat4 uTexMatrix;varying vec2 vTextureCoord;void main(){gl_Position=aPosition;vTextureCoord=(uTexMatrix*aTextureCoord).xy;}";
            String fs = "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "varying vec2 vTextureCoord;\n"
                    + "uniform samplerExternalOES sTexture;\n"
                    + "uniform vec2 uTexel;\n"
                    + "uniform float uBright;\n"
                    + "uniform float uContrast;\n"
                    + "uniform float uSat;\n"
                    + "uniform float uWarm;\n"
                    + "uniform float uShadow;\n"
                    + "uniform float uHigh;\n"
                    + "uniform float uVig;\n"
                    + "uniform float uSharp;\n"
                    + "void main(){\n"
                    + "  vec3 c = texture2D(sTexture, vTextureCoord).rgb;\n"
                    + "  vec3 n1 = texture2D(sTexture, vTextureCoord + vec2(uTexel.x, 0.0)).rgb;\n"
                    + "  vec3 n2 = texture2D(sTexture, vTextureCoord - vec2(uTexel.x, 0.0)).rgb;\n"
                    + "  vec3 n3 = texture2D(sTexture, vTextureCoord + vec2(0.0, uTexel.y)).rgb;\n"
                    + "  vec3 n4 = texture2D(sTexture, vTextureCoord - vec2(0.0, uTexel.y)).rgb;\n"
                    + "  vec3 blur = (n1 + n2 + n3 + n4) * 0.25;\n"
                    + "  c = c + (c - blur) * uSharp;\n"
                    + "  c += uBright;\n"
                    + "  c = (c - 0.5) * uContrast + 0.5;\n"
                    + "  float lum = dot(c, vec3(0.299, 0.587, 0.114));\n"
                    + "  c = mix(vec3(lum), c, uSat);\n"
                    + "  c += uWarm * vec3(0.08, 0.0, -0.08);\n"
                    + "  float l01 = clamp(dot(c, vec3(0.299, 0.587, 0.114)), 0.0, 1.0);\n"
                    + "  c += uShadow * (1.0 - smoothstep(0.0, 0.45, l01)) * vec3(0.10);\n"
                    + "  c -= uHigh * smoothstep(0.55, 1.0, l01) * vec3(0.10);\n"
                    + "  vec2 d = vTextureCoord - vec2(0.5);\n"
                    + "  c *= 1.0 - uVig * smoothstep(0.25, 0.85, dot(d, d) * 2.0);\n"
                    + "  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);\n"
                    + "}";
            program = createProgram(vs,fs);
            aPos = GLES20.glGetAttribLocation(program,"aPosition");
            aTex = GLES20.glGetAttribLocation(program,"aTextureCoord");
            uTexMat = GLES20.glGetUniformLocation(program,"uTexMatrix");
            uTexelLoc = GLES20.glGetUniformLocation(program,"uTexel");
            uBrightLoc = GLES20.glGetUniformLocation(program,"uBright");
            uContrastLoc = GLES20.glGetUniformLocation(program,"uContrast");
            uSatLoc = GLES20.glGetUniformLocation(program,"uSat");
            uWarmLoc = GLES20.glGetUniformLocation(program,"uWarm");
            uShadowLoc = GLES20.glGetUniformLocation(program,"uShadow");
            uHighLoc = GLES20.glGetUniformLocation(program,"uHigh");
            uVigLoc = GLES20.glGetUniformLocation(program,"uVig");
            uSharpLoc = GLES20.glGetUniformLocation(program,"uSharp");
            int[] t = new int[1]; GLES20.glGenTextures(1,t,0); texId=t[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texId);
            // Hardening spec S1.2: bilinear filtering both ways — never GL_NEAREST.
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        }
        void draw(SurfaceTexture texture) {
            texture.getTransformMatrix(st);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            // Quality Pack grading uniforms (HDR+ / Cinematic / Vivid / Warm / Cool).
            GLES20.glUniform2f(uTexelLoc, texelX, texelY);
            GLES20.glUniform1f(uBrightLoc, grade[0]);
            GLES20.glUniform1f(uContrastLoc, grade[1]);
            GLES20.glUniform1f(uSatLoc, grade[2]);
            GLES20.glUniform1f(uWarmLoc, grade[3]);
            GLES20.glUniform1f(uShadowLoc, grade[4]);
            GLES20.glUniform1f(uHighLoc, grade[5]);
            GLES20.glUniform1f(uVigLoc, grade[6]);
            GLES20.glUniform1f(uSharpLoc, grade[7]);
            vb.position(0); GLES20.glVertexAttribPointer(aPos,3,GLES20.GL_FLOAT,false,12,vb); GLES20.glEnableVertexAttribArray(aPos);
            tb.position(0); GLES20.glVertexAttribPointer(aTex,2,GLES20.GL_FLOAT,false,8,tb); GLES20.glEnableVertexAttribArray(aTex);
            GLES20.glUniformMatrix4fv(uTexMat,1,false,st,0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texId);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
            GLES20.glFinish();
        }
        private static int createProgram(String vs,String fs) {
            int v=load(GLES20.GL_VERTEX_SHADER,vs), f=load(GLES20.GL_FRAGMENT_SHADER,fs);
            int p=GLES20.glCreateProgram(); GLES20.glAttachShader(p,v); GLES20.glAttachShader(p,f); GLES20.glLinkProgram(p);
            int[] ok=new int[1]; GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);
            if(ok[0]==0) throw new RuntimeException("GL link: "+GLES20.glGetProgramInfoLog(p));
            return p;
        }
        private static int load(int type,String code) {
            int s=GLES20.glCreateShader(type); GLES20.glShaderSource(s,code); GLES20.glCompileShader(s);
            int[] ok=new int[1]; GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);
            if(ok[0]==0) throw new RuntimeException("GL shader: "+GLES20.glGetShaderInfoLog(s));
            return s;
        }
    }
}
