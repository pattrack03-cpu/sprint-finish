package com.topsprintspeed.finish

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.topsprintspeed.finish.camera.CameraController
import com.topsprintspeed.finish.camera.CameraStatus
import com.topsprintspeed.finish.camera.GlSlicer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Tab(val label: String) { START("Start"), FINISH("Finish"), REVIEW("Review"), RESULTS("Results"), SETTINGS("Settings") }

/** What is open on the Review screen. */
class ReviewSession(val file: File, val rec: Recording, heat: String, marks: List<Mark>) {
    var clipIndex by mutableStateOf(0)
    var bitmap by mutableStateOf<Bitmap?>(null)
    var ticks by mutableStateOf<List<Pair<Int, Double>>>(emptyList())
    var heat by mutableStateOf(heat)
    val marks = mutableStateListOf<Mark>().apply { addAll(marks) }
}

class AppModel(private val activity: MainActivity) : GlSlicer.Listener {
    private val ctx: Context = activity.applicationContext
    private val main = Handler(Looper.getMainLooper())

    val settings = Settings(ctx)

    // Camera
    var cameraStatus by mutableStateOf(CameraStatus())
    var liveFps by mutableStateOf(0.0)
    var warningAcknowledged by mutableStateOf(false)
    var showCameraWarning by mutableStateOf(false)
    var hasCameraPermission by mutableStateOf(false)

    // Race
    var raceName by mutableStateOf("100m")
    var heat by mutableStateOf("Heat 1")
    var armed by mutableStateOf(false)
    var voiceStage by mutableStateOf("")
    var gunLevelDb by mutableStateOf(-90f)
    var startMono by mutableStateOf<Long?>(null)
    var endMono by mutableStateOf<Long?>(null)
    var recordingNow by mutableStateOf(false)
    var current by mutableStateOf<Recording?>(null)
    val clipSpans = mutableStateListOf<Pair<Double, Double?>>()
    val splits = mutableStateListOf<Long>()
    var saving by mutableStateOf(false)
    var raceOffset by mutableStateOf(0.0)

    // Review / results
    var review by mutableStateOf<ReviewSession?>(null)
    var reviewLoading by mutableStateOf(false)
    val races = mutableStateListOf<RaceInfo>()
    var tab by mutableStateOf(Tab.START)
    var toast by mutableStateOf<String?>(null)

    val slicer = GlSlicer(this)
    private val camera = CameraController(ctx, slicer) { st -> main.post { onCameraStatus(st) } }
    private val sound = StartSound(ctx)
    private val voice = VoiceStarter(ctx)
    private val gun = GunDetector(
        onLevel = { db -> main.post { gunLevelDb = db } },
        onShot = { mono -> main.post { onGunShot(mono) } },
    )

    val racesDir: File = File(ctx.filesDir, "races").apply { mkdirs() }

    init {
        slicer.linePos = settings.linePos
        slicer.sliceWidth = settings.sliceWidth
        createNotificationChannel()
        refreshRaces()
    }

    val running get() = startMono != null && endMono == null

    fun raceTime(mono: Long): Double? {
        val s = startMono ?: return null
        return (mono - s) / 1e9 + (current?.offsetS ?: 0.0)
    }

    // ---------------- Camera ----------------

    fun openCamera() {
        if (!hasCameraPermission) return
        camera.open(settings.fps, settings.shutter, settings.rotationOverride)
    }

    fun closeCamera() {
        if (recordingNow) pauseRecording()
        camera.close()
    }

    fun rerunAutoSetup() {
        warningAcknowledged = false
        camera.rerunAutoSetup()
    }

    fun applyCameraSettings() {
        warningAcknowledged = false
        slicer.sliceWidth = settings.sliceWidth
        openCamera()
    }

    private var lastNotified = ""

    private fun onCameraStatus(st: CameraStatus) {
        val wasChecked = cameraStatus.checked
        cameraStatus = st
        if (st.checked && st.issues.isNotEmpty()) {
            val key = st.issues.joinToString()
            if (key != lastNotified) {
                lastNotified = key
                notifyCameraProblem(st.issues)
            }
        } else if (st.ok) {
            lastNotified = ""
            NotificationManagerCompat.from(ctx).cancel(1)
        }
        if (!wasChecked && st.checked && st.issues.isNotEmpty()) warningAcknowledged = false
    }

    override fun onFrameStats(fps: Double, meanLuma: Double) {
        camera.measuredFps = fps
        camera.meanLuma = meanLuma
        main.post { liveFps = fps }
    }

    override fun onSlice(tsMono: Long, rgba: ByteArray, w: Int, h: Int) {
        val rec = current ?: return
        synchronized(rec) {
            var clip = rec.clips.lastOrNull() ?: return
            if (clip.sliceW != w) return
            val maxSlices = 20_000 / w
            if (clip.size >= maxSlices) {
                clip = Clip(w, h)
                rec.clips.add(clip)
                main.post { clipSpans.add(rec.timeOf(tsMono) to null) }
            }
            clip.ts.add(tsMono)
            clip.slices.add(rgba)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel("camera", "Camera setup", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Warns you before a race if the camera isn't set up properly"
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun notifyCameraProblem(issues: List<String>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, "camera")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Camera not set up for the race")
            .setContentText(issues.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(issues.joinToString("\n") + "\nFix it in Settings before the start."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(1, n) } catch (_: SecurityException) {}
    }

    // ---------------- Starting ----------------

    /** True if the start may go ahead; otherwise shows the camera warning first. */
    fun checkCameraBeforeStart(): Boolean {
        val st = cameraStatus
        if (st.ok || warningAcknowledged) return true
        showCameraWarning = true
        return false
    }

    fun touchPressed(): Boolean {
        if (running || saving) return false
        if (!checkCameraBeforeStart()) return false
        armed = true
        return true
    }

    fun touchReleased() {
        if (!armed) return
        val mono = System.nanoTime()
        sound.play(settings.startSound)
        armed = false
        startRace(mono, StartMode.TOUCH, 0.0)
    }

    fun armOrCancel() {
        if (armed) { cancelArm(); return }
        if (running || saving) return
        if (!checkCameraBeforeStart()) return
        when (settings.startMode) {
            StartMode.GUN -> {
                armed = true
                gun.start(settings.gunThresholdDb, settings.shotFilter)
            }
            StartMode.VOICE -> {
                armed = true
                voice.run(settings.voiceHoldMin, settings.voiceHoldMax, 4f, { voiceStage = it }) {
                    val mono = System.nanoTime()
                    sound.play(if (settings.startSound == StartSoundKind.NONE) StartSoundKind.BANG else settings.startSound)
                    armed = false
                    voiceStage = ""
                    startRace(mono, StartMode.VOICE, 0.0)
                }
            }
            StartMode.TOUCH -> {}
        }
    }

    fun cancelArm() {
        gun.stop()
        voice.cancel()
        armed = false
        voiceStage = ""
    }

    private fun onGunShot(mono: Long) {
        if (!armed) return
        armed = false
        startRace(mono, StartMode.GUN, TimeMath.soundDelaySeconds(settings.gunDistanceM.toDouble()))
    }

    private fun startRace(mono: Long, mode: StartMode, extraOffset: Double) {
        val st = cameraStatus
        current = Recording(
            name = "${raceName.trim()} ${heat.trim()}".trim(),
            fps = if (st.fps > 0) st.fps else settings.fps,
            sliceW = settings.sliceWidth,
            height = GlSlicer.OUT_HEIGHT,
            direction = settings.direction,
            startMono = mono,
            offsetS = settings.offsetFor(mode) + extraOffset,
            startMode = mode.label,
            createdAt = System.currentTimeMillis(),
        )
        startMono = mono
        endMono = null
        raceOffset = current?.offsetS ?: 0.0
        clipSpans.clear()
        splits.clear()
        // Straight to the finish-line camera the moment the race starts.
        tab = Tab.FINISH
    }

    /** "Released too early? Tap at the real start" — moves the start to now; every time shifts with it. */
    fun correctStartToNow() {
        if (!running) return
        val mono = System.nanoTime()
        startMono = mono
        current?.let { rec -> synchronized(rec) { rec.startMono = mono } }
        val rec = current ?: return
        val spans = (0 until rec.clips.size).map { i -> rec.clipSpan(i) }
        clipSpans.clear()
        spans.forEachIndexed { i, s ->
            if (s != null) clipSpans.add(s.first to (if (recordingNow && i == spans.lastIndex) null else s.second))
        }
        toast = "Start moved to your tap"
    }

    /** Changes this race's offset (positive = slower times). Applies to every time already recorded too. */
    fun adjustRaceOffset(delta: Double) {
        val rec = current ?: return
        synchronized(rec) { rec.offsetS = Math.round((rec.offsetS + delta) * 100.0) / 100.0 }
        raceOffset = rec.offsetS
    }

    // ---------------- Finish recording ----------------

    fun startRecording() {
        val rec = current
        if (rec == null || !running) { toast = "Start the race first — the clock must be running"; return }
        if (!cameraStatus.open) { toast = "Camera isn't open"; return }
        slicer.linePos = settings.linePos
        slicer.sliceWidth = rec.sliceW
        synchronized(rec) { rec.clips.add(Clip(rec.sliceW, rec.height)) }
        clipSpans.add((raceTime(System.nanoTime()) ?: 0.0) to null)
        slicer.recording = true
        recordingNow = true
    }

    fun pauseRecording() {
        slicer.recording = false
        recordingNow = false
        val rec = current ?: return
        if (clipSpans.isNotEmpty()) {
            val i = clipSpans.lastIndex
            clipSpans[i] = clipSpans[i].first to (raceTime(System.nanoTime()) ?: 0.0)
        }
        synchronized(rec) { rec.clips.removeAll { it.size == 0 } }
    }

    fun tapSplit() { if (running) splits.add(System.nanoTime()) }

    fun endRace() {
        if (recordingNow) pauseRecording()
        cancelArm()
        val rec = current
        if (startMono != null) endMono = System.nanoTime()
        if (rec == null) { resetRace(); return }
        val hasFrames = synchronized(rec) { rec.clips.any { it.size > 0 } }
        if (!hasFrames) { toast = "No finish recorded — nothing to save"; return }
        saving = true
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(rec.createdAt))
        val safe = rec.name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifEmpty { "race" }
        val file = File(racesDir, "${safe}_$stamp.bvid")
        val heatName = heat.trim()
        Thread {
            val result = runCatching {
                synchronized(rec) { BvidFile.write(file, rec) }
                BvidFile.writeMarks(BvidFile.marksFile(file), heatName, emptyList())
            }
            main.post {
                saving = false
                if (result.isSuccess) {
                    toast = "Saved ${file.name}"
                    resetRace()
                    refreshRaces()
                    openReview(file)
                } else {
                    toast = "Couldn't save: ${result.exceptionOrNull()?.message}"
                }
            }
        }.start()
    }

    fun resetRace() {
        if (recordingNow) pauseRecording()
        cancelArm()
        current = null
        startMono = null
        endMono = null
        clipSpans.clear()
        splits.clear()
    }

    // ---------------- Results & review ----------------

    fun refreshRaces() {
        Thread {
            val list = (racesDir.listFiles { f -> f.name.endsWith(".bvid") } ?: emptyArray())
                .mapNotNull { BvidFile.info(it) }
                .sortedByDescending { it.createdAt }
            main.post { races.clear(); races.addAll(list) }
        }.start()
    }

    fun openReview(file: File) {
        reviewLoading = true
        tab = Tab.REVIEW
        Thread {
            val result = runCatching {
                val rec = BvidFile.read(file)
                val (h, marks) = BvidFile.readMarks(BvidFile.marksFile(file))
                val s = ReviewSession(file, rec, h.ifEmpty { rec.name }, marks)
                val bmp = if (rec.clips.isNotEmpty()) PhotoFinishImage.build(rec.clips[0], rec.direction) else null
                Triple(s, bmp, PhotoFinishImage.ticks(rec, 0))
            }
            main.post {
                reviewLoading = false
                result.onSuccess { (s, bmp, ticks) ->
                    s.bitmap = bmp
                    s.ticks = ticks
                    review = s
                }.onFailure { toast = "Couldn't open: ${it.message}" }
            }
        }.start()
    }

    fun showClip(index: Int) {
        val s = review ?: return
        if (index !in 0 until s.rec.clips.size) return
        s.clipIndex = index
        Thread {
            val bmp = PhotoFinishImage.build(s.rec.clips[index], s.rec.direction)
            val ticks = PhotoFinishImage.ticks(s.rec, index)
            main.post { if (review === s && s.clipIndex == index) { s.bitmap = bmp; s.ticks = ticks } }
        }.start()
    }

    fun addMark(slice: Int, lane: String, athlete: String) {
        val s = review ?: return
        val time = s.rec.sliceTime(s.clipIndex, slice)
        s.marks.add(Mark(s.clipIndex, slice, time, lane.trim(), athlete.trim()))
        val sorted = s.marks.sortedBy { it.time }
        s.marks.clear(); s.marks.addAll(sorted)
        saveMarks()
    }

    fun removeMark(m: Mark) {
        val s = review ?: return
        s.marks.remove(m)
        saveMarks()
    }

    fun saveMarks() {
        val s = review ?: return
        val copy = s.marks.toList()
        val heatName = s.heat
        Thread {
            runCatching { BvidFile.writeMarks(BvidFile.marksFile(s.file), heatName, copy) }
            main.post { refreshRaces() }
        }.start()
    }

    fun deleteRace(info: RaceInfo) {
        info.file.delete()
        BvidFile.marksFile(info.file).delete()
        if (review?.file == info.file) review = null
        refreshRaces()
    }

    fun importBvid(uri: Uri) {
        Thread {
            val result = runCatching {
                val name = "import_" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".bvid"
                val out = File(racesDir, name)
                ctx.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Couldn't read the file" }
                    out.outputStream().use { input.copyTo(it) }
                }
                if (BvidFile.info(out) == null) {
                    out.delete()
                    throw IllegalArgumentException("Not a Sprint Finish .bvid. SprintTimer's own .bvid layout isn't supported yet.")
                }
                out
            }
            main.post {
                result.onSuccess { toast = "Imported"; refreshRaces(); openReview(it) }
                    .onFailure { toast = it.message ?: "Import failed" }
            }
        }.start()
    }

    fun share(file: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, title))
    }

    fun shareBvid(file: File) = share(file, "application/octet-stream", file.name)

    fun exportPng() {
        val s = review ?: return
        val bmp = s.bitmap ?: return
        Thread {
            val result = runCatching { Exporter.png(ctx, s, bmp) }
            main.post {
                result.onSuccess { share(it, "image/png", it.name) }.onFailure { toast = "Export failed: ${it.message}" }
            }
        }.start()
    }

    fun exportCsv() {
        val s = review ?: return
        val result = runCatching { Exporter.csv(ctx, s) }
        result.onSuccess { share(it, "text/csv", it.name) }.onFailure { toast = "Export failed: ${it.message}" }
    }

    fun release() {
        cancelArm()
        camera.release()
        slicer.release()
        sound.release()
        voice.shutdown()
    }
}
