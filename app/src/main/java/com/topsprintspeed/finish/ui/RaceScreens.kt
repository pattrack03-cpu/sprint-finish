@file:OptIn(ExperimentalMaterial3Api::class)

package com.topsprintspeed.finish.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.topsprintspeed.finish.AppModel
import com.topsprintspeed.finish.StartMode
import com.topsprintspeed.finish.Tab
import com.topsprintspeed.finish.TimeMath
import kotlin.math.roundToInt

private fun iconFor(t: Tab): ImageVector = when (t) {
    Tab.START -> Icons.Filled.PlayArrow
    Tab.FINISH -> Icons.Filled.Star
    Tab.REVIEW -> Icons.Filled.Search
    Tab.RESULTS -> Icons.Filled.Menu
    Tab.SETTINGS -> Icons.Filled.Settings
}

@Composable
fun AppRoot(m: AppModel) {
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) m.importBvid(uri)
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(m.toast) {
        val t = m.toast
        if (t != null) {
            m.toast = null
            snackbar.showSnackbar(t)
        }
    }
    val fullReview = m.tab == Tab.REVIEW && m.review != null && !m.reviewLoading
    val activity = LocalContext.current as? Activity
    DisposableEffect(fullReview) {
        activity?.requestedOrientation =
            if (fullReview) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        activity?.window?.let { w ->
            val c = WindowCompat.getInsetsController(w, w.decorView)
            if (fullReview) {
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                c.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }
    if (fullReview) {
        BackHandler { m.tab = Tab.RESULTS }
        Box(Modifier.fillMaxSize().background(Palette.Black)) {
            ReviewScreen(m)
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    } else {
        Scaffold(
            containerColor = Palette.Black,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = Palette.Nav) {
                    Tab.values().forEach { t ->
                        NavigationBarItem(
                            selected = m.tab == t,
                            onClick = { m.tab = t },
                            icon = { Icon(iconFor(t), contentDescription = null) },
                            label = { Text(t.label, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Palette.Black, indicatorColor = Palette.Gold,
                                selectedTextColor = Palette.Gold, unselectedIconColor = Palette.Muted,
                                unselectedTextColor = Palette.Muted,
                            ),
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize().background(Palette.Black)) {
                when (m.tab) {
                    Tab.START -> StartScreen(m)
                    Tab.FINISH -> FinishScreen(m)
                    Tab.REVIEW -> ReviewScreen(m)
                    Tab.RESULTS -> ResultsScreen(m) { importer.launch(arrayOf("*/*")) }
                    Tab.SETTINGS -> SettingsScreen(m)
                }
            }
        }
    }

    if (m.showCameraWarning) {
        val issues = m.cameraStatus.issues
        AlertDialog(
            onDismissRequest = { m.showCameraWarning = false },
            containerColor = Palette.Surface1,
            title = { Text("Camera not set up", color = Palette.Ink) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (m.cameraStatus.checking) Text("Auto setup is still running…", color = Palette.Ink2)
                    issues.forEach { Text("• $it", color = Palette.Ink2) }
                    if (!m.cameraStatus.checking && issues.isEmpty()) Text("The camera hasn't been checked yet.", color = Palette.Ink2)
                    Text("Fix it before the start, or start anyway.", color = Palette.Muted, fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { m.showCameraWarning = false; m.tab = Tab.SETTINGS }) { Text("Fix", color = Palette.Gold) }
            },
            dismissButton = {
                TextButton(onClick = { m.showCameraWarning = false; m.warningAcknowledged = true }) {
                    Text("Start anyway", color = Palette.Ink2)
                }
            },
        )
    }
}

@Composable
fun CameraBanner(m: AppModel) {
    val st = m.cameraStatus
    when {
        !m.hasCameraPermission -> Card(warn = true) {
            Title("Camera permission needed")
            Label("Allow camera access in Android settings, then reopen the app.")
        }
        st.checking -> Card {
            Title("Checking camera…")
            Label("Auto setup: frame rate, shutter, exposure and focus")
        }
        st.checked && st.issues.isNotEmpty() -> Card(warn = true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Camera not set up yet", color = Palette.Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    st.issues.take(3).forEach { Text(it, color = Palette.Ink2, fontSize = 12.sp) }
                }
                GoldButton("Fix", { m.tab = Tab.SETTINGS }, Modifier.width(80.dp))
            }
        }
        st.ok -> Card {
            Text(
                "✓ Camera ready · ${st.fps} fps · ${st.shutterLabel} s · ISO ${st.iso}" + if (st.focusLocked) " · focus locked" else "",
                color = Palette.Ink, fontSize = 13.sp,
            )
        }
        else -> Card { Label("Camera opening…") }
    }
}

@Composable
private fun rememberNow(): Long {
    val now by produceState(System.nanoTime()) {
        while (true) withFrameNanos { value = System.nanoTime() }
    }
    return now
}

@Composable
fun StartScreen(m: AppModel) {
    val s = m.settings
    val now = rememberNow()
    val t = m.startMono?.let { m.raceTime(m.endMono ?: now) } ?: 0.0
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(m.raceName, { m.raceName = it }, label = { Text("Event") }, singleLine = true,
                modifier = Modifier.weight(1f), colors = fieldColors())
            OutlinedTextField(m.heat, { m.heat = it }, label = { Text("Heat") }, singleLine = true,
                modifier = Modifier.weight(1f), colors = fieldColors())
        }
        CameraBanner(m)

        Label("Start mode")
        Segmented(StartMode.values().map { it.label }, s.startMode.ordinal, enabled = !m.armed && !m.running) {
            s.update { startMode = StartMode.values()[it] }
        }
        Label(
            when (s.startMode) {
                StartMode.TOUCH -> "Press and hold, lift your finger to start. The bang plays on release."
                StartMode.GUN -> "Listens for the starter pistol on the microphone. Set level and distance in Settings."
                StartMode.VOICE -> "Self start: calls “On your marks… Set…”, then fires after a random hold."
            }
        )

        Text(
            TimeMath.clock(t), fontFamily = Mono, fontSize = 52.sp, fontWeight = FontWeight.Bold,
            color = if (m.running) Palette.Gold else Palette.Ink,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )

        val (big, sub) = when {
            m.running -> "Running" to "Go to Finish to record"
            m.armed && s.startMode == StartMode.TOUCH -> "Release to start" to "Lift finger = GO"
            m.armed && s.startMode == StartMode.GUN -> "Listening…" to "Fire the gun · tap to cancel"
            m.armed && s.startMode == StartMode.VOICE -> m.voiceStage.ifEmpty { "Starting…" } to "Tap to cancel"
            m.endMono != null -> "Finished" to "Saved — see Review"
            s.startMode == StartMode.TOUCH -> "Press & hold" to "then lift your finger"
            s.startMode == StartMode.GUN -> "Arm gun start" to "Tap, then fire the pistol"
            else -> "Start countdown" to "Tap to begin"
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(200.dp)
                    .clip(CircleShape)
                    .background(if (m.armed) Palette.Maroon else if (m.running) Palette.Surface2 else Palette.Gold)
                    .pointerInput(s.startMode) {
                        detectTapGestures(onPress = {
                            if (s.startMode == StartMode.TOUCH) {
                                if (m.touchPressed()) {
                                    val released = tryAwaitRelease()
                                    if (released) m.touchReleased() else m.cancelArm()
                                }
                            } else {
                                m.armOrCancel()
                            }
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                val fg = if (m.armed || m.running) Palette.Ink else Palette.Black
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(big, color = fg, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(sub, color = fg, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
            }
        }

        if (m.armed && s.startMode == StartMode.GUN) {
            val frac = ((m.gunLevelDb + 60f) / 60f).coerceIn(0f, 1f)
            val thr = ((s.gunThresholdDb + 60f) / 60f).coerceIn(0f, 1f)
            Label("Mic level (trigger at ${s.gunThresholdDb.roundToInt()} dB)")
            BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Palette.Surface2)) {
                Box(Modifier.fillMaxHeight().width(maxWidth * frac).background(Palette.Gold))
                Box(Modifier.offset(x = maxWidth * thr).fillMaxHeight().width(3.dp).background(Palette.Maroon))
            }
        }

        Stepper(
            "${s.startMode.label} start offset",
            (if (s.offsetFor(s.startMode) >= 0) "+" else "−") + String.format(java.util.Locale.US, "%.2f s", kotlin.math.abs(s.offsetFor(s.startMode))),
            onMinus = { s.setOffset(s.startMode, s.offsetFor(s.startMode) - 0.01) },
            onPlus = { s.setOffset(s.startMode, s.offsetFor(s.startMode) + 0.01) },
        )
        LineButton("Released too early? Tap at the real start", { m.correctStartToNow() },
            Modifier.fillMaxWidth(), enabled = m.running)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MaroonButton(if (m.saving) "Saving…" else "End race & save", { m.endRace() }, Modifier.weight(1f),
                enabled = m.running && !m.saving)
            LineButton("Reset", { m.resetRace() }, Modifier.width(96.dp).height(54.dp), enabled = !m.saving)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Palette.Gold, unfocusedBorderColor = Palette.Border,
    focusedLabelColor = Palette.Gold, unfocusedLabelColor = Palette.Muted,
    focusedTextColor = Palette.Ink, unfocusedTextColor = Palette.Ink, cursorColor = Palette.Gold,
)

@Composable
fun FinishScreen(m: AppModel) {
    val s = m.settings
    val now = rememberNow()
    val st = m.cameraStatus
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().background(Palette.Black),
            contentAlignment = Alignment.Center,
        ) {
            val ratio = 9f / 16f
            val w = minOf(maxWidth, maxHeight * ratio)
            val h = w / ratio
            Box(Modifier.size(w, h)) {
                AndroidView(
                    factory = { c ->
                        SurfaceView(c).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {}
                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                    m.slicer.setPreview(holder.surface, width, height)
                                }
                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    m.slicer.setPreview(null, 0, 0)
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                Canvas(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectDragGestures(onDragEnd = { s.update { } }) { change, drag ->
                            change.consume()
                            if (!m.recordingNow) {
                                val nx = (s.linePos + drag.x / size.width).coerceIn(0.03f, 0.97f)
                                s.linePos = nx
                                m.slicer.linePos = nx
                            }
                        }
                    }
                ) {
                    val x = size.width * s.linePos
                    drawLine(Palette.LineGold, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
                    drawCircle(Palette.LineGold, 22f, Offset(x, size.height * 0.12f))
                    drawCircle(Palette.LineGold, 22f, Offset(x, size.height * 0.88f))
                }
                Column(
                    Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(12.dp))
                        .background(Palette.Black.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    val t = m.startMono?.let { m.raceTime(m.endMono ?: now) } ?: 0.0
                    Text(TimeMath.clock(t), fontFamily = Mono, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                    Text(if (m.running) "Clock running" else "Waiting for start", color = Palette.Gold, fontSize = 12.sp)
                }
                Column(
                    Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(12.dp))
                        .background(Palette.Black.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        if (st.ok) "✓ Auto setup OK" else if (st.checking) "Checking…" else "! Camera warning",
                        color = if (st.ok) Palette.Gold else Palette.GoldLight, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    )
                    Text("${m.liveFps.roundToInt()} fps · ${st.shutterLabel} s", color = Palette.Ink, fontSize = 12.sp)
                    Text("Slice ${s.sliceWidth} px · ${s.direction.label}", color = Palette.Ink, fontSize = 12.sp)
                }
                Row(
                    Modifier.align(Alignment.BottomStart).padding(10.dp).clip(RoundedCornerShape(50))
                        .background(if (m.recordingNow) Palette.Maroon else Palette.Black.copy(alpha = 0.8f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (m.recordingNow) "● REC photo finish" else if (m.running) "Camera paused · clock still running" else "Drag the line onto the finish line",
                        color = Palette.Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LineButton("Released too early? Tap at the real start", { m.correctStartToNow() },
                Modifier.fillMaxWidth(), enabled = m.running)
            Stepper(
                "Race offset",
                (if (m.raceOffset >= 0) "+" else "−") + String.format(java.util.Locale.US, "%.2f s", kotlin.math.abs(m.raceOffset)),
                onMinus = { m.adjustRaceOffset(-0.01) },
                onPlus = { m.adjustRaceOffset(0.01) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (m.recordingNow) {
                    MaroonButton("❚❚  Pause recording", { m.pauseRecording() }, Modifier.weight(1f))
                } else {
                    GoldButton("●  Start recording", { m.startRecording() }, Modifier.weight(1f), enabled = m.running)
                }
                LineButton("Tap split", { m.tapSplit() }, Modifier.width(104.dp).height(54.dp), enabled = m.running)
            }
            val clipsText = m.clipSpans.mapIndexed { i, (a, b) ->
                "Clip ${i + 1}: ${TimeMath.clock(a)} → ${b?.let { TimeMath.clock(it) } ?: "recording…"}"
            }
            val splitText = m.splits.mapIndexedNotNull { i, mono -> m.raceTime(mono)?.let { "#${i + 1} ${TimeMath.format(it)}" } }
            if (clipsText.isEmpty() && splitText.isEmpty()) {
                Label(if (m.running) "Press Start recording as runners get close to the line." else "Start the race on the Start tab first.")
            }
            clipsText.takeLast(3).forEach { Text(it, fontFamily = Mono, color = Palette.Ink2, fontSize = 13.sp) }
            if (splitText.isNotEmpty()) Text("Splits: " + splitText.takeLast(6).joinToString("  "), color = Palette.Gold, fontSize = 13.sp)
            MaroonButton(if (m.saving) "Saving…" else "End race & save .bvid", { m.endRace() }, Modifier.fillMaxWidth(),
                enabled = m.running && !m.saving)
        }
    }
}
