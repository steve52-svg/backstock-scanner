package com.frozenpull.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.camera.core.ImageCapture
import java.io.File
import java.time.LocalDate

/**
 * Where photos and per-spot references live.
 *
 *  - Photos go to the phone gallery under Pictures/WalkIn/<date>/, named
 *    "<section>-<step>.jpg" (for example lt-03.jpg), so they can be picked
 *    section by section in the page's "Update walk-in" screen.
 *  - References stay inside the app: for every spot, the last shot's small
 *    grayscale thumbnail (for line-up matching) and a faded preview image
 *    (shown as a ghost over the camera).
 */
class PhotoStore(private val context: Context) {
    private val refDir = File(context.filesDir, "refs").apply { mkdirs() }

    fun outputOptions(spot: Spot): ImageCapture.OutputFileOptions {
        val name = "${spot.section.id}-${"%02d".format(spot.step + 1)}"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WalkIn/${LocalDate.now()}")
            }
        }
        return ImageCapture.OutputFileOptions.Builder(
            context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
        ).build()
    }

    fun saveReference(spot: Spot, thumb: Thumb, ghost: Bitmap?) {
        File(refDir, "${spot.refKey}.luma").writeBytes(thumb.toBytes())
        ghost?.let { bmp ->
            val scaled = Bitmap.createScaledBitmap(bmp, 640, (640f * bmp.height / bmp.width).toInt(), true)
            File(refDir, "${spot.refKey}.jpg").outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 70, it) }
        }
    }

    /** One saved route photo. */
    data class Shot(val uri: Uri, val name: String, val date: String) {
        /** The route spot this photo belongs to, from its file name (e.g. lt-03.jpg). */
        val spot: Spot? get() {
            val m = Regex("^([a-z]{2})-(\\d+)").find(name) ?: return null
            val section = Route.all.find { it.id == m.groupValues[1] } ?: return null
            return Spot(section, (m.groupValues[2].toIntOrNull() ?: return null) - 1)
        }
    }

    private fun folderFor(date: String) = "Pictures/WalkIn/$date/"

    /** Dates (yyyy-mm-dd) that have route photos, newest first. */
    fun dates(): List<String> {
        val out = sortedSetOf<String>(compareByDescending { it })
        query(null)?.use { c -> while (c.moveToNext()) folderDate(c.getString(1))?.let { out.add(it) } }
        return out.toList()
    }

    /** Photos taken on a date, in route order. */
    fun shots(date: String): List<Shot> {
        val out = mutableListOf<Shot>()
        query(date)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                out.add(Shot(uri, c.getString(2) ?: "", date))
            }
        }
        val order = Route.all.map { it.id }
        return out.sortedWith(compareBy({ order.indexOf(it.spot?.section?.id) }, { it.spot?.step ?: 99 }, { it.name }))
    }

    /** Deletes today's photo for a spot, so a retake replaces it instead of adding a copy. */
    fun deleteForSpot(spot: Spot) {
        val base = "${spot.section.id}-${"%02d".format(spot.step + 1)}"
        shots(LocalDate.now().toString()).filter { it.name.startsWith(base) }.forEach { delete(it.uri) }
    }

    /** Deletes today's photos of one row, so a new sweep of that row doesn't leave old extra shots behind. */
    fun deleteSection(section: Section): Int =
        shots(LocalDate.now().toString()).filter { it.spot?.section?.id == section.id }.count { delete(it.uri) }

    fun delete(uri: Uri): Boolean = try { context.contentResolver.delete(uri, null, null) > 0 } catch (e: Exception) { false }

    fun deleteDay(date: String): Int = shots(date).count { delete(it.uri) }

    /** Forgets every spot's line-up reference; the next route starts fresh. */
    fun clearReferences() { refDir.listFiles()?.forEach { it.delete() } }

    fun thumbnail(uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(uri, Size(480, 270), null)
        else context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 8 })
        }
    } catch (e: Exception) { null }

    fun full(uri: Uri): Bitmap? = try {
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
        }
    } catch (e: Exception) { null }

    // Columns: 0 = _ID, 1 = folder, 2 = DISPLAY_NAME
    @Suppress("DEPRECATION")
    private fun query(date: String?): android.database.Cursor? {
        val folderCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
        val like = if (Build.VERSION.SDK_INT >= 29) (if (date == null) "Pictures/WalkIn/%" else folderFor(date))
                   else (if (date == null) "%/Pictures/WalkIn/%" else "%/${folderFor(date)}%")
        val op = if (Build.VERSION.SDK_INT >= 29 && date != null) "=" else "LIKE"
        return context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, folderCol, MediaStore.Images.Media.DISPLAY_NAME),
            "$folderCol $op ?", arrayOf(like), null,
        )
    }

    private fun folderDate(folder: String?): String? =
        folder?.let { Regex("WalkIn/(\\d{4}-\\d{2}-\\d{2})").find(it)?.groupValues?.get(1) }

    fun referenceThumb(spot: Spot): Thumb? =
        File(refDir, "${spot.refKey}.luma").takeIf { it.exists() }?.let { Thumb.fromBytes(it.readBytes()) }

    fun referenceGhost(spot: Spot): Bitmap? =
        File(refDir, "${spot.refKey}.jpg").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
}
