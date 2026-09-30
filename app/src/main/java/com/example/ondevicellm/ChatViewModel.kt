package com.example.ondevicellm

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

enum class Role { USER, MODEL, ERROR }

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val streaming: Boolean = false,
    val stats: String? = null,
    val image: Bitmap? = null,
    /** モデルの読み込みが終わるのを待っている応答 */
    val queued: Boolean = false,
)

sealed interface ModelState {
    data object NotLoaded : ModelState
    data class Copying(val name: String, val progress: Float) : ModelState
    data class Loading(val name: String, val detail: String) : ModelState
    data class Ready(
        val name: String,
        /** "GPU" "CPU" "AICore · nano-v3" など */
        val backend: String,
        val loadMillis: Long,
        val supportsImages: Boolean,
        /** モデルファイルで動いているとき、そのファイル (Gemini Nano なら null) */
        val file: File?,
    ) : ModelState
    data class Error(val message: String) : ModelState
}

const val DEFAULT_SYSTEM_PROMPT = "あなたは親切なアシスタントです。日本語で簡潔に答えてください。絵文字の使用は控えてください。"

/** 以前のデフォルト。これが保存されていたら「未編集」とみなして今のデフォルトを使う */
private val LEGACY_DEFAULT_SYSTEM_PROMPTS = setOf("あなたは親切なアシスタントです。日本語で答えてください。")

data class ChatUiState(
    val modelState: ModelState = ModelState.NotLoaded,
    val availableModels: List<File> = emptyList(),
    val preferredBackend: BackendType = BackendType.GPU,
    val messages: List<ChatMessage> = emptyList(),
    val generating: Boolean = false,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    /** 次の発話に添付する画像 */
    val pendingImage: PendingImage? = null,
)

class PendingImage(val preview: Bitmap, val jpeg: ByteArray)

/** 読み込み中に送られた発話。読み込みが終わったら生成を始める */
private class QueuedSend(val prompt: String, val image: PendingImage?, val replyId: Long)

/**
 * 読み込んだエンジンは Activity / ViewModel ではなくプロセスに持たせる。
 * 戻るボタンや履歴からのスワイプで画面が破棄されても、プロセスが残っていれば読み込み直さずに済む
 */
private object SessionHolder {
    var session: LlmSession? = null
    var ready: ModelState.Ready? = null

    /**
     * エンジンの読み込み・生成・会話のリセットを直列化するロック。
     * 画面を開き直すと ViewModel が入れ替わるので、ViewModel ではなくプロセスで持つ
     * (前の画面の生成が止まりきる前に次の画面がリセットする、読み込みが二重に走る、を防ぐ)
     */
    val lock = Mutex()

    /** 最新の読み込み要求の番号。古い要求で開いたエンジンは使わずに閉じる */
    var loadToken = 0L
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var session: LlmSession?
        get() = SessionHolder.session
        set(value) { SessionHolder.session = value }
    private var generationJob: Job? = null
    private var queuedSend: QueuedSend? = null
    private var nextId = 0L
    @Volatile private var cancelRequested = false

    /** adb push 先 (/sdcard/Android/data/<pkg>/files) */
    val externalModelDir: File? = app.getExternalFilesDir(null)

    /** ファイルピッカーから取り込んだモデルの置き場所 */
    private val importedModelDir = File(app.filesDir, "models").apply { mkdirs() }

    private val prefs = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    init {
        _state.update {
            it.copy(
                systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, null)
                    ?.takeUnless { saved -> saved in LEGACY_DEFAULT_SYSTEM_PROMPTS }
                    ?: DEFAULT_SYSTEM_PROMPT,
                preferredBackend = prefs.getString(KEY_BACKEND, null)
                    ?.let { name -> BackendType.entries.firstOrNull { b -> b.name == name } }
                    ?: BackendType.GPU,
            )
        }
        refreshModels()
        // 同じプロセスで読み込み済みなら使い回す (画面側の会話は消えているので、エンジン側の履歴も捨てる)
        val alive = SessionHolder.ready
        if (alive != null && session != null) {
            _state.update { it.copy(modelState = alive) }
            resetConversation()
        } else {
            // 前回使ったモデル、なければモデルファイルが 1 つだけのときにそれを読み込む
            val models = _state.value.availableModels
            val lastUsed = prefs.getString(KEY_LAST_MODEL, null)
            when {
                lastUsed == GEMINI_NANO_NAME -> loadGeminiNano()
                else -> (models.firstOrNull { it.name == lastUsed } ?: models.singleOrNull())?.let { loadModel(it) }
            }
        }
    }

    fun refreshModels() {
        val files = listOfNotNull(externalModelDir, importedModelDir)
            .flatMap { dir -> dir.listFiles()?.toList().orEmpty() }
            .filter { it.isFile && it.name.endsWith(".litertlm") }
            .sortedBy { it.name }
        _state.update { it.copy(availableModels = files) }
    }

    fun setPreferredBackend(backend: BackendType) {
        prefs.edit().putString(KEY_BACKEND, backend.name).apply()
        // GPU を明示的に選び直したら、以前 GPU で失敗した記録を忘れてもう一度試す
        if (backend == BackendType.GPU) {
            val editor = prefs.edit()
            prefs.all.keys.filter { it.startsWith(KEY_GPU_FAILED) }.forEach { editor.remove(it) }
            editor.apply()
        }
        _state.update { it.copy(preferredBackend = backend) }
        val current = _state.value.modelState
        if (current is ModelState.Ready && current.file != null && current.backend != backend.name) {
            loadModel(current.file)
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
        // GPU の初期化に失敗したことのあるモデルは、毎回 GPU で失敗するのを待たずに最初から CPU で開く
        val gpuFailedKey = KEY_GPU_FAILED + file.name + ":" + file.length()
        val backend = _state.value.preferredBackend
            .let { if (it == BackendType.GPU && prefs.getBoolean(gpuFailedKey, false)) BackendType.CPU else it }
        open(file.name, backend.name, file) {
            val cacheDir = getApplication<Application>().cacheDir
            val systemPrompt = _state.value.systemPrompt
            withContext(Dispatchers.IO) {
                runCatching { LiteRtSession.open(file, backend, cacheDir, systemPrompt) to backend }
                    .recoverCatching { e ->
                        // GPU が使えない端末では CPU にフォールバック
                        if (backend != BackendType.GPU) throw e
                        Log.w(TAG, "GPU init failed, falling back to CPU", e)
                        prefs.edit().putBoolean(gpuFailedKey, true).apply()
                        LiteRtSession.open(file, BackendType.CPU, cacheDir, systemPrompt) to BackendType.CPU
                    }
                    .getOrThrow()
                    .let { (session, used) -> session to used.name }
            }
        }
    }

    fun loadGeminiNano() {
        open(GEMINI_NANO_NAME, "AICore", file = null) {
            val session = GeminiNanoSession.open(_state.value.systemPrompt) { progress ->
                _state.update { it.copy(modelState = ModelState.Loading(GEMINI_NANO_NAME, progress)) }
            }
            session to "AICore · ${session.baseModelName}"
        }
    }

    /** 今のセッションを閉じて、新しいセッションを開く */
    private fun open(
        name: String,
        backendLabel: String,
        file: File?,
        create: suspend () -> Pair<LlmSession, String>,
    ) {
        stopGeneration()
        val token = ++SessionHolder.loadToken
        viewModelScope.launch {
            generationJob?.join()
            _state.update {
                it.copy(modelState = ModelState.Loading(name, backendLabel), messages = emptyList(), generating = false)
            }
            SessionHolder.lock.withLock {
                // ロック待ちの間に新しい読み込みが来ていたら、そちらに任せる
                if (token != SessionHolder.loadToken) return@launch
                withContext(Dispatchers.IO) {
                    SessionHolder.ready = null
                    session?.close()
                    session = null
                }
                val start = System.currentTimeMillis()
                // 読み込み中に画面が閉じられても、開いたエンジンを取りこぼさずに閉じられるよう、キャンセルさせない
                val result = withContext(NonCancellable) { runCatching { create() } }
                if (!currentCoroutineContext().isActive || token != SessionHolder.loadToken) {
                    result.getOrNull()?.first?.let { stale -> withContext(NonCancellable + Dispatchers.IO) { stale.close() } }
                    return@launch
                }
                onOpened(result, name, file, start)
            }
        }
    }

    private fun onOpened(result: Result<Pair<LlmSession, String>>, name: String, file: File?, start: Long) {
        result
            .onSuccess { (opened, label) ->
                session = opened
                prefs.edit().putString(KEY_LAST_MODEL, name).apply()
                val ready = ModelState.Ready(
                    name = name,
                    backend = label,
                    loadMillis = System.currentTimeMillis() - start,
                    supportsImages = opened.supportsImages,
                    file = file,
                )
                SessionHolder.ready = ready
                _state.update {
                    it.copy(
                        modelState = ready,
                        pendingImage = it.pendingImage.takeIf { opened.supportsImages },
                    )
                }
                // 読み込み中に送られていた発話があれば、ここで生成を始める
                queuedSend?.let { q ->
                    queuedSend = null
                    updateReply(q.replyId) { it.copy(queued = false) }
                    generate(q.prompt, q.image?.takeIf { opened.supportsImages }, q.replyId)
                }
            }
            .onFailure { e ->
                Log.e(TAG, "load failed", e)
                queuedSend?.let { q ->
                    queuedSend = null
                    updateReply(q.replyId) {
                        it.copy(role = Role.ERROR, text = "モデルを読み込めなかったので応答できませんでした", streaming = false, queued = false)
                    }
                }
                _state.update { it.copy(modelState = ModelState.Error("読み込みに失敗: ${e.message}"), generating = false) }
            }
    }

    /** カメラやギャラリーの画像を縮小して、次の発話に添付する */
    fun attachImage(uri: Uri) {
        viewModelScope.launch {
            try {
                val image = withContext(Dispatchers.IO) { loadImage(uri) }
                _state.update { it.copy(pendingImage = image) }
            } catch (e: Exception) {
                Log.e(TAG, "image load failed", e)
                _state.update {
                    it.copy(messages = it.messages + ChatMessage(nextId++, Role.ERROR, "画像を読み込めませんでした: ${e.message}"))
                }
            }
        }
    }

    fun clearImage() {
        _state.update { it.copy(pendingImage = null) }
    }

    private fun loadImage(uri: Uri): PendingImage {
        val source = ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)
        // ImageDecoder は EXIF の向きを反映してくれる。長辺を MAX_IMAGE_SIDE に縮める
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (w, h) = info.size.width to info.size.height
            val scale = MAX_IMAGE_SIDE.toFloat() / maxOf(w, h)
            if (scale < 1f) decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val jpeg = ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
            it.toByteArray()
        }
        return PendingImage(bitmap, jpeg)
    }

    fun send(text: String) {
        val image = _state.value.pendingImage
        val prompt = text.trim().ifEmpty { if (image != null) "この画像について教えてください。" else "" }
        if (prompt.isEmpty() || _state.value.generating) return
        val loading = _state.value.modelState is ModelState.Loading
        if (_state.value.modelState !is ModelState.Ready && !loading) return

        val userMsg = ChatMessage(nextId++, Role.USER, prompt, image = image?.preview)
        val replyId = nextId++
        _state.update {
            it.copy(
                messages = it.messages + userMsg + ChatMessage(replyId, Role.MODEL, "", streaming = true, queued = loading),
                generating = true,
                pendingImage = null,
            )
        }
        if (loading) {
            queuedSend = QueuedSend(prompt, image, replyId)
        } else {
            generate(prompt, image, replyId)
        }
    }

    private fun generate(prompt: String, image: PendingImage?, replyId: Long) {
        cancelRequested = false
        generationJob = viewModelScope.launch {
            SessionHolder.lock.withLock { generateLocked(prompt, replyId, image) }
        }
    }

    private suspend fun generateLocked(prompt: String, replyId: Long, image: PendingImage?) {
        val current = session ?: return
        val start = System.currentTimeMillis()
        var firstTokenAt = 0L
        var chunks = 0
        current.generate(prompt, image)
            .catch { e ->
                if (cancelRequested) return@catch
                Log.e(TAG, "generation failed", e)
                _state.update {
                    it.copy(messages = it.messages + ChatMessage(nextId++, Role.ERROR, "エラー: ${e.message}"))
                }
            }
            .collect { delta ->
                if (chunks++ == 0) firstTokenAt = System.currentTimeMillis()
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

    fun stopGeneration() {
        // 読み込み待ちの発話は、応答の枠ごと取り消す
        queuedSend?.let { q ->
            queuedSend = null
            _state.update { s -> s.copy(messages = s.messages.filterNot { it.id == q.replyId }, generating = false) }
            return
        }
        if (!_state.value.generating) return
        cancelRequested = true
        session?.cancel()
    }

    /** システムプロンプトは会話の作成時にしか渡せないので、変更したら会話をリセットする */
    fun setSystemPrompt(prompt: String) {
        // デフォルトのままなら保存しない (デフォルトを変えたときに追従させるため)
        prefs.edit().apply {
            if (prompt == DEFAULT_SYSTEM_PROMPT) remove(KEY_SYSTEM_PROMPT) else putString(KEY_SYSTEM_PROMPT, prompt)
        }.apply()
        _state.update { it.copy(systemPrompt = prompt) }
        if (session != null) resetConversation()
    }

    fun resetConversation() {
        stopGeneration()
        viewModelScope.launch {
            generationJob?.join()
            val systemPrompt = _state.value.systemPrompt
            SessionHolder.lock.withLock {
                withContext(Dispatchers.IO) { session?.reset(systemPrompt) }
            }
            _state.update { it.copy(messages = emptyList(), pendingImage = null) }
        }
    }

    private fun updateReply(id: Long, transform: (ChatMessage) -> ChatMessage) {
        _state.update { s ->
            s.copy(messages = s.messages.map { if (it.id == id) transform(it) else it })
        }
    }

    /** エンジンは SessionHolder に残して次の画面で使い回すので、ここでは生成を止めるだけ */
    override fun onCleared() {
        session?.cancel()
    }

    companion object {
        private const val TAG = "OnDeviceLlm"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_LAST_MODEL = "last_model"
        private const val KEY_BACKEND = "backend"
        private const val KEY_GPU_FAILED = "gpu_failed:"
        private const val MAX_IMAGE_SIDE = 1024
        const val GEMINI_NANO_NAME = "Gemini Nano"
    }
}
