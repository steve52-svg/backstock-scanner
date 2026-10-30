package com.frozenpull.scanner

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt

/** One scanned photo and what was found in it. */
class ScanPhoto(val key: String, val uri: Uri, val label: String, val aspect: Float) {
    val detections = mutableStateListOf<Detection>()
    /** Products this photo has added to the found list. */
    val counted = mutableSetOf<String>()
}

/** Products seen in back stock today, with how many photos each showed up in. */
class Found(context: Context) {
    private val file = File(context.filesDir, "found.json")
    val items = mutableStateMapOf<String, Int>()
    var date: String = LocalDate.now().toString(); private set

    init {
        try {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                if (o.optString("date") == date) {
                    val it = o.getJSONObject("items"); it.keys().forEach { k -> items[k] = it.getInt(k) }
                }
            }
        } catch (_: Exception) { }
    }

    private fun rollDay() {
        val today = LocalDate.now().toString()
        if (today != date) { date = today; items.clear() }
    }

    fun add(id: String) { rollDay(); items[id] = (items[id] ?: 0) + 1; save() }
    fun remove(id: String) { val n = (items[id] ?: 0) - 1; if (n <= 0) items.remove(id) else items[id] = n; save() }
    fun drop(id: String) { items.remove(id); save() }
    fun clear() { items.clear(); save() }
    private fun save() = file.writeText(JSONObject().put("date", date).put("items", JSONObject(items.toMap() as Map<*, *>)).toString())
}

/** Everything the app holds while it runs. */
class AppState(private val context: Context) {
    val catalog = Catalog(context)
    val learned = Learned(context)
    val matcher = Matcher(catalog, learned)
    val scanner = Scanner(matcher, learned)
    val found = Found(context)
    val photos = mutableStateListOf<ScanPhoto>()

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var careful by mutableStateOf(prefs.getBoolean("careful", true))
        private set
    fun updateCareful(v: Boolean) { careful = v; prefs.edit().putBoolean("careful", v).apply() }

    /** Handwritten labels saved for training a phone model later. */
    var trainingCount by mutableIntStateOf(prefs.getInt("trainingCount", 0))
        private set

    private val scanDir = File(context.cacheDir, "scans").apply { mkdirs() }

    init {
        // Camera scans are kept two days, then cleared.
        val cutoff = System.currentTimeMillis() - 2 * 24 * 3600 * 1000L
        scanDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    fun newCaptureFile(): File = File(scanDir, "scan-${System.currentTimeMillis()}.jpg")

    fun decode(uri: Uri, maxSide: Int): Bitmap {
        val src = if (uri.scheme == "file") ImageDecoder.createSource(File(uri.path!!))
                  else ImageDecoder.createSource(context.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width; val h = info.size.height
            val s = maxSide.toFloat() / max(w, h)
            if (s < 1f) d.setTargetSize((w * s).roundToInt(), (h * s).roundToInt())
        }
        return if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
    }

    /** Reads one photo and adds what it finds. */
    suspend fun scan(uri: Uri, label: String): ScanPhoto {
        val bmp = withContext(Dispatchers.IO) { decode(uri, 3200) }
        val dets = withContext(Dispatchers.Default) { scanner.read(bmp, careful) }
        val photo = ScanPhoto("p${System.nanoTime()}", uri, label, bmp.width.toFloat() / bmp.height)
        bmp.recycle()
        photo.detections += dets.sortedWith(compareBy({ it.level.ordinal }, { it.box.left }))
        photos += photo
        recount(photo)
        return photo
    }

    /** Keeps the found list in step with what this photo shows. */
    fun recount(photo: ScanPhoto) {
        val now = photo.detections.filter { it.counts }.mapNotNull { it.product?.id }.toSet()
        (photo.counted - now).forEach { found.remove(it) }
        (now - photo.counted).forEach { found.add(it) }
        photo.counted.clear(); photo.counted += now
    }

    /**
     * You told it what a label is. It remembers the writing (or barcode), saves the label
     * picture for training if it's handwritten, and re-checks every other label.
     */
    suspend fun confirm(photo: ScanPhoto, det: Detection, product: Product, handwritten: Boolean): Int {
        if (det.isBarcode) learned.teachCode(det.text, product.id) else learned.teachWriting(det.text, product.id)
        matcher.rebuild()
        val i = photo.detections.indexOfFirst { it.id == det.id }
        if (i >= 0) photo.detections[i] = det.copy(product = product, level = Level.SURE, confirmed = true,
            guesses = listOf(Guess(product, 1f, "you said so")) + det.guesses.filter { it.product.id != product.id })
        recount(photo)
        if (handwritten && !det.isBarcode) withContext(Dispatchers.IO) { saveTraining(photo, det, product) }
        return withContext(Dispatchers.Default) { rematchAll() }
    }

    suspend fun ignore(photo: ScanPhoto, det: Detection) {
        if (det.isBarcode) learned.ignoreCode(det.text) else learned.ignore(det.text)
        matcher.rebuild()
        photo.detections.removeAll { it.id == det.id }
        recount(photo)
        withContext(Dispatchers.Default) { rematchAll() }
    }

    /** Puts back a label that was marked not relevant by mistake. */
    suspend fun unignore(photo: ScanPhoto, det: Detection) {
        learned.unignore(det.text, det.isBarcode)
        matcher.rebuild()
        if (photo.detections.none { it.id == det.id }) photo.detections += det
        recount(photo)
        withContext(Dispatchers.Default) { rematchAll() }
    }

    fun addProduct(name: String): Product { val p = catalog.addCustom(name); matcher.rebuild(); return p }

    /** Re-reads the text of every label not yet confirmed, using what it now knows. Returns how many changed. */
    fun rematchAll(): Int {
        var changed = 0
        for (p in photos) {
            for (i in p.detections.indices) {
                val d = p.detections[i]
                if (d.confirmed) continue
                val g = if (d.isBarcode) listOfNotNull(matcher.guessBarcode(d.text)) else matcher.guessText(d.text)
                val level = if (d.isBarcode) (if (g.isEmpty()) Level.UNKNOWN else Level.SURE) else Scanner.levelFor(g)
                val prod = if (level == Level.UNKNOWN) null else g.firstOrNull()?.product
                if (prod?.id != d.product?.id || level != d.level) {
                    p.detections[i] = d.copy(guesses = g, product = prod, level = level); changed++
                }
            }
            recount(p)
        }
        return changed
    }

    /** Cuts the label out of the photo for the training set: Pictures/BackStockScanner/Training/<product>/. */
    private fun saveTraining(photo: ScanPhoto, det: Detection, product: Product) {
        val crop = cropOf(photo, det, 1600) ?: return
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val name = "${product.id}-$stamp"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BackStockScanner/Training/${product.id}")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        context.contentResolver.openOutputStream(uri)?.use { crop.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        File(context.filesDir, "training.csv").appendText(
            listOf(stamp, product.id, product.name, det.text, "$name.jpg").joinToString(",") { "\"" + it.replace("\"", "'") + "\"" } + "\n",
        )
        trainingCount += 1
        prefs.edit().putInt("trainingCount", trainingCount).apply()
    }

    /** The label's area of the photo, with a margin, for showing big or saving. */
    fun cropOf(photo: ScanPhoto, det: Detection, maxSide: Int = 2400): Bitmap? = try {
        val bmp = decode(photo.uri, maxSide)
        val padX = det.box.width() * 0.25f + 0.01f; val padY = det.box.height() * 0.4f + 0.01f
        val l = ((det.box.left - padX).coerceIn(0f, 1f) * bmp.width).toInt()
        val t = ((det.box.top - padY).coerceIn(0f, 1f) * bmp.height).toInt()
        val r = ((det.box.right + padX).coerceIn(0f, 1f) * bmp.width).toInt()
        val b = ((det.box.bottom + padY).coerceIn(0f, 1f) * bmp.height).toInt()
        if (r - l < 4 || b - t < 4) null else Bitmap.createBitmap(bmp, l, t, r - l, b - t)
    } catch (_: Exception) { null }

    fun learnedJson(): String = learned.toJson().put("trainingCsv",
        File(context.filesDir, "training.csv").takeIf { it.exists() }?.readText() ?: "").toString(1)

    /** The found list as plain text, in walk-in order. */
    fun foundText(): String {
        val sb = StringBuilder("Back stock found — ${found.date}\n")
        var lastShelf = "?"
        foundProducts().forEach { (p, n) ->
            val shelf = p.shelf.ifBlank { "Other" }
            if (shelf != lastShelf) { sb.append("\n").append(shelf).append("\n"); lastShelf = shelf }
            sb.append("• ").append(p.name).append(if (n > 1) "  (in $n photos)" else "").append("\n")
        }
        return sb.toString()
    }

    fun foundProducts(): List<Pair<Product, Int>> =
        found.items.mapNotNull { (id, n) -> catalog[id]?.let { it to n } }.sortedBy { it.first.order }
}
