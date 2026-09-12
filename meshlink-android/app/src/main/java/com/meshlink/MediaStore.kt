package com.meshlink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * On-disk storage for attachments, plus the downscaling that makes sending one
 * over BLE realistic at all.
 *
 * Files live in app-private storage, so other apps cannot read what arrives.
 */
object MediaStore {
    private const val TAG = "MediaStore"

    /** Longest edge after downscaling. */
    private const val MAX_IMAGE_EDGE = 1024
    private const val JPEG_QUALITY = 70

    /**
     * Refuse anything this large even after downscaling.
     *
     * At the ~5-15 KB/s a real BLE link sustains, a megabyte is minutes of
     * transfer that any disconnection restarts. Better to refuse clearly than to
     * begin something that will not finish.
     */
    const val MAX_MEDIA_BYTES = 512 * 1024

    private fun dir(context: Context): File =
        File(context.filesDir, "media").apply { mkdirs() }

    fun fileFor(context: Context, mediaId: String): File = File(dir(context), mediaId)

    /**
     * Copies an image in, downscaled and recompressed.
     *
     * A modern phone photo is 3-12 MB, which BLE cannot carry in any useful
     * time. Reducing it to a long edge of [MAX_IMAGE_EDGE] typically yields
     * 80-200 KB with no visible loss on a phone screen, and is the single
     * largest factor in whether a transfer completes.
     */
    fun importImage(context: Context, source: Uri, mediaId: String): File? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Decode at a reduced sample size first so a large photo never has to be
        // fully materialised in memory.
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = generateSequence(1) { it * 2 }
                .first { longest / it <= MAX_IMAGE_EDGE * 2 }
        }
        val decoded = context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        // Phone cameras record portrait shots as landscape pixels plus an EXIF
        // rotation flag. Decoding ignores that flag, so without this every photo
        // taken upright arrives on its side.
        val upright = applyExifRotation(context, source, decoded)
        val scaled = scaleToFit(upright)
        val target = fileFor(context, mediaId)
        target.outputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        if (scaled !== upright) scaled.recycle()
        if (upright !== decoded) upright.recycle()
        decoded.recycle()

        Log.i(TAG, "Imported image as ${target.length()}B (was ${bounds.outWidth}x${bounds.outHeight})")
        target
    }.getOrElse {
        Log.e(TAG, "Failed to import image: ${it.message}")
        null
    }

    /** Rotates a decoded bitmap to match the orientation recorded in its EXIF. */
    private fun applyExifRotation(context: Context, source: Uri, bitmap: Bitmap): Bitmap {
        val degrees = runCatching {
            context.contentResolver.openInputStream(source)?.use { stream ->
                when (
                    android.media.ExifInterface(stream).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL
                    )
                ) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)

        if (degrees == 0f) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    private fun scaleToFit(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= MAX_IMAGE_EDGE) return source
        val ratio = MAX_IMAGE_EDGE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    /** Content hash, so the recipient can prove it reassembled the same file. */
    fun sha256(file: File): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    fun readRange(file: File, offset: Long, length: Int): ByteArray? = runCatching {
        file.inputStream().use { input ->
            input.skip(offset)
            val buffer = ByteArray(length)
            val read = input.read(buffer)
            if (read <= 0) ByteArray(0) else buffer.copyOf(read)
        }
    }.getOrNull()

    fun appendRange(file: File, data: ByteArray) {
        file.appendBytes(data)
    }

    fun delete(context: Context, mediaId: String) {
        runCatching { fileFor(context, mediaId).delete() }
    }
}
