package com.frozenpull.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Which pictures to compare against: retail packages (sales floor) or shipping cases (walk-in). */
enum class PicKind { RETAIL, CASE }

/**
 * Reference pictures and on-phone picture matching (no internet):
 *  - retail packages from traderjoes.com (sales floor),
 *  - your own walk-in cases, cut from the Oct 30 photos,
 *  - and every box you correct in the live view, saved as it looked in your store.
 * A camera crop is compared with the pictures of the right kind to find the product.
 */
class Retail(private val context: Context, private val catalog: Catalog) {
    private class Ref(val key: String, val product: Product, val kind: PicKind, val asset: String?, val file: File?)

    private val refs = mutableListOf<Ref>()
    private var vectors = mutableListOf<FloatArray>()
    private val taughtDir = File(context.filesDir, "taught-pictures").apply { mkdirs() }
    private var embedder: ImageEmbedder? = null
    @Volatile var ready = false
        private set
    @Volatile var status = "Picture matching not started"
        private set

    init {
        // Retail pictures are named by the last 5 digits of the SKU; some stickers add a digit in front.
        for (f in assetList("retail")) {
            val s = f.removeSuffix(".jpg").padStart(5, '0').takeLast(5)
            catalog.products.firstOrNull { p -> p.allSkus.any { it.padStart(5, '0').takeLast(5) == s } }
                ?.let { refs += Ref("r:$f", it, PicKind.RETAIL, "retail/$f", null) }
        }
        // Walk-in cases: <product id>__<n>.jpg
        for (f in assetList("cases")) {
            catalog[f.substringBefore("__")]?.let { refs += Ref("c:$f", it, PicKind.CASE, "cases/$f", null) }
        }
        // Boxes you corrected: <product id>__<time>__<kind>.jpg
        for (f in taughtDir.listFiles().orEmpty().filter { it.name.endsWith(".jpg") }.sortedBy { it.name }) {
            val parts = f.name.removeSuffix(".jpg").split("__")
            val p = catalog[parts[0]] ?: continue
            val kind = if (parts.getOrNull(2) == "retail") PicKind.RETAIL else PicKind.CASE
            refs += Ref("t:${f.name}", p, kind, null, f)
        }
    }

    private fun assetList(dir: String) =
        runCatching { context.assets.list(dir)?.toList() }.getOrNull().orEmpty().filter { it.endsWith(".jpg") }.sorted()

    val count: Int get() = refs.size
    val taughtCount: Int get() = refs.count { it.file != null }

    /** What the package looks like on the floor (or the case, if there's no retail picture). */
    fun pictureFor(p: Product): Bitmap? {
        val r = refs.firstOrNull { it.product.id == p.id && it.kind == PicKind.RETAIL } ?: refs.firstOrNull { it.product.id == p.id } ?: return null
        return load(r)
    }

    private fun load(r: Ref): Bitmap? = runCatching {
        if (r.asset != null) context.assets.open(r.asset).use { BitmapFactory.decodeStream(it) } else BitmapFactory.decodeFile(r.file!!.path)
    }.getOrNull()

    /** Loads the model and works out every picture's fingerprint (cached after the first time). Call off the main thread. */
    @Synchronized
    fun prepare() {
        if (ready) return
        try {
            status = "Loading picture matching…"
            val opts = ImageEmbedder.ImageEmbedderOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("models/embedder.tflite").build())
                .setL2Normalize(true).setQuantize(false).setRunningMode(RunningMode.IMAGE).build()
            embedder = ImageEmbedder.createFromOptions(context, opts)
            val cache = File(context.filesDir, "picture-fingerprints-v2.bin")
            val old = readCache(cache)
            val out = mutableListOf<FloatArray>()
            var computed = 0
            refs.forEachIndexed { i, r ->
                val v = old[r.key] ?: run {
                    status = "Learning pictures ${i + 1} of ${refs.size}…"
                    computed++
                    load(r)?.let { embed(it) } ?: FloatArray(0)
                }
                out += v
            }
            vectors = out
            if (computed > 0) writeCache(cache)
            ready = true
            status = "${refs.count { it.kind == PicKind.RETAIL }} retail + ${refs.count { it.kind == PicKind.CASE }} case pictures ready"
        } catch (e: Exception) {
            status = "Picture matching unavailable: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun embed(bmp: Bitmap): FloatArray? {
        val e = embedder ?: return null
        val b = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
        return e.embed(BitmapImageBuilder(b).build()).embeddingResult().embeddings().firstOrNull()?.floatEmbedding()
    }

    class Hit(val product: Product, val score: Float, val margin: Float)

    /**
     * The product whose pictures look most like this crop, or null. The margin is measured
     * against the best *other* product, so several pictures of one product don't hide each other.
     */
    fun match(crop: Bitmap, kind: PicKind): Hit? {
        if (!ready) return null
        val v = synchronized(this) { embed(crop) } ?: return null
        val best = HashMap<String, Float>()
        val snapshot = synchronized(this) { refs.indices.map { refs[it] to vectors.getOrNull(it) } }
        for ((r, w) in snapshot) {
            if (r.kind != kind || w == null || w.size != v.size) continue
            var dot = 0f; for (k in v.indices) dot += v[k] * w[k]
            if (dot > (best[r.product.id] ?: -1f)) best[r.product.id] = dot
        }
        val ranked = best.entries.sortedByDescending { it.value }
        val top = ranked.firstOrNull() ?: return null
        val p = catalog[top.key] ?: refs.firstOrNull { it.product.id == top.key }?.product ?: return null
        return Hit(p, top.value, top.value - (ranked.getOrNull(1)?.value ?: 0f))
    }

    /** You corrected a box: keep how it looked as another picture of that product. */
    fun teach(crop: Bitmap, p: Product, kind: PicKind) {
        val small = scaleDown(crop, 256)
        val f = File(taughtDir, "${p.id}__${System.currentTimeMillis()}__${if (kind == PicKind.RETAIL) "retail" else "case"}.jpg")
        runCatching { f.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
        synchronized(this) {
            refs += Ref("t:${f.name}", p, kind, null, f)
            vectors += if (embedder != null) (embed(small) ?: FloatArray(0)) else FloatArray(0)
        }
        if (ready) writeCache(File(context.filesDir, "picture-fingerprints-v2.bin"))
    }

    private fun scaleDown(b: Bitmap, max: Int): Bitmap {
        val s = max.toFloat() / maxOf(b.width, b.height)
        return if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    private fun readCache(f: File): Map<String, FloatArray> = runCatching {
        if (!f.exists()) return emptyMap()
        DataInputStream(f.inputStream().buffered()).use { d ->
            val n = d.readInt()
            (0 until n).associate { d.readUTF() to FloatArray(d.readInt()) { d.readFloat() } }
        }
    }.getOrDefault(emptyMap())

    @Synchronized
    private fun writeCache(f: File) {
        runCatching {
            DataOutputStream(f.outputStream().buffered()).use { d ->
                val pairs = refs.indices.mapNotNull { i -> vectors.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { refs[i].key to it } }
                d.writeInt(pairs.size)
                for ((k, v) in pairs) { d.writeUTF(k); d.writeInt(v.size); v.forEach { d.writeFloat(it) } }
            }
        }
    }
}
