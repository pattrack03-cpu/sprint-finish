package com.topsprintspeed.finish

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

enum class Direction(val label: String) {
    /** Runners cross the screen moving left to right. Time runs right-to-left in the image so they face forward. */
    LEFT_TO_RIGHT("Left → right"),
    RIGHT_TO_LEFT("Right → left"),
}

/** Pure timing math, kept free of Android so it can be unit-tested. */
object TimeMath {
    const val SPEED_OF_SOUND = 343.0

    /** Track rule: fully automatic times are rounded UP to the next hundredth. */
    fun officialRoundUp(seconds: Double): Double = ceil(seconds * 100.0 - 1e-4) / 100.0

    fun format(seconds: Double, decimals: Int = 2): String {
        val neg = seconds < 0
        val t = abs(seconds)
        val minutes = floor(t / 60.0).toInt()
        val rest = t - minutes * 60
        val body = if (minutes > 0) {
            String.format(Locale.US, "%d:%0${decimals + 3}.${decimals}f", minutes, rest)
        } else {
            String.format(Locale.US, "%.${decimals}f", rest)
        }
        return if (neg) "-$body" else body
    }

    /** Clock display, always with minutes: 0:09.86 */
    fun clock(seconds: Double): String {
        val t = if (seconds < 0) 0.0 else seconds
        val minutes = floor(t / 60.0).toInt()
        return String.format(Locale.US, "%d:%05.2f", minutes, t - minutes * 60)
    }

    fun soundDelaySeconds(distanceM: Double): Double = distanceM / SPEED_OF_SOUND

    /** Left x (in image pixels) of slice [k] out of [n] slices, each [w] pixels wide. */
    fun sliceX(k: Int, w: Int, n: Int, dir: Direction): Int =
        if (dir == Direction.LEFT_TO_RIGHT) (n - 1 - k) * w else k * w

    /** Which slice is under image pixel column [x]. */
    fun sliceIndexAt(x: Int, w: Int, n: Int, dir: Direction): Int {
        if (n <= 0) return 0
        val col = (x / w).coerceIn(0, n - 1)
        return if (dir == Direction.LEFT_TO_RIGHT) n - 1 - col else col
    }

    /** Pick the fastest shutter (1/x s) whose needed ISO stays under [isoMax], given the auto-exposure result. */
    fun chooseShutter(aeExposureNs: Long, aeIso: Int, candidates: List<Int>, isoMax: Int): Pair<Int, Int> {
        val total = aeExposureNs.toDouble() * aeIso
        for (c in candidates) {
            val iso = total / (1e9 / c)
            if (iso <= isoMax) return c to iso.toInt().coerceAtLeast(1)
        }
        val last = candidates.last()
        return last to isoMax
    }
}
