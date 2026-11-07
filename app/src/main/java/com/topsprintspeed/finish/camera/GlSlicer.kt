package com.topsprintspeed.finish.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Receives every camera frame on the GPU, cuts out the thin column under the finish line
 * (1–4 px wide) and hands it back with the frame's timestamp. Also draws the live preview.
 * Works for both normal and 120/240 fps high-speed sessions (those only allow preview/video surfaces).
 */
class GlSlicer(private val listener: Listener) {
    interface Listener {
        fun onSlice(tsMono: Long, rgba: ByteArray, w: Int, h: Int)
        fun onFrameStats(fps: Double, meanLuma: Double)
    }

    companion object {
        const val OUT_HEIGHT = 720
        const val MAX_SLICE = 4

        private const val VS = """
            attribute vec2 aPos;
            uniform vec4 uD;
            varying vec2 vD;
            void main() {
                vD = vec2(mix(uD.x, uD.z, aPos.x), mix(uD.y, uD.w, aPos.y));
                gl_Position = vec4(aPos.x * 2.0 - 1.0, 1.0 - aPos.y * 2.0, 0.0, 1.0);
            }
        """
        private const val FS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            uniform mat4 uSt;
            uniform float uRot;
            varying vec2 vD;
            void main() {
                vec2 s;
                if (uRot > 45.0 && uRot < 135.0) s = vec2(vD.y, 1.0 - vD.x);
                else if (uRot > 135.0 && uRot < 225.0) s = vec2(1.0 - vD.x, 1.0 - vD.y);
                else if (uRot > 225.0) s = vec2(1.0 - vD.y, vD.x);
                else s = vD;
                vec4 tc = uSt * vec4(s.x, 1.0 - s.y, 0.0, 1.0);
                gl_FragColor = texture2D(uTex, tc.xy);
            }
        """
    }

    private val thread = HandlerThread("gl-slicer").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var windowW = 0
    private var windowH = 0

    private var program = 0
    private var aPos = 0
    private var uD = 0
    private var uSt = 0
    private var uRot = 0
    private var uTex = 0
    private var oesTex = 0
    private var fbo = 0
    private var fboTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private val stMatrix = FloatArray(16)
    private val readBuf: ByteBuffer = ByteBuffer.allocateDirect(MAX_SLICE * OUT_HEIGHT * 4).order(ByteOrder.nativeOrder())
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }

    private var frameW = 1280
    private var frameH = 720

    @Volatile var linePos = 0.5f
    @Volatile var sliceWidth = 1
    @Volatile var rotation = 90
    @Volatile var recording = false
    @Volatile var timestampIsRealtime = false

    private var frameCount = 0L
    private var lastTs = 0L
    private var fpsEma = 0.0
    private var lastPreviewTs = 0L
    private var luma = 0.0

    init { runSync { initEgl() } }

    private fun runSync(block: () -> Unit) {
        if (Thread.currentThread() == thread) { block(); return }
        val latch = CountDownLatch(1)
        var err: Throwable? = null
        handler.post { try { block() } catch (t: Throwable) { err = t } finally { latch.countDown() } }
        latch.await(5, TimeUnit.SECONDS)
        err?.let { throw it }
    }

    private fun initEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(display, ver, 0, ver, 1)
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0)
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        pbuffer = EGL14.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)

        program = buildProgram(VS, FS)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uD = GLES20.glGetUniformLocation(program, "uD")
        uSt = GLES20.glGetUniformLocation(program, "uSt")
        uRot = GLES20.glGetUniformLocation(program, "uRot")
        uTex = GLES20.glGetUniformLocation(program, "uTex")

        val t = IntArray(2)
        GLES20.glGenTextures(2, t, 0)
        oesTex = t[0]
        fboTex = t[1]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, MAX_SLICE, OUT_HEIGHT, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        val f = IntArray(1)
        GLES20.glGenFramebuffers(1, f, 0)
        fbo = f[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw RuntimeException("Shader error: " + GLES20.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }

    /** Creates the surface the camera draws into. Call before opening a camera session. */
    fun configureInput(width: Int, height: Int): Surface {
        var result: Surface? = null
        runSync {
            surfaceTexture?.setOnFrameAvailableListener(null)
            inputSurface?.release()
            surfaceTexture?.release()
            frameW = width
            frameH = height
            val st = SurfaceTexture(oesTex)
            st.setDefaultBufferSize(width, height)
            st.setOnFrameAvailableListener({ onFrame() }, handler)
            surfaceTexture = st
            inputSurface = Surface(st)
            result = inputSurface
            frameCount = 0; lastTs = 0; fpsEma = 0.0
        }
        return result!!
    }

    fun setPreview(surface: Surface?, w: Int, h: Int) = runSync {
        if (window != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            EGL14.eglDestroySurface(display, window)
            window = EGL14.EGL_NO_SURFACE
        }
        if (surface != null && surface.isValid) {
            window = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            windowW = w
            windowH = h
        }
    }

    private fun onFrame() {
        val st = surfaceTexture ?: return
        try { st.updateTexImage() } catch (e: Exception) { return }
        var ts = st.timestamp
        if (timestampIsRealtime) ts -= (SystemClock.elapsedRealtimeNanos() - System.nanoTime())
        st.getTransformMatrix(stMatrix)

        if (lastTs > 0) {
            val dt = ts - lastTs
            if (dt > 0) {
                val inst = 1e9 / dt
                fpsEma = if (fpsEma == 0.0) inst else fpsEma * 0.95 + inst * 0.05
            }
        }
        lastTs = ts
        frameCount++
        val w = sliceWidth.coerceIn(1, MAX_SLICE)
        val statsFrame = frameCount % 24L == 0L

        if (recording || statsFrame) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glViewport(0, 0, w, OUT_HEIGHT)
            val dispW = if (rotation == 90 || rotation == 270) frameH else frameW
            val half = (w / 2f) / dispW
            draw(linePos - half, 0f, linePos + half, 1f)
            readBuf.position(0)
            GLES20.glReadPixels(0, 0, w, OUT_HEIGHT, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readBuf)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            if (recording) {
                val arr = ByteArray(w * OUT_HEIGHT * 4)
                readBuf.position(0)
                readBuf.get(arr)
                listener.onSlice(ts, arr, w, OUT_HEIGHT)
            }
            if (statsFrame) {
                var sum = 0.0
                var count = 0
                var i = 0
                val total = w * OUT_HEIGHT
                while (i < total) {
                    val o = i * 4
                    val r = readBuf.get(o).toInt() and 0xFF
                    val g = readBuf.get(o + 1).toInt() and 0xFF
                    val b = readBuf.get(o + 2).toInt() and 0xFF
                    sum += 0.299 * r + 0.587 * g + 0.114 * b
                    count++
                    i += 7
                }
                luma = if (count > 0) sum / count else 0.0
                listener.onFrameStats(fpsEma, luma)
            }
        }

        if (window != EGL14.EGL_NO_SURFACE && ts - lastPreviewTs > 30_000_000L) {
            lastPreviewTs = ts
            if (EGL14.eglMakeCurrent(display, window, window, context)) {
                GLES20.glViewport(0, 0, windowW, windowH)
                draw(0f, 0f, 1f, 1f)
                EGL14.eglSwapBuffers(display, window)
                EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            }
        }
    }

    private fun draw(x0: Float, y0: Float, x1: Float, y1: Float) {
        GLES20.glUseProgram(program)
        quad.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, quad)
        GLES20.glUniform4f(uD, x0, y0, x1, y1)
        GLES20.glUniformMatrix4fv(uSt, 1, false, stMatrix, 0)
        GLES20.glUniform1f(uRot, rotation.toFloat())
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() {
        runSync {
            surfaceTexture?.setOnFrameAvailableListener(null)
            inputSurface?.release()
            surfaceTexture?.release()
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, pbuffer)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        thread.quitSafely()
    }
}
