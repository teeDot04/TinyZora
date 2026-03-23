package com.telo.tinyzora.util

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

object AudioUtils {
    // Gemma 3 Audio requirements
    const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    // Hard limit audio to 30 seconds
    private const val MAX_AUDIO_DURATION_SEC = 30

    /**
     * Records audio from the microphone.
     *
     * @param onAmplitude  Called on the IO thread each chunk with a normalised [0..1] amplitude
     *                     value — use this to drive the waveform UI. This avoids opening a second
     *                     AudioRecord that conflicts with this one.
     * @param onMaxDurationReached  Called when the recording hits the hard time cap.
     * @param stopSignal   Suspend function; should return `true` when the caller wants to stop.
     * @return  WAV-formatted audio byte array.
     */
    @SuppressLint("MissingPermission")
    suspend fun recordAudio(
        onAmplitude: (Float) -> Unit = {},
        onMaxDurationReached: () -> Unit,
        stopSignal: suspend () -> Boolean
    ): ByteArray = withContext(Dispatchers.IO) {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(4096)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            minBufferSize
        )

        val buffer = ByteArray(minBufferSize)
        val stream = ByteArrayOutputStream()
        val startMs = System.currentTimeMillis()

        recorder.startRecording()

        try {
            while (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING && !stopSignal()) {
                val bytesRead = recorder.read(buffer, 0, buffer.size)
                if (bytesRead > 0) {
                    stream.write(buffer, 0, bytesRead)

                    // Compute RMS amplitude from the raw PCM shorts — single recorder, no conflict
                    val shorts = ShortArray(bytesRead / 2)
                    for (i in shorts.indices) {
                        val lo = buffer[i * 2].toInt() and 0xFF
                        val hi = buffer[i * 2 + 1].toInt()
                        shorts[i] = ((hi shl 8) or lo).toShort()
                    }
                    val rms = sqrt(shorts.map { it.toDouble() * it }.average()).toFloat()
                    val normalised = (rms / 32768f).coerceIn(0f, 1f)
                    onAmplitude(normalised)
                }

                if (System.currentTimeMillis() - startMs >= MAX_AUDIO_DURATION_SEC * 1000L) {
                    onMaxDurationReached()
                    break
                }
            }
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
            recorder.release()
        }

        val pcmData = stream.toByteArray()
        generateWavByteArray(pcmData)
    }

    private fun generateWavByteArray(pcmData: ByteArray): ByteArray {
        val header = ByteArray(44)
        val pcmDataSize = pcmData.size
        val wavFileSize = pcmDataSize + 44
        val channels = 1
        val bitsPerSample: Short = 16
        val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8

        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (wavFileSize and 0xFF).toByte()
        header[5] = (wavFileSize shr 8 and 0xFF).toByte()
        header[6] = (wavFileSize shr 16 and 0xFF).toByte()
        header[7] = (wavFileSize shr 24 and 0xFF).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (SAMPLE_RATE and 0xFF).toByte()
        header[25] = (SAMPLE_RATE shr 8 and 0xFF).toByte()
        header[26] = (SAMPLE_RATE shr 16 and 0xFF).toByte()
        header[27] = (SAMPLE_RATE shr 24 and 0xFF).toByte()
        header[28] = (byteRate and 0xFF).toByte()
        header[29] = (byteRate shr 8 and 0xFF).toByte()
        header[30] = (byteRate shr 16 and 0xFF).toByte()
        header[31] = (byteRate shr 24 and 0xFF).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte(); header[33] = 0
        header[34] = bitsPerSample.toByte()
        header[35] = (bitsPerSample.toInt() shr 8 and 0xFF).toByte()
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (pcmDataSize and 0xFF).toByte()
        header[41] = (pcmDataSize shr 8 and 0xFF).toByte()
        header[42] = (pcmDataSize shr 16 and 0xFF).toByte()
        header[43] = (pcmDataSize shr 24 and 0xFF).toByte()

        return header + pcmData
    }

    /**
     * Converts the raw amplitude history from a recording session into a compact
     * list of 20 representative bars suitable for the static waveform pill.
     */
    fun summariseAmplitudes(history: List<Float>, bars: Int = 20): List<Float> {
        if (history.isEmpty()) return List(bars) { 0.1f }
        val step = (history.size.toFloat() / bars).coerceAtLeast(1f)
        return (0 until bars).map { bar ->
            val start = (bar * step).toInt().coerceIn(0, history.size - 1)
            val end = ((bar + 1) * step).toInt().coerceIn(start + 1, history.size)
            history.subList(start, end).average().toFloat().coerceIn(0.05f, 1f)
        }
    }
}
