package com.kadrava.audio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.kadrava.audio.R

object Term {
    /** DejaVu Sans Mono (bundled, see third_party/DejaVu-LICENSE.txt). The only typeface in the app. */
    val font = FontFamily(Font(R.font.dejavu_sans_mono))
    val base: TextUnit = 14.sp
}

object Palette {
    /** Light theme swaps white and black everywhere; buttons/sliders stay red, the logo and title keep their colours. */
    var light by mutableStateOf(false)

    val Bg: Color get() = if (light) Color(0xFFFFFFFF) else Color(0xFF000000)   // absolute black / absolute white
    val Fg: Color get() = if (light) Color(0xFF000000) else Color(0xFFFFFFFF)
    val Dim: Color get() = Fg        // alias: no gray text
    val Prompt: Color get() = Fg
    val Error: Color get() = Fg
    val Version: Color get() = Fg
    val Glyph: Color get() = Fg
    val Button = Color(0xFFFF3B30)
    /** Colour a button/slider flashes to when touched (it only flashes, it does not stay). */
    val Selected: Color get() = Fg
    val Disabled: Color get() = if (light) Color(0xFF6E6E6E) else Color(0xFF8E8E8E)
    val Rainbow = listOf(            // 7 header letters
        Color(0xFFFF0000), Color(0xFFFB7600), Color(0xFFFAE300), Color(0xFF47CC00),
        Color(0xFF0080FF), Color(0xFF5000FF), Color(0xFF8000EE),
    )
}

@Composable
fun KadavraTheme(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.Bg)) { content() }
}

@Composable
fun TermText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Palette.Fg,
    size: TextUnit = Term.base,
    maxLines: Int = Int.MAX_VALUE,
) = BasicText(
    text = text,
    modifier = modifier,
    style = TextStyle(color = color, fontFamily = Term.font, fontSize = size),
    maxLines = maxLines,
    overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
)
