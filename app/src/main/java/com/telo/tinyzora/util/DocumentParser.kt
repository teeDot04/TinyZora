package com.telo.tinyzora.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.telo.tinyzora.util.ConsoleLogger
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

object DocumentParser {
    private const val TAG = "DocumentParser"
    private var isPdfBoxInitialized = false

    private fun initPdfBox(context: Context) {
        if (!isPdfBoxInitialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            isPdfBoxInitialized = true
        }
    }

    suspend fun parseFromUri(context: Context, uri: Uri, maxWords: Int = 600): String? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri) ?: ""
        val uriStr = uri.toString().lowercase()

        try {
            val text = when {
                mimeType == "application/pdf" || uriStr.endsWith(".pdf") -> {
                    initPdfBox(context)
                    resolver.openInputStream(uri)?.use { stream ->
                        PDDocument.load(stream).use { document ->
                            val stripper = PDFTextStripper()
                            stripper.getText(document)
                        }
                    }
                }
                mimeType.startsWith("text/") || mimeType == "application/json" || uriStr.endsWith(".txt") || uriStr.endsWith(".md") || uriStr.endsWith(".json") || uriStr.endsWith(".csv") -> {
                    resolver.openInputStream(uri)?.use { stream ->
                        InputStreamReader(stream).readText()
                    }
                }
                else -> {
                    ConsoleLogger.i(TAG, "Unsupported MIME type: $mimeType")
                    return@withContext null
                }
            } ?: return@withContext null

            truncateWords(text, maxWords)
        } catch (e: Exception) {
            ConsoleLogger.e(TAG, "Error parsing document", e)
            null
        }
    }

    private fun truncateWords(text: String, maxWords: Int): String {
        val words = text.split("\\s+".toRegex())
        if (words.size <= maxWords) return text
        return words.take(maxWords).joinToString(" ") + "\n\n...[Document Truncated to $maxWords words for Context Window Size]"
    }
}
