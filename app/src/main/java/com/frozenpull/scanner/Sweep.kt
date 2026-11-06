package com.frozenpull.scanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

// ---------------------------------------------------------------------------------------------
// Sweep counting with the 4×4 shelf markers.
//
// Each marker is a known 1.5-inch square, so wherever one is in view the app can turn any spot
// in the picture into a position on that shelf face, in centimetres. Markers on the same wall
// are linked into one map whenever two are in view together. Every case it reads is pinned to
// a position on its wall; reading it again at the same spot is the same case, so a sweep never
// counts a case twice. Cases that were in view during a sweep but not seen go to "Gone?".
// ---------------------------------------------------------------------------------------------

const val MARKER_CM = 3.81f            // 1.5-inch black square
private const val SAME_CASE_CM = 18f     // same product within this distance = same case
private const val COUNT_AFTER = 3        // clear reads before a case is counted
private const val LINK_AFTER = 3         // co-sightings before a marker joins its wall's map

val WALLS = listOf("Left shelf", "Back wall", "Right shelf", "Front wall")
val ROWS = listOf("Top", "Middle", "Bottom", "Floor")

/** A marker seen in one frame: its id and four corners in screen pixels (upright, on-screen crop). */
class SeenMarker(val id: Int, val corners: FloatArray) {
    val cx get() = (corners[0] + corners[2] + corners[4] + corners[6]) / 4
    val cy get() = (corners[1] + corners[3] + corners[5] + corners[7]) / 4
    /**
     * Screen pixels -> centimetres on this marker's shelf face (origin at its centre, y up).
     * An affine fit: a small square can't pin down perspective well enough to reach 60 cm away,
     * but scale, angle and shear it can (tested: within ~3 cm at 20 cm × 30 cm from the marker).
     */
    val toPlane: DoubleArray by lazy { Homography.affine(uprightCorners(), squareCm()) }

    /** Corners reordered so the marker's "up" points up on screen, however the magnet was stuck on. */
    fun uprightCorners(): FloatArray {
        // ArUco order: top-left, top-right, bottom-right, bottom-left (in the marker's own frame).
        var best = corners; var bestDot = -2f
        for (k in 0 until 4) {
            val c = FloatArray(8) { corners[(it + 2 * k) % 8] }
            // up vector = bottom-left -> top-left
            val ux = c[0] - c[6]; val uy = c[1] - c[7]; val len = hypot(ux, uy).coerceAtLeast(1e-3f)
            val dot = -uy / len   // screen up is -y
            if (dot > bestDot) { bestDot = dot; best = c }
        }
        return best
    }
}

/** The marker's corners (TL, TR, BR, BL) in centimetres, centre origin, y up. */
fun squareCm(): DoubleArray { val h = MARKER_CM / 2.0; return doubleArrayOf(-h, h, h, h, h, -h, -h, -h) }

object Homography {
    /** Least-squares affine map src -> dst (any number of point pairs ≥ 3). */
    fun affine(src: FloatArray, dst: DoubleArray): DoubleArray {
        val n = src.size / 2
        val ata = Array(3) { DoubleArray(3) }; val atx = DoubleArray(3); val aty = DoubleArray(3)
        for (i in 0 until n) {
            val r = doubleArrayOf(src[2 * i].toDouble(), src[2 * i + 1].toDouble(), 1.0)
            for (a in 0 until 3) { for (b in 0 until 3) ata[a][b] += r[a] * r[b]; atx[a] += r[a] * dst[2 * i]; aty[a] += r[a] * dst[2 * i + 1] }
        }
        val p = gauss(ata.map { it.copyOf() }.toTypedArray(), atx) ?: return identity()
        val q = gauss(ata.map { it.copyOf() }.toTypedArray(), aty) ?: return identity()
        return doubleArrayOf(p[0], p[1], p[2], q[0], q[1], q[2], 0.0, 0.0, 1.0)
    }

    /** Least-squares homography from many point pairs (≥ 8 points, e.g. two markers), with normalisation. */
    fun fit(src: FloatArray, dst: DoubleArray): DoubleArray {
        val n = src.size / 2
        var mx = 0.0; var my = 0.0
        for (i in 0 until n) { mx += src[2 * i]; my += src[2 * i + 1] }
        mx /= n; my /= n
        var md = 0.0
        for (i in 0 until n) md += kotlin.math.hypot(src[2 * i] - mx, src[2 * i + 1] - my)
        val sc = if (md == 0.0) 1.0 else kotlin.math.sqrt(2.0) / (md / n)
        val ata = Array(8) { DoubleArray(8) }; val atb = DoubleArray(8)
        for (i in 0 until n) {
            val x = (src[2 * i] - mx) * sc; val y = (src[2 * i + 1] - my) * sc; val u = dst[2 * i]; val v = dst[2 * i + 1]
            val r1 = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y); val r2 = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y)
            for (a in 0 until 8) { for (b in 0 until 8) ata[a][b] += r1[a] * r1[b] + r2[a] * r2[b]; atb[a] += r1[a] * u + r2[a] * v }
        }
        val h = gauss(ata, atb) ?: return affine(src, dst)
        // undo the normalisation: H = Hn * T
        val hn = doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0)
        val t = doubleArrayOf(sc, 0.0, -sc * mx, 0.0, sc, -sc * my, 0.0, 0.0, 1.0)
        val m = mul(hn, t)
        return DoubleArray(9) { m[it] / m[8] }
    }

    fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { k -> val r = k / 3; val c = k % 3; a[3 * r] * b[c] + a[3 * r + 1] * b[3 + c] + a[3 * r + 2] * b[6 + c] }

    /** Shifts a map's output by (ox, oy). */
    fun translate(h: DoubleArray, ox: Float, oy: Float) = mul(doubleArrayOf(1.0, 0.0, ox.toDouble(), 0.0, 1.0, oy.toDouble(), 0.0, 0.0, 1.0), h)

    fun identity() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    private fun gauss(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            val t = m[col]; m[col] = m[piv]; m[piv] = t
            val d = m[col][col]; if (abs(d) < 1e-12) return null
            for (k in col..n) m[col][k] /= d
            for (r in 0 until n) if (r != col) { val f = m[r][col]; if (f != 0.0) for (k in col..n) m[r][k] -= f * m[col][k] }
        }
        return DoubleArray(n) { m[it][n] }
    }

    /** Maps the 4 screen corners (TL, TR, BR, BL) of a square of side s onto centimetres, centre origin, y up. */
    fun fromSquare(c: FloatArray, s: Float): DoubleArray {
        val h = s / 2.0
        val dst = doubleArrayOf(-h, h, h, h, h, -h, -h, -h)
        return solve(DoubleArray(8) { c[it].toDouble() }, dst)
    }

    /** 4-point homography src -> dst (8 unknowns, h33 = 1). */
    fun solve(src: DoubleArray, dst: DoubleArray): DoubleArray {
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = src[2 * i]; val y = src[2 * i + 1]; val u = dst[2 * i]; val v = dst[2 * i + 1]
            a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        // Gaussian elimination with partial pivoting
        for (col in 0 until 8) {
            var piv = col
            for (r in col + 1 until 8) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
            val t = a[col]; a[col] = a[piv]; a[piv] = t
            val d = a[col][col]; if (abs(d) < 1e-12) return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
            for (k in col until 9) a[col][k] /= d
            for (r in 0 until 8) if (r != col) { val f = a[r][col]; if (f != 0.0) for (k in col until 9) a[r][k] -= f * a[col][k] }
        }
        return DoubleArray(9) { if (it < 8) a[it][8] else 1.0 }
    }

    fun apply(h: DoubleArray, x: Float, y: Float): Pair<Float, Float> {
        val w = h[6] * x + h[7] * y + h[8]
        return ((h[0] * x + h[1] * y + h[2]) / w).toFloat() to ((h[3] * x + h[4] * y + h[5]) / w).toFloat()
    }

    fun invert(m: DoubleArray): DoubleArray {
        val a = m[0]; val b = m[1]; val c = m[2]; val d = m[3]; val e = m[4]; val f = m[5]; val g = m[6]; val h = m[7]; val i = m[8]
        val A = e * i - f * h; val B = -(d * i - f * g); val C = d * h - e * g
        val det = a * A + b * B + c * C
        if (abs(det) < 1e-12) return m
        return doubleArrayOf(A / det, -(b * i - c * h) / det, (b * f - c * e) / det,
            B / det, (a * i - c * g) / det, -(a * f - c * d) / det,
            C / det, -(a * h - b * g) / det, (a * e - b * d) / det)
    }
}

/** A marker you've set up: which wall and row it's on, and where it sits on that wall's map. */
class MarkerInfo(val id: Int, var wall: String, var row: String) {
    var ox = 0f; var oy = 0f
    var linkN = 0
    var linked = false
}

/** One counted (or about to be counted) case. Position is in centimetres on its wall's map. */
class CaseRec(val id: String, var productId: String, val wall: String, var row: String, var x: Float, var y: Float) {
    var reads = 0
    var partial = false
    var firstSeen = 0L
    var lastSeen = 0L
    /** During the current sweep. */
    var seenThisSweep = false
    var inViewMs = 0L
    val counted get() = reads >= COUNT_AFTER
}

/** A pinned case placed on screen for this frame. */
data class PinView(val caseId: String, val x: Float, val y: Float, val productId: String, val counted: Boolean, val seenNow: Boolean, val partial: Boolean)

/** A marker placed on screen for this frame. */
data class MarkerView(val id: Int, val corners: FloatArray, val set: Boolean, val linked: Boolean)

class SweepSummary(val added: List<CaseRec>, val gone: List<CaseRec>, val check: List<CaseRec>, val counted: Int)

class SweepStore(context: Context) {
    private val file = File(context.filesDir, "sweep.json")
    val markers = HashMap<Int, MarkerInfo>()
    val cases = mutableListOf<CaseRec>()
    /** Goes up whenever the counted cases change, so the back-stock list can follow live. */
    @Volatile var version = 0L
        private set
    @Volatile var sweeping = false
        private set
    private var sweepStart = 0L
    private var nextCase = 0L
    /** Co-sightings waiting to link a marker: id -> (sum x, sum y, n) relative to a linked marker's map. */
    private val linkSums = HashMap<Int, FloatArray>()

    init { load() }

    // ---------- setup ----------
    @Synchronized
    fun setMarker(id: Int, wall: String, row: String) {
        val m = markers[id]
        if (m != null && m.wall == wall) { m.row = row }
        else {
            markers[id] = MarkerInfo(id, wall, row).also { nm ->
                // The first marker on a wall starts that wall's map.
                if (markers.values.none { it.id != id && it.wall == wall && it.linked }) { nm.linked = true; nm.linkN = LINK_AFTER }
            }
            linkSums.remove(id)
        }
        save()
    }

    @Synchronized
    fun forgetMarker(id: Int) { markers.remove(id); linkSums.remove(id); save() }

    @Synchronized fun setCount() = markers.size
    @Synchronized fun linkedCount() = markers.values.count { it.linked }
    @Synchronized fun countedCount() = cases.count { it.counted }
    @Synchronized fun case(id: String): CaseRec? = cases.firstOrNull { it.id == id }
    @Synchronized fun marker(id: Int): MarkerInfo? = markers[id]

    // ---------- each frame ----------
    /**
     * Called for every analysed frame with the markers and named labels in view.
     * @param labels (track id, product id or null, sure, centre x, centre y) in screen pixels
     */
    @Synchronized
    fun update(seen: List<SeenMarker>, labels: List<LabelSeen>, now: Long, dtMs: Long, screenW: Float, screenH: Float): Pair<List<PinView>, List<MarkerView>> {
        val known = seen.filter { markers[it.id] != null }
        // Screen -> wall map for each wall in view: fitted over all its markers in view when there are
        // two or more (steady across the whole picture), from the one marker otherwise.
        fun wallMap(ms: List<SeenMarker>): DoubleArray? {
            if (ms.isEmpty()) return null
            if (ms.size == 1) { val i = markers.getValue(ms[0].id); return Homography.translate(ms[0].toPlane, i.ox, i.oy) }
            val src = FloatArray(ms.size * 8); val dst = DoubleArray(ms.size * 8); val sq = squareCm()
            ms.forEachIndexed { j, m ->
                val c = m.uprightCorners(); val i = markers.getValue(m.id)
                for (k in 0 until 8) { src[j * 8 + k] = c[k]; dst[j * 8 + k] = sq[k] + if (k % 2 == 0) i.ox else i.oy }
            }
            return Homography.fit(src, dst)
        }
        val linkedByWall = known.filter { markers.getValue(it.id).linked }.groupBy { markers.getValue(it.id).wall }
        val maps = linkedByWall.mapValues { (_, ms) -> wallMap(ms) }.filterValues { it != null }.mapValues { it.value!! }

        // Link markers that are in view with markers already on their wall's map; keep refining the others.
        for (b in known) {
            val mb = markers.getValue(b.id)
            val others = (linkedByWall[mb.wall] ?: emptyList()).filter { it.id != b.id }
            if (others.isEmpty()) continue
            val t = wallMap(others) ?: continue
            val (wx, wy) = Homography.apply(t, b.cx, b.cy)
            if (wx.isNaN() || wy.isNaN()) continue
            if (mb.linked) {
                if (mb.linkN < 50) { val n = mb.linkN.toFloat(); mb.ox = (mb.ox * n + wx) / (n + 1); mb.oy = (mb.oy * n + wy) / (n + 1); mb.linkN++ }
            } else {
                val sum = linkSums.getOrPut(b.id) { FloatArray(3) }
                sum[0] += wx; sum[1] += wy; sum[2] += 1f
                if (sum[2] >= LINK_AFTER) { mb.ox = sum[0] / sum[2]; mb.oy = sum[1] / sum[2]; mb.linkN = sum[2].toInt(); mb.linked = true; linkSums.remove(b.id); save() }
            }
        }

        // Pin labels to wall positions.
        if (sweeping && maps.isNotEmpty()) for (l in labels) {
            val pid = l.productId ?: continue
            if (!l.sure) continue
            val (wall, wx, wy) = placeOnWall(maps, linkedByWall, l.x, l.y) ?: continue
            val row = rowFor(wall, wx, wy) ?: continue
            val near = cases.filter { it.wall == wall && hypot(it.x - wx, it.y - wy) < SAME_CASE_CM }
            val same = near.filter { it.productId == pid }.minByOrNull { hypot(it.x - wx, it.y - wy) }
            if (same != null) {
                // Same case read again: settle its position, count the read once per frame.
                val n = same.reads.coerceAtMost(20).toFloat()
                same.x = (same.x * n + wx) / (n + 1); same.y = (same.y * n + wy) / (n + 1)
                if (same.lastSeen != now) { same.reads++; if (same.reads == COUNT_AFTER) version++ }
                same.lastSeen = now; same.seenThisSweep = true; same.row = row
            } else {
                cases += CaseRec("c${nextCase++}", pid, wall, row, wx, wy).also { it.reads = 1; it.firstSeen = now; it.lastSeen = now; it.seenThisSweep = true }
            }
        }

        // Where every pinned case is on screen right now, and how long it's been in view this sweep.
        val pins = mutableListOf<PinView>()
        val back = maps.mapValues { Homography.invert(it.value) }
        for (c in cases) {
            val inv = back[c.wall] ?: continue
            val (sx, sy) = Homography.apply(inv, c.x, c.y)
            if (sx.isNaN() || sy.isNaN()) continue
            if (sx < -0.1f * screenW || sx > 1.1f * screenW || sy < -0.1f * screenH || sy > 1.1f * screenH) continue
            pins += PinView(c.id, sx, sy, c.productId, c.counted, c.lastSeen == now, c.partial)
            // Only count it as "in view" well inside the picture, so edge cases aren't called gone.
            if (sweeping && sx in 0.08f * screenW..0.92f * screenW && sy in 0.08f * screenH..0.92f * screenH) c.inViewMs += dtMs
        }
        val mv = seen.map { s -> val i = markers[s.id]; MarkerView(s.id, s.corners, i != null, i?.linked == true) }
        return pins to mv
    }

    /**
     * Which wall a label is on, and where: the wall whose markers in view it sits just above
     * (a case sits on the shelf whose lip carries the marker), else the closest.
     */
    private fun placeOnWall(maps: Map<String, DoubleArray>, byWall: Map<String, List<SeenMarker>>, x: Float, y: Float): Triple<String, Float, Float>? {
        var best: Triple<String, Float, Float>? = null; var bestScore = Float.MAX_VALUE
        for ((wall, h) in maps) {
            val (wx, wy) = Homography.apply(h, x, y)
            if (wx.isNaN() || wy.isNaN()) continue
            for (m in byWall[wall].orEmpty()) {
                val i = markers.getValue(m.id)
                val dx = wx - i.ox; val dy = wy - i.oy
                val score = abs(dx) + (if (dy in -5f..60f) 0f else 200f) + abs(dy) * 0.2f
                if (score < bestScore) { bestScore = score; best = Triple(wall, wx, wy) }
            }
        }
        return best
    }

    /** Row of a wall position: the row of the highest marker that sits below it. */
    private fun rowFor(wall: String, x: Float, y: Float): String? {
        val below = markers.values.filter { it.wall == wall && it.linked && it.oy <= y + 5f && it.row != "Floor" }
        return (below.minByOrNull { (y - it.oy) + abs(x - it.ox) * 0.3f } ?: markers.values.filter { it.wall == wall && it.linked }
            .minByOrNull { hypot(it.ox - x, it.oy - y) })?.row
    }

    // ---------- sweeps ----------
    @Synchronized
    fun startSweep(now: Long) {
        sweeping = true; sweepStart = now
        cases.forEach { it.seenThisSweep = false; it.inViewMs = 0 }
    }

    /** What changed: new cases, cases that were in view but not seen, and cases read too few times to count. */
    @Synchronized
    fun summary(): SweepSummary {
        val added = cases.filter { it.counted && it.firstSeen >= sweepStart }
        val gone = cases.filter { it.counted && !it.seenThisSweep && it.inViewMs >= 1500 }
        val check = cases.filter { !it.counted && it.firstSeen >= sweepStart }
        return SweepSummary(added, gone, check, cases.count { it.counted })
    }

    /** Ends the sweep: confirms the gone ones as pulled (or keeps them), keeps or drops the unsure ones. */
    @Synchronized
    fun finish(removeGone: Boolean, keepCheck: Boolean) {
        val s = summary()
        if (removeGone) cases.removeAll(s.gone.toSet())
        if (keepCheck) s.check.forEach { it.reads = COUNT_AFTER } else cases.removeAll(s.check.toSet())
        sweeping = false; version++; save()
    }

    @Synchronized
    fun cancelSweep() { val s = summary(); cases.removeAll(s.check.toSet()); sweeping = false; save() }

    @Synchronized
    fun setPartial(caseId: String, partial: Boolean) { cases.firstOrNull { it.id == caseId }?.partial = partial; version++; save() }

    @Synchronized
    fun relabel(caseId: String, productId: String) { cases.firstOrNull { it.id == caseId }?.let { it.productId = productId; it.reads = COUNT_AFTER }; version++; save() }

    @Synchronized
    fun removeCase(caseId: String) { cases.removeAll { it.id == caseId }; version++; save() }

    @Synchronized
    fun clearCases() { cases.clear(); save() }

    /** The back-stock list: one line per product, with how many cases, how many partial, and where. */
    @Synchronized
    fun stockItems(catalog: Catalog): List<StockItem> =
        cases.filter { it.counted }.groupBy { it.productId }.mapNotNull { (pid, cs) ->
            val p = catalog[pid] ?: return@mapNotNull null
            val where = cs.groupBy { it.wall to it.row }.maxByOrNull { it.value.size }!!.key
            StockItem(pid, p.name, p.section, where.first, where.second, cs.map { it.x.toDouble() }.average(), cs.size, cs.count { it.partial },
                usual = if (p.shelf.isNotBlank() && p.shelf != where.first) p.shelf else "")
        }

    // ---------- saving ----------
    private fun load() {
        runCatching {
            if (!file.exists()) return
            val o = JSONObject(file.readText())
            nextCase = o.optLong("next", 0)
            o.optJSONArray("markers")?.let { a ->
                for (i in 0 until a.length()) {
                    val m = a.getJSONObject(i)
                    markers[m.getInt("id")] = MarkerInfo(m.getInt("id"), m.getString("wall"), m.optString("row")).also {
                        it.ox = m.optDouble("ox", 0.0).toFloat(); it.oy = m.optDouble("oy", 0.0).toFloat()
                        it.linkN = m.optInt("n", 0); it.linked = m.optBoolean("linked", false)
                    }
                }
            }
            o.optJSONArray("cases")?.let { a ->
                for (i in 0 until a.length()) {
                    val c = a.getJSONObject(i)
                    cases += CaseRec(c.getString("id"), c.getString("p"), c.getString("wall"), c.optString("row"),
                        c.getDouble("x").toFloat(), c.getDouble("y").toFloat()).also {
                        it.reads = c.optInt("reads", COUNT_AFTER); it.partial = c.optBoolean("partial"); it.firstSeen = c.optLong("first"); it.lastSeen = c.optLong("last")
                    }
                }
            }
        }
    }

    @Synchronized
    fun save() {
        runCatching {
            val ma = JSONArray()
            markers.values.forEach { ma.put(JSONObject().put("id", it.id).put("wall", it.wall).put("row", it.row).put("ox", it.ox.toDouble()).put("oy", it.oy.toDouble()).put("n", it.linkN).put("linked", it.linked)) }
            val ca = JSONArray()
            cases.filter { it.counted || sweeping }.forEach {
                ca.put(JSONObject().put("id", it.id).put("p", it.productId).put("wall", it.wall).put("row", it.row).put("x", it.x.toDouble()).put("y", it.y.toDouble())
                    .put("reads", it.reads).put("partial", it.partial).put("first", it.firstSeen).put("last", it.lastSeen))
            }
            file.writeText(JSONObject().put("next", nextCase).put("markers", ma).put("cases", ca).toString())
        }
    }
}

/** A named label in one frame, for pinning. Screen pixels. */
data class LabelSeen(val trackId: Int, val productId: String?, val sure: Boolean, val x: Float, val y: Float)
