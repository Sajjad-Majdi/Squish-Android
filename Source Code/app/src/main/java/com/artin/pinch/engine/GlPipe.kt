package com.artin.pinch.engine

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max

/**
 * Decoder surface → GPU → encoder surface. Scales with a box filter when shrinking a lot (no
 * shimmering), and tone-maps HDR (PQ / HLG) down to SDR so phone HDR clips don't come out grey.
 * [grab] reads the current frame back as luma for quality checks; without an encoder surface the
 * pipe only grabs.
 */
class GlPipe(encoderSurface: Surface?, private val outW: Int, private val outH: Int, private val srcW: Int, private val srcH: Int, private val hdr: Int) {
    private val display: EGLDisplay
    private val context: EGLContext
    private val surface: EGLSurface
    private val thread = HandlerThread("pinch-frames").apply { start() }
    private val frames = Semaphore(0)
    private val tex: Int
    private val st: SurfaceTexture
    val inputSurface: Surface
    private val program: Int
    private val mtx = FloatArray(16)
    private val quad: FloatBuffer
    private val taps: Int
    private val aPos: Int
    private val aUv: Int
    private val uMtx: Int
    private val uStep: Int
    private val uTaps: Int
    private val uHdr: Int

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize" }
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            if (encoderSurface != null) EGLExt.EGL_RECORDABLE_ANDROID else EGL14.EGL_SURFACE_TYPE,
            if (encoderSurface != null) 1 else EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "eglChooseConfig" }
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        surface = if (encoderSurface != null) EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        else EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent" }

        program = link(VS, FS)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uMtx = GLES20.glGetUniformLocation(program, "uMtx")
        uStep = GLES20.glGetUniformLocation(program, "uStep")
        uTaps = GLES20.glGetUniformLocation(program, "uTaps")
        uHdr = GLES20.glGetUniformLocation(program, "uHdr")

        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        tex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        st = SurfaceTexture(tex)
        st.setOnFrameAvailableListener({ frames.release() }, Handler(thread.looper))
        inputSurface = Surface(st)

        val v = floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)
        quad = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }
        taps = tapsFor(outW, outH)
    }

    private fun tapsFor(w: Int, h: Int): Int {
        val ratio = max(srcW.toFloat() / w, srcH.toFloat() / h)
        return if (ratio < 1.3f) 1 else ceil(ratio).toInt().coerceIn(2, 4)
    }

    /** Waits for the frame the decoder just rendered and latches it. */
    fun awaitFrame() {
        if (!frames.tryAcquire(3, TimeUnit.SECONDS)) throw IllegalStateException("Decoder frame timed out")
        st.updateTexImage()
    }

    fun draw(ptsNs: Long) {
        render(outW, outH, taps)
        EGLExt.eglPresentationTimeANDROID(display, surface, ptsNs)
        EGL14.eglSwapBuffers(display, surface)
    }

    private var fbo = 0
    private var fboTex = 0
    private var fboW = 0
    private var fboH = 0
    private var pixels: ByteBuffer? = null
    private var rgba = ByteArray(0)

    /** The latched frame scaled to [w]×[h], as luma. */
    fun grab(w: Int, h: Int): ByteArray {
        if (fbo == 0 || fboW != w || fboH != h) {
            if (fbo != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
                GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0)
            }
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            fboTex = t[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glGenFramebuffers(1, t, 0)
            fbo = t[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "framebuffer" }
            fboW = w; fboH = h
            pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            rgba = ByteArray(w * h * 4)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        render(w, h, tapsFor(w, h))
        val buf = pixels!!
        buf.position(0)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        buf.position(0)
        buf.get(rgba)
        val y = ByteArray(w * h)
        Quality.lumaRgba(rgba, y)
        return y
    }

    private fun render(w: Int, h: Int, taps: Int) {
        st.getTransformMatrix(mtx)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniformMatrix4fv(uMtx, 1, false, mtx, 0)
        GLES20.glUniform2f(uStep, 1f / w / taps, 1f / h / taps)
        GLES20.glUniform1i(uTaps, taps)
        GLES20.glUniform1i(uHdr, hdr)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() {
        runCatching { inputSurface.release() }
        runCatching { st.release() }
        runCatching {
            GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
            if (fbo != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
                GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0)
            }
            GLES20.glDeleteProgram(program)
        }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        thread.quitSafely()
    }

    private fun link(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    companion object {
        const val HDR_NONE = 0
        const val HDR_PQ = 1
        const val HDR_HLG = 2

        fun hdrOf(transfer: Int) = when (transfer) {
            android.media.MediaFormat.COLOR_TRANSFER_ST2084 -> HDR_PQ
            android.media.MediaFormat.COLOR_TRANSFER_HLG -> HDR_HLG
            else -> HDR_NONE
        }

        private const val VS = """
attribute vec4 aPos;
attribute vec2 aUv;
varying vec2 vUv;
void main() { gl_Position = aPos; vUv = aUv; }
"""

        private const val FS = """
#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vUv;
uniform samplerExternalOES uTex;
uniform mat4 uMtx;
uniform vec2 uStep;
uniform int uTaps;
uniform int uHdr;

vec3 px(vec2 uv) { return texture2D(uTex, (uMtx * vec4(uv, 0.0, 1.0)).xy).rgb; }

vec3 pq(vec3 e) {
    vec3 p = pow(max(e, 0.0), vec3(1.0 / 78.84375));
    return pow(max(p - 0.8359375, 0.0) / (18.8515625 - 18.6875 * p), vec3(1.0 / 0.1593017578125)) * (10000.0 / 203.0);
}
float hlg1(float x) { return x <= 0.5 ? x * x / 3.0 : (exp((x - 0.55991073) / 0.17883277) + 0.28466892) / 12.0; }
vec3 hlg(vec3 e) {
    vec3 s = vec3(hlg1(e.r), hlg1(e.g), hlg1(e.b));
    float y = dot(s, vec3(0.2627, 0.6780, 0.0593));
    return s * pow(max(y, 1e-6), 0.2) * (1000.0 / 203.0);
}
vec3 sdr(vec3 l) {
    l = max(mat3(1.6605, -0.1246, -0.0182, -0.5876, 1.1329, -0.1006, -0.0728, -0.0083, 1.1187) * l, 0.0);
    float y = dot(l, vec3(0.2126, 0.7152, 0.0722));
    float m = y * (1.0 + y / 24.3) / (1.0 + y);
    l *= y > 0.0 ? m / y : 0.0;
    return pow(clamp(l, 0.0, 1.0), vec3(1.0 / 2.2));
}

void main() {
    vec3 c;
    if (uTaps <= 1) {
        c = px(vUv);
    } else {
        c = vec3(0.0);
        float h = float(uTaps - 1) * 0.5;
        for (int i = 0; i < 4; i++) {
            if (i >= uTaps) break;
            for (int j = 0; j < 4; j++) {
                if (j >= uTaps) break;
                c += px(vUv + (vec2(float(i), float(j)) - h) * uStep);
            }
        }
        c /= float(uTaps * uTaps);
    }
    if (uHdr == 1) c = sdr(pq(c));
    else if (uHdr == 2) c = sdr(hlg(c));
    gl_FragColor = vec4(c, 1.0);
}
"""
    }
}
