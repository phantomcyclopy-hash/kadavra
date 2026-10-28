package com.kadrava.audio.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

sealed interface LogoState {
    data object Idle : LogoState
    data object Indeterminate : LogoState            // work running, progress not measurable
    data class Progress(val fraction: Float) : LogoState // measured progress
    data object Error : LogoState
}

@Composable
fun rememberReducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

@Composable
fun KadavraLogo(
    state: LogoState,
    modifier: Modifier = Modifier.size(32.dp),
    reducedMotion: Boolean = rememberReducedMotion(),
) {
    val animating = state is LogoState.Indeterminate && !reducedMotion
    val phase = remember { Animatable(0f) }
    LaunchedEffect(animating) {
        if (animating) {
            while (true) {
                phase.snapTo(0f)
                phase.animateTo(1f, tween(1100, easing = LinearEasing))
            }
        } else phase.snapTo(0f)
    }
    val description = when (state) {
        LogoState.Idle -> "Logo do Kadavra"
        LogoState.Indeterminate -> "Kadavra: processando"
        is LogoState.Progress -> "Kadavra: ${(state.fraction * 100).toInt()} por cento concluído"
        LogoState.Error -> "Kadavra: erro"
    }
    Canvas(modifier.semantics { contentDescription = description }) {
        val ring = min(size.width, size.height) / 2f * 0.82f
        val dotR = ring * 0.19f
        val c = Offset(size.width / 2f, size.height / 2f)
        for (i in 0 until 12) {
            val ang = (-90.0 + 30.0 * i) * PI / 180.0
            val p = Offset(c.x + ring * cos(ang).toFloat(), c.y + ring * sin(ang).toFloat())
            var color = Iridescence.logoDots[i]
            var alpha = 1f
            when (state) {
                LogoState.Idle -> {}
                LogoState.Indeterminate ->
                    alpha = if (reducedMotion) 0.6f else {
                        val head = phase.value * 12f
                        val d = ((head - i) % 12f + 12f) % 12f
                        (1f - d / 12f * 0.85f).coerceAtLeast(0.15f)
                    }
                is LogoState.Progress -> {
                    val lit = state.fraction.coerceIn(0f, 1f) * 12f
                    val whole = floor(lit).toInt()
                    alpha = when {
                        i < whole -> 1f
                        i == whole -> 0.2f + 0.8f * (lit - whole)
                        else -> 0.18f
                    }
                }
                LogoState.Error -> {
                    if (i != 0) { color = Color(0xFF555555); alpha = 0.7f }
                }
            }
            drawCircle(color.copy(alpha = alpha), dotR, p)
        }
    }
}
