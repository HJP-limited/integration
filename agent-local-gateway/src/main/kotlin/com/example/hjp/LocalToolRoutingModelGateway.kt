package com.example.hjp

import com.hjp.agent.contract.ClarifyReason
import com.hjp.agent.contract.AgentModelGateway
import com.hjp.agent.contract.AgentModelSession
import com.hjp.agent.contract.FinalAnswerInput
import com.hjp.agent.contract.ModelDecision
import com.hjp.agent.contract.ModelInput
import com.hjp.agent.contract.ModelSessionConfig
import com.hjp.agent.contract.ModelToolCall
import com.hjp.agent.contract.ModelToolResponse
import com.hjp.agent.contract.ModelWorkflowNote
import com.hjp.agent.core.CardUpdateIntent
import com.hjp.agent.core.ContactReadIntent
import com.hjp.agent.core.CurrentDateTimeIntent
import com.hjp.agent.core.PersonNameMask
import com.hjp.agent.core.ContactAnaphora
import com.hjp.agent.contract.ModelConversationRole
import com.hjp.agent.core.TurnContactTargetResolver
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Compatibility model used only on Android emulators whose advertised ARM SME
 * features can make LiteRT-LM's CPU backend terminate the process with SIGILL.
 * It still emits the same ModelToolCall protocol consumed by AgentKernel, so
 * policy, validation, execution, observation mapping, and UI events are not
 * bypassed.
 */
class LocalToolRoutingModelGateway : AgentModelGateway {
    override suspend fun openSession(config: ModelSessionConfig): AgentModelSession =
        LocalToolRoutingModelSession(config)
}

private class LocalToolRoutingModelSession(
    private val config: ModelSessionConfig,
) : AgentModelSession {
    override val catalogRevision: String = config.toolCatalog.revision
    private var pendingAction: PendingAction? = null
    private var turnContext: com.hjp.agent.contract.TurnContext? = null

    /** 마지막으로 사용자에게 내놓은 답. 워크플로 재촉이 들어왔을 때 되돌려 줄 값이다. */
    private var lastFinalText: String? = null

    /**
     * 이 게이트웨이가 **직접 요청한** 도구 호출들.
     *
     * 커널은 우리가 부르지 않은 도구도 실행한다 — 라우터가 사람을 확정해 두면 새로 읽어
     * 오라고(requiresFreshRead) 시킨다. 그런 결과가 오면 이번 턴에 우리가 낸 답이라는 게
     * 없으므로, 앞 턴 답을 되살리면 안 된다(실측: "첫 번째 사람 상세 보여줘" 가 앞 턴의
     * 개수 답을 그대로 뱉었다).
     */
    private val issuedCallIds = LinkedHashSet<String>()

    override suspend fun decide(input: ModelInput): ModelDecision = remember(
        when (input) {
            is ModelInput.User -> routeUserInput(input)
        }
    )

    /**
     * 이미 끝난 턴을 워크플로가 다시 재촉해도 **답을 뒤엎지 않는다.**
     *
     * 기본 구현은 재촉을 도구 결과로 바꿔 보내는데, 그러면 직전 도구가 get_contact 였던
     * 턴은 pendingAction 이 비어 있어 "예상하지 않은 명함 상세 결과"라는 오류로 끝난다.
     * 실제로 그랬다: 이메일이 없는 명함에 메일을 쓰라고 하면 get_contact 로 확인한 뒤
     * "이메일 주소 정보가 없습니다" 라고 바르게 답해 놓고, 재촉 한 번에 그 답이
     * 오류 문구로 덮였다. 할 일이 남아 있을 때만 기본 동작으로 넘긴다.
     */
    override suspend fun continueWithWorkflowNote(note: ModelWorkflowNote): ModelDecision {
        val answered = lastFinalText
        if (pendingAction == null && !answered.isNullOrBlank()) {
            return ModelDecision.FinalCandidate(answered)
        }
        return super.continueWithWorkflowNote(note)
    }

    private fun remember(decision: ModelDecision): ModelDecision {
        if (decision is ModelDecision.FinalCandidate) lastFinalText = decision.draftText
        return decision
    }

    override suspend fun continueWithToolResult(result: ModelToolResponse): ModelDecision = remember(
        // 할 일이 없는데 도착한 도구 결과는 아무것도 진전시키지 못한다. 그대로 아래로 흘리면
        // pendingAction 이 비어 있어 "예상하지 않은 결과" 오류가 되고, 이미 옳게 내놓은 답이
        // 그 오류 문구로 덮인다.
        //
        // 두 가지 모양으로 겪었다. (1) 이메일 없는 명함에 메일을 쓰라고 하면 get_contact 로
        // 확인해 "이메일 주소 정보가 없습니다" 라고 답해 놓고, 뒤늦은 실패 결과에 그 답이
        // 사라졌다. (2) "그 사람한테 메일 초안 열어줘" 처럼 본문이 없는 요청은 게이트웨이가
        // "본문을 알려 주세요" 로 끝내는데, 라우터가 그 턴을 이미 사람에게 묶어 둬서 커널이
        // get_contact 를 **성공적으로** 실행하고, 그 결과가 되물음을 덮었다.
        //
        // 성공이든 실패든 같다 — 이 턴은 이미 답을 냈다.
        if (pendingAction == null && result.callId in issuedCallIds && !lastFinalText.isNullOrBlank()) {
            ModelDecision.FinalCandidate(lastFinalText.orEmpty())
        } else when (result.modelToolName) {
        SEARCH_CONTACTS -> continueAfterSearch(result)
        GET_CONTACT -> continueAfterGetContact(result)
            COUNT_CONTACTS -> continueAfterCount(result)
        GET_CURRENT_DATETIME -> continueAfterCurrentDateTime(result)
        CREATE_CALENDAR_EVENT -> finishExternalUi(result, "캘린더 작성 화면을 열었습니다. 저장 전에 확인해 주세요.")
        OPEN_COMPOSE -> finishExternalUi(result, composeSuccessMessage())
        UPDATE_BUSINESS_CARD -> finishUpdate(result)
            else -> ModelDecision.Invalid("예상하지 않은 도구 결과를 받았습니다.", retryable = false)
        }
    )

    override fun streamFinal(input: FinalAnswerInput): Flow<String> = flowOf(input.draftText)

    override fun close() = Unit

    private fun routeUserInput(input: ModelInput.User): ModelDecision {
        // 턴마다 새로 쌓는다 — 앞 턴의 답이 이번 턴의 늦은 실패에 되살아나면 안 된다.
        lastFinalText = null
        issuedCallIds.clear()
        val text = input.text
        turnContext = input.turnContext
        val action = (LocalPromptRouter.parse(text) ?: bareLookup(text, input.turnContext))
            ?.let { withImplicitTarget(it, input.turnContext) }
        val missing = action?.missingMessage()
        if (action == null) {
            pendingAction = null
            if (ContactSearchPromptParser.isConversationReference(text)) {
                return ModelDecision.FinalCandidate(conversationRecap(input))
            }
            return ModelDecision.FinalCandidate(
                "명함 검색, 캘린더 일정 작성, 메일/SMS 작성 요청을 처리할 수 있습니다. " +
                    "명함 수정과 현재 날짜·시각 조회도 처리할 수 있습니다. 메시지는 수신자와 본문을 함께 입력해 주세요.",
            )
        }
        if (missing != null) {
            pendingAction = null
            // Typed as a clarification, not an answer: the turn stopped because a tool's required
            // slot is unknown, and a metric has to be able to tell that apart from a general reply.
            return ModelDecision.FinalCandidate(missing, ClarifyReason.MISSING_REQUIRED_SLOT)
        }
        pendingAction = action
        if (System.getenv("HJP_GW_DEBUG") != null) println("@@@route " + action)
        return when (action) {
            is PendingAction.ContactSearch -> {
                // The pre-router already grounded this reference on a verified card, so re-reading
                // that card is both cheaper and safer than searching a rewritten name again.
                val grounded = turnContext?.groundedCardId
                if (grounded != null && hasTool(GET_CONTACT)) getContactToolCall(grounded, "display")
                else if (!hasTool(SEARCH_CONTACTS)) unavailable("명함 검색")
                else searchToolCall(action.query)
            }
            is PendingAction.ContactCount -> {
                if (!hasTool(COUNT_CONTACTS)) unavailable("명함 개수 세기")
                else countToolCall(action.condition.ifBlank { inheritedCountCondition(text) })
            }
            is PendingAction.CurrentDateTime -> {
                if (!hasTool(GET_CURRENT_DATETIME)) unavailable("현재 날짜·시각 조회")
                else currentDateTimeToolCall(action.timezone)
            }
            is PendingAction.Calendar -> {
                if (!hasTool(CREATE_CALENDAR_EVENT)) unavailable("캘린더 작성")
                else if (action.needsCurrentDateTime()) {
                    if (!hasTool(GET_CURRENT_DATETIME)) unavailable("현재 날짜·시각 조회")
                    else currentDateTimeToolCall()
                }
                // 참석자를 아무도 말하지 않은 일정은 **아무도 참석하지 않는 일정**이다.
                // 빈 문자열을 "대상 미지정"이 아니라 "대상 있음"으로 읽으면, 앞 턴에서
                // 조회한 사람을 멋대로 참석자로 끌어와 명함 조회부터 돈다(실측:
                // "분기 점검 일정 만들어줘" 가 get_contact 로 새서 일정이 안 만들어졌다).
                // withImplicitTarget 주석이 명함 수정에 대해 말하는 원칙과 같다 —
                // 이름을 안 댄 요청에 세션 focus 를 물려주지 않는다.
                else if (!action.contactQuery.isNullOrBlank()) contactLookupStart(action.contactQuery)
                else calendarToolCall(action)
            }
            is PendingAction.Compose -> {
                if (!hasTool(OPEN_COMPOSE)) unavailable("메일/SMS 작성")
                else if (action.to == null) contactLookupStart(action.contactQuery.orEmpty())
                else composeToolCall(action)
            }
            is PendingAction.ContactUpdate -> {
                if (!hasTool(UPDATE_BUSINESS_CARD)) unavailable("명함 수정")
                else if (action.cardId != null) updateToolCall(action.cardId, action)
                else contactLookupStart(action.contactQuery.orEmpty())
            }
        }
    }

    /**
     * 동사 없이 낱말만 던진 검색.
     *
     * "대전 변호사", "손다은" 처럼 사람들이 실제로 치는 말에는 찾아·검색 같은 동사가 없다.
     * 위의 프롬프트 규칙들은 동사나 명함·연락처 같은 낱말을 요구해서 이런 입력을 놓치고
     * 기능 안내문으로 끝냈다(실기기 실측: "판교개발자" 가 아무것도 안 했다).
     *
     * 저장소가 **아는 말**을 했을 때만 검색으로 본다 — 카드가 직함으로 쓰는 낱말이거나
     * 실제 사람 이름일 때다. 둘 다 문장의 철자가 아니라 저장소가 답한 것이라, 아무 말에나
     * 검색을 돌리지 않는다.
     */
    private fun bareLookup(text: String, context: com.hjp.agent.contract.TurnContext): PendingAction? {
        if (text.isBlank()) return null
        val namesSomethingWeHold = context.titleMatches.isNotEmpty() ||
            context.directoryMatches.any { it.identifiesAPerson }
        if (!namesSomethingWeHold) return null
        return PendingAction.ContactSearch(text.trim())
    }

    /**
     * Fills in the target a resolved reference already established.
     *
     * "그 사람 메모를 VIP로 수정해줘" carries no name once the pronoun is resolved against a card id,
     * so asking the user which card to edit would be asking about a target they had already given.
     * The reference still has to be in the sentence, though: an edit that names nobody is an edit
     * whose target the user has not stated, and inheriting the session's focus for it would edit
     * whoever happened to be looked up earlier. [TurnContactTargetResolver] is the single authority
     * on that question, shared with the kernel.
     */
    private fun withImplicitTarget(action: PendingAction, context: com.hjp.agent.contract.TurnContext): PendingAction {
        if (action !is PendingAction.ContactUpdate) return action
        if (action.cardId != null || action.contactQuery != null) return action
        val target = TurnContactTargetResolver.focusReferencedBy(context.userText, context.memory)
        if (target !is TurnContactTargetResolver.Target.Confirmed) return action
        val name = context.memory.selectedContact?.name?.takeIf(String::isNotBlank) ?: return action
        return action.copy(contactQuery = name)
    }

    /**
     * Answers "what did we just do" style questions from this session's own turns only. It never
     * re-runs a search and never introduces a name that the session did not already produce.
     */
    private fun conversationRecap(input: ModelInput.User): String {
        val context = input.turnContext
        val lastAssistant = context.transcript
            .lastOrNull { it.role == com.hjp.agent.contract.ModelConversationRole.ASSISTANT }
            ?.text
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val topic = context.memory.topic.trim()
        return when {
            lastAssistant != null -> "직전 대화에서 이렇게 안내했습니다: $lastAssistant"
            topic.isNotEmpty() -> "직전에 “$topic” 요청을 처리했습니다. 이어서 필요한 작업을 알려 주세요."
            else -> "이 세션에서 아직 처리한 대화가 없습니다. 찾을 명함 이름이나 조건을 알려 주세요."
        }
    }

    /**
     * Starts a contact lookup. When this session already verified exactly one contact and the
     * request refers to that person, the lookup goes straight to `get_contact` with the stored
     * card id: the remembered projection holds no email or phone, so the address is always re-read
     * rather than guessed from memory.
     */
    private fun contactLookupStart(query: String): ModelDecision {
        if (!hasTool(GET_CONTACT)) {
            pendingAction = null
            return unavailable("명함 상세 조회")
        }
        val verified = turnContext?.memory?.selectedContact?.takeIf { it.isActionable }
        // 지시어("그 사람에게 메일 써줘")는 **이름이 아니라 가리킴**이다. 이걸 검색어로 넘기면
        // '그 사람에게'라는 이름을 찾다가 0건이 나오고, 대화는 이미 누구인지 아는 상태인데도
        // "해당하는 명함을 찾지 못했습니다"로 끝난다(실측). 어휘집은 agent-core 의
        // ContactAnaphora 를 그대로 쓴다 — 지시어 목록이 두 군데로 갈라지면 라우터가 지시어로
        // 본 문장을 게이트웨이는 이름으로 보는 어긋남이 생긴다.
        val refersToFocus = query.isBlank() ||
            query.noSpaces().contains(verified?.name?.noSpaces().orEmpty()) ||
            ContactAnaphora.isPresent(query)
        if (verified != null && refersToFocus) {
            return getContactToolCall(verified.cardId, contactPurpose())
        }
        if (query.isBlank() || !hasTool(SEARCH_CONTACTS)) {
            pendingAction = null
            return unavailable("명함 상세 조회")
        }
        return searchToolCall(query)
    }

    private fun String.noSpaces(): String = replace(Regex("\\s+"), "")

    private fun contactPurpose(): String = when (val action = pendingAction) {
        is PendingAction.Calendar -> "calendar"
        is PendingAction.Compose -> action.channel
        else -> "display"
    }

    private fun continueAfterSearch(result: ModelToolResponse): ModelDecision {
        val action = pendingAction
        if (!result.ok()) return ModelDecision.FinalCandidate(result.errorMessage("명함 검색을 완료하지 못했습니다."))
        val results = result.searchResults()
        if (action is PendingAction.ContactSearch) return finishSearch(action.query, results)
        if (action !is PendingAction.Calendar && action !is PendingAction.Compose && action !is PendingAction.ContactUpdate) {
            return ModelDecision.Invalid("예상하지 않은 명함 검색 결과를 받았습니다.", retryable = false)
        }

        val query = when (action) {
            is PendingAction.Calendar -> action.contactQuery.orEmpty()
            is PendingAction.Compose -> action.contactQuery.orEmpty()
            is PendingAction.ContactUpdate -> action.contactQuery.orEmpty()
            else -> ""
        }
        if (results.isEmpty()) {
            pendingAction = null
            return ModelDecision.FinalCandidate("‘$query’에 해당하는 명함을 찾지 못했습니다.")
        }
        if (results.size > 1) {
            pendingAction = null
            return ModelDecision.FinalCandidate(
                "대상 명함을 하나로 특정해 주세요.\n${formatSearchLines(results)}",
            )
        }
        val cardId = results.single().cardId
        val purpose = when (action) {
            is PendingAction.Calendar -> "calendar"
            is PendingAction.Compose -> action.channel
            is PendingAction.ContactUpdate -> "display"
            else -> "display"
        }
        return getContactToolCall(cardId, purpose)
    }

    private fun continueAfterGetContact(result: ModelToolResponse): ModelDecision {
        val action = pendingAction
        if (System.getenv("HJP_GW_DEBUG") != null) println("@@@getc action=" + action + " ok=" + result.ok())
        // action == null 은 오류가 아니다 — 커널이 라우터의 확정에 따라 스스로 읽어 온 경우이고,
        // 아래 when 이 카드를 그대로 보여 준다. 다른 종류의 할 일이 걸려 있는데 명함 상세가
        // 오는 것만 진짜 예상 밖이다.
        if (action != null &&
            action !is PendingAction.Calendar && action !is PendingAction.Compose &&
            action !is PendingAction.ContactUpdate && action !is PendingAction.ContactSearch
        ) {
            return ModelDecision.Invalid("예상하지 않은 명함 상세 결과를 받았습니다.", retryable = false)
        }
        if (!result.ok()) {
            pendingAction = null
            return ModelDecision.FinalCandidate(result.errorMessage("명함 상세정보를 확인하지 못했습니다."))
        }
        val contact = result.payload["data"] as? JsonObject
            ?: return ModelDecision.Invalid("명함 상세 결과 형식이 올바르지 않습니다.", retryable = false)

        return when (action) {
            is PendingAction.ContactSearch -> {
                pendingAction = null
                renderCard(contact)
            }
            // 우리가 부르지 않은 조회. 라우터가 사람을 확정해 두면 커널이 새로 읽어 오라고
            // 시키는데(requiresFreshRead), 그 결과가 여기로 온다. 할 일이 없다고 오류를 내면
            // "첫 번째 사람 상세 보여줘" 가 "예상하지 않은 명함 상세 결과"로 끝난다(실측).
            // 물어본 것이 그 사람의 카드이므로 그대로 보여 준다.
            null -> renderCard(contact)
            is PendingAction.Calendar -> {
                val email = contact.string("email")
                calendarToolCall(
                    action,
                    attendeeEmails = (action.attendeeEmails + listOfNotNull(email)).distinct(),
                    attendeeName = contact.string("name"),
                )
            }
            is PendingAction.Compose -> {
                val to = when (action.channel) {
                    CHANNEL_EMAIL -> contact.string("email")
                    // Cards carry a mobile number far more often than a landline, and the workflow
                    // policy accepts either; reading only `phone` refused every mobile-only card.
                    CHANNEL_SMS -> contact.string("mobile") ?: contact.string("phone")
                    else -> null
                }
                if (to.isNullOrBlank()) {
                    pendingAction = null
                    val field = if (action.channel == CHANNEL_EMAIL) "이메일 주소" else "전화번호"
                    ModelDecision.FinalCandidate("선택한 명함에 $field 정보가 없습니다.")
                } else {
                    composeToolCall(action.copy(to = to))
                }
            }
            is PendingAction.ContactUpdate -> {
                val cardId = contact.string("card_id")
                    ?: return ModelDecision.Invalid("명함 상세 결과에 card_id가 없습니다.", retryable = false)
                updateToolCall(cardId, action)
            }
            else -> ModelDecision.Invalid("예상하지 않은 작업 상태입니다.", retryable = false)
        }
    }

    /** 전체 개수는 조건을 **보내지 않는다.** 빈 문자열은 인자 검증에 걸린다. */
    /**
     * 조건을 말하지 않은 개수 질문이 무엇을 세야 하는지.
     *
     * "선행연구팀 사람 찾아줘" 다음의 "몇 명이야?" 는 **그 팀이** 몇 명이냐는 뜻이다.
     * 전체를 세면 1000 이라 답하게 되는데, 물어본 것과 다른 답이면서 숫자라서 맞아 보인다
     * (실측: 선행연구팀 12명인데 1000장이라고 답했다).
     *
     * 다만 명함·연락처·카드를 입에 올린 질문("명함 몇 장 있어")은 전체를 묻는 것이다. 그때는
     * 물려받지 않는다.
     *
     * 앞 발화는 transcript 에서 가져온다. 라우터는 한 턴에서 여러 번 불리고 그 사이 이번
     * 발화가 transcript 에 들어가므로, 지금 문장과 같은 것은 건너뛴다.
     */
    private fun inheritedCountCondition(current: String): String {
        if (ContactReadIntent.CARD_OBJECTS.any(current::contains)) return ""
        val previous = turnContext?.transcript.orEmpty()
            .lastOrNull { entry ->
                entry.role == ModelConversationRole.USER &&
                    entry.text.trim() != current.trim() &&
                    CountPromptParser.parse(entry.text) == null
            }
            ?: return ""
        return previous.text
    }

    private fun countToolCall(condition: String) = toolCall(COUNT_CONTACTS) {
        if (condition.isNotBlank()) put("query", condition)
    }

    private fun continueAfterCount(result: ModelToolResponse): ModelDecision {
        pendingAction = null
        if (!result.ok()) {
            return ModelDecision.FinalCandidate(result.errorMessage("명함 개수를 세지 못했습니다."))
        }
        val data = result.payload["data"] as? JsonObject
            ?: return ModelDecision.Invalid("명함 개수 결과 형식이 올바르지 않습니다.", retryable = false)
        val countable = (data["countable"] as? JsonPrimitive)?.booleanOrNull ?: false
        val count = (data["count"] as? JsonPrimitive)?.intOrNull ?: 0
        val condition = (data["query"] as? JsonPrimitive)?.content.orEmpty()
        // 셀 수 없는 조건이면 숫자를 말하지 않는다. 지어낸 확신보다 못 센다고 말하는 편이 낫다.
        if (!countable) {
            return ModelDecision.FinalCandidate(
                "그 조건은 사람마다 기준이 달라 정확한 수를 세기 어렵습니다. " +
                    "이름·회사·지역·직함으로 물어보시면 세어 드릴 수 있어요.",
            )
        }
        return ModelDecision.FinalCandidate(
            if (condition.isBlank()) "등록된 명함은 총 " + count + "장입니다."
            else "조건에 맞는 명함은 총 " + count + "장입니다.",
        )
    }

    private fun renderCard(contact: JsonObject): ModelDecision {
        val details = listOf("company", "title", "email", "mobile", "phone")
            .mapNotNull { field -> contact.string(field)?.takeIf(String::isNotBlank) }
        return ModelDecision.FinalCandidate(
            (contact.string("name").orEmpty()) + " 명함입니다." + NEWLINE + "- " + details.joinToString(" · "),
        )
    }

    private fun continueAfterCurrentDateTime(result: ModelToolResponse): ModelDecision {
        val action = pendingAction
        if (!result.ok()) {
            pendingAction = null
            return ModelDecision.FinalCandidate(result.errorMessage("현재 날짜와 시각을 확인하지 못했습니다."))
        }
        val data = result.payload["data"] as? JsonObject
            ?: return ModelDecision.Invalid("현재 날짜·시각 결과 형식이 올바르지 않습니다.", retryable = false)
        return when (action) {
            is PendingAction.CurrentDateTime -> {
                pendingAction = null
                ModelDecision.FinalCandidate(
                    "현재 날짜와 시각입니다.\n" +
                        "- 날짜: ${data.string("date").orEmpty()}\n" +
                        "- 시각: ${data.string("time").orEmpty()}\n" +
                        "- 시간대: ${data.string("timezone").orEmpty()}",
                )
            }
            is PendingAction.Calendar -> {
                val resolved = action.resolveRelativeDateTime(data.string("date"))
                    ?: return ModelDecision.FinalCandidate(
                        "일정 시작 날짜와 시각을 yyyy-MM-dd HH:mm 형식으로 알려 주세요.",
                        ClarifyReason.MISSING_REQUIRED_SLOT,
                    )
                pendingAction = resolved
                if (resolved.contactQuery != null) contactLookupStart(resolved.contactQuery)
                else calendarToolCall(resolved)
            }
            else -> ModelDecision.Invalid("예상하지 않은 현재 날짜·시각 결과를 받았습니다.", retryable = false)
        }
    }

    private fun finishExternalUi(result: ModelToolResponse, successMessage: String): ModelDecision {
        pendingAction = null
        if (!result.ok()) return ModelDecision.FinalCandidate(result.errorMessage("외부 작성 화면을 열지 못했습니다."))
        return ModelDecision.FinalCandidate(successMessage)
    }

    private fun finishUpdate(result: ModelToolResponse): ModelDecision {
        pendingAction = null
        if (!result.ok()) return ModelDecision.FinalCandidate(result.errorMessage("명함을 수정하지 못했습니다."))
        return ModelDecision.FinalCandidate("명함을 수정했습니다.")
    }

    private fun finishSearch(query: String, results: List<SearchHit>): ModelDecision {
        pendingAction = null
        if (results.isEmpty()) {
            return ModelDecision.FinalCandidate("‘$query’에 해당하는 명함을 찾지 못했습니다.")
        }
        return ModelDecision.FinalCandidate("명함 검색 결과입니다.\n${formatSearchLines(results)}")
    }

    private fun formatSearchLines(results: List<SearchHit>): String = results.joinToString("\n") { item ->
        val details = listOf(item.company, item.title, item.location).filter(String::isNotBlank)
        if (details.isEmpty()) "- ${item.name}" else "- ${item.name} · ${details.joinToString(" · ")}"
    }

    private fun searchToolCall(query: String) = toolCall(SEARCH_CONTACTS) {
        put("query", query)
        put("limit", 5)
    }

    private fun getContactToolCall(cardId: String, purpose: String) = toolCall(GET_CONTACT) {
        put("card_id", cardId)
        put("purpose", purpose)
    }

    private fun currentDateTimeToolCall(timezone: String? = null) = toolCall(GET_CURRENT_DATETIME) {
        timezone?.let { put("timezone", it) }
    }

    private fun calendarToolCall(
        action: PendingAction.Calendar,
        attendeeEmails: List<String> = action.attendeeEmails,
        attendeeName: String? = null,
    ) = toolCall(CREATE_CALENDAR_EVENT) {
        val title = if (!action.titleExplicit && attendeeName != null) "${attendeeName} ${action.title}" else action.title
        put("title", title)
        put("start_time", action.startTime.orEmpty())
        action.endTime?.let { put("end_time", it) }
        action.location?.let { put("location", it) }
        action.description?.let { put("description", it) }
        if (attendeeEmails.isNotEmpty()) {
            put("attendee_emails", buildJsonArray { attendeeEmails.forEach { add(JsonPrimitive(it)) } })
        }
    }

    private fun composeToolCall(action: PendingAction.Compose) = toolCall(OPEN_COMPOSE) {
        put("channel", action.channel)
        put("to", action.to.orEmpty())
        action.subject?.let { put("subject", it) }
        put("body", action.body.orEmpty())
    }

    private fun updateToolCall(cardId: String, action: PendingAction.ContactUpdate) = toolCall(UPDATE_BUSINESS_CARD) {
        put("card_id", cardId)
        if (action.updates.isNotEmpty()) {
            put("updates", buildJsonObject {
                action.updates.forEach { (field, value) -> put(field, value) }
            })
        }
        if (action.clearFields.isNotEmpty()) {
            put("clear_fields", buildJsonArray { action.clearFields.forEach { add(JsonPrimitive(it)) } })
        }
    }

    private fun toolCall(
        modelToolName: String,
        arguments: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): ModelDecision.ToolCalls {
        val callId = UUID.randomUUID().toString()
        issuedCallIds += callId
        return ModelDecision.ToolCalls(listOf(ModelToolCall(
            callId = callId,
            modelToolName = modelToolName,
            arguments = buildJsonObject(arguments),
        )))
    }

    private fun hasTool(modelToolName: String): Boolean =
        config.toolCatalog.contractsByModelName.containsKey(modelToolName)

    private fun unavailable(name: String): ModelDecision.FinalCandidate {
        pendingAction = null
        return ModelDecision.FinalCandidate("현재 $name 기능을 사용할 수 없습니다.")
    }

    private fun composeSuccessMessage(): String = when ((pendingAction as? PendingAction.Compose)?.channel) {
        CHANNEL_EMAIL -> "메일 작성 화면을 열었습니다. 전송 전에 확인해 주세요."
        CHANNEL_SMS -> "문자 작성 화면을 열었습니다. 전송 전에 확인해 주세요."
        else -> "작성 화면을 열었습니다. 전송 전에 확인해 주세요."
    }

    private fun ModelToolResponse.ok(): Boolean =
        (payload["ok"] as? JsonPrimitive)?.booleanOrNull == true

    private fun ModelToolResponse.errorMessage(fallback: String): String =
        ((payload["error"] as? JsonObject)?.get("message_ko") as? JsonPrimitive)
            ?.content
            ?.takeIf { it.isNotBlank() }
            ?: fallback

    private fun ModelToolResponse.searchResults(): List<SearchHit> {
        val data = payload["data"] as? JsonObject
        val results = data?.get("results") as? JsonArray ?: JsonArray(emptyList())
        val declaredCount = (data?.get("count") as? JsonPrimitive)?.intOrNull
        if (declaredCount == 0) return emptyList()
        return results.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            SearchHit(
                cardId = item.string("card_id") ?: return@mapNotNull null,
                name = item.string("name") ?: return@mapNotNull null,
                company = item.string("company").orEmpty(),
                title = item.string("title").orEmpty(),
                location = item.string("location").orEmpty(),
            )
        }
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty)
}

private data class SearchHit(
    val cardId: String,
    val name: String,
    val company: String,
    val title: String,
    val location: String,
)

private sealed interface PendingAction {
    fun missingMessage(): String? = null

    data class ContactSearch(val query: String) : PendingAction

    /** 개수 질문. [condition] 이 비면 전체 개수다. */
    data class ContactCount(val condition: String) : PendingAction

    data class CurrentDateTime(val timezone: String?) : PendingAction

    data class Calendar(
        val title: String,
        val titleExplicit: Boolean,
        val startTime: String?,
        val endTime: String?,
        val relativeDateOffset: Int?,
        val relativeHour: Int?,
        val relativeMinute: Int?,
        val location: String?,
        val description: String?,
        val attendeeEmails: List<String>,
        val contactQuery: String?,
    ) : PendingAction {
        override fun missingMessage(): String? =
            if (startTime == null && !needsCurrentDateTime()) "일정 시작 날짜와 시각을 yyyy-MM-dd HH:mm 형식으로 알려 주세요."
            else null

        fun needsCurrentDateTime(): Boolean =
            startTime == null && relativeDateOffset != null && relativeHour != null

        fun resolveRelativeDateTime(currentDate: String?): Calendar? {
            val base = currentDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
            val hour = relativeHour ?: return null
            val minute = relativeMinute ?: 0
            val start = base.plusDays((relativeDateOffset ?: 0).toLong()).atTime(hour, minute)
            return copy(
                startTime = "%04d-%02d-%02dT%02d:%02d".format(
                    start.year,
                    start.monthValue,
                    start.dayOfMonth,
                    start.hour,
                    start.minute,
                ),
                relativeDateOffset = null,
                relativeHour = null,
                relativeMinute = null,
            )
        }
    }

    data class Compose(
        val channel: String,
        val to: String?,
        val contactQuery: String?,
        val subject: String?,
        val body: String?,
    ) : PendingAction {
        override fun missingMessage(): String? {
            if (to == null && contactQuery == null) {
                return if (channel == CHANNEL_EMAIL) "메일 수신자 이메일 주소나 명함 이름을 알려 주세요."
                else "문자 수신자 전화번호나 명함 이름을 알려 주세요."
            }
            return null
        }
    }

    data class ContactUpdate(
        val cardId: String?,
        val contactQuery: String?,
        val updates: Map<String, String>,
        val clearFields: Set<String>,
    ) : PendingAction {
        override fun missingMessage(): String? {
            if (cardId == null && contactQuery == null) return "수정할 명함의 이름이나 ID를 알려 주세요."
            if (updates.isEmpty() && clearFields.isEmpty()) return "수정하거나 비울 명함 필드를 알려 주세요."
            return null
        }
    }
}

private object LocalPromptRouter {
    fun parse(prompt: String): PendingAction? {
        DateTimePromptParser.parse(prompt)?.let { return it }
        UpdatePromptParser.parse(prompt)?.let { return it }
        ComposePromptParser.parse(prompt)?.let { return it }
        CalendarPromptParser.parse(prompt)?.let { return it }
        // 개수 질문은 검색보다 **먼저** 본다. 검색으로 가면 상위 몇 장만 보고 그 수를 전체라고
        // 답한다(실측: 실제 42명인 질문에 5명).
        CountPromptParser.parse(prompt)?.let { return it }
        return ContactSearchPromptParser.parse(prompt)?.let { PendingAction.ContactSearch(it) }
    }
}

/**
 * 개수를 묻는 말인지, 그리고 조건이 붙었는지 가른다.
 *
 * 검색으로 보내면 안 되는 이유가 측정으로 남아 있다: 검색은 상위 몇 장만 채우므로 그 수를
 * 전체로 착각해 답한다(실기기 실측 — 실제 1000장인데 "총 5명", 실제 42명인데 "5명").
 *
 * 판정은 **일반 낱말을 다 걷어내고 남는 것이 있는지**로 한다. 예전에는 "전체/모두/전부/총"
 * 같은 신호가 있어야 통과시켰는데, 사람들이 가장 자연스럽게 쓰는 표현이 그 신호를 안 쓴다 —
 * "내가 가진 명함 개수 몇개야?", "명함 몇 개 있어?" 가 전부 검색으로 빠졌다. 조건이 있으면
 * ("판교에 몇 명") 그 말이 안 걷히고 남으므로 같은 규칙이 조건 유무까지 함께 가른다.
 */
private object CountPromptParser {
    private val countIntent = Regex("몇|목록|리스트|다 보여|얼마나|개수|장수|전체|전부|모두")
    private val filteredCountSignal = Regex("몇\\s*(명|장|개)")

    /** 긴 것부터 — "내가 가진"이 "내"보다 먼저 걷혀야 한다. */
    private val genericWords = listOf(
        "가지고 있어", "가지고 있는", "가지고있는", "내가 가진", "가진", "가지고",
        "지금", "현재",
        "저장된", "등록된", "있는", "있어", "있나", "있지",
        "보여줘", "알려줘", "찾아줘", "리스트", "목록", "전체", "명함", "이름",
        "카드", "사람", "모두", "전부", "얼마나", "몇", "장수", "개수", "장", "명", "총", "개",
        "내", "제", "다", "이", "야", "어", "지", "나",
        "은", "는", "이야", "인가", "될까",
        "?", "!", ".", ",", " ",
    )

    fun parse(prompt: String): PendingAction.ContactCount? {
        if (!countIntent.containsMatchIn(prompt)) return null
        // 아무것도 안 남으면 조건 없는 전체 질문이다.
        if (strip(prompt).isEmpty()) return PendingAction.ContactCount("")
        // 남은 게 있으면 조건이 붙은 질문이고, 그때는 "몇 명/장/개" 라는 분명한 신호를 요구한다.
        // 목록 요청("판교 사람 보여줘")까지 개수로 답하면 물어본 것과 다른 답이 된다.
        if (!filteredCountSignal.containsMatchIn(prompt)) return null
        // **걷어낸 찌꺼기가 아니라 원문을 넘긴다.** 걷어내기는 조건이 있는지 가르는 용도일 뿐이고,
        // 그 결과를 조건으로 쓰면 낱말이 부서진다("이사 직급" -> "사직급", "판교에" -> "판교에").
        // 조건을 읽는 일은 검색이 쓰는 것과 같은 기계(SearchFieldConstraintResolver)가 한다.
        return PendingAction.ContactCount(prompt)
    }

    private fun strip(prompt: String): String {
        var stripped = prompt
        for (word in genericWords) stripped = stripped.replace(word, "")
        return stripped.trim()
    }
}

internal object ContactSearchPromptParser {
    private val conversationReferenceIntent = Regex(
        "(방금|아까|앞서|이전|지금까지|그동안|방금\\s*전).*(찾은|검색한|조회한).*(사람|명함|연락처)|" +
            "(찾은|검색한|조회한).*(사람|명함|연락처).*(누구|정리|목록|리스트|알려)",
    )
    private val searchIntent = Regex(
        "(명함|연락처|사람|담당자|대표|개발자|contact|business\\s*card|찾|검색|조회)",
        RegexOption.IGNORE_CASE,
    )
    /**
     * Card objects and read verbs, only where they are whole words.
     *
     * Without the guards this stripped the query down to nothing useful whenever the person's own
     * name spelled one of them: 조회연 lost its 조회, 검색희 lost its 검색. The name is the query, so
     * mangling it is a lookup for somebody who does not exist.
     */
    private val removableWords = Regex(
        "(?<![가-힣])(명함|연락처|contact|business\\s*card|찾아\\s*줘|찾아줘|찾아주세요|" +
            "찾아|찾기|검색해\\s*줘|검색해줘|검색해주세요|검색|조회해\\s*줘|조회해줘|조회해주세요|조회|" +
            "보여\\s*줘|보여줘|보여주세요|알려\\s*줘|알려줘|알려주세요)(?![가-힣])",
        RegexOption.IGNORE_CASE,
    )
    /** Particles attach directly to a name, so they get only the trailing guard. */
    private val removableParticles = Regex("(을|를|좀)(?![가-힣])")
    private val whitespace = Regex("\\s+")

    fun isConversationReference(prompt: String): Boolean =
        conversationReferenceIntent.containsMatchIn(prompt.trim())

    fun parse(prompt: String): String? {
        val normalized = prompt.trim()
        if (isConversationReference(normalized)) return null
        if (normalized.isEmpty() || !searchIntent.containsMatchIn(normalized)) return null
        val withoutObjects = removableWords.replace(normalized, " ")
            .replace(Regex("[.!?。]+$"), "")
            .replace(whitespace, " ")
            .trim()
        if (withoutObjects.isEmpty()) return null
        // Exact first. The span the user actually typed is the primary query, because a person's
        // name may itself end in what looks like a particle: stripping 을/를/좀 unconditionally cut
        // 설태을 down to 설태 and sent the lookup after somebody who does not exist. The stripped
        // form is kept only as an additional term, so a real particle ("김민수를") still resolves
        // without the exact form ever being lost.
        val stripped = removableParticles.replace(withoutObjects, " ").replace(whitespace, " ").trim()
        return if (stripped.isEmpty() || stripped == withoutObjects) withoutObjects
        else "$withoutObjects $stripped"
    }
}

private object CalendarPromptParser {
    private val intent = Regex(
        "(캘린더|일정|스케줄|회의|미팅|약속).*(만들|생성|추가|등록|잡아|작성|열어)|" +
            "(만들|생성|추가|등록|잡아|작성|열어).*(캘린더|일정|스케줄|회의|미팅|약속)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Generic calendar objects. A stated title that ends in one of these is naming the *thing* being
     * scheduled, not part of the title: "제목은 분기 리뷰 일정 잡아줘" is a 분기 리뷰, not a
     * "분기 리뷰 일정". Same rule the structured path applies through `CalendarTitlePolicy`.
     */
    private val trailingObjects = listOf("일정", "회의", "미팅", "약속", "스케줄", "캘린더")

    fun parse(prompt: String): PendingAction.Calendar? {
        val normalized = prompt.trim()
        if (normalized.isEmpty() || !intent.containsMatchIn(PersonNameMask.maskNames(normalized))) return null
        val dateTimes = DateTimeTextParser.extractAll(normalized)
        val explicitTitle = extractMarkedValue(normalized, listOf("제목"), listOf("장소", "설명", "메모", "내용"))
            ?.let(::stripTrailingObject)
        val defaultTitle = when {
            Regex("미팅|회의", RegexOption.IGNORE_CASE).containsMatchIn(normalized) -> "회의"
            normalized.contains("약속") -> "약속"
            else -> "일정"
        }
        return PendingAction.Calendar(
            title = explicitTitle ?: defaultTitle,
            titleExplicit = explicitTitle != null,
            startTime = dateTimes.firstOrNull(),
            endTime = dateTimes.drop(1).firstOrNull(),
            relativeDateOffset = if (dateTimes.isEmpty()) relativeDateOffset(normalized) else null,
            relativeHour = if (dateTimes.isEmpty()) ClockTimeParser.extract(normalized)?.first else null,
            relativeMinute = if (dateTimes.isEmpty()) ClockTimeParser.extract(normalized)?.second else null,
            location = extractMarkedValue(normalized, listOf("장소", "위치"), listOf("설명", "메모", "내용")),
            description = extractMarkedValue(normalized, listOf("설명", "메모", "내용"), emptyList()),
            attendeeEmails = EMAIL.findAll(normalized).map { it.value }.distinct().toList(),
            contactQuery = extractContactQuery(normalized),
        )
    }

    /** Drops one trailing generic object, but never leaves the title empty. */
    private fun stripTrailingObject(title: String): String {
        val trimmed = title.trim()
        val suffix = trailingObjects.firstOrNull { trimmed.endsWith(it) } ?: return trimmed
        val remainder = trimmed.removeSuffix(suffix).trim()
        return if (remainder.isEmpty()) trimmed else remainder
    }

    private fun extractContactQuery(prompt: String): String? {
        val match = Regex("""^(.{1,24}?)(?:와|과|하고|랑|에게|께|한테)\s*.*(?:회의|미팅|약속|일정)""")
            .find(prompt)
            ?: return null
        val candidate = cleanRecipient(match.groupValues[1])
        if (candidate.contains(Regex("""\d{4}|\d{1,2}\s*월|\d{1,2}\s*시"""))) return null
        return candidate.takeIf { it.isNotBlank() }
    }

    private fun relativeDateOffset(prompt: String): Int? = when {
        prompt.contains("모레") -> 2
        prompt.contains("내일") -> 1
        prompt.contains("오늘") -> 0
        else -> null
    }
}

/**
 * Whether the turn is asking the clock.
 *
 * The rule itself lives in [CurrentDateTimeIntent] because the pre-router and the workflow validator
 * have to agree with this parser exactly. When each of the three kept its own pattern, "지금 몇 시인지
 * 알려줘" was a clock query to the first two and an unrecognised sentence here, so the turn ran no
 * tool and was then told off by the validator for not having consulted the clock.
 */
private object DateTimePromptParser {
    fun parse(prompt: String): PendingAction.CurrentDateTime? {
        val normalized = prompt.trim()
        if (normalized.isEmpty() || !CurrentDateTimeIntent.isDirectQuery(normalized)) return null
        val timezone = if (Regex("한국|서울|Asia/Seoul", RegexOption.IGNORE_CASE).containsMatchIn(normalized)) "Asia/Seoul" else null
        return PendingAction.CurrentDateTime(timezone)
    }
}

private object UpdatePromptParser {
    private val intent = Regex(ContactReadIntent.CARD_OBJECTS.joinToString("|"))
    /** Shared with the router and the workflow validator; see [CardUpdateIntent]. */
    private val updateWords =
        Regex((CardUpdateIntent.UPDATE_VERBS + CardUpdateIntent.CLEAR_VERBS).joinToString("|"))
    private val clearWords = Regex(CardUpdateIntent.CLEAR_VERBS.joinToString("|"))
    private val cardId = Regex("""\b(C\d{3}|sample-card-\d+)\b""", RegexOption.IGNORE_CASE)
    private val fieldAliases = listOf(
        FieldAlias("memo", Regex("메모|memo", RegexOption.IGNORE_CASE)),
        FieldAlias("email", Regex("이메일|메일\\s*주소|email", RegexOption.IGNORE_CASE)),
        FieldAlias("phone", Regex("전화번호|전화|phone", RegexOption.IGNORE_CASE)),
        FieldAlias("mobile", Regex("휴대폰|모바일|핸드폰|mobile", RegexOption.IGNORE_CASE)),
        FieldAlias("title", Regex("직함|직책|title", RegexOption.IGNORE_CASE)),
        FieldAlias("company", Regex("회사|company", RegexOption.IGNORE_CASE)),
        FieldAlias("department", Regex("부서|department", RegexOption.IGNORE_CASE)),
        FieldAlias("name", Regex("이름|name", RegexOption.IGNORE_CASE)),
        FieldAlias("address", Regex("주소|address", RegexOption.IGNORE_CASE)),
        FieldAlias("website", Regex("웹사이트|홈페이지|website", RegexOption.IGNORE_CASE)),
        FieldAlias("location", Regex("지역|위치|location", RegexOption.IGNORE_CASE)),
        FieldAlias("industry", Regex("업종|industry", RegexOption.IGNORE_CASE)),
    )

    fun parse(prompt: String): PendingAction.ContactUpdate? {
        val normalized = prompt.trim()
        // 서수정 is a person; only an edit verb outside every name span makes this an edit.
        val forIntent = PersonNameMask.maskNames(normalized)
        if (normalized.isEmpty() || !updateWords.containsMatchIn(forIntent)) return null
        val field = fieldAliases.firstOrNull { it.pattern.containsMatchIn(forIntent) }
        // Once a reference has been resolved the sentence names the person, not the object:
        // "김지원 메모를 VIP로 수정해줘" is the same request as "김지원 명함 수정해줘". Requiring the
        // literal word 명함 made every resolved-reference edit unparseable.
        // 칸도 카드도 안 말한 수정("수정해줘")도 받는다. 예전에는 여기서 null 이라 기능
        // 안내문으로 끝났는데, 사용자는 이미 "손재민씨 명함 좀 고쳐야 해" 로 대상을 말한
        // 뒤였다. 대상은 withImplicitTarget 이 세션에서 채우고, 무엇을 고칠지는
        // missingMessage 가 되묻는다 — 그게 이 상황에 맞는 답이다.
        val clear = clearWords.containsMatchIn(forIntent)
        val updates = linkedMapOf<String, String>()
        val clearFields = linkedSetOf<String>()
        if (field != null) {
            if (clear) clearFields += field.name
            else extractUpdateValue(normalized, field.pattern)?.let { updates[field.name] = it }
        }
        val id = cardId.find(normalized)?.value
        val query = if (id == null) extractContactQuery(normalized, field?.pattern) else null
        return PendingAction.ContactUpdate(id, query, updates, clearFields)
    }

    private fun extractContactQuery(prompt: String, field: Regex?): String? {
        val beforeCard = prompt.substringBefore("명함", missingDelimiterValue = "")
            .ifBlank { prompt.substringBefore("연락처", missingDelimiterValue = "") }
            // Without a 명함/연락처 anchor the person is whatever precedes the field being edited.
            .ifBlank { field?.find(prompt)?.let { prompt.substring(0, it.range.first) }.orEmpty() }
        return cleanRecipient(beforeCard).takeIf(String::isNotBlank)
    }

    private fun extractUpdateValue(prompt: String, fieldPattern: Regex): String? {
        val fieldMatch = fieldPattern.find(prompt) ?: return null
        val tail = prompt.substring(fieldMatch.range.last + 1)
            .replace(Regex("""^\s*(을|를|은|는|:)\s*"""), "")
        val value = Regex("""(.+?)\s*(?:${CardUpdateIntent.UPDATE_VERBS.joinToString("|")})""").find(tail)
            ?.groupValues
            ?.get(1)
            ?: return null
        return cleanExtractedText(value)
            .replace(Regex("(으로|로|라고)$"), "")
            .trim()
            .takeIf(String::isNotEmpty)
    }

    private data class FieldAlias(val name: String, val pattern: Regex)
}

private object ComposePromptParser {
    private val emailIntent = Regex("이메일|메일|email|e-mail|mail|gmail", RegexOption.IGNORE_CASE)
    private val smsIntent = Regex("문자|sms|메시지|message", RegexOption.IGNORE_CASE)
    private val actionIntent = Regex("작성|써|보내|전송|열어|초안", RegexOption.IGNORE_CASE)

    fun parse(prompt: String): PendingAction.Compose? {
        val normalized = prompt.trim()
        if (normalized.isEmpty()) return null
        // Which channel this is gets decided on the name-masked text: 문자현 is a person, not an SMS.
        // Everything extracted afterwards — recipient, subject, body — reads the original.
        val forIntent = PersonNameMask.maskNames(normalized)
        val email = EMAIL.find(normalized)?.value
        val phone = PHONE.find(normalized)?.value
        val channel = when {
            smsIntent.containsMatchIn(forIntent) -> CHANNEL_SMS
            emailIntent.containsMatchIn(forIntent) -> CHANNEL_EMAIL
            email != null && actionIntent.containsMatchIn(forIntent) -> CHANNEL_EMAIL
            else -> null
        } ?: return null

        val directTo = if (channel == CHANNEL_EMAIL) email else phone
        val contactQuery = if (directTo == null) extractContactQuery(normalized) else null
        return PendingAction.Compose(
            channel = channel,
            to = directTo,
            contactQuery = contactQuery,
            subject = if (channel == CHANNEL_EMAIL) extractMarkedValue(normalized, listOf("제목"), listOf("본문", "내용", "메시지")) else null,
            body = extractBody(normalized),
        )
    }

    private fun extractContactQuery(prompt: String): String? {
        val match = Regex("""^(.{1,32}?)(?:에게|께|한테|으로|로)\s*.*(?:메일|이메일|문자|메시지|sms)""", RegexOption.IGNORE_CASE)
            .find(prompt)
            ?: return null
        return cleanRecipient(match.groupValues[1]).takeIf { it.isNotBlank() }
    }

    private fun extractBody(prompt: String): String? {
        QUOTED.findAll(prompt).lastOrNull()?.groupValues?.get(1)?.let { quoted ->
            return quoted.trim().takeIf(String::isNotEmpty)
        }
        extractMarkedValue(prompt, listOf("본문", "내용", "메시지"), emptyList())?.let { return it }
        Regex("""(.+?(?:라고|이라고|다고))\s*(?:메일|이메일|문자|메시지|sms)""", RegexOption.IGNORE_CASE)
            .find(prompt)
            ?.groupValues
            ?.get(1)
            ?.let { return stripRecipientPrefix(it).takeIf(String::isNotEmpty) }
        return null
    }
}

private object DateTimeTextParser {
    private val isoDateTime = Regex(
        """(\d{4})[-./](\d{1,2})[-./](\d{1,2})(?:[ T]|에\s*)?(오전|오후|am|pm|AM|PM)?\s*(\d{1,2})(?:(?::|시\s*)(\d{1,2})?\s*분?)?""",
    )
    private val koreanDateTime = Regex(
        """(\d{4})\s*년\s*(\d{1,2})\s*월\s*(\d{1,2})\s*일\s*(오전|오후|am|pm|AM|PM)?\s*(\d{1,2})\s*시(?:\s*(\d{1,2})\s*분?)?""",
    )

    fun extractAll(text: String): List<String> {
        val matches = (isoDateTime.findAll(text) + koreanDateTime.findAll(text))
            .sortedBy { it.range.first }
            .mapNotNull { toDateTimeString(it) }
            .distinct()
            .toList()
        return matches
    }

    private fun toDateTimeString(match: MatchResult): String? {
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: return null
        val meridiem = match.groupValues[4].takeIf(String::isNotBlank)
        val hourRaw = match.groupValues[5].toIntOrNull() ?: return null
        val minute = match.groupValues[6].takeIf(String::isNotBlank)?.toIntOrNull() ?: 0
        val hour = normalizeHour(hourRaw, meridiem) ?: return null
        return try {
            val dateTime = LocalDateTime.of(year, month, day, hour, minute)
            "%04d-%02d-%02dT%02d:%02d".format(
                dateTime.year,
                dateTime.monthValue,
                dateTime.dayOfMonth,
                dateTime.hour,
                dateTime.minute,
            )
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun normalizeHour(hour: Int, meridiem: String?): Int? {
        if (hour !in 0..23) return null
        return when (meridiem?.lowercase()) {
            "오전", "am" -> if (hour == 12) 0 else hour
            "오후", "pm" -> if (hour < 12) hour + 12 else hour
            else -> hour
        }.takeIf { it in 0..23 }
    }
}

private object ClockTimeParser {
    private val clock = Regex("""(오전|오후|am|pm|AM|PM)?\s*(\d{1,2})\s*시(?:\s*(\d{1,2})\s*분?)?""")

    fun extract(text: String): Pair<Int, Int>? {
        val match = clock.find(text) ?: return null
        val meridiem = match.groupValues[1].takeIf(String::isNotBlank)
        val hourRaw = match.groupValues[2].toIntOrNull() ?: return null
        val minute = match.groupValues[3].takeIf(String::isNotBlank)?.toIntOrNull() ?: 0
        val hour = normalizeHour(hourRaw, meridiem) ?: return null
        if (minute !in 0..59) return null
        return hour to minute
    }

    private fun normalizeHour(hour: Int, meridiem: String?): Int? {
        if (hour !in 0..23) return null
        return when (meridiem?.lowercase()) {
            "오전", "am" -> if (hour == 12) 0 else hour
            "오후", "pm" -> if (hour < 12) hour + 12 else hour
            else -> hour
        }.takeIf { it in 0..23 }
    }
}

private fun extractMarkedValue(prompt: String, markers: List<String>, stopMarkers: List<String>): String? {
    val markerPattern = markers.joinToString("|") { Regex.escape(it) }
    // 잡아/열어/예약 are the verbs a Korean scheduling request ends with, and their absence is why a
    // stated title swallowed the rest of the sentence: "제목은 분기 리뷰 일정 잡아줘" came back as
    // "분기 리뷰 일정 잡아줘" because nothing told the match where the title stopped.
    val stopPattern = (
        stopMarkers +
            listOf("작성", "보내", "전송", "만들", "생성", "추가", "등록", "잡아", "잡자", "열어", "예약")
        )
        .distinct()
        .joinToString("|") { Regex.escape(it) }
    val regex = Regex("""(?:$markerPattern)\s*(?:은|는|:)?\s*(.+?)(?=(?:$stopPattern)\s*(?:은|는|:)?|[,.。]|$)""")
    return regex.find(prompt)
        ?.groupValues
        ?.get(1)
        ?.let(::cleanExtractedText)
        ?.takeIf(String::isNotEmpty)
}

private fun cleanExtractedText(value: String): String =
    value.replace(TRAILING_COMMANDS, " ")
        .replace(Regex("\\s*(이라고|라고)\\s*(메일|이메일|문자|메시지|sms)\\s*$", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\s*(메일|이메일|문자|메시지|sms)\\s*$", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("(이라고|라고)$"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim(',', '.', '。', ' ', '"', '\'', '“', '”', '‘', '’')

private fun cleanRecipient(value: String): String =
    value.replace(EMAIL, " ")
        .replace(PHONE, " ")
        // Whole words only. Unguarded, this turned 문자현 into 현 and 서수정 into 서, so the lookup
        // went out for a person who does not exist.
        .replace(
            Regex("(?<![가-힣])(일정|회의|미팅|약속|메일|이메일|문자|메시지|작성|보내|전송|열어|초안|좀)(?![가-힣])"),
            " ",
        )
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim(',', '.', '。')

private fun stripRecipientPrefix(value: String): String =
    value.substringAfterLast("에게")
        .substringAfterLast("한테")
        .substringAfterLast("께")
        .substringAfterLast("으로")
        .substringAfterLast("로")
        .let(::cleanExtractedText)

private const val SEARCH_CONTACTS = "search_contacts"
private const val NEWLINE = "\n"
private const val GET_CONTACT = "get_contact"
private const val COUNT_CONTACTS = "count_contacts"
private const val GET_CURRENT_DATETIME = "get_current_datetime"
private const val CREATE_CALENDAR_EVENT = "create_calendar_event"
private const val OPEN_COMPOSE = "open_compose"
private const val UPDATE_BUSINESS_CARD = "update_business_card"
private const val CHANNEL_EMAIL = "email"
private const val CHANNEL_SMS = "sms"

private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
private val PHONE = Regex("""(?:\+?\d{1,3}[-\s]?)?0\d{1,2}[-\s]?\d{3,4}[-\s]?\d{4}""")
private val QUOTED = Regex("""["'“”‘’]([^"'“”‘’]+)["'“”‘’]""")
private val TRAILING_COMMANDS = Regex(
    "(메일|이메일|문자|메시지|sms)?\\s*(작성|써|보내|전송|열어)\\s*(해\\s*줘|해주세요|줘|요)?$",
    RegexOption.IGNORE_CASE,
)
