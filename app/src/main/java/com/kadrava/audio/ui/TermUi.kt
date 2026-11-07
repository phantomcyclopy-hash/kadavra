package com.kadrava.audio.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import com.kadrava.audio.util.parseNumber

/** Number of monospace columns that fit the screen width (measured at the root). */
val LocalTermCols = compositionLocalOf { 40 }

/** Which button/slider was touched last. It only flashes: the mark clears itself a moment later. */
class SelectionState(private val scope: CoroutineScope) {
    var selected by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    fun select(key: String) {
        selected = key
        job?.cancel()
        job = scope.launch { delay(260); selected = null }
    }
}

val LocalSelection = compositionLocalOf { SelectionState(CoroutineScope(Dispatchers.Main)) }

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
    val c by animateColorAsState(target, tween(160), label = "control")
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
    LaunchedEffect(pressed) { if (pressed && clickable && itemKey != null) sel.select(itemKey) }
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
            if (itemKey != null) sel.select(itemKey)
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

/** `----#-----`: ten dashes, red; the `#` marker is red until this is the last control selected. */
fun sliderBar(len: Int, fraction: Float, markerColor: Color, enabled: Boolean = true) = buildAnnotatedString {
    val hash = if (enabled) Math.round(fraction.coerceIn(0f, 1f) * (len - 1)) else -1
    for (i in 0 until len) {
        if (i == hash) withStyle(SpanStyle(color = markerColor)) { append("#") }
        else withStyle(SpanStyle(color = if (enabled) Palette.Button else Palette.Disabled)) { append("-") }
    }
}

/**
 * A number the user can type. Tap, type any value (decimal point or comma; "m:ss" when [time]) and press
 * Done or tap elsewhere: [onType] receives the parsed number (callers clamp it to the valid range).
 * Without [onType] it is plain text.
 */
@Composable
fun TermNumberField(
    valueText: String, key: String, onType: ((Double) -> Unit)?,
    modifier: Modifier = Modifier, time: Boolean = false, centered: Boolean = false,
) {
    val sel = LocalSelection.current
    val focus = LocalFocusManager.current
    var tfv by remember { mutableStateOf(TextFieldValue(valueText)) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(valueText, focused) { if (!focused) tfv = TextFieldValue(valueText) }
    if (onType == null) {
        BasicText(valueText, modifier = modifier, style = termStyle(Term.base).copy(textAlign = if (centered) TextAlign.Center else TextAlign.Start))
    } else {
        BasicTextField(
            value = tfv,
            onValueChange = { tfv = it },
            singleLine = true,
            textStyle = termStyle(Term.base).copy(textAlign = if (centered) TextAlign.Center else TextAlign.Start),
            cursorBrush = SolidColor(Palette.Fg),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (time) KeyboardType.Text else KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = modifier.onFocusChanged { st ->
                if (st.isFocused && !focused) {
                    focused = true
                    sel.select(key)
                    tfv = tfv.copy(selection = TextRange(0, tfv.text.length))
                } else if (!st.isFocused && focused) {
                    focused = false
                    parseNumber(tfv.text)?.let(onType)
                }
            },
        )
    }
}

/** Fraction (0..1) of a pointer x inside a bar that has [padPx] of extra touch area on each side. */
private fun barFraction(x: Float, widthPx: Int, padPx: Float): Float =
    ((x - padPx) / (widthPx - 2 * padPx).coerceAtLeast(1f)).coerceIn(0f, 1f)

/** Compact slider: label, 10-dash bar (drag or tap) and a typeable value, all on one line. */
@Composable
fun TermSlider(
    label: String, valueText: String, fraction: Float,
    onFraction: (Float) -> Unit, onType: ((Double) -> Unit)? = null,
) {
    val sel = LocalSelection.current
    val key = label.trim()
    var widthPx by remember { mutableIntStateOf(1) }
    val padPx = with(LocalDensity.current) { 10.dp.toPx() }
    val update by rememberUpdatedState(onFraction)
    val marker = controlColor(key)
    val f = fraction.coerceIn(0f, 1f)
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(label.padEnd(14) + " ", style = termStyle(Term.base))
        Box(
            Modifier
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { sel.select(key) },
                        onTap = { update(barFraction(it.x, widthPx, padPx)) },
                    )
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(onDragStart = { sel.select(key) }) { change, _ ->
                        update(barFraction(change.position.x, widthPx, padPx))
                    }
                }
                .semantics {
                    contentDescription = key
                    stateDescription = valueText
                    progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f)
                    setProgress { target -> sel.select(key); update(target.coerceIn(0f, 1f)); true }
                }
                .padding(horizontal = 10.dp, vertical = 10.dp)
        ) { BasicText(sliderBar(10, f, marker), style = termStyle(Term.base)) }
        BasicText("  ", style = termStyle(Term.base))
        TermNumberField(valueText, key, onType, Modifier.weight(1f))
    }
}

/** Fraction (0..1) of a pointer x inside a bar with [padPx] of touch area on each side. */
private fun fractionAt(x: Float, widthPx: Int, padPx: Float): Float =
    ((x - padPx) / (widthPx - 2 * padPx).coerceAtLeast(1f)).coerceIn(0f, 1f)

/** The draggable bar itself (tap or drag), red dashes with a `#` marker. */
@Composable
fun SliderBar(
    key: String, fraction: Float, barLen: Int, valueText: String,
    enabled: Boolean = true, onFraction: (Float) -> Unit,
) {
    val sel = LocalSelection.current
    var widthPx by remember { mutableIntStateOf(1) }
    val padPx = with(LocalDensity.current) { 8.dp.toPx() }
    val update by rememberUpdatedState(onFraction)
    val marker = controlColor(key)
    val f = fraction.coerceIn(0f, 1f)
    Box(
        Modifier
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(enabled) {
                if (enabled) detectTapGestures(onPress = { sel.select(key) }, onTap = { update(fractionAt(it.x, widthPx, padPx)) })
            }
            .pointerInput(enabled) {
                if (enabled) detectHorizontalDragGestures(onDragStart = { sel.select(key) }) { change, _ ->
                    sel.select(key)
                    update(fractionAt(change.position.x, widthPx, padPx))
                }
            }
            .semantics {
                contentDescription = key
                stateDescription = valueText
                progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f)
                setProgress { target -> sel.select(key); update(target.coerceIn(0f, 1f)); true }
            }
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) { BasicText(sliderBar(barLen, f, marker, enabled), style = termStyle(Term.base)) }
}

/** Grid cell: label on top, 10-dash bar, typeable value below; everything centred. */
@Composable
fun CellSlider(
    label: String, valueText: String, fraction: Float,
    onFraction: (Float) -> Unit, onType: ((Double) -> Unit)?, modifier: Modifier = Modifier,
) {
    Column(modifier.heightIn(min = 58.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(label, style = termStyle(12.sp), maxLines = 1)
        SliderBar(label, fraction, 10, valueText, true, onFraction)
        TermNumberField(valueText, label, onType, Modifier.fillMaxWidth(), centered = true)
    }
}

/** Wide slider for precise work (loop start/end, speed): longer bar, typeable value and [-] / [+] nudges. */
@Composable
fun WideSlider(
    label: String, valueText: String, fraction: Float,
    onFraction: (Float) -> Unit, onType: ((Double) -> Unit)?,
    onMinus: (() -> Unit)?, onPlus: (() -> Unit)?, modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(label.padEnd(6), style = termStyle(12.sp))
        SliderBar(label, fraction, 18, valueText, true, onFraction)
        TermNumberField(valueText, label, onType, Modifier.weight(1f), centered = true)
        if (onMinus != null) TermItem(listOf(Span("[-]", kind = SpanKind.CONTROL)), separator = false, key = "$label-", onClick = onMinus)
        if (onPlus != null) TermItem(listOf(Span("[+]", kind = SpanKind.CONTROL)), separator = false, key = "$label+", onClick = onPlus)
    }
}
