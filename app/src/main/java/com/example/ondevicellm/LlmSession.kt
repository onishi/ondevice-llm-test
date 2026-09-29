package com.example.ondevicellm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import java.io.File
import kotlin.random.Random

/** チャットの推論エンジン。LiteRT-LM (モデルファイル) と Gemini Nano (AICore) を同じ形で扱う */
interface LlmSession : AutoCloseable {
    val supportsImages: Boolean

    /** 応答を差分で流す */
    fun generate(prompt: String, image: PendingImage?): Flow<String>

    /** 生成中の応答を止める */
    fun cancel()

    /** 会話履歴を捨てて新しい会話を始める */
    fun reset(systemPrompt: String)
}

enum class BackendType { GPU, CPU }

class LiteRtSession private constructor(
    private val engine: Engine,
    private var conversation: Conversation,
    private val modelName: String,
    override val supportsImages: Boolean,
) : LlmSession {

    override fun generate(prompt: String, image: PendingImage?): Flow<String> {
        // Qwen3 の .litertlm に同梱のテンプレートは enable_thinking を無視するので、
        // ユーザー発話末尾のソフトスイッチで思考を止める (システムプロンプトに入れると 2 ターン目以降効かない)
        val input = if (modelName.contains("qwen3", ignoreCase = true)) "$prompt /no_think" else prompt
        val contents = if (image != null) {
            Contents.of(Content.ImageBytes(image.jpeg), Content.Text(input))
        } else {
            Contents.of(input)
        }
        return conversation.sendMessageAsync(contents).map { it.toString() }
    }

    override fun cancel() = conversation.cancelProcess()

    override fun reset(systemPrompt: String) {
        conversation.close()
        conversation = engine.createConversation(conversationConfig(systemPrompt))
    }

    override fun close() {
        conversation.close()
        engine.close()
    }

    companion object {
        private const val MAX_NUM_TOKENS = 4096

        /** 重いので IO スレッドで呼ぶ */
        @OptIn(ExperimentalApi::class)
        fun open(file: File, backend: BackendType, cacheDir: File, systemPrompt: String): LiteRtSession {
            // 画像非対応のモデルに visionBackend を指定すると会話の作成に失敗するので、先にモデルの対応状況を見る
            val supportsImages = runCatching {
                Capabilities(file.absolutePath).use { it.inputModalities().vision }
            }.getOrDefault(false)
            fun backendOf(type: BackendType) = when (type) {
                BackendType.GPU -> Backend.GPU()
                BackendType.CPU -> Backend.CPU()
            }
            val engine = Engine(
                EngineConfig(
                    modelPath = file.absolutePath,
                    backend = backendOf(backend),
                    visionBackend = if (supportsImages) backendOf(backend) else null,
                    // 省略するとモデルが対応する最大長の分の KV キャッシュを確保して CPU の読み込みが遅くなる。
                    // GPU はエンジン側で 4096 に抑えられるので、それに揃える
                    maxNumTokens = MAX_NUM_TOKENS,
                    cacheDir = cacheDir.path,
                )
            )
            try {
                engine.initialize()
                return LiteRtSession(engine, engine.createConversation(conversationConfig(systemPrompt)), file.name, supportsImages)
            } catch (t: Throwable) {
                engine.close()
                throw t
            }
        }

        private fun conversationConfig(systemPrompt: String) = ConversationConfig(
            systemInstruction = systemPrompt.takeIf { it.isNotBlank() }?.let { Contents.of(it) },
            // seed を指定しないと毎回同じ応答になるので、会話ごとに変える
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8, seed = Random.nextInt()),
            thinkingConfig = ThinkingConfig(enableThinking = false),
        )
    }
}

/**
 * ML Kit GenAI Prompt API 経由で端末内蔵の Gemini Nano を使う。
 * Prompt API は 1 問 1 答なので、会話履歴はアプリ側で持ってプロンプトに埋め込む。
 */
class GeminiNanoSession private constructor(
    private val model: GenerativeModel,
    private var systemPrompt: String,
    private val systemInstructionSupported: Boolean,
    val baseModelName: String,
) : LlmSession {

    override val supportsImages = true

    private val history = mutableListOf<Pair<String, String>>()
    private var seed = Random.nextInt()
    @Volatile private var cancelled = false

    override fun generate(prompt: String, image: PendingImage?): Flow<String> = flow {
        cancelled = false
        val text = TextPart(buildPrompt(prompt))
        val system = systemPrompt.takeIf { systemInstructionSupported && it.isNotBlank() }?.let { SystemInstruction(it) }
        val request = when {
            image != null && system != null -> generateContentRequest(system, ImagePart(image.preview), text) { configure() }
            image != null -> generateContentRequest(ImagePart(image.preview), text) { configure() }
            system != null -> generateContentRequest(system, text) { configure() }
            else -> generateContentRequest(text) { configure() }
        }
        val reply = StringBuilder()
        // 止めるときは上流の collect を打ち切る
        model.generateContentStream(request)
            .takeWhile { !cancelled }
            .collect { response ->
                val delta = response.candidates.firstOrNull()?.text.orEmpty()
                reply.append(delta)
                emit(delta)
            }
        history += (if (image != null) "[画像を添付] $prompt" else prompt) to reply.toString()
    }

    private fun com.google.mlkit.genai.prompt.GenerateContentRequest.Builder.configure() {
        temperature = 0.8f
        topK = 40
        seed = this@GeminiNanoSession.seed
    }

    /** 入力は 4000 トークン程度までなので、古い履歴から削って収める */
    private fun buildPrompt(prompt: String): String {
        val sb = StringBuilder()
        if (!systemInstructionSupported && systemPrompt.isNotBlank()) sb.append(systemPrompt).append("\n\n")
        val kept = ArrayDeque<Pair<String, String>>()
        var budget = HISTORY_CHAR_BUDGET
        for (turn in history.asReversed()) {
            budget -= turn.first.length + turn.second.length
            if (budget < 0) break
            kept.addFirst(turn)
        }
        if (kept.isNotEmpty()) {
            sb.append("以下はこれまでの会話です。\n\n")
            kept.forEach { (q, a) -> sb.append("ユーザー: ").append(q).append("\nアシスタント: ").append(a).append("\n\n") }
            sb.append("これを踏まえて、次のユーザーの発言に答えてください。\n\nユーザー: ")
        }
        sb.append(prompt)
        return sb.toString()
    }

    override fun cancel() {
        cancelled = true
    }

    override fun reset(systemPrompt: String) {
        this.systemPrompt = systemPrompt
        history.clear()
        seed = Random.nextInt()
    }

    override fun close() = model.close()

    companion object {
        private const val HISTORY_CHAR_BUDGET = 3000

        suspend fun open(systemPrompt: String, onProgress: (String) -> Unit): GeminiNanoSession {
            val model = Generation.getClient()
            try {
                when (model.checkStatus()) {
                    FeatureStatus.UNAVAILABLE -> throw IllegalStateException(
                        "この端末では Gemini Nano を使えません (非対応機種、または AICore の設定がまだ取得されていない可能性があります)"
                    )
                    FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> model.download().collect { status ->
                        when (status) {
                            is DownloadStatus.DownloadProgress ->
                                onProgress("ダウンロード中 ${status.totalBytesDownloaded / 1_000_000} MB")
                            is DownloadStatus.DownloadFailed -> throw status.e
                            else -> {}
                        }
                    }
                }
                onProgress("準備中")
                model.warmup()
                val name = runCatching { model.getBaseModelName() }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Gemini Nano"
                val systemOk = runCatching { model.isSystemPromptAvailable() }.getOrDefault(false)
                return GeminiNanoSession(model, systemPrompt, systemOk, name)
            } catch (t: Throwable) {
                model.close()
                throw t
            }
        }
    }
}
