package com.kadrava.audio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Number of monospace columns that fit the screen width (measured at the root). */
val LocalTermCols = compositionLocalOf { 40 }

class Span(val text: String, val color: Color = Palette.Fg)

private fun termStyle(size: TextUnit, color: Color = Palette.Fg) =
    TextStyle(fontFamily = Term.font, fontSize = size, color = color)

/**
 * One terminal line. When clickable, a green cursor block appears right after the text only while the
 * item is pressed and for a moment after it is activated. Followed by the `>` prompt line unless
 * [separator] is false; the whole thing is the touch target.
 */
@Composable
fun TermItem(
    spans: List<Span>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    separator: Boolean = true,
    size: TextUnit = Term.base,
    description: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    var recent by remember { mutableStateOf(false) }
    val clickable = onClick != null && enabled
    val text = buildAnnotatedString {
        for (s in spans) withStyle(SpanStyle(color = if (enabled) s.color else Palette.Dim)) { append(s.text) }
        if (clickable) withStyle(SpanStyle(background = if (pressed || recent) Palette.Cursor else Color.Transparent)) { append(" ") }
    }
    val m = if (clickable) modifier.clickable(
        interactionSource = src, indication = null, role = Role.Button,
        onClick = {
            recent = true
            scope.launch { delay(350); recent = false }
            onClick?.invoke()
        },
    ) else modifier
    Column(
        m.heightIn(min = if (clickable) 44.dp else 0.dp)
            .semantics { if (description != null) contentDescription = description }
    ) {
        BasicText(text, style = termStyle(size))
        if (separator) BasicText(">", style = termStyle(size, Palette.Dim))
    }
}

/** ASCII slider: a label/value line and a `------#------` bar line; drag or tap to set. */
@Composable
fun TermSlider(label: String, valueText: String, fraction: Float, onFraction: (Float) -> Unit) {
    val cols = LocalTermCols.current
    var widthPx by remember { mutableIntStateOf(1) }
    val update by rememberUpdatedState(onFraction)
    var pressing by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val barLen = (cols - 2).coerceAtLeast(8)
    val f = fraction.coerceIn(0f, 1f)
    val hash = Math.round(f * (barLen - 1))
    val bar = buildAnnotatedString {
        append("  ")
        for (i in 0 until barLen) {
            if (i == hash) withStyle(SpanStyle(color = Palette.Prompt)) { append("#") }
            else withStyle(SpanStyle(color = Palette.Dim)) { append("-") }
        }
        withStyle(SpanStyle(background = if (pressing || dragging) Palette.Cursor else Color.Transparent)) { append(" ") }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { pressing = true; tryAwaitRelease(); pressing = false },
                    onTap = { update((it.x / widthPx).coerceIn(0f, 1f)) },
                )
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ -> update((change.position.x / widthPx).coerceIn(0f, 1f)) }
            }
            .semantics {
                contentDescription = label.trim()
                stateDescription = valueText
                progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f)
                setProgress { target -> update(target.coerceIn(0f, 1f)); true }
            }
    ) {
        BasicText(label + " " + valueText, style = termStyle(Term.base))
        BasicText(bar, style = termStyle(Term.base))
        BasicText(">", style = termStyle(Term.base, Palette.Dim))
    }
}
