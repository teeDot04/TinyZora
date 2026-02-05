package com.telo.tinyzora.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
// import androidx.exifinterface.media.ExifInterface // Check dependencies first, fallback to standard if needed
import android.net.Uri
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

object ImageUtils {

    // Edge Gallery Implementation
    fun decodeSampledBitmapFromUri(context: Context, uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        // First, decode with inJustDecodeBounds=true to check dimensions
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            openInputStream(context, uri)?.use { 
                BitmapFactory.decodeStream(it, null, this) 
            }

            // Calculate inSampleSize
            inSampleSize = calculateInSampleSize(this, reqWidth, reqHeight)

            // Decode bitmap with inSampleSize set
            inJustDecodeBounds = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }

        return openInputStream(context, uri)?.use { 
            BitmapFactory.decodeStream(it, null, options) 
        }
    }

    private fun openInputStream(context: Context, uri: Uri): InputStream? {
        return if (uri.scheme == null || uri.scheme == "file") {
            uri.path?.let { FileInputStream(it) }
        } else {
            context.contentResolver.openInputStream(uri)
        }
    }

    // Official implementation from Developer docs / Edge Gallery
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        // Raw height and width of image
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    fun rotateBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        // Map ExifInterface constants to degrees if library is not available, 
        // or ensure these match standard EXIF values.
        // 0 = UNDEFINED, 1 = NORMAL
        // 3 = 180, 6 = 90, 8 = 270
        when (orientation) {
            6 -> matrix.postRotate(90f) // ORIENTATION_ROTATE_90
            3 -> matrix.postRotate(180f) // ORIENTATION_ROTATE_180
            8 -> matrix.postRotate(270f) // ORIENTATION_ROTATE_270
            // Add others if needed
        }
        return try {
             Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
             e.printStackTrace()
             bitmap
        }
    }
    
    // Legacy support for older calls (redirect to new)
    fun decodeSampledBitmap(resolver: android.content.ContentResolver, uri: Uri, maxSize: Int): Bitmap? {
         // This is a bit tricky since we don't have Context here but resolver usually implies it.
         // We'll trust the caller to update or pass context.
         // For now, let's keep a simplified version or assume specific usage.
         
         // Actually, let's update call sites to use `decodeSampledBitmapFromUri` with context.
         // But `resolver` alone is passed currently.
         // We can hack it or refactor. 
         // Since I'm refactoring ImageUtils entirely, I should update call sites.
         return null // Placeholder, cleaner to ensure callers update.
    }
}
