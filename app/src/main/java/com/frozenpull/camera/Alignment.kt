package com.frozenpull.camera

import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A small grayscale picture of a camera frame, already turned upright.
 * Everything about "is the phone lined up" is decided by comparing these.
 */
class Thumb(val pixels: FloatArray) {
    companion object {
        const val W = 96
        const val H = 54

        /** Builds an upright thumbnail from the luma plane of a CameraX analysis frame. */
        fun from(image: ImageProxy): Thumb {
            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixStride = plane.pixelStride
            val rot = image.imageInfo.rotationDegrees
            val srcW = image.width
            val srcH = image.height
            val out = FloatArray(W * H)
            for (v in 0 until H) for (u in 0 until W) {
                // (fu, fv) in 0..1 of the upright picture -> pixel in the sensor buffer
                val fu = (u + 0.5f) / W
                val fv = (v + 0.5f) / H
                val (fx, fy) = when (rot) {
                    90 -> fv to (1f - fu)
                    180 -> (1f - fu) to (1f - fv)
                    270 -> (1f - fv) to fu
                    else -> fu to fv
                }
                val x = (fx * (srcW - 1)).toInt()
                val y = (fy * (srcH - 1)).toInt()
                out[v * W + u] = (buf.get(y * rowStride + x * pixStride).toInt() and 0xFF).toFloat()
            }
            return Thumb(out)
        }

        fun fromBytes(b: ByteArray): Thumb? =
            if (b.size != W * H) null else Thumb(FloatArray(W * H) { (b[it].toInt() and 0xFF).toFloat() })
    }

    fun toBytes(): ByteArray = ByteArray(W * H) { pixels[it].roundToInt().coerceIn(0, 255).toByte() }

    /** Bilinear sample; NaN outside the picture. */
    fun at(x: Float, y: Float): Float {
        if (x < 0 || y < 0 || x > W - 1 || y > H - 1) return Float.NaN
        val x0 = x.toInt().coerceAtMost(W - 2); val y0 = y.toInt().coerceAtMost(H - 2)
        val ax = x - x0; val ay = y - y0
        val p = pixels
        val top = p[y0 * W + x0] * (1 - ax) + p[y0 * W + x0 + 1] * ax
        val bot = p[(y0 + 1) * W + x0] * (1 - ax) + p[(y0 + 1) * W + x0 + 1] * ax
        return top * (1 - ay) + bot * ay
    }
}

/** What the person should do to line up the shot. */
enum class Hint(val text: String) {
    NONE(""),
    MOVE_LEFT("Step left"),
    MOVE_RIGHT("Step right"),
    AIM_UP("Aim a little higher"),
    AIM_DOWN("Aim a little lower"),
    CLOSER("Step closer"),
    BACK("Step back"),
}

data class Match(val score: Float, val hint: Hint)

/**
 * Compares the live frame with a target and says how well they line up.
 *
 * Two targets are used:
 *  - REFERENCE: the photo taken at this exact spot last time. Best match = the same view.
 *  - OVERLAP: the previous shot in this row. The left third of the live frame should
 *    show what the right third of the previous shot showed (about one third overlap).
 */
object Aligner {
    enum class Mode { REFERENCE, OVERLAP }

    private val scales = floatArrayOf(0.85f, 1f, 1.15f)

    fun match(live: Thumb, target: Thumb, mode: Mode): Match {
        val W = Thumb.W; val H = Thumb.H
        // In OVERLAP mode only the left third of the live frame is compared,
        // against the right third of the target.
        val uMax = if (mode == Mode.OVERLAP) W / 3 else W
        val baseDx = if (mode == Mode.OVERLAP) W * 2f / 3f else 0f
        var best = -2f; var bestDx = 0; var bestDy = 0; var bestS = 1f
        for (s in scales) for (dy in -8..8 step 2) for (dx in -16..16 step 2) {
            val r = ncc(live, target, uMax, baseDx, dx.toFloat(), dy.toFloat(), s, W, H)
            if (r > best) { best = r; bestDx = dx; bestDy = dy; bestS = s }
        }
        // Positive dx: the target view sits to the right of where the camera points -> step right.
        val hint = when {
            best < 0.35f -> Hint.NONE // not even close; the person is probably at the wrong spot
            bestS < 1f && abs(bestDx) <= 4 -> Hint.CLOSER
            bestS > 1f && abs(bestDx) <= 4 -> Hint.BACK
            bestDx >= 6 -> Hint.MOVE_RIGHT
            bestDx <= -6 -> Hint.MOVE_LEFT
            bestDy >= 6 -> Hint.AIM_DOWN
            bestDy <= -6 -> Hint.AIM_UP
            else -> Hint.NONE
        }
        return Match(best, hint)
    }

    /** How far the view moved between two frames taken a moment apart, in thumbnail pixels. */
    data class Shift(val dx: Float, val dy: Float, val score: Float)

    fun shift(prev: Thumb, cur: Thumb): Shift {
        val W = Thumb.W; val H = Thumb.H
        var best = -2f; var bx = 0; var by = 0
        for (dy in -6..6 step 2) for (dx in -20..20 step 2) {
            val r = ncc(cur, prev, W, 0f, dx.toFloat(), dy.toFloat(), 1f, W, H)
            if (r > best) { best = r; bx = dx; by = dy }
        }
        // refine to the nearest pixel
        var fx = bx
        for (dx in (bx - 1)..(bx + 1)) {
            val r = ncc(cur, prev, W, 0f, dx.toFloat(), by.toFloat(), 1f, W, H)
            if (r > best) { best = r; fx = dx }
        }
        return Shift(fx.toFloat(), by.toFloat(), best)
    }

    /**
     * Normalized cross-correlation between live(u, v) and target at the matching point:
     * target(baseDx + cx + (u - cx) * s + dx, cy + (v - cy) * s + dy).
     */
    private fun ncc(
        live: Thumb, target: Thumb, uMax: Int, baseDx: Float,
        dx: Float, dy: Float, s: Float, W: Int, H: Int,
    ): Float {
        val cx = uMax / 2f; val cy = H / 2f
        var n = 0; var sa = 0.0; var sb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0
        var v = 1
        while (v < H - 1) {
            var u = 1
            while (u < uMax - 1) {
                val b = target.at(baseDx + cx + (u - cx) * s + dx, cy + (v - cy) * s + dy)
                if (!b.isNaN()) {
                    val a = live.pixels[v * W + u].toDouble()
                    n++; sa += a; sb += b; saa += a * a; sbb += b * b; sab += a * b
                }
                u += 2
            }
            v += 2
        }
        if (n < 60) return -1f
        val cov = sab - sa * sb / n
        val va = saa - sa * sa / n; val vb = sbb - sb * sb / n
        if (va <= 1e-6 || vb <= 1e-6) return 0f
        return (cov / sqrt(va * vb)).toFloat()
    }
}
