package com.kadrava.audio.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kadrava.audio.util.asciiBar

/** Draggable ASCII slider: `[#####-----]`. Also operable through accessibility "set progress". */
@Composable
fun TermSlider(label: String, valueText: String, fraction: Float, onFraction: (Float) -> Unit) {
    var width by remember { mutableIntStateOf(1) }
    val update by rememberUpdatedState(onFraction)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TermText(label, color = Palette.Dim, size = 12.sp)
            Spacer(Modifier.weight(1f))
            TermText(valueText, size = 13.sp)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .onSizeChanged { width = it.width.coerceAtLeast(1) }
                .pointerInput(Unit) { detectTapGestures { update((it.x / width).coerceIn(0f, 1f)) } }
                .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> update((change.position.x / width).coerceIn(0f, 1f)) } }
                .semantics {
                    contentDescription = label
                    stateDescription = valueText
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    setProgress { target -> update(target.coerceIn(0f, 1f)); true }
                },
            contentAlignment = Alignment.CenterStart,
        ) { TermText(asciiBar(fraction, 28), color = Palette.Prompt) }
    }
}
