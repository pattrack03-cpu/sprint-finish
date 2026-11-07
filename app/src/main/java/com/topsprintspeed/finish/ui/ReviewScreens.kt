@file:OptIn(ExperimentalMaterial3Api::class)

package com.topsprintspeed.finish.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topsprintspeed.finish.AppModel
import com.topsprintspeed.finish.Direction
import com.topsprintspeed.finish.RaceInfo
import com.topsprintspeed.finish.StartMode
import com.topsprintspeed.finish.StartSoundKind
import com.topsprintspeed.finish.Tab
import com.topsprintspeed.finish.TimeMath
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
fun ReviewScreen(m: AppModel) {
    val r = m.review
    if (m.reviewLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Opening race…", color = Palette.Ink) }
        return
    }
    if (r == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Title("No race open")
            Label("Finish a race, or pick one in Results to mark the times.")
            GoldButton("Go to Results", { m.tab = Tab.RESULTS }, Modifier.fillMaxWidth())
        }
        return
    }
    val rec = r.rec
    val clip = rec.clips.getOrNull(r.clipIndex)
    val bmp = r.bitmap
    var lane by remember { mutableStateOf("") }
    var athlete by remember { mutableStateOf("") }
    var zoom by remember(r) { mutableFloatStateOf(1f) }
    var offsetX by remember(r, r.clipIndex, bmp) { mutableFloatStateOf((bmp?.width ?: 0) / 2f) }

    // Landscape: the photo-finish image takes most of the screen, controls sit in a narrow panel on the right.
    Row(Modifier.fillMaxSize().background(Palette.Black)) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (bmp == null || clip == null || clip.size == 0) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Label("This clip has no frames.") }
            } else {
                PhotoViewer(r, clip, bmp, zoom, offsetX, onZoom = { zoom = it }, onOffset = { offsetX = it })
            }
        }

        val slice = if (bmp != null && clip != null && clip.size > 0)
            TimeMath.sliceIndexAt(offsetX.toInt().coerceIn(0, bmp.width - 1), clip.sliceW, clip.size, rec.direction) else 0
        val raw = if (clip != null && clip.size > 0) rec.timeOf(clip.ts[slice]) else 0.0
        val sliceW = clip?.sliceW ?: 1
        val maxX = ((bmp?.width ?: 1) - 1).toFloat()

        Column(
            Modifier.width(236.dp).fillMaxHeight().background(Palette.Nav)
                .verticalScroll(rememberScrollState()).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rec.name, color = Palette.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(r.heat, color = Palette.Muted, fontSize = 12.sp, maxLines = 1)
                }
                TextButton(onClick = { m.tab = Tab.RESULTS }) { Text("Done", color = Palette.Gold) }
            }
            Text(TimeMath.format(TimeMath.officialRoundUp(raw)), fontFamily = Mono, fontSize = 34.sp,
                fontWeight = FontWeight.Bold, color = Palette.Gold)
            Label("raw ${TimeMath.format(raw, 3)} s")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LineButton("◀", { offsetX = (offsetX - sliceW).coerceIn(0f, maxX) }, Modifier.weight(1f))
                LineButton("▶", { offsetX = (offsetX + sliceW).coerceIn(0f, maxX) }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LineButton("−", { zoom = (zoom / 1.5f).coerceIn(0.5f, 10f) }, Modifier.weight(1f))
                LineButton("Zoom +", { zoom = (zoom * 1.5f).coerceIn(0.5f, 10f) }, Modifier.weight(1f))
            }
            OutlinedTextField(lane, { lane = it }, label = { Text("Lane") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), colors = fieldColors())
            OutlinedTextField(athlete, { athlete = it }, label = { Text("Athlete") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), colors = fieldColors())
            GoldButton("Mark time", {
                m.addMark(slice, lane, athlete)
                lane = ""; athlete = ""
            }, Modifier.fillMaxWidth(), enabled = clip != null && clip.size > 0)

            if (rec.clips.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmallButton("‹", { m.showClip(r.clipIndex - 1) }, enabled = r.clipIndex > 0)
                    Text("Clip ${r.clipIndex + 1}/${rec.clips.size}", color = Palette.Ink, fontSize = 13.sp,
                        modifier = Modifier.weight(1f))
                    SmallButton("›", { m.showClip(r.clipIndex + 1) }, enabled = r.clipIndex < rec.clips.size - 1)
                }
            }

            if (r.marks.isNotEmpty()) {
                Title("Results")
                r.marks.forEachIndexed { i, mk ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.Surface1)
                            .padding(start = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${i + 1}. " + listOf(mk.lane, mk.athlete).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "—" },
                            color = Palette.Ink, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        Text(TimeMath.format(TimeMath.officialRoundUp(mk.time)), fontFamily = Mono, fontSize = 14.sp,
                            fontWeight = FontWeight.Bold, color = Palette.Gold)
                        TextButton(onClick = { m.removeMark(mk) }) { Text("✕", color = Palette.Muted) }
                    }
                }
            }
            OutlinedTextField(r.heat, { r.heat = it; m.saveMarks() }, label = { Text("Heat") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), colors = fieldColors())
            LineButton("Share image", { m.exportPng() }, Modifier.fillMaxWidth())
            LineButton("Share CSV", { m.exportCsv() }, Modifier.fillMaxWidth(), enabled = r.marks.isNotEmpty())
            LineButton("Share .bvid", { m.shareBvid(r.file) }, Modifier.fillMaxWidth())
        }
    }
}

/** The photo-finish picture, filling its box top to bottom, with the cursor line fixed in the middle. */
@Composable
private fun PhotoViewer(
    r: com.topsprintspeed.finish.ReviewSession,
    clip: com.topsprintspeed.finish.Clip,
    bmp: android.graphics.Bitmap,
    zoom: Float,
    offsetX: Float,
    onZoom: (Float) -> Unit,
    onOffset: (Float) -> Unit,
) {
    val rec = r.rec
    val image = remember(bmp) { bmp.asImageBitmap() }
    val imgW = bmp.width
    val imgH = bmp.height
    val clipMarks = r.marks.filter { it.clip == r.clipIndex }
    val ticks = r.ticks
    // Latest zoom/offset for the gesture handler (plain holder, updated each composition).
    val live = remember { FloatArray(2) }
    live[0] = zoom
    live[1] = offsetX
    Canvas(
        Modifier.fillMaxSize().clipToBounds().pointerInput(bmp) {
            detectTransformGestures { _, pan, gestureZoom, _ ->
                val nz = (live[0] * gestureZoom).coerceIn(0.5f, 10f)
                val scale = (size.height - 30f) / imgH * nz
                val no = (live[1] - pan.x / scale).coerceIn(0f, imgW - 1f)
                live[0] = nz; live[1] = no
                onZoom(nz); onOffset(no)
            }
        }
    ) {
        val ruler = 30f
        val areaH = size.height - ruler
        val scale = areaH / imgH * zoom
        val left = size.width / 2f - offsetX * scale
        val top = (areaH - imgH * scale) / 2f
        val x0 = floor(offsetX - size.width / 2f / scale).toInt().coerceIn(0, imgW - 1)
        val x1 = ceil(offsetX + size.width / 2f / scale).toInt().coerceIn(x0 + 1, imgW)
        drawImage(
            image = image,
            srcOffset = IntOffset(x0, 0),
            srcSize = IntSize(x1 - x0, imgH),
            dstOffset = IntOffset((left + x0 * scale).roundToInt(), top.roundToInt()),
            dstSize = IntSize(((x1 - x0) * scale).roundToInt().coerceAtLeast(1), (imgH * scale).roundToInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.None,
        )
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Palette.Ink2.toArgb(); textSize = 26f
        }
        val markPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Palette.Gold.toArgb(); textSize = 30f; isFakeBoldText = true
        }
        clipMarks.forEachIndexed { i, mk ->
            val mx = left + (TimeMath.sliceX(mk.slice, clip.sliceW, clip.size, rec.direction) + clip.sliceW / 2f) * scale
            if (mx in -10f..size.width + 10f) {
                drawLine(Palette.Maroon, Offset(mx, 0f), Offset(mx, areaH), strokeWidth = 4f)
                val place = r.marks.indexOf(mk) + 1
                val label = "$place ${mk.lane} " + TimeMath.format(TimeMath.officialRoundUp(mk.time))
                drawContext.canvas.nativeCanvas.drawText(label, mx + 6f, 34f + (i % 3) * 32f, markPaint)
            }
        }
        drawRect(Palette.Black, Offset(0f, areaH), Size(size.width, ruler))
        var lastLabelX = -1000f
        for ((tx, tv) in ticks) {
            val sx = left + tx * scale
            if (sx < 0f || sx > size.width) continue
            drawLine(Palette.Muted, Offset(sx, areaH), Offset(sx, areaH + 10f), strokeWidth = 2f)
            if (sx - lastLabelX > 70f) {
                drawContext.canvas.nativeCanvas.drawText(String.format(Locale.US, "%.1f", tv), sx + 3f, areaH + 27f, paint)
                lastLabelX = sx
            }
        }
        val cx = size.width / 2f
        drawLine(Palette.LineGold, Offset(cx, 0f), Offset(cx, areaH), strokeWidth = 3f)
    }
}

@Composable
fun ResultsScreen(m: AppModel, onImport: () -> Unit) {
    var confirmDelete by remember { mutableStateOf<RaceInfo?>(null) }
    val fmt = remember { SimpleDateFormat("EEE d MMM · h:mm a", Locale.US) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Results", color = Palette.Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        LineButton("Import a .bvid…", onImport, Modifier.fillMaxWidth())
        val waiting = m.races.filter { it.marks == 0 }
        val timed = m.races.filter { it.marks > 0 }
        if (m.races.isEmpty()) Label("No saved races yet. Finish a race and it appears here as a .bvid.")
        if (waiting.isNotEmpty()) {
            Text("Waiting for times · .bvid", color = Palette.Gold, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            waiting.forEach { RaceRow(m, it, fmt) { confirmDelete = it } }
        }
        if (timed.isNotEmpty()) {
            Text("Timed", color = Palette.Muted, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            timed.forEach { RaceRow(m, it, fmt) { confirmDelete = it } }
        }
        Spacer(Modifier.height(8.dp))
        Label("Note: .bvid files here use Sprint Finish's own layout. SprintTimer's .bvid layout isn't published, so swapping files with SprintTimer isn't supported yet.")
    }
    confirmDelete?.let { info ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Palette.Surface1,
            title = { Text("Delete ${info.name}?", color = Palette.Ink) },
            text = { Text("The recording and its marked times are removed from this phone.", color = Palette.Ink2) },
            confirmButton = { TextButton(onClick = { m.deleteRace(info); confirmDelete = null }) { Text("Delete", color = Palette.GoldLight) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel", color = Palette.Ink2) } },
        )
    }
}

@Composable
private fun RaceRow(m: AppModel, info: RaceInfo, fmt: SimpleDateFormat, onDelete: () -> Unit) {
    Card {
        Text(info.file.name, fontFamily = Mono, color = Palette.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Label("${fmt.format(Date(info.createdAt))} · ${info.clips} clip(s) · ${info.fps} fps · ${info.sizeBytes / 1_000_000} MB" +
            if (info.marks > 0) " · ${info.marks} marked" else "")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GoldButton(if (info.marks > 0) "Review" else "Time it", { m.openReview(info.file) }, Modifier.weight(1f))
            LineButton("Share", { m.shareBvid(info.file) }, Modifier.width(88.dp).height(54.dp))
            LineButton("Delete", onDelete, Modifier.width(88.dp).height(54.dp))
        }
    }
}

@Composable
fun SettingsScreen(m: AppModel) {
    val s = m.settings
    val st = m.cameraStatus
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Camera & photo finish", color = Palette.Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)

        Card(warn = st.checked && st.issues.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Title("Auto setup")
                    Label(if (st.checking) "Checking now…" else "Runs every time the camera opens")
                }
                LineButton("Run again", { m.rerunAutoSetup() }, Modifier.width(120.dp), enabled = !st.checking && st.open)
            }
            CheckRow("Mode", st.highSpeed || st.sharpMode,
                if (st.sharpMode) "sharpness · ${st.fps} fps" else if (st.highSpeed) "high-speed ${st.fps} fps · ${st.size}" else "normal ${st.fps} fps")
            CheckRow("Measured frame rate", st.measuredFps >= st.fps * 0.85 && st.fps > 0, "${st.measuredFps.roundToInt()} fps")
            CheckRow("Shutter speed", st.issues.none { it.startsWith("Shutter") },
                "${st.shutterLabel} s · ISO ${st.iso}" + if (st.evComp != 0) " · EV ${st.evComp}" else "")
            CheckRow("Exposure", st.brightness in 30.0..235.0, "brightness ${st.brightness.roundToInt()}")
            CheckRow("Focus locked", st.focusLocked, if (st.focusLocked) "locked" else "auto")
            st.issues.forEach { Text("• $it", color = Palette.GoldLight, fontSize = 13.sp) }
            st.notes.forEach { Text("ℹ $it", color = Palette.Ink2, fontSize = 12.sp) }
            LineButton("Send camera report", { m.shareCameraReport() }, Modifier.fillMaxWidth())
        }

        Label("Priority")
        Segmented(listOf("Frame rate", "Sharpness"), if (s.sharpnessPriority) 1 else 0, enabled = !m.running) {
            s.update { sharpnessPriority = it == 1 }; m.applyCameraSettings()
        }
        Label(if (s.sharpnessPriority)
            "Fixed fast shutter at the highest frame rate this phone allows outside high-speed mode (often 60 fps)."
        else
            "Highest frame rate. Shutter is set by auto exposure, pushed as fast as the light allows.")

        Label("Frame rate")
        val fpsOpts = listOf(60, 120, 240)
        Segmented(fpsOpts.map { "$it fps" }, fpsOpts.indexOf(s.fps).coerceAtLeast(0), enabled = !m.running) {
            s.update { fps = fpsOpts[it] }; m.applyCameraSettings()
        }
        Label("Shutter speed (faster = sharper, needs more light)")
        val shutOpts = listOf(0, 500, 1000, 2000, 4000)
        Segmented(shutOpts.map { if (it == 0) "Auto" else "1/$it" }, shutOpts.indexOf(s.shutter).coerceAtLeast(0), enabled = !m.running) {
            s.update { shutter = shutOpts[it] }; m.applyCameraSettings()
        }
        Label("Slice width (pixels per frame)")
        Segmented(listOf("1 px", "2 px", "3 px", "4 px"), s.sliceWidth - 1, enabled = !m.running) {
            s.update { sliceWidth = it + 1 }; m.applyCameraSettings()
        }
        Label("Runner direction")
        Segmented(Direction.values().map { it.label }, s.direction.ordinal, enabled = !m.running) {
            s.update { direction = Direction.values()[it] }
        }
        Label("Extra image rotation (only if the preview is still sideways)")
        val rotOpts = listOf(-1, 0, 90, 180, 270)
        Segmented(rotOpts.map { if (it < 0) "Auto" else "$it°" }, rotOpts.indexOf(s.rotationOverride).coerceAtLeast(0), enabled = !m.running) {
            s.update { rotationOverride = rotOpts[it] }; m.applyCameraSettings()
        }
        val perSecond = s.fps * s.sliceWidth
        Card {
            Label("At ${s.fps} fps each slice is ${String.format(Locale.US, "%.4f", 1.0 / s.fps)} s. " +
                "A 10 s recording makes ${s.fps * 10} slices = ${perSecond * 10} px wide.")
        }

        Spacer(Modifier.height(4.dp))
        Text("Start & offsets", color = Palette.Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Label("Offsets are added to every time; calibrate each start mode once against a known start.")
        StartMode.values().forEach { mode ->
            Stepper(
                "${mode.label} offset",
                (if (s.offsetFor(mode) >= 0) "+" else "−") + String.format(Locale.US, "%.2f s", kotlin.math.abs(s.offsetFor(mode))),
                onMinus = { s.setOffset(mode, s.offsetFor(mode) - 0.01) },
                onPlus = { s.setOffset(mode, s.offsetFor(mode) + 0.01) },
            )
        }
        Label("Start sound")
        Segmented(StartSoundKind.values().map { it.label }, s.startSound.ordinal) {
            s.update { startSound = StartSoundKind.values()[it] }
        }
        Card {
            Title("Starting pistol (microphone)")
            Label("Trigger level: ${s.gunThresholdDb.roundToInt()} dB (lower = more sensitive)")
            Slider(s.gunThresholdDb, { s.gunThresholdDb = it }, valueRange = -40f..-3f,
                onValueChangeFinished = { s.update { } }, colors = sliderColors())
            Label("Phone distance from gun: ${s.gunDistanceM.roundToInt()} m → adds ${String.format(Locale.US, "%.3f", TimeMath.soundDelaySeconds(s.gunDistanceM.toDouble()))} s for sound travel")
            Slider(s.gunDistanceM, { s.gunDistanceM = it.roundToInt().toFloat() }, valueRange = 0f..120f,
                onValueChangeFinished = { s.update { } }, colors = sliderColors())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Shot filter (ignore claps & voices)", color = Palette.Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(s.shotFilter, { v -> s.update { shotFilter = v } },
                    colors = SwitchDefaults.colors(checkedTrackColor = Palette.Gold, checkedThumbColor = Palette.Black))
            }
        }
        Card {
            Title("Voice self start")
            Label("“Set” hold: random between ${String.format(Locale.US, "%.1f", s.voiceHoldMin)} and ${String.format(Locale.US, "%.1f", s.voiceHoldMax)} s")
            Slider(s.voiceHoldMin, { s.voiceHoldMin = minOf(it, s.voiceHoldMax) }, valueRange = 0.5f..4f,
                onValueChangeFinished = { s.update { } }, colors = sliderColors())
            Slider(s.voiceHoldMax, { s.voiceHoldMax = maxOf(it, s.voiceHoldMin) }, valueRange = 0.5f..4f,
                onValueChangeFinished = { s.update { } }, colors = sliderColors())
        }
        Card {
            Title("Late-tap correction")
            Label("If you release too early, press “Released too early? Tap at the real start” on the Start screen the moment the athlete goes. The start moves to your tap and every time shifts with it.")
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CheckRow(label: String, ok: Boolean, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.width(24.dp).height(24.dp).clip(RoundedCornerShape(12.dp))
                .background(if (ok) Palette.Gold else Palette.Maroon),
            contentAlignment = Alignment.Center,
        ) { Text(if (ok) "✓" else "!", color = if (ok) Palette.Black else Palette.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        Text(label, color = Palette.Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = Palette.Ink2, fontFamily = Mono, fontSize = 12.sp)
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = Palette.Gold, activeTrackColor = Palette.Gold, inactiveTrackColor = Palette.Surface2,
)
