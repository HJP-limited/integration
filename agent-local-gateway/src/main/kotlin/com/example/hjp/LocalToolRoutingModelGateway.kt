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
import com.hjp.agent.core.CardUpdateIntent
import com.hjp.agent.core.ContactReadIntent
import com.hjp.agent.core.CurrentDateTimeIntent
import com.hjp.agent.core.PersonNameMask
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

    override suspend fun decide(input: ModelInput): ModelDecision = when (input) {
        is ModelInput.User -> routeUserInput(input)
    }

    override suspend fun continueWithToolResult(result: ModelToolResponse): ModelDecision = when (result.modelToolName) {
        SEARCH_CONTACTS -> continueAfterSearch(result)
        GET_CONTACT -> continueAfterGetContact(result)
        GET_CURRENT_DATETIME -> continueAfterCurrentDateTime(result)
        CREATE_CALENDAR_EVENT -> finishExternalUi(result, "캘린더 작성 화면을 열었습니다. 저장 전에 확인해 주세요.")
        OPEN_COMPOSE -> finishExternalUi(result, composeSuccessMessage())
        UPDATE_BUSINESS_CARD -> finishUpdate(result)
        else -> ModelDecision.Invalid("예상하지 않은 도구 결과를 받았습니다.", retryable = false)
    }

    override fun streamFinal(input: FinalAnswerInput): Flow<String> = flowOf(input.draftText)

    override fun close() = Unit

    private fun routeUserInput(input: ModelInput.User): ModelDecision {
        val text = input.text
        turnContext = input.turnContext
        val action = LocalPromptRouter.parse(text)?.let { withImplicitTarget(it, input.turnContext) }
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
        return when (action) {
            is PendingAction.ContactSearch -> {
                // The pre-router already grounded this reference on a verified card, so re-reading
                // that card is both cheaper and safer than searching a rewritten name again.
                val grounded = turnContext?.groundedCardId
                if (grounded != null && hasTool(GET_CONTACT)) getContactToolCall(grounded, "display")
                else if (!hasTool(SEARCH_CONTACTS)) unavailable("명함 검색")
                else searchToolCall(action.query)
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
                else if (action.contactQuery != null) contactLookupStart(action.contactQuery)
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
        if (verified != null && (query.isBlank() || query.noSpaces().contains(verified.name.noSpaces()))) {
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
        if (
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
                val details = listOf("company", "title", "email", "mobile", "phone")
                    .mapNotNull { field -> contact.string(field)?.takeIf(String::isNotBlank) }
                ModelDecision.FinalCandidate(
                    "${contact.string("name").orEmpty()} 명함입니다.\n- ${details.joinToString(" · ")}",
                )
            }
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
    ): ModelDecision.ToolCalls =
        ModelDecision.ToolCalls(listOf(ModelToolCall(
            callId = UUID.randomUUID().toString(),
            modelToolName = modelToolName,
            arguments = buildJsonObject(arguments),
        )))

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
        return ContactSearchPromptParser.parse(prompt)?.let { PendingAction.ContactSearch(it) }
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
        if (!intent.containsMatchIn(forIntent) && field == null) return null
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
private const val GET_CONTACT = "get_contact"
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
