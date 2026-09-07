package com.example.sdnpu.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * Utility for exporting generated images to the device's public MediaStore gallery
 * and sharing them via Android system share sheet with FileProvider.
 */
object MediaExporter {

    private const val TAG = "MediaExporter"
    const val GALLERY_SUBDIRECTORY = "Pictures/SD_NPU"
    const val FILE_PROVIDER_SUFFIX = ".fileprovider"
    const val DEFAULT_MIME_TYPE = "image/png"

    /**
     * Optional hook for injecting URI resolution in tests.
     */
    internal var uriResolver: ((Context, String, File) -> Uri)? = null

    /**
     * Sanitizes a title/name to make it safe for filesystem and MediaStore usage.
     * Replaces reserved filesystem characters (\, /, :, *, ?, ", <, >, |, newlines) with underscore,
     * strips leading/trailing whitespace, strips redundant .png extension, and caps length at 120 chars.
     */
    fun sanitizeFilename(name: String): String {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").trim()
        val withoutExt = if (clean.endsWith(".png", ignoreCase = true)) {
            clean.substring(0, clean.length - 4)
        } else {
            clean
        }
        return withoutExt.take(120).ifBlank { "sd_image_${System.currentTimeMillis()}" }
    }

    /**
     * Generates a suitable filename for public export, always ending in .png.
     */
    fun getExportFilename(sourceFile: File, title: String? = null): String {
        val base = if (!title.isNullOrBlank()) {
            sanitizeFilename(title)
        } else {
            sanitizeFilename(sourceFile.nameWithoutExtension)
        }
        return "$base.png"
    }

    /**
     * Determines MIME type based on file extension, defaulting to image/png.
     */
    fun getMimeType(file: File): String {
        return when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> DEFAULT_MIME_TYPE
        }
    }

    /**
     * Saves an image to the public MediaStore gallery under Pictures/SD_NPU.
     * Returns Result.success(uri) on success, or Result.failure(exception) on error.
     */
    fun saveImageToPublicGallery(
        context: Context,
        sourceFile: File,
        title: String? = null
    ): Result<Uri> {
        if (!sourceFile.exists()) {
            return Result.failure(FileNotFoundException("Source file does not exist: ${sourceFile.absolutePath}"))
        }

        return try {
            val fileName = getExportFilename(sourceFile, title)
            val mimeType = getMimeType(sourceFile)
            val resolver = context.contentResolver

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, GALLERY_SUBDIRECTORY)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: return Result.failure(IllegalStateException("Failed to insert MediaStore entry for $fileName"))

            try {
                resolver.openOutputStream(uri)?.use { outStream ->
                    sourceFile.inputStream().use { inStream ->
                        inStream.copyTo(outStream)
                    }
                } ?: throw IllegalStateException("Failed to open output stream for $uri")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
                Log.i(TAG, "Successfully exported image to gallery: $uri ($fileName)")
                Result.success(uri)
            } catch (e: Exception) {
                try {
                    resolver.delete(uri, null, null)
                } catch (_: Exception) {}
                Result.failure(e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export image to public gallery", e)
            Result.failure(e)
        }
    }

    /**
     * Resolves the content URI for a file using FileProvider.
     */
    fun getShareUri(context: Context, sourceFile: File): Uri? {
        uriResolver?.let { return it(context, "${context.packageName}$FILE_PROVIDER_SUFFIX", sourceFile) }
        return try {
            FileProvider.getUriForFile(context, "${context.packageName}$FILE_PROVIDER_SUFFIX", sourceFile)
        } catch (e: Exception) {
            Log.w(TAG, "FileProvider URI resolution failed, falling back to file URI: ${e.message}")
            try {
                Uri.fromFile(sourceFile)
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Creates an ACTION_SEND Intent for sharing the image file.
     */
    fun createShareIntent(context: Context, sourceFile: File, prompt: String? = null): Intent {
        val uri = getShareUri(context, sourceFile)
        return Intent(Intent.ACTION_SEND).apply {
            type = getMimeType(sourceFile)
            if (uri != null) {
                putExtra(Intent.EXTRA_STREAM, uri)
            }
            if (!prompt.isNullOrBlank()) {
                putExtra(Intent.EXTRA_TEXT, prompt)
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Launches the system share chooser for the given image file.
     */
    fun shareImage(context: Context, sourceFile: File, prompt: String? = null) {
        val shareIntent = createShareIntent(context, sourceFile, prompt)
        val chooserIntent = Intent.createChooser(shareIntent, "Share Image").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooserIntent)
    }
}
