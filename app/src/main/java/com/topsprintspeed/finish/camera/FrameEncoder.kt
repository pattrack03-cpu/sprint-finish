package com.topsprintspeed.finish.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface

/**
 * Hardware H.264 encoder fed straight from the camera. In 120/240 fps high-speed mode Android only
 * delivers every frame to a video-encoder surface (previews get ~30 fps), so this is how full-rate
 * frames are captured. Frames keep the camera's own timestamps.
 */
class FrameEncoder(
    width: Int,
    height: Int,
    fps: Int,
    private val timestampIsRealtime: Boolean,
    private val listener: Listener,
) {
    interface Listener {
        fun onConfig(config: ByteArray)
        fun onEncodedFrame(tsMono: Long, data: ByteArray, key: Boolean)
        fun onEncoderFps(fps: Double)
    }

    private val thread = HandlerThread("encoder").apply { start() }
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val inputSurface: Surface

    @Volatile var wantFrames = false
    private var lastPts = 0L
    private var fpsEma = 0.0
    private var count = 0L
    @Volatile private var released = false

    init {
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        val bitrate = (width.toLong() * height * fps / 4).coerceIn(8_000_000L, 80_000_000L).toInt()
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        f.setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
        if (Build.VERSION.SDK_INT >= 29) f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, index: Int) {}

            override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (released) return
                try {
                    val buf = c.getOutputBuffer(index)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            val cfg = ByteArray(info.size)
                            buf.get(cfg)
                            listener.onConfig(cfg)
                        } else {
                            val pts = info.presentationTimeUs
                            if (lastPts > 0 && pts > lastPts) {
                                val inst = 1e6 / (pts - lastPts)
                                fpsEma = if (fpsEma == 0.0) inst else fpsEma * 0.95 + inst * 0.05
                            }
                            lastPts = pts
                            if (++count % 30L == 0L) listener.onEncoderFps(fpsEma)
                            if (wantFrames) {
                                val data = ByteArray(info.size)
                                buf.get(data)
                                var tsNs = pts * 1000L
                                if (timestampIsRealtime) tsNs -= (SystemClock.elapsedRealtimeNanos() - System.nanoTime())
                                listener.onEncodedFrame(tsNs, data, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                            }
                        }
                    }
                    c.releaseOutputBuffer(index, false)
                } catch (_: IllegalStateException) {
                }
            }

            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {}
            override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {}
        }, Handler(thread.looper))
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
    }

    /** Ask for a key frame now, so a new clip can start immediately. */
    fun requestKeyFrame() {
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: Exception) {}
    }

    fun release() {
        if (released) return
        released = true
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        try { inputSurface.release() } catch (_: Exception) {}
        thread.quitSafely()
    }
}
