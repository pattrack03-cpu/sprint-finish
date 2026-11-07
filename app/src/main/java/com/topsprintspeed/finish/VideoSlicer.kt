package com.topsprintspeed.finish

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

/**
 * Builds the photo-finish slices for a recorded (video) clip: decodes every frame and cuts out the
 * finish-line column. The column position uses the same display->camera mapping as the live preview,
 * so the line lands where it was on screen.
 */
object VideoSlicer {

    fun slice(rec: Recording, clip: Clip) {
        val n = clip.frames.size
        if (n == 0) return
        val config = rec.codecConfig ?: throw IllegalStateException("Recording has no video settings")
        val w = clip.sliceW
        val h = clip.height
        val out = arrayOfNulls<ByteArray>(n)
        val map = ColumnMap(rec, clip)

        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, rec.frameW, rec.frameH)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            dec.configure(fmt, null, null, 0)
            dec.start()
            val info = MediaCodec.BufferInfo()
            var fed = -1 // -1 = config not sent yet
            var eosSent = false
            var done = false
            var idle = 0
            while (!done && idle < 400) {
                if (!eosSent) {
                    val ii = dec.dequeueInputBuffer(2000)
                    if (ii >= 0) {
                        val b = dec.getInputBuffer(ii)!!
                        b.clear()
                        when {
                            fed < 0 -> {
                                b.put(config)
                                dec.queueInputBuffer(ii, 0, config.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                                fed = 0
                            }
                            fed < n -> {
                                val f = clip.frames[fed]
                                b.put(f)
                                dec.queueInputBuffer(ii, 0, f.size, (fed + 1) * 1000L, 0)
                                fed++
                            }
                            else -> {
                                dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                eosSent = true
                            }
                        }
                    }
                }
                val oi = dec.dequeueOutputBuffer(info, 2000)
                if (oi >= 0) {
                    idle = 0
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                    if (info.size > 0 || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) {
                        val k = (info.presentationTimeUs / 1000L).toInt() - 1
                        if (k in 0 until n) {
                            val img = dec.getOutputImage(oi)
                            if (img != null) {
                                out[k] = map.extract(img)
                                img.close()
                            }
                        }
                    }
                    dec.releaseOutputBuffer(oi, false)
                } else if (eosSent) {
                    idle++
                }
            }
        } finally {
            try { dec.stop() } catch (_: Exception) {}
            dec.release()
        }
        val blank = ByteArray(w * h * 4)
        clip.slices.clear()
        for (k in 0 until n) clip.slices.add(out[k] ?: blank)
    }

    /** Precomputed display->camera-buffer sampling positions for the finish-line column. */
    private class ColumnMap(rec: Recording, clip: Clip) {
        val w = clip.sliceW
        val h = clip.height
        // Buffer coordinates (0..1) for each output pixel, rows top-first.
        val u = FloatArray(w * h)
        val v = FloatArray(w * h)

        init {
            val m = rec.transform
            val portrait = (rec.sensorOrientation + rec.extraRotation) % 180 == 90
            val dispW = if (portrait) rec.frameH else rec.frameW
            for (r in 0 until h) {
                val dy = (r + 0.5f) / h
                for (j in 0 until w) {
                    val dx = clip.linePos + (j + 0.5f - w / 2f) / dispW
                    // Same steps as the preview shader: extra rotation, then the camera texture transform.
                    val (sx, sy) = when (rec.extraRotation) {
                        90 -> dy to (1f - dx)
                        180 -> (1f - dx) to (1f - dy)
                        270 -> (1f - dy) to dx
                        else -> dx to dy
                    }
                    val tx = sx
                    val ty = 1f - sy
                    val i = r * w + j
                    u[i] = m[0] * tx + m[4] * ty + m[12]
                    v[i] = m[1] * tx + m[5] * ty + m[13]
                }
            }
        }

        fun extract(img: Image): ByteArray {
            val crop = img.cropRect
            val cw = crop.width()
            val ch = crop.height()
            val yP = img.planes[0]
            val uP = img.planes[1]
            val vP = img.planes[2]
            val yB = yP.buffer
            val uB = uP.buffer
            val vB = vP.buffer
            val out = ByteArray(w * h * 4)
            for (r in 0 until h) {
                val rr = h - 1 - r // stored bottom row first, like the live path
                for (j in 0 until w) {
                    val i = r * w + j
                    val px = (crop.left + (u[i] * cw).toInt().coerceIn(0, cw - 1))
                    val py = (crop.top + (v[i] * ch).toInt().coerceIn(0, ch - 1))
                    val yv = (yB.get(py * yP.rowStride + px * yP.pixelStride).toInt() and 0xFF) - 16
                    val cx = px / 2
                    val cy = py / 2
                    val uv = (uB.get(cy * uP.rowStride + cx * uP.pixelStride).toInt() and 0xFF) - 128
                    val vv = (vB.get(cy * vP.rowStride + cx * vP.pixelStride).toInt() and 0xFF) - 128
                    val yy = 1.164f * yv
                    val red = (yy + 1.596f * vv).toInt().coerceIn(0, 255)
                    val green = (yy - 0.392f * uv - 0.813f * vv).toInt().coerceIn(0, 255)
                    val blue = (yy + 2.017f * uv).toInt().coerceIn(0, 255)
                    val o = (rr * w + j) * 4
                    out[o] = red.toByte()
                    out[o + 1] = green.toByte()
                    out[o + 2] = blue.toByte()
                    out[o + 3] = 0xFF.toByte()
                }
            }
            return out
        }
    }
}
