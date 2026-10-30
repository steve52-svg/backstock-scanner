package com.frozenpull.camera

import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import kotlin.math.atan

/** The three back cameras most phones have. */
enum class Lens(val label: String) { WIDE("Wide"), NORMAL("Normal"), ZOOM("Zoom") }

/** How to get one lens on this phone: which camera to open and what zoom to set. */
data class LensPlan(val selector: CameraSelector, val zoom: Float, val shown: String)

/**
 * Works out how this phone offers its wide, normal and zoom cameras.
 * Most phones switch lenses through the zoom setting of the main camera
 * (0.5× = wide, 2-3× = zoom); some list the extra cameras separately.
 * Both ways are handled, and a lens the phone doesn't have is simply left out.
 */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
object Lenses {
    private var cache: Map<Lens, LensPlan>? = null

    fun plans(provider: ProcessCameraProvider): Map<Lens, LensPlan> {
        cache?.let { return it }
        val all = provider.availableCameraInfos
        val backs = all.filter { info -> runCatching { info.lensFacing }.getOrNull() == CameraSelector.LENS_FACING_BACK }
        val main = runCatching { CameraSelector.DEFAULT_BACK_CAMERA.filter(all).firstOrNull() }.getOrNull() ?: backs.firstOrNull()
        val out = linkedMapOf<Lens, LensPlan>()
        if (main == null) return out

        val (minZ, maxZ) = zoomRange(main)
        val mainFov = fov(main)
        // Separate cameras, compared by how much they see.
        val others = backs.filter { it != main }.mapNotNull { i -> fov(i)?.let { i to it } }

        // Wide
        if (minZ < 0.95f) out[Lens.WIDE] = LensPlan(CameraSelector.DEFAULT_BACK_CAMERA, minZ, fmt(minZ))
        else if (mainFov != null) {
            others.filter { it.second > mainFov * 1.2f }.maxByOrNull { it.second }?.let { (info, f) ->
                out[Lens.WIDE] = LensPlan(selectorFor(info), 1f, fmt(mainFov / f))
            }
        }
        // Normal
        out[Lens.NORMAL] = LensPlan(CameraSelector.DEFAULT_BACK_CAMERA, 1f, "1×")
        // Zoom: a separate zoom camera if the phone lists one, otherwise 2× on the main camera
        // (on phones with a zoom lens, the main camera switches to it by itself).
        val tele = if (mainFov != null) others.filter { it.second < mainFov / 1.6f }.minByOrNull { it.second } else null
        if (tele != null && mainFov != null) out[Lens.ZOOM] = LensPlan(selectorFor(tele.first), 1f, fmt(mainFov / tele.second))
        else if (maxZ >= 2f) {
            val z = if (maxZ >= 3f && minZ < 0.95f) 3f else 2f
            out[Lens.ZOOM] = LensPlan(CameraSelector.DEFAULT_BACK_CAMERA, z, fmt(z))
        }
        cache = out
        return out
    }

    private fun selectorFor(info: CameraInfo): CameraSelector =
        CameraSelector.Builder().addCameraFilter { list -> list.filter { it == info } }.build()

    private fun zoomRange(info: CameraInfo): Pair<Float, Float> {
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) }
                .getOrNull()?.let { return it.lower to it.upper }
        }
        val z = info.zoomState.value
        return (z?.minZoomRatio ?: 1f) to (z?.maxZoomRatio ?: 1f)
    }

    /** Horizontal field of view in radians, from the lens and sensor size. */
    private fun fov(info: CameraInfo): Float? = runCatching {
        val c = Camera2CameraInfo.from(info)
        val f = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return null
        val size = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        (2 * atan(size.width / (2 * f)))
    }.getOrNull()

    private fun fmt(z: Float): String {
        val r = Math.round(z * 10) / 10f
        return (if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()) + "×"
    }
}
