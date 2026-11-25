package com.truenain.app;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.Surface;
import android.widget.FrameLayout;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Quality Pack Phase B: VideoView replacement — MediaPlayer frames render
 * through a GL shader, so the selected Quality Look (HDR+/Cinematic/Vivid/…)
 * applies LIVE to the playing video in Media Player.
 *
 * Exposes the VideoView-style API MainActivity already uses
 * (setVideoURI / start / pause / seekTo / getCurrentPosition / ...).
 * View transforms (zoom/pan/rotate) work normally on this container.
 */
public final class GradedVideoView extends FrameLayout {

    public interface OnPreparedListener { void onPrepared(MediaPlayer mp); }
    public interface OnCompletionListener { void onCompletion(MediaPlayer mp); }
    public interface OnErrorListener { boolean onError(MediaPlayer mp, int what, int extra); }

    /** Shared Quality Look (ColorGrade id) — live, applied to every drawn frame. */
    private static volatile int lookPreset = 0;
    public static void setLookPreset(int preset) {
        lookPreset = preset;
        // NOTE (v2.4.4 fix): yahan se gl.requestRender() call KABHI nahi hota tha —
        // comment dawa karta tha lekin code mein call hi nahi likha tha. Isliye
        // paused/stuck video par look change (aur Original par wapas jana) screen
        // par nazar nahi aata tha jab tak playback resume na ho (user Note #4).
        // Foran render ke liye MainActivity ab instance requestLookRedraw() bulata hai.
    }

    /** Paused/stuck video par bhi naya look FORAN render karo (Note #4 fix). */
    public void requestLookRedraw() {
        if (glReady && !directMode) gl.requestRender();
    }

    private final GLSurfaceView gl;
    private final Renderer renderer;
    private MediaPlayer player;
    private Uri pendingUri;
    private volatile boolean glReady;
    private volatile boolean prepared;
    private volatile float liveZoom = 1f;
    /** v2.4.6 PHASE 2: live zoom for the GL bicubic sampling path. */
    public void setLiveZoom(float z) { liveZoom = z; }
    /** v2.4.6 PHASE 2 (audit fix 5): activity resume par GL restart — black/frozen na ho. */
    public void glResume() {
        try { gl.onResume(); } catch (RuntimeException ignored) {}
        gl.requestRender();
    }
    public void glPause() {
        try { gl.onPause(); } catch (RuntimeException ignored) {}
    }
    /** v2.4.6 PHASE 2 (audit fix 5): surface recreate par player dobara attach — warna black. */
    void reattachPlayerSurface(Surface s) {
        if (player != null && prepared) {
            try { player.setSurface(s); } catch (RuntimeException ignored) {}
            gl.requestRender();
        }
    }
    private OnPreparedListener preparedListener;
    private OnCompletionListener completionListener;
    private OnErrorListener errorListener;
    // Dual-engine: GL (live look) choti/medium videos par; DIRECT SurfaceView
    // (bina look) 2K/4K + GL-error fallback par — S9 hardware decoder ka
    // proven path (VideoView wala). Ek dafa fallback, phir direct hi rahega.
    private android.view.SurfaceView directSurface;
    private volatile boolean directMode;
    private volatile boolean directReady;
    private boolean fallbackTried;
    private static final long GL_MAX_PIXELS = 2560L * 1440L;

    /** GL pipeline 2K/4K par S9 par stuck/error deta hai — badi videos direct. */
    public void setDirectMode(boolean d) { directMode = d; }

    public GradedVideoView(Context context) {
        super(context);
        renderer = new Renderer(this);
        gl = new GLSurfaceView(context);
        gl.setEGLContextClientVersion(2);
        gl.setRenderer(renderer);
        gl.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        addView(gl, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private final class Renderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
        private final GradedVideoView host;
        private SurfaceTexture texture;
        private Surface surface;
        private int program, texId, aPos, aTex, uTexMat;
        private int uTexelLoc, uBrightLoc, uContrastLoc, uSatLoc, uWarmLoc, uShadowLoc, uHighLoc, uVigLoc, uSharpLoc;
        private int uZoomLoc;   // v2.4.6 PHASE 2
        private final float[] st = new float[16];
        private final FloatBuffer vb;
        private final FloatBuffer tb;
        private static final float[] VERT = {-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f};
        private static final float[] TEX = {0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f};
        private volatile int videoW, videoH;
        private volatile int surfaceW, surfaceH;
        private volatile boolean frameDirty = true;

        Renderer(GradedVideoView host) {
            this.host = host;
            vb = ByteBuffer.allocateDirect(VERT.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            tb = ByteBuffer.allocateDirect(TEX.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            vb.put(VERT).position(0);
            tb.put(TEX).position(0);
        }

        @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            String vs = "attribute vec4 aPosition;\n"
                    + "attribute vec4 aTextureCoord;\n"
                    + "uniform mat4 uTexMatrix;\n"
                    + "varying vec2 vTextureCoord;\n"
                    + "void main(){ gl_Position = aPosition; vTextureCoord = (uTexMatrix * aTextureCoord).xy; }";
            String fs = "#extension GL_OES_EGL_image_external : require\n"
                    + "precision highp float;\n"
                    + "varying vec2 vTextureCoord;\n"
                    + "uniform samplerExternalOES sTexture;\n"
                    + "uniform vec2 uTexel;\n"
                    + "uniform float uZoom;\n"
                    + "uniform float uBright;\n"
                    + "uniform float uContrast;\n"
                    + "uniform float uSat;\n"
                    + "uniform float uWarm;\n"
                    + "uniform float uShadow;\n"
                    + "uniform float uHigh;\n"
                    + "uniform float uVig;\n"
                    + "uniform float uSharp;\n"
                    + "float cmr(float x){ x = abs(x); if (x < 1.0) return 1.5*x*x*x - 2.5*x*x + 1.0; if (x < 2.0) return -0.5*x*x*x + 2.5*x*x - 4.0*x + 2.0; return 0.0; }\n"
                    + "vec3 sampleBicubic(vec2 uv){\n"
                    + "  vec2 fx = uv / uTexel - 0.5;\n"
                    + "  vec2 x0 = floor(fx);\n"
                    + "  vec2 t = fx - x0;\n"
                    + "  vec3 sum = vec3(0.0);\n"
                    + "  for (int j = -1; j <= 2; j++) {\n"
                    + "    for (int i = -1; i <= 2; i++) {\n"
                    + "      float wx = (i == -1) ? cmr(t.x + 1.0) : (i == 0) ? cmr(t.x) : (i == 1) ? cmr(1.0 - t.x) : cmr(2.0 - t.x);\n"
                    + "      float wy = (j == -1) ? cmr(t.y + 1.0) : (j == 0) ? cmr(t.y) : (j == 1) ? cmr(1.0 - t.y) : cmr(2.0 - t.y);\n"
                    + "      vec2 o = clamp((x0 + vec2(float(i), float(j)) + 0.5) * uTexel, uTexel * 0.5, 1.0 - uTexel * 0.5);\n"
                    + "      sum += texture2D(sTexture, o).rgb * wx * wy;\n"
                    + "    }\n"
                    + "  }\n"
                    + "  return sum;\n"
                    + "}\n"
                    + "void main(){\n"
                    + "  vec3 c;\n"
                    + "  if (uZoom > 1.3) c = sampleBicubic(vTextureCoord);\n"
                    + "  else c = texture2D(sTexture, vTextureCoord).rgb;\n"
                    + "  vec3 n1 = texture2D(sTexture, vTextureCoord + vec2(uTexel.x, 0.0)).rgb;\n"
                    + "  vec3 n2 = texture2D(sTexture, vTextureCoord - vec2(uTexel.x, 0.0)).rgb;\n"
                    + "  vec3 n3 = texture2D(sTexture, vTextureCoord + vec2(0.0, uTexel.y)).rgb;\n"
                    + "  vec3 n4 = texture2D(sTexture, vTextureCoord - vec2(0.0, uTexel.y)).rgb;\n"
                    + "  vec3 blur = (n1 + n2 + n3 + n4) * 0.25;\n"
                    + "  float effSharp = (uZoom > 1.5) ? max(uSharp, 0.30) : uSharp;\n" + "  c = c + (c - blur) * effSharp;\n"
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
            program = createProgram(vs, fs);
            aPos = GLES20.glGetAttribLocation(program, "aPosition");
            aTex = GLES20.glGetAttribLocation(program, "aTextureCoord");
            uTexMat = GLES20.glGetUniformLocation(program, "uTexMatrix");
            uTexelLoc = GLES20.glGetUniformLocation(program, "uTexel");
            uZoomLoc = GLES20.glGetUniformLocation(program, "uZoom");
            uBrightLoc = GLES20.glGetUniformLocation(program, "uBright");
            uContrastLoc = GLES20.glGetUniformLocation(program, "uContrast");
            uSatLoc = GLES20.glGetUniformLocation(program, "uSat");
            uWarmLoc = GLES20.glGetUniformLocation(program, "uWarm");
            uShadowLoc = GLES20.glGetUniformLocation(program, "uShadow");
            uHighLoc = GLES20.glGetUniformLocation(program, "uHigh");
            uVigLoc = GLES20.glGetUniformLocation(program, "uVig");
            uSharpLoc = GLES20.glGetUniformLocation(program, "uSharp");
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            texId = t[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            texture = new SurfaceTexture(texId);
            texture.setOnFrameAvailableListener(this);
            surface = new Surface(texture);
            host.reattachPlayerSurface(surface);   // v2.4.6 PHASE 2: resume par player re-attach
            host.markGlReady();
        }

        @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
            surfaceW = width;
            surfaceH = height;
            host.gl.requestRender();
        }

        @Override public void onFrameAvailable(SurfaceTexture st) {
            frameDirty = true;
            host.gl.requestRender();
        }

        @Override public void onDrawFrame(GL10 gl) {
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (texture == null) return;
            if (frameDirty) {
                try { texture.updateTexImage(); } catch (RuntimeException e) { return; }
                frameDirty = false;
            }
            texture.getTransformMatrix(st);
            // Letterbox: video aspect vs surface aspect — black bars VideoView jaisa.
            int vw = videoW, vh = videoH, sw = surfaceW, sh = surfaceH;
            if (vw > 0 && vh > 0 && sw > 0 && sh > 0) {
                float va = (float) vw / vh, sa = (float) sw / sh;
                int ow, oh;
                if (va > sa) { ow = sw; oh = Math.max(1, Math.round(sw / va)); }
                else { oh = sh; ow = Math.max(1, Math.round(sh * va)); }
                GLES20.glViewport((sw - ow) / 2, (sh - oh) / 2, ow, oh);
            } else {
                GLES20.glViewport(0, 0, Math.max(1, sw), Math.max(1, sh));
            }
            GLES20.glUseProgram(program);
            // Quality Look uniforms (live — look badalte hi agle frame par lag jata hai).
            float[] p = ColorGrade.params(lookPreset);
            GLES20.glUniform2f(uTexelLoc, texelX(), texelY());
            GLES20.glUniform1f(uZoomLoc, host.liveZoom);
            GLES20.glUniform1f(uBrightLoc, p[0]);
            GLES20.glUniform1f(uContrastLoc, p[1]);
            GLES20.glUniform1f(uSatLoc, p[2]);
            GLES20.glUniform1f(uWarmLoc, p[3]);
            GLES20.glUniform1f(uShadowLoc, p[4]);
            GLES20.glUniform1f(uHighLoc, p[5]);
            GLES20.glUniform1f(uVigLoc, p[6]);
            GLES20.glUniform1f(uSharpLoc, p[7]);
            vb.position(0); GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 12, vb); GLES20.glEnableVertexAttribArray(aPos);
            tb.position(0); GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 8, tb); GLES20.glEnableVertexAttribArray(aTex);
            GLES20.glUniformMatrix4fv(uTexMat, 1, false, st, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        private float texelX() { return 1f / Math.max(1, videoW); }
        private float texelY() { return 1f / Math.max(1, videoH); }

        void setVideoSize(int w, int h) { videoW = w; videoH = h; }

        void releaseSurface() {
            glReady = false;
            if (texture != null) {
                texture.setOnFrameAvailableListener(null);
                try { texture.release(); } catch (RuntimeException ignored) {}
            }
            if (surface != null) { try { surface.release(); } catch (RuntimeException ignored) {} }
            texture = null;
            surface = null;
        }

        private int createProgram(String vs, String fs) {
            int v = load(GLES20.GL_VERTEX_SHADER, vs);
            int f = load(GLES20.GL_FRAGMENT_SHADER, fs);
            int pr = GLES20.glCreateProgram();
            GLES20.glAttachShader(pr, v);
            GLES20.glAttachShader(pr, f);
            GLES20.glLinkProgram(pr);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(pr, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new RuntimeException("GL link: " + GLES20.glGetProgramInfoLog(pr));
            return pr;
        }

        private int load(int type, String code) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, code);
            GLES20.glCompileShader(s);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) throw new RuntimeException("GL shader: " + GLES20.glGetShaderInfoLog(s));
            return s;
        }
    }

    void markGlReady() {
        glReady = true;
        post(this::maybeOpen);      // MediaPlayer main thread par banta hai (Looper)
    }

    private void maybeOpen() {
        if (directMode) { maybeOpenDirect(); return; }
        if (glReady && pendingUri != null && player == null) openPlayer(pendingUri);
    }

    private void openPlayer(Uri uri) {
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build());
        if (renderer.surface != null) player.setSurface(renderer.surface);
        player.setOnPreparedListener(mp -> {
            prepared = true;
            int vw = mp.getVideoWidth(), vh = mp.getVideoHeight();
            if (vw > 0 && vh > 0) renderer.setVideoSize(vw, vh);
            // (setDefaultBufferSize REMOVED — MediaPlayer SurfaceTexture par buffer
            //  size khud set karta hai; manual call buffer-restart = video start se
            //  restart ka bug tha, double-tap seek par pakra gaya.)
            // 2K/4K GL path par S9 stuck/error — pehle frame se pehle hi direct par jao.
            if (!fallbackTried && (long) vw * vh > GL_MAX_PIXELS) {
                fallbackTried = true;
                fallbackToDirect();
                return;
            }
            gl.requestRender();        // frame 0 foran render — blank start na ho
            if (preparedListener != null) preparedListener.onPrepared(mp);
        });
        player.setOnCompletionListener(mp -> { if (completionListener != null) completionListener.onCompletion(mp); });
        player.setOnErrorListener((mp, what, extra) -> {
            // GL pipeline fail → ek dafa DIRECT engine par auto-fallback (koi popup nahi).
            if (!fallbackTried) {
                fallbackTried = true;
                fallbackToDirect();
                return true;
            }
            return errorListener != null && errorListener.onError(mp, what, extra);
        });
        // S1 (v2.4.4 — right engine): seek COMPLETE hote hi GL re-render.  v2.4.3
        // mein ye listener galti se openDirectPlayer mein laga tha (wahan gl GONE
        // hai — koi asar nahi), is liye GL engine mein seek-stuck (Note #5) reh
        // gaya tha.  Ab seek ke baad pending frame foran draw hota hai.
        player.setOnSeekCompleteListener(mp -> gl.requestRender());
        try {
            player.setDataSource(getContext(), uri);
            player.prepareAsync();
        } catch (Exception e) {
            if (errorListener != null) errorListener.onError(player, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
        }
    }

    // ── DIRECT engine: SurfaceView + MediaPlayer (VideoView ka proven path — 4K OK, no look) ──
    // (directSurface/directReady fields upar declared hain.)

    private void fallbackToDirect() {
        if (player != null) {
            try { player.stop(); } catch (RuntimeException ignored) {}
            try { player.release(); } catch (RuntimeException ignored) {}
            player = null;
        }
        prepared = false;
        directMode = true;
        gl.setVisibility(GONE);
        try { gl.onPause(); } catch (RuntimeException ignored) {}
        ensureDirectSurface();
        if (pendingUri != null) maybeOpenDirect();
    }

    private void ensureDirectSurface() {
        if (directSurface != null) return;
        directSurface = new android.view.SurfaceView(getContext());
        directSurface.setZOrderOnTop(false);
        directSurface.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
            @Override public void surfaceCreated(android.view.SurfaceHolder holder) {
                directReady = true;
                maybeOpenDirect();
            }
            @Override public void surfaceChanged(android.view.SurfaceHolder holder, int format, int width, int height) { }
            @Override public void surfaceDestroyed(android.view.SurfaceHolder holder) { directReady = false; }
        });
        addView(directSurface, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private void maybeOpenDirect() {
        if (directMode && directReady && pendingUri != null && player == null) openDirectPlayer(pendingUri);
    }

    private void openDirectPlayer(Uri uri) {
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build());
        player.setSurface(directSurface.getHolder().getSurface());
        player.setOnPreparedListener(mp -> {
            prepared = true;
            if (preparedListener != null) preparedListener.onPrepared(mp);
        });
        player.setOnCompletionListener(mp -> { if (completionListener != null) completionListener.onCompletion(mp); });
        player.setOnErrorListener((mp, what, extra) ->
                errorListener != null && errorListener.onError(mp, what, extra));
        // (S1 seek-re-render yahan se HATA gaya — v2.4.3 mein galti se DIRECT
        //  player mein laga tha; direct mode mein gl GONE hota hai is liye
        //  requestRender ka koi asar nahi tha.  Ab ye GL engine ke openPlayer
        //  mein sahi jagah par hai.)
        try {
            player.setDataSource(getContext(), uri);
            player.prepareAsync();
        } catch (Exception e) {
            if (errorListener != null) errorListener.onError(player, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
        }
    }

    // ── VideoView-style API ────────────────────────────────────────────────

    public void setVideoURI(Uri uri) {
        pendingUri = uri;
        prepared = false;
        maybeOpen();
    }

    public void setOnPreparedListener(OnPreparedListener l) { preparedListener = l; }
    public void setOnCompletionListener(OnCompletionListener l) { completionListener = l; }
    public void setOnErrorListener(OnErrorListener l) { errorListener = l; }

    public void start() { if (player != null) { try { player.start(); } catch (RuntimeException ignored) {} } }
    public void pause() { if (player != null) { try { player.pause(); } catch (RuntimeException ignored) {} } }
    public boolean isPlaying() {
        try { return player != null && player.isPlaying(); } catch (RuntimeException e) { return false; }
    }
    public int getCurrentPosition() {
        try { return player != null ? Math.max(0, player.getCurrentPosition()) : 0; } catch (RuntimeException e) { return 0; }
    }
    public int getDuration() {
        try { return (player != null && prepared) ? Math.max(0, player.getDuration()) : 0; } catch (RuntimeException e) { return 0; }
    }
    public void seekTo(int ms) { if (player != null) { try { player.seekTo(ms); } catch (RuntimeException ignored) {} } }
    public void seekTo(long ms, int mode) {
        if (player != null) { try { player.seekTo(ms, mode); } catch (RuntimeException ignored) {} }
    }
    public void stopPlayback() {
        pendingUri = null;
        prepared = false;
        if (player != null) {
            try { player.stop(); } catch (RuntimeException ignored) {}
            try { player.release(); } catch (RuntimeException ignored) {}
            player = null;
        }
    }
}
