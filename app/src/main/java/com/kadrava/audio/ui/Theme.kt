package com.kadrava.audio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

object Palette {
    val Bg = Color(0xFF000000)       // absolute OLED black
    val Fg = Color(0xFFF2F2F2)
    val Dim = Color(0xFF8E8E8E)      // ~6:1 contrast on black
    val Faint = Color(0xFF3A3A3A)
    val Prompt = Color(0xFF55E36B)   // terminal green, as in the concept
    val Error = Color(0xFFFF6B6B)
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
    size: TextUnit = 14.sp,
    maxLines: Int = Int.MAX_VALUE,
) = BasicText(
    text = text,
    modifier = modifier,
    style = TextStyle(color = color, fontFamily = FontFamily.Monospace, fontSize = size),
    maxLines = maxLines,
    overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
)
