package com.kadrava.audio

import com.kadrava.audio.util.asciiBar
import com.kadrava.audio.util.designVersion
import com.kadrava.audio.util.ellipsize
import com.kadrava.audio.util.formatClock
import com.kadrava.audio.util.formatTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TermFormatTest {
    @Test fun formatsMinutesAndHours() {
        assertEquals("01:05", formatTime(65_000))
        assertEquals("1:02:05", formatTime(3_725_000))
    }
    @Test fun negativeClampsToZero() = assertEquals("00:00", formatTime(-5))
    @Test fun barHalf() = assertEquals("[#####-----]", asciiBar(0.5f, 10))
    @Test fun barClamps() {
        assertEquals("[----------]", asciiBar(Float.NaN, 10))
        assertEquals("[##########]", asciiBar(1.5f, 10))
        assertEquals("[----------]", asciiBar(-1f, 10))
    }

    @Test fun clockHasNoLeadingZeroOnMinutes() {
        assertEquals("1:18", formatClock(78_000))
        assertEquals("0:00", formatClock(-1))
        assertEquals("3:45", formatClock(225_000))
        assertEquals("1:02:05", formatClock(3_725_000))
    }
    @Test fun designVersionFormat() {
        assertEquals("v0.03.0", designVersion("0.3.0"))
        assertEquals("v1.12.4", designVersion("1.12.4"))
        assertEquals("vabc", designVersion("abc"))
    }
    @Test fun ellipsizeKeepsExtension() {
        assertEquals("short.wav", ellipsize("short.wav", 20))
        val e = ellipsize("a_very_long_file_name.wav", 12)
        assertEquals(12, e.length)
        assertTrue(e.endsWith(".wav") && e.contains("…"))
    }
}
