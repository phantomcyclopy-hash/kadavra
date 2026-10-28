package com.kadrava.audio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.kadrava.audio.viz.AsciiWaveformRenderer

/**
 * Peak-envelope waveform in ASCII. The text rows are laid out once per (data, size) and cached;
 * playback only moves the playhead, which is drawn in the draw phase (no recomposition per tick).
 */
@Composable
fun AsciiWaveform(
    peaks: FloatArray?,
    progress: () -> Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    rows: Int = 9,
) {
    val measurer: TextMeasurer = rememberTextMeasurer()
    val style = remember { TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
    val cell = remember(style, measurer) { measurer.measure("M", style).size }
    val heightDp = with(LocalDensity.current) { (cell.height * rows).toDp() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val cols = if (cell.width > 0) size.width / cell.width else 0

    val layouts: List<TextLayoutResult> = remember(peaks, cols, rows, style, measurer) {
        if (peaks == null) {
            listOf(measurer.measure("-- sem áudio carregado --", style.copy(color = Palette.Dim), softWrap = false, maxLines = 1))
        } else if (cols <= 0) emptyList() else {
            val g = AsciiWaveformRenderer.render(peaks, cols, rows)
            List(rows) { r ->
                measurer.measure(
                    buildAnnotatedString {
                        for (c in 0 until cols) {
                            withStyle(SpanStyle(color = Iridescence.cell(c.toFloat() / cols, g.intensityAt(r, c)))) {
                                append(g.charAt(r, c))
                            }
                        }
                    }, style, softWrap = false, maxLines = 1,
                )
            }
        }
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(heightDp)
            .onSizeChanged { size = it }
            .semantics { contentDescription = "Forma de onda ASCII do áudio. Toque ou arraste para buscar." }
            .pointerInput(peaks) {
                if (peaks != null) detectTapGestures { o -> onSeek((o.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)) }
            }
            .pointerInput(peaks) {
                if (peaks != null) detectHorizontalDragGestures { change, _ ->
                    onSeek((change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                }
            }
    ) {
        if (peaks == null) {
            layouts.firstOrNull()?.let { drawText(it, topLeft = Offset(0f, (this.size.height - it.size.height) / 2f)) }
        } else {
            layouts.forEachIndexed { r, l -> drawText(l, topLeft = Offset(0f, r * cell.height.toFloat())) }
            if (cols > 0) {
                val col = (progress().coerceIn(0f, 1f) * cols).toInt().coerceAtMost(cols - 1)
                val x = col * cell.width.toFloat()
                drawRect(Color.White.copy(alpha = 0.16f), Offset(x, 0f), Size(cell.width.toFloat(), this.size.height))
                drawRect(Color.White, Offset(x, 0f), Size(2f, this.size.height))
            }
        }
    }
}
