package com.frozenpull.scanner

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue

enum class PullStatus { TODO, PULLED, CANT }

/** One line of the pull list: "Chicken Tikka Masala" + "Pull 2 of 3 · 1 partial, 1 full". */
data class PullItem(
    val id: String,
    val name: String,
    val detail: String,
    /** Where it is, e.g. "Left shelf · top row". */
    val section: String,
    val status: PullStatus = PullStatus.TODO,
)

/**
 * Hands-free's view of the app's pull list (the items marked Need on the Pull list screen).
 * Marks made hands-free go straight back to the pull list.
 */
class PullRun(private val stock: StockStore) {
    val items = mutableStateListOf<PullItem>()
    var index by mutableIntStateOf(0)
    val date: String get() = stock.date

    private fun statusOf(m: String?) = when (m) { "pulled" -> PullStatus.PULLED; "cant" -> PullStatus.CANT; else -> PullStatus.TODO }

    /** Rebuilds from the pull list, keeping your place. */
    fun sync() {
        val current = items.getOrNull(index)?.id
        val list = stock.pullList()
        items.clear()
        items += list.map { PullItem(it.id, it.name, stock.pullText(it), stock.place(it), statusOf(stock.marks[it.id])) }
        index = items.indexOfFirst { it.id == current }.takeIf { it >= 0 }
            ?: items.indexOfFirst { it.status == PullStatus.TODO }.takeIf { it >= 0 } ?: 0
    }

    fun save() {}

    /** Loads a list sent from the pull list page. Returns how many items it found. */
    fun load(text: String): Int { val n = stock.importAny(text); if (n > 0) { sync(); index = items.indexOfFirst { it.status == PullStatus.TODO }.coerceAtLeast(0) }; return n }

    fun set(i: Int, s: PullStatus) {
        if (i !in items.indices) return
        items[i] = items[i].copy(status = s)
        stock.mark(items[i].id, when (s) { PullStatus.PULLED -> "pulled"; PullStatus.CANT -> "cant"; PullStatus.TODO -> "need" })
    }

    /** Start over: every item back to not done, back to the first one. */
    fun restart() { items.forEach { stock.marks[it.id] = "need" }; stock.save(); sync(); index = 0 }

    /** Clear all: takes everything off the pull list (the stock list stays). */
    fun clear() { items.forEach { stock.marks.remove(it.id) }; stock.save(); items.clear(); index = 0 }

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

    fun resultsText(): String = stock.resultsText()

    companion object {
        private val ITEM = Regex("^\\s*(?:☐|\\[ ?\\]|-|•|\\*)\\s*(.+?)\\s*(?:\\(([^()]*)\\))?\\s*$")

        /**
         * Reads the page's older "Copy as text" format:
         *   INDIAN · LEFT SHELF
         *   ☐ Chicken Tikka Masala (Pull 2 of 3 · 1 partial, 1 full)
         */
        fun parse(text: String): List<PullItem> {
            val out = mutableListOf<PullItem>()
            var section = ""
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("Frozen Pull List") || line.startsWith("Walk-in Stock") || line.startsWith("Frozen Back Stock")) continue
                val m = ITEM.find(line)
                if (m != null && (line.startsWith("☐") || line.startsWith("[") || line.startsWith("-") || line.startsWith("•") || line.startsWith("*"))) {
                    out += PullItem("", m.groupValues[1].trim(), m.groupValues[2].trim(), section)
                } else if (line == line.uppercase() && line.any { it.isLetter() }) {
                    section = line.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
                }
            }
            return out
        }
    }
}
