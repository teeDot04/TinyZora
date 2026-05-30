package com.telo.tinyzora.core.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LlamaAndroid {

    private var tokenCallback: ((String) -> Unit)? = null

    companion object {
        init {
            System.loadLibrary("llama_android")
        }
    }

    @Suppress("unused")
    private fun onTokenGenerated(token: String) {
        tokenCallback?.invoke(token)
    }

    external fun loadModel(
        path: String,
        nCtx: Int,
        nThreads: Int,
        topK: Int,
        topP: Float,
        temp: Float
    ): Boolean

    private external fun sendMessageNative(prompt: String)
    external fun stopGeneration()
    external fun unloadModel()

    fun sendMessageBlocking(prompt: String, onToken: (String) -> Unit) {
        tokenCallback = onToken
        try {
            sendMessageNative(prompt)
        } finally {
            tokenCallback = null
        }
    }
}
