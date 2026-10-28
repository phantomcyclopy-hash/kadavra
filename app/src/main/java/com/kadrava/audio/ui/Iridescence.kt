package com.kadrava.audio.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.floor

/**
 * Thin-film style palette: cyan -> blue -> violet -> magenta with a faint green glint,
 * cyclic so a moving phase never jumps.
 */
object Iridescence {
    private val stops = listOf(
        Color(0xFF00E5FF), Color(0xFF3D7BFF), Color(0xFF8A5CFF),
        Color(0xFFE040FB), Color(0xFF5CF2B0),
    )

    fun at(t: Float, phase: Float = 0f): Color {
        val x = (((t + phase) % 1f) + 1f) % 1f * stops.size
        val i = floor(x).toInt().coerceIn(0, stops.size - 1)
        return lerp(stops[i], stops[(i + 1) % stops.size], x - i)
    }

    /** Colour of one ASCII cell: position picks the hue, intensity picks how far it emerges from black. */
    fun cell(t: Float, intensity: Float): Color =
        lerp(Color(0xFF0B0E12), at(t * 0.8f), 0.35f + 0.65f * intensity.coerceIn(0f, 1f))

    /** The 12 logo dots, clockwise from 12 o'clock (sampled from the supplied logo). */
    val logoDots = listOf(
        Color(0xFFFF0000), Color(0xFFFB7600), Color(0xFFFAE300), Color(0xFF47CC00),
        Color(0xFF00973C), Color(0xFF00BFFF), Color(0xFF0080FF), Color(0xFF0022FF),
        Color(0xFF5000FF), Color(0xFFB400B8), Color(0xFFFF0080), Color(0xFF8000EE),
    )
}
