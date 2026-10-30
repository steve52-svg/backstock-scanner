package com.frozenpull.scanner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Building blocks that look like the Frozen Pull List page.

enum class Btn { PLAIN, ACCENT, DARK, DANGER }

/** A page-style button: white card with a thin border, bold label. */
@Composable
fun PageButton(text: String, onClick: () -> Unit, kind: Btn = Btn.PLAIN, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val t = T
    val (bg, fg, line) = when (kind) {
        Btn.PLAIN -> Triple(t.surface, t.fg, t.line)
        Btn.ACCENT -> Triple(t.accent, t.bg, t.accent)
        Btn.DARK -> Triple(t.fg, t.bg, t.fg)
        Btn.DANGER -> Triple(t.surface, t.miss, t.line)
    }
    Box(
        modifier.heightIn(min = 40.dp).alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(8.dp)).background(bg).border(1.dp, line, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 11.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.button, color = fg, maxLines = 1) }
}

/** The page's segmented control (Check stock | Pull list). */
@Composable
fun Seg(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val t = T
    Row(Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, t.line, RoundedCornerShape(8.dp)).height(IntrinsicSize.Min)) {
        options.forEachIndexed { i, label ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(t.line))
            Box(
                Modifier.heightIn(min = 40.dp).background(if (i == selected) t.fg else t.surface).clickable { onSelect(i) }
                    .padding(horizontal = 11.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = Type.button, color = if (i == selected) t.bg else t.fg) }
        }
    }
}

/** The page's checkbox buttons: "☐ Need", "☑ Pulled". */
@Composable
fun CheckButton(label: String, checked: Boolean, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = T
    Row(
        modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(8.dp)).background(t.surface)
            .border(1.dp, if (checked) color else t.line, RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(start = 7.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)).background(if (checked) color else Color.Transparent)
                .border(2.dp, if (checked) color else t.muted, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) { if (checked) Text("✓", style = Type.button, color = t.bg) }
        Text(label, style = Type.button.copy(fontSize = 13.sp), color = if (checked) color else t.fg, maxLines = 1)
    }
}

/** "PARTIAL CASE", "3 CASES · 1 PARTIAL" */
@Composable
fun CaseTag(text: String, partial: Boolean) {
    val t = T
    Text(
        text.uppercase(), style = Type.tag, color = if (partial) t.skip else t.fg,
        modifier = Modifier.border(1.dp, if (partial) t.skip else t.line, RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** Section heading: "INDIAN · LEFT SHELF" */
@Composable
fun SectionHead(text: String, right: String = "") {
    val t = T
    Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 8.dp)) {
        Text(text.uppercase(), style = Type.section, color = t.muted, modifier = Modifier.weight(1f))
        if (right.isNotBlank()) Text(right.uppercase(), style = Type.section, color = t.muted, textAlign = TextAlign.End)
    }
}

/** The white rounded list the rows sit in. */
fun Modifier.card(t: Tokens) = this.clip(RoundedCornerShape(10.dp)).background(t.surface).border(BorderStroke(1.dp, t.line), RoundedCornerShape(10.dp))

/** − 2 + stepper */
@Composable
fun QtyStepper(n: Int, of: Int, onChange: (Int) -> Unit) {
    val t = T
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
        PageButton("−", { onChange(n - 1) }, modifier = Modifier.widthIn(min = 36.dp))
        Text("$n", style = Type.name.copy(fontSize = 17.sp), color = t.fg)
        PageButton("+", { onChange(n + 1) }, modifier = Modifier.widthIn(min = 36.dp))
        Text("of $of", style = Type.body, color = t.fg)
    }
    Text("to pull, partial cases first", style = Type.small, color = t.muted)
}
