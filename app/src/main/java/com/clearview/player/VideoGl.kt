package com.clearview.player

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Takes decoded video frames (the player renders into [captureSurface]) and draws them to [outSurface]:
 *
 *   frame -> [optional NPU 4x upscale] -> picture filters -> screen
 *
 * The AI step sees the real video frame, not a screenshot, so it works on the actual source detail.
 * Everything runs on one GL thread; the public methods are safe to call from any thread.
 */
class VideoGl(private val outSurface: Surface) {

    private val thread = HandlerThread("gl-video").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var oesTex = 0
    lateinit var surfaceTexture: SurfaceTexture
        private set
    lateinit var captureSurface: Surface
        private set

    private val stMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val ident = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(
                floatArrayOf(
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 1f, 0f,
                    -1f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f,
                )
            )
            position(0)
        }

    @Volatile private var released = false
    @Volatile private var videoW = 1280
    @Volatile private var videoH = 720
    @Volatile private var aspect = 16f / 9f
    /** false = whole picture visible (black bars), true = fill the screen (crops the edges). */
    @Volatile var fill = false
        set(v) { field = v; requestRedraw() }
    private var hasFrame = false

    private var progOes = 0
    private var progTex = 0
    private val locs = HashMap<Int, HashMap<String, Int>>()

    // Output of the AI step, fed to the filters.
    private var enhFbo = 0
    private var enhTex = 0
    private var enhW = 0
    private var enhH = 0

    private var upscaler: NpuUpscaler? = null
    private var upscalerPath: String? = null
    private var ai: AiPass? = null
    private var aiFailedPath: String? = null
    private var aiGeneration = 0
    private var drawErrors = 0

    /** Blocks briefly until the GL thread has built the context and the video surface exists. */
    fun start() {
        val latch = CountDownLatch(1)
        handler.post {
            try {
                initEgl()
                initGl()
            } catch (t: Throwable) {
                ClearViewApp.record(ClearViewApp.instance, "VideoGl.init", t)
                AiSettings.status = "GL setup failed: ${t.javaClass.simpleName}: ${t.message}"
            }
            latch.countDown()
        }
        latch.await()
    }

    fun setVideoSize(w: Int, h: Int, pixelRatio: Float) {
        if (w <= 0 || h <= 0) return
        videoW = w
        videoH = h
        aspect = w * (if (pixelRatio > 0f) pixelRatio else 1f) / h
        requestRedraw()
    }

    /** Redraws the last frame (used when a setting changes while paused). */
    fun requestRedraw() {
        if (released) return
        handler.post { if (!released) drawFrame(false) }
    }

    fun release() {
        released = true
        handler.post {
            runCatching { surfaceTexture.setOnFrameAvailableListener(null) }
            runCatching { captureSurface.release() }
            runCatching { surfaceTexture.release() }
            runCatching { ai?.release() }
            runCatching { upscaler?.close() }
            ai = null
            upscaler = null
            if (progOes != 0) GLES30.glDeleteProgram(progOes)
            if (progTex != 0) GLES30.glDeleteProgram(progTex)
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            thread.quitSafely()
        }
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) { "eglInitialize failed" }
        val cfgAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 0x0040, // EGL_OPENGL_ES3_BIT_KHR
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, cfgAttribs, 0, configs, 0, 1, num, 0)
        check(num[0] > 0) { "No EGL config" }
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], outSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }
        EGL14.eglSwapInterval(eglDisplay, 1)
    }

    private fun initGl() {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        oesTex = tex[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(oesTex).apply {
            setDefaultBufferSize(videoW, videoH)
            setOnFrameAvailableListener({ drawFrame(true) }, handler)
        }
        captureSurface = Surface(surfaceTexture)

        progOes = buildGlProgram(VERTEX_SRC, lookFragment(true))
        progTex = buildGlProgram(VERTEX_SRC, lookFragment(false))
    }

    private fun loc(p: Int, name: String): Int =
        locs.getOrPut(p) { HashMap() }.getOrPut(name) { GLES30.glGetUniformLocation(p, name) }

    private fun ensureEnh(w: Int, h: Int) {
        if (enhFbo != 0 && enhW == w && enhH == h) return
        if (enhTex != 0) GLES30.glDeleteTextures(1, intArrayOf(enhTex), 0)
        if (enhFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(enhFbo), 0)
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0); enhTex = t[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, enhTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glGenFramebuffers(1, t, 0); enhFbo = t[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, enhFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, enhTex, 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "FBO incomplete" }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        enhW = w; enhH = h
    }

    /** Never lets an exception escape the GL thread (that would kill the whole app). */
    private fun drawFrame(update: Boolean) {
        if (released) return
        try {
            drawFrameUnsafe(update)
        } catch (t: Throwable) {
            if (released) return
            Log.e(TAG, "drawFrame failed", t)
            if (++drawErrors <= 3) runCatching { ClearViewApp.record(ClearViewApp.instance, "drawFrame", t) }
            AiSettings.status = "Draw error (skipped frame): ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun drawFrameUnsafe(update: Boolean) {
        if (update) {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(stMatrix)
            hasFrame = true
        }
        val sz = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, sz, 0)
        val outW = sz[0]
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, sz, 0)
        val outH = sz[0]
        if (outW <= 0 || outH <= 0) return

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, outW, outH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (!hasFrame) {
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            return
        }

        // Where the picture goes on screen (letterboxed, or cropped to fill).
        val a = aspect
        val screenA = outW.toFloat() / outH
        val widthLimited = if (fill) screenA > a else screenA <= a
        val vw: Int
        val vh: Int
        if (widthLimited) {
            vw = outW; vh = (outW / a).roundToInt()
        } else {
            vh = outH; vw = (outH * a).roundToInt()
        }
        val vx = (outW - vw) / 2
        val vy = (outH - vh) / 2

        var usedAi = false
        if (AiSettings.enabled && AiSettings.modelPath != null) usedAi = tryAi(vw, vh)
        if (!AiSettings.enabled) AiSettings.stats = ""

        if (usedAi) {
            drawLook(false, enhTex, ident, 1f / vw, 1f / vh, vx, vy, vw, vh, split = false)
        } else {
            drawLook(true, oesTex, stMatrix, 1f / videoW, 1f / videoH, vx, vy, vw, vh, split = VideoLook.compare)
        }
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    /** NPU path: video frame -> 4x upscale into [enhTex]. Returns false if the AI step could not run. */
    private fun tryAi(vw: Int, vh: Int): Boolean {
        val path = AiSettings.modelPath ?: return false
        if (aiGeneration != AiSettings.generation) {
            aiGeneration = AiSettings.generation
            aiFailedPath = null
        }
        if (path == aiFailedPath) return false
        try {
            if (upscaler == null || upscalerPath != path) {
                ai?.release(); ai = null
                upscaler?.close(); upscaler = null
                AiSettings.status = "AI: loading model on the NPU..."
                val up = NpuUpscaler(path, AiSettings.nativeLibDir, AiSettings.allowCpu)
                upscaler = up
                upscalerPath = path
                AiSettings.status = "AI running: " + up.describe()
                Log.i(TAG, AiSettings.status)
            }
            val up = upscaler!!
            // Grid in 128 px units: k rows, and as many columns as the video's shape needs.
            val k = when (AiSettings.quality) { 0 -> 2; 1 -> 3; else -> 12 }
            val unitsW = max(1, (k * aspect).roundToInt())
            val c = max(1, unitsW * 128 / up.inW)
            val r = max(1, k * 128 / up.inH)
            var pass = ai
            if (pass == null || pass.cols != c || pass.rows != r) {
                pass?.release()
                pass = AiPass(up, c, r).also { it.init() }
                ai = pass
            }
            AiSettings.viewMode = if (VideoLook.compare) 1 else 0
            ensureEnh(vw, vh)
            pass.render(oesTex, stMatrix, quad, enhFbo, 0, 0, vw, vh)
            return true
        } catch (t: Throwable) {
            aiFailedPath = path
            ai?.let { runCatching { it.release() } }
            ai = null
            runCatching { ClearViewApp.record(ClearViewApp.instance, "AI path", t) }
            AiSettings.status = "AI failed, using filters only: ${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "AI path failed", t)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            return false
        }
    }

    private fun drawLook(
        oes: Boolean, tex: Int, matrix: FloatArray, texelW: Float, texelH: Float,
        vx: Int, vy: Int, vw: Int, vh: Int, split: Boolean,
    ) {
        val p = if (oes) progOes else progTex
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(vx, vy, vw, vh)
        GLES30.glUseProgram(p)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(if (oes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(loc(p, "uTex"), 0)
        GLES30.glUniformMatrix4fv(loc(p, "uStMatrix"), 1, false, matrix, 0)
        GLES30.glUniform2f(loc(p, "uTexel"), texelW, texelH)
        GLES30.glUniform4f(loc(p, "uVp"), vx.toFloat(), vy.toFloat(), vw.toFloat(), vh.toFloat())
        GLES30.glUniform1f(loc(p, "uSplit"), if (split) 1f else 0f)
        GLES30.glUniform1f(loc(p, "uClean"), VideoLook.clean)
        GLES30.glUniform1f(loc(p, "uSharp"), VideoLook.sharpen)
        GLES30.glUniform1f(loc(p, "uClarity"), VideoLook.clarity)
        GLES30.glUniform1f(loc(p, "uContrast"), VideoLook.contrast)
        GLES30.glUniform1f(loc(p, "uSat"), VideoLook.saturation)
        GLES30.glUniform1f(loc(p, "uFilmic"), VideoLook.filmic)
        GLES30.glUniform1f(loc(p, "uExposure"), VideoLook.exposure)
        GLES30.glUniform1f(loc(p, "uGrade"), VideoLook.grade)
        GLES30.glUniform1f(loc(p, "uVibrance"), VideoLook.vibrance)
        GLES30.glUniform1f(loc(p, "uHdr"), VideoLook.hdr)
        GLES30.glUniform1f(loc(p, "uBlack"), VideoLook.blackPt)
        quad.position(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, quad)
        GLES30.glEnableVertexAttribArray(0)
        quad.position(2)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, quad)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    companion object {
        private const val TAG = "ClearViewGl"

        internal const val VERTEX_SRC = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
uniform mat4 uStMatrix;
out vec2 vUv;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vUv = (uStMatrix * vec4(aUv, 0.0, 1.0)).xy;
}
"""

        /** The picture filter. [oes] selects the external (decoder) texture or a normal 2D texture. */
        internal fun lookFragment(oes: Boolean): String =
            "#version 300 es\n" +
                (if (oes) "#extension GL_OES_EGL_image_external_essl3 : require\n" else "") +
                "precision highp float;\n" +
                (if (oes) "uniform samplerExternalOES uTex;\n" else "uniform sampler2D uTex;\n") +
                LOOK_BODY

        internal const val LOOK_BODY = """
uniform vec2 uTexel;
uniform float uClean;
uniform float uSharp;
uniform float uClarity;
uniform float uContrast;
uniform float uSat;
uniform float uFilmic;
uniform float uExposure;
uniform float uGrade;
uniform float uVibrance;
uniform float uHdr;
uniform float uBlack;
uniform float uSplit;
uniform vec4 uVp;

const vec2 DIRS[8] = vec2[8](
    vec2(1.0, 0.0), vec2(0.7071, 0.7071), vec2(0.0, 1.0), vec2(-0.7071, 0.7071),
    vec2(-1.0, 0.0), vec2(-0.7071, -0.7071), vec2(0.0, -1.0), vec2(0.7071, -0.7071)
);

in vec2 vUv;
out vec4 outColor;

vec3 tap(vec2 off) { return texture(uTex, vUv + off * uTexel).rgb; }
vec3 tapUv(vec2 off) { return texture(uTex, vUv + off).rgb; }
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

void main() {
    vec3 raw = tap(vec2(0.0));
    if (uSplit > 0.5 && (gl_FragCoord.x - uVp.x) / uVp.z > 0.5) {
        outColor = vec4(raw, 1.0);
        return;
    }

    // Clean: edge-preserving smoothing. Neighbours that look alike are averaged (softens compression blocks
    // and noise); neighbours that differ a lot (real edges) get almost no weight and stay sharp.
    vec3 e = raw;
    if (uClean > 0.001) {
        vec3 acc = raw;
        float ws = 1.0;
        for (int i = 0; i < 8; i++) {
            vec3 s0 = tap(DIRS[i] * 1.5);
            vec3 d = s0 - raw;
            float wgt = exp(-dot(d, d) * 120.0);
            acc += s0 * wgt;
            ws += wgt;
        }
        e = mix(raw, acc / ws, uClean);
    }

    vec3 n = tap(vec2(0.0, -1.0));
    vec3 s = tap(vec2(0.0, 1.0));
    vec3 w = tap(vec2(-1.0, 0.0));
    vec3 ea = tap(vec2(1.0, 0.0));

    // Contrast-adaptive sharpening: back off where the local range is already high.
    vec3 mn = min(e, min(min(n, s), min(w, ea)));
    vec3 mx = max(e, max(max(n, s), max(w, ea)));
    vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, vec3(1e-4)), 0.0, 1.0));
    float peak = mix(-0.125, -0.2, uSharp);
    vec3 wgt = amp * peak;
    vec3 sharp = (e + (n + s + w + ea) * wgt) / (1.0 + 4.0 * wgt);
    vec3 col = mix(e, sharp, step(0.001, uSharp));

    // Clarity: boost mid-frequency detail.
    vec3 blur = (tap(vec2(-2.0, 0.0)) + tap(vec2(2.0, 0.0)) + tap(vec2(0.0, -2.0)) + tap(vec2(0.0, 2.0))) * 0.25;
    float midMask = 1.0 - abs(luma(col) * 2.0 - 1.0);
    col += (col - blur) * uClarity * (0.5 + 0.5 * midMask);
    col = clamp(col, 0.0, 1.0);

    // HDR-style look (SDR emulation): local tone mapping, then highlight punch.
    if (uHdr > 0.001) {
        vec3 wide = (tapUv(vec2(-0.02, -0.035)) + tapUv(vec2(0.02, -0.035)) + tapUv(vec2(-0.02, 0.035)) + tapUv(vec2(0.02, 0.035))
                   + tapUv(vec2(-0.04, 0.0)) + tapUv(vec2(0.04, 0.0)) + tapUv(vec2(0.0, -0.07)) + tapUv(vec2(0.0, 0.07))) * 0.125;
        float localL = luma(wide);
        float gain = clamp(pow(0.42 / (localL + 0.04), 0.5), 0.92, 1.7);
        col *= mix(1.0, gain, uHdr * 0.55);
        float lc = luma(col);
        float hi = smoothstep(0.62, 1.0, lc);
        col += col * hi * uHdr * 0.30;
        col = mix(col, col / (1.0 + max(col - 0.85, 0.0) * 1.2), uHdr);
    }

    // Exposure + gentle S-curve + contrast.
    col *= uExposure;
    vec3 curved = clamp(col * col * (3.0 - 2.0 * col), 0.0, 1.0);
    col = mix(clamp(col, 0.0, 1.0), curved, uFilmic);
    col = (col - 0.5) * uContrast + 0.5;

    // Split-tone grade: cool shadows, warm highlights.
    float l0 = luma(col);
    float shadowsW = 1.0 - smoothstep(0.0, 0.55, l0);
    float highsW = smoothstep(0.45, 1.0, l0);
    col += uGrade * (vec3(-0.03, 0.03, 0.08) * shadowsW + vec3(0.08, 0.03, -0.05) * highsW);

    // Vibrance, then saturation.
    float chromaMax = max(col.r, max(col.g, col.b));
    float chromaMin = min(col.r, min(col.g, col.b));
    float satNow = chromaMax - chromaMin;
    col = mix(vec3(luma(col)), col, 1.0 + uVibrance * (1.0 - satNow));
    col = mix(vec3(luma(col)), col, uSat);

    // Deeper blacks.
    float bp = uBlack * 0.07;
    col = max(col - bp, 0.0) / (1.0 - bp);

    outColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
"""
    }
}
