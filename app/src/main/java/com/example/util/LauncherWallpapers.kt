package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max

/**
 * Storage for the launcher's slideshow backgrounds.
 *
 * Picked images are copied into a private app directory, downscaled to a sane
 * long edge and re-encoded so many large photos cannot exhaust memory or fill
 * the data partition. The list is capped; callers keep the ordered file paths
 * in launcher prefs.
 */
object LauncherWallpapers {
    const val MAX_IMAGES = 20
    private const val MAX_EDGE = 1920
    private const val JPEG_QUALITY = 88
    private const val PREFIX = "bg_"

    fun dir(context: Context): File =
        File(context.filesDir, "launcher_slideshow").apply { if (!exists()) mkdirs() }

    fun list(context: Context): List<File> =
        dir(context).listFiles()
            ?.filter { it.isFile && it.name.startsWith(PREFIX) }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun totalSize(context: Context): Long = list(context).sumOf { it.length() }

    /** Copies [uris] into the slideshow dir, returning the merged path list. */
    fun import(context: Context, uris: List<Uri>, existing: List<String>): List<String> {
        val merged = existing.toMutableList()
        val room = (MAX_IMAGES - merged.size).coerceAtLeast(0)
        if (room == 0) return merged
        for (uri in uris.take(room)) {
            val path = runCatching { copyOne(context, uri) }.getOrNull() ?: continue
            merged.add(path)
        }
        return merged
    }

    /** Deletes slideshow files that are no longer referenced by [keep]. */
    fun prune(context: Context, keep: List<String>) {
        val keepSet = keep.toSet()
        list(context).forEach { file ->
            if (file.absolutePath !in keepSet) runCatching { file.delete() }
        }
    }

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { runCatching { it.delete() } }
    }

    private fun copyOne(context: Context, uri: Uri): String? {
        val decoded = decode(context, uri) ?: return null
        val oriented = orient(context, uri, decoded)
        val destination = File(
            dir(context),
            PREFIX + System.currentTimeMillis() + "_" + (1000..9999).random() + ".jpg"
        )
        val ok = runCatching {
            destination.outputStream().use { out ->
                oriented.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        }.getOrDefault(false)
        if (oriented !== decoded) runCatching { decoded.recycle() }
        runCatching { oriented.recycle() }
        if (!ok) {
            runCatching { destination.delete() }
            return null
        }
        return destination.absolutePath
    }

    private fun decode(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val longEdge = max(bitmap.width, bitmap.height)
        if (longEdge <= MAX_EDGE) return bitmap
        val scale = MAX_EDGE.toFloat() / longEdge
        return runCatching {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            ).also { if (it !== bitmap) bitmap.recycle() }
        }.getOrDefault(bitmap)
    }

    private fun orient(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
        }
        if (matrix.isIdentity) return bitmap
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }
}
