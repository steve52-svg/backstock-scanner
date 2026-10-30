package com.frozenpull.scanner

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

enum class Level { SURE, GUESS, UNKNOWN }

/** One thing read in a photo: a label or a barcode, where it is, and what product it seems to be. */
data class Detection(
    val id: Int,
    /** Position in the photo, 0..1 on both axes. */
    val box: RectF,
    val text: String,
    val isBarcode: Boolean,
    val guesses: List<Guess>,
    val product: Product?,
    val level: Level,
    val confirmed: Boolean = false,
) {
    val counts: Boolean get() = product != null && (confirmed || level == Level.SURE)
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
}

/**
 * Reads a photo with Google's on-phone readers (no internet, no AI service):
 * barcodes, and text including hand-printed labels. Then [Matcher] turns
 * what was read into products.
 */
class Scanner(private val matcher: Matcher, private val learned: Learned) {
    private val textReader = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val barcodeReader = BarcodeScanning.getClient()

    /** A run of text lines read from one spot, before matching. */
    class Piece(val text: String, val box: RectF)

    /**
     * @param careful also reads the photo in four overlapping quarters, which picks up
     * smaller writing at the cost of a few extra seconds.
     */
    suspend fun read(bmp: Bitmap, careful: Boolean): List<Detection> {
        val W = bmp.width.toFloat(); val H = bmp.height.toFloat()
        val out = mutableListOf<Detection>()
        var nextId = 0

        // Barcodes (whole photo).
        try {
            val codes = barcodeReader.process(InputImage.fromBitmap(bmp, 0)).await()
            for (b in codes) {
                val raw = b.rawValue ?: continue
                if (learned.isIgnoredCode(raw)) continue
                val r = b.boundingBox ?: continue
                val g = matcher.guessBarcode(raw)
                val level = when { g == null -> Level.UNKNOWN; g.score >= 0.95f -> Level.SURE; else -> Level.GUESS }
                out += Detection(nextId++, norm(r, 0, 0, W, H), raw, true, listOfNotNull(g), g?.product, level)
            }
        } catch (_: Exception) { }

        // Text: whole photo, then quarters if careful.
        val tiles = mutableListOf(Rect(0, 0, bmp.width, bmp.height))
        if (careful) {
            val tw = (bmp.width * 0.56f).toInt(); val th = (bmp.height * 0.56f).toInt()
            for (ty in listOf(0, bmp.height - th)) for (tx in listOf(0, bmp.width - tw)) tiles += Rect(tx, ty, tx + tw, ty + th)
        }
        val textDets = mutableListOf<Detection>()
        for (t in tiles) {
            val src = if (t.width() == bmp.width && t.height() == bmp.height) bmp
                      else Bitmap.createBitmap(bmp, t.left, t.top, t.width(), t.height())
            val result = try { textReader.process(InputImage.fromBitmap(src, 0)).await() } catch (_: Exception) { null } ?: continue
            for (block in result.textBlocks) {
                val lines = block.lines.mapNotNull { l -> l.boundingBox?.let { Piece(l.text, norm(it, t.left, t.top, W, H)) } }
                textDets += matchBlock(lines) { nextId++ }
            }
        }
        out += dedupe(textDets)
        return out
    }

    /** Picks the products in one block of text lines; leftover lines become an "unknown" label. */
    private fun matchBlock(lines: List<Piece>, newId: () -> Int): List<Detection> {
        val out = mutableListOf<Detection>()
        val used = BooleanArray(lines.size)
        while (true) {
            var best: Triple<Int, Int, List<Guess>>? = null
            var bestScore = 0f
            for (i in lines.indices) {
                if (used[i]) continue
                for (len in 1..3) {
                    val end = i + len
                    if (end > lines.size || (i until end).any { used[it] }) break
                    val text = lines.subList(i, end).joinToString(" ") { it.text }
                    if (Text.norm(text) in learned.ignored) continue
                    val g = matcher.guessText(text)
                    val s = (g.firstOrNull()?.score ?: 0f) + 0.005f * len
                    if (s > bestScore) { bestScore = s; best = Triple(i, end, g) }
                }
            }
            val b = best ?: break
            val top = b.third.firstOrNull() ?: break
            if (top.score < 0.6f) break
            val level = levelFor(b.third)
            val span = lines.subList(b.first, b.second)
            out += Detection(newId(), union(span.map { it.box }), span.joinToString(" ") { it.text }, false, b.third, top.product, level)
            for (k in b.first until b.second) used[k] = true
        }
        // Whatever is left: show it as unread text if it looks like a short label (not a paragraph of fine print).
        val rest = lines.filterIndexed { i, _ -> !used[i] }
        if (rest.isNotEmpty()) {
            val text = rest.joinToString(" ") { it.text }
            val n = Text.norm(text)
            val letters = n.count { it.isLetter() }
            if (n !in learned.ignored && Text.tokens(text).isNotEmpty() && letters >= 3 && text.length <= 60) {
                val g = matcher.guessText(text)
                out += Detection(newId(), union(rest.map { it.box }), text, false, g, null, Level.UNKNOWN)
            }
        }
        return out
    }

    /** The quarters overlap the whole photo, so the same label can be read twice; keep the better reading. */
    private fun dedupe(list: List<Detection>): List<Detection> {
        fun rank(d: Detection) = (2 - d.level.ordinal) * 10f + (d.guesses.firstOrNull()?.score ?: 0f)
        val kept = mutableListOf<Detection>()
        for (d in list.sortedByDescending(::rank)) {
            if (kept.none { overlap(it.box, d.box) > 0.5f }) kept += d
        }
        return kept
    }

    companion object {
        /** Sure = an exact or clearly-best match; guess = probably right, tap to check. */
        fun levelFor(g: List<Guess>): Level {
            val top = g.firstOrNull()?.score ?: return Level.UNKNOWN
            val second = g.getOrNull(1)?.score ?: 0f
            return when {
                top >= 0.99f || (top >= 0.85f && top - second >= 0.1f) -> Level.SURE
                top >= 0.6f -> Level.GUESS
                else -> Level.UNKNOWN
            }
        }

        fun norm(r: Rect, ox: Int, oy: Int, W: Float, H: Float) =
            RectF((r.left + ox) / W, (r.top + oy) / H, (r.right + ox) / W, (r.bottom + oy) / H)

        fun union(boxes: List<RectF>): RectF = RectF(
            boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom },
        )

        /** Overlap as a share of the smaller box. */
        fun overlap(a: RectF, b: RectF): Float {
            val w = min(a.right, b.right) - max(a.left, b.left)
            val h = min(a.bottom, b.bottom) - max(a.top, b.top)
            if (w <= 0 || h <= 0) return 0f
            val small = min(a.width() * a.height(), b.width() * b.height())
            return if (small <= 0) 0f else w * h / small
        }
    }
}
