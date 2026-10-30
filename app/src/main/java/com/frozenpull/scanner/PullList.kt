package com.frozenpull.scanner

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

enum class PullStatus { TODO, PULLED, CANT }

/** One line of the pull list: "Chicken Tikka Masala" + "Pull 2 of 3 · 1 partial, 1 full". */
data class PullItem(
    val name: String,
    val detail: String,
    /** The section heading it was under, e.g. "Indian · Left shelf". */
    val section: String,
    val status: PullStatus = PullStatus.TODO,
)

/**
 * The pull list for hands-free pulling. It comes from the Frozen Pull List page
 * (Print & share → Copy as text) and the results go back the same way.
 */
class PullRun(context: Context) {
    private val file = File(context.filesDir, "pull-run.json")
    val items = mutableStateListOf<PullItem>()
    var index by mutableIntStateOf(0)
    var date: String = LocalDate.now().toString(); private set

    init {
        try {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                date = o.optString("date", date)
                val a = o.getJSONArray("items")
                for (i in 0 until a.length()) {
                    val x = a.getJSONObject(i)
                    items += PullItem(x.getString("name"), x.optString("detail"), x.optString("section"),
                        runCatching { PullStatus.valueOf(x.optString("status")) }.getOrDefault(PullStatus.TODO))
                }
                index = o.optInt("index", 0).coerceIn(0, (items.size - 1).coerceAtLeast(0))
            }
        } catch (_: Exception) { }
    }

    fun save() {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("name", it.name).put("detail", it.detail).put("section", it.section).put("status", it.status.name)) }
        file.writeText(JSONObject().put("date", date).put("index", index).put("items", a).toString())
    }

    /** Loads a copied pull list. Returns how many items it found. */
    fun load(text: String): Int {
        val parsed = parse(text)
        if (parsed.isEmpty()) return 0
        items.clear(); items += parsed; index = 0; date = LocalDate.now().toString(); save()
        return parsed.size
    }

    fun set(i: Int, s: PullStatus) {
        if (i !in items.indices) return
        items[i] = items[i].copy(status = s); save()
    }

    /** The next item still to do after [from], wrapping around; null when everything is done. */
    fun nextTodo(from: Int): Int? {
        if (items.isEmpty()) return null
        for (k in 1..items.size) {
            val i = (from + k) % items.size
            if (items[i].status == PullStatus.TODO) return i
        }
        return null
    }

    val pulled get() = items.count { it.status == PullStatus.PULLED }
    val cant get() = items.count { it.status == PullStatus.CANT }
    val left get() = items.count { it.status == PullStatus.TODO }

    /** Text to paste back into the pull list page: ✓ = pulled, ✗ = can't find. */
    fun resultsText(): String {
        val sb = StringBuilder("Pull results – $date\n")
        items.filter { it.status != PullStatus.TODO }.forEach {
            sb.append(if (it.status == PullStatus.PULLED) "✓ " else "✗ ").append(it.name).append('\n')
        }
        return sb.toString()
    }

    companion object {
        private val ITEM = Regex("^\\s*(?:☐|\\[ ?\\]|-|•|\\*)\\s*(.+?)\\s*(?:\\(([^()]*)\\))?\\s*$")

        /**
         * Reads the page's "Copy as text" format:
         *   Frozen Pull List – Fri, Oct 30, 2026
         *   INDIAN · LEFT SHELF
         *   ☐ Chicken Tikka Masala (Pull 2 of 3 · 1 partial, 1 full)
         */
        fun parse(text: String): List<PullItem> {
            val out = mutableListOf<PullItem>()
            var section = ""
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("Frozen Pull List") || line.startsWith("Frozen Back Stock")) continue
                val m = ITEM.find(line)
                if (m != null && (line.startsWith("☐") || line.startsWith("[") || line.startsWith("-") || line.startsWith("•") || line.startsWith("*"))) {
                    out += PullItem(m.groupValues[1].trim(), m.groupValues[2].trim(), section)
                } else if (line == line.uppercase() && line.any { it.isLetter() }) {
                    section = line.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
                }
            }
            return out
        }
    }
}
