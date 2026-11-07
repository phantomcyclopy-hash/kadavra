package com.kadrava.audio

import com.kadrava.audio.util.asciiBar
import com.kadrava.audio.util.designVersion
import com.kadrava.audio.util.ellipsize
import com.kadrava.audio.util.formatClock
import com.kadrava.audio.util.formatPrecise
import com.kadrava.audio.util.formatTime
import com.kadrava.audio.util.parseNumber
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

    @Test fun parseNumberAcceptsCommonTypings() {
        assertEquals(12.0, parseNumber("12")!!, 0.0)
        assertEquals(12.5, parseNumber("12,5")!!, 0.0)
        assertEquals(-3.25, parseNumber("-3.25 dB")!!, 0.0)
        assertEquals(8.0, parseNumber("8x")!!, 0.0)
        assertEquals(78.0, parseNumber("1:18")!!, 0.0)
        assertEquals(3725.0, parseNumber("62:05")!!, 0.0)
        assertEquals(null, parseNumber("abc"))
        assertEquals(null, parseNumber(""))
    }

    @Test fun preciseTimeShowsMilliseconds() {
        assertEquals("1:18.250", formatPrecise(78.25))
        assertEquals("0:00.000", formatPrecise(0.0))
        assertEquals("0:00.000", formatPrecise(-3.0))
        assertEquals("62:05.500", formatPrecise(3725.5))
        assertEquals(78.25, parseNumber("1:18.250")!!, 1e-9)
    }
}
