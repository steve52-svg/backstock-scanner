package com.frozenpull.scanner

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.frozenpull.camera.Lens
import com.frozenpull.camera.LensPicker
import com.frozenpull.camera.LensPlan
import com.frozenpull.camera.Lenses
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------------------------------------
// Live view (prototype): point the camera at the shelves; every label the phone can read gets a
// box with the product's name, live. Everything runs on the phone; nothing is sent anywhere.
// ---------------------------------------------------------------------------------------------

/** One label being followed from frame to frame. Boxes are 0..1 of the screen. */
class Track(val id: Int, var box: RectF, var text: String, val isBarcode: Boolean) {
    val votes = HashMap<String, Float>()
    var product: Product? = null
    var sure = false
    var hits = 0
    var lastSeen = 0L
    /** Frames in a row where the label was seen but too small or blurry to read. */
    var unreadFrames = 0
}

/** What the overlay draws for one frame. */
data class LiveBox(val id: Int, val box: RectF, val product: Product?, val sure: Boolean, val text: String, val held: Boolean)

data class LiveFrame(val boxes: List<LiveBox> = emptyList(), val fps: Float = 0f, val ms: Long = 0, val res: String = "")

/**
 * Keeps labels steady between frames: a label that was read once keeps its name while it's
 * still seen as text, even after the camera has moved too far away to read it.
 */
class LiveTracker {
    private val tracks = mutableListOf<Track>()
    private var nextId = 0
    var holdMs = 700L

    fun update(dets: List<Detection>, now: Long): List<LiveBox> {
        val taken = HashSet<Int>()
        // Read labels first, so they claim their track before unread text does.
        for (d in dets.sortedByDescending { if (it.product != null) 1 else 0 }) {
            val t = tracks.filter { it.id !in taken }
                .map { it to match(it.box, d.box) }.filter { it.second > 0.25f }.maxByOrNull { it.second }?.first
                ?: Track(nextId++, RectF(d.box), d.text, d.isBarcode).also { tracks += it }
            taken += t.id
            // Smooth the movement a little so boxes don't jitter.
            val a = if (t.hits == 0) 1f else 0.6f
            t.box = RectF(lerp(t.box.left, d.box.left, a), lerp(t.box.top, d.box.top, a), lerp(t.box.right, d.box.right, a), lerp(t.box.bottom, d.box.bottom, a))
            t.hits++; t.lastSeen = now
            val p = d.product
            if (p != null) {
                t.unreadFrames = 0; t.text = d.text
                t.votes[p.id] = (t.votes[p.id] ?: 0f) + if (d.level == Level.SURE) 2f else 1f
                val top = t.votes.maxByOrNull { it.value }!!
                if (t.product?.id != top.key) t.product = if (top.key == p.id) p else t.product
                t.sure = top.value >= 3f || d.level == Level.SURE
            } else {
                t.unreadFrames++
            }
        }
        tracks.removeAll { now - it.lastSeen > holdMs }
        return tracks.filter { it.hits >= 2 || it.sure }.map {
            LiveBox(it.id, RectF(it.box), it.product, it.sure, it.text, held = it.product != null && it.unreadFrames > 0)
        }
    }

    fun clear() { tracks.clear() }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** How well two boxes line up: overlap, or near-overlap for small labels moving fast. */
    private fun match(a: RectF, b: RectF): Float {
        val o = Scanner.overlap(a, b)
        if (o > 0f) return o
        val dx = a.centerX() - b.centerX(); val dy = a.centerY() - b.centerY()
        val reach = max(max(a.width(), a.height()), max(b.width(), b.height()))
        val d = kotlin.math.sqrt(dx * dx + dy * dy)
        return if (d < reach * 0.6f) 0.26f else 0f
    }
}

/** Runs the on-phone readers on camera frames and feeds the tracker. */
@OptIn(ExperimentalGetImage::class)
class LiveAnalyzer(private val state: AppState, private val out: MutableStateFlow<LiveFrame>) : ImageAnalysis.Analyzer {
    private val textReader = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val barcodeReader = BarcodeScanning.getClient()
    val tracker = LiveTracker()
    @Volatile var paused = false
    private var frame = 0L
    private var lastAt = 0L
    private var fps = 0f
    private var ms = 0f

    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || paused) { proxy.close(); return }
        val t0 = SystemClock.elapsedRealtime()
        try {
            val rot = proxy.imageInfo.rotationDegrees
            val input = InputImage.fromMediaImage(media, rot)
            // The part of the frame that's on screen, turned upright like the readers' boxes.
            val crop = upright(proxy.cropRect, proxy.width, proxy.height, rot)
            fun toScreen(r: Rect) = RectF(
                (r.left - crop.left) / crop.width().toFloat(), (r.top - crop.top) / crop.height().toFloat(),
                (r.right - crop.left) / crop.width().toFloat(), (r.bottom - crop.top) / crop.height().toFloat(),
            )
            val dets = mutableListOf<Detection>()
            val text = runCatching { Tasks.await(textReader.process(input)) }.getOrNull()
            if (text != null) for (block in text.textBlocks) {
                val lines = block.lines.mapNotNull { l -> l.boundingBox?.let { Scanner.Piece(l.text, toScreen(it)) } }
                if (lines.isEmpty()) continue
                val found = state.scanner.matchLines(lines)
                // Text the phone can see but not read (too far, blurry): keep it as an unread box so a
                // label that was read up close keeps its name.
                dets += found.ifEmpty { listOf(Detection(-1, Scanner.union(lines.map { it.box }), lines.joinToString(" ") { it.text }, false, emptyList(), null, Level.UNKNOWN)) }
            }
            if (frame % 2 == 0L) {
                val codes = runCatching { Tasks.await(barcodeReader.process(input)) }.getOrNull()
                if (codes != null) for (b in codes) {
                    val raw = b.rawValue ?: continue
                    val r = b.boundingBox ?: continue
                    if (state.learned.isIgnoredCode(raw)) continue
                    val g = state.matcher.guessBarcode(raw) ?: continue
                    dets += Detection(-1, toScreen(r), raw, true, listOf(g), g.product, if (g.score >= 0.95f) Level.SURE else Level.GUESS)
                }
            }
            val now = SystemClock.elapsedRealtime()
            val boxes = tracker.update(dets.filter { it.box.right > 0 && it.box.left < 1 && it.box.bottom > 0 && it.box.top < 1 }, now)
            val took = (now - t0).toFloat()
            ms = if (ms == 0f) took else ms * 0.85f + took * 0.15f
            if (lastAt > 0) { val f = 1000f / max(1L, now - lastAt); fps = if (fps == 0f) f else fps * 0.85f + f * 0.15f }
            lastAt = now; frame++
            out.value = LiveFrame(boxes, fps, ms.toLong(), "${proxy.width}×${proxy.height}")
        } catch (_: Exception) {
        } finally {
            proxy.close()
        }
    }

    fun close() { textReader.close(); barcodeReader.close() }

    companion object {
        /** A rectangle in the camera's buffer, turned the way the image is shown. */
        fun upright(r: Rect, w: Int, h: Int, rot: Int): Rect = when ((rot % 360 + 360) % 360) {
            90 -> Rect(h - r.bottom, r.left, h - r.top, r.right)
            180 -> Rect(w - r.right, h - r.bottom, w - r.left, h - r.top)
            270 -> Rect(r.top, w - r.right, r.bottom, w - r.left)
            else -> Rect(r)
        }
    }
}

private suspend fun cameraProvider(ctx: android.content.Context): ProcessCameraProvider = suspendCancellableCoroutine { c ->
    val f = ProcessCameraProvider.getInstance(ctx)
    f.addListener({ c.resume(f.get()) }, ContextCompat.getMainExecutor(ctx))
}

@Composable
fun LiveScreen(state: AppState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; keepScreenOn = true } }
    val frames = remember { MutableStateFlow(LiveFrame()) }
    val analyzer = remember { LiveAnalyzer(state, frames) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val live by frames.collectAsState()

    var plans by remember { mutableStateOf<Map<Lens, LensPlan>>(emptyMap()) }
    var lensPick by remember { mutableStateOf(Lens.NORMAL) }
    val lens = if (plans.isEmpty() || lensPick in plans) lensPick else Lens.NORMAL
    var sharp by remember { mutableStateOf(true) }
    var torch by remember { mutableStateOf(false) }
    var showUnread by remember { mutableStateOf(false) }
    var holdLong by remember { mutableStateOf(false) }
    var frozen by remember { mutableStateOf<Bitmap?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(holdLong) { analyzer.tracker.holdMs = if (holdLong) 2000L else 700L }
    LaunchedEffect(lens, sharp) {
        try {
            val provider = cameraProvider(ctx)
            while (previewView.width == 0 || previewView.viewPort == null) delay(30)
            plans = Lenses.plans(provider)
            val plan = plans[lens] ?: plans[Lens.NORMAL] ?: LensPlan(CameraSelector.DEFAULT_BACK_CAMERA, 1f, "1×")
            val aspect = AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build()).build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val target = if (sharp) Size(1920, 1080) else Size(1280, 720)
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect)
                    .setResolutionStrategy(ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                .also { it.setAnalyzer(executor, analyzer) }
            // Same view port for both, so the boxes line up with what's on screen.
            val group = UseCaseGroup.Builder().setViewPort(previewView.viewPort!!).addUseCase(preview).addUseCase(analysis).build()
            provider.unbindAll()
            analyzer.tracker.clear()
            val cam = try { provider.bindToLifecycle(owner, plan.selector, group) }
                catch (e: Exception) { provider.unbindAll(); provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group) }
            cam.cameraControl.setZoomRatio(plan.zoom)
            camera = cam
            error = ""
        } catch (e: Exception) { error = "Camera problem: ${e.message ?: e.javaClass.simpleName}" }
    }
    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(ctx).get().unbindAll() }
            executor.shutdown(); analyzer.close()
        }
    }

    // Today's pull list, matched to the scanner's products.
    val stock = state.stock
    val pullFor: Map<String, StockItem> = remember(stock.items.size, stock.updated) {
        val m = HashMap<String, StockItem>()
        for (it in stock.items) {
            val p = state.catalog[it.id] ?: state.matcher.guessText(it.name, 1).firstOrNull()?.takeIf { g -> g.score >= 0.85f }?.product
            if (p != null) m[p.id] = it
        }
        m
    }
    fun statusOf(p: Product?): String? = p?.let { pullFor[it.id] }?.let { stock.marks[it.id] }

    val shown = live.boxes.filter { showUnread || it.product != null }
    val shownNow by rememberUpdatedState(shown)
    val onList = shown.count { statusOf(it.product) == "need" }

    Row(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            AndroidView({ previewView }, Modifier.fillMaxSize())
            frozen?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
            LiveOverlay(shown, selected, ::statusOf, Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { pos ->
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    selected = shownNow.filter { it.product != null }.firstOrNull {
                        val pad = 16f
                        pos.x in (it.box.left * w - pad)..(it.box.right * w + pad) && pos.y in (it.box.top * h - pad)..(it.box.bottom * h + pad)
                    }?.id
                }
            })
            // Speed readout, so we can judge if live is good enough.
            Text(
                "${"%.1f".format(live.fps)} reads/sec · ${live.ms} ms each · ${shown.count { it.product != null }} named" +
                    (if (onList > 0) " · $onList on pull list" else "") + "  (${live.res})",
                color = Color.White, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
            )
            if (error.isNotEmpty()) Text(error, color = Color(0xFFFF8A80), modifier = Modifier.align(Alignment.Center).background(Color.Black).padding(12.dp))
            if (frozen != null) Text("PAUSED — tap a box", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).background(Color(0xFFFFD400), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
            LensPicker(plans, lens, onPick = { lensPick = it; frozen = null; analyzer.paused = false }, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
            // Tapped box: name, where it goes, and pull-list marking.
            shown.firstOrNull { it.id == selected }?.product?.let { p ->
                val item = pullFor[p.id]
                val st = item?.let { stock.marks[it.id] }
                Column(
                    Modifier.align(Alignment.BottomEnd).padding(10.dp).widthIn(max = 300.dp)
                        .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(10.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(p.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    if (item != null) {
                        Text("${stock.caseText(item)} · ${stock.place(item).ifBlank { "in stock" }}", color = Color(0xFFCFD8DC), fontSize = 13.sp)
                        when (st) {
                            "need" -> { Text(stock.pullText(item), color = Color(0xFFFFD400), fontSize = 13.sp)
                                PageButton("Mark pulled", { stock.mark(item.id, "pulled"); selected = null }, kind = Btn.ACCENT) }
                            "pulled" -> PageButton("Undo pulled", { stock.mark(item.id, "need") })
                            else -> Text("Not on the pull list", color = Color(0xFFCFD8DC), fontSize = 13.sp)
                        }
                    } else Text("Not in today's stock list", color = Color(0xFFCFD8DC), fontSize = 13.sp)
                    PageButton("Close", { selected = null })
                }
            }
        }
        Column(
            Modifier.width(150.dp).fillMaxHeight().background(Panel).verticalScroll(rememberScrollState()).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("LIVE VIEW", style = Type.title.copy(fontSize = 20.sp), color = Ink)
            Text("Test version · nothing is uploaded", style = Type.small, color = Muted)
            PageButton(if (frozen == null) "Pause" else "Resume", {
                if (frozen == null) { analyzer.paused = true; frozen = previewView.bitmap }
                else { frozen = null; analyzer.paused = false; selected = null }
            }, kind = Btn.ACCENT, modifier = Modifier.fillMaxWidth())
            PageButton(if (sharp) "Detail: high" else "Detail: fast", { sharp = !sharp }, modifier = Modifier.fillMaxWidth())
            PageButton(if (holdLong) "Hold: 2 sec" else "Hold: short", { holdLong = !holdLong }, modifier = Modifier.fillMaxWidth())
            PageButton(if (showUnread) "Unread: shown" else "Unread: hidden", { showUnread = !showUnread }, modifier = Modifier.fillMaxWidth())
            PageButton(if (torch) "Light: on" else "Light: off", { torch = !torch }, modifier = Modifier.fillMaxWidth())
            PageButton("Back", onBack, modifier = Modifier.fillMaxWidth())
            Legend()
        }
    }
}

private val Yellow = Color(0xFFFFD400)
private val Pulled = Color(0xFF2ECC71)
private val Known = Color(0xFFFFFFFF)
private val Grey = Color(0xFFB0BEC5)

@Composable
private fun Legend() {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf(Yellow to "On pull list", Pulled to "Pulled", Known to "In stock", Grey to "Can't read").forEach { (c, t) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(c, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(6.dp)); Text(t, style = Type.small, color = Muted)
            }
        }
    }
}

@Composable
private fun LiveOverlay(boxes: List<LiveBox>, selected: Int?, statusOf: (Product?) -> String?, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val dens = LocalDensity.current
    val style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) }
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val pad = with(dens) { 6.dp.toPx() }
        // Pull-list boxes last, so they draw on top.
        for (b in boxes.sortedBy { if (statusOf(it.product) == "need") 1 else 0 }) {
            val st = statusOf(b.product)
            val color = when { b.product == null -> Grey; st == "need" -> Yellow; st == "pulled" -> Pulled; else -> Known }
            val stroke = with(dens) { (if (st == "need") 4.dp else if (b.product == null) 1.5.dp else 2.5.dp).toPx() }
            val l = b.box.left * w - pad; val t = b.box.top * h - pad
            val r = b.box.right * w + pad; val btm = b.box.bottom * h + pad
            val sel = b.id == selected
            drawRoundRect(color, Offset(l, t), GSize(r - l, btm - t), CornerRadius(8f, 8f),
                style = Stroke(width = if (sel) stroke * 1.8f else stroke, pathEffect = if (b.product == null || (b.held && st != "need")) dash else null))
            val p = b.product ?: continue
            val label = (if (st == "need") "PULL · " else if (st == "pulled") "✓ " else "") + p.name + (if (!b.sure) " ?" else "")
            val maxW = max(with(dens) { 160.dp.toPx() }, r - l).toInt()
            val m = measurer.measure(label, style, maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = maxW))
            val lx = min(max(0f, l), w - m.size.width - 8f)
            val ly = if (t - m.size.height - 6f > 0) t - m.size.height - 6f else btm + 4f
            drawRoundRect(color, Offset(lx, ly), GSize(m.size.width + 10f, m.size.height + 4f), CornerRadius(6f, 6f))
            drawText(m, topLeft = Offset(lx + 5f, ly + 2f))
        }
    }
}
