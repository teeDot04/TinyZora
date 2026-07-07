package com.telo.tinyzora.core.inference

import android.content.Context
import android.util.Log
import com.telo.tinyzora.core.security.UserPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

internal class InferenceEngineImpl private constructor(
    private val context: Context
) : InferenceEngine {
    companion object {
        private val TAG = InferenceEngineImpl::class.java.simpleName
        @Volatile
        private var instance: InferenceEngine? = null

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
    // FIX 1: signature matches C++ exactly — (String, Float, Int, Float)
    private external fun processUserPrompt(userPrompt: String, temperature: Float, topK: Int, topP: Float): Int
    private external fun generateNextToken(): String?
    private external fun unload()
    private external fun shutdown()

    init {
        runBlocking(llamaDispatcher) {
            try {
                _state.value = InferenceEngine.State.Initializing
                Log.i(TAG, "Loading native library...")
                System.loadLibrary("ai-chat")
                init()
                _state.value = InferenceEngine.State.Initialized
                Log.i(TAG, "Native library loaded!")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load native library", e)
                _state.value = InferenceEngine.State.Error(e)
                throw e
            }
        }
    }

    override suspend fun loadModel(pathToModel: String) {
        withContext(llamaDispatcher) {
            // FIX 2: recover from Error state so a retry works
            if (_state.value is InferenceEngine.State.Error) {
                Log.w(TAG, "Resetting from Error state before load")
                _state.value = InferenceEngine.State.Initialized
            }
            check(_state.value is InferenceEngine.State.Initialized) {
                "loadModel requires Initialized, got ${_state.value}"
            }
            try {
                val modelFile = File(pathToModel)
                Log.i(TAG, "File exists: ${modelFile.exists()}, size: ${modelFile.length()} bytes")
                _readyForSystemPrompt = false
                _state.value = InferenceEngine.State.LoadingModel

                val loadResult = load(pathToModel, userPrefs.getCtxSize())
                if (loadResult != 0) throw IllegalStateException("load() returned $loadResult")

                val prepareResult = prepare()
                if (prepareResult != 0) throw IllegalStateException("prepare() returned $prepareResult")

                _readyForSystemPrompt = true
                _cancelGeneration = false
                _state.value = InferenceEngine.State.ModelReady
                Log.i(TAG, "Model loaded successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading model", e)
                _state.value = InferenceEngine.State.Error(e)
                throw e
            }
        }
    }

    override suspend fun setSystemPrompt(prompt: String) {
        withContext(llamaDispatcher) {
            require(prompt.isNotBlank())
            check(_readyForSystemPrompt)
            check(_state.value is InferenceEngine.State.ModelReady)
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.ProcessingSystemPrompt
            val result = processSystemPrompt(prompt)
            if (result != 0) {
                val ex = IllegalStateException("processSystemPrompt() returned $result")
                _state.value = InferenceEngine.State.Error(ex)
                throw ex
            }
            _state.value = InferenceEngine.State.ModelReady
            Log.i(TAG, "System prompt set OK")
        }
    }

    override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> = callbackFlow {
        require(message.isNotEmpty())
        check(_state.value is InferenceEngine.State.ModelReady)
        try {
            _cancelGeneration = false
            _state.value = InferenceEngine.State.ProcessingUserPrompt

            val temperature = userPrefs.getTemperature()
            val topK        = userPrefs.getTopK()
            val topP        = userPrefs.getTopP()

            // FIX 1: call without predictLength — matches C++ signature
            val result = withContext(llamaDispatcher) {
                processUserPrompt(message, temperature, topK, topP)
            }
            if (result != 0) {
                Log.e(TAG, "processUserPrompt() returned $result")
                _state.value = InferenceEngine.State.ModelReady
                close()
                return@callbackFlow
            }

            _state.value = InferenceEngine.State.Generating
            var n = 0
            while (!_cancelGeneration && n < predictLength) {
                val piece = withContext(llamaDispatcher) { generateNextToken() } ?: break
                if (piece.isNotEmpty()) trySend(piece)
                n++
            }
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

    override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String =
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.ModelReady) {
                "bench requires ModelReady, got ${_state.value}"
            }
            _state.value = InferenceEngine.State.Benchmarking
            try { benchModel(pp, tg, pl, nr) }
            finally { _state.value = InferenceEngine.State.ModelReady }
        }

    override fun cleanUp() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            when (_state.value) {
                is InferenceEngine.State.ModelReady,
                is InferenceEngine.State.Generating -> {
                    _readyForSystemPrompt = false
                    _state.value = InferenceEngine.State.UnloadingModel
                    unload()
                    _state.value = InferenceEngine.State.Initialized
                    Log.i(TAG, "Model unloaded")
                }
                // FIX 2: don't throw for Initialized or Error — just reset
                is InferenceEngine.State.Error,
                is InferenceEngine.State.Initialized -> {
                    _state.value = InferenceEngine.State.Initialized
                }
                else -> {
                    Log.w(TAG, "cleanUp() called in unexpected state: ${_state.value}")
                    _state.value = InferenceEngine.State.Initialized
                }
            }
        }
    }

    override fun destroy() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            _readyForSystemPrompt = false
            when (_state.value) {
                is InferenceEngine.State.Uninitialized -> {}
                is InferenceEngine.State.Initialized   -> shutdown()
                else                                   -> { unload(); shutdown() }
            }
        }
        llamaScope.cancel()
    }
}
