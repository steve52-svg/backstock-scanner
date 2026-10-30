package com.frozenpull.scanner

import kotlin.math.max
import kotlin.math.min

object Text {
    fun norm(s: String): String =
        s.uppercase().replace("&", " AND ").replace(Regex("[^A-Z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")

    /** Words printed on nearly every case box; they say nothing about which product it is. */
    val STOP = setOf(
        "KEEP", "FROZEN", "FROZE", "TRADER", "JOE", "JOES", "TJ", "TJS", "NET", "WT", "OZ", "LB", "LBS", "KG",
        "PRODUCT", "OF", "USA", "CA", "MONROVIA", "DIST", "DISTRIBUTED", "BY", "CASE", "CASES", "PACK", "PK", "CT",
        "COUNT", "UPC", "SKU", "LOT", "BEST", "USE", "BEFORE", "EXP", "PERISHABLE", "THIS", "SIDE", "UP", "STORE",
        "AT", "BELOW", "DEG", "THE", "AND", "WITH", "W", "IN", "A", "FOR", "OR", "ITEM", "QTY", "DATE", "MADE",
        "PRODUCED", "PACKED", "CONTAINS", "UNITS", "UNIT", "EA", "EACH", "MFG", "CODE", "NO", "PLEASE", "HANDLE",
        "CARE", "FRAGILE", "TOP", "LADO", "HACIA", "ARRIBA", "MANTENGA", "CONGELADO", "CONGELADOS", "GROSS", "WEIGHT",
    )

    fun tokens(s: String): List<String> =
        norm(s).split(' ').filter { it.length >= 2 && it !in STOP && !it.all(Char::isDigit) }

    fun lev(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    fun sim(a: String, b: String): Float {
        val m = max(a.length, b.length)
        return if (m == 0) 0f else 1f - lev(a, b).toFloat() / m
    }
}

/** A possible product for some text, with how sure the scanner is (0..1). */
data class Guess(val product: Product, val score: Float, val why: String)

/**
 * Turns what the phone read (label text or a barcode) into products, with no AI:
 * spelling-tolerant word matching against product names, short names people write
 * on boxes, SKUs, and everything you've taught it.
 */
class Matcher(private val catalog: Catalog, private val learned: Learned) {

    private class Cand(val product: Product, val tokens: List<String>, val joined: String, val weight: Float, val why: String)

    private var cands: List<Cand> = emptyList()

    init { rebuild() }

    /** Call after the catalog or learned data changes. */
    fun rebuild() {
        val out = mutableListOf<Cand>()
        fun add(p: Product, s: String, w: Float, why: String) {
            val t = Text.tokens(s)
            if (t.isNotEmpty()) out += Cand(p, t, t.joinToString(""), w, why)
        }
        for (p in catalog.products) {
            add(p, p.name, 1f, "name")
            p.aliases.forEach { add(p, it, 1f, "box name") }
        }
        for ((text, id) in learned.writings) catalog[id]?.let { add(it, text, 1.04f, "you taught it") }
        cands = out
    }

    /** How well one read word matches one product word; handles abbreviations ("Vind" for Vindaloo). */
    private fun tokSim(t: String, c: String): Float {
        if (t == c) return 1f
        if (t.length >= 3 && c.startsWith(t)) return 0.78f + 0.2f * t.length / c.length
        if (c.length >= 3 && t.startsWith(c)) return 0.9f
        val s = Text.sim(t, c)
        return if (s >= 0.6f) s else 0f
    }

    private fun score(text: List<String>, joined: String, c: Cand): Float {
        var cw = 0f; var ch = 0f
        for (ct in c.tokens) { cw += ct.length; ch += ct.length * (text.maxOfOrNull { tokSim(it, ct) } ?: 0f) }
        var tw = 0f; var th = 0f
        for (tt in text) { tw += tt.length; th += tt.length * (c.tokens.maxOfOrNull { tokSim(tt, it) } ?: 0f) }
        val tokenScore = 0.65f * (ch / cw) + 0.35f * (th / tw)
        // Whole-string comparison catches run-together or split words ("CAULIPIZZ").
        val whole = Text.sim(joined, c.joined) * 0.95f
        return min(1f, max(tokenScore, whole) * c.weight)
    }

    /** Best few products for a piece of label text, most likely first. */
    fun guessText(raw: String, limit: Int = 5): List<Guess> {
        val n = Text.norm(raw)
        if (n.isBlank()) return emptyList()
        // SKU printed on the box ("SKU# 054321").
        Regex("SKU\\s*#?\\s*:?\\s*0*(\\d{4,6})").find(n)?.groupValues?.get(1)?.let { sku ->
            catalog.products.firstOrNull { it.sku == sku }?.let { return listOf(Guess(it, 1f, "SKU $sku")) }
        }
        learned.writings[n]?.let { id -> catalog[id]?.let { return listOf(Guess(it, 1f, "you taught it")) } }
        val toks = Text.tokens(n)
        if (toks.isEmpty()) return emptyList()
        val joined = toks.joinToString("")
        val best = HashMap<String, Guess>()
        for (c in cands) {
            val s = score(toks, joined, c)
            if (s < 0.3f) continue
            val prev = best[c.product.id]
            if (prev == null || s > prev.score) best[c.product.id] = Guess(c.product, s, c.why)
        }
        return best.values.sortedByDescending { it.score }.take(limit)
    }

    /** Product for a barcode: something you taught it, or the SKU hidden inside the code. */
    fun guessBarcode(raw: String): Guess? {
        learned.codes[raw]?.let { id -> catalog[id]?.let { return Guess(it, 1f, "barcode you taught it") } }
        val d = raw.filter(Char::isDigit)
        if (d.isEmpty()) return null
        catalog.products.firstOrNull { it.caseCode.isNotEmpty() && it.caseCode == d }?.let { return Guess(it, 1f, "case code") }
        val skus = catalog.products.filter { it.sku.length >= 4 }
        // 8-digit case codes: 0 + 6-digit SKU + check digit (00990325 -> 99032).
        if (d.length == 8) {
            val sku = d.substring(1, 7).trimStart('0')
            skus.firstOrNull { it.sku == sku }?.let { return Guess(it, 1f, "SKU $sku in barcode") }
        }
        // GTIN with the Trader Joe's prefix: the SKU follows it.
        val i = d.indexOf("30112")
        if (i >= 0 && d.length >= i + 5 + 5) {
            val sku = d.substring(i + 5, i + 10).trimStart('0')
            skus.firstOrNull { it.sku == sku }?.let { return Guess(it, 0.95f, "SKU $sku in barcode") }
        }
        skus.filter { it.sku.length >= 5 && d.contains(it.sku) }.singleOrNull()?.let { return Guess(it, 0.85f, "SKU ${it.sku} in barcode") }
        return null
    }

    /** Free search for the correction screen. */
    fun search(q: String, limit: Int = 30): List<Product> {
        val n = Text.norm(q)
        if (n.isBlank()) return catalog.products.sortedBy { it.name }.take(limit)
        val contains = catalog.products.filter { p -> Text.norm(p.name).contains(n) || p.aliases.any { Text.norm(it).contains(n) } || p.sku == n }
        val fuzzy = guessText(q, limit).map { it.product }
        return (contains + fuzzy).distinctBy { it.id }.take(limit)
    }
}
