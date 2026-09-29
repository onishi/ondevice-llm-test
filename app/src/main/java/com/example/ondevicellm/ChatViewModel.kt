package com.example.ondevicellm

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import java.io.File

enum class Role { USER, MODEL, ERROR }

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val streaming: Boolean = false,
    val stats: String? = null,
)

enum class BackendType { GPU, CPU }

sealed interface ModelState {
    data object NotLoaded : ModelState
    data class Copying(val name: String, val progress: Float) : ModelState
    data class Loading(val name: String, val backend: BackendType) : ModelState
    data class Ready(val name: String, val backend: BackendType, val loadMillis: Long) : ModelState
    data class Error(val message: String) : ModelState
}

const val DEFAULT_SYSTEM_PROMPT = "あなたは親切なアシスタントです。日本語で答えてください。"

data class ChatUiState(
    val modelState: ModelState = ModelState.NotLoaded,
    val availableModels: List<File> = emptyList(),
    val preferredBackend: BackendType = BackendType.GPU,
    val messages: List<ChatMessage> = emptyList(),
    val generating: Boolean = false,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    // ネイティブ呼び出しは直列化する
    private val engineDispatcher = Dispatchers.IO.limitedParallelism(1)

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var generationJob: Job? = null
    private var nextId = 0L
    @Volatile private var cancelRequested = false

    /** adb push 先 (/sdcard/Android/data/<pkg>/files) */
    val externalModelDir: File? = app.getExternalFilesDir(null)

    /** ファイルピッカーから取り込んだモデルの置き場所 */
    private val importedModelDir = File(app.filesDir, "models").apply { mkdirs() }

    private val prefs = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    init {
        _state.update {
            it.copy(systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, null) ?: DEFAULT_SYSTEM_PROMPT)
        }
        refreshModels()
        // 前回使ったモデル、なければモデルが 1 つだけのときにそれを読み込む
        val models = _state.value.availableModels
        val lastUsed = prefs.getString(KEY_LAST_MODEL, null)
        (models.firstOrNull { it.name == lastUsed } ?: models.singleOrNull())?.let { loadModel(it) }
    }

    fun refreshModels() {
        val files = listOfNotNull(externalModelDir, importedModelDir)
            .flatMap { dir -> dir.listFiles()?.toList().orEmpty() }
            .filter { it.isFile && it.name.endsWith(".litertlm") }
            .sortedBy { it.name }
        _state.update { it.copy(availableModels = files) }
    }

    fun setPreferredBackend(backend: BackendType) {
        _state.update { it.copy(preferredBackend = backend) }
        val current = _state.value.modelState
        if (current is ModelState.Ready && current.backend != backend) {
            _state.value.availableModels.firstOrNull { it.name == current.name }?.let { loadModel(it) }
        }
    }

    fun importModel(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: "model.litertlm"
        val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L

        viewModelScope.launch {
            val dest = File(importedModelDir, name)
            try {
                _state.update { it.copy(modelState = ModelState.Copying(name, 0f)) }
                withContext(Dispatchers.IO) {
                    val tmp = File(importedModelDir, "$name.part")
                    resolver.openInputStream(uri)!!.use { input ->
                        tmp.outputStream().use { output ->
                            val buf = ByteArray(1 shl 20)
                            var copied = 0L
                            var lastReport = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                copied += n
                                if (size > 0 && copied - lastReport > 32L shl 20) {
                                    lastReport = copied
                                    _state.update {
                                        it.copy(modelState = ModelState.Copying(name, copied.toFloat() / size))
                                    }
                                }
                            }
                        }
                    }
                    tmp.renameTo(dest)
                }
                refreshModels()
                loadModel(dest)
            } catch (e: Exception) {
                Log.e(TAG, "import failed", e)
                _state.update { it.copy(modelState = ModelState.Error("取り込みに失敗: ${e.message}")) }
            }
        }
    }

    fun loadModel(file: File) {
        stopGeneration()
        val backend = _state.value.preferredBackend
        viewModelScope.launch {
            _state.update {
                it.copy(modelState = ModelState.Loading(file.name, backend), messages = emptyList())
            }
            val result = withContext(engineDispatcher) {
                releaseEngine()
                runCatching { initEngine(file, backend) }
                    .recoverCatching { e ->
                        // GPU が使えない端末では CPU にフォールバック
                        if (backend != BackendType.GPU) throw e
                        Log.w(TAG, "GPU init failed, falling back to CPU", e)
                        releaseEngine()
                        initEngine(file, BackendType.CPU)
                    }
            }
            result.onSuccess { (usedBackend, millis) ->
                prefs.edit().putString(KEY_LAST_MODEL, file.name).apply()
                _state.update {
                    it.copy(modelState = ModelState.Ready(file.name, usedBackend, millis))
                }
            }.onFailure { e ->
                Log.e(TAG, "load failed", e)
                _state.update { it.copy(modelState = ModelState.Error("読み込みに失敗: ${e.message}")) }
            }
        }
    }

    private fun initEngine(file: File, backend: BackendType): Pair<BackendType, Long> {
        val start = System.currentTimeMillis()
        val config = EngineConfig(
            modelPath = file.absolutePath,
            backend = when (backend) {
                BackendType.GPU -> Backend.GPU()
                BackendType.CPU -> Backend.CPU()
            },
            cacheDir = getApplication<Application>().cacheDir.path,
        )
        val e = Engine(config)
        try {
            e.initialize()
            engine = e
            conversation = e.createConversation(newConversationConfig())
        } catch (t: Throwable) {
            e.close()
            engine = null
            throw t
        }
        return backend to (System.currentTimeMillis() - start)
    }

    private fun newConversationConfig() = ConversationConfig(
        systemInstruction = _state.value.systemPrompt.takeIf { it.isNotBlank() }?.let { Contents.of(it) },
        // seed を指定しないと毎回同じ応答になるので、会話ごとに変える
        samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8, seed = Random.nextInt()),
        thinkingConfig = ThinkingConfig(enableThinking = false),
    )

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _state.value.generating) return
        if (_state.value.modelState !is ModelState.Ready) return

        val userMsg = ChatMessage(nextId++, Role.USER, prompt)
        val replyId = nextId++
        _state.update {
            it.copy(
                messages = it.messages + userMsg + ChatMessage(replyId, Role.MODEL, "", streaming = true),
                generating = true,
            )
        }

        cancelRequested = false
        generationJob = viewModelScope.launch {
            val conv = conversation ?: return@launch
            val start = System.currentTimeMillis()
            var firstTokenAt = 0L
            var chunks = 0
            // Qwen3 の .litertlm に同梱のテンプレートは enable_thinking を無視するので、
            // ユーザー発話末尾のソフトスイッチで思考を止める (システムプロンプトに入れると 2 ターン目以降効かない)
            val modelName = (_state.value.modelState as? ModelState.Ready)?.name.orEmpty()
            val input = if (modelName.contains("qwen3", ignoreCase = true)) "$prompt /no_think" else prompt
            conv.sendMessageAsync(input)
                .catch { e ->
                    if (cancelRequested) return@catch
                    Log.e(TAG, "generation failed", e)
                    _state.update {
                        it.copy(messages = it.messages + ChatMessage(nextId++, Role.ERROR, "エラー: ${e.message}"))
                    }
                }
                .collect { chunk ->
                    if (chunks++ == 0) firstTokenAt = System.currentTimeMillis()
                    val delta = chunk.toString()
                    updateReply(replyId) { it.copy(text = it.text + delta) }
                }
            val end = System.currentTimeMillis()
            val stats = if (chunks > 0) {
                val ttft = firstTokenAt - start
                val decodeSec = (end - firstTokenAt).coerceAtLeast(1) / 1000.0
                "初回 %.2fs · %.1f chunk/s · 合計 %.1fs".format(ttft / 1000.0, (chunks - 1) / decodeSec, (end - start) / 1000.0)
            } else null
            updateReply(replyId) { it.copy(streaming = false, stats = stats) }
            _state.update { it.copy(generating = false) }
        }
    }

    fun stopGeneration() {
        if (!_state.value.generating) return
        cancelRequested = true
        conversation?.cancelProcess()
    }

    /** システムプロンプトは会話の作成時にしか渡せないので、変更したら会話をリセットする */
    fun setSystemPrompt(prompt: String) {
        prefs.edit().putString(KEY_SYSTEM_PROMPT, prompt).apply()
        _state.update { it.copy(systemPrompt = prompt) }
        if (engine != null) resetConversation()
    }

    fun resetConversation() {
        stopGeneration()
        viewModelScope.launch {
            generationJob?.join()
            withContext(engineDispatcher) {
                conversation?.close()
                conversation = engine?.createConversation(newConversationConfig())
            }
            _state.update { it.copy(messages = emptyList()) }
        }
    }

    private fun updateReply(id: Long, transform: (ChatMessage) -> ChatMessage) {
        _state.update { s ->
            s.copy(messages = s.messages.map { if (it.id == id) transform(it) else it })
        }
    }

    private fun releaseEngine() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
    }

    override fun onCleared() {
        conversation?.cancelProcess()
        releaseEngine()
    }

    companion object {
        private const val TAG = "OnDeviceLlm"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_LAST_MODEL = "last_model"
    }
}
