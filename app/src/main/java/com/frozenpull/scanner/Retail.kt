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

/**
 * The retail pictures from traderjoes.com (what each package looks like on the sales floor),
 * and on-phone picture matching: a camera crop is compared with every picture to find the
 * product. No internet; the pictures and the small matching model ship inside the app.
 */
class Retail(private val context: Context, private val catalog: Catalog) {
    /** 5-digit SKU (as in the picture's file name) -> product. */
    private val bySku: Map<String, Product>
    val skus: List<String>
    private var embedder: ImageEmbedder? = null
    private var vectors: Array<FloatArray> = emptyArray()
    @Volatile var ready = false
        private set
    @Volatile var status = "Picture matching not started"
        private set

    init {
        val files = runCatching { context.assets.list("retail")?.toList() }.getOrNull().orEmpty().filter { it.endsWith(".jpg") }.sorted()
        val m = HashMap<String, Product>()
        for (f in files) {
            // Pictures are named by the last 5 digits of the SKU; some stickers add a digit in front.
            val s = f.removeSuffix(".jpg").padStart(5, '0').takeLast(5)
            catalog.products.firstOrNull { p -> p.allSkus.any { it.padStart(5, '0').takeLast(5) == s } }?.let { m[f.removeSuffix(".jpg")] = it }
        }
        bySku = m
        skus = files.map { it.removeSuffix(".jpg") }.filter { it in m }
    }

    val count: Int get() = skus.size

    /** The retail picture for a product, if there is one. */
    fun pictureFor(p: Product): Bitmap? {
        val sku = skus.firstOrNull { bySku[it]?.id == p.id } ?: return null
        return runCatching { context.assets.open("retail/$sku.jpg").use { BitmapFactory.decodeStream(it) } }.getOrNull()
    }

    fun hasPicture(p: Product) = skus.any { bySku[it]?.id == p.id }

    /** Loads the model and works out every picture's fingerprint (cached after the first time). Call off the main thread. */
    @Synchronized
    fun prepare() {
        if (ready) return
        try {
            status = "Loading picture matching…"
            val opts = ImageEmbedder.ImageEmbedderOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("models/embedder.tflite").build())
                .setL2Normalize(true).setQuantize(false).setRunningMode(RunningMode.IMAGE).build()
            val e = ImageEmbedder.createFromOptions(context, opts)
            embedder = e
            val cache = File(context.filesDir, "retail-fingerprints-v1.bin")
            val key = skus.joinToString(",").hashCode()
            vectors = readCache(cache, key) ?: run {
                val out = Array(skus.size) { i ->
                    status = "Learning retail pictures ${i + 1} of ${skus.size}…"
                    val bmp = context.assets.open("retail/${skus[i]}.jpg").use { BitmapFactory.decodeStream(it) }
                    embed(bmp) ?: FloatArray(0)
                }
                writeCache(cache, key, out); out
            }
            ready = true
            status = "${skus.size} retail pictures ready"
        } catch (e: Exception) {
            status = "Picture matching unavailable: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun embed(bmp: Bitmap): FloatArray? {
        val e = embedder ?: return null
        val b = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
        val r = e.embed(BitmapImageBuilder(b).build())
        return r.embeddingResult().embeddings().firstOrNull()?.floatEmbedding()
    }

    class Hit(val product: Product, val score: Float, val margin: Float)

    /** The product whose retail picture looks most like this crop, or null if nothing is close. */
    fun match(crop: Bitmap): Hit? {
        if (!ready) return null
        val v = synchronized(this) { embed(crop) } ?: return null
        var best = -1; var bs = -1f; var second = -1f
        for (i in vectors.indices) {
            val w = vectors[i]; if (w.size != v.size) continue
            var dot = 0f; for (k in v.indices) dot += v[k] * w[k]
            if (dot > bs) { second = bs; bs = dot; best = i } else if (dot > second) second = dot
        }
        if (best < 0) return null
        val p = bySku[skus[best]] ?: return null
        return Hit(p, bs, bs - second)
    }

    private fun readCache(f: File, key: Int): Array<FloatArray>? = runCatching {
        if (!f.exists()) return null
        DataInputStream(f.inputStream().buffered()).use { d ->
            if (d.readInt() != key) return null
            val n = d.readInt()
            Array(n) { FloatArray(d.readInt()) { d.readFloat() } }
        }
    }.getOrNull()

    private fun writeCache(f: File, key: Int, v: Array<FloatArray>) {
        runCatching {
            DataOutputStream(f.outputStream().buffered()).use { d ->
                d.writeInt(key); d.writeInt(v.size)
                for (a in v) { d.writeInt(a.size); a.forEach { d.writeFloat(it) } }
            }
        }
    }
}
