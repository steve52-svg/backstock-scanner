package com.frozenpull.scanner

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Keeps the print view alive until printing has started. */
private var printView: WebView? = null

fun printHtml(ctx: Context, title: String, html: String) {
    val wv = WebView(ctx)
    wv.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            val pm = ctx.getSystemService(PrintManager::class.java) ?: return
            pm.print(title, view.createPrintDocumentAdapter(title), PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.NA_LETTER).build())
        }
    }
    wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    printView = wv
}

private fun asOf(iso: String): String = try {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE h:mm a"))
} catch (e: Exception) { "" }

/**
 * The pull list, in the app, laid out like the Frozen Pull List page:
 * Check stock (Need / Don't need, quantities partial-first) and the Pull list (Pulled / Can't find),
 * plus hands-free, the stock sheet and Start new day. The stock comes from the page's Send to app.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PullScreen(state: AppState, onBack: () -> Unit, onHandsFree: () -> Unit) {
    val ctx = LocalContext.current
    val t = T
    val stock = state.stock
    val scope = rememberCoroutineScope()
    val clip = remember { ctx.getSystemService(ClipboardManager::class.java) }
    var tab by remember { mutableIntStateOf(if (stock.pullList().isNotEmpty()) 1 else 0) }
    var note by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var sheetMenu by remember { mutableStateOf(false) }

    // This screen reads like the page, so it can turn upright; the cameras stay sideways.
    DisposableEffect(Unit) {
        val act = ctx as? Activity
        act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
        onDispose { act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
    }

    fun clipText(): String = clip?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
    fun loadFromPage(auto: Boolean) {
        val text = clipText()
        if (auto && !stock.isNewExport(text)) return
        val n = stock.importAny(text)
        note = when {
            n > 0 -> "Loaded from the pull list page: ${stock.items.size} products, ${stock.pullList().size} on the pull list."
            auto -> note
            else -> "Nothing to load. On the pull list page tap Send to app, then come back."
        }
        if (n > 0 && stock.pullList().isNotEmpty()) tab = 1
    }
    // Opening the screen (or coming back to the app) picks up a list sent from the page.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { delay(450); loadFromPage(auto = true) } }

    val pull = stock.pullList()
    val toPull = pull.count { stock.marks[it.id] == "need" }
    val done = pull.count { stock.marks[it.id] == "pulled" }
    val cant = pull.count { stock.marks[it.id] == "cant" }
    val leftToCheck = stock.items.count { stock.marks[it.id] == null }

    Box(Modifier.fillMaxSize().background(t.bg), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
            // Header, like the page's.
            item(key = "head") {
                Column(Modifier.padding(top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("‹ Scanner", style = Type.button, color = t.accent, modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp, top = 6.dp, bottom = 6.dp))
                    }
                    Column {
                        Text("FROZEN PULL LIST", style = Type.title, color = t.fg)
                        Text(
                            if (stock.items.isEmpty()) "No stock list yet. Send it from the pull list page."
                            else (asOf(stock.updated).let { if (it.isNotBlank()) "Walk-in as of $it. " else "" }) +
                                if (tab == 0) "Mark what you need, then switch to Pull list." else "Tap Pulled as you go, or double-tap a row.",
                            style = Type.body, color = t.muted, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("$toPull to pull", style = Type.tally, color = t.accent)
                        Text("$done pulled", style = Type.tally, color = t.fg)
                        if (cant > 0) Text("$cant can't find", style = Type.tally, color = t.miss)
                        if (tab == 0 && stock.items.isNotEmpty()) Text("$leftToCheck left to check", style = Type.tally, color = t.muted)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Seg(listOf("Check stock", "Pull list"), tab) { tab = it }
                        PageButton("Hands-free", onHandsFree, kind = Btn.ACCENT, enabled = pull.isNotEmpty())
                        PageButton("Load from page", { loadFromPage(auto = false) }, kind = Btn.ACCENT)
                        Box {
                            PageButton("Print & share", { sheetMenu = true }, enabled = stock.items.isNotEmpty())
                            DropdownMenu(expanded = sheetMenu, onDismissRequest = { sheetMenu = false }) {
                                DropdownMenuItem(text = { Text("Print stock sheet") }, onClick = { sheetMenu = false; printHtml(ctx, "Walk-in Stock ${stock.date}", stock.sheetHtml()) })
                                DropdownMenuItem(text = { Text("Share stock sheet as text") }, onClick = { sheetMenu = false; share(ctx, "Walk-in stock", stock.sheetText()) })
                                DropdownMenuItem(text = { Text("Copy results for the page") }, enabled = pull.isNotEmpty(), onClick = {
                                    sheetMenu = false
                                    clip?.setPrimaryClip(ClipData.newPlainText("Pull results", stock.resultsText()))
                                    note = "Results copied. On the pull list page, tap Paste results from app."
                                })
                            }
                        }
                        PageButton("Start new day", { confirm = true })
                    }
                    if (note.isNotBlank()) Text(note, style = Type.body, color = t.accent)
                    Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
                }
            }

            if (stock.items.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "1. Walk the route and tap Update walk-in on the pull list page (the AI reads the photos there).\n" +
                            "2. On the page, tap Send to app.\n3. Come back here. The list loads by itself (or tap Load from page).",
                        style = Type.body, color = t.muted, modifier = Modifier.padding(vertical = 40.dp),
                    )
                }
            } else if (tab == 0) {
                // Check stock: sections in walk-in order, as on the page.
                val groups = mutableListOf<Pair<String, MutableList<StockItem>>>()
                for (it in stock.items) {
                    val k = listOf(it.group, it.shelf).filter { s -> s.isNotBlank() }.distinct().joinToString(" · ").ifBlank { "Other" }
                    if (groups.isEmpty() || groups.last().first != k) groups += k to mutableListOf()
                    groups.last().second += it
                }
                groups.forEachIndexed { gi, (title, list) ->
                    item(key = "h$gi") { SectionHead(title) }
                    item(key = "g$gi") {
                        Column(Modifier.fillMaxWidth().card(t)) {
                            list.forEachIndexed { i, it ->
                                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
                                StockRow(stock, it)
                            }
                        }
                    }
                }
            } else {
                if (pull.isEmpty()) item(key = "none") {
                    Text("Nothing marked Need yet. Go to Check stock and tap Need on what you want to pull.", style = Type.body, color = t.muted,
                        modifier = Modifier.padding(vertical = 40.dp))
                } else {
                    item(key = "hint") { Text("Use ▲ ▼ to change the order. Double-tap an item to mark it pulled.", style = Type.body, color = t.muted, modifier = Modifier.padding(top = 14.dp)) }
                    item(key = "pull") {
                        Column(Modifier.padding(top = 14.dp).fillMaxWidth().card(t)) {
                            pull.forEachIndexed { i, it ->
                                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
                                PullRow(stock, it)
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Start a new day?") },
        text = { Text("Clears the stock list and every mark in the app, so today starts fresh. The pull list page has its own Start new day button.") },
        confirmButton = { TextButton(onClick = { confirm = false; stock.newDay(); state.pullRun.sync(); tab = 0; note = "New day. Send today's list from the pull list page when it's ready." }) { Text("Start new day") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemInfo(stock: StockStore, it: StockItem, tag: String, struck: Boolean, modifier: Modifier) {
    val t = T
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(it.name, style = Type.name, color = if (struck) t.muted else t.fg, textDecoration = if (struck) TextDecoration.LineThrough else null)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CaseTag(tag, it.partial > 0)
            Text(stock.place(it), style = Type.small, color = t.muted, modifier = Modifier.align(Alignment.CenterVertically))
            stock.overflow(it).takeIf { s -> s.isNotBlank() }?.let { s -> Text(s, style = Type.small, color = t.skip, modifier = Modifier.align(Alignment.CenterVertically)) }
        }
    }
}

@Composable
private fun StockRow(stock: StockStore, it: StockItem) {
    val t = T
    val m = stock.marks[it.id]
    val need = stock.isNeed(it.id)
    Row(
        Modifier.fillMaxWidth().background(when { need -> t.accentSoft; m == "skip" -> t.skipSoft; else -> Color.Transparent }).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            ItemInfo(stock, it, stock.caseText(it), struck = m == "skip", modifier = Modifier)
            if (need && it.cases > 1) QtyStepper(stock.qtyOf(it), it.cases) { n -> stock.setQty(it, n) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CheckButton("Need", need, t.accent, { stock.mark(it.id, if (need) null else "need") }, Modifier.width(128.dp))
            CheckButton("Don't need", m == "skip", t.skip, { stock.mark(it.id, if (m == "skip") null else "skip") }, Modifier.width(128.dp))
        }
    }
}

@Composable
private fun PullRow(stock: StockStore, it: StockItem) {
    val t = T
    val m = stock.marks[it.id]
    var lastTap by remember { mutableLongStateOf(0L) }
    Row(
        Modifier.fillMaxWidth().background(if (m == "cant") t.missSoft else Color.Transparent)
            .clickable {
                val now = System.currentTimeMillis()
                if (now - lastTap < 380) { stock.mark(it.id, if (m == "pulled") "need" else "pulled"); lastTap = 0 } else lastTap = now
            }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f).alpha(if (m == "pulled") 0.62f else 1f)) {
            ItemInfo(stock, it, stock.pullText(it), struck = m == "pulled", modifier = Modifier)
            if (it.cases > 1) QtyStepper(stock.qtyOf(it), it.cases) { n -> stock.setQty(it, n) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CheckButton("Pulled", m == "pulled", t.accent, { stock.mark(it.id, if (m == "pulled") "need" else "pulled") }, Modifier.width(128.dp))
            CheckButton("Can't find", m == "cant", t.miss, { stock.mark(it.id, if (m == "cant") "need" else "cant") }, Modifier.width(128.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("▲", style = Type.button, color = t.muted, modifier = Modifier.clickable { stock.move(it.id, -1) }.padding(8.dp))
            Text("▼", style = Type.button, color = t.muted, modifier = Modifier.clickable { stock.move(it.id, 1) }.padding(8.dp))
        }
    }
}
