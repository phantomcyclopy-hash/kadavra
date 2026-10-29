package com.kadrava.audio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
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
    val Bg = Color(0xFF000000)       // absolute OLED black
    val Fg = Color(0xFFF2F2F2)
    val Dim = Color(0xFF8E8E8E)      // ~6:1 contrast on black
    val Prompt = Color(0xFF55E36B)   // terminal green
    val Cursor = Color(0xFF00C800)   // touch cursor block
    val Error = Color(0xFFFF6B6B)
    val Version = Color(0xFFE53935)
    val Glyph = Color(0xFFB8D43A)    // yellow-green activity glyphs
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
