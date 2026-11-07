package com.topsprintspeed.finish

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeMathTest {
    @Test
    fun roundsUpToHundredths() {
        assertEquals(10.71, TimeMath.officialRoundUp(10.701), 1e-9)
        assertEquals(10.70, TimeMath.officialRoundUp(10.70), 1e-9)
        assertEquals(10.70, TimeMath.officialRoundUp(10.6999999999), 1e-9)
        assertEquals(11.00, TimeMath.officialRoundUp(10.9951), 1e-9)
    }

    @Test
    fun formats() {
        assertEquals("10.71", TimeMath.format(10.71))
        assertEquals("1:02.30", TimeMath.format(62.3))
        assertEquals("0:09.86", TimeMath.clock(9.86))
        assertEquals("10.705", TimeMath.format(10.705, 3))
    }

    @Test
    fun soundDelay() {
        assertEquals(0.02915, TimeMath.soundDelaySeconds(10.0), 1e-4)
    }

    @Test
    fun sliceIndexIsInverseOfSliceX() {
        for (dir in Direction.values()) {
            for (w in 1..4) {
                val n = 50
                for (k in 0 until n) {
                    val x = TimeMath.sliceX(k, w, n, dir)
                    assertEquals(k, TimeMath.sliceIndexAt(x, w, n, dir))
                    assertEquals(k, TimeMath.sliceIndexAt(x + w - 1, w, n, dir))
                }
            }
        }
    }

    @Test
    fun earliestSliceIsOnTheRightForLeftToRight() {
        assertEquals(9, TimeMath.sliceX(0, 1, 10, Direction.LEFT_TO_RIGHT))
        assertEquals(0, TimeMath.sliceX(0, 1, 10, Direction.RIGHT_TO_LEFT))
    }

    @Test
    fun choosesFastestShutterThatFits() {
        // AE chose 1/250 s at ISO 100 -> 1/2000 needs ISO 800, fits under 1600.
        val (s, iso) = TimeMath.chooseShutter(4_000_000L, 100, listOf(2000, 1000, 500), 1600)
        assertEquals(2000, s)
        assertEquals(800, iso)
        // Dim: AE 1/60 at ISO 800 -> nothing fits, falls back to slowest at max ISO.
        val (s2, iso2) = TimeMath.chooseShutter(16_666_666L, 800, listOf(2000, 1000, 500), 1600)
        assertEquals(500, s2)
        assertEquals(1600, iso2)
    }
}
