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
import kotlinx.coroutines.launch
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
    var byPicture = false
    /** Looks like a short label (not fine print), so it's worth showing even unread. */
    var labelLike = false
    /** The last text read here, for teaching. */
    var raw = ""
    val votes = HashMap<String, Float>()
    var product: Product? = null
    var sure = false
    var hits = 0
    var lastSeen = 0L
    /** Frames in a row where the label was seen but too small or blurry to read. */
    var unreadFrames = 0
}

/** What the overlay draws for one frame. */
data class LiveBox(val id: Int, val box: RectF, val product: Product?, val sure: Boolean, val text: String, val held: Boolean, val byPicture: Boolean = false, val labelLike: Boolean = false)

data class LiveFrame(
    val boxes: List<LiveBox> = emptyList(), val fps: Float = 0f, val ms: Long = 0, val res: String = "",
    /** Shelf markers and pinned cases in view, 0..1 of the screen. */
    val markers: List<MarkerView> = emptyList(), val pins: List<PinView> = emptyList(),
)

/**
 * Keeps labels steady between frames: a label that was read once keeps its name while it's
 * still seen as text, even after the camera has moved too far away to read it.
 */
class LiveTracker {
    private val tracks = mutableListOf<Track>()
    private var nextId = 0
    var holdMs = 700L

    @Synchronized
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
            if (!d.text.startsWith(PIC) && d.text.isNotBlank()) t.raw = d.text
            if (d.product == null && d.id != FINE_PRINT && !d.isBarcode) t.labelLike = true
            val p = d.product
            if (p != null) {
                t.unreadFrames = 0; t.text = d.text; t.byPicture = d.text.startsWith(PIC)
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
            LiveBox(it.id, RectF(it.box), it.product, it.sure, it.raw, held = it.product != null && it.unreadFrames > 0, byPicture = it.byPicture, labelLike = it.labelLike)
        }
    }

    @Synchronized
    fun clear() { tracks.clear() }

    /** You told it what this box is: it shows that name from now on. */
    @Synchronized
    fun relabel(id: Int, p: Product) {
        val t = tracks.firstOrNull { it.id == id } ?: return
        t.votes.clear(); t.votes[p.id] = 10f; t.product = p; t.sure = true; t.labelLike = false
    }

    /** You said this text isn't a product: stop showing the box. */
    @Synchronized
    fun drop(id: Int) { tracks.removeAll { it.id == id } }

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

/** Marks a detection that came from picture matching rather than reading text. */
const val PIC = "\u0000pic:"
/** Id for text that's fine print (ingredients, addresses), not a label. */
const val FINE_PRINT = -2

/** Runs the on-phone readers on camera frames and feeds the tracker. */
@OptIn(ExperimentalGetImage::class)
class LiveAnalyzer(private val state: AppState, private val out: MutableStateFlow<LiveFrame>) : ImageAnalysis.Analyzer {
    private val textReader = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val barcodeReader = BarcodeScanning.getClient()
    val tracker = LiveTracker()
    @Volatile var paused = false
    /** On the sales floor: also match retail packages by their picture. */
    @Volatile var floor = false
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
                dets += found.ifEmpty { listOf(Detection(FINE_PRINT, Scanner.union(lines.map { it.box }), lines.joinToString(" ") { it.text }, false, emptyList(), null, Level.UNKNOWN)) }
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
            pictureMatch(proxy, rot, crop, dets)
            val seenMarkers = if (floor) emptyList() else findMarkers(proxy, rot, crop)
            val now = SystemClock.elapsedRealtime()
            val boxes = tracker.update(dets.filter { it.box.right > 0 && it.box.left < 1 && it.box.bottom > 0 && it.box.top < 1 }, now)
            // Pin what was read to the shelf (sweep counting).
            val cw = crop.width().toFloat(); val ch = crop.height().toFloat()
            var mv = emptyList<MarkerView>(); var pins = emptyList<PinView>()
            if (!floor) {
                val labels = boxes.filter { it.product != null }.map { LabelSeen(it.id, it.product!!.id, it.sure, it.box.centerX() * cw, it.box.centerY() * ch) }
                val (p, m) = state.sweep.update(seenMarkers, labels, now, if (lastAt > 0) now - lastAt else 0, cw, ch)
                pins = p.map { it.copy(x = it.x / cw, y = it.y / ch) }
                mv = m.map { v -> v.copy(corners = FloatArray(8) { k -> v.corners[k] / if (k % 2 == 0) cw else ch }) }
            }
            val took = (now - t0).toFloat()
            ms = if (ms == 0f) took else ms * 0.85f + took * 0.15f
            if (lastAt > 0) { val f = 1000f / max(1L, now - lastAt); fps = if (fps == 0f) f else fps * 0.85f + f * 0.15f }
            lastAt = now; frame++
            out.value = LiveFrame(boxes, fps, ms.toLong(), "${proxy.width}×${proxy.height}", mv, pins)
        } catch (_: Exception) {
        } finally {
            proxy.close()
        }
    }

    fun close() { textReader.close(); barcodeReader.close() }

    // ---------- shelf markers (4×4 ArUco), walk-in only ----------
    private val aruco: org.opencv.objdetect.ArucoDetector? by lazy {
        runCatching {
            org.opencv.android.OpenCVLoader.initLocal()
            org.opencv.objdetect.ArucoDetector(
                org.opencv.objdetect.Objdetect.getPredefinedDictionary(org.opencv.objdetect.Objdetect.DICT_4X4_50),
                org.opencv.objdetect.DetectorParameters(),
            )
        }.getOrNull()
    }

    /** Finds the markers in a frame; corners come back upright, in pixels of the on-screen part. */
    private fun findMarkers(proxy: ImageProxy, rot: Int, crop: Rect): List<SeenMarker> {
        val det = aruco ?: return emptyList()
        val plane = proxy.image?.planes?.getOrNull(0) ?: return emptyList()
        val w = proxy.width; val h = proxy.height; val rs = plane.rowStride
        val buf = plane.buffer.duplicate(); buf.rewind()
        val gray = org.opencv.core.Mat(h, w, org.opencv.core.CvType.CV_8UC1)
        val row = ByteArray(w)
        for (y in 0 until h) {
            buf.position(y * rs); buf.get(row, 0, min(w, buf.remaining())); gray.put(y, 0, row)
        }
        val up = if (rot == 0) gray else org.opencv.core.Mat().also {
            org.opencv.core.Core.rotate(gray, it, when (rot) { 90 -> org.opencv.core.Core.ROTATE_90_CLOCKWISE; 180 -> org.opencv.core.Core.ROTATE_180; else -> org.opencv.core.Core.ROTATE_90_COUNTERCLOCKWISE })
            gray.release()
        }
        val corners = mutableListOf<org.opencv.core.Mat>(); val ids = org.opencv.core.Mat()
        runCatching { det.detectMarkers(up, corners, ids) }
        val out = mutableListOf<SeenMarker>()
        for (i in corners.indices) {
            val id = ids.get(i, 0)?.getOrNull(0)?.toInt() ?: continue
            val c = FloatArray(8)
            for (k in 0 until 4) { val pt = corners[i].get(0, k); c[2 * k] = (pt[0] - crop.left).toFloat(); c[2 * k + 1] = (pt[1] - crop.top).toFloat() }
            out += SeenMarker(id, c)
            corners[i].release()
        }
        ids.release(); up.release()
        return out
    }

    /**
     * Around text that couldn't be read for sure, cut out the package (floor) or case (walk-in)
     * and compare it with the pictures of that kind. A few per frame, so the view stays live.
     */
    private fun pictureMatch(proxy: ImageProxy, rot: Int, crop: Rect, dets: MutableList<Detection>) {
        val retail = state.retail
        if (!retail.ready) return
        val todo = dets.withIndex().filter { (_, d) -> !d.isBarcode && d.level != Level.SURE && d.box.width() > 0.015f }
            .sortedByDescending { it.value.box.width() * it.value.box.height() }.take(4)
        if (todo.isEmpty()) return
        val frame = runCatching { proxy.toBitmap() }.getOrNull() ?: return
        val up = if (rot == 0) frame else Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, android.graphics.Matrix().apply { postRotate(rot.toFloat()) }, true)
        for ((i, d) in todo) {
            // The package or case is bigger than its label: widen and heighten the box around the text.
            val cx = d.box.centerX(); val cy = d.box.centerY()
            val w = if (floor) max(d.box.width() * 2.2f, 0.08f) else max(d.box.width() * 2.6f, 0.12f)
            val h = if (floor) max(d.box.height() * 4f, 0.12f) else max(d.box.height() * 5f, 0.16f)
            val r = RectF(cx - w / 2, cy - h * 0.45f, cx + w / 2, cy + h * 0.55f)
            val l = (crop.left + r.left.coerceIn(0f, 1f) * crop.width()).toInt().coerceIn(0, up.width - 1)
            val t = (crop.top + r.top.coerceIn(0f, 1f) * crop.height()).toInt().coerceIn(0, up.height - 1)
            val rr = (crop.left + r.right.coerceIn(0f, 1f) * crop.width()).toInt().coerceIn(l + 1, up.width)
            val bb = (crop.top + r.bottom.coerceIn(0f, 1f) * crop.height()).toInt().coerceIn(t + 1, up.height)
            if (rr - l < 48 || bb - t < 48) continue
            val kind = if (floor) PicKind.RETAIL else PicKind.CASE
            val hit = runCatching { retail.match(Bitmap.createBitmap(up, l, t, rr - l, bb - t), kind) }.getOrNull() ?: continue
            // Plain brown cases look alike, so the walk-in needs a clearer win than the floor.
            if (floor && (hit.score < 0.72f || hit.margin < 0.03f)) continue
            if (!floor && (hit.score < 0.8f || hit.margin < 0.05f)) continue
            // Text that clearly names something else wins over the picture.
            if (d.product != null && d.product.id != hit.product.id && (d.guesses.firstOrNull()?.score ?: 0f) >= 0.8f) continue
            val level = if (hit.score >= 0.85f && hit.margin >= 0.06f) Level.SURE else Level.GUESS
            dets[i] = Detection(-1, RectF(r.left.coerceIn(0f, 1f), r.top.coerceIn(0f, 1f), r.right.coerceIn(0f, 1f), r.bottom.coerceIn(0f, 1f)),
                PIC + hit.product.name, false, listOf(Guess(hit.product, hit.score, "picture")), hit.product, level)
        }
    }

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
    var floor by remember { mutableStateOf(false) }
    var teaching by remember { mutableStateOf(false) }
    var markerSel by remember { mutableStateOf<Int?>(null) }
    var pinSel by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<SweepSummary?>(null) }
    var sweeping by remember { mutableStateOf(state.sweep.sweeping) }
    var counts by remember { mutableStateOf(Triple(0, 0, 0)) }
    val sweep = state.sweep
    // Counting is always on in the walk-in, and the back-stock list follows along live:
    // no need to tap Finish (that's only for checking what's gone).
    LaunchedEffect(floor) { if (!floor && !sweep.sweeping) { sweep.startSweep(SystemClock.elapsedRealtime()); sweeping = true } }
    LaunchedEffect(Unit) {
        var applied = -1L
        while (true) {
            counts = Triple(sweep.setCount(), sweep.linkedCount(), sweep.countedCount())
            val v = sweep.version
            if (v != applied) { applied = v; state.stock.setFromSweep(sweep.stockItems(state.catalog)); sweep.save() }
            delay(1500)
        }
    }
    val liveNow by rememberUpdatedState(live)
    var retailStatus by remember { mutableStateOf("") }

    LaunchedEffect(floor) {
        analyzer.floor = floor
        analyzer.tracker.clear()
        if (!state.retail.ready) {
            val job = launch(kotlinx.coroutines.Dispatchers.Default) { state.retail.prepare() }
            while (job.isActive) { retailStatus = state.retail.status; delay(250) }
            delay(2500)
        }
        retailStatus = ""
    }

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
    fun statusOf(p: Product?): String? = p?.let { pullFor[it.id] }?.let { stock.marks[it.id] ?: if (floor) "back" else null }

    val shown = live.boxes.filter { showUnread || it.product != null || it.labelLike }
    val shownNow by rememberUpdatedState(shown)
    val onList = shown.count { statusOf(it.product) == "need" }

    Row(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            AndroidView({ previewView }, Modifier.fillMaxSize())
            frozen?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
            if (!floor) SweepOverlay(live.markers, live.pins, state.catalog, markerSel, pinSel, Modifier.fillMaxSize())
            LiveOverlay(shown, selected, floor, { p -> p?.let { pullFor[it.id] }?.let { "${it.cases}" } }, ::statusOf, Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { pos ->
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    teaching = false
                    val f = liveNow
                    // A shelf marker first (to set it up), then a pinned case, then a label.
                    val mk = f.markers.firstOrNull { m ->
                        val xs = (0 until 4).map { m.corners[2 * it] * w }; val ys = (0 until 4).map { m.corners[2 * it + 1] * h }
                        pos.x in (xs.min() - 30)..(xs.max() + 30) && pos.y in (ys.min() - 30)..(ys.max() + 30)
                    }
                    if (mk != null) { markerSel = mk.id; pinSel = null; selected = null; return@detectTapGestures }
                    val pin = f.pins.minByOrNull { kotlin.math.hypot(it.x * w - pos.x, it.y * h - pos.y) }?.takeIf { kotlin.math.hypot(it.x * w - pos.x, it.y * h - pos.y) < 60f }
                    markerSel = null
                    if (pin != null && shownNow.none { b -> pos.x in b.box.left * w..b.box.right * w && pos.y in b.box.top * h..b.box.bottom * h }) {
                        pinSel = pin.caseId; selected = null; return@detectTapGestures
                    }
                    pinSel = null
                    selected = shownNow.sortedBy { if (it.product != null) 0 else 1 }.firstOrNull {
                        val pad = 16f
                        pos.x in (it.box.left * w - pad)..(it.box.right * w + pad) && pos.y in (it.box.top * h - pad)..(it.box.bottom * h + pad)
                    }?.id
                }
            })
            // Speed readout, so we can judge if live is good enough.
            Text(
                "${"%.1f".format(live.fps)} reads/sec · ${live.ms} ms each · ${shown.count { it.product != null }} named" +
                    (if (onList > 0) " · $onList on pull list" else "") + "  (${live.res})" +
                    (if (retailStatus.isNotEmpty()) "\n$retailStatus" else ""),
                color = Color.White, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
            )
            if (error.isNotEmpty()) Text(error, color = Color(0xFFFF8A80), modifier = Modifier.align(Alignment.Center).background(Color.Black).padding(12.dp))
            if (frozen != null) Text("PAUSED — tap a box", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).background(Color(0xFFFFD400), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
            LensPicker(plans, lens, onPick = { lensPick = it; frozen = null; analyzer.paused = false }, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
            // Tapped box: name, where it goes, and pull-list marking.
            markerSel?.let { id ->
                MarkerCard(state, id, onDone = { markerSel = null }, modifier = Modifier.align(Alignment.BottomEnd))
            }
            pinSel?.let { cid ->
                PinCard(state, cid, onDone = { pinSel = null }, modifier = Modifier.align(Alignment.BottomEnd))
            }
            summary?.let { sm ->
                SummaryCard(state, sm, onSaved = { summary = null; sweep.startSweep(SystemClock.elapsedRealtime()); sweeping = true }, onClose = { summary = null }, modifier = Modifier.align(Alignment.Center))
            }
            val sel = shown.firstOrNull { it.id == selected }
            if (sel != null && (sel.product == null || teaching)) TeachCard(state, sel, analyzer.tracker, if (floor) PicKind.RETAIL else PicKind.CASE,
                grab = { frozen ?: previewView.bitmap },
                onDone = { selected = null; teaching = false }, modifier = Modifier.align(Alignment.BottomEnd))
            else sel?.product?.let { p ->
                val item = pullFor[p.id]
                val st = item?.let { stock.marks[it.id] }
                Column(
                    Modifier.align(Alignment.BottomEnd).padding(10.dp).widthIn(max = 300.dp)
                        .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(10.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val pic = remember(p.id) { state.retail.pictureFor(p) }
                        if (pic != null) Image(pic.asImageBitmap(), "What it looks like", Modifier.size(72.dp).background(Color.White, RoundedCornerShape(6.dp)).padding(3.dp))
                        Column {
                            Text(p.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            if (shown.firstOrNull { it.id == selected }?.byPicture == true) Text("Matched by its picture", color = Color(0xFFCFD8DC), fontSize = 12.sp)
                        }
                    }
                    if (item != null && floor) {
                        Text("In back stock: ${stock.caseText(item)} · ${stock.place(item).ifBlank { "walk-in" }}", color = Color(0xFFCFD8DC), fontSize = 13.sp)
                        when (st) {
                            "need" -> { Text("On the pull list", color = Color(0xFFFFD400), fontSize = 13.sp)
                                PageButton("Take off the list", { stock.mark(item.id, null) }) }
                            "pulled" -> Text("Already pulled today", color = Color(0xFF2ECC71), fontSize = 13.sp)
                            else -> PageButton("Add to pull list", {
                                if (item.id !in stock.order) stock.order.add(item.id)
                                stock.mark(item.id, "need")
                                selected = null
                            }, kind = Btn.ACCENT)
                        }
                    } else if (floor) {
                        Text("No back stock for this today", color = Color(0xFFCFD8DC), fontSize = 13.sp)
                    } else if (item != null) {
                        Text("${stock.caseText(item)} · ${stock.place(item).ifBlank { "in stock" }}", color = Color(0xFFCFD8DC), fontSize = 13.sp)
                        when (st) {
                            "need" -> { Text(stock.pullText(item), color = Color(0xFFFFD400), fontSize = 13.sp)
                                PageButton("Mark pulled", { stock.mark(item.id, "pulled"); selected = null }, kind = Btn.ACCENT) }
                            "pulled" -> PageButton("Undo pulled", { stock.mark(item.id, "need") })
                            else -> Text("Not on the pull list", color = Color(0xFFCFD8DC), fontSize = 13.sp)
                        }
                    } else Text("Not in today's stock list", color = Color(0xFFCFD8DC), fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PageButton("Wrong name?", { teaching = true })
                        PageButton("Close", { selected = null })
                    }
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
            Seg(listOf("Walk-in", "Floor"), if (floor) 1 else 0) { floor = it == 1; selected = null; markerSel = null; pinSel = null }
            if (!floor) {
                PageButton("Check what's gone", { summary = sweep.summary() }, kind = Btn.DARK, modifier = Modifier.fillMaxWidth())
                Text("Markers ${counts.first} set · ${counts.second} linked\n${counts.third} cases counted" + if (sweeping) "\nCounting live" else "",
                    style = Type.small, color = Muted)
            }
            PageButton(if (frozen == null) "Pause" else "Resume", {
                if (frozen == null) { analyzer.paused = true; frozen = previewView.bitmap }
                else { frozen = null; analyzer.paused = false; selected = null }
            }, kind = Btn.ACCENT, modifier = Modifier.fillMaxWidth())
            PageButton(if (sharp) "Detail: high" else "Detail: fast", { sharp = !sharp }, modifier = Modifier.fillMaxWidth())
            PageButton(if (holdLong) "Hold: 2 sec" else "Hold: short", { holdLong = !holdLong }, modifier = Modifier.fillMaxWidth())
            PageButton(if (showUnread) "Unread: shown" else "Unread: hidden", { showUnread = !showUnread }, modifier = Modifier.fillMaxWidth())
            PageButton(if (torch) "Light: on" else "Light: off", { torch = !torch }, modifier = Modifier.fillMaxWidth())
            PageButton("Back", onBack, modifier = Modifier.fillMaxWidth())
            Legend(floor)
        }
    }
}

private val Yellow = Color(0xFFFFD400)
private val Pulled = Color(0xFF2ECC71)
private val Known = Color(0xFFFFFFFF)
private val Grey = Color(0xFFB0BEC5)
private val Back = Color(0xFF4FC0CC)

@Composable
private fun Legend(floor: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        (if (floor) listOf(Back to "Back stock: tap to add", Yellow to "On pull list", Pulled to "Pulled", Known to "None in back", Grey to "Can't read")
         else listOf(Yellow to "On pull list", Pulled to "Pulled", Known to "In stock", Grey to "Can't read")).forEach { (c, t) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(c, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(6.dp)); Text(t, style = Type.small, color = Muted)
            }
        }
    }
}

@Composable
private fun LiveOverlay(boxes: List<LiveBox>, selected: Int?, floor: Boolean, casesOf: (Product?) -> String?, statusOf: (Product?) -> String?, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val dens = LocalDensity.current
    val style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) }
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val pad = with(dens) { 6.dp.toPx() }
        // Pull-list boxes last, so they draw on top.
        for (b in boxes.sortedBy { when (statusOf(it.product)) { "need" -> 2; "back" -> 1; else -> 0 } }) {
            val st = statusOf(b.product)
            val color = when { b.product == null -> Grey; st == "need" -> Yellow; st == "pulled" -> Pulled; st == "back" -> Back; else -> Known }
            val stroke = with(dens) { (if (st == "need" || st == "back") 4.dp else if (b.product == null) 1.5.dp else 2.5.dp).toPx() }
            val l = b.box.left * w - pad; val t = b.box.top * h - pad
            val r = b.box.right * w + pad; val btm = b.box.bottom * h + pad
            val sel = b.id == selected
            drawRoundRect(color, Offset(l, t), GSize(r - l, btm - t), CornerRadius(8f, 8f),
                style = Stroke(width = if (sel) stroke * 1.8f else stroke, pathEffect = if (b.product == null || (b.held && st != "need")) dash else null))
            val p = b.product ?: continue
            val label = (if (floor) when (st) { "need" -> "ON LIST · "; "pulled" -> "✓ "; "back" -> "IN BACK ${casesOf(p) ?: ""} · "; else -> "" }
                else if (st == "need") "PULL · " else if (st == "pulled") "✓ " else "") + p.name + (if (b.byPicture) " ◆" else "") + (if (!b.sure) " ?" else "")
            val maxW = max(with(dens) { 160.dp.toPx() }, r - l).toInt()
            val m = measurer.measure(label, style, maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = maxW))
            val lx = min(max(0f, l), w - m.size.width - 8f)
            val ly = if (t - m.size.height - 6f > 0) t - m.size.height - 6f else btm + 4f
            drawRoundRect(color, Offset(lx, ly), GSize(m.size.width + 10f, m.size.height + 4f), CornerRadius(6f, 6f))
            drawText(m, topLeft = Offset(lx + 5f, ly + 2f))
        }
    }
}


/**
 * Tap a grey box (or "Wrong name?"): see what the phone read, pick the right product,
 * and it remembers that writing from then on, in the live view and in photo scans.
 */
@Composable
private fun TeachCard(state: AppState, box: LiveBox, tracker: LiveTracker, kind: PicKind, grab: () -> Bitmap?, onDone: () -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var q by remember(box.id) { mutableStateOf("") }
    val read = box.text
    val picks = remember(box.id, q) {
        val sug = if (read.isBlank()) emptyList() else state.matcher.guessText(read, 6).map { it.product }
        (if (q.isBlank()) sug else state.matcher.search(q, 12) + sug).distinctBy { it.id }.take(12)
    }
    fun teach(p: Product) {
        if (read.isNotBlank()) { state.learned.teachWriting(read, p.id); state.matcher.rebuild() }
        tracker.relabel(box.id, p)
        // Keep how this box looked, so picture matching learns your store's cases and packages.
        val view = runCatching { grab() }.getOrNull()
        if (view != null) {
            val b = box.box
            val padX = b.width() * 0.25f; val padY = b.height() * 0.25f
            val l = ((b.left - padX).coerceIn(0f, 1f) * view.width).toInt()
            val t = ((b.top - padY).coerceIn(0f, 1f) * view.height).toInt()
            val r = ((b.right + padX).coerceIn(0f, 1f) * view.width).toInt().coerceAtLeast(l + 1)
            val btm = ((b.bottom + padY).coerceIn(0f, 1f) * view.height).toInt().coerceAtLeast(t + 1)
            if (r - l >= 32 && btm - t >= 32) {
                val crop = Bitmap.createBitmap(view, l, t, (r - l).coerceAtMost(view.width - l), (btm - t).coerceAtMost(view.height - t))
                scope.launch(kotlinx.coroutines.Dispatchers.Default) { state.retail.teach(crop, p, kind) }
            }
        }
        onDone()
    }
    Column(
        modifier.padding(10.dp).width(320.dp).heightIn(max = 420.dp)
            .background(Color.Black.copy(alpha = 0.88f), RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("What is this box?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(if (read.isBlank()) "Couldn't make out any letters." else "The phone read: \u201C${read.take(60)}\u201D", color = Color(0xFFCFD8DC), fontSize = 13.sp)
        androidx.compose.material3.OutlinedTextField(
            q, { q = it }, singleLine = true, placeholder = { Text("Search products") },
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
        )
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            picks.forEach { p ->
                Text(p.name, color = Color.White, fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                        .pointerInput(p.id) { detectTapGestures { teach(p) } }.padding(horizontal = 10.dp, vertical = 9.dp))
            }
            if (q.isNotBlank()) Text("+ Add \u201C${q.trim()}\u201D as a new product", color = Color(0xFFFFD400), fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().pointerInput(q) { detectTapGestures { teach(state.addProduct(q.trim())) } }.padding(10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (read.isNotBlank()) PageButton("Not a product", {
                state.learned.ignore(read); state.matcher.rebuild(); tracker.drop(box.id); onDone()
            })
            PageButton("Cancel", onDone)
        }
    }
}


// ---------------------------------------------------------------------------------------------
// Sweep counting on screen
// ---------------------------------------------------------------------------------------------

private val MarkerNew = Color(0xFFFF9F43)
private val MarkerLinking = Color(0xFFFFD400)
private val MarkerOk = Color(0xFF2ECC71)

@Composable
private fun SweepOverlay(markers: List<MarkerView>, pins: List<PinView>, catalog: Catalog, markerSel: Int?, pinSel: String?, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val dens = LocalDensity.current
    Canvas(modifier) {
        val w = size.width; val h = size.height
        for (m in markers) {
            val c = if (!m.set) MarkerNew else if (!m.linked) MarkerLinking else MarkerOk
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(m.corners[0] * w, m.corners[1] * h)
                for (k in 1 until 4) lineTo(m.corners[2 * k] * w, m.corners[2 * k + 1] * h)
                close()
            }
            drawPath(path, c, style = Stroke(width = with(dens) { (if (m.id == markerSel) 5.dp else 3.dp).toPx() }))
            val label = "#${m.id + 1}" + when { !m.set -> " · tap to set"; !m.linked -> " · linking"; else -> "" }
            val t = measurer.measure(label, TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black))
            val x = m.corners[0] * w; val y = m.corners[1] * h - t.size.height - 6
            drawRoundRect(c, Offset(x, y), GSize(t.size.width + 8f, t.size.height + 2f), CornerRadius(4f, 4f))
            drawText(t, topLeft = Offset(x + 4, y + 1))
        }
        for (p in pins) {
            val color = when { !p.counted -> Color(0xFFB0BEC5); p.seenNow -> MarkerOk; else -> Color.White }
            val cx = p.x * w; val cy = p.y * h
            drawCircle(Color.Black.copy(alpha = 0.6f), radius = with(dens) { 9.dp.toPx() }, center = Offset(cx, cy))
            drawCircle(color, radius = with(dens) { (if (p.caseId == pinSel) 8.dp else 6.dp).toPx() }, center = Offset(cx, cy))
            if (p.partial) drawCircle(Color(0xFFD9B77C), radius = with(dens) { 3.dp.toPx() }, center = Offset(cx, cy))
        }
    }
}

/** Tap a marker: say which wall and row it's on. */
@Composable
private fun MarkerCard(state: AppState, id: Int, onDone: () -> Unit, modifier: Modifier) {
    val info = state.sweep.marker(id)
    var wall by remember(id) { mutableStateOf(info?.wall ?: WALLS[0]) }
    var row by remember(id) { mutableStateOf(info?.row ?: ROWS[0]) }
    var part by remember(id) { mutableStateOf(info?.part ?: "") }
    Column(
        modifier.padding(10.dp).width(340.dp).background(Color.Black.copy(alpha = 0.88f), RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Marker #${id + 1}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(if (info == null) "Where is this marker?" else if (info.linked) "Set up and on the map." else "Set up. It joins the map once it's been in view with a marker that's already on the map.",
            color = Color(0xFFCFD8DC), fontSize = 13.sp)
        Seg(WALLS.map { it.replace(" shelf", "") }, WALLS.indexOf(wall)) { wall = WALLS[it] }
        Seg(ROWS, ROWS.indexOf(row)) { row = ROWS[it] }
        val parts = partsFor(wall)
        Seg(parts.map { it.ifBlank { "Whole" } }, parts.indexOf(part).coerceAtLeast(0)) { part = parts[it] }
        Text("Row = the shelf this marker's lip holds up. On the shelves, 1–6 runs from the door end.", color = Color(0xFF93A4B0), fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PageButton("Save", { state.sweep.setMarker(id, wall, row, part); onDone() }, kind = Btn.ACCENT)
            if (info != null) PageButton("Forget", { state.sweep.forgetMarker(id); onDone() }, kind = Btn.DANGER)
            PageButton("Cancel", onDone)
        }
    }
}

/** Tap a pinned case: partial or full, wrong product, or not a case. */
@Composable
private fun PinCard(state: AppState, caseId: String, onDone: () -> Unit, modifier: Modifier) {
    val c = state.sweep.case(caseId)
    if (c == null) { LaunchedEffect(caseId) { onDone() }; return }
    val p = state.catalog[c.productId]
    var picking by remember(caseId) { mutableStateOf(false) }
    var q by remember(caseId) { mutableStateOf("") }
    Column(
        modifier.padding(10.dp).width(320.dp).heightIn(max = 440.dp).background(Color.Black.copy(alpha = 0.88f), RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(p?.name ?: c.productId, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("${c.wall} · ${c.row.lowercase()} row" + (if (c.part.isNotBlank()) " · ${partLabel(c.part)}" else "") + " · read ${c.reads}×" + if (!c.counted) " (not counted yet)" else "", color = Color(0xFFCFD8DC), fontSize = 13.sp)
        if (!picking) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageButton(if (c.partial) "Partial ✓" else "Mark partial", { state.sweep.setPartial(caseId, !c.partial); onDone() })
                PageButton("Wrong product?", { picking = true })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageButton("Not a case", { state.sweep.removeCase(caseId); onDone() }, kind = Btn.DANGER)
                PageButton("Close", onDone)
            }
        } else {
            androidx.compose.material3.OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("Search products") }, modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.matcher.search(q, 12).forEach { x ->
                    Text(x.name, color = Color.White, fontSize = 15.sp, modifier = Modifier.fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                        .pointerInput(x.id) { detectTapGestures { state.sweep.relabel(caseId, x.id); onDone() } }.padding(horizontal = 10.dp, vertical = 9.dp))
                }
            }
            PageButton("Cancel", { picking = false })
        }
    }
}

/** End of a sweep: what's new, what looks gone, what needs a look; then it becomes the back-stock list. */
@Composable
private fun SummaryCard(state: AppState, s: SweepSummary, onSaved: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    var removeGone by remember { mutableStateOf(true) }
    var keepCheck by remember { mutableStateOf(false) }
    fun names(list: List<CaseRec>) = list.groupBy { it.productId }.entries.sortedByDescending { it.value.size }
        .joinToString(", ") { (pid, cs) -> (state.catalog[pid]?.name ?: pid) + if (cs.size > 1) " ×${cs.size}" else "" }
    Column(
        modifier.padding(10.dp).width(420.dp).heightIn(max = 480.dp).background(Color.Black.copy(alpha = 0.92f), RoundedCornerShape(10.dp))
            .verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("What's gone? Since this sweep started", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("New this sweep: ${s.added.size} cases" + if (s.added.isNotEmpty()) "\n${names(s.added)}" else "", color = Color(0xFFCFD8DC), fontSize = 13.sp)
        if (s.gone.isNotEmpty()) {
            Text("Gone? ${s.gone.size} cases were in view but not seen:\n${names(s.gone)}", color = Color(0xFFFFD400), fontSize = 13.sp)
            CheckButton("Take them off (pulled)", removeGone, Color(0xFFFFD400), { removeGone = !removeGone })
        }
        if (s.check.isNotEmpty()) {
            Text("Check these: ${s.check.size} read only once or twice:\n${names(s.check)}", color = Color(0xFFCFD8DC), fontSize = 13.sp)
            CheckButton("Count them too", keepCheck, Color(0xFF2ECC71), { keepCheck = !keepCheck })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PageButton("Apply", {
                state.sweep.finish(removeGone, keepCheck)
                state.stock.setFromSweep(state.sweep.stockItems(state.catalog))
                onSaved()
            }, kind = Btn.ACCENT)
            PageButton("Keep sweeping", onClose)
        }
        Text("Back stock will list ${state.sweep.countedCount() - (if (removeGone) s.gone.size else 0) + (if (keepCheck) s.check.size else 0)} cases.",
            color = Color(0xFF93A4B0), fontSize = 12.sp)
    }
}
