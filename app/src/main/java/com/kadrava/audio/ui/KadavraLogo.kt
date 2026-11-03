package com.kadrava.audio.ui

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

@Composable
fun rememberReducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * The 12-dot logo. Rotation speed is eased, never cut: it turns slowly counter-clockwise while audio is
 * loaded, faster clockwise while processing, and winds down smoothly to rest. While audio plays the colours
 * intensify and each dot's rise/fall and tremble grow with the loudness ([level], 0..1).
 * While processing with measurable [progress], dots light up whole, one per twelfth.
 */
@Composable
fun KadavraLogo(
    hasAudio: Boolean,
    playing: Boolean,
    processing: Boolean,
    error: Boolean,
    progress: Float?,
    level: () -> Float,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = rememberReducedMotion(),
) {
    val hasAudioS by rememberUpdatedState(hasAudio)
    val playingS by rememberUpdatedState(playing)
    val processingS by rememberUpdatedState(processing)
    val levelS by rememberUpdatedState(level)
    var angle by remember { mutableFloatStateOf(0f) }
    var bobPhase by remember { mutableFloatStateOf(0f) }
    var bobAmp by remember { mutableFloatStateOf(0f) }
    var trembleAmp by remember { mutableFloatStateOf(0f) }
    var intensity by remember { mutableFloatStateOf(0.85f) }

    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        var omega = 0f
        var lvl = 0f
        var last = 0L
        while (true) {
            val targetOmega = if (processingS) 100f else if (hasAudioS) -14f else 0f
            val lv = if (playingS) levelS() else 0f
            val bobT = if (processingS) 0.07f else if (playingS) 0.015f + 0.09f * lvl else 0f
            val tremT = if (processingS) 0.012f else if (playingS) 0.004f + 0.03f * lvl else 0f
            val intT = if (processingS) 1f else if (playingS) 0.75f + 0.25f * lvl else if (hasAudioS) 0.55f else 0.85f
            val atRest = abs(omega) < 0.05f && targetOmega == 0f && bobAmp < 0.001f && trembleAmp < 0.001f &&
                abs(intensity - intT) < 0.005f
            if (atRest) { last = 0L; delay(120); continue }
            val now = androidx.compose.runtime.withFrameNanos { it }
            val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
            last = now
            omega += (targetOmega - omega) * (1f - exp(-dt / 0.7f))
            lvl += (lv - lvl) * (1f - exp(-dt / (if (lv > lvl) 0.05f else 0.25f)))
            bobAmp += (bobT - bobAmp) * (1f - exp(-dt / 0.4f))
            trembleAmp += (tremT - trembleAmp) * (1f - exp(-dt / 0.4f))
            intensity += (intT - intensity) * (1f - exp(-dt / 0.5f))
            angle = (angle + omega * dt) % 360f
            bobPhase = (bobPhase + dt * 0.9f) % 1000f
        }
    }

    val lit = if (processing && progress != null) (progress.coerceIn(0f, 1f) * 12f).toInt() else 12
    val staticIntensity = if (processing) 1f else if (hasAudio) 0.55f else 0.85f
    val description = when {
        error -> "Kadavra: error"
        processing -> "Kadavra: processing"
        playing -> "Kadavra: playing"
        else -> "Kadavra"
    }
    Canvas(modifier.semantics { contentDescription = description }) {
        val ring = min(size.width, size.height) / 2f * 0.74f
        val dotR = ring * 0.19f
        val c = Offset(size.width / 2f, size.height / 2f)
        val inten = if (reducedMotion) staticIntensity else intensity
        rotate(degrees = if (reducedMotion) 0f else angle, pivot = c) {
            for (i in 0 until 12) {
                val ang = (-90.0 + 30.0 * i) * PI / 180.0
                val bob = if (reducedMotion) 0f else bobAmp * sin(2 * PI * (bobPhase + i * 0.13)).toFloat() +
                    trembleAmp * sin(2 * PI * (bobPhase * 7 + i * 0.41)).toFloat()
                val r = ring * (1f + bob)
                val p = Offset(c.x + r * cos(ang).toFloat(), c.y + r * sin(ang).toFloat())
                val base = Iridescence.logoDots[i]
                var color = lerp(Color(base.red * 0.35f, base.green * 0.35f, base.blue * 0.35f), base, inten.coerceIn(0f, 1f))
                var alpha = if (i < lit) 1f else 0.18f
                if (error) { if (i == 0) color = base else { color = Color(0xFF555555); alpha = 0.7f } }
                drawCircle(color.copy(alpha = alpha), dotR, p)
            }
        }
    }
}
