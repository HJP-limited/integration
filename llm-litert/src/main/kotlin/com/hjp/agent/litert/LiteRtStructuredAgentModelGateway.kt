package com.hjp.agent.litert

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
import com.hjp.agent.contract.AgentIntent
import com.hjp.agent.contract.AgentAction
import com.hjp.agent.contract.ComposeChannel
import com.hjp.agent.contract.CapabilityActionPolicy
import com.hjp.agent.contract.ComposeContentRequest
import com.hjp.agent.contract.ComposeContentValidator
import com.hjp.agent.contract.ConversationContext
import com.hjp.agent.contract.GeneratedComposeContent
import com.hjp.agent.contract.RecipientType
import com.hjp.agent.contract.StageIntent
import com.hjp.agent.contract.StagedIntentCodec
import com.hjp.agent.contract.StructuredAgentModelGateway
import com.hjp.agent.contract.StructuredDecodeResult
import com.hjp.agent.contract.StructuredFinalRequest
import com.hjp.agent.contract.StructuredIntentPlan
import com.hjp.agent.contract.StagedDecode
import com.hjp.agent.contract.StageAttempt
import com.hjp.agent.contract.StageTransport
import com.hjp.agent.contract.StructuredModelResult
import com.hjp.agent.contract.StructuredStageEngine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class LiteRtStructuredAgentModelGateway(
    private val modelFile: File,
    private val cacheDirectory: File,
    private val backendPreference: LiteRtBackendPreference = LiteRtBackendPreference.GPU_THEN_CPU,
    private val stageTimeoutMillis: Long = DEFAULT_STAGE_TIMEOUT_MILLIS,
) : StructuredAgentModelGateway {
    private val engineMutex = Mutex()
    private val stageEngine = StructuredStageEngine(
        maxAttempts = MAX_ATTEMPTS,
        stageTimeoutMillis = stageTimeoutMillis,
    )
    private var engine: Engine? = null

    override suspend fun analyzeIntent(
        userText: String,
        conversation: ConversationContext,
    ): StructuredModelResult<StructuredIntentPlan> {
        val raw = mutableListOf<String>()
        val history = conversation.promptPrefix()
        val classification = decodeStage(
            system = STAGE1_SYSTEM,
            prompt = history + "사용자 원문: $userText",
            toolName = STAGE1_TOOL_NAME,
            tool = STAGE1_TOOL,
            raw = raw,
        ) { StagedIntentCodec.decodeClassification(it) }
        var classified = when (classification) {
            is StagedDecode.Success -> classification.value
            is StagedDecode.Failure -> return StructuredModelResult.Failure(
                "요청의 종류와 실행 여부를 구조화하지 못했습니다.",
                raw,
                classification.attempts,
            )
        }
        var classificationAttempts = classification.attempts
        val capabilityViolation = CapabilityActionPolicy.violation(userText, classified)
        if (capabilityViolation != null) {
            val originalIntent = classified.intent
            val capabilityRepair = decodeStage(
                system = STAGE1_SYSTEM,
                prompt = buildString {
                    appendLine("원본 사용자 요청: $userText")
                    appendLine("확정 intent: $originalIntent")
                    append("앱 capability 검사 결과: ${capabilityViolation.reason}. 이 동작은 지원하지 않습니다. intent를 유지하고 action=UNSUPPORTED만 제출하세요.")
                },
                toolName = STAGE1_TOOL_NAME,
                tool = STAGE1_TOOL,
                raw = raw,
                maxAttempts = 1,
            ) { arguments ->
                when (val decoded = StagedIntentCodec.decodeClassification(arguments)) {
                    is StructuredDecodeResult.Success -> if (
                        decoded.value.intent == originalIntent &&
                        decoded.value.action == AgentAction.UNSUPPORTED
                    ) decoded else StructuredDecodeResult.Failure(
                        listOf("capability repair must preserve intent and set UNSUPPORTED"),
                    )
                    is StructuredDecodeResult.Failure -> decoded
                }
            }
            if (capabilityRepair is StagedDecode.Success) {
                classified = capabilityRepair.value
                classificationAttempts += capabilityRepair.attempts
            }
        }
        if (classified.action != AgentAction.EXECUTE) {
            return StructuredModelResult.Success(
                StagedIntentCodec.nonExecutingPlan(classified),
                raw,
                classificationAttempts,
            )
        }
        val slotTool = stage2Tool(classified.intent)
        if (slotTool == null) {
            val decoded = StagedIntentCodec.decodeSlots(classified, buildJsonObject {})
            return when (decoded) {
                is StructuredDecodeResult.Success ->
                    StructuredModelResult.Success(decoded.value, raw, classificationAttempts)
                is StructuredDecodeResult.Failure ->
                    StructuredModelResult.Failure("실행 slot을 구성하지 못했습니다.", raw, classificationAttempts)
            }
        }
        val slots = decodeStage(
            system = STAGE2_SYSTEM,
            prompt = history + "확정 intent: ${classified.intent}\n사용자 원문: $userText",
            toolName = slotTool.name,
            tool = slotTool.tool,
            raw = raw,
        ) { StagedIntentCodec.decodeSlots(classified, it, userText) }
        return when (slots) {
            is StagedDecode.Success -> StructuredModelResult.Success(
                slots.value,
                raw,
                classificationAttempts + slots.attempts,
            )
            is StagedDecode.Failure -> {
                val semanticRepair = decodeStage(
                    system = STAGE1_SYSTEM,
                    prompt = buildString {
                        appendLine("원본 사용자 요청: $userText")
                        appendLine("확정 intent: ${classified.intent}")
                        appendLine("slot 단계가 찾지 못한 필수 정보: ${slots.errors.joinToString("; ")}")
                        append("정보를 추측하지 말고 intent를 유지한 채 action만 다시 판정하세요. 필수 정보가 없으면 CLARIFY입니다.")
                    },
                    toolName = STAGE1_TOOL_NAME,
                    tool = STAGE1_TOOL,
                    raw = raw,
                    maxAttempts = 1,
                ) { StagedIntentCodec.decodeClassification(it) }
                if (
                    semanticRepair is StagedDecode.Success &&
                    semanticRepair.value.intent == classified.intent &&
                    semanticRepair.value.action in setOf(AgentAction.CLARIFY, AgentAction.UNSUPPORTED)
                ) {
                    StructuredModelResult.Success(
                        StagedIntentCodec.nonExecutingPlan(semanticRepair.value),
                        raw,
                        classificationAttempts + slots.attempts + semanticRepair.attempts,
                    )
                } else {
                    StructuredModelResult.Failure(
                        "요청 실행에 필요한 정보를 구조화하지 못했습니다.",
                        raw,
                        classificationAttempts + slots.attempts +
                            (semanticRepair as? StagedDecode.Success)?.attempts.orZero(),
                    )
                }
            }
        }
    }

    private suspend fun <T> decodeStage(
        system: String,
        prompt: String,
        toolName: String,
        tool: OpenApiTool,
        raw: MutableList<String>,
        maxAttempts: Int = MAX_ATTEMPTS,
        decode: (JsonObject) -> StructuredDecodeResult<T>,
    ): StagedDecode<T> {
        val conversation = conversation(system, tool)
        return conversation.use { active ->
            val transport = object : StageTransport {
                private var last: Message? = null

                override suspend fun send(prompt: String): StageAttempt {
                    val message = sendPrompt(active, prompt)
                    last = message
                    return StageAttempt(message.toString(), expectedArguments(message, toolName))
                }

                override suspend fun repair(
                    errors: List<String>,
                    targetedMessageKo: String?,
                ): StageAttempt {
                    val previous = last ?: return StageAttempt("", null)
                    val message = correction(active, previous, toolName, errors, targetedMessageKo)
                    last = message
                    return StageAttempt(message.toString(), expectedArguments(message, toolName))
                }
            }
            stageEngine.run(
                toolName = toolName,
                prompt = prompt,
                transport = transport,
                rawOutputs = raw,
                attempts = maxAttempts,
                targetedRepair = stage1TargetedRepair(toolName),
                decode = decode,
            )
        }
    }

    /**
     * Stage 1 keeps a valid intent and only re-asks for the action. A generic repair message here
     * makes the model change a correct intent as well, which was measured as a regression.
     */
    private fun stage1TargetedRepair(toolName: String): (JsonObject?, List<String>) -> String? =
        { arguments, errors ->
            val fixedIntent = arguments?.string("intent")?.let { value ->
                StageIntent.entries.firstOrNull { it.name == value }
            }
            val currentAction = arguments?.string("action")?.let { value ->
                AgentAction.entries.firstOrNull { it.name == value }
            }
            val consistencyError = errors.any { error ->
                error == "GENERAL cannot use EXECUTE" || error == "ANSWER or PREVIEW requires GENERAL"
            }
            if (
                toolName == STAGE1_TOOL_NAME && fixedIntent != null &&
                currentAction != null && consistencyError
            ) {
                "intent=$fixedIntent 는 유효하며 고정합니다. " +
                    "잘못된 action=$currentAction 만 원본 요청에 맞게 고치세요."
            } else {
                null
            }
        }

    override suspend fun generateComposeContent(
        request: ComposeContentRequest,
    ): StructuredModelResult<GeneratedComposeContent> {
        val tool = if (request.channel == ComposeChannel.EMAIL) EMAIL_CONTENT_TOOL else SMS_CONTENT_TOOL
        val toolName = if (request.channel == ComposeChannel.EMAIL) EMAIL_TOOL_NAME else SMS_TOOL_NAME
        val conversation = conversation(CONTENT_SYSTEM, tool)
        return conversation.use {
            val raw = mutableListOf<String>()
            var message = sendPrompt(it, contentPrompt(request))
            repeat(MAX_ATTEMPTS) { attempt ->
                raw += message.toString()
                val arguments = expectedArguments(message, toolName)
                val content = arguments?.let { value ->
                    val body = value.string("body")
                    if (body == null) null else GeneratedComposeContent(
                        subject = value.string("subject"),
                        body = body,
                    )
                }
                val errors = if (content == null) {
                    listOf("$toolName native call with valid JSON is required")
                } else {
                    ComposeContentValidator.validate(request, content)
                }
                if (content != null && errors.isEmpty()) {
                    return StructuredModelResult.Success(content, raw, attempt + 1)
                }
                if (attempt + 1 < MAX_ATTEMPTS) {
                    message = correction(it, message, toolName, errors)
                }
            }
            StructuredModelResult.Failure(
                "메시지 제목과 본문을 안전하게 생성하지 못했습니다.",
                raw,
                MAX_ATTEMPTS,
            )
        }
    }

    override suspend fun generateFinalText(
        request: StructuredFinalRequest,
    ): StructuredModelResult<String> {
        val conversation = conversation(FINAL_SYSTEM, null)
        return conversation.use {
            val prompt = buildString {
                append(request.conversation.promptPrefix())
                appendLine("사용자 요청: ${request.originalUserText}")
                appendLine("확정 intent: ${request.plan.intent}")
                appendLine("실행 결과: ${request.completionHintKo}")
                append("위 사실만 사용해 한두 문장의 자연스러운 한국어 최종 답변을 작성하세요.")
            }
            val message = sendPrompt(it, prompt)
            val text = message.toString().trim()
            if (text.isBlank()) {
                StructuredModelResult.Failure("최종 답변을 생성하지 못했습니다.", listOf(text), 1)
            } else {
                StructuredModelResult.Success(text, listOf(text), 1)
            }
        }
    }

    private suspend fun conversation(
        system: String,
        openApiTool: OpenApiTool?,
    ): Conversation {
        val activeEngine = requireEngine()
        val tools = openApiTool?.let { listOf(tool(it)) }.orEmpty()
        return withContext(Dispatchers.Default) {
            activeEngine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(system),
                    samplerConfig = SamplerConfig(topK = 20, topP = 0.95, temperature = 0.2),
                    tools = tools,
                    automaticToolCalling = false,
                ),
            )
        }
    }

    private suspend fun sendPrompt(conversation: Conversation, prompt: String): Message =
        withContext(Dispatchers.Default) { conversation.sendMessage(prompt) }

    private suspend fun correction(
        conversation: Conversation,
        previous: Message,
        toolName: String,
        errors: List<String>,
        targetedMessage: String? = null,
    ): Message = withContext(Dispatchers.Default) {
        if (previous.toolCalls.any { it.name == toolName }) {
            val response = Content.ToolResponse(
                toolName,
                mapOf(
                    "status" to "rejected",
                    "errors" to errors,
                    "message" to (targetedMessage
                        ?: "모든 오류를 수정해 같은 schema로 한 번만 다시 제출하세요."),
                ),
            )
            conversation.sendMessage(Message.tool(Contents.of(listOf(response))))
        } else {
            conversation.sendMessage(
                "이전 응답은 허용되지 않습니다. $toolName 을 호출하고 다음 오류를 모두 수정하세요: " +
                    errors.joinToString("; "),
            )
        }
    }

    private fun expectedArguments(message: Message, name: String): JsonObject? {
        if (message.toolCalls.size != 1) return null
        val call = message.toolCalls.single()
        if (call.name != name) return null
        return JsonObject(call.arguments.entries.associate { (key, value) ->
            key to value.toJsonElement()
        })
    }

    private fun contentPrompt(request: ComposeContentRequest) = buildString {
        append(request.conversation.promptPrefix())
        appendLine("채널: ${request.channel}")
        appendLine("사용자 원문: ${request.originalUserText}")
        appendLine("본문 목표: ${request.contentGoal}")
        appendLine("수신자 표시 이름: ${request.recipientDisplayName.orEmpty()}")
        appendLine("수신자 회사: ${request.recipientCompany.orEmpty()}")
        appendLine("수신자 직함: ${request.recipientTitle.orEmpty()}")
        appendLine("요청한 말투: ${request.requestedTone.orEmpty()}")
        appendLine("사용자가 직접 제공한 사실: ${request.userProvidedFacts}")
        if (request.validationFeedback.isNotEmpty()) {
            appendLine("이전 오류: ${request.validationFeedback.joinToString("; ")}")
        }
        append("사용자가 제공한 사실만 사용하고 placeholder 없이 schema tool을 호출하세요.")
    }

    private suspend fun requireEngine(): Engine = engineMutex.withLock {
        engine?.let { return@withLock it }
        require(modelFile.isFile && modelFile.canRead()) {
            "LiteRT-LM model not found: ${modelFile.absolutePath}"
        }
        cacheDirectory.mkdirs()
        val backends = when (backendPreference) {
            LiteRtBackendPreference.GPU_THEN_CPU -> listOf(Backend.GPU(), Backend.CPU())
            LiteRtBackendPreference.CPU_ONLY -> listOf(Backend.CPU())
        }
        var lastError: Throwable? = null
        for (backend in backends) {
            var candidate: Engine? = null
            try {
                candidate = Engine(
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = backend,
                        cacheDir = cacheDirectory.absolutePath,
                    ),
                )
                withContext(Dispatchers.Default) { candidate.initialize() }
                engine = candidate
                return@withLock candidate
            } catch (error: Throwable) {
                candidate?.close()
                lastError = error
            }
        }
        throw IllegalStateException("LiteRT-LM structured engine initialization failed", lastError)
    }

    override fun close() {
        engine?.close()
        engine = null
    }

    companion object {
        /** A stalled native stage must fail the turn, not hang the UI. */
        const val DEFAULT_STAGE_TIMEOUT_MILLIS = 120_000L

        internal const val MAX_ATTEMPTS = 2
        internal const val STAGE1_TOOL_NAME = "submit_action"
        internal const val EMAIL_TOOL_NAME = "submit_email_content"
        internal const val SMS_TOOL_NAME = "submit_sms_content"

        private val STAGE1_TOOL = SchemaOpenApiTool(
            STAGE1_TOOL_NAME,
            "요청의 의미 유형과 action만 제출합니다.",
            stage1Schema(),
        )
        private val EMAIL_CONTENT_TOOL = SchemaOpenApiTool(
            EMAIL_TOOL_NAME,
            "사용자가 제공한 사실만 사용한 한국어 이메일 제목과 본문을 제출합니다.",
            objectSchema(
                listOf("subject", "body"),
                buildJsonObject {
                    putJsonObject("subject") { put("type", "string") }
                    putJsonObject("body") { put("type", "string") }
                },
            ),
        )
        private val SMS_CONTENT_TOOL = SchemaOpenApiTool(
            SMS_TOOL_NAME,
            "사용자가 제공한 사실만 사용한 한국어 문자 본문을 제출합니다.",
            objectSchema(
                listOf("body"),
                buildJsonObject { putJsonObject("body") { put("type", "string") } },
            ),
        )

        const val STAGE1_SYSTEM =
            "한국어 요청 하나의 intent와 action만 submit_action으로 분류하세요. " +
                "수신자가 있고 메일·문자를 작성해 달라면 작성 화면 실행이므로 해당 COMPOSE와 EXECUTE입니다. " +
                "예시·방법·설명 또는 먼저 보여 달라거나 화면을 열지 말라는 요청만 GENERAL/ANSWER 또는 PREVIEW입니다. " +
                "필수 수신자·시각·수정값 누락과 잘못된 주소는 해당 intent/CLARIFY입니다. " +
                "일정은 날짜와 시간 표현이 모두 있을 때만 EXECUTE이고 하나라도 없으면 CLARIFY입니다. " +
                "삭제·전화 걸기·날씨·실제 자동 전송은 UNSUPPORTED입니다. 초안이라는 단어만으로 PREVIEW로 분류하지 마세요. " +
                "대조: 수신자 없는 감사 이메일 작성은 COMPOSE_EMAIL/CLARIFY, 시간이 없는 일정은 " +
                "CREATE_CALENDAR_EVENT/CLARIFY, 필드·값 없는 수정은 UPDATE_CONTACT/CLARIFY, 명함 삭제는 UPDATE_CONTACT/UNSUPPORTED입니다." +
                " 내일 서울 날씨 조회는 GENERAL/UNSUPPORTED, 전화 걸기는 GENERAL/UNSUPPORTED, card ID 상세는 VIEW_CONTACT/EXECUTE입니다. " +
                "이름에 없는·오류가 포함돼도 존재를 추측하지 말고 CONTACT 실행으로 분류하세요. 잘못된 email/phone 형식은 해당 COMPOSE/CLARIFY입니다." +
                " 없는사람에게 이메일 작성은 COMPOSE_EMAIL/EXECUTE이고 test-at-example에게 이메일 작성은 COMPOSE_EMAIL/CLARIFY입니다." +
                " 명함을 찾아 달라는 요청은 SEARCH_CONTACT이고 상세를 보여 달라는 요청만 VIEW_CONTACT입니다." +
                " 실제 전송·자동 전송은 해당 COMPOSE/UNSUPPORTED이고 캘린더 삭제는 CREATE_CALENDAR_EVENT/UNSUPPORTED입니다."
        const val STAGE2_SYSTEM =
            "확정 intent에 필요한 slot만 사용자 원문 그대로 제출하세요. 날짜를 계산하거나 연락처를 추측하지 마세요. " +
                "일정 원문에 사람 이름이 있으면 contact_name을 반드시 포함하세요. 이름은 줄이지 말고 전체 원문을 보존하세요. " +
                "일정 title에서 일정·작성 화면·저장 완료 표현을 제거하고, content goal에서 화면·완료를 말하라는 에이전트 지시를 제외하세요."
        const val CONTENT_SYSTEM =
            "한국어 이메일 또는 문자 내용만 생성합니다. 반드시 제공된 schema tool을 호출하세요. " +
                "원문과 확정된 최소 연락처 정보 및 말투를 반영하세요. placeholder, 어색한 client님 호칭, " +
                "사용자가 제공하지 않은 사실·일정·약속, 실제 전송 완료 표현을 추가하지 마세요."
        const val FINAL_SYSTEM =
            "검증된 실행 결과만 설명하는 한국어 Android 명함 에이전트입니다. 작성 화면을 연 것을 전송 또는 저장 완료라고 말하지 마세요."
    }
}

private fun Int?.orZero(): Int = this ?: 0

private data class NamedStageTool(val name: String, val tool: OpenApiTool)

private class SchemaOpenApiTool(
    private val name: String,
    private val description: String,
    private val parameters: JsonObject,
) : OpenApiTool {
    override fun getToolDescriptionJsonString(): String = buildJsonObject {
        put("name", name)
        put("description", description)
        put("parameters", parameters)
    }.toString()

    override fun execute(paramsJsonString: String): String =
        """{"status":"manual_execution_required"}"""
}

private fun stage1Schema(): JsonObject = objectSchema(
    listOf("intent", "action"),
    buildJsonObject {
        put("intent", enumString(StageIntent.entries.map(Enum<*>::name)))
        put("action", enumString(AgentAction.entries.map(Enum<*>::name)))
    },
)

private fun stage2Tool(intent: StageIntent): NamedStageTool? {
    val text = { description: String -> buildJsonObject {
        put("type", "string")
        put("description", description)
    } }
    val definition = when (intent) {
        StageIntent.SEARCH_CONTACT -> Triple(
            "submit_search_slots",
            listOf("contact_name"),
            buildJsonObject { put("contact_name", text("검색할 원문 이름")) },
        )
        StageIntent.VIEW_CONTACT -> Triple(
            "submit_view_slots",
            listOf("recipient_type", "recipient_value"),
            buildJsonObject {
                put("recipient_type", enumString(listOf("CONTACT_NAME", "CARD_ID")))
                put("recipient_value", text("원문 이름 또는 card ID"))
            },
        )
        StageIntent.COMPOSE_EMAIL -> Triple(
            "submit_email_slots",
            listOf("recipient_type", "recipient_value", "content_goal"),
            buildJsonObject {
                put("recipient_type", enumString(listOf("CONTACT_NAME", "EMAIL", "CARD_ID")))
                put("recipient_value", text("원문 이름 또는 email"))
                put("content_goal", text("사용자가 제공한 사실과 전달 목적"))
                put("tone", text("명시적으로 요청한 말투"))
            },
        )
        StageIntent.COMPOSE_SMS -> Triple(
            "submit_sms_slots",
            listOf("recipient_type", "recipient_value", "content_goal"),
            buildJsonObject {
                put("recipient_type", enumString(listOf("CONTACT_NAME", "PHONE", "CARD_ID")))
                put("recipient_value", text("원문 이름 또는 전화번호"))
                put("content_goal", text("사용자가 제공한 사실과 전달 목적"))
                put("tone", text("명시적으로 요청한 말투"))
            },
        )
        StageIntent.CREATE_CALENDAR_EVENT -> Triple(
            "submit_calendar_slots",
            listOf("title", "date_expression", "time_expression", "attendee_type", "contact_name"),
            buildJsonObject {
                put("title", text("일정 제목"))
                put("date_expression", text("계산하지 않은 날짜 원문"))
                put("time_expression", text("계산하지 않은 시간 원문"))
                put("attendee_type", enumString(listOf("NONE", "CONTACT_NAME")))
                put("contact_name", text("원문 참석자 이름. attendee_type=NONE이면 빈 문자열"))
            },
        )
        StageIntent.UPDATE_CONTACT -> Triple(
            "submit_update_slots",
            listOf("contact_name", "update_field", "update_value"),
            buildJsonObject {
                put("contact_name", text("수정할 연락처 이름"))
                put(
                    "update_field",
                    enumString(
                        listOf(
                            "name", "name_en", "company", "department", "title", "industry",
                            "location", "phone", "mobile", "email", "address", "website", "memo",
                        ),
                    ),
                )
                put("update_value", text("사용자가 제공한 새 값"))
            },
        )
        StageIntent.GET_CURRENT_DATETIME, StageIntent.GENERAL -> return null
    }
    val name = definition.first
    return NamedStageTool(
        name,
        SchemaOpenApiTool(
            name,
            "$intent 실행에 필요한 slot만 제출합니다.",
            objectSchema(definition.second, definition.third),
        ),
    )
}

private fun objectSchema(required: List<String>, properties: JsonObject) = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
    put("properties", properties)
}

private fun enumString(values: List<String>) = buildJsonObject {
    put("type", "string")
    put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()

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
