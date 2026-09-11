package com.hjp.agent.core

import com.hjp.agent.contract.requestsAgentAction
import com.hjp.agent.contract.AgentEvent
import com.hjp.agent.contract.AgentIntent
import com.hjp.agent.contract.ComposeChannel
import com.hjp.agent.contract.ComposeContentRequest
import com.hjp.agent.contract.ConversationContext
import com.hjp.agent.contract.IntentRecipient
import com.hjp.agent.contract.ModelToolCall
import com.hjp.agent.contract.ModelToolResponse
import com.hjp.agent.contract.RecipientType
import com.hjp.agent.contract.StructuredAgentModelGateway
import com.hjp.agent.contract.StructuredFinalRequest
import com.hjp.agent.contract.StructuredIntentPlan
import com.hjp.agent.contract.StructuredModelResult
import com.hjp.agent.contract.TurnRoutePlan
import com.hjp.tool.contract.CatalogContext
import com.hjp.tool.contract.StandardToolErrorCodes
import com.hjp.tool.contract.ToolCatalogSnapshot
import com.hjp.tool.contract.ToolContract
import com.hjp.tool.contract.ToolError
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolExecutionResult
import com.hjp.tool.contract.ToolRegistry
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun interface AgentTraceSink {
    fun record(key: String, value: String)
}

class StructuredAgentKernel(
    private val registry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val policyEngine: ToolPolicyEngine,
    private val sessionStore: AgentSessionStore,
    private val observationMapper: ToolObservationMapper,
    private val environment: AgentRuntimeEnvironment,
    private val modelGateway: StructuredAgentModelGateway,
    private val workflowPolicy: AgentWorkflowPolicy = ProductionAgentWorkflowPolicy(),
    private val dateTimeParser: KoreanDateTimeParser = KoreanDateTimeParser(),
    private val maxToolCalls: Int = 6,
    private val traceSink: AgentTraceSink = AgentTraceSink { _, _ -> },
    private val contextSelector: ModelContextSelector = ModelContextSelector(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
    /** See [AgentKernel.contactDirectory]; defaults to the no-op so existing callers are unchanged. */
    private val contactDirectory: ContactDirectory = ContactDirectory.None,
) : AgentTurnEngine {
    override val mode = AgentKernelMode.STRUCTURED

    private val turnMutex = Mutex()

    override suspend fun resetSession(): Long = sessionStore.replace()

    override fun runTurn(userText: String): Flow<AgentEvent> = flow {
        val normalized = userText.trim()
        if (normalized.isEmpty()) {
            emit(AgentEvent.UserError("메시지를 입력해 주세요."))
            return@flow
        }
        turnMutex.withLock {
            val turnId = UUID.randomUUID().toString()
            emit(AgentEvent.TurnStarted(turnId))
            val session = sessionStore.getOrCreate()
            val generation = session.generation
            val permissions = environment.grantedPermissions()
            val snapshot = registry.snapshot(
                CatalogContext(
                    session.sessionId,
                    environment.localeTag,
                    permissions,
                    environment.deviceCapabilities(),
                ),
            )
            // Same store lookup as the ReAct kernel, for the same reason: the router decides who a
            // sentence is about from typed state, never by reaching for a repository itself.
            val directoryMatches = try {
                contactDirectory.resolve(ContactNameCandidates.candidates(normalized))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emptyList()
            }
            val turnContext = session.turnContext(
                normalized, snapshot.contractsByModelName.keys, null, directoryMatches,
            )
            // Same rule as the ReAct kernel: the action ledger holds requests, not every sentence.
            val requestsAction = DeterministicTurnRouter
                .act(turnContext)
                .requestsAgentAction
            sessionStore.beginConversationTurn(turnId, normalized, clockMillis(), requestsAction)
            val context = environment.toolContext(session.sessionId, turnId)
            trace("DECISION_SOURCE", "MODEL_INTENT")
            trace("TOOL_SEQUENCE_SOURCE", "WORKFLOW_ORCHESTRATOR")

            val route = DeterministicTurnRouter.route(turnContext)
            when (route) {
                is TurnRoutePlan.AnswerFromHistory -> {
                    finishWithoutTools(route.messageKo, turnId, TurnOutcome.COMPLETED, generation, ::emit)
                    return@withLock
                }
                is TurnRoutePlan.Clarify -> {
                    finishWithoutTools(
                        route.questionKo, turnId, TurnOutcome.NEEDS_CLARIFICATION, generation, ::emit,
                        detailKo = route.reason.name,
                    )
                    return@withLock
                }
                is TurnRoutePlan.Unsupported -> {
                    finishWithoutTools(route.messageKo, turnId, TurnOutcome.COMPLETED, generation, ::emit)
                    return@withLock
                }
                else -> Unit
            }
            val groundedCardId = (route as? TurnRoutePlan.GroundedContact)?.cardId
            val modelInput = when (route) {
                is TurnRoutePlan.Continue -> route.text
                is TurnRoutePlan.GroundedContact -> route.text
                else -> normalized
            }
            if (modelInput != normalized) trace("CONTACT_REFERENCE_RESOLVED", modelInput)
            val conversation = ConversationContext(contextSelector.select(ContextRequest(
                transcript = session.transcript.toList(),
                memory = session.conversationMemory,
                currentInput = modelInput,
                currentTurnId = turnId,
                capabilityContext = null,
                strategy = ConversationHistoryStrategy.DUPLICATE_BASELINE,
            )))

            val lease = TurnLease(generation) { sessionStore.getOrCreate().generation }
            val planResult = try {
                modelGateway.analyzeIntent(modelInput, conversation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                failEarly("구조화된 의도 분석을 실행하지 못했습니다.", turnId, generation, ::emit)
                return@withLock
            }
            // Stage 1 and every repair inside it may have run while `새 대화` replaced the session.
            if (lease.isStale()) return@withLock
            var plan = when (planResult) {
                is StructuredModelResult.Success -> {
                    planResult.rawOutputs.forEach { trace("RAW_MODEL_INTENT_OUTPUT", it) }
                    trace("INTENT_ATTEMPTS", planResult.attempts.toString())
                    planResult.value
                }
                is StructuredModelResult.Failure -> {
                    trace("INTENT_PARSE_FAILURE", planResult.safeReasonKo)
                    failEarly(planResult.safeReasonKo, turnId, generation, ::emit)
                    return@withLock
                }
            }
            plan = StructuredPlanPolicy.normalize(plan, normalized)
            if (groundedCardId != null &&
                plan.intent in CONTACT_REFERENCE_INTENTS &&
                plan.recipient.type == RecipientType.CONTACT_NAME
            ) {
                plan = plan.copy(
                    recipient = IntentRecipient(RecipientType.CARD_ID, groundedCardId),
                )
                trace("CONTACT_PROVENANCE_CARD_ID", groundedCardId)
            }
            trace("MODEL_INTENT", plan.intent.name)
            trace("MODEL_EXECUTE", plan.execute.toString())

            if (
                plan.execute && plan.recipient.type == RecipientType.CARD_ID &&
                plan.intent in PROVENANCE_REQUIRED_CARD_ID_INTENTS &&
                groundedCardId != plan.recipient.value
            ) {
                trace("VALIDATION_RESULT", "rejected")
                trace("REJECT_REASON", "STALE_OR_UNGROUNDED_CARD_ID")
                failEarly("이전 연락처 선택이 만료되었습니다. 연락처를 다시 검색해 주세요.", turnId, generation, ::emit)
                return@withLock
            }

            if (!plan.execute || plan.intent in NON_EXECUTING_INTENTS) {
                val message = when (plan.intent) {
                    AgentIntent.CLARIFY -> plan.clarificationQuestion
                        ?: "요청을 처리하는 데 필요한 정보를 더 알려주세요."
                    AgentIntent.UNSUPPORTED -> "요청한 기능은 현재 지원하지 않습니다."
                    else -> finalText(
                        normalized,
                        plan,
                        emptyList(),
                        "도구를 실행하지 않고 사용자의 질문이나 초안 요청에 답하세요.",
                        conversation,
                    )
                }
                val outcome = if (plan.intent == AgentIntent.CLARIFY) {
                    TurnOutcome.NEEDS_CLARIFICATION
                } else {
                    TurnOutcome.COMPLETED
                }
                finishWithoutTools(message, turnId, outcome, generation, ::emit)
                return@withLock
            }

            val runner = TurnRunner(
                lease = lease,
                userText = normalized,
                plan = plan,
                snapshot = snapshot,
                session = session,
                context = context,
                permissions = permissions,
                groundedCardId = groundedCardId,
                conversation = conversation,
                generation = generation,
                turnIdForMemory = turnId,
                emitEvent = ::emit,
            )
            val outcome = runner.execute()
            when (outcome) {
                is OrchestrationOutcome.Failure -> {
                    if (isCurrent(generation)) {
                        sessionStore.completeConversationTurn(
                            turnId, outcome.messageKo, TurnOutcome.FAILED,
                            runner.executedTools, clockMillis(), outcome.messageKo,
                        )
                    }
                    emit(AgentEvent.UserError(outcome.messageKo))
                }
                is OrchestrationOutcome.Completed -> {
                    var message = finalText(
                        normalized,
                        plan,
                        outcome.observations,
                        outcome.completionHintKo,
                        conversation,
                    )
                    when (val checked = runner.workflow.validateFinal(message)) {
                        WorkflowFinalValidationResult.Allow -> Unit
                        is WorkflowFinalValidationResult.Replace -> message = checked.safeMessageKo
                    }
                    trace("FINAL_RESPONSE", message)
                    emitFinal(message, ::emit)
                    if (isCurrent(generation)) {
                        sessionStore.completeConversationTurn(
                            turnId, message, TurnOutcome.COMPLETED,
                            runner.executedTools, clockMillis(),
                        )
                    }
                }
            }
        }
    }

    private suspend fun isCurrent(generation: Long): Boolean =
        sessionStore.getOrCreate().generation == generation

    private suspend fun finishWithoutTools(
        message: String,
        turnId: String,
        outcome: TurnOutcome,
        generation: Long,
        emitEvent: suspend (AgentEvent) -> Unit,
        detailKo: String? = null,
    ) {
        if (isCurrent(generation)) {
            sessionStore.completeConversationTurn(
                turnId, message, outcome, emptyList(), clockMillis(), detailKo,
            )
        }
        emitFinal(message, emitEvent)
    }

    private suspend fun finalText(
        userText: String,
        plan: StructuredIntentPlan,
        observations: List<ModelToolResponse>,
        hint: String,
        conversation: ConversationContext,
    ): String {
        val result = modelGateway.generateFinalText(
            StructuredFinalRequest(userText, plan, observations, hint, conversation),
        )
        return when (result) {
            is StructuredModelResult.Success -> {
                result.rawOutputs.forEach { trace("RAW_FINAL_MODEL_OUTPUT", it) }
                result.value
            }
            is StructuredModelResult.Failure -> hint
        }
    }

    private suspend fun emitFinal(
        message: String,
        emitEvent: suspend (AgentEvent) -> Unit,
    ) {
        emitEvent(AgentEvent.Token(message))
        emitEvent(AgentEvent.FinalMessage(message))
    }

    private fun trace(key: String, value: String) = traceSink.record(key, value)

    override fun close() = modelGateway.close()

    private suspend fun failEarly(
        message: String,
        turnId: String,
        generation: Long,
        emitEvent: suspend (AgentEvent) -> Unit,
    ) {
        if (isCurrent(generation)) {
            sessionStore.completeConversationTurn(
                turnId, message, TurnOutcome.FAILED, emptyList(), clockMillis(), message,
            )
        }
        emitEvent(AgentEvent.UserError(message))
    }

    private inner class TurnRunner(
        private val lease: TurnLease,
        private val userText: String,
        private val plan: StructuredIntentPlan,
        private val snapshot: ToolCatalogSnapshot,
        private val session: AgentSession,
        private val context: ToolExecutionContext,
        private val permissions: Set<String>,
        groundedCardId: String?,
        private val conversation: ConversationContext,
        private val generation: Long,
        private val turnIdForMemory: String,
        private val emitEvent: suspend (AgentEvent) -> Unit,
    ) {
        val workflow = workflowPolicy.startTurn(userText, environment.timeZoneId)
        val sideEffects = SideEffectGuard()
        val executedTools = mutableListOf<String>()
        private val observations = mutableListOf<ModelToolResponse>()
        private var callCount = 0

        init {
            val trusted = groundedCardId
                ?: session.conversationMemory.selectedContact?.takeIf { it.isActionable }?.cardId
            trusted?.let(workflow::seedTrustedContactProvenance)
        }

        suspend fun execute(): OrchestrationOutcome {
            currentCoroutineContext().ensureActive()
            return when (plan.intent) {
                AgentIntent.SEARCH_CONTACT -> searchOnly()
                AgentIntent.VIEW_CONTACT -> viewContact()
                AgentIntent.COMPOSE_EMAIL -> compose(ComposeChannel.EMAIL)
                AgentIntent.COMPOSE_SMS -> compose(ComposeChannel.SMS)
                AgentIntent.CREATE_CALENDAR_EVENT -> calendar()
                AgentIntent.UPDATE_CONTACT -> updateContact()
                AgentIntent.GET_CURRENT_DATETIME -> currentDateTime()
                else -> OrchestrationOutcome.Failure("실행할 수 없는 구조화 intent입니다.")
            }
        }

        private suspend fun searchOnly(): OrchestrationOutcome {
            val query = plan.recipient.value
            if (plan.recipient.type != RecipientType.CONTACT_NAME || query.isBlank()) {
                return OrchestrationOutcome.Failure("검색할 연락처 이름이 필요합니다.")
            }
            val result = step(SEARCH, buildJsonObject { put("query", query) })
            return result.toOutcome("연락처 검색을 완료했습니다.")
        }

        private suspend fun viewContact(): OrchestrationOutcome {
            if (plan.recipient.type == RecipientType.CARD_ID) {
                return step(
                    GET,
                    buildJsonObject {
                        put("card_id", plan.recipient.value)
                        put("purpose", "display")
                    },
                ).toOutcome("명함 상세정보를 조회했습니다.")
            }
            val contact = resolveContact("display")
            return when (contact) {
                is ContactResolution.Resolved ->
                    OrchestrationOutcome.Completed(observations, "명함 상세정보를 조회했습니다.")
                is ContactResolution.Terminal ->
                    OrchestrationOutcome.Completed(observations, contact.messageKo)
                is ContactResolution.Failed -> OrchestrationOutcome.Failure(contact.messageKo)
            }
        }

        private suspend fun compose(channel: ComposeChannel): OrchestrationOutcome {
            val recipient = when (plan.recipient.type) {
                RecipientType.EMAIL -> {
                    if (channel != ComposeChannel.EMAIL) {
                        return OrchestrationOutcome.Failure("문자 수신자로 이메일 주소를 사용할 수 없습니다.")
                    }
                    ResolvedRecipient(plan.recipient.value, null, null, null)
                }
                RecipientType.PHONE -> {
                    if (channel != ComposeChannel.SMS) {
                        return OrchestrationOutcome.Failure("이메일 수신자로 전화번호를 사용할 수 없습니다.")
                    }
                    ResolvedRecipient(plan.recipient.value, null, null, null)
                }
                RecipientType.CONTACT_NAME, RecipientType.CARD_ID -> when (val contact = resolveContact(channel.name.lowercase())) {
                    is ContactResolution.Resolved -> {
                        val destination = if (channel == ComposeChannel.EMAIL) {
                            contact.data.string("email")
                        } else {
                            contact.data.string("mobile").notBlank()
                                ?: contact.data.string("phone").notBlank()
                        }
                        if (destination.isNullOrBlank()) {
                            return OrchestrationOutcome.Completed(
                                observations,
                                if (channel == ComposeChannel.EMAIL) {
                                    "선택한 연락처에 이메일 주소가 없습니다."
                                } else {
                                    "선택한 연락처에 전화번호가 없습니다."
                                },
                            )
                        }
                        ResolvedRecipient(
                            destination = destination,
                            displayName = contact.data.string("name"),
                            company = contact.data.string("company"),
                            title = contact.data.string("title"),
                        )
                    }
                    is ContactResolution.Terminal ->
                        return OrchestrationOutcome.Completed(observations, contact.messageKo)
                    is ContactResolution.Failed ->
                        return OrchestrationOutcome.Failure(contact.messageKo)
                }
                else -> return OrchestrationOutcome.Failure("유효한 수신자가 필요합니다.")
            }

            trace("CONTENT_SOURCE", "GEMMA4")
            val generated = modelGateway.generateComposeContent(
                ComposeContentRequest(
                    channel = channel,
                    originalUserText = userText,
                    contentGoal = plan.contentGoal,
                    recipientDisplayName = recipient.displayName,
                    recipientCompany = recipient.company,
                    recipientTitle = recipient.title,
                    requestedTone = plan.tone,
                    userProvidedFacts = userText,
                    conversation = conversation,
                ),
            )
            val content = when (generated) {
                is StructuredModelResult.Success -> {
                    generated.rawOutputs.forEach { trace("RAW_CONTENT_MODEL_OUTPUT", it) }
                    trace("CONTENT_ATTEMPTS", generated.attempts.toString())
                    generated.value
                }
                is StructuredModelResult.Failure ->
                    return OrchestrationOutcome.Failure(generated.safeReasonKo)
            }
            val arguments = buildJsonObject {
                put("channel", channel.name.lowercase())
                put("to", recipient.destination)
                content.subject?.let { put("subject", it) }
                put("body", content.body)
            }
            return step(COMPOSE, arguments).toOutcome("작성 화면을 열었습니다. 전송 전에 확인해 주세요.")
        }

        private suspend fun calendar(): OrchestrationOutcome {
            val dateExpression = plan.dateExpression
                ?: return OrchestrationOutcome.Failure("일정 날짜가 필요합니다.")
            val timeExpression = plan.timeExpression
                ?: return OrchestrationOutcome.Failure("일정 시작 시각이 필요합니다.")
            var now = ZonedDateTime.now(ZoneId.of(environment.timeZoneId))
            if (dateTimeParser.requiresCurrentDate(dateExpression)) {
                val current = step(DATETIME, buildJsonObject {
                    put("timezone", environment.timeZoneId)
                })
                if (current !is StepOutcome.Success) return current.toOutcome("")
                val date = current.data.string("date")?.let {
                    runCatching { LocalDate.parse(it) }.getOrNull()
                } ?: return OrchestrationOutcome.Failure("현재 날짜 결과를 해석하지 못했습니다.")
                val time = current.data.string("time")?.let {
                    runCatching { LocalTime.parse(it.take(8)) }.getOrNull()
                } ?: LocalTime.NOON
                now = ZonedDateTime.of(date, time, ZoneId.of(environment.timeZoneId))
            }
            val parsed = dateTimeParser.parse(dateExpression, timeExpression, now)
            val start = when (parsed) {
                is DateTimeParseResult.Success -> parsed.value
                is DateTimeParseResult.Failure ->
                    return OrchestrationOutcome.Failure(parsed.reasonKo)
            }
            val attendees = mutableListOf<String>()
            if (plan.recipient.type in setOf(RecipientType.CONTACT_NAME, RecipientType.CARD_ID)) {
                when (val contact = resolveContact("calendar")) {
                    is ContactResolution.Resolved -> {
                        val email = contact.data.string("email")
                        if (email.isNullOrBlank()) {
                            return OrchestrationOutcome.Completed(
                                observations,
                                "선택한 연락처에 일정 참석자 이메일이 없습니다.",
                            )
                        }
                        attendees += email
                    }
                    is ContactResolution.Terminal ->
                        return OrchestrationOutcome.Completed(observations, contact.messageKo)
                    is ContactResolution.Failed ->
                        return OrchestrationOutcome.Failure(contact.messageKo)
                }
            } else if (plan.recipient.type == RecipientType.EMAIL) {
                attendees += plan.recipient.value
            }
            val arguments = buildJsonObject {
                put("title", plan.calendarTitle ?: "일정")
                put("start_time", start.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")))
                if (attendees.isNotEmpty()) {
                    put("attendee_emails", buildJsonArray {
                        attendees.forEach { add(JsonPrimitive(it)) }
                    })
                }
            }
            return step(CALENDAR, arguments).toOutcome("캘린더 작성 화면을 열었습니다. 저장 전에 확인해 주세요.")
        }

        private suspend fun updateContact(): OrchestrationOutcome {
            val updates = plan.updates
                ?: return OrchestrationOutcome.Failure("변경할 명함 필드와 값이 필요합니다.")
            if (!updatesAreGrounded(updates)) {
                return OrchestrationOutcome.Failure("수정할 명함 필드와 새 값을 구체적으로 알려주세요.")
            }
            val cardId = when (plan.recipient.type) {
                RecipientType.CARD_ID -> plan.recipient.value
                RecipientType.CONTACT_NAME -> when (val contact = resolveContact("display")) {
                    is ContactResolution.Resolved -> contact.data.string("card_id")
                        ?: return OrchestrationOutcome.Failure("명함 ID를 확인하지 못했습니다.")
                    is ContactResolution.Terminal ->
                        return OrchestrationOutcome.Completed(observations, contact.messageKo)
                    is ContactResolution.Failed ->
                        return OrchestrationOutcome.Failure(contact.messageKo)
                }
                else -> return OrchestrationOutcome.Failure("수정할 연락처 이름 또는 명함 ID가 필요합니다.")
            }
            return step(
                UPDATE,
                buildJsonObject {
                    put("card_id", cardId)
                    put("updates", updates)
                },
            ).toOutcome("명함 수정 확인 절차를 완료했습니다.")
        }

        private fun updatesAreGrounded(updates: JsonObject): Boolean =
            updates.isNotEmpty() && updates.all { (field, element) ->
                val value = (element as? JsonPrimitive)
                    ?.takeIf(JsonPrimitive::isString)
                    ?.content
                    ?.trim()
                    .orEmpty()
                value.isNotEmpty() &&
                    value in userText &&
                    UPDATE_FIELD_ALIASES[field].orEmpty().any(userText::contains)
            }

        private suspend fun currentDateTime(): OrchestrationOutcome =
            step(
                DATETIME,
                buildJsonObject { put("timezone", environment.timeZoneId) },
            ).toOutcome("현재 날짜와 시각을 조회했습니다.")

        private suspend fun resolveContact(purpose: String): ContactResolution {
            if (plan.recipient.type == RecipientType.CARD_ID) {
                return when (val detail = step(
                    GET,
                    buildJsonObject {
                        put("card_id", plan.recipient.value)
                        put("purpose", purpose)
                    },
                )) {
                    is StepOutcome.Success -> ContactResolution.Resolved(detail.data)
                    is StepOutcome.Failure -> ContactResolution.Failed(detail.messageKo)
                }
            }
            if (plan.recipient.type != RecipientType.CONTACT_NAME ||
                plan.recipient.value.isBlank()
            ) {
                return ContactResolution.Failed("연락처 이름이 필요합니다.")
            }
            val search = step(
                SEARCH,
                buildJsonObject { put("query", plan.recipient.value) },
            )
            if (search !is StepOutcome.Success) {
                return ContactResolution.Failed((search as StepOutcome.Failure).messageKo)
            }
            val results = search.data["results"] as? JsonArray ?: JsonArray(emptyList())
            if (results.isEmpty()) {
                return ContactResolution.Terminal("‘${plan.recipient.value}’ 연락처를 찾지 못했습니다.")
            }
            if (results.size > 1) {
                return ContactResolution.Terminal("같은 이름의 연락처가 여러 명입니다. 대상을 선택해 주세요.")
            }
            val cardId = (results.single() as? JsonObject)?.string("card_id")
                ?: return ContactResolution.Failed("검색 결과의 명함 ID가 없습니다.")
            val detail = step(
                GET,
                buildJsonObject {
                    put("card_id", cardId)
                    put("purpose", purpose)
                },
            )
            return when (detail) {
                is StepOutcome.Success -> ContactResolution.Resolved(detail.data)
                is StepOutcome.Failure -> ContactResolution.Failed(detail.messageKo)
            }
        }

        private suspend fun step(
            toolName: String,
            arguments: JsonObject,
        ): StepOutcome {
            if (callCount >= maxToolCalls) {
                return StepOutcome.Failure("한 요청의 최대 도구 실행 횟수를 초과했습니다.")
            }
            callCount += 1
            val contract = snapshot.contractsByModelName[toolName]
                ?: return StepOutcome.Failure("등록되지 않은 기능입니다: $toolName")
            val call = ModelToolCall(UUID.randomUUID().toString(), toolName, arguments)
            trace("RAW_MODEL_TOOL_CALL", "NONE")
            trace("ORCHESTRATED_TOOL_CALL", "$toolName:$arguments")
            when (val validation = workflow.validate(call, contract)) {
                WorkflowValidationResult.Allow -> Unit
                is WorkflowValidationResult.Reject -> {
                    trace("VALIDATION_RESULT", "rejected")
                    trace("REJECT_REASON", validation.rejection.reason.name)
                    return StepOutcome.Failure(validation.rejection.messageKo)
                }
            }
            trace("VALIDATION_RESULT", "approved")
            if (!sideEffects.tryReserve(contract)) {
                return StepOutcome.Failure("같은 요청에서 외부 작업을 두 번 실행할 수 없습니다.")
            }
            if (lease.isStale()) return StepOutcome.Failure(STALE_SESSION)
            val policy = policyEngine.evaluate(
                contract,
                call,
                AgentSessionView(session.sessionId, permissions),
            )
            val policyError = when (policy) {
                ToolPolicyDecision.Allow -> null
                is ToolPolicyDecision.RequirePermission -> {
                    emitEvent(AgentEvent.PermissionRequested(policy.permissions))
                    ToolError(
                        StandardToolErrorCodes.PERMISSION_REQUIRED,
                        "필요한 권한이 없습니다.",
                        true,
                    )
                }
                is ToolPolicyDecision.RequireConfirmation -> {
                    emitEvent(AgentEvent.ConfirmationRequested(policy.promptKo))
                    if (context.confirmationGateway.confirm(policy.promptKo)) null else ToolError(
                        StandardToolErrorCodes.CONFIRMATION_REJECTED,
                        "사용자가 작업을 취소했습니다.",
                        false,
                    )
                }
                is ToolPolicyDecision.Deny -> policy.error
            }
            if (policyError != null) {
                sideEffects.release(contract)
                return StepOutcome.Failure(policyError.safeMessageKo)
            }

            // Last gate before an irreversible action.
            if (lease.isStale()) return StepOutcome.Failure(STALE_SESSION)
            emitEvent(AgentEvent.ToolStarted(contract.presentation.runningMessageKo))
            val result = toolExecutor.execute(call, snapshot, context)
            if (lease.isStale()) return StepOutcome.Failure(STALE_SESSION)
            if (result is ToolExecutionResult.Success && isCurrent(generation)) {
                if (result.sessionUpdates.isNotEmpty()) {
                    sessionStore.update { active ->
                        result.sessionUpdates.forEach { update ->
                            val value = update.value
                            if (value == null) active.capabilityState.remove(update.key)
                            else active.capabilityState[update.key] =
                                com.hjp.tool.contract.StoredSessionState(
                                    update.schemaVersion,
                                    value,
                                    update.expiresAtEpochMillis,
                                )
                        }
                    }
                }
                sessionStore.projectToolResult(toolName, result.data, clockMillis(), turnIdForMemory)
            }
            workflow.recordResult(call, result)
            val observation = observationMapper.toModelResponse(result, contract)
            observations += observation.modelResponse
            emitEvent(AgentEvent.ToolFinished(observation.safeUiMessageKo))
            if (result is ToolExecutionResult.Failure && toolName == GET && lease.isValid()) {
                arguments["card_id"]?.let { value ->
                    sessionStore.invalidateStaleCard(value.toString().trim('"'))
                }
            }
            return when (result) {
                is ToolExecutionResult.Success -> {
                    executedTools += toolName
                    trace("TOOL_EXECUTED", toolName)
                    trace("TOOL_RESULT", result.data.toString())
                    StepOutcome.Success(result.data)
                }
                is ToolExecutionResult.Failure -> {
                    trace("TOOL_RESULT", result.error.code.value)
                    StepOutcome.Failure(result.error.safeMessageKo)
                }
            }
        }

        private fun StepOutcome.toOutcome(successHint: String): OrchestrationOutcome =
            when (this) {
                is StepOutcome.Success -> OrchestrationOutcome.Completed(observations, successHint)
                is StepOutcome.Failure -> OrchestrationOutcome.Failure(messageKo)
            }
    }

    private sealed interface StepOutcome {
        data class Success(val data: JsonObject) : StepOutcome
        data class Failure(val messageKo: String) : StepOutcome
    }

    private sealed interface ContactResolution {
        data class Resolved(val data: JsonObject) : ContactResolution
        data class Terminal(val messageKo: String) : ContactResolution
        data class Failed(val messageKo: String) : ContactResolution
    }

    private sealed interface OrchestrationOutcome {
        data class Completed(
            val observations: List<ModelToolResponse>,
            val completionHintKo: String,
        ) : OrchestrationOutcome

        data class Failure(val messageKo: String) : OrchestrationOutcome
    }

    private data class ResolvedRecipient(
        val destination: String,
        val displayName: String?,
        val company: String?,
        val title: String?,
    )

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()

    private fun String?.notBlank(): String? = this?.takeIf(String::isNotBlank)

    private companion object {
        val NON_EXECUTING_INTENTS = setOf(
            AgentIntent.ANSWER_ONLY,
            AgentIntent.CLARIFY,
            AgentIntent.UNSUPPORTED,
        )
        val CONTACT_REFERENCE_INTENTS = setOf(
            AgentIntent.VIEW_CONTACT,
            AgentIntent.COMPOSE_EMAIL,
            AgentIntent.COMPOSE_SMS,
            AgentIntent.CREATE_CALENDAR_EVENT,
            AgentIntent.UPDATE_CONTACT,
        )
        val PROVENANCE_REQUIRED_CARD_ID_INTENTS = setOf(
            AgentIntent.COMPOSE_EMAIL,
            AgentIntent.COMPOSE_SMS,
            AgentIntent.CREATE_CALENDAR_EVENT,
            AgentIntent.UPDATE_CONTACT,
        )
        const val SEARCH = "search_contacts"
        const val GET = "get_contact"
        const val UPDATE = "update_business_card"
        const val CALENDAR = "create_calendar_event"
        const val COMPOSE = "open_compose"
        const val DATETIME = "get_current_datetime"
        const val STALE_SESSION = "새 대화가 시작되어 이전 요청을 중단했습니다."
        val UPDATE_FIELD_ALIASES = mapOf(
            "name" to listOf("이름"),
            "name_en" to listOf("영문 이름"),
            "company" to listOf("회사", "회사명"),
            "department" to listOf("부서"),
            "title" to listOf("직함", "직급"),
            "industry" to listOf("업종"),
            "location" to listOf("지역", "위치"),
            "phone" to listOf("전화"),
            "mobile" to listOf("휴대폰", "전화번호"),
            "email" to listOf("이메일", "메일 주소"),
            "address" to listOf("주소"),
            "website" to listOf("웹사이트"),
            "memo" to listOf("메모"),
        )
    }
}
