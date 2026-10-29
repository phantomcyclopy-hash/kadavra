package com.kadrava.audio.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

/** Number of monospace columns that fit the screen width (measured at the root). */
val LocalTermCols = compositionLocalOf { 40 }

/** Remembers which button/slider was selected last; only that one is drawn in the highlight colour. */
class SelectionState {
    var selected by mutableStateOf<String?>(null)
}

val LocalSelection = compositionLocalOf { SelectionState() }

/**
 * PLAIN: fixed colour (white). CONTROL: button/slider part, red, turns to the highlight colour (with a
 * fade) while it is the last selected control. OPTION: menu entry, white, fades to red while [TermItem.active].
 */
enum class SpanKind { PLAIN, CONTROL, OPTION }

class Span(val text: String, val color: Color = Palette.Fg, val kind: SpanKind = SpanKind.PLAIN)

private fun termStyle(size: TextUnit, color: Color = Palette.Fg) =
    TextStyle(fontFamily = Term.font, fontSize = size, color = color)

/** Colour of a control's marker: red normally, highlight colour (fading) while [key] is the last selected. */
@Composable
fun controlColor(key: String?): Color {
    val sel = LocalSelection.current
    val target = if (key != null && sel.selected == key) Palette.Selected else Palette.Button
    val c by animateColorAsState(target, tween(300), label = "control")
    return c
}

/**
 * One terminal line followed by the `>` prompt line (unless [separator] is false); the whole thing is the
 * touch target. No cursor block: selection is shown only by the colour change of the item itself.
 */
@Composable
fun TermItem(
    spans: List<Span>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    separator: Boolean = true,
    size: TextUnit = Term.base,
    description: String? = null,
    key: String? = null,
    active: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val sel = LocalSelection.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val itemKey = key ?: spans.filter { it.kind == SpanKind.CONTROL }.joinToString("|") { it.text }.ifEmpty { null }
    val clickable = onClick != null && enabled
    LaunchedEffect(pressed) { if (pressed && clickable && itemKey != null) sel.selected = itemKey }
    val ctrl = controlColor(itemKey)
    val option by animateColorAsState(if (active) Palette.Button else Palette.Fg, tween(300), label = "option")
    val text = buildAnnotatedString {
        for (s in spans) {
            val c = when {
                !enabled -> Palette.Disabled
                s.kind == SpanKind.CONTROL -> ctrl
                s.kind == SpanKind.OPTION -> option
                else -> s.color
            }
            withStyle(SpanStyle(color = c)) { append(s.text) }
        }
    }
    val m = if (clickable) modifier.clickable(
        interactionSource = src, indication = null, role = Role.Button,
        onClick = {
            if (itemKey != null) sel.selected = itemKey
            onClick?.invoke()
        },
    ) else modifier
    Column(
        m.heightIn(min = if (clickable) 44.dp else 0.dp)
            .semantics { if (description != null) contentDescription = description }
    ) {
        BasicText(text, style = termStyle(size))
        if (separator) BasicText(">", style = termStyle(size))
    }
}

/** `------#------`: dashes red, the `#` marker red until the slider is the last one selected. */
fun sliderBar(len: Int, fraction: Float, markerColor: Color, enabled: Boolean = true) = buildAnnotatedString {
    val hash = if (enabled) Math.round(fraction.coerceIn(0f, 1f) * (len - 1)) else -1
    for (i in 0 until len) {
        if (i == hash) withStyle(SpanStyle(color = markerColor)) { append("#") }
        else withStyle(SpanStyle(color = if (enabled) Palette.Button else Palette.Disabled)) { append("-") }
    }
}

/** ASCII slider: a label/value line and a bar line; drag or tap to set. */
@Composable
fun TermSlider(label: String, valueText: String, fraction: Float, onFraction: (Float) -> Unit) {
    val cols = LocalTermCols.current
    val sel = LocalSelection.current
    val key = label.trim()
    var widthPx by remember { mutableIntStateOf(1) }
    val update by rememberUpdatedState(onFraction)
    val marker = controlColor(key)
    val barLen = (cols - 2).coerceAtLeast(8)
    val f = fraction.coerceIn(0f, 1f)
    val bar = buildAnnotatedString {
        append("  ")
        append(sliderBar(barLen, f, marker))
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { sel.selected = key },
                    onTap = { update((it.x / widthPx).coerceIn(0f, 1f)) },
                )
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragStart = { sel.selected = key }) { change, _ ->
                    update((change.position.x / widthPx).coerceIn(0f, 1f))
                }
            }
            .semantics {
                contentDescription = key
                stateDescription = valueText
                progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f)
                setProgress { target -> sel.selected = key; update(target.coerceIn(0f, 1f)); true }
            }
    ) {
        BasicText(label + " " + valueText, style = termStyle(Term.base))
        BasicText(bar, style = termStyle(Term.base))
        BasicText(">", style = termStyle(Term.base))
    }
}
