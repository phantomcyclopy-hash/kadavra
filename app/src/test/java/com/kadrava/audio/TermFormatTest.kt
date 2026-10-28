package com.kadrava.audio

import com.kadrava.audio.util.asciiBar
import com.kadrava.audio.util.formatTime
import org.junit.Assert.assertEquals
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
}
