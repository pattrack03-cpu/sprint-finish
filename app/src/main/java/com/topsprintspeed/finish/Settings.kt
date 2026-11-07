package com.topsprintspeed.finish

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class StartMode(val label: String) { TOUCH("Touch"), GUN("Gun"), VOICE("Voice") }

enum class StartSoundKind(val label: String) { BANG("Pistol bang"), BEEP("Beep"), NONE("None") }

/** All user settings, saved on the phone. Change them with [update] so they persist. */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // Camera / photo finish
    var fps by mutableStateOf(p.getInt("fps", 240))
    var shutter by mutableStateOf(p.getInt("shutter", 0)) // 0 = auto, else 1/x s
    var sliceWidth by mutableStateOf(p.getInt("sliceWidth", 1))
    var direction by mutableStateOf(Direction.values()[p.getInt("direction", 0)])
    var rotationOverride by mutableStateOf(p.getInt("rotation", -1)) // -1 = from camera
    var linePos by mutableStateOf(p.getFloat("linePos", 0.5f))

    // Start
    var startMode by mutableStateOf(StartMode.values()[p.getInt("startMode", 0)])
    var offsetTouch by mutableStateOf(p.getFloat("offTouch", 0f).toDouble())
    var offsetGun by mutableStateOf(p.getFloat("offGun", 0f).toDouble())
    var offsetVoice by mutableStateOf(p.getFloat("offVoice", 0f).toDouble())
    var gunThresholdDb by mutableStateOf(p.getFloat("gunDb", -12f))
    var gunDistanceM by mutableStateOf(p.getFloat("gunDist", 0f))
    var shotFilter by mutableStateOf(p.getBoolean("shotFilter", true))
    var startSound by mutableStateOf(StartSoundKind.values()[p.getInt("startSound", 0)])
    var voiceHoldMin by mutableStateOf(p.getFloat("holdMin", 1.5f))
    var voiceHoldMax by mutableStateOf(p.getFloat("holdMax", 2.5f))

    fun offsetFor(mode: StartMode): Double = when (mode) {
        StartMode.TOUCH -> offsetTouch
        StartMode.GUN -> offsetGun
        StartMode.VOICE -> offsetVoice
    }

    fun setOffset(mode: StartMode, v: Double) = update {
        val r = Math.round(v * 100.0) / 100.0
        when (mode) {
            StartMode.TOUCH -> offsetTouch = r
            StartMode.GUN -> offsetGun = r
            StartMode.VOICE -> offsetVoice = r
        }
    }

    fun update(block: Settings.() -> Unit) {
        block()
        p.edit()
            .putInt("fps", fps).putInt("shutter", shutter).putInt("sliceWidth", sliceWidth)
            .putInt("direction", direction.ordinal).putInt("rotation", rotationOverride)
            .putFloat("linePos", linePos)
            .putInt("startMode", startMode.ordinal)
            .putFloat("offTouch", offsetTouch.toFloat()).putFloat("offGun", offsetGun.toFloat())
            .putFloat("offVoice", offsetVoice.toFloat())
            .putFloat("gunDb", gunThresholdDb).putFloat("gunDist", gunDistanceM)
            .putBoolean("shotFilter", shotFilter).putInt("startSound", startSound.ordinal)
            .putFloat("holdMin", voiceHoldMin).putFloat("holdMax", voiceHoldMax)
            .apply()
    }
}
