package com.kadrava.audio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed interface Cell
class SliderCell(
    val label: String, val valueText: String, val fraction: Float,
    val onFraction: (Float) -> Unit, val onType: ((Double) -> Unit)? = null,
) : Cell
class ButtonCell(val label: String, val valueText: String, val enabled: Boolean = true, val key: String? = null, val onClick: () -> Unit) : Cell
class NumberCell(
    val label: String, val valueText: String, val onType: (Double) -> Unit,
    val onMinus: () -> Unit, val onPlus: () -> Unit, val enabled: Boolean = true,
) : Cell
class WideSliderCell(
    val label: String, val valueText: String, val fraction: Float,
    val onFraction: (Float) -> Unit, val onType: ((Double) -> Unit)?, val onMinus: (() -> Unit)?, val onPlus: (() -> Unit)?,
) : Cell

/** A module page: one or more sub-pages, each a group of cells shown in a 3-column grid. */
class ModulePage(val name: String, val subs: List<List<Cell>>)

/** What an engine offers the screen: its pages and the actions behind [Process], [Preview] and [Live]. */
class EngineSpec(
    val pages: List<ModulePage>,
    val canProcess: Boolean,
    val problem: String?,
    val onProcess: () -> Unit,
    val onPreview: () -> Unit,
    val showLive: Boolean = false,
)

/** `[<]  centre  [>]`, arrows at the edges and the centre content centred. */
@Composable
fun CarouselRow(prevKey: String, nextKey: String, onPrev: () -> Unit, onNext: () -> Unit, center: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TermItem(listOf(Span("[<]", kind = SpanKind.CONTROL)), key = prevKey, separator = false, onClick = onPrev, description = "Previous")
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { center() }
        TermItem(listOf(Span("[>]", kind = SpanKind.CONTROL)), key = nextKey, separator = false, onClick = onNext, description = "Next")
    }
}

@Composable
fun ModuleView(pages: List<ModulePage>, page: Int, sub: Int, onPage: (Int) -> Unit, onSub: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (pages.isEmpty()) return
    val pi = page.coerceIn(0, pages.size - 1)
    val pg = pages[pi]
    val si = sub.coerceIn(0, pg.subs.size - 1)
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (pages.size > 1) {
            CarouselRow("page-prev", "page-next", { onPage((pi + pages.size - 1) % pages.size) }, { onPage((pi + 1) % pages.size) }) {
                TermText("${pi + 1} ${pg.name}")
            }
        } else TermText(pg.name)
        if (pg.subs.size > 1) {
            CarouselRow("sub-prev", "sub-next", { onSub((si + pg.subs.size - 1) % pg.subs.size) }, { onSub((si + 1) % pg.subs.size) }) {
                TermText("${si + 1}/${pg.subs.size}")
            }
        }
        CellGrid(pg.subs[si])
    }
}

@Composable
private fun CellGrid(cells: List<Cell>) {
    val rows = ArrayList<List<Cell>>()
    var cur = ArrayList<Cell>()
    for (c in cells) {
        if (c is WideSliderCell) {
            if (cur.isNotEmpty()) { rows.add(cur); cur = ArrayList() }
            rows.add(listOf(c))
        } else {
            cur.add(c)
            if (cur.size == 3) { rows.add(cur); cur = ArrayList() }
        }
    }
    if (cur.isNotEmpty()) rows.add(cur)
    Column(Modifier.fillMaxWidth()) {
        for (row in rows) {
            val only = row.singleOrNull()
            if (only is WideSliderCell) {
                WideSlider(only.label, only.valueText, only.fraction, only.onFraction, only.onType, only.onMinus, only.onPlus)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    for (c in row) CellView(c, Modifier.weight(1f))
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun CellView(cell: Cell, modifier: Modifier) {
    val small = TextStyle(fontFamily = Term.font, fontSize = 12.sp, color = Palette.Fg, textAlign = TextAlign.Center)
    when (cell) {
        is SliderCell -> CellSlider(cell.label, cell.valueText, cell.fraction, cell.onFraction, cell.onType, modifier)
        is ButtonCell -> Column(modifier.heightIn(min = 58.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(cell.label, style = small, maxLines = 1)
            TermItem(
                listOf(Span("[${cell.valueText}]", kind = SpanKind.CONTROL)),
                enabled = cell.enabled, separator = false, key = cell.key ?: cell.label, onClick = cell.onClick,
                description = cell.label + " " + cell.valueText,
            )
        }
        is NumberCell -> Column(modifier.heightIn(min = 58.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(cell.label, style = small, maxLines = 1)
            TermNumberField(cell.valueText, cell.label, if (cell.enabled) cell.onType else null, Modifier.fillMaxWidth(), centered = true)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TermItem(listOf(Span("[-]", kind = SpanKind.CONTROL)), enabled = cell.enabled, separator = false, key = "${cell.label}-", onClick = cell.onMinus)
                TermItem(listOf(Span("[+]", kind = SpanKind.CONTROL)), enabled = cell.enabled, separator = false, key = "${cell.label}+", onClick = cell.onPlus)
            }
        }
        is WideSliderCell -> {}
    }
}
