package com.telo.tinyzora.util

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AudioClip(
    val audioData: ByteArray,
    val sampleRate: Int
) {
    // Helper to regenerate a valid WAV file from the raw PCM data (for playback or debugging)
    fun genByteArrayForWav(): ByteArray {
        val header = ByteArray(44)
        val totalDataLen = audioData.size + 36
        val bitrate = sampleRate * 16 * 1 / 8
        val byteRate = sampleRate * 1 * 16 / 8

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        
        ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalDataLen)
        
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        
        ByteBuffer.wrap(header, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(16) // Subchunk1Size
        
        header[20] = 1 // AudioFormat (PCM)
        header[21] = 0
        header[22] = 1 // Channels (Mono)
        header[23] = 0
        
        ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(sampleRate)
        ByteBuffer.wrap(header, 28, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(byteRate)
        
        header[32] = 2 // BlockAlign
        header[33] = 0
        header[34] = 16 // BitsPerSample
        header[35] = 0
        
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        
        ByteBuffer.wrap(header, 40, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(audioData.size)
        
        return header + audioData
    }
    
    fun getDurationInSeconds(): Float {
        // sampleRate * 2 bytes per sample
        return audioData.size.toFloat() / (sampleRate * 2f)
    }
}

object AudioUtils {
    private const val TAG = "AudioUtils"
    private const val HEADER_SIZE = 44
    private const val TARGET_SAMPLE_RATE = 16000 // Standard for LLMs

    fun convertWavToMonoWithMaxSeconds(
        context: Context,
        stereoUri: Uri,
        maxSeconds: Int = 30
    ): AudioClip? {
        Log.d(TAG, "Start to convert wav file to mono channel")
        
        try {
            context.contentResolver.openInputStream(stereoUri)?.use { inputStream ->
                val bufferedStream = BufferedInputStream(inputStream)
                val bytes = bufferedStream.readBytes()
                
                if (bytes.size < HEADER_SIZE) return null
                
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                
                // Read Header Info
                val channels = buffer.getShort(22).toInt()
                val sampleRate = buffer.getInt(24)
                val bitsPerSample = buffer.getShort(34).toInt()
                
                // Find 'data' chunk
                var dataOffset = 36
                while (dataOffset < bytes.size - 8) {
                    if (bytes[dataOffset] == 'd'.code.toByte() && 
                        bytes[dataOffset+1] == 'a'.code.toByte() && 
                        bytes[dataOffset+2] == 't'.code.toByte() && 
                        bytes[dataOffset+3] == 'a'.code.toByte()) {
                        break
                    }
                    dataOffset++
                }
                dataOffset += 8 // Skip "data" + Size
                
                if (dataOffset >= bytes.size) {
                    Log.e(TAG, "No data chunk found")
                    return null
                }

                // Process Audio Data
                val pcmData = ByteBuffer.wrap(bytes, dataOffset, bytes.size - dataOffset)
                    .order(ByteOrder.LITTLE_ENDIAN)
                
                val outputStream = ByteArrayOutputStream()
                val shortBuffer = pcmData.asShortBuffer()
                val totalSamples = shortBuffer.remaining()
                
                // Calculate max samples to keep
                val maxSamples = maxSeconds * sampleRate * channels
                val limit = if (totalSamples > maxSamples) maxSamples else totalSamples
                
                // Downmixing & Truncating
                // This is a simplified loop. For production, efficient array ops exist.
                for (i in 0 until limit step channels) {
                    if (channels == 2) {
                        // Average Left + Right
                        val left = shortBuffer.get(i)
                        val right = shortBuffer.get(i + 1)
                        val mono = ((left + right) / 2).toShort()
                        outputStream.write(mono.toInt() and 0xFF)
                        outputStream.write((mono.toInt() shr 8) and 0xFF)
                    } else {
                        // Already Mono
                        val sample = shortBuffer.get(i)
                        outputStream.write(sample.toInt() and 0xFF)
                        outputStream.write((sample.toInt() shr 8) and 0xFF)
                    }
                }
                
                // Note: We are currently NOT resampling. 
                // If the input is 44.1kHz, we are returning 44.1kHz.
                // MediaPipe typically wants 16kHz, but might handle others if metadata is correct.
                // Or we should downsample. Simple decimation (drop samples) works if ratio is int.
                // For now, returning native sample rate.
                
                return AudioClip(outputStream.toByteArray(), sampleRate)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting audio", e)
            return null
        }
        return null
    }
}
