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
import android.os.Build
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
    val sharpMode: Boolean = false,
    val fps: Int = 0,
    val measuredFps: Double = 0.0,
    val size: String = "",
    val shutterNs: Long = 0L,
    val targetShutterNs: Long = 0L,
    val iso: Int = 0,
    val evComp: Int = 0,
    val manualControl: Boolean = false,
    val focusLocked: Boolean = false,
    val brightness: Double = 0.0,
    val rotation: Int = 0,
    /** Problems that should stop a race until fixed (or the user starts anyway). */
    val issues: List<String> = emptyList(),
    /** Things worth knowing that don't block a race. */
    val notes: List<String> = emptyList(),
) {
    val ok get() = checked && issues.isEmpty()
    val shutterLabel: String get() = if (shutterNs <= 0) "—" else "1/" + (1e9 / shutterNs).roundToInt()
}

/**
 * Opens the back camera and runs "auto setup".
 *
 * High-speed mode (120/240 fps): Android's constrained high-speed sessions only honour auto exposure —
 * turning auto exposure off to force a shutter makes many phones (Samsung included) fall back to 30 fps.
 * So in high-speed mode we keep auto exposure ON and push it towards a faster shutter with negative
 * exposure compensation, stopping before the picture gets too dark. The frame rate itself caps the
 * slowest shutter (1/240 s at 240 fps).
 *
 * Sharpness priority: a normal session with a truly fixed shutter (1/1000, 1/2000…) at the highest frame
 * rate the phone allows outside high-speed mode (often 60 fps).
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
    private var sharpMode = false
    private var fpsRange = Range(30, 30)
    private var fpsTarget = 30
    private var shutterWanted = 0
    private var manualSupported = false
    private var isoRange = Range(100, 1600)
    private var expRange = Range(100_000L, 100_000_000L)
    private var evRange = Range(0, 0)
    private var evStep = 0.0
    private var baseIssues = mutableListOf<String>()
    private var generation = 0

    private var manualExposure: Pair<Long, Int>? = null
    private var evComp = 0
    private var focusLocked = false
    private var focusDistance = 0f

    @Volatile private var lastExposureNs = 0L
    @Volatile private var lastIso = 0
    @Volatile private var lastFocus = 0f
    @Volatile var measuredFps = 0.0
    @Volatile var meanLuma = 0.0

    /** Plain-text description of the camera and what auto setup did; shared from Settings. */
    @Volatile var report: String = "Camera not opened yet."
        private set
    private val charsText = StringBuilder()
    private val log = StringBuilder()

    private var status = CameraStatus()

    // Full-rate recording path (video encoder).
    private var encoder: FrameEncoder? = null
    @Volatile var codecConfig: ByteArray? = null
        private set
    /** Receives every encoded frame while [setWantFrames] is on: (time on System.nanoTime clock, H.264 data, key frame). */
    @Volatile var frameSink: ((Long, ByteArray, Boolean) -> Unit)? = null
    @Volatile var frameW = 1280
        private set
    @Volatile var frameH = 720
        private set
    val encoding: Boolean get() = encoder != null && session != null
    private var resultCount = 0
    private var resultWindowStart = 0L
    @Volatile private var resultRate = 0.0

    fun setWantFrames(on: Boolean) {
        val e = encoder ?: return
        if (on) e.requestKeyFrame()
        e.wantFrames = on
    }

    private val encListener = object : FrameEncoder.Listener {
        override fun onConfig(config: ByteArray) { codecConfig = config }
        override fun onEncodedFrame(tsMono: Long, data: ByteArray, key: Boolean) { frameSink?.invoke(tsMono, data, key) }
        override fun onEncoderFps(fps: Double) { measuredFps = fps }
    }

    private fun releaseEncoder() {
        encoder?.release()
        encoder = null
        codecConfig = null
    }

    private fun publish(s: CameraStatus) {
        status = s
        report = buildReport()
        onStatus(s)
    }

    private fun logLine(s: String) {
        log.append(String.format(java.util.Locale.US, "%6.1fs  ", (System.nanoTime() % 1_000_000_000_000L) / 1e9)).append(s).append('\n')
    }

    fun open(fps: Int, shutter: Int, rotationOverride: Int, sharpnessPriority: Boolean) {
        handler.post {
            closeInternal()
            generation++
            openInternal(fps, shutter, rotationOverride, sharpnessPriority, generation)
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
        releaseEncoder()
        slicer.recording = false
        publish(status.copy(open = false, checking = false))
    }

    private fun nearest(sizes: Array<Size>, w: Int, h: Int): Size =
        sizes.minByOrNull { abs(it.width * it.height - w * h) + abs(it.width - w) } ?: Size(w, h)

    private fun describe(id: String, ch: CameraCharacteristics) {
        charsText.setLength(0)
        val level = when (ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "?"
        }
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val capNames = caps.map {
            when (it) {
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "HIGH_SPEED_VIDEO"
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
                else -> "cap$it"
            }
        }
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        charsText.append("Phone: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})\n")
        charsText.append("Camera id $id · hardware level $level\n")
        charsText.append("Capabilities: ${capNames.joinToString()}\n")
        charsText.append("Sensor orientation: ${ch.get(CameraCharacteristics.SENSOR_ORIENTATION)}°\n")
        charsText.append("Exposure time range: ${ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)} ns\n")
        charsText.append("ISO range: ${ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)}\n")
        charsText.append("Exposure compensation: ${ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)} × ${ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)} EV\n")
        charsText.append("Normal fps ranges: ${ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.joinToString()}\n")
        if (map != null) {
            val hs = map.highSpeedVideoSizes.joinToString { s ->
                "${s.width}x${s.height} " + map.getHighSpeedVideoFpsRangesFor(s).joinToString("/")
            }
            charsText.append("High-speed sizes: ${hs.ifEmpty { "none" }}\n")
            val s720 = nearest(map.getOutputSizes(android.graphics.SurfaceTexture::class.java), 1280, 720)
            val minDur = map.getOutputMinFrameDuration(android.graphics.SurfaceTexture::class.java, s720)
            charsText.append("Normal ${s720.width}x${s720.height} min frame duration: $minDur ns (max ${if (minDur > 0) (1e9 / minDur).roundToInt() else 0} fps)\n")
        }
        charsText.append("Timestamp source: ${if (ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) "REALTIME" else "UNKNOWN"}\n")
    }

    private fun buildReport(): String {
        val s = status
        return buildString {
            append("Sprint Finish camera report\n\n")
            append(charsText)
            append("Recording path: ${if (encoder != null) "hardware video encoder (full frame rate)" else "preview frames"}\n")
            append("\nCurrent: ${if (s.sharpMode) "sharpness priority (normal session)" else if (s.highSpeed) "high-speed session" else "normal session"} · " +
                "${s.fps} fps wanted · ${s.measuredFps.roundToInt()} fps measured · shutter ${s.shutterLabel} s · ISO ${s.iso} · EV ${s.evComp} · brightness ${s.brightness.roundToInt()}\n")
            if (s.issues.isNotEmpty()) append("Issues: ${s.issues.joinToString(" | ")}\n")
            if (s.notes.isNotEmpty()) append("Notes: ${s.notes.joinToString(" | ")}\n")
            append("\nAuto setup log:\n").append(log)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openInternal(fps: Int, shutter: Int, rotationOverride: Int, sharpnessPriority: Boolean, gen: Int) {
        log.setLength(0)
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
        describe(id, ch)
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val hsCap = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)
        manualSupported = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { isoRange = it }
        ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { expRange = it }
        ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { evRange = it }
        evStep = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toDouble() ?: 0.0
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map == null) {
            publish(CameraStatus(checked = true, issues = listOf("Camera gave no video formats")))
            return
        }

        var size = Size(1280, 720)
        useHighSpeed = false
        sharpMode = sharpnessPriority && manualSupported
        if (sharpnessPriority && !manualSupported) issues += "Sharpness priority needs manual shutter, which this phone doesn't allow"
        if (fps >= 120 && hsCap && !sharpMode) {
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
            fpsRange = Range(best.upper, best.upper).let { fixed -> if (ranges.contains(fixed)) fixed else best }
            fpsTarget = best.upper
            if (fps >= 120 && !sharpMode) issues += "High-speed (120/240 fps) not available — using ${best.upper} fps"
        }
        baseIssues = issues
        shutterWanted = shutter
        logLine("Open ${if (useHighSpeed) "high-speed" else "normal"} ${size.width}x${size.height} @ $fpsRange" + if (sharpMode) " (sharpness priority)" else "")

        val tsSource = ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        slicer.timestampIsRealtime = tsSource == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        // Android already rotates the camera image for an upright preview; we only add a user override.
        slicer.sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val rot = if (rotationOverride >= 0) rotationOverride else 0
        slicer.rotation = rot

        val surf = slicer.configureInput(size.width, size.height)
        surface = surf
        frameW = size.width
        frameH = size.height
        releaseEncoder()
        encoder = try {
            FrameEncoder(size.width, size.height, fpsTarget, slicer.timestampIsRealtime, encListener)
        } catch (e: Exception) {
            logLine("Video encoder couldn't start at $fpsTarget fps: ${e.message}")
            null
        }
        logLine(if (encoder != null) "Video encoder ready (${size.width}x${size.height} @ $fpsTarget)" else "No video encoder — recording from preview frames")
        manualExposure = null
        evComp = 0
        focusLocked = false
        publish(CameraStatus(open = false, checking = true, fps = fpsTarget, highSpeed = useHighSpeed, sharpMode = sharpMode,
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
                if (encoder != null && !useHighSpeed) {
                    logLine("Session with encoder refused; trying preview only")
                    releaseEncoder()
                    createSession(d, surf, gen)
                } else if (useHighSpeed) {
                    useHighSpeed = false
                    fpsRange = Range(30, 30)
                    fpsTarget = 30
                    // Encoder was sized for high speed; rebuild it for 30 fps.
                    releaseEncoder()
                    encoder = try { FrameEncoder(frameW, frameH, 30, slicer.timestampIsRealtime, encListener) } catch (e: Exception) { null }
                    baseIssues = (baseIssues + "High-speed session refused — using 30 fps").toMutableList()
                    logLine("High-speed session refused; falling back to normal")
                    createSession(d, surf, gen)
                } else {
                    publish(status.copy(checking = false, checked = true, issues = listOf("Camera session failed")))
                }
            }
        }
        try {
            val outputs = listOfNotNull(surf, encoder?.inputSurface)
            if (useHighSpeed) d.createConstrainedHighSpeedCaptureSession(outputs, cb, handler)
            else d.createCaptureSession(outputs, cb, handler)
        } catch (e: Exception) {
            publish(status.copy(checking = false, checked = true, issues = listOf("Camera session failed: ${e.message}")))
        }
    }

    private val captureCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExposureNs = it }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastFocus = it }
            val now = System.nanoTime()
            if (resultWindowStart == 0L) resultWindowStart = now
            resultCount++
            if (now - resultWindowStart >= 1_000_000_000L) {
                resultRate = resultCount * 1e9 / (now - resultWindowStart)
                resultCount = 0
                resultWindowStart = now
            }
        }
    }

    private fun submit() {
        val d = device ?: return
        val s = session ?: return
        val surf = surface ?: return
        try {
            val b = d.createCaptureRequest(if (useHighSpeed) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(surf)
            encoder?.let { b.addTarget(it.inputSurface) }
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            val m = manualExposure
            if (m != null && !useHighSpeed) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, m.first)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, m.second)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / fpsTarget)
            } else {
                // High-speed sessions only work with auto exposure on.
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evComp)
                b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF)
            }
            if (focusLocked && manualSupported && focusDistance > 0f && !useHighSpeed) {
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
            logLine("Camera rejected request: ${e.message}")
            baseIssues = (baseIssues + "Camera rejected settings: ${e.message}").toMutableList()
        }
    }

    private fun targetShutterNs(): Long {
        val denom = if (shutterWanted > 0) shutterWanted else 1000
        return 1_000_000_000L / denom
    }

    private fun snapshot(label: String) {
        logLine("$label: ${measuredFps.roundToInt()} fps recorded (camera ${resultRate.roundToInt()}/s), shutter ${if (lastExposureNs > 0) "1/" + (1e9 / lastExposureNs).roundToInt() else "?"} s, ISO $lastIso, EV $evComp, brightness ${meanLuma.roundToInt()}")
    }

    /** Step 1: auto exposure/focus settle. */
    private fun startAutoSetup(gen: Int) {
        manualExposure = null
        evComp = 0
        focusLocked = false
        publish(status.copy(checking = true, checked = false, issues = emptyList(), notes = emptyList()))
        submit()
        handler.postDelayed({ if (gen == generation) afterSettle(gen) }, 1500)
    }

    private fun afterSettle(gen: Int) {
        snapshot("Auto exposure settled")
        if (useHighSpeed) stepExposureDown(gen) else lockManual(gen)
    }

    /** High-speed: nudge auto exposure darker one step at a time until the shutter is fast enough. */
    private fun stepExposureDown(gen: Int) {
        val target = targetShutterNs()
        val fastEnough = lastExposureNs in 1..(target * 11 / 10)
        val tooDark = meanLuma in 0.1..55.0
        if (fastEnough || tooDark || evComp <= evRange.lower) {
            snapshot(when {
                fastEnough -> "Shutter target reached"
                tooDark -> "Stopped: image getting dark"
                else -> "Stopped: lowest exposure compensation"
            })
            handler.postDelayed({ if (gen == generation) verify(gen) }, 600)
            return
        }
        evComp -= 1
        submit()
        handler.postDelayed({ if (gen == generation) stepExposureDown(gen) }, 450)
    }

    /** Normal session: fixed fast shutter + matching ISO and locked focus. */
    private fun lockManual(gen: Int) {
        if (manualSupported && lastExposureNs > 0 && lastIso > 0) {
            val isoMax = minOf(isoRange.upper, 3200)
            val candidates = if (shutterWanted > 0) listOf(shutterWanted) else listOf(2000, 1000, 500)
            val (shutter, iso) = TimeMath.chooseShutter(lastExposureNs, lastIso, candidates, isoMax)
            val exp = (1_000_000_000L / shutter).coerceIn(expRange.lower, expRange.upper)
            manualExposure = exp to iso.coerceIn(isoRange.lower, isoMax)
            focusDistance = lastFocus
            focusLocked = true
            logLine("Manual shutter 1/$shutter s, ISO ${manualExposure?.second}")
            submit()
        }
        handler.postDelayed({ if (gen == generation) verify(gen) }, 1500)
    }

    /** Step 3: check what the camera actually did. */
    private fun verify(gen: Int) {
        snapshot("Check")
        val issues = baseIssues.toMutableList()
        val notes = mutableListOf<String>()
        val target = targetShutterNs()
        val shutterTxt = if (lastExposureNs > 0) "1/${(1e9 / lastExposureNs).roundToInt()} s" else "?"
        val m = manualExposure
        if (m != null) {
            val diff = abs(lastExposureNs - m.first).toDouble() / m.first
            if (diff > 0.25) issues += "Shutter not applied (camera is using $shutterTxt)"
        } else if (lastExposureNs > 0) {
            when {
                lastExposureNs > 4_200_000L -> issues += "Shutter is slow ($shutterTxt) — runners will blur. More light, or use Sharpness priority"
                lastExposureNs > target * 5 / 4 -> notes += "Shutter $shutterTxt (high-speed mode keeps auto exposure; brighter light gives a faster shutter)"
            }
        }
        if (!useHighSpeed && !manualSupported) notes += "This phone doesn't allow a manual shutter"
        if (measuredFps > 0 && measuredFps < fpsTarget * 0.85) {
            issues += "Frame rate only ${measuredFps.roundToInt()} fps (wanted $fpsTarget)"
        }
        if (measuredFps == 0.0) issues += "No frames from the camera"
        if (meanLuma in 0.1..30.0) issues += "Image too dark — more light needed"
        if (meanLuma > 235.0) issues += "Image too bright"
        publish(status.copy(
            open = true, checking = false, checked = true,
            measuredFps = measuredFps, shutterNs = lastExposureNs, targetShutterNs = target, iso = lastIso, evComp = evComp,
            focusLocked = focusLocked, brightness = meanLuma, issues = issues.distinct(), notes = notes.distinct(),
        ))
    }
}
