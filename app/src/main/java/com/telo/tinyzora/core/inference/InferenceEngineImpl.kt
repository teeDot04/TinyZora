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
    private external fun processUserPrompt(userPrompt: String, predictLength: Int, temperature: Float, topK: Int, topP: Float): Int
    private external fun generateNextToken(): String?
    private external fun unload()
    private external fun shutdown()

    init {
        llamaScope.launch {
            try {
                check(_state.value is InferenceEngine.State.Uninitialized)
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

    // FIX: Use block body instead of expression body to ensure Unit return type
    override suspend fun loadModel(pathToModel: String) {
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.Initialized)
            try {
                val ctxSize = userPrefs.getCtxSize()
                Log.i(TAG, "Loading model with ctx_size=$ctxSize from: $pathToModel")
                _readyForSystemPrompt = false
                _state.value = InferenceEngine.State.LoadingModel
                load(pathToModel, ctxSize).let {
                    if (it != 0) throw Exception("Failed to load model (error $it)")
                }
                prepare().let {
                    if (it != 0) throw Exception("Failed to prepare resources")
                }
                Log.i(TAG, "Model loaded!")
                _readyForSystemPrompt = true
                _cancelGeneration = false
                _state.value = InferenceEngine.State.ModelReady
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
            Log.i(TAG, "Sending system prompt...")
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.ProcessingSystemPrompt
            processSystemPrompt(prompt).let { result ->
                if (result != 0) {
                    RuntimeException("Failed to process system prompt: $result").also {
                        _state.value = InferenceEngine.State.Error(it)
                        throw it
                    }
                }
            }
            Log.i(TAG, "System prompt processed!")
            _state.value = InferenceEngine.State.ModelReady
        }
    }

    override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> = flow {
        require(message.isNotEmpty())
        check(_state.value is InferenceEngine.State.ModelReady)
        try {
            Log.i(TAG, "Sending user prompt with predictLength=$predictLength")
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.ProcessingUserPrompt
            
            val temperature = userPrefs.getTemperature()
            val topK = userPrefs.getTopK()
            val topP = userPrefs.getTopP()
            
            Log.i(TAG, "Using params: temp=$temperature, topK=$topK, topP=$topP")
            
            processUserPrompt(message, predictLength, temperature, topK, topP).let { result ->
                if (result != 0) {
                    Log.e(TAG, "Failed to process user prompt: $result")
                    return@flow
                }
            }
            Log.i(TAG, "User prompt processed. Generating...")
            _state.value = InferenceEngine.State.Generating
            while (!_cancelGeneration) {
                generateNextToken()?.let { utf8token ->
                    if (utf8token.isNotEmpty()) emit(utf8token)
                } ?: break
            }
            if (_cancelGeneration) {
                Log.i(TAG, "Generation aborted.")
            } else {
                Log.i(TAG, "Generation complete!")
            }
            _state.value = InferenceEngine.State.ModelReady
        } catch (e: CancellationException) {
            Log.i(TAG, "Flow collection cancelled.")
            _state.value = InferenceEngine.State.ModelReady
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during generation!", e)
            _state.value = InferenceEngine.State.Error(e)
            throw e
        }
    }.flowOn(llamaDispatcher)

    override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String = withContext(llamaDispatcher) {
        check(_state.value is InferenceEngine.State.ModelReady) { "Benchmark request discarded due to: ${_state.value}" }
        Log.i(TAG, "Start benchmark (pp: $pp, tg: $tg, pl: $pl, nr: $nr)")
        _readyForSystemPrompt = false
        _state.value = InferenceEngine.State.Benchmarking
        benchModel(pp, tg, pl, nr).also {
            _state.value = InferenceEngine.State.ModelReady
        }
    }

    override fun cleanUp() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            when (val state = _state.value) {
                is InferenceEngine.State.ModelReady -> {
                    Log.i(TAG, "Unloading model...")
                    _readyForSystemPrompt = false
                    _state.value = InferenceEngine.State.UnloadingModel
                    unload()
                    _state.value = InferenceEngine.State.Initialized
                    Log.i(TAG, "Model unloaded!")
                }
                is InferenceEngine.State.Error -> {
                    Log.i(TAG, "Resetting error state...")
                    _state.value = InferenceEngine.State.Initialized
                }
                else -> throw IllegalStateException("Cannot unload model in ${state.javaClass.simpleName}")
            }
        }
    }

    override fun destroy() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            _readyForSystemPrompt = false
            when(_state.value) {
                is InferenceEngine.State.Uninitialized -> {}
                is InferenceEngine.State.Initialized -> shutdown()
                else -> { unload(); shutdown() }
            }
        }
        llamaScope.cancel()
    }
}
