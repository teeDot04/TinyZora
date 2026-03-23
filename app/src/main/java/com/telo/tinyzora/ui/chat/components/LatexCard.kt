package com.telo.tinyzora.ui.chat.components

import android.graphics.Color
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.toArgb
import java.util.concurrent.ConcurrentLinkedQueue
import android.content.Context

private object WebViewPool {
    private val pool = ConcurrentLinkedQueue<WebView>()
    
    fun get(context: Context): WebView {
        return pool.poll() ?: WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
        }
    }
    
    fun recycle(webView: WebView) {
        webView.loadDataWithBaseURL(null, "", "text/html", "UTF-8", null)
        webView.tag = null
        if (pool.size < 5) pool.offer(webView)
    }
}

@Composable
fun LatexCard(formula: String) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val textColorCss = String.format("#%06X", 0xFFFFFF and textColor.toArgb())

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .heightIn(min = 60.dp)
    ) {
        AndroidView(
            modifier = Modifier.padding(8.dp),
            factory = { context -> WebViewPool.get(context) },
            onRelease = { webView -> WebViewPool.recycle(webView) },
            update = { webView ->
                val currentTag = webView.tag as? String
                if (currentTag == formula) return@AndroidView // Avoid redundant reloads
                
                webView.tag = formula
                
                // Escape backslashes, quotes, and newlines safely for JSON/JS literal injection
                val escapedFormula = formula
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("'", "\\'")
                    .replace("\n", "\\n")
                    .replace("\r", "")
                    
                val html = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                      <link rel="stylesheet" href="file:///android_asset/katex/katex.min.css">
                      <script src="file:///android_asset/katex/katex.min.js"></script>
                      <style>
                        body {
                          margin: 0; padding: 12px 16px;
                          background: transparent;
                          display: flex;
                          align-items: center;
                          justify-content: center;
                          color: $textColorCss;
                        }
                        .katex { font-size: 1.2em; }
                      </style>
                    </head>
                    <body>
                      <span id="formula"></span>
                      <script>
                        try {
                          katex.render("$escapedFormula", 
                            document.getElementById('formula'),
                            { throwOnError: false, displayMode: true }
                          );
                        } catch (e) {}
                      </script>
                    </body>
                    </html>
                """.trimIndent()
                webView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
            }
        )
    }
}
