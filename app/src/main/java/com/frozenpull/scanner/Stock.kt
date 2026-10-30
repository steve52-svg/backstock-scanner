package com.frozenpull.scanner

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** One product in today's walk-in stock: how many cases, how many are partial, and where. */
data class StockItem(
    val id: String,
    val name: String,
    val group: String,
    val shelf: String,
    val row: String,
    val pos: Double,
    val cases: Int,
    val partial: Int,
    /** Where it usually lives (from the box library), to flag overflow. */
    val usual: String = "",
)

/**
 * Today's stock list and pull-list marks, kept on the phone.
 * It's filled from the Frozen Pull List page (Send to app), which does the AI photo reading;
 * everything after that (marking, pulling, hands-free, the stock sheet) happens here.
 */
class StockStore(context: Context) {
    private val file = File(context.filesDir, "stock.json")

    val items = mutableStateListOf<StockItem>()
    /** id -> need | skip | pulled | cant */
    val marks = mutableStateMapOf<String, String>()
    val qty = mutableStateMapOf<String, Int>()
    /** Pull order (all ids; the pull list shows the ones marked). */
    val order = mutableStateListOf<String>()
    val pulledToday = mutableStateListOf<String>()
    var date by mutableStateOf(LocalDate.now().toString())
    var updated by mutableStateOf("")
    /** The last export loaded from the page, so the same one isn't loaded twice. */
    var lastImport by mutableStateOf("")

    init { load() }

    // ---------- saving ----------
    private fun load() {
        try {
            if (!file.exists()) return
            val o = JSONObject(file.readText())
            date = o.optString("date", date); updated = o.optString("updated"); lastImport = o.optString("lastImport")
            readItems(o.optJSONArray("items"))
            o.optJSONObject("marks")?.let { m -> m.keys().forEach { marks[it] = m.getString(it) } }
            o.optJSONObject("qty")?.let { m -> m.keys().forEach { qty[it] = m.getInt(it) } }
            o.optJSONArray("order")?.let { a -> for (i in 0 until a.length()) order += a.getString(i) }
            o.optJSONArray("pulledToday")?.let { a -> for (i in 0 until a.length()) pulledToday += a.getString(i) }
        } catch (_: Exception) { }
    }

    fun save() {
        val a = JSONArray()
        items.forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("group", it.group).put("shelf", it.shelf).put("row", it.row)
                .put("pos", it.pos).put("cases", it.cases).put("partial", it.partial).put("usual", it.usual))
        }
        file.writeText(JSONObject().put("date", date).put("updated", updated).put("lastImport", lastImport).put("items", a)
            .put("marks", JSONObject(marks.toMap() as Map<*, *>)).put("qty", JSONObject(qty.toMap() as Map<*, *>))
            .put("order", JSONArray(order.toList())).put("pulledToday", JSONArray(pulledToday.toList())).toString())
    }

    private fun readItems(a: JSONArray?) {
        items.clear()
        if (a == null) return
        for (i in 0 until a.length()) {
            val x = a.getJSONObject(i)
            items += StockItem(
                id = x.getString("id"), name = x.getString("name"), group = x.optString("group"),
                shelf = x.optString("shelf"), row = x.optString("row"), pos = x.optDouble("pos", 99.0),
                cases = x.optInt("cases", 1).coerceAtLeast(1), partial = x.optInt("partial", 0), usual = x.optString("usual"),
            )
        }
    }

    // ---------- loading from the page ----------
    /**
     * Loads what the page's Send to app put on the clipboard (or an old copied pull list).
     * Returns how many items were loaded, 0 if the text isn't a list.
     */
    fun importAny(text: String): Int {
        val t = text.trim()
        if (t.startsWith(EXPORT_TAG)) return importExport(t)
        // An older "Copy as text" pull list: mark those items Need.
        val lines = PullRun.parse(t)
        if (lines.isEmpty()) return 0
        for (l in lines) {
            val hit = items.firstOrNull { it.name.equals(l.name, ignoreCase = true) } ?: StockItem(
                id = "t-" + l.name.lowercase().replace(Regex("[^a-z0-9]+"), "-"), name = l.name, group = l.section, shelf = l.section,
                row = "", pos = 99.0, cases = Regex("of (\\d+)").find(l.detail)?.groupValues?.get(1)?.toIntOrNull() ?: 1,
                partial = if (l.detail.contains("partial", ignoreCase = true)) 1 else 0,
            ).also { items += it; order += it.id }
            if (marks[hit.id] !in setOf("pulled", "cant")) marks[hit.id] = "need"
            Regex("Pull (\\d+) of").find(l.detail)?.groupValues?.get(1)?.toIntOrNull()?.let { qty[hit.id] = it }
        }
        save()
        return lines.size
    }

    private fun importExport(t: String): Int {
        val o = try { JSONObject(t.substringAfter('\n')) } catch (e: Exception) { return 0 }
        val stamp = o.optString("updated") + "|" + t.length + "|" + t.hashCode()
        readItems(o.optJSONArray("items"))
        updated = o.optString("updated"); date = o.optString("date", LocalDate.now().toString())
        val ids = items.map { it.id }.toSet()
        // The page's marks win; marks for items no longer in stock are dropped.
        val pm = o.optJSONObject("marks")
        val newMarks = mutableMapOf<String, String>()
        marks.filterKeys { it in ids }.forEach { (k, v) -> newMarks[k] = v }
        pm?.keys()?.forEach { k -> if (k in ids) newMarks[k] = pm.getString(k) }
        marks.clear(); marks.putAll(newMarks)
        o.optJSONObject("qty")?.let { q -> q.keys().forEach { k -> if (k in ids) qty[k] = q.getInt(k) } }
        qty.keys.filter { it !in ids }.forEach { qty.remove(it) }
        val po = o.optJSONArray("order")
        val newOrder = mutableListOf<String>()
        if (po != null) for (i in 0 until po.length()) po.getString(i).takeIf { it in ids && it !in newOrder }?.let { newOrder += it }
        items.forEach { if (it.id !in newOrder) newOrder += it.id }
        order.clear(); order.addAll(newOrder)
        pulledToday.clear()
        o.optJSONArray("pulledToday")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { x -> pulledToday += x.optString("name") + if (x.optString("kind") == "partial") " (partial)" else "" } }
        lastImport = stamp
        save()
        return items.size
    }

    fun isNewExport(text: String): Boolean {
        val t = text.trim()
        if (!t.startsWith(EXPORT_TAG)) return false
        val updatedField = Regex("\"updated\"\\s*:\\s*\"([^\"]*)\"").find(t)?.groupValues?.get(1) ?: ""
        return lastImport != "$updatedField|${t.length}|${t.hashCode()}"
    }

    // ---------- marks ----------
    fun mark(id: String, v: String?) { if (v == null) marks.remove(id) else marks[id] = v; save() }
    fun isNeed(id: String) = marks[id] in setOf("need", "pulled", "cant")
    fun qtyOf(it: StockItem) = (qty[it.id] ?: 1).coerceIn(1, it.cases)
    fun setQty(it: StockItem, n: Int) { qty[it.id] = n.coerceIn(1, it.cases); save() }

    /** Start new day: an empty stock list and no marks. The pull order is kept. */
    fun newDay() {
        items.clear(); marks.clear(); qty.clear(); pulledToday.clear()
        date = LocalDate.now().toString(); updated = ""; save()
    }

    fun pullList(): List<StockItem> {
        val byId = items.associateBy { it.id }
        return order.mapNotNull { byId[it] }.filter { isNeed(it.id) }
    }

    /** Moves an item up or down among the items on the pull list. */
    fun move(id: String, delta: Int) {
        val pl = pullList().map { it.id }
        val i = pl.indexOf(id); val j = i + delta
        if (i < 0 || j !in pl.indices) return
        val a = order.indexOf(pl[i]); val b = order.indexOf(pl[j])
        order[a] = pl[j]; order[b] = pl[i]; save()
    }

    // ---------- text ----------
    fun caseText(it: StockItem): String = when {
        it.cases <= 1 -> if (it.partial > 0) "Partial case" else "Full case"
        else -> "${it.cases} cases · " + when (it.partial) { 0 -> "all full"; it.cases -> "all partial"; else -> "${it.partial} partial" }
    }

    /** "Pull 2 of 3 · 1 partial, 1 full" (partials first). */
    fun pullText(it: StockItem): String {
        if (it.cases <= 1) return caseText(it)
        val n = qtyOf(it); val p = minOf(n, it.partial); val f = n - p
        return "Pull $n of ${it.cases} · " + listOfNotNull(if (p > 0) "$p partial" else null, if (f > 0) "$f full" else null).joinToString(", ")
    }

    fun place(it: StockItem): String = listOf(it.shelf, if (it.row.isNotBlank()) "${it.row.lowercase()} row" else "").filter { s -> s.isNotBlank() }.joinToString(" · ")
    fun overflow(it: StockItem): String = if (it.usual.isNotBlank() && it.shelf.isNotBlank() && it.usual != it.shelf) "overflow, usually ${it.usual.lowercase()}" else ""

    /** For the page's "Paste results from app": ✓ pulled, ✗ can't find, ☐ still to pull. */
    fun resultsText(): String {
        val sb = StringBuilder("Pull results – $date\n")
        for (it in pullList()) {
            when (marks[it.id]) {
                "pulled" -> sb.append("✓ ")
                "cant" -> sb.append("✗ ")
                else -> sb.append("☐ ")
            }
            sb.append(it.name).append('\n')
        }
        return sb.toString()
    }

    private val wallOrder = listOf("Left shelf", "Back wall", "Right shelf", "Front wall")
    private val rowOrder = listOf("Top", "Middle", "Bottom")

    /** The stock sheet, by wall and row. */
    fun sheetGroups(): List<Pair<String, List<StockItem>>> {
        val sorted = items.sortedWith(compareBy<StockItem>({ wallOrder.indexOf(it.shelf).let { i -> if (i < 0) 9 else i } },
            { rowOrder.indexOf(it.row).let { i -> if (i < 0) 3 else i } }, { it.pos }))
        val out = mutableListOf<Pair<String, MutableList<StockItem>>>()
        for (it in sorted) {
            val k = if (it.shelf.isNotBlank()) it.shelf + (if (it.row.isNotBlank()) " · ${it.row.lowercase()} row" else "") else it.group.ifBlank { "Other" }
            if (out.isEmpty() || out.last().first != k) out += k to mutableListOf()
            out.last().second += it
        }
        return out
    }

    fun sheetText(): String {
        val sb = StringBuilder("Walk-in Stock – $date\n${items.size} products · ${items.sumOf { it.cases }} cases\n")
        for ((k, list) in sheetGroups()) {
            sb.append("\n").append(k.uppercase()).append("\n")
            list.forEach { sb.append("☐ ").append(it.name).append(" (").append(listOf(caseText(it), overflow(it)).filter { s -> s.isNotBlank() }.joinToString(" · ")).append(")\n") }
        }
        if (pulledToday.isNotEmpty()) {
            sb.append("\nPULLED SINCE THE START OF THE DAY\n")
            pulledToday.forEach { sb.append("✓ ").append(it).append("\n") }
        }
        return sb.toString()
    }

    fun sheetHtml(): String {
        fun e(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
        val rows = StringBuilder()
        for ((k, list) in sheetGroups()) {
            rows.append("<h2>").append(e(k)).append("</h2><table>")
            list.forEach {
                rows.append("<tr><td class=b>&#9744;</td><td>").append(e(it.name)).append("</td><td class=r>")
                    .append(e(listOf(caseText(it), overflow(it)).filter { s -> s.isNotBlank() }.joinToString(" · "))).append("</td></tr>")
            }
            rows.append("</table>")
        }
        if (pulledToday.isNotEmpty()) {
            rows.append("<h2>Pulled since the start of the day</h2><table>")
            pulledToday.forEach { rows.append("<tr><td class=b>&#10003;</td><td>").append(e(it)).append("</td><td></td></tr>") }
            rows.append("</table>")
        }
        return """<!doctype html><html><head><meta charset="utf-8"><style>
            body{font-family:sans-serif;margin:28px;color:#111}h1{font-size:22px;margin:0}p{color:#555;margin:4px 0 12px}
            h2{font-size:12px;text-transform:uppercase;letter-spacing:.08em;color:#444;margin:16px 0 4px;border-bottom:1px solid #999}
            table{width:100%;border-collapse:collapse}td{padding:5px 4px;border-bottom:1px solid #ddd;font-size:14px}
            td.b{width:18px;font-size:16px}td.r{text-align:right;color:#555;font-size:12px;white-space:nowrap}
            </style></head><body><h1>Walk-in Stock</h1><p>$date · ${items.size} products · ${items.sumOf { it.cases }} cases</p>$rows</body></html>"""
    }

    companion object {
        const val EXPORT_TAG = "BACKSTOCK-EXPORT"
    }
}
