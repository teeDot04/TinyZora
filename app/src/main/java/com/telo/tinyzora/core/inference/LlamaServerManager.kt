package com.telo.tinyzora.core.inference

import android.content.Context
import android.system.Os
import com.telo.tinyzora.util.ConsoleLogger
import com.telo.tinyzora.core.security.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

object LlamaServerManager {

    private const val TAG = "LlamaServerManager"
    private const val ASSETS_DIR = "llama"
    private const val SERVER_BINARY = "llama-server"
    private const val STARTUP_TIMEOUT_MS = 10_000L
    private const val HEALTH_POLL_MS = 500L

    private var process: Process? = null

    val isRunning: Boolean
        get() = process?.isAlive == true

    suspend fun start(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext Result.success(Unit)

        val prefs = UserPreferences(context)
        val modelPath = prefs.getModelPath()

        if (modelPath.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("No model selected. Import a .gguf model first.")
            )
        }

        if (!File(modelPath).exists()) {
            return@withContext Result.failure(
                IllegalStateException("Model file not found: $modelPath")
            )
        }

        return@withContext try {
            val serverBin = extractBinaries(context)
            launchProcess(context, serverBin, modelPath, prefs)
            waitForHealth(prefs.getServerUrl())
        } catch (e: Exception) {
            ConsoleLogger.e(TAG, "Failed to start llama-server: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun stop() {
        process?.let {
            it.destroy()
            ConsoleLogger.d(TAG, "llama-server stopped.")
        }
        process = null
    }

    private fun extractBinaries(context: Context): File {
        val outDir = File(context.filesDir, ASSETS_DIR).also { it.mkdirs() }
        val versionFile = File(outDir, ".version")
        val currentVersion = context.packageManager
            .getPackageInfo(context.packageName, 0).versionCode.toString()

        if (versionFile.exists() && versionFile.readText().trim() == currentVersion) {
            ConsoleLogger.d(TAG, "Binaries up to date, skipping extraction.")
            return File(outDir, SERVER_BINARY)
        }

        ConsoleLogger.d(TAG, "Extracting llama binaries to $outDir")
        context.assets.list(ASSETS_DIR)?.forEach { filename ->
            val outFile = File(outDir, filename)
            context.assets.open("$ASSETS_DIR/$filename").use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            if (!filename.endsWith(".so")) {
                Os.chmod(outFile.absolutePath, 0b111_101_101)
            }
        }

        versionFile.writeText(currentVersion)
        ConsoleLogger.d(TAG, "Extraction complete.")
        val binary = File(outDir, SERVER_BINARY)
        if (!binary.exists()) {
            throw IllegalStateException("llama-server binary missing from assets/llama/ — cannot start server")
        }
        return binary
    }

    private fun launchProcess(
        context: Context,
        serverBin: File,
        modelPath: String,
        prefs: UserPreferences
    ) {
        val libDir = File(context.filesDir, ASSETS_DIR).absolutePath
        val ctxSize = prefs.getCtxSize()
        val port = extractPort(prefs.getServerUrl())

        val cmd = listOf(
            serverBin.absolutePath,
            "-m", modelPath,
            "-c", ctxSize.toString(),
            "-t", "4",
            "--port", port,
            "--host", "127.0.0.1",
            "--no-mmap"
        )

        ConsoleLogger.d(TAG, "Launching: ${cmd.joinToString(" ")}")

        process = ProcessBuilder(cmd)
            .apply {
                environment()["LD_LIBRARY_PATH"] = libDir
                redirectErrorStream(true)
            }
            .start()

        Thread {
            process?.inputStream?.bufferedReader()?.forEachLine { line ->
                ConsoleLogger.d(TAG, "server: $line")
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private suspend fun waitForHealth(serverUrl: String): Result<Unit> {
        val healthUrl = "$serverUrl/health"
        val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            if (!isRunning) {
                return Result.failure(IllegalStateException("llama-server process died on startup"))
            }
            try {
                val connection = java.net.URL(healthUrl).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 500
                connection.readTimeout = 500
                if (connection.responseCode == 200) {
                    ConsoleLogger.d(TAG, "llama-server ready at $serverUrl")
                    return Result.success(Unit)
                }
                connection.disconnect()
            } catch (_: Exception) {
            }
            delay(HEALTH_POLL_MS)
        }

        stop()
        return Result.failure(IllegalStateException("llama-server failed to start within ${STARTUP_TIMEOUT_MS}ms"))
    }

    private fun extractPort(serverUrl: String): String {
        return try {
            java.net.URL(serverUrl).port.takeIf { it > 0 }?.toString() ?: "8080"
        } catch (_: Exception) {
            "8080"
        }
    }
}
