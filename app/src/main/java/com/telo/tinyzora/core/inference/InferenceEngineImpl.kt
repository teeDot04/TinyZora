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
        internal fun getInstance(context: Context): InferenceEngine =
            instance ?: synchronized(this) {
                instance ?: InferenceEngineImpl(context.applicationContext).also { instance = it }
            }
    }

    private val userPrefs = UserPreferences(context)
    private val _state = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
    override val state: StateFlow<InferenceEngine.State> = _state.asStateFlow()

    @Volatile private var _readyForSystemPrompt = false
    @Volatile private var _cancelGeneration     = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val llamaScope      = CoroutineScope(llamaDispatcher + SupervisorJob())

    // ── JNI — param counts must match C++ exactly ────────────────────────────
    private external fun init()
    private external fun load(modelPath: String, ctxSize: Int): Int
    private external fun prepare(): Int
    private external fun systemInfo(): String
    private external fun processSystemPrompt(systemPrompt: String): Int
    // 4 params — no predictLength (cap lives in the Kotlin loop below)
    private external fun processUserPrompt(
        userPrompt: String,
        temperature: Float,
        topK: Int,
        topP: Float
    ): Int
    private external fun generateNextToken(): String?
    private external fun benchModel(pp: Int, tg: Int, pl: Int, nr: Int): String
    private external fun unload()
    private external fun shutdown()

    // ── init ─────────────────────────────────────────────────────────────────
    // Runs on llamaDispatcher (limitedParallelism=1), so loadModel() queued
    // after this will always see Initialized state — no race possible.
    init {
        llamaScope.launch {
            try {
                _state.value = InferenceEngine.State.Initializing
                System.loadLibrary("ai-chat")
                init()
                _state.value = InferenceEngine.State.Initialized
                Log.i(TAG, "native ready")
            } catch (e: Exception) {
                Log.e(TAG, "native init failed", e)
                _state.value = InferenceEngine.State.Error(e)
            }
        }
    }

    // ── model lifecycle ───────────────────────────────────────────────────────
    override suspend fun loadModel(pathToModel: String) = withContext(llamaDispatcher) {
        check(_state.value is InferenceEngine.State.Initialized) {
            "loadModel requires Initialized, got ${_state.value}"
        }
        try {
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.LoadingModel
            val ctxSize = userPrefs.getCtxSize()
            Log.i(TAG, "load $pathToModel ctx=$ctxSize")
            load(pathToModel, ctxSize).let {
                if (it != 0) throw IllegalStateException("load() = $it")
            }
            prepare().let {
                if (it != 0) throw IllegalStateException("prepare() = $it")
            }
            _readyForSystemPrompt = true
            _cancelGeneration     = false
            _state.value = InferenceEngine.State.ModelReady
            Log.i(TAG, "model ready")
        } catch (e: Exception) {
            _state.value = InferenceEngine.State.Error(e)
            throw e
        }
    }

    override suspend fun setSystemPrompt(prompt: String) = withContext(llamaDispatcher) {
        check(_readyForSystemPrompt && _state.value is InferenceEngine.State.ModelReady)
        try {
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.ProcessingSystemPrompt
            processSystemPrompt(prompt).let {
                if (it != 0) throw IllegalStateException("processSystemPrompt() = $it")
            }
            _state.value = InferenceEngine.State.ModelReady
            Log.i(TAG, "system prompt set")
        } catch (e: Exception) {
            _state.value = InferenceEngine.State.Error(e)
            throw e
        }
    }

    // ── generation ────────────────────────────────────────────────────────────
    override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> = flow {
        require(message.isNotEmpty())
        check(_state.value is InferenceEngine.State.ModelReady)
        try {
            _cancelGeneration = false
            _state.value = InferenceEngine.State.ProcessingUserPrompt
            val temperature = userPrefs.getTemperature()
            val topK        = userPrefs.getTopK()
            val topP        = userPrefs.getTopP()
            processUserPrompt(message, temperature, topK, topP).let {
                if (it != 0) {
                    _state.value = InferenceEngine.State.ModelReady
                    return@flow
                }
            }
            _state.value = InferenceEngine.State.Generating
            var n = 0
            while (!_cancelGeneration && n < predictLength) {
                val piece = generateNextToken() ?: break
                if (piece.isNotEmpty()) emit(piece)
                n++
            }
            _state.value = InferenceEngine.State.ModelReady
        } catch (e: CancellationException) {
            _state.value = InferenceEngine.State.ModelReady
            throw e
        } catch (e: Exception) {
            _state.value = InferenceEngine.State.Error(e)
            throw e
        }
    }.flowOn(llamaDispatcher)

    // ── bench ─────────────────────────────────────────────────────────────────
    override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String =
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.ModelReady) {
                "bench requires ModelReady, got ${_state.value}"
            }
            _state.value = InferenceEngine.State.Benchmarking
            try {
                benchModel(pp, tg, pl, nr)
            } finally {
                _state.value = InferenceEngine.State.ModelReady
            }
        }

    // ── cleanUp — handles ALL states, always lands on Initialized ─────────────
    override fun cleanUp() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            _readyForSystemPrompt = false
            when (_state.value) {
                is InferenceEngine.State.Uninitialized,
                is InferenceEngine.State.Initializing  -> { /* nothing allocated yet */ }
                is InferenceEngine.State.Initialized   -> { /* already clean */ }
                else -> {
                    _state.value = InferenceEngine.State.UnloadingModel
                    unload()
                }
            }
            _state.value = InferenceEngine.State.Initialized
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
            _state.value = InferenceEngine.State.Uninitialized
        }
        llamaScope.cancel()
    }
}