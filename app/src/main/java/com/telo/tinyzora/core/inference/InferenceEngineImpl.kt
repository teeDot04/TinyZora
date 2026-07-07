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
    private external fun processUserPrompt(userPrompt: String, predictLength: Int, temperature: Float, topK: Int, topP: Float): Int
    private external fun generateNextToken(): String?
    private external fun unload()
    private external fun shutdown()

    // FIX #1: Make init synchronous and wait for completion
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
            check(_state.value is InferenceEngine.State.Initialized)
            try {
                val modelFile = File(pathToModel)
                Log.i(TAG, "Attempting to load model from: $pathToModel")
                Log.i(TAG, "File exists: ${modelFile.exists()}, size: ${modelFile.length()} bytes")

                val ctxSize = userPrefs.getCtxSize()
                Log.i(TAG, "Loading model with ctx_size=$ctxSize")
                _readyForSystemPrompt = false
                _state.value = InferenceEngine.State.LoadingModel

                val loadResult = load(pathToModel, ctxSize)
                Log.i(TAG, "Native load() returned: $loadResult")
                if (loadResult != 0) {
                    Log.e(TAG, "Model load FAILED with error code: $loadResult")
                    throw Exception("Failed to load model (error $loadResult)")
                }

                val prepareResult = prepare()
                Log.i(TAG, "Native prepare() returned: $prepareResult")
                if (prepareResult != 0) {
                    Log.e(TAG, "Model prepare FAILED with error code: $prepareResult")
                    throw Exception("Failed to prepare resources")
                }

                Log.i(TAG, "Model loaded successfully!")
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
            Log.i(TAG, "Sending system prompt (length=${prompt.length})...")
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.ProcessingSystemPrompt

            val result = processSystemPrompt(prompt)
            Log.i(TAG, "System prompt processing returned: $result")
            if (result != 0) {
                RuntimeException("Failed to process system prompt: $result").also {
                    _state.value = InferenceEngine.State.Error(it)
                    throw it
                }
            }

            Log.i(TAG, "System prompt processed successfully!")
            _state.value = InferenceEngine.State.ModelReady
        }
    }

    // FIX #3: Use callbackFlow to ensure JNI calls run on llamaDispatcher
    override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> = callbackFlow {
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

            // FIX #3: Wrap in withContext to ensure JNI runs on llamaDispatcher
            val result = withContext(llamaDispatcher) {
                processUserPrompt(message, predictLength, temperature, topK, topP)
            }

            if (result != 0) {
                Log.e(TAG, "Failed to process user prompt: $result")
                close()
                return@callbackFlow
            }

            Log.i(TAG, "User prompt processed. Generating...")
            _state.value = InferenceEngine.State.Generating

            while (!_cancelGeneration) {
                val token = withContext(llamaDispatcher) { generateNextToken() }
                token?.let { utf8token ->
                    if (utf8token.isNotEmpty()) {
                        trySend(utf8token)
                    }
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
        val result = benchModel(pp, tg, pl, nr)
        Log.i(TAG, "Benchmark result: $result")
        _state.value = InferenceEngine.State.ModelReady
        result
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