package com.frozenpull.scanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One frozen product the scanner knows about. */
data class Product(
    val id: String,
    val name: String,
    val sku: String,
    val caseCode: String,
    val aliases: List<String>,
    val shelf: String,
    val row: String,
    val section: String,
    /** Sort key: walk-in order (left shelf, back wall, right shelf, front wall), then row and spot. */
    val order: Int,
    val custom: Boolean = false,
) {
    val place: String get() = listOf(shelf, if (row.isNotBlank()) "$row row" else "").filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * The product list. Most of it ships inside the app (catalog.json, made from the
 * box library in the Frozen Pull List); products added on the phone are kept too.
 */
class Catalog(private val context: Context) {
    private val customFile = java.io.File(context.filesDir, "custom-products.json")

    var products: List<Product> = emptyList()
        private set
    private var byId: Map<String, Product> = emptyMap()

    init { reload() }

    fun reload() {
        val bundled = context.assets.open("catalog.json").bufferedReader().use { it.readText() }
        val list = parse(JSONObject(bundled).getJSONArray("products"), custom = false).toMutableList()
        if (customFile.exists()) list += parse(JSONArray(customFile.readText()), custom = true)
        products = list
        byId = list.associateBy { it.id }
    }

    operator fun get(id: String): Product? = byId[id]

    /** Adds a product typed on the phone (for boxes the catalog doesn't have yet). */
    fun addCustom(name: String): Product {
        val base = "custom-" + name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        byId[base]?.let { return it }
        val arr = if (customFile.exists()) JSONArray(customFile.readText()) else JSONArray()
        arr.put(JSONObject().put("id", base).put("name", name.trim()).put("shelf", "").put("row", "")
            .put("section", "Added on phone").put("order", 900000))
        customFile.writeText(arr.toString())
        reload()
        return byId.getValue(base)
    }

    private fun parse(arr: JSONArray, custom: Boolean): List<Product> = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val al = o.optJSONArray("aliases")
        Product(
            id = o.getString("id"),
            name = o.getString("name"),
            sku = o.optString("sku", ""),
            caseCode = o.optString("caseCode", ""),
            aliases = if (al == null) emptyList() else (0 until al.length()).map { al.getString(it) },
            shelf = o.optString("shelf", ""),
            row = o.optString("row", ""),
            section = o.optString("section", ""),
            order = o.optInt("order", 900000),
            custom = custom,
        )
    }
}
