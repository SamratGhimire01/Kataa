package app.kataa.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Photos of where things are ("snap the spot"). Kept in the app's private storage, named after the
 * memory, never in the gallery and never sent anywhere.
 */
object Spots {
    private fun dir(context: Context) = File(context.filesDir, "spots").apply { mkdirs() }

    fun file(context: Context, memoryUuid: String) = File(dir(context), "$memoryUuid.jpg")

    fun has(context: Context, memoryUuid: String) = file(context, memoryUuid).let { it.exists() && it.length() > 0 }

    /** Where the camera app writes the photo. */
    fun captureUri(context: Context, memoryUuid: String): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".spots", file(context, memoryUuid))

    /** Decodes at roughly [targetPx] wide (not full size, to stay light on memory) and fixes rotation. */
    fun load(context: Context, memoryUuid: String, targetPx: Int): Bitmap? = runCatching {
        val f = file(context, memoryUuid)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rot = when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rot == 0f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
    }.getOrNull()
}
