package com.telo.tinyzora.core.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface InferenceEngine {
    val state: StateFlow<State>
    suspend fun loadModel(pathToModel: String)
    suspend fun setSystemPrompt(systemPrompt: String)
    fun sendUserPrompt(message: String, predictLength: Int = DEFAULT_PREDICT_LENGTH): Flow<String>
    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String
    fun cleanUp()
    fun destroy()

    sealed class State {
        object Uninitialized : State()
        object Initializing : State()
        object Initialized : State()
        object LoadingModel : State()
        object UnloadingModel : State()
        object ModelReady : State()
        object Benchmarking : State()
        object ProcessingSystemPrompt : State()
        object ProcessingUserPrompt : State()
        object Generating : State()
        data class Error(val exception: Exception) : State()
    }

    companion object {
        const val DEFAULT_PREDICT_LENGTH = 1024
    }
}

val State.isUninterruptible
    get() = this is State.Initializing || this is State.LoadingModel || this is State.UnloadingModel || this is State.Benchmarking || this is State.ProcessingSystemPrompt || this is State.ProcessingUserPrompt

val State.isModelLoaded: Boolean
    get() = this is State.ModelReady || this is State.Benchmarking || this is State.ProcessingSystemPrompt || this is State.ProcessingUserPrompt || this is State.Generating

class UnsupportedArchitectureException : Exception()
