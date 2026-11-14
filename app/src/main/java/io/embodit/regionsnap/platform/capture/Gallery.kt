package io.embodit.regionsnap.platform.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import io.embodit.regionsnap.domain.Px
import io.embodit.regionsnap.domain.Rectify
import io.embodit.regionsnap.platform.RsLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pictures (design capture.pipeline 拉平 / 存, dataModel.files): the straightened crop, the JPEG in the album
 * (MediaStore, Pictures/RegionSnap, IS_PENDING flow) and the app's private copy files/shots/<id>.jpg; the preview frame is
 * kept in cache/preview.jpg so an interruption can come back to it (R1-7).
 */
class Gallery(private val context: Context) {
    private val shotsDir: File
        get() = File(context.filesDir, "shots").also { it.mkdirs() }

    fun privateFile(id: String): File = File(shotsDir, "$id.jpg")

    /**
     * The result picture: [rectified] = the quad [q] mapped onto a [outW] x [outH] rectangle (Matrix.setPolyToPoly, F12,
     * bilinear via the filter paint); otherwise the quad's bounding box cropped straight.
     */
    fun render(src: Bitmap, q: List<Px>, aspect: Float, rectified: Boolean): Bitmap {
        if (!rectified) {
            val r = Rectify.boundingCrop(q, src.width, src.height)
            return Bitmap.createBitmap(src, r[0], r[1], (r[2] - r[0]).coerceAtLeast(1), (r[3] - r[1]).coerceAtLeast(1))
        }
        val (w, h) = Rectify.outputSize(q, aspect)
        val dst = Rectify.rect(w, h)
        val m = Matrix()
        val s = FloatArray(8) { if (it % 2 == 0) q[it / 2].x else q[it / 2].y }
        val d = FloatArray(8) { if (it % 2 == 0) dst[it / 2].x else dst[it / 2].y }
        if (!m.setPolyToPoly(s, 0, d, 0, 4)) RsLog.w("RECT setPolyToPoly refused")
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Saves [bmp]: private copy first, then the album. Returns the album uri (null when the album write failed). */
    fun save(id: String, bmp: Bitmap, description: String): Uri? {
        privateFile(id).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        runCatching { ExifInterface(privateFile(id).absolutePath).apply { setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, description); saveAttributes() } }
        val name = "RegionSnap_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE)
            put(MediaStore.Images.Media.DESCRIPTION, description)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val cr = context.contentResolver
        val uri = runCatching { cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.onFailure { RsLog.w("SAVE insert failed: $it") }.getOrNull() ?: return null
        return runCatching {
            cr.openOutputStream(uri)?.use { out -> privateFile(id).inputStream().use { it.copyTo(out) } }
            cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            RsLog.i("SAVE album $name -> $uri")
            uri
        }.onFailure {
            RsLog.w("SAVE album write failed: $it")
            runCatching { cr.delete(uri, null, null) }
        }.getOrNull()
    }

    fun load(id: String, maxSide: Int = 2048): Bitmap? {
        val f = privateFile(id)
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > maxSide) sample *= 2
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun deletePrivate(id: String) {
        privateFile(id).delete()
        RsLog.i("SAVE private deleted $id")
    }

    fun deleteMedia(uri: String) {
        val n = runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }.onFailure { RsLog.w("SAVE album delete failed: $it") }.getOrDefault(0)
        RsLog.i("SAVE album deleted $uri rows=$n")
    }

    /** Which of these album uris are gone (deleted from the album outside the app). */
    fun missing(uris: Map<String, String>): Set<String> =
        uris.filter { (_, u) ->
            runCatching { context.contentResolver.query(Uri.parse(u), arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { it.count == 0 } ?: true }.getOrDefault(false)
        }.keys

    /** Removes our own rows left at IS_PENDING = 1 by a crash mid-save (R1-7). */
    fun clearPending() {
        runCatching {
            val n = context.contentResolver.delete(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.Images.Media.IS_PENDING} = 1 AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("$RELATIVE%"),
            )
            if (n > 0) RsLog.i("SAVE cleared $n pending rows")
        }
    }

    // preview cache (S23 interruption)
    private val previewFile: File
        get() = File(context.cacheDir, "preview.jpg")

    fun keepPreview(src: Bitmap) {
        runCatching { previewFile.outputStream().use { src.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
    }

    fun loadPreview(): Bitmap? = if (previewFile.exists()) BitmapFactory.decodeFile(previewFile.absolutePath) else null

    fun dropPreview() {
        previewFile.delete()
    }

    companion object {
        const val RELATIVE = "Pictures/RegionSnap/"
    }
}
