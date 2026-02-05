package com.telo.tinyzora.ui

import android.content.Context
import android.media.MediaRecorder
import java.io.File

class AudioRecorder(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun startRecording(): File {
        // Create output file
        val audioDir = File(context.cacheDir, "audio")
        audioDir.mkdirs()
        outputFile = File(audioDir, "recording_${System.currentTimeMillis()}.m4a")

        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(outputFile!!.absolutePath)
            prepare()
            start()
        }

        return outputFile!!
    }

    fun stopRecording(): File? {
        return try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            outputFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun cancelRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            outputFile?.delete()
            outputFile = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun release() {
        cancelRecording()
    }
}
