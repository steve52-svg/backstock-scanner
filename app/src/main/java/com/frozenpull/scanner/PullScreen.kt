package com.frozenpull.scanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

private val Green = Color(0xFF2ECC71)
private val Red = Color(0xFFE5534B)

/**
 * The pull list, in the app: today's stock (from the pull list page's Send to app),
 * Need / Don't need with quantities (partials first), the pull list with Pulled / Can't find,
 * hands-free, the stock sheet, and Start new day.
 */
@Composable
fun PullScreen(state: AppState, onBack: () -> Unit, onHandsFree: () -> Unit) {
    val ctx = LocalContext.current
    val stock = state.stock
    val scope = rememberCoroutineScope()
    val clip = remember { ctx.getSystemService(ClipboardManager::class.java) }
    var tab by remember { mutableStateOf(if (stock.pullList().isNotEmpty()) 1 else 0) }
    var note by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<String?>(null) }
    var sheetMenu by remember { mutableStateOf(false) }

    fun clipText(): String = clip?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
    fun loadFromPage(auto: Boolean) {
        val t = clipText()
        if (auto && !stock.isNewExport(t)) return
        val n = stock.importAny(t)
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

    Column(Modifier.fillMaxSize().background(Bg).padding(10.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onBack) { Text("‹ Scanner", color = Color.White) }
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Check stock") })
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Pull list (${pull.size})") })
            Button(onClick = onHandsFree, enabled = pull.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4FC0CC), contentColor = Color.Black)) { Text("Hands-free", fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = { loadFromPage(auto = false) }) { Text("Load from page", color = Color.White) }
            Box {
                OutlinedButton(onClick = { sheetMenu = true }, enabled = stock.items.isNotEmpty()) { Text("Stock sheet", color = Color.White) }
                DropdownMenu(expanded = sheetMenu, onDismissRequest = { sheetMenu = false }) {
                    DropdownMenuItem(text = { Text("Print") }, onClick = { sheetMenu = false; printHtml(ctx, "Walk-in Stock ${stock.date}", stock.sheetHtml()) })
                    DropdownMenuItem(text = { Text("Share as text") }, onClick = { sheetMenu = false; share(ctx, "Walk-in stock", stock.sheetText()) })
                }
            }
            OutlinedButton(onClick = {
                clip?.setPrimaryClip(ClipData.newPlainText("Pull results", stock.resultsText()))
                note = "Results copied. On the pull list page, tap Paste results from app."
            }, enabled = pull.isNotEmpty()) { Text("Copy results", color = Color.White) }
            OutlinedButton(onClick = { confirm = "newday" }) { Text("Start new day", color = Color(0xFFFF8A80)) }
        }
        Text(
            listOf(
                if (stock.items.isEmpty()) "" else "${stock.items.size} products · ${stock.items.sumOf { it.cases }} cases",
                if (pull.isEmpty()) "" else "$toPull to pull · $done pulled" + if (cant > 0) " · $cant can't find" else "",
                note,
            ).filter { it.isNotBlank() }.joinToString("  ·  "),
            color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )

        if (stock.items.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("No stock list yet", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("1. Walk the route and tap Update walk-in on the pull list page (the AI reads the photos there).\n" +
                    "2. On the page, tap Send to app.\n3. Come back here. The list loads by itself (or tap Load from page).",
                    color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
            }
            return@Column
        }

        if (tab == 0) StockTab(stock) else PullTab(stock)
    }

    if (confirm == "newday") AlertDialog(
        onDismissRequest = { confirm = null },
        title = { Text("Start a new day?") },
        text = { Text("Clears the stock list and every mark in the app, so today starts fresh. The pull list page has its own Start new day button.") },
        confirmButton = { TextButton(onClick = { confirm = null; stock.newDay(); state.pullRun.sync(); tab = 0; note = "New day. Send today's list from the pull list page when it's ready." }) { Text("Start new day") } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
    )
}

@Composable
private fun Stepper(stock: StockStore, it: StockItem) {
    if (it.cases <= 1) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        OutlinedButton(onClick = { stock.setQty(it, stock.qtyOf(it) - 1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp)) { Text("−", color = Color.White, fontSize = 20.sp) }
        Text("${stock.qtyOf(it)}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = { stock.setQty(it, stock.qtyOf(it) + 1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp)) { Text("+", color = Color.White, fontSize = 20.sp) }
        Text("of ${it.cases} to pull, partial cases first", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
    }
}

@Composable
private fun Toggle(text: String, on: Boolean, color: Color, onClick: () -> Unit) {
    if (on) Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.Black), modifier = Modifier.width(130.dp)) { Text("✓ $text", fontWeight = FontWeight.Bold, maxLines = 1) }
    else OutlinedButton(onClick = onClick, modifier = Modifier.width(130.dp)) { Text(text, color = Color.White, maxLines = 1) }
}

@Composable
private fun ItemInfo(stock: StockStore, it: StockItem, detail: String, modifier: Modifier) {
    Column(modifier) {
        Text(it.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(detail, color = if (it.partial > 0) Maybe else Color.White.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(stock.place(it), color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
            stock.overflow(it).takeIf { s -> s.isNotBlank() }?.let { s -> Text(s, color = Maybe, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun StockTab(stock: StockStore) {
    // Sections in walk-in order, as on the page.
    val groups = remember(stock.items.toList()) {
        val out = mutableListOf<Pair<String, MutableList<StockItem>>>()
        for (it in stock.items) {
            val k = listOf(it.group, it.shelf).filter { s -> s.isNotBlank() }.distinct().joinToString(" · ").ifBlank { "Other" }
            if (out.isEmpty() || out.last().first != k) out += k to mutableListOf()
            out.last().second += it
        }
        out
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        groups.forEach { (title, list) ->
            item(key = "h-$title-${list.first().id}") { Text(title.uppercase(), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, start = 4.dp)) }
            items(list, key = { it.id }) { it ->
                val m = stock.marks[it.id]
                Row(
                    Modifier.fillMaxWidth().background(if (stock.isNeed(it.id)) Color(0xFF163A40) else Panel, RoundedCornerShape(10.dp)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        ItemInfo(stock, it, stock.caseText(it), Modifier)
                        if (stock.isNeed(it.id)) Stepper(stock, it)
                    }
                    Toggle("Need", stock.isNeed(it.id), Color(0xFF4FC0CC)) { stock.mark(it.id, if (stock.isNeed(it.id)) null else "need") }
                    Toggle("Don't need", m == "skip", Color(0xFFD9B77C)) { stock.mark(it.id, if (m == "skip") null else "skip") }
                }
            }
        }
    }
}

@Composable
private fun PullTab(stock: StockStore) {
    val pull = stock.pullList()
    if (pull.isEmpty()) {
        Text("Nothing marked Need yet. Go to Check stock and tap Need on what you want to pull.",
            color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(24.dp))
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(pull, key = { it.id }) { it ->
            val m = stock.marks[it.id]
            var lastTap by remember { mutableLongStateOf(0L) }
            Row(
                Modifier.fillMaxWidth()
                    .background(when (m) { "pulled" -> Color(0xFF1E3A2A); "cant" -> Color(0xFF43201E); else -> Panel }, RoundedCornerShape(10.dp))
                    // Double-tap the row to mark it pulled.
                    .clickable {
                        val now = System.currentTimeMillis()
                        if (now - lastTap < 380) { stock.mark(it.id, if (m == "pulled") "need" else "pulled"); lastTap = 0 } else lastTap = now
                    }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    ItemInfo(stock, it, stock.pullText(it), Modifier)
                    Stepper(stock, it)
                }
                Toggle("Pulled", m == "pulled", Green) { stock.mark(it.id, if (m == "pulled") "need" else "pulled") }
                Toggle("Can't find", m == "cant", Red) { stock.mark(it.id, if (m == "cant") "need" else "cant") }
                Column {
                    TextButton(onClick = { stock.move(it.id, -1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp, 34.dp)) { Text("▲", color = Color.White) }
                    TextButton(onClick = { stock.move(it.id, 1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp, 34.dp)) { Text("▼", color = Color.White) }
                }
            }
        }
    }
}
