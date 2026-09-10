package com.example.hjp.agent

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import java.io.File

/**
 * 안드로이드용 [ChatEngineProvider] — LiteRT-LM 으로 모델을 찾아 연다.
 * 라우팅·프롬프트 규칙은 :core 의 turn 파이프라인에 있고, 여기는 로딩만 담당한다.
 */
class LiteRtChatEngineProvider(context: Context) : ChatEngineProvider {
    private val appContext = context.applicationContext

    override fun modelStatus(role: LlmRole): String =
        LiteRtLmChatEngine.modelStatus(appContext, role)

    override fun openShared(role: LlmRole): ChatEngine =
        LiteRtLmChatEngine.openShared(appContext, role)
}

class LiteRtLmChatEngine private constructor(
    private val engine: Engine,
    val backendName: String,
    override val loadedFileName: String,
) : ChatEngine, AutoCloseable {

    // 채팅 탭 전용 지속 대화 세션. 한 번 만들면 여러 턴에 걸쳐 유지되어
    // 모델이 이전 질문/답변을 기억한다(멀티턴). 단발성 generate()와 분리한다.
    private var chatConversation: Conversation? = null

    /**
     * 대화 맥락을 유지하며 답변을 생성한다. 같은 세션 동안 이전 턴을 기억한다.
     * 누적된 KV 캐시가 한도(maxNumTokens)를 넘어 생성이 실패하면 대화를 리셋하고
     * 이번 질문만 새 세션으로 한 번 재시도한다 — 이전 기억은 잃지만 앱은 죽지 않게.
     */
    @Synchronized
    fun generateWithHistory(prompt: String): String {
        val conversation = chatConversation ?: engine.createConversation().also { chatConversation = it }
        return try {
            finishGenerate(conversation.sendMessage(prompt))
        } catch (_: Throwable) {
            resetChat()
            val fresh = engine.createConversation().also { chatConversation = it }
            finishGenerate(fresh.sendMessage(prompt))
        }
    }

    /** 맥락 없는 단발성 생성(스모크 테스트 등). 채팅 대화 세션을 건드리지 않는다. */
    @Synchronized
    override fun generate(prompt: String): String {
        val conversation = engine.createConversation()
        try {
            return finishGenerate(conversation.sendMessage(prompt))
        } finally {
            conversation.close()
        }
    }

    /** 채팅 대화를 처음부터 다시 시작한다(이전 기억 삭제). "새 대화" 버튼이 호출. */
    @Synchronized
    fun resetChat() {
        chatConversation?.close()
        chatConversation = null
    }

    private fun finishGenerate(response: Any?): String {
        // FunctionGemma처럼 대화용이 아닌 모델은 <pad> 같은 특수 토큰만 뱉기도 한다 — 걸러낸다
        val cleaned = response?.toString().orEmpty()
            .replace(Regex("<pad>|<eos>|<bos>|<end_of_turn>|<start_of_turn>|<unk>"), "")
            .trim()
        // 소형 모델이 같은 문자열을 무한 반복하는 degenerate 출력도 무효 처리한다 ("</h4></h4>..." 등)
        if (Regex("(.{3,40})\\1{4,}").containsMatchIn(cleaned)) return EMPTY_LLM_RESPONSE
        return cleaned.ifBlank { EMPTY_LLM_RESPONSE }
    }

    override fun close() {
        chatConversation?.close()
        chatConversation = null
        engine.close()
    }

    companion object {
        // 엔진을 열고 닫을 때마다 네이티브 메모리가 조금씩 새서(LiteRT-LM), 질문을 반복하면
        // 저사양 기기에서 앱이 통째로 죽는다. 그래서 엔진 하나를 로드해 두고 계속 재사용한다.
        private var shared: LiteRtLmChatEngine? = null
        private var sharedModelPath: String? = null

        fun modelStatus(context: Context, role: LlmRole): String =
            locateModel(context, role)?.absolutePath ?: "missing: ${expectedModelLocations(context, role).joinToString(" | ")}"

        /**
         * 이미 로드된 공유 엔진이 있으면 그 채팅 대화 세션만 초기화한다(멀티턴 기억 삭제).
         * 엔진이 아직 로드되지 않았으면 아무 것도 하지 않는다 — 리셋하려다 모델을 새로 로드하지 않게.
         */
        @Synchronized
        fun resetSharedChat() {
            shared?.resetChat()
        }

        /**
         * 공유 엔진을 돌려준다. 같은 모델이면 이미 로드된 엔진을 재사용하고,
         * 다른 모델이 요청되면 기존 것을 닫고 새로 연다 (한 번에 LLM 하나만 메모리에 유지).
         * 호출한 쪽에서 close()하지 말 것.
         */
        @Synchronized
        fun openShared(context: Context, role: LlmRole): LiteRtLmChatEngine {
            val model = locateModel(context, role)
                ?: throw IllegalStateException("${role.displayName} model not found. Put it at app/src/main/assets/${role.assetPath} or files/models/${role.fileNames.joinToString(" | ")}.")
            shared?.let { existing ->
                if (sharedModelPath == model.absolutePath) return existing
                existing.close()
                shared = null
                sharedModelPath = null
            }
            val engine = open(context, role)
            shared = engine
            sharedModelPath = model.absolutePath
            return engine
        }

        fun open(context: Context, role: LlmRole): LiteRtLmChatEngine {
            val model = locateModel(context, role)
                ?: throw IllegalStateException("${role.displayName} model not found. Put it at app/src/main/assets/${role.assetPath} or files/models/${role.fileNames.joinToString(" | ")}.")
            Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
            var gpuEngine: LiteRtLmChatEngine? = null
            return try {
                gpuEngine = create(context, model.absolutePath, Backend.GPU(), "GPU", model.name)
                // 일부 기기(특히 삼성)는 OpenCL 라이브러리가 없어도 엔진 초기화는 통과하고
                // 실제 생성(sendMessage) 시점에야 실패한다 — 그래서 초기화 성공만으로는 GPU가
                // 진짜 되는지 알 수 없어 짧은 시험 생성으로 확인한다.
                gpuEngine.generate("Hi")
                gpuEngine
            } catch (gpuError: Throwable) {
                gpuEngine?.close()
                create(context, model.absolutePath, Backend.CPU(), "CPU fallback after ${gpuError.javaClass.simpleName}", model.name)
            }
        }

        private fun locateModel(context: Context, role: LlmRole): File? {
            expectedModelFiles(context, role).firstOrNull { it.exists() && it.length() > 0L }?.let { return it }
            return if (assetExists(context, role.assetPath)) materializeAsset(context, role.assetPath, role.fileName) else null
        }

        private fun expectedModelFiles(context: Context, role: LlmRole): List<File> {
            val external = context.getExternalFilesDir("models")
            return role.fileNames.flatMap { fileName ->
                listOfNotNull(
                    File(File(context.filesDir, "models"), fileName),
                    File(context.filesDir, fileName),
                    external?.let { File(it, fileName) },
                )
            }
        }

        private fun expectedModelLocations(context: Context, role: LlmRole): List<String> =
            expectedModelFiles(context, role).map { it.absolutePath } + "app/src/main/assets/${role.assetPath}"

        private fun create(
            context: Context,
            modelPath: String,
            backend: Backend,
            backendName: String,
            loadedFileName: String,
        ): LiteRtLmChatEngine {
            val config = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                cacheDir = context.cacheDir.absolutePath,
                // 멀티턴 기억을 담을 수 있도록 KV 캐시를 잡는다. 한 턴이 명함 컨텍스트(~300)
                // + 질문 + 답변(~300)으로 ~650토큰이라, 2048이면 직전 1~2턴을 기억한다.
                // 더 키우면 기억은 늘지만 KV 캐시를 미리 크게 잡아 저사양 기기에서 죽을 위험이 커진다
                // (RAM 4GB S8이 이 이유로 실패했었음 — 8GB 기기 기준값). 한도 초과 시
                // generateWithHistory()가 대화를 리셋하고 재시도하므로 앱이 죽지는 않는다.
                maxNumTokens = 2048,
            )
            val engine = Engine(config)
            engine.initialize()
            return LiteRtLmChatEngine(engine, backendName, loadedFileName)
        }

        private fun assetExists(context: Context, assetPath: String): Boolean =
            try {
                context.assets.open(assetPath).close()
                true
            } catch (_: Throwable) {
                false
            }

        private fun materializeAsset(context: Context, assetPath: String, fileName: String): File {
            val dir = File(context.filesDir, "models").apply { mkdirs() }
            val out = File(dir, fileName)
            if (out.exists() && out.length() > 0L) return out
            context.assets.open(assetPath).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            return out
        }
    }
}
