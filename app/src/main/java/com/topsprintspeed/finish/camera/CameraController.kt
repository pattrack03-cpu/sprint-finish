package com.topsprintspeed.finish.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import com.topsprintspeed.finish.TimeMath
import kotlin.math.abs
import kotlin.math.roundToInt

data class CameraStatus(
    val open: Boolean = false,
    val checking: Boolean = false,
    val checked: Boolean = false,
    val highSpeed: Boolean = false,
    val fps: Int = 0,
    val measuredFps: Double = 0.0,
    val size: String = "",
    val shutterNs: Long = 0L,
    val iso: Int = 0,
    val manualControl: Boolean = false,
    val focusLocked: Boolean = false,
    val brightness: Double = 0.0,
    val rotation: Int = 90,
    val issues: List<String> = emptyList(),
) {
    val ok get() = checked && issues.isEmpty()
    val shutterLabel: String get() = if (shutterNs <= 0) "—" else "1/" + (1e9 / shutterNs).roundToInt()
}

/**
 * Opens the back camera at 240 or 120 fps (high-speed mode) when the phone supports it, then runs
 * "auto setup": lets auto-exposure settle, switches to a fast fixed shutter with matching ISO,
 * locks focus, and checks that the phone really applied it. Problems end up in [CameraStatus.issues].
 */
class CameraController(
    context: Context,
    private val slicer: GlSlicer,
    private val onStatus: (CameraStatus) -> Unit,
) {
    private val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var useHighSpeed = false
    private var fpsRange = Range(30, 30)
    private var fpsTarget = 30
    private var shutterWanted = 0
    private var manualSupported = false
    private var isoRange = Range(100, 1600)
    private var expRange = Range(100_000L, 100_000_000L)
    private var baseIssues = mutableListOf<String>()
    private var generation = 0

    private var manualExposure: Pair<Long, Int>? = null
    private var focusLocked = false
    private var focusDistance = 0f

    @Volatile private var lastExposureNs = 0L
    @Volatile private var lastIso = 0
    @Volatile private var lastFocus = 0f
    @Volatile var measuredFps = 0.0
    @Volatile var meanLuma = 0.0

    private var status = CameraStatus()

    private fun publish(s: CameraStatus) { status = s; onStatus(s) }

    fun open(fps: Int, shutter: Int, rotationOverride: Int) {
        handler.post {
            closeInternal()
            generation++
            openInternal(fps, shutter, rotationOverride, generation)
        }
    }

    fun rerunAutoSetup() { handler.post { if (session != null) startAutoSetup(generation) } }

    fun close() { handler.post { closeInternal() } }

    fun release() {
        handler.post { closeInternal() }
        thread.quitSafely()
    }

    private fun closeInternal() {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        session = null
        device = null
        slicer.recording = false
        publish(status.copy(open = false, checking = false))
    }

    private fun nearest(sizes: Array<Size>, w: Int, h: Int): Size =
        sizes.minByOrNull { abs(it.width * it.height - w * h) + abs(it.width - w) } ?: Size(w, h)

    @SuppressLint("MissingPermission")
    private fun openInternal(fps: Int, shutter: Int, rotationOverride: Int, gen: Int) {
        val issues = mutableListOf<String>()
        val id = try {
            mgr.cameraIdList.firstOrNull {
                mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
            } ?: mgr.cameraIdList.firstOrNull()
        } catch (e: Exception) { null }
        if (id == null) {
            publish(CameraStatus(checked = true, issues = listOf("No camera found")))
            return
        }
        val ch = mgr.getCameraCharacteristics(id)
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val hsCap = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)
        manualSupported = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { isoRange = it }
        ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { expRange = it }
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map == null) {
            publish(CameraStatus(checked = true, issues = listOf("Camera gave no video formats")))
            return
        }

        var size = Size(1280, 720)
        useHighSpeed = false
        if (fps >= 120 && hsCap) {
            fun findHs(f: Int): Size? = map.highSpeedVideoSizes
                .sortedBy { abs(it.width * it.height - 1280 * 720) }
                .firstOrNull { s -> map.getHighSpeedVideoFpsRangesFor(s).any { it.lower == f && it.upper == f } }
            val exact = findHs(fps)
            val pick = exact ?: if (fps > 120) findHs(120) else null
            if (pick != null) {
                size = pick
                useHighSpeed = true
                fpsTarget = if (exact != null) fps else 120
                fpsRange = Range(fpsTarget, fpsTarget)
                if (exact == null) issues += "$fps fps not offered by this phone — using 120 fps"
            }
        }
        if (!useHighSpeed) {
            size = nearest(map.getOutputSizes(android.graphics.SurfaceTexture::class.java), 1280, 720)
            val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: arrayOf(Range(30, 30))
            val best = ranges.maxWithOrNull(compareBy<Range<Int>>({ it.upper }, { it.lower })) ?: Range(30, 30)
            fpsRange = best
            fpsTarget = best.upper
            if (fps >= 120) issues += "High-speed (120/240 fps) not available — using ${best.upper} fps"
        }
        baseIssues = issues
        shutterWanted = shutter

        val tsSource = ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        slicer.timestampIsRealtime = tsSource == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val sensorRot = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val rot = if (rotationOverride >= 0) rotationOverride else sensorRot
        slicer.rotation = rot

        val surf = slicer.configureInput(size.width, size.height)
        surface = surf
        manualExposure = null
        focusLocked = false
        publish(CameraStatus(open = false, checking = true, fps = fpsTarget, highSpeed = useHighSpeed,
            size = "${size.width}×${size.height}", rotation = rot, manualControl = manualSupported))

        try {
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (gen != generation) { d.close(); return }
                    device = d
                    createSession(d, surf, gen)
                }
                override fun onDisconnected(d: CameraDevice) { d.close(); if (device == d) device = null }
                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    if (device == d) device = null
                    publish(status.copy(open = false, checking = false, checked = true,
                        issues = listOf("Camera error $error — close other camera apps and reopen")))
                }
            }, handler)
        } catch (e: Exception) {
            publish(status.copy(checking = false, checked = true, issues = listOf("Couldn't open camera: ${e.message}")))
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession(d: CameraDevice, surf: Surface, gen: Int) {
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (gen != generation) { s.close(); return }
                session = s
                publish(status.copy(open = true))
                startAutoSetup(gen)
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (useHighSpeed) {
                    // Fall back to a normal session.
                    useHighSpeed = false
                    fpsRange = Range(30, 30)
                    fpsTarget = 30
                    baseIssues = (baseIssues + "High-speed session refused — using 30 fps").toMutableList()
                    createSession(d, surf, gen)
                } else {
                    publish(status.copy(checking = false, checked = true, issues = listOf("Camera session failed")))
                }
            }
        }
        try {
            if (useHighSpeed) d.createConstrainedHighSpeedCaptureSession(listOf(surf), cb, handler)
            else d.createCaptureSession(listOf(surf), cb, handler)
        } catch (e: Exception) {
            publish(status.copy(checking = false, checked = true, issues = listOf("Camera session failed: ${e.message}")))
        }
    }

    private val captureCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExposureNs = it }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastFocus = it }
        }
    }

    private fun submit() {
        val d = device ?: return
        val s = session ?: return
        val surf = surface ?: return
        try {
            val b = d.createCaptureRequest(if (useHighSpeed) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(surf)
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            val m = manualExposure
            if (m != null) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, m.first)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, m.second)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / fpsTarget)
            } else {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            if (focusLocked && manualSupported && focusDistance > 0f) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
            } else {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            if (useHighSpeed) {
                val hs = s as CameraConstrainedHighSpeedCaptureSession
                hs.setRepeatingBurst(hs.createHighSpeedRequestList(b.build()), captureCb, handler)
            } else {
                s.setRepeatingRequest(b.build(), captureCb, handler)
            }
        } catch (e: Exception) {
            baseIssues = (baseIssues + "Camera rejected settings: ${e.message}").toMutableList()
        }
    }

    /** Step 1: let auto exposure/focus settle. */
    private fun startAutoSetup(gen: Int) {
        manualExposure = null
        focusLocked = false
        publish(status.copy(checking = true, checked = false, issues = emptyList()))
        submit()
        handler.postDelayed({ if (gen == generation) lockSettings(gen) }, 1500)
    }

    /** Step 2: fixed fast shutter + matching ISO, focus locked where she phone allows it. */
    private fun lockSettings(gen: Int) {
        val issues = baseIssues.toMutableList()
        if (manualSupported && lastExposureNs > 0 && lastIso > 0) {
            val isoMax = minOf(isoRange.upper, 3200)
            val candidates = if (shutterWanted > 0) listOf(shutterWanted) else listOf(2000, 1000, 500)
            val (shutter, iso) = TimeMath.chooseShutter(lastExposureNs, lastIso, candidates, isoMax)
            val exp = (1_000_000_000L / shutter).coerceIn(expRange.lower, expRange.upper)
            manualExposure = exp to iso.coerceIn(isoRange.lower, isoMax)
            focusDistance = lastFocus
            focusLocked = true
            submit()
        } else if (!manualSupported) {
            issues += "This phone doesn't allow setting the shutter speed — using auto exposure"
        }
        handler.postDelayed({ if (gen == generation) verify(gen, issues) }, 1500)
    }

    /** Step 3: check what the camera actually did. */
    private fun verify(gen: Int, issues: MutableList<String>) {
        val m = manualExposure
        if (m != null && lastExposureNs > 0) {
            val diff = abs(lastExposureNs - m.first).toDouble() / m.first
            if (diff > 0.25) issues += "Shutter not applied (camera is using 1/${(1e9 / lastExposureNs).roundToInt()} s)"
        } else if (lastExposureNs > 2_000_000L) {
            issues += "Shutter is slow (1/${(1e9 / lastExposureNs).roundToInt()} s) — runners may blur"
        }
        if (measuredFps > 0 && measuredFps < fpsTarget * 0.85) {
            issues += "Frame rate only ${measuredFps.roundToInt()} fps (wanted $fpsTarget)"
        }
        if (measuredFps == 0.0) issues += "No frames from the camera"
        if (meanLuma in 0.1..35.0) issues += "Image too dark — more light, or a slower shutter"
        if (meanLuma > 235.0) issues += "Image too bright — try a faster shutter"
        publish(status.copy(
            open = true, checking = false, checked = true,
            measuredFps = measuredFps, shutterNs = lastExposureNs, iso = lastIso,
            focusLocked = focusLocked, brightness = meanLuma, issues = issues.distinct(),
        ))
    }
}
