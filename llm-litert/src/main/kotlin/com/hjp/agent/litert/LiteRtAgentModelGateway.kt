package com.hjp.agent.litert

import com.hjp.agent.contract.ModelWorkflowNote
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import com.hjp.agent.contract.AgentModelGateway
import com.hjp.agent.contract.AgentModelSession
import com.hjp.agent.contract.FinalAnswerInput
import com.hjp.agent.contract.ModelDecision
import com.hjp.agent.contract.ModelInput
import com.hjp.agent.contract.ModelSessionConfig
import com.hjp.agent.contract.ModelToolCall
import com.hjp.agent.contract.ModelToolResponse
import com.hjp.tool.contract.ToolContract
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

enum class LiteRtBackendPreference { GPU_THEN_CPU, CPU_ONLY }

class LiteRtAgentModelGateway(
    private val modelFile: File,
    private val cacheDirectory: File,
    private val backendPreference: LiteRtBackendPreference = LiteRtBackendPreference.GPU_THEN_CPU,
    /**
     * Where the artefact's own lifecycle is recorded.
     *
     * Engine initialisation is the one thing no decorator above this class can see: a backend that
     * fails and is silently retried on CPU, or an artefact that never loads at all, both look
     * identical from the outside. Counting it here is what makes "the model loaded" an observation.
     * Defaults to a private instance so existing callers are unchanged.
     */
    private val counters: com.hjp.agent.contract.AgentRuntimeCounters =
        com.hjp.agent.contract.AgentRuntimeCounters(),
) : AgentModelGateway {
    private val engineMutex = Mutex()
    private var engine: Engine? = null

    /** A real `.litertlm` artefact runs behind this boundary. */
    override val boundaryKind = com.hjp.agent.contract.ModelBoundaryKind.ACTUAL_MODEL

    override suspend fun openSession(config: ModelSessionConfig): AgentModelSession {
        require(modelFile.isFile && modelFile.canRead()) {
            "LiteRT-LM model not found: ${modelFile.absolutePath}"
        }
        val activeEngine = requireEngine()
        val nativeTools = config.toolCatalog.contractsByModelName.values
            .sortedBy { it.modelName }
            .map { tool(ContractOpenApiTool(it)) }
        val conversationConfig = ConversationConfig(
            systemInstruction = Contents.of(config.systemInstruction),
            samplerConfig = SamplerConfig(
                topK = config.samplingProfile.topK,
                topP = config.samplingProfile.topP,
                temperature = config.samplingProfile.temperature.toDouble(),
            ),
            tools = nativeTools,
            automaticToolCalling = false,
        )
        val factory = suspend {
            withContext(Dispatchers.Default) { activeEngine.createConversation(conversationConfig) }
        }
        return LiteRtAgentModelSession(factory, factory(), config.toolCatalog.revision)
    }

    private suspend fun requireEngine(): Engine = engineMutex.withLock {
        engine?.let { return@withLock it }
        cacheDirectory.mkdirs()
        val backends = when (backendPreference) {
            LiteRtBackendPreference.GPU_THEN_CPU -> listOf(Backend.GPU(), Backend.CPU())
            LiteRtBackendPreference.CPU_ONLY -> listOf(Backend.CPU())
        }
        var lastError: Throwable? = null
        for ((index, backend) in backends.withIndex()) {
            var candidate: Engine? = null
            counters.recordModelLoadAttempt()
            // Anything after the first backend is a fallback. Recorded, because "the model loaded"
            // and "the model loaded on the second choice after the GPU refused it" are different
            // facts about a device, and only one of them predicts the latency that follows.
            if (index > 0) counters.recordModelBackendFallback()
            try {
                candidate = Engine(EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backend,
                    cacheDir = cacheDirectory.absolutePath,
                ))
                withContext(Dispatchers.Default) { candidate.initialize() }
                engine = candidate
                counters.recordModelLoadSuccess()
                return@withLock candidate
            } catch (cancelled: CancellationException) {
                candidate?.close()
                throw cancelled
            } catch (error: Throwable) {
                candidate?.close()
                counters.recordModelLoadFailure()
                Log.w(TAG, "LiteRT-LM backend initialization failed: $backend", error)
                lastError = error
            }
        }
        throw IllegalStateException("LiteRT-LM engine initialization failed", lastError)
    }

    override fun close() {
        engine?.close()
        engine = null
    }

    private companion object {
        const val TAG = "HjpLiteRt"
    }
}

/**
 * The native conversation is an execution cache, not the source of truth.
 *
 * LiteRT re-renders every stored turn through the model chat template on each send, so the agent
 * core decides what history to restate and when to start a fresh conversation; this class only
 * renders what it is given and honours [resetConversation].
 */
private class LiteRtAgentModelSession(
    private val conversationFactory: suspend () -> Conversation,
    initialConversation: Conversation,
    override val catalogRevision: String,
) : AgentModelSession {
    private var conversation: Conversation = initialConversation

    override suspend fun decide(input: ModelInput): ModelDecision = when (input) {
        is ModelInput.User -> withContext(Dispatchers.Default) {
            runCatching { conversation.sendMessage(input.promptContext.render(input.text)).toDecision() }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    ModelDecision.Invalid("모델 도구 호출 형식을 해석하지 못했습니다.", retryable = true)
                }
        }
    }

    override suspend fun resetConversation() {
        val previous = conversation
        conversation = conversationFactory()
        runCatching { previous.close() }
    }

    override suspend fun continueWithToolResult(result: ModelToolResponse): ModelDecision =
        withContext(Dispatchers.Default) {
            val content = Content.ToolResponse(result.modelToolName, result.payload.toKotlinValue())
            runCatching { conversation.sendMessage(Message.tool(Contents.of(listOf(content)))).toDecision() }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    ModelDecision.Invalid("모델 도구 호출 형식을 해석하지 못했습니다.", retryable = true)
                }
        }

    /**
     * Delivers a workflow repair as a user-role message, not as a tool response.
     *
     * The default in [AgentModelSession] wraps the note as a `ModelToolResponse`, which this boundary
     * would then send as `Message.tool(...)`. That is a tool-role message for a call the native
     * `Conversation` has already completed and already been given a response for; the SDK defines no
     * ordering for it, so a runtime is free to reject it or attribute it to the wrong call. A
     * user-role message is a role the protocol does define at this point in the exchange.
     *
     * The note never reaches the app transcript or persistent memory — the kernel does not record it
     * as user input, so nothing the user reads or that a later turn recalls contains it.
     */
    override suspend fun continueWithWorkflowNote(note: ModelWorkflowNote): ModelDecision =
        withContext(Dispatchers.Default) {
            runCatching { conversation.sendMessage(note.text).toDecision() }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    ModelDecision.Invalid("모델 도구 호출 형식을 해석하지 못했습니다.", retryable = true)
                }
        }

    override fun streamFinal(input: FinalAnswerInput): Flow<String> = flowOf(input.draftText)

    private fun Message.toDecision(): ModelDecision {
        if (toolCalls.isNotEmpty()) {
            val calls = toolCalls.map { call ->
                val arguments = JsonObject(call.arguments.entries.associate { (key, value) ->
                    key to value.toJsonElement()
                })
                ModelToolCall(UUID.randomUUID().toString(), call.name, arguments)
            }
            return ModelDecision.ToolCalls(calls)
        }
        val text = toString()
        return if (text.isNotBlank()) ModelDecision.FinalCandidate(text)
        else ModelDecision.Invalid("모델이 응답을 생성하지 못했습니다.", retryable = true)
    }

    override fun close() = conversation.close()
}

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.mapNotNull { (key, value) ->
        (key as? String)?.let { it to value.toJsonElement() }
    }.toMap())
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}

private fun JsonElement.toKotlinValue(): Any? = when (this) {
    JsonNull -> null
    is JsonObject -> entries.associate { (key, value) -> key to value.toKotlinValue() }
    is JsonArray -> map { it.toKotlinValue() }
    is JsonPrimitive -> booleanOrNull ?: doubleOrNull ?: content
}

private class ContractOpenApiTool(private val contract: ToolContract) : OpenApiTool {
    override fun getToolDescriptionJsonString(): String = buildJsonObject {
        put("name", contract.modelName)
        put("description", contract.description)
        put("parameters", contract.inputSchema)
    }.toString()

    override fun execute(paramsJsonString: String): String = buildJsonObject {
        put("ok", false)
        put("error", "manual_tool_execution_required")
    }.toString()
}
