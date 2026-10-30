package com.frozenpull.scanner

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * What the scanner has been taught on this phone. This is how it gets better:
 *
 *  - writings: the text it read off a label -> the product you said it was.
 *    Next time it reads the same (or nearly the same) writing, it knows.
 *  - codes: a barcode it couldn't place -> the product you said it was.
 *  - ignored: text you marked "not a product" (printing like KEEP FROZEN), so it stops showing it.
 *
 * Everything is a small JSON file in the app's private storage, and can be exported
 * (Share learned data) to merge into a future version or a trained model.
 */
class Learned(context: Context) {
    private val file = File(context.filesDir, "learned.json")

    /** normalized text -> product id */
    val writings = mutableMapOf<String, String>()
    /** raw barcode -> product id */
    val codes = mutableMapOf<String, String>()
    val ignored = mutableSetOf<String>()

    init { load() }

    private fun load() {
        if (!file.exists()) return
        try {
            val o = JSONObject(file.readText())
            o.optJSONObject("writings")?.let { w -> w.keys().forEach { writings[it] = w.getString(it) } }
            o.optJSONObject("codes")?.let { w -> w.keys().forEach { codes[it] = w.getString(it) } }
            o.optJSONArray("ignored")?.let { a -> for (i in 0 until a.length()) ignored += a.getString(i) }
        } catch (_: Exception) { }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("writings", JSONObject(writings as Map<*, *>))
        .put("codes", JSONObject(codes as Map<*, *>))
        .put("ignored", org.json.JSONArray(ignored.toList()))

    private fun save() = file.writeText(toJson().toString(1))

    fun teachWriting(text: String, productId: String) {
        val n = Text.norm(text)
        if (n.isBlank()) return
        writings[n] = productId
        ignored -= n
        save()
    }

    fun teachCode(code: String, productId: String) { codes[code] = productId; save() }

    fun ignore(text: String) {
        val n = Text.norm(text)
        if (n.isBlank()) return
        ignored += n
        writings.remove(n)
        save()
    }

    /** Barcodes marked not relevant are kept as "#<code>" so they can't clash with label text. */
    fun ignoreCode(code: String) { ignored += "#$code"; save() }
    fun isIgnoredCode(code: String) = "#$code" in ignored

    /** Undo for "Not relevant". */
    fun unignore(text: String, isBarcode: Boolean) {
        if (isBarcode) ignored -= "#$text" else ignored -= Text.norm(text)
        save()
    }

    fun clear() { writings.clear(); codes.clear(); ignored.clear(); save() }
}
