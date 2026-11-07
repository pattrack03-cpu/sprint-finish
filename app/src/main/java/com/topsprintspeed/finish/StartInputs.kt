package com.topsprintspeed.finish

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random

/** Plays the start sound (pistol bang or beep) with as little delay as Android allows. */
class StartSound(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()
    private val bang = pool.load(context, R.raw.bang, 1)
    private val beep = pool.load(context, R.raw.beep, 1)

    fun play(kind: StartSoundKind) {
        when (kind) {
            StartSoundKind.BANG -> pool.play(bang, 1f, 1f, 1, 0, 1f)
            StartSoundKind.BEEP -> pool.play(beep, 1f, 1f, 1, 0, 1f)
            StartSoundKind.NONE -> {}
        }
    }

    fun release() = pool.release()
}

/**
 * Listens on the microphone for a starting pistol. Reports the onset time of the shot on the
 * System.nanoTime() clock, worked out from the audio sample position (not when the code noticed it).
 */
class GunDetector(
    private val onLevel: (Float) -> Unit,
    private val onShot: (Long) -> Unit,
) {
    @Volatile private var running = false
    val isRunning get() = running

    @SuppressLint("MissingPermission")
    fun start(thresholdDb: Float, shotFilter: Boolean) {
        if (running) return
        running = true
        Thread({ loop(thresholdDb, shotFilter) }, "gun-detector").start()
    }

    fun stop() { running = false }

    @SuppressLint("MissingPermission")
    private fun loop(thresholdDb: Float, shotFilter: Boolean) {
        val sr = 44100
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, sr,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 4096) * 4
            )
        } catch (e: Exception) { running = false; return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); running = false; return }

        val thr = (32768.0 * 10.0.pow(thresholdDb / 20.0)).toInt().coerceIn(200, 32767)
        val buf = ShortArray(441) // 10 ms
        val ts = AudioTimestamp()
        var framesRead = 0L
        var prevTailAvg = 0.0
        var lastLevelPost = 0L
        rec.startRecording()
        try {
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var peak = 0
                var onset = -1
                for (i in 0 until n) {
                    val a = abs(buf[i].toInt())
                    if (a > peak) peak = a
                    if (onset < 0 && a >= thr) onset = i
                }
                val now = System.nanoTime()
                if (now - lastLevelPost > 50_000_000L) {
                    onLevel((20.0 * log10(max(peak, 1) / 32768.0)).toFloat())
                    lastLevelPost = now
                }
                if (onset >= 0) {
                    // Shot filter: a pistol jumps from quiet to loud almost instantly; voices and claps build up.
                    val quiet = if (!shotFilter) true else {
                        val from = (onset - 220).coerceAtLeast(0)
                        var sum = 0.0
                        for (i in from until onset) sum += abs(buf[i].toInt())
                        val count = onset - from
                        val before = if (count >= 40) sum / count else prevTailAvg
                        before < thr * 0.25
                    }
                    if (quiet) {
                        val onsetFrame = framesRead + onset
                        val mono = if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                            ts.nanoTime + (onsetFrame - ts.framePosition) * 1_000_000_000L / sr
                        } else {
                            now - (n - onset).toLong() * 1_000_000_000L / sr
                        }
                        running = false
                        onShot(mono)
                        break
                    }
                }
                var tail = 0.0
                val from = (n - 220).coerceAtLeast(0)
                for (i in from until n) tail += abs(buf[i].toInt())
                prevTailAvg = tail / (n - from).coerceAtLeast(1)
                framesRead += n
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }
}

/** Self start: calls "On your marks", "Set", then fires after a random hold. */
class VoiceStarter(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context, this)
    private var ready = false
    private val handler = Handler(Looper.getMainLooper())
    private var token = 0

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) tts.language = Locale.US
    }

    fun run(holdMin: Float, holdMax: Float, marksSeconds: Float, onStage: (String) -> Unit, onGo: () -> Unit) {
        val my = ++token
        onStage("On your marks")
        speak("On your marks")
        handler.postDelayed({
            if (my != token) return@postDelayed
            onStage("Set")
            speak("Set")
            val lo = minOf(holdMin, holdMax)
            val hi = maxOf(holdMin, holdMax)
            val hold = ((lo + Random.nextFloat() * (hi - lo)) * 1000f).toLong() + 500L // ~0.5 s to say "Set"
            handler.postDelayed({ if (my == token) onGo() }, hold)
        }, (marksSeconds * 1000).toLong())
    }

    fun cancel() {
        token++
        handler.removeCallbacksAndMessages(null)
        if (ready) tts.stop()
    }

    private fun speak(s: String) {
        if (ready) tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "u$token")
    }

    fun shutdown() { cancel(); tts.shutdown() }
}
