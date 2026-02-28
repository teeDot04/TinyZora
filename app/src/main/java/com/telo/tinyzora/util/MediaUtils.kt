package com.telo.tinyzora.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object MediaUtils {
    suspend fun getDownscaledBitmap(context: Context, uri: Uri, maxDimension: Int = 1024): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            
            options.inSampleSize = calculateInSampleSize(options, maxDimension, maxDimension)
            options.inJustDecodeBounds = false
            
            return@withContext context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }
    
    fun compressForInference(original: Bitmap): ByteArray {
        val maxDimension = 768
        val ratio = Math.min(
            maxDimension.toFloat() / original.width,
            maxDimension.toFloat() / original.height
        )
        
        val width = Math.round(ratio * original.width)
        val height = Math.round(ratio * original.height)

        val resizedBitmap = Bitmap.createScaledBitmap(original, width, height, true)
        
        val stream = ByteArrayOutputStream()
        resizedBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
    
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
    
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
