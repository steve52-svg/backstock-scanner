package com.frozenpull.scanner

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ───────────────────────── Results: one photo with what was read ─────────────────────────

@Composable
fun ResultsScreen(state: AppState, index: Int, onIndex: (Int) -> Unit, onBack: () -> Unit, onFound: () -> Unit) {
    if (state.photos.isEmpty()) { onBack(); return }
    val photo = state.photos[index]
    var open by remember { mutableStateOf<Detection?>(null) }
    var showUnread by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }
    /** The last label marked not relevant, so it can be undone. */
    var lastHidden by remember { mutableStateOf<Detection?>(null) }
    val scope = rememberCoroutineScope()
    fun notRelevant(d: Detection) {
        scope.launch {
            state.ignore(photo, d)
            lastHidden = d
            note = "Hidden \"${d.text.take(30)}\" — it won't show again"
        }
    }
    val dets = photo.detections.filter { showUnread || it.level != Level.UNKNOWN || it.confirmed }

    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black)) {
            PhotoView(state, photo, dets, open?.id) { open = it }
        }
        Column(Modifier.width(320.dp).fillMaxHeight().background(Panel).padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Camera", color = Color.White) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onIndex(index - 1) }, enabled = index > 0) { Text("‹") }
                Text("${index + 1}/${state.photos.size}", color = Color.White)
                TextButton(onClick = { onIndex(index + 1) }, enabled = index < state.photos.lastIndex) { Text("›") }
            }
            val sure = photo.detections.count { it.confirmed || it.level == Level.SURE }
            val guess = photo.detections.count { !it.confirmed && it.level == Level.GUESS }
            val unread = photo.detections.count { !it.confirmed && it.level == Level.UNKNOWN }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(Sure); Text("$sure sure", color = Color.White, fontSize = 13.sp)
                Dot(Maybe); Text("$guess guesses", color = Color.White, fontSize = 13.sp)
                Dot(Unread); Text("$unread unread", color = Color.White, fontSize = 13.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show unread text", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Switch(checked = showUnread, onCheckedChange = { showUnread = it })
            }
            if (note.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(note, color = Sure, fontSize = 13.sp, modifier = Modifier.weight(1f))
                lastHidden?.let { h ->
                    TextButton(onClick = {
                        scope.launch { state.unignore(photo, h); lastHidden = null; note = "Put back \"${h.text.take(30)}\"" }
                    }) { Text("Undo", color = Maybe) }
                }
            }
            Text("Tap a label to fix it or teach it.", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(dets, key = { it.id }) { d ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.05f))
                            .clickable { open = d }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Dot(levelColor(d))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                d.product?.name ?: if (d.isBarcode) "Unknown barcode" else "Can't place this",
                                color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                (if (d.isBarcode) "barcode " else "read: ") + d.text + (if (d.confirmed) "  ✓" else ""),
                                color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!d.confirmed) NotRelevantButton(small = true) { notRelevant(d) }
                    }
                }
            }
            Button(onClick = onFound, modifier = Modifier.fillMaxWidth()) { Text("Found list (${state.found.items.size})") }
        }
    }

    open?.let { d ->
        DetectionSheet(state, photo, d, onClose = { open = null }, onNote = { note = it; lastHidden = null },
            onNotRelevant = { open = null; notRelevant(d) })
    }
}

/** Hides a label that isn't a product (printing, stickers, a cart number…) and stops showing it. */
@Composable
fun NotRelevantButton(small: Boolean = false, onClick: () -> Unit) {
    val red = Color(0xFFFF8A80)
    OutlinedButton(
        onClick = onClick,
        border = androidx.compose.foundation.BorderStroke(1.dp, red),
        contentPadding = if (small) PaddingValues(horizontal = 8.dp, vertical = 0.dp) else ButtonDefaults.ContentPadding,
        modifier = if (small) Modifier.height(32.dp) else Modifier,
    ) { Text("Not relevant", color = red, fontSize = if (small) 12.sp else 14.sp) }
}

@Composable
private fun Dot(c: Color) = Box(Modifier.size(10.dp).clip(CircleShape).background(c))

/** The photo, fitted to the space, with a colored box around everything that was read. */
@Composable
private fun PhotoView(state: AppState, photo: ScanPhoto, dets: List<Detection>, selected: Int?, onTap: (Detection) -> Unit) {
    var bmp by remember(photo.key) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(photo.key) { bmp = withContext(Dispatchers.IO) { try { state.decode(photo.uri, 2000) } catch (e: Exception) { null } } }
    val density = LocalDensity.current
    val labelPaint = remember { Paint().apply { isAntiAlias = true; textSize = with(density) { 12.sp.toPx() }; isFakeBoldText = true } }
    val bgPaint = remember { Paint() }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val boxW = constraints.maxWidth.toFloat(); val boxH = constraints.maxHeight.toFloat()
        val fitW: Float; val fitH: Float
        if (boxW / boxH > photo.aspect) { fitH = boxH; fitW = boxH * photo.aspect } else { fitW = boxW; fitH = boxW / photo.aspect }
        val wDp = with(density) { fitW.toDp() }; val hDp = with(density) { fitH.toDp() }
        Box(Modifier.size(wDp, hDp)) {
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
            Canvas(
                Modifier.fillMaxSize().pointerInput(dets) {
                    detectTapGestures { p ->
                        val x = p.x / size.width; val y = p.y / size.height
                        val slack = 0.015f
                        dets.filter { x >= it.box.left - slack && x <= it.box.right + slack && y >= it.box.top - slack && y <= it.box.bottom + slack }
                            .minByOrNull { it.box.width() * it.box.height() }?.let(onTap)
                    }
                },
            ) {
                for (d in dets) {
                    val c = levelColor(d)
                    val tl = Offset(d.box.left * size.width, d.box.top * size.height)
                    val sz = Size(d.box.width() * size.width, d.box.height() * size.height)
                    drawRect(c, tl, sz, style = Stroke(width = if (d.id == selected) 6f else 3f))
                    val label = d.product?.name ?: "?"
                    labelPaint.color = android.graphics.Color.BLACK
                    bgPaint.color = c.toArgb()
                    val tw = labelPaint.measureText(label) + 10f
                    val th = labelPaint.textSize + 6f
                    val ly = if (tl.y > th) tl.y - th else tl.y + sz.height
                    drawContext.canvas.nativeCanvas.apply {
                        drawRect(tl.x, ly, tl.x + tw, ly + th, bgPaint)
                        drawText(label, tl.x + 5f, ly + th - 6f, labelPaint)
                    }
                }
            }
        }
    }
}

// ───────────────────────── Fix / teach one label ─────────────────────────

@Composable
fun DetectionSheet(
    state: AppState, photo: ScanPhoto, det: Detection,
    onClose: () -> Unit, onNote: (String) -> Unit, onNotRelevant: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var crop by remember(det.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(det.id) { crop = withContext(Dispatchers.IO) { state.cropOf(photo, det) } }
    var query by remember { mutableStateOf("") }
    var handwritten by remember { mutableStateOf(!det.isBarcode) }
    var working by remember { mutableStateOf(false) }
    val results = remember(query) { if (query.isBlank()) emptyList() else state.matcher.search(query, 25) }

    fun choose(p: Product) {
        if (working) return
        working = true
        scope.launch {
            val n = state.confirm(photo, det, p, handwritten)
            onNote("Learned \"${det.text.take(30)}\" = ${p.name}" + if (n > 0) " · $n other label${if (n == 1) "" else "s"} updated" else "")
            onClose()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).pointerInput(Unit) { detectTapGestures { } }) {
        Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth().border(2.dp, levelColor(det), RoundedCornerShape(8.dp)).padding(4.dp), contentAlignment = Alignment.Center) {
                    crop?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                        ?: CircularProgressIndicator()
                }
                Spacer(Modifier.height(8.dp))
                Text(if (det.isBarcode) "Barcode: ${det.text}" else "The phone read: \"${det.text}\"", color = Color.White, fontSize = 16.sp)
                det.product?.let {
                    Text(
                        "It thinks: ${it.name}" + (det.guesses.firstOrNull()?.let { g -> "  (${(g.score * 100).toInt()}%, ${g.why})" } ?: ""),
                        color = levelColor(det), fontSize = 14.sp,
                    )
                }
            }
            Column(Modifier.width(360.dp).fillMaxHeight().background(Panel, RoundedCornerShape(10.dp)).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("What is this?", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    NotRelevantButton { if (!working) onNotRelevant() }
                    TextButton(onClick = onClose) { Text("Close", color = Color.White) }
                }
                if (query.isBlank()) {
                    det.guesses.take(5).forEach { g ->
                        OutlinedButton(onClick = { choose(g.product) }, enabled = !working, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(g.product.name, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${(g.score * 100).toInt()}%", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                        }
                    }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(if (det.guesses.isEmpty()) "Type the product" else "None of these? Search") },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.weight(1f)) {
                    items(results, key = { it.id }) { p ->
                        Column(Modifier.fillMaxWidth().clickable(enabled = !working) { choose(p) }.padding(vertical = 8.dp, horizontal = 4.dp)) {
                            Text(p.name, color = Color.White)
                            if (p.place.isNotBlank()) Text(p.place, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                        }
                    }
                    if (query.isNotBlank()) item {
                        TextButton(onClick = { choose(state.addProduct(query)) }, enabled = !working) {
                            Text("+ Add new product \"${query.trim()}\"", color = Maybe)
                        }
                    }
                }
                if (!det.isBarcode) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = handwritten, onCheckedChange = { handwritten = it })
                    Text("Handwritten — save it for training", color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }
}

// ───────────────────────── Found list ─────────────────────────

@Composable
fun FoundScreen(state: AppState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    val list = state.foundProducts()

    Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Camera", color = Color.White) }
                Text("Found in back stock · ${list.size} products", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            if (list.isEmpty()) Text(
                "Nothing yet. Scan some boxes — green labels land here automatically; yellow ones once you confirm them.",
                color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(16.dp),
            )
            LazyColumn(Modifier.weight(1f)) {
                var last = "?"
                list.forEach { (p, n) ->
                    val shelf = p.shelf.ifBlank { "Other" }
                    if (shelf != last) {
                        last = shelf
                        item(key = "h-$shelf") { Text(shelf, color = Sure, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) }
                    }
                    item(key = p.id) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, color = Color.White)
                                Text(listOf(p.row.takeIf { it.isNotBlank() }?.let { "$it row" }, if (n > 1) "in $n photos" else null)
                                    .filterNotNull().joinToString(" · "), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                            }
                            TextButton(onClick = { state.found.drop(p.id) }) { Text("Remove", color = Color(0xFFFF8A80)) }
                        }
                    }
                }
            }
        }
        Column(Modifier.width(280.dp).fillMaxHeight().background(Panel, RoundedCornerShape(10.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { share(ctx, "Back stock found", state.foundText()) }, enabled = list.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Share list") }
            OutlinedButton(onClick = { confirmClear = true }, enabled = list.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Clear list", color = Color.White) }
            Divider(color = Color.White.copy(alpha = 0.15f))
            Text("What it has learned", color = Color.White, fontWeight = FontWeight.Bold)
            Text(
                "${state.learned.writings.size} handwritten names\n${state.learned.codes.size} barcodes\n" +
                    "${state.learned.ignored.size} labels marked not relevant\n${state.trainingCount} label pictures saved for training",
                color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp,
            )
            OutlinedButton(onClick = { share(ctx, "Back Stock Scanner learned data", state.learnedJson()) }, modifier = Modifier.fillMaxWidth()) {
                Text("Share learned data", color = Color.White)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Careful reading", color = Color.White)
                    Text("Reads each photo in quarters too. Catches smaller writing; a few seconds slower.", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                }
                Switch(checked = state.careful, onCheckedChange = { state.updateCareful(it) })
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { confirmForget = true }) { Text("Forget everything it learned", color = Color(0xFFFF8A80), fontSize = 12.sp) }
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear the found list?") }, text = { Text("Starts a fresh list. What it learned is kept.") },
        confirmButton = { TextButton(onClick = { state.found.clear(); state.photos.forEach { it.counted.clear() }; confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
    if (confirmForget) AlertDialog(
        onDismissRequest = { confirmForget = false },
        title = { Text("Forget everything it learned?") },
        text = { Text("Removes the handwritten names, barcodes and skipped words you taught it. Saved training pictures stay in your Photos.") },
        confirmButton = { TextButton(onClick = { state.learned.clear(); state.matcher.rebuild(); confirmForget = false }) { Text("Forget") } },
        dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
    )
}
