package com.telo.tinyzora.core.inference

import android.content.Context
import android.util.Log
import com.telo.tinyzora.core.security.UserPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class InferenceEngineImpl private constructor(
    private val context: Context
) : InferenceEngine {
    companion object {
        private val TAG = InferenceEngineImpl::class.java.simpleName
        @Volatile private var instance: InferenceEngine? = null
        internal fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                instance ?: InferenceEngineImpl(context.applicationContext).also { instance = it }
            }
    }

    private val userPrefs = UserPreferences(context)
    private val _state = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
    override val state: StateFlow<InferenceEngine.State> = _state.asStateFlow()
    private var _readyForSystemPrompt = false
    private var _cancelGeneration = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val llamaScope = CoroutineScope(llamaDispatcher + SupervisorJob())

    private external fun init()
    private external fun load(modelPath: String, ctxSize: Int): Int
    private external fun prepare(): Int
    private external fun systemInfo(): String
    private external fun benchModel(pp: Int, tg: Int, pl: Int, nr: Int): String
    private external fun processSystemPrompt(systemPrompt: String): Int
    // CHANGED: no predictLength - just tokenize and set up sampler
    private external fun processUserPrompt(userPrompt: String, temperature: Float, topK: Int, topP: Float): Int
    // Now called repeatedly, returns one token at a time (or null when done)
    private external fun generateNextToken(): String?
    private external fun unload()
    private external fun shutdown()

    init {
        llamaScope.launch {
            try {
                check(_state.value is InferenceEngine.State.Uninitialized)
                _state.value = InferenceEngine.State.Initializing
                System.loadLibrary("ai-chat")
                init()
                _state.value = InferenceEngine.State.Initialized
                Log.i(TAG, "Native library loaded")
            } catch (e: Exception) {
                _state.value = InferenceEngine.State.Error(e)
                throw e
            }
        }
    }

    override suspend fun loadModel(pathToModel: String) = withContext(llamaDispatcher) {
        check(_state.value is InferenceEngine.State.Initialized)
        try {
            val ctxSize = userPrefs.getCtxSize()
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.LoadingModel
            load(pathToModel, ctxSize).let { if (it != 0) throw Exception("Load error $it") }
            prepare().let { if (it != 0) throw Exception("Prepare error $it") }
            _readyForSystemPrompt = true
            _cancelGeneration = false
            _state.value = InferenceEngine.State.ModelReady
            Log.i(TAG, "Model loaded, ctx=$ctxSize")
        } catch (e: Exception) {
            _state.value = InferenceEngine.State.Error(e)
            throw e
        }
    }

    override suspend fun setSystemPrompt(prompt: String) = withContext(llamaDispatcher) {
        require(prompt.isNotBlank())
        check(_readyForSystemPrompt && _state.value is InferenceEngine.State.ModelReady)
        _state.value = InferenceEngine.State.ProcessingSystemPrompt
        processSystemPrompt(prompt).let { if (it != 0) throw RuntimeException("System prompt failed") }
        _state.value = InferenceEngine.State.ModelReady
    }

    override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> = flow {
        require(message.isNotEmpty())
        check(_state.value is InferenceEngine.State.ModelReady)
        try {
            _state.value = InferenceEngine.State.ProcessingUserPrompt
            val temp = userPrefs.getTemperature()
            val topK = userPrefs.getTopK()
            val topP = userPrefs.getTopP()
            Log.i(TAG, "Processing prompt: temp=$temp topK=$topK topP=$topP")

            processUserPrompt(message, temp, topK, topP).let {
                if (it != 0) { emit("Error: failed to process prompt"); return@flow }
            }

            _state.value = InferenceEngine.State.Generating
            var count = 0
            // STREAMING: emit each token as it's generated
            while (!_cancelGeneration && count < predictLength) {
                val token = generateNextToken() ?: break
                if (token.isNotEmpty()) emit(token)
                count++
            }
            Log.i(TAG, "Generation complete: $count tokens")
            _state.value = InferenceEngine.State.ModelReady
        } catch (e: CancellationException) {
            _state.value = InferenceEngine.State.ModelReady
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Generation error", e)
            _state.value = InferenceEngine.State.Error(e)
            throw e
        }
    }.flowOn(llamaDispatcher)

    override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String = withContext(llamaDispatcher) {
        check(_state.value is InferenceEngine.State.ModelReady) { "Engine not ready: ${_state.value}" }
        _state.value = InferenceEngine.State.Benchmarking
        benchModel(pp, tg, pl, nr).also {
            _state.value = InferenceEngine.State.ModelReady
        }
    }

    override fun cleanUp() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            when (_state.value) {
                is InferenceEngine.State.ModelReady -> {
                    _state.value = InferenceEngine.State.UnloadingModel
                    unload()
                    _state.value = InferenceEngine.State.Initialized
                }
                is InferenceEngine.State.Error -> _state.value = InferenceEngine.State.Initialized
                else -> {}
            }
        }
    }

    override fun destroy() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            when (_state.value) {
                is InferenceEngine.State.Uninitialized -> {}
                is InferenceEngine.State.Initialized -> shutdown()
                else -> { unload(); shutdown() }
            }
        }
        llamaScope.cancel()
    }
}
