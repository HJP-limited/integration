package com.hjp.agent.core

import com.hjp.agent.contract.ModelWorkflowNote
import com.hjp.agent.contract.ModelToolCall
import com.hjp.agent.contract.ModelToolResponse
import com.hjp.tool.contract.ToolContract
import com.hjp.tool.contract.ToolExecutionResult
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

enum class WorkflowRejectReason {
    TOOL_NOT_ALLOWED,
    TOOL_NOT_REQUESTED,
    UNSUPPORTED_REQUEST,
    MISSING_REQUIRED_INFORMATION,
    INVALID_ARGUMENTS,
    INVALID_EMAIL,
    INVALID_PHONE,
    INVALID_DATETIME,
    CONTACT_LOOKUP_REQUIRED,
    CONTACT_NOT_FOUND,
    CONTACT_SELECTION_REQUIRED,
    CONTACT_DETAIL_REQUIRED,
    CONTACT_VALUE_NOT_VERIFIED,
    CURRENT_DATETIME_REQUIRED,
    UNNECESSARY_TOOL_CALL,
    WORKFLOW_ORDER_VIOLATION,
    REPEATED_TOOL_CALL,
    TOOL_CALL_LIMIT_REACHED,
}

data class WorkflowRejection(
    val reason: WorkflowRejectReason,
    val messageKo: String,
    val allowedNextTools: Set<String> = emptySet(),
    /** Exact safe arguments for the next read when the target is already grounded. */
    val repairArguments: JsonObject? = null,
)

sealed interface WorkflowValidationResult {
    data object Allow : WorkflowValidationResult
    data class Reject(val rejection: WorkflowRejection) : WorkflowValidationResult
}

sealed interface WorkflowFinalValidationResult {
    data object Allow : WorkflowFinalValidationResult
    data class Replace(
        val safeMessageKo: String,
        val reason: WorkflowRejectReason,
    ) : WorkflowFinalValidationResult
}

fun interface AgentWorkflowPolicy {
    fun startTurn(userText: String, deviceTimeZoneId: String): AgentWorkflowSession
}

class ProductionAgentWorkflowPolicy : AgentWorkflowPolicy {
    override fun startTurn(userText: String, deviceTimeZoneId: String) =
        AgentWorkflowSession(userText.trim())
}

/**
 * Per-turn state machine. It never creates a tool call; it only validates a
 * model-originated call and records trusted tool results.
 */
/**
 * A slot an action tool cannot run without.
 *
 * Typed rather than a string so the question the user sees is derived from the slot, and a new slot
 * cannot be added without deciding what to ask about it.
 */
enum class MissingSlot(val questionKo: String) {
    COMPOSE_RECIPIENT("누구에게 보낼지 알려 주세요. 이 대화에서 확인된 연락처가 없습니다."),
    CALENDAR_START_TIME("일정을 언제로 잡을지 날짜와 시간을 알려 주세요."),
    UPDATE_FIELD("명함에서 수정할 필드와 새 값을 알려 주세요."),
    UPDATE_TARGET("어떤 분의 명함을 수정할지 알려 주세요."),
}

class AgentWorkflowSession internal constructor(
    private val userText: String,
) {
    private val successfulTools = mutableSetOf<String>()
    private val failedTools = mutableSetOf<String>()
    private var searchResultIds: List<String>? = null
    private var selectedContact: JsonObject? = null
    private var selectedContactPurpose: String? = null
    private var currentDate: LocalDate? = null
    private var lastRejection: WorkflowRejection? = null
    // A terminal call rejected only because its contact has not been freshly read remains an
    // obligation for this turn. A successful get_contact clears the rejection, but must not erase
    // the action the user asked for.
    private var pendingTerminalAfterFreshRead: ModelToolCall? = null
    private var terminalExecutionFailure = false
    // Opening the Calendar insert surface is deliberately not a saved event.  Keep this as a
    // typed result fact instead of trying to recognize every completion phrase a model may use.
    private var calendarScreenAwaitingUserSave = false
    private var composeScreenAwaitingUserSend = false
    private var trustedSessionContactTarget = false
    private var contactSearchRequestedByRouter = false
    /** A prior search left multiple candidates and no user selection; contact-bound actions must
     * remain fail-closed until that clarification is resolved. */
    private var unresolvedContactObligation = false

    private val directEmails = EMAIL_REGEX.findAll(userText).map { it.value.lowercase() }.toSet()
    private val directPhones = PHONE_REGEX.findAll(userText).map { normalizePhone(it.value) }.toSet()
    private val previewOnly = PREVIEW_MARKERS.any(userText::contains) &&
        !EXTERNAL_UI_MARKERS.any(userText::contains)
    private val unsupportedRequest = UNSUPPORTED_REQUEST_MARKERS.any(userText::contains)
    private val explicitRealSend = REAL_SEND_MARKERS.any(userText::contains)
    private val invalidEmailLike = (
        INVALID_EMAIL_LIKE_REGEX.containsMatchIn(userText) && directEmails.isEmpty()
    ) || MALFORMED_SPACED_EMAIL_REGEX.containsMatchIn(userText)
    /**
     * Whether this request's target is a person named in the sentence.
     *
     * The two regexes below find a short Hangul run in front of a dative particle or 명함, which is
     * where a person's name sits — and also where a job title sits. Accepting a job title here made
     * "품질팀장에게 메일 작성해줘" a contact-bound request whose target was whoever currently holds
     * that role, and the compose validator then happily accepted their verified address. A role is
     * not an identifier, so [PersonNameMask.namesARoleOrOrganisation] rules those spans out and the
     * turn ends up where "전략기획 부서에" and "한빛물산에" already ended up: asking which person.
     */
    private val contactNameTarget = directEmails.isEmpty() && directPhones.isEmpty() &&
        (namesAContactTarget(NAME_RECIPIENT_REGEX) || namesAContactTarget(NAME_CARD_REGEX))
    /**
     * The request with person-name spans blanked, for keyword tests only.
     *
     * Asked of the raw sentence, "does this mention 일정?" cannot tell a scheduling request from a
     * contact named 김일정, and "does this mention 문자?" cannot tell an SMS from 문자현. Everything
     * that needs the real words — the recipient, the body, the dates — keeps reading [userText].
     */
    private val maskedUserText = PersonNameMask.maskNames(userText)

    private val calendarIntent = CALENDAR_MARKERS.any(maskedUserText::contains)
    private val composeIntent = COMPOSE_MARKERS.any(maskedUserText::contains)
    /**
     * A card edit is identified by the verb plus what it acts on. Requiring the literal word 명함
     * missed every turn where the reference had already been resolved — "김지원 메모를 VIP로 수정해줘"
     * is the same request as "김지원 명함 수정해줘", and rejecting it as "not an update" made a
     * correct model call unexecutable.
     */
    private val updateIntent = CardUpdateIntent.hasUpdateVerb(userText) &&
        (CARD_OBJECT_MARKERS.any(userText::contains) || CARD_FIELD_MARKERS.any(userText::contains))
    /** An edit can only run once the field *and* its new value are both on the table. */
    private val updateSlotsComplete = CARD_FIELD_MARKERS.any(userText::contains) &&
        UPDATE_VALUE_REGEX.containsMatchIn(userText)
    /**
     * Reading contacts is "a card object plus a read verb", not a fixed list of phrasings. The
     * literal-phrase list accepted "명함 찾아줘" but rejected "명함 검색해줘" and "연락처 조회해줘",
     * which made an ordinary search unexecutable depending on which synonym the user picked.
     */
    private val contactReadIntent = ContactReadIntent.isCardRead(userText)
    private val relativeDateIntent = calendarIntent && RELATIVE_DATE_MARKERS.any(userText::contains)
    /**
     * "지금은 언제인가" in any of its ordinary forms, and only when nothing more specific owns the
     * time expression. The rule lives in [CurrentDateTimeIntent] because the pre-router and the
     * emulator gateway have to reach the same verdict; three private copies of it disagreed on
     * "지금 몇 시인지 알려줘", so the turn was routed as a clock query and then validated as one that
     * had failed to consult the clock.
     */
    private val currentTimeIntent = CurrentDateTimeIntent.isDirectQuery(userText)
    /**
     * Whether the request states a time of day, decided by the shared temporal vocabulary.
     *
     * The private pattern this replaces recognised only 오전/오후 + digits + 시, so 14시, 2시, 정오,
     * 자정, 새벽 3시 and 14:30 all counted as "no time given" — and once a calendar request without a
     * time had to ask for one, those complete requests became questions.
     */
    private val calendarTimeProvided = TemporalSlotIntent.hasTime(userText)

    /** Whether the request states a day. A date is not a time; they are separate slots. */
    private val calendarDateProvided = TemporalSlotIntent.hasDate(userText)

    /**
     * Whether the request states a *start instant* — which needs both halves.
     *
     * `create_calendar_event` takes one `start_time`, and neither half alone produces one. The two
     * facts above were computed and then only the time was ever read, so "3시에 일정 잡아줘" counted
     * as a complete request: the model boundary correctly answered "which day?", and this policy
     * then overwrote that question with "일정 작성 절차를 완료하지 못했습니다. 다시 시도해 주세요."
     * — an apology for not doing something the user had not finished asking for.
     *
     * A day with no hour was already handled; an hour with no day now is too, by the same rule.
     */
    private val calendarStartProvided = calendarTimeProvided && calendarDateProvided

    /** Seeds only a card ID previously emitted by search_contacts in this same agent session. */
    fun seedTrustedContactProvenance(cardId: String) {
        val value = cardId.trim()
        if (value.isNotEmpty()) {
            searchResultIds = listOf(value)
            trustedSessionContactTarget = true
        }
    }

    /**
     * Carries the deterministic router's classification into this per-turn validator.
     *
     * The workflow deliberately does not try to rediscover a person-search request from the raw
     * text: that used to reject a model call for a valid short form such as "우성씨 찾아줘" because
     * it has no literal "명함" or "연락처" token.  The router has already made that decision using
     * the same catalog and typed directory evidence that protects the rest of the turn.  Keeping
     * the fact here means the validator accepts only a search call on a router-classified contact
     * search turn; an arbitrary model-originated search remains rejected.
     */
    fun seedContactSearchIntent() {
        contactSearchRequestedByRouter = true
    }

    /** Carries the session's active multi-candidate clarification into this turn's validator. */
    fun seedUnresolvedContactObligation(candidateCount: Int) {
        if (candidateCount > 1) unresolvedContactObligation = true
    }

    /** Purpose a fresh contact read must use for this request's terminal action. */
    fun requiredFreshReadPurpose(): String = when {
        updateIntent -> "display"
        calendarIntent && !composeIntent -> "calendar"
        composeIntent && SMS_MARKERS.any(maskedUserText::contains) -> "sms"
        composeIntent -> "email"
        else -> "display"
    }

    /**
     * The call as it will be executed, with any argument whose form is merely a notation difference
     * put into canonical form.
     *
     * Deliberately narrow: it never invents, completes or corrects a *value*. Besides dropping a
     * zero seconds datetime notation, it removes a field from `clear_fields` when that exact field
     * is also present in `updates`: writing a new value is the unambiguous operation, while doing
     * both is an invalid self-contradiction. All other values remain exactly as the model wrote
     * them so [validate] can refuse them and say why.
     */
    fun normalizeArguments(call: ModelToolCall, contract: ToolContract): ModelToolCall {
        return when (contract.modelName) {
            CREATE_CALENDAR_EVENT -> normalizeVerifiedCalendarAttendee(
                normalizeCalendarDateTimes(call),
            )
            UPDATE_BUSINESS_CARD -> normalizeUpdateClearFields(call)
            OPEN_COMPOSE -> normalizeVerifiedComposeRecipient(call)
            else -> call
        }
    }


    private fun normalizeCalendarDateTimes(call: ModelToolCall): ModelToolCall {
        if (!calendarArgumentOwnershipEligible()) return normalizeCalendarDateTimesLegacy(call)
        val expected = expectedDate()?.let { date -> expectedTime()?.let { time -> LocalDateTime.of(date, time).toString() } }
        if (expected == null || call.arguments.string("start_time") == expected) return call
        val arguments = buildJsonObject {
            call.arguments.forEach { (key, value) -> if (key == "start_time") put(key, expected) else put(key, value) }
        }
        return call.copy(arguments = arguments)
    }

    private fun calendarArgumentOwnershipEligible(): Boolean =
        pendingTerminalAfterFreshRead?.modelToolName == CREATE_CALENDAR_EVENT &&
            calendarIntent && selectedContactPurpose == "calendar" &&
            searchResultIds?.singleOrNull() == selectedContact?.string("card_id") &&
            selectedContact?.string("email")?.takeIf(EMAIL_REGEX::matches) != null &&
            expectedDate() != null && expectedTime() != null &&
            (!relativeDateIntent || currentDate != null)

    private fun normalizeCalendarDateTimesLegacy(call: ModelToolCall): ModelToolCall {
        val rewritten = DATETIME_ARGUMENTS.mapNotNull { name ->
            val raw = call.arguments.string(name) ?: return@mapNotNull null
            val canonical = LocalDateTimeCanonicalizer.canonicalOrNull(raw) ?: return@mapNotNull null
            // Relative dates are resolved from the tool-verified device date.  Preserve the
            // model's time component, but never let it substitute a different calendar day.
            val relative = expectedDate()?.let { expected ->
                runCatching { LocalDateTime.parse(canonical) }.getOrNull()
                    ?.withYear(expected.year)?.withMonth(expected.monthValue)?.withDayOfMonth(expected.dayOfMonth)
                    ?.toString()
            } ?: canonical
            if (relative == raw) null else name to relative
        }
        if (rewritten.isEmpty()) return call
        val arguments = buildJsonObject {
            call.arguments.forEach { (key, value) ->
                val replacement = rewritten.firstOrNull { it.first == key }?.second
                if (replacement != null) put(key, replacement) else put(key, value)
            }
        }
        return call.copy(arguments = arguments)
    }

    private fun normalizeUpdateClearFields(call: ModelToolCall): ModelToolCall {
        val updates = call.arguments["updates"] as? JsonObject ?: return call
        val clearFields = call.arguments["clear_fields"] as? JsonArray ?: return call
        val remaining = clearFields.filterNot { field ->
            (field as? JsonPrimitive)?.content in updates.keys
        }
        if (remaining.size == clearFields.size) return call
        val arguments = buildJsonObject {
            call.arguments.forEach { (key, value) ->
                if (key != "clear_fields") {
                    put(key, value)
                } else if (remaining.isNotEmpty()) {
                    put(key, JsonArray(remaining))
                }
            }
        }
        return call.copy(arguments = arguments)
    }

    /**
     * A contact card id is an execution target, never an e-mail recipient. Once this turn has
     * obtained that target through get_contact(purpose=email), the verified result is the sole
     * provenance allowed for `to`. This deliberately cannot run before the fresh read, because
     * [selectedContactPurpose] is then null and the fresh-read gate remains responsible for repair.
     */
    private fun normalizeVerifiedComposeRecipient(call: ModelToolCall): ModelToolCall {
        if (call.arguments.string("channel")?.lowercase() != "email" ||
            selectedContactPurpose != "email"
        ) return call
        val selectedId = selectedContact?.string("card_id") ?: return call
        // The recipient is authoritative only when this exact target was freshly read for
        // the current email workflow.  The model's `to` is deliberately not used as a gate:
        // it may be a stale address, a card id, or malformed text.  It is replaced only after
        // the provenance checks below succeed; subject/body and every other argument remain
        // model-owned.
        if (searchResultIds?.singleOrNull() != selectedId) return call
        val verifiedEmail = selectedContact?.string("email")
            ?.lowercase()
            ?.takeIf(EMAIL_REGEX::matches)
            ?: return call
        val arguments = buildJsonObject {
            call.arguments.forEach { (key, value) ->
                if (key == "to") put(key, verifiedEmail) else put(key, value)
            }
        }
        return call.copy(arguments = arguments)
    }

    /**
     * A calendar attendee is an e-mail address, never a contact card id or display name. The
     * model commonly carries the selected person's label forward after the fresh calendar read;
     * resolve only that exact, single target from the result just returned by get_contact. This
     * is deliberately narrower than general name resolution: it cannot add attendees, select a
     * candidate, or substitute an unverified address.
     */
    private fun normalizeVerifiedCalendarAttendee(call: ModelToolCall): ModelToolCall {
        if (!calendarArgumentOwnershipEligible()) return call
        val contact = selectedContact ?: return call
        val verifiedEmail = contact.string("email")
            ?.lowercase()
            ?.takeIf(EMAIL_REGEX::matches)
            ?: return call
        val arguments = buildJsonObject {
            call.arguments.forEach { (key, value) ->
                if (key == "attendee_emails") {
                    put(key, JsonArray(listOf(JsonPrimitive(verifiedEmail))))
                } else {
                    put(key, value)
                }
            }
        }
        return call.copy(arguments = arguments)
    }

    fun validate(call: ModelToolCall, contract: ToolContract): WorkflowValidationResult {
        if (unsupportedRequest) {
            return reject(
                WorkflowRejectReason.UNSUPPORTED_REQUEST,
                "요청한 기능은 현재 지원하지 않습니다.",
            )
        }
        if (explicitRealSend) {
            return reject(
                WorkflowRejectReason.UNSUPPORTED_REQUEST,
                "이메일이나 문자를 직접 전송할 수 없습니다. 작성 화면만 열 수 있습니다.",
            )
        }
        if (unresolvedContactDownstream() && call.modelToolName in CONTACT_DEPENDENT_TOOLS) {
            return reject(
                WorkflowRejectReason.CONTACT_SELECTION_REQUIRED,
                "같은 이름의 연락처가 여러 명입니다. 사용자가 대상을 선택해야 합니다.",
            )
        }
        if (previewOnly) {
            return reject(
                WorkflowRejectReason.TOOL_NOT_REQUESTED,
                "초안이나 예시만 요청되어 도구를 실행하지 않습니다.",
            )
        }
        if (invalidEmailLike && call.modelToolName in setOf(SEARCH_CONTACTS, GET_CONTACT, OPEN_COMPOSE)) {
            return reject(
                WorkflowRejectReason.INVALID_EMAIL,
                "입력한 이메일 주소 형식이 올바르지 않습니다. 정확한 주소를 확인해 주세요.",
            )
        }
        if (calendarIntent && !calendarStartProvided &&
            call.modelToolName in setOf(GET_CURRENT_DATETIME, CREATE_CALENDAR_EVENT)
        ) {
            return reject(
                WorkflowRejectReason.MISSING_REQUIRED_INFORMATION,
                "일정을 만들려면 시작 날짜와 시각이 필요합니다.",
            )
        }

        // A contact-bound terminal action never consumes a projection from an earlier turn. The
        // per-turn workflow starts empty and accepts only a get_contact result for this grounded id
        // and this action's purpose. This gate runs before tool-specific argument checks so a model
        // that writes `to=<card_id>` receives the recoverable read step, not a dead-end INVALID_EMAIL.
        contactFreshReadGate(call)?.let { return it }
        validateSchema(call.arguments, contract.inputSchema)?.let {
            return WorkflowValidationResult.Reject(it)
        }

        return when (call.modelToolName) {
            SEARCH_CONTACTS -> validateSearch()
            GET_CONTACT -> validateGetContact(call)
            OPEN_COMPOSE -> validateCompose(call)
            CREATE_CALENDAR_EVENT -> validateCalendar(call)
            UPDATE_BUSINESS_CARD -> validateUpdate(call)
            GET_CURRENT_DATETIME -> validateCurrentDatetime()
            // Registry membership is the allowlist. Domain-specific ordering
            // rules apply only to the production tools known here.
            else -> WorkflowValidationResult.Allow
        }
    }

    /**
     * True when a tool in this turn failed and nothing later recovered it.
     *
     * The kernel needs this to close the turn as FAILED: a turn that ends with a polite sentence but
     * a broken tool call is still a failure, and recording it as COMPLETED left "방금 그거 왜
     * 실패했어?" with nothing to explain.
     */
    val hasTerminalExecutionFailure: Boolean get() = terminalExecutionFailure

    /** A rejected call that was not followed by a successful repair. */
    val hasUnrecoveredRejection: Boolean get() = lastRejection != null

    /** User-level reason for [hasTerminalExecutionFailure]. Never a raw exception. */
    var failureDetailKo: String? = null
        private set

    fun recordResult(call: ModelToolCall, result: ToolExecutionResult) {
        if (result !is ToolExecutionResult.Success) {
            failedTools += call.modelToolName
            terminalExecutionFailure = true
            failureDetailKo = (result as? ToolExecutionResult.Failure)?.error?.safeMessageKo
                ?.takeIf(String::isNotBlank)
                ?: "‘${call.modelToolName}’ 실행에 실패했습니다."
            return
        }
        // A retry that succeeds recovers the earlier failure of that same tool.
        failedTools -= call.modelToolName
        if (failedTools.isEmpty()) {
            terminalExecutionFailure = false
            failureDetailKo = null
        }
        lastRejection = null
        successfulTools += call.modelToolName
        if (call.modelToolName == pendingTerminalAfterFreshRead?.modelToolName) {
            pendingTerminalAfterFreshRead = null
        }
        when (call.modelToolName) {
            SEARCH_CONTACTS -> {
                val results = result.data["results"] as? JsonArray
                searchResultIds = results.orEmpty().mapNotNull { item ->
                    ((item as? JsonObject)?.get("card_id") as? JsonPrimitive)
                        ?.content?.trim()?.takeIf(String::isNotEmpty)
                }
            }
            GET_CONTACT -> {
                val requestedId = call.arguments.string("card_id")
                val returnedId = result.data.string("card_id")
                if (requestedId != null && requestedId == returnedId) {
                    selectedContact = result.data
                    selectedContactPurpose = call.arguments.string("purpose")?.lowercase() ?: "display"
                }
            }
            GET_CURRENT_DATETIME -> {
                currentDate = result.data.string("date")?.let {
                    try {
                        LocalDate.parse(it)
                    } catch (_: DateTimeParseException) {
                        null
                    }
                }
            }
            CREATE_CALENDAR_EVENT -> {
                calendarScreenAwaitingUserSave =
                    (result.data["opened"] as? JsonPrimitive)?.content == "true" &&
                    (result.data["requires_user_confirmation"] as? JsonPrimitive)?.content == "true"
            }
            OPEN_COMPOSE -> {
                composeScreenAwaitingUserSend =
                    (result.data["opened"] as? JsonPrimitive)?.content == "true" &&
                    (result.data["requires_user_confirmation"] as? JsonPrimitive)?.content == "true"
            }
        }
    }

    fun rejectionResponse(
        call: ModelToolCall,
        rejection: WorkflowRejection,
    ): ModelToolResponse {
        lastRejection = rejection
        if (rejection.reason == WorkflowRejectReason.CONTACT_DETAIL_REQUIRED &&
            call.modelToolName in CONTACT_DEPENDENT_TERMINAL_TOOLS
        ) {
            pendingTerminalAfterFreshRead = call
        }
        failureDetailKo = rejection.messageKo
        return ModelToolResponse(
            callId = call.callId,
            modelToolName = call.modelToolName,
            payload = buildJsonObject {
                put("status", "rejected")
                put("reason", rejection.reason.name)
                put("message", rejection.messageKo)
                putJsonArray("allowed_next_tools") {
                    rejection.allowedNextTools.sorted().forEach { add(JsonPrimitive(it)) }
                }
                rejection.repairArguments?.let { put("repair_arguments", it) }
            },
        )
    }

    /**
     * A model-generation failure after a terminal surface opened must not turn a proven tool result
     * into a failed user-visible operation, nor trigger the side effect again.  This is deliberately
     * available only for the two typed, confirmation-required surface results.
     */
    fun terminalSurfaceFallback(): String? = when {
        terminalExecutionFailure || hasUnrecoveredRejection -> null
        calendarScreenAwaitingUserSave ->
            "캘린더 일정 작성 화면을 열었습니다. 내용을 확인한 뒤 저장해 주세요."
        composeScreenAwaitingUserSend ->
            "메일 작성 화면을 열었습니다. 내용을 확인한 뒤 전송해 주세요."
        else -> null
    }

    /**
     * The only deterministic terminal admission: all values originate in this turn's successful
     * calendar contact read and clock result.  Returning null preserves the normal clarification
     * path whenever any provenance, freshness, date/time or attendee condition is absent.
     */
    fun deterministicCalendarTerminalCall(): ModelToolCall? {
        if (pendingTerminalTool() != CREATE_CALENDAR_EVENT) return null
        val arguments = defaultCalendarArguments() ?: return null
        return ModelToolCall(
            callId = "policy-calendar-terminal",
            modelToolName = CREATE_CALENDAR_EVENT,
            arguments = arguments,
        )
    }

    /** Admit exactly one verified calendar contact read without asking the model to recreate it. */
    fun deterministicCalendarPrerequisiteCall(): ModelToolCall? {
        if (pendingTerminalTool() != CREATE_CALENDAR_EVENT || !trustedSessionContactTarget) return null
        val cardId = searchResultIds?.singleOrNull()?.takeIf(String::isNotBlank) ?: return null
        if (selectedContactPurpose == "calendar" && selectedContact?.string("card_id") == cardId) return null
        return ModelToolCall(
            callId = "policy-calendar-prerequisite",
            modelToolName = GET_CONTACT,
            arguments = buildJsonObject {
                put("card_id", cardId)
                put("purpose", "calendar")
            },
        )
    }

    /** Resolve a relative calendar request's base time without asking the model to rediscover it. */
    fun deterministicCalendarDatetimePrerequisiteCall(): ModelToolCall? {
        if (pendingTerminalTool() != CREATE_CALENDAR_EVENT) return null
        if (selectedContactPurpose != "calendar") return null
        if (selectedContact?.string("email")?.let(::isValidEmail) != true) return null
        if (!relativeDateIntent || currentDate != null || !calendarTimeProvided) return null
        return ModelToolCall(
            callId = "policy-calendar-datetime-prerequisite",
            modelToolName = GET_CURRENT_DATETIME,
            arguments = buildJsonObject {},
        )
    }

    /** Admit exactly one verified email contact read without asking the model to recreate it. */
    fun deterministicComposePrerequisiteCall(): ModelToolCall? {
        if (!composeIntent || !hasExplicitComposeExecution() || !trustedSessionContactTarget) return null
        val cardId = searchResultIds?.singleOrNull()?.takeIf(String::isNotBlank) ?: return null
        if (selectedContactPurpose == "email" && selectedContact?.string("card_id") == cardId &&
            selectedContact?.string("email")?.let(::isValidEmail) == true
        ) return null
        return ModelToolCall(
            callId = "policy-compose-prerequisite",
            modelToolName = GET_CONTACT,
            arguments = buildJsonObject {
                put("card_id", cardId)
                put("purpose", "email")
            },
        )
    }

    /**
     * Final text guard. It never invents or executes a tool call: it only
     * prevents an incomplete workflow or a false send-completion claim from
     * reaching the user.
     */
    fun validateFinal(draftText: String): WorkflowFinalValidationResult {
        lastRejection?.let {
            return WorkflowFinalValidationResult.Replace(it.messageKo, it.reason)
        }
        // An execution failure is a typed fact, so no model prose can override it. This is broader
        // and safer than trying to enumerate every Korean way to claim success.
        if (terminalExecutionFailure) {
            return WorkflowFinalValidationResult.Replace(
                failureDetailKo ?: "요청한 작업을 완료하지 못했습니다.",
                WorkflowRejectReason.WORKFLOW_ORDER_VIOLATION,
            )
        }
        // The calendar tool reports that it opened the insert surface, not that the event was
        // persisted.  Once that typed state is present, model prose cannot turn it into a save.
        if (calendarScreenAwaitingUserSave) {
            return WorkflowFinalValidationResult.Replace(
                "캘린더 일정 작성 화면을 열었습니다. 내용을 확인한 뒤 저장해 주세요.",
                WorkflowRejectReason.WORKFLOW_ORDER_VIOLATION,
            )
        }
        // A screen-opening tool ran, and the draft claims the work itself is done.
        SCREEN_ONLY_COMPLETION_CLAIMS.forEach { (tool, claims) ->
            if (tool in successfulTools && claims.any(draftText::contains)) {
                return WorkflowFinalValidationResult.Replace(
                    SCREEN_ONLY_CORRECTION.getValue(tool),
                    WorkflowRejectReason.WORKFLOW_ORDER_VIOLATION,
                )
            }
        }
        // Nothing ran, or the thing that would have done the work failed, and the draft still says
        // it happened. This is the claim that makes a broken turn read like a successful one.
        val didSomething = successfulTools.any { it in SIDE_EFFECTING_TOOLS }
        if ((!didSomething || terminalExecutionFailure) && COMPLETION_CLAIMS.any(draftText::contains)) {
            return WorkflowFinalValidationResult.Replace(
                failureDetailKo ?: "요청한 작업을 완료하지 못했습니다.",
                WorkflowRejectReason.WORKFLOW_ORDER_VIOLATION,
            )
        }
        if (previewOnly || unsupportedRequest || explicitRealSend ||
            invalidEmailLike || (calendarIntent && !calendarStartProvided)
        ) {
            return WorkflowFinalValidationResult.Allow
        }

        val unfinishedMessage = when {
            currentTimeIntent && GET_CURRENT_DATETIME !in successfulTools ->
                "현재 시각 조회를 완료하지 못했습니다. 다시 시도해 주세요."
            // Reading one already-verified card by id satisfies "show me the contact" just as well
            // as a fresh search. Demanding search_contacts here reported a completed lookup as a
            // failure whenever the turn resolved a reference instead of a name.
            contactReadIntent && SEARCH_CONTACTS !in successfulTools &&
                GET_CONTACT !in successfulTools ->
                "연락처 검색을 완료하지 못했습니다. 다시 시도해 주세요."
            // Asking which field to change is the correct end of an under-specified edit, not an
            // interrupted workflow, so it must not be overwritten with a retry message.
            updateIntent && updateSlotsComplete && UPDATE_BUSINESS_CARD !in successfulTools &&
                !contactLookupTerminal() ->
                "명함 수정 절차를 완료하지 못했습니다. 다시 시도해 주세요."
            calendarIntent && CREATE_CALENDAR_EVENT !in successfulTools &&
                !contactLookupTerminal() ->
                "일정 작성 절차를 완료하지 못했습니다. 다시 시도해 주세요."
            composeIntent && hasExplicitComposeExecution() &&
                OPEN_COMPOSE !in successfulTools && !contactLookupTerminal() &&
                hasResolvableRecipient() ->
                "작성 화면 열기 절차를 완료하지 못했습니다. 다시 시도해 주세요."
            else -> null
        }
        return if (unfinishedMessage == null) {
            WorkflowFinalValidationResult.Allow
        } else {
            WorkflowFinalValidationResult.Replace(
                unfinishedMessage,
                WorkflowRejectReason.WORKFLOW_ORDER_VIOLATION,
            )
        }
    }

    /**
     * The terminal tool this turn still owes, or null when nothing is outstanding.
     *
     * A turn that resolved a contact and then ended in prose has not done what the user asked; the
     * model simply stopped narrating. Naming the tool that is missing is what lets the kernel give
     * it one bounded chance to finish instead of either accepting the prose or failing the turn.
     *
     * It reports a tool only when acting would be *safe*: the request must be for that action, the
     * action must not already have run, and the parts the validator needs — a resolvable recipient,
     * a stated time — must be present. When they are not, the honest end of the turn is a question,
     * so this returns null and the existing clarification path handles it.
     */
    /**
     * A required slot this request does not supply, or null when nothing is missing.
     *
     * Read from the request and the workflow's own state — never from the model's wording and never
     * from a flag the model boundary happened to attach. The deterministic local gateway marks its
     * missing-slot answers; the LiteRT gateway cannot, because an actual model just writes a sentence.
     * Deciding here means both boundaries produce the same typed outcome for the same request, which
     * is the difference between "the app asked for the missing time" and "the app said something
     * friendly and did nothing".
     *
     * Returns the slot, so the turn can ask about the thing that is actually absent.
     */
    fun missingRequiredSlot(): MissingSlot? = when {
        // Anything that already acted, failed, or was refused is not a missing-slot turn.
        terminalExecutionFailure || previewOnly || unsupportedRequest || explicitRealSend ->
            null
        successfulTools.any { it in SIDE_EFFECTING_TOOLS } -> null

        // A schedule with no date or time in it, and no way to derive one.
        //
        // Not a schedule request when the turn is a compose request: the calendar markers are matched
        // against the whole utterance, and a mail whose *subject* is "회의 안내" with a body about
        // 일정 공유 contains them without asking for a meeting. Reading that as a schedule missing its
        // time turned three ordinary compose turns into questions.
        // A date is not a time. "내일 점검 일정 만들어줘" and "다음 주에 회의 잡아줘" name a day and
        // nothing else, and create_calendar_event needs a start *time* — the earlier rule exempted
        // them because a relative-date marker was present, so they passed as complete requests.
        calendarIntent && !composeIntent && !calendarStartProvided &&
            CREATE_CALENDAR_EVENT !in successfulTools -> MissingSlot.CALENDAR_START_TIME

        // A message with nobody to send it to. A direct address or number counts as a recipient, so
        // "test@example.com으로 보내줘" is not treated as missing anything.
        composeIntent && hasExplicitComposeExecution() && !hasResolvableRecipient() ->
            MissingSlot.COMPOSE_RECIPIENT

        // An edit that never says what to change.
        //
        // Deliberately not here: an ambiguous name, a contact with no address on file, and a lookup
        // that found nobody. Those are not missing slots — the request said who — and each already
        // has a more specific answer and its own typed outcome. Folding them in reclassified
        // ambiguity as a missing slot and replaced "이 명함에는 이메일 주소가 없습니다" with a
        // vaguer question.
        updateIntent && !updateSlotsComplete && UPDATE_BUSINESS_CARD !in successfulTools ->
            MissingSlot.UPDATE_FIELD

        // The field and the value are both stated and there is still nobody to edit: "메모를
        // 우선연락으로 수정해줘" with no name in the sentence and no verified contact in the session.
        // MissingSlot.UPDATE_TARGET existed for this and was never returned, so the turn fell through
        // to whatever the model happened to say.
        //
        // Narrow on purpose: it requires the slots to be complete, so it cannot collide with
        // UPDATE_FIELD, and it requires no contact target at all — a named person, or a person the
        // session has verified, satisfies contactTarget() and is handled by the ordinary path.
        updateIntent && updateSlotsComplete && !contactTarget() &&
            UPDATE_BUSINESS_CARD !in successfulTools -> MissingSlot.UPDATE_TARGET

        else -> null
    }

    /**
     * True when this turn asked for an action and could not pin down a usable target.
     *
     * Three shapes, all of which end with the agent asking the user something:
     *
     *  - the lookup found nobody;
     *  - it found more than one person with that name;
     *  - it found exactly one, and their card does not carry the value the action needs (a mail to
     *    somebody with no e-mail address on file).
     *
     * Each of those already produced a *good sentence* — "대상 명함을 하나로 특정해 주세요",
     * "선택한 명함에 이메일 주소 정보가 없습니다" — and each was then recorded as CONTACT_SELECTED or
     * CONTACT_DETAIL_SHOWN, as though the turn had shown the user something they asked for. It had
     * not: it had asked a question. That typing is what let a turn which ran nothing be counted as a
     * success, and an evaluator cannot tell the two apart afterwards because the wording is fine.
     *
     * Deliberately separate from [missingRequiredSlot]: that one both types the turn *and* supplies
     * the question, and its question is the generic one. Here the boundary's own sentence is more
     * specific and is kept; only the typing changes. Read from workflow state rather than from the
     * boundary's wording, so an actual model that writes its own prose is typed identically.
     */
    fun unresolvedActionTarget(): Boolean =
        !terminalExecutionFailure &&
            !previewOnly && !unsupportedRequest && !explicitRealSend &&
            successfulTools.none { it in SIDE_EFFECTING_TOOLS } &&
            (composeIntent || calendarIntent || updateIntent) &&
            (contactLookupTerminal() || unresolvedContactObligation)

    fun pendingTerminalTool(): String? = when {
        terminalExecutionFailure || previewOnly || unsupportedRequest || explicitRealSend ||
            invalidEmailLike || contactLookupTerminal() || unresolvedContactDownstream() -> null

        pendingTerminalAfterFreshRead != null && GET_CONTACT in successfulTools ->
            pendingTerminalAfterFreshRead?.modelToolName

        calendarIntent && calendarStartProvided && CREATE_CALENDAR_EVENT !in successfulTools ->
            CREATE_CALENDAR_EVENT

        updateIntent && updateSlotsComplete && UPDATE_BUSINESS_CARD !in successfulTools ->
            UPDATE_BUSINESS_CARD

        composeIntent && hasExplicitComposeExecution() && hasResolvableRecipient() &&
            OPEN_COMPOSE !in successfulTools -> OPEN_COMPOSE

        else -> null
    }

    /** A contact-bound compose needs a verified address before Gemma can draft safely. */
    fun initialPrerequisiteTool(): String? = when {
        composeIntent && hasExplicitComposeExecution() && selectedContact == null &&
            searchResultIds?.singleOrNull() != null -> GET_CONTACT
        calendarIntent && calendarStartProvided && selectedContact == null &&
            searchResultIds?.singleOrNull() != null -> GET_CONTACT
        else -> null
    }

    fun initialPrerequisitePrompt(pendingTool: String): ModelWorkflowNote {
        val cardId = searchResultIds!!.single()
        val purpose = if (calendarIntent) "calendar" else "email"
        return ModelWorkflowNote(
            text = if (calendarIntent) {
                calendarStateBlock(pendingTool, cardId, purpose)
            } else {
                "수신자 주소를 검증하려면 get_contact 도구를 먼저 호출해야 합니다. " +
                    "arguments는 {\"card_id\":\"$cardId\",\"purpose\":\"$purpose\"}를 사용하세요."
            },
            pendingTool = pendingTool,
            afterCallId = "router-prerequisite",
            afterToolName = "router",
        )
    }

    /**
     * A tool response that tells the model the workflow is unfinished and which tool finishes it.
     *
     * Sent through the ordinary tool-result channel rather than as a new user turn, so the native
     * conversation stays a single coherent exchange and the repair cannot be mistaken for something
     * the user said.
     */
    fun continuationPrompt(
        pendingTool: String,
        lastCallId: String,
        lastToolName: String,
    ): ModelWorkflowNote = ModelWorkflowNote(
        text = if (calendarIntent) {
            calendarStateBlock(
                pendingTool = pendingTool,
                cardId = searchResultIds?.singleOrNull()
                    ?: lastRejection?.repairArguments?.string("card_id"),
                purpose = "calendar",
            )
        } else buildString {
            append("요청을 완료하려면 ").append(pendingTool)
                .append(" 도구를 호출해야 합니다. 설명 대신 도구를 호출해 주세요.")
            // A rejected contact-bound terminal call has already been chosen by the model and
            // validated except for the fresh read. Once that read succeeds, preserve its exact
            // arguments for the terminal reminder. Reconstructing them invites the model to add
            // unrelated optional fields (for example clearing the very field it is updating).
            (lastRejection?.repairArguments ?: pendingTerminalArguments())?.let { arguments ->
                append(" arguments는 ").append(arguments).append("를 그대로 사용하세요.")
            }
        },
        pendingTool = pendingTool,
        afterCallId = lastCallId,
        afterToolName = lastToolName,
    )

    /**
     * Calendar repair state is intentionally a compact, typed block.  It exposes only values that
     * this turn has either verified or can safely identify as missing; model prose is not allowed
     * to reinterpret an opaque id or invent an attendee/time value.
     */
    private fun calendarStateBlock(
        pendingTool: String,
        cardId: String?,
        purpose: String,
    ): String = buildString {
        val id = cardId?.trim()?.takeIf(String::isNotEmpty)
        val fresh = selectedContactPurpose == "calendar" &&
            selectedContact?.string("card_id") == id
        val attendeeVerified = fresh && selectedContact?.string("email")?.let(::isValidEmail) == true
        val dateResolved = expectedDate() != null && (!relativeDateIntent || currentDate != null)
        val timeResolved = expectedTime() != null
        val targetStatus = if (id != null) "VERIFIED" else "MISSING"
        append("calendar_workflow_state:\n")
        append("  target_card_id: ").append(id ?: "MISSING").append('\n')
        append("  target_status: ").append(targetStatus).append('\n')
        append("  pending_terminal_tool: ").append(pendingTerminalTool() ?: pendingTool).append('\n')
        append("  required_next_action:\n")
        append("    tool: ").append(pendingTool).append('\n')
        if (pendingTool == GET_CONTACT) {
            append("    card_id: ").append(id ?: "MISSING").append('\n')
            append("    purpose: ").append(purpose).append('\n')
        }
        append("  prerequisites:\n")
        append("    fresh_contact: ").append(if (fresh) "VERIFIED" else "MISSING").append('\n')
        append("    attendee_email: ").append(if (attendeeVerified) "VERIFIED" else "MISSING").append('\n')
        append("    current_datetime: ").append(if (currentDate != null) "VERIFIED" else "MISSING").append('\n')
        append("    start_time: ").append(if (dateResolved && timeResolved) "RESOLVED" else "UNRESOLVED").append('\n')
        append("    title: ").append(if (fresh && selectedContact?.string("name").orEmpty().isNotBlank()) "READY_NEUTRAL" else "UNRESOLVED")
    }

    /** A typed, safe read step that can recover the last rejected terminal call. */
    fun pendingRejectionRepairTool(): String? = lastRejection
        ?.takeIf { it.repairArguments != null }
        ?.allowedNextTools
        ?.singleOrNull()

    private fun pendingTerminalArguments(): JsonObject? = pendingTerminalAfterFreshRead
        ?.let(::normalizeVerifiedComposeRecipient)
        ?.let(::normalizeVerifiedCalendarAttendee)
        ?.arguments ?: defaultComposeDraftArguments() ?: defaultCalendarArguments()

    /** Neutral draft: it introduces no facts, dates, prices, or commitments the user did not give. */
    private fun defaultComposeDraftArguments(): JsonObject? {
        if (!composeIntent || selectedContactPurpose != "email") return null
        val email = selectedContact?.string("email")?.takeIf(::isValidEmail) ?: return null
        return buildJsonObject {
            put("channel", "email")
            put("to", email)
            put("subject", "업무 관련 연락드립니다")
            put("body", "안녕하세요.\n\n업무 관련하여 연락드립니다. 확인 부탁드립니다.\n\n감사합니다.")
        }
    }

    /** Calendar arguments are derived only from the fresh calendar read and tool-verified time. */
    private fun defaultCalendarArguments(): JsonObject? {
        if (!calendarIntent || selectedContactPurpose != "calendar") return null
        val contact = selectedContact ?: return null
        val email = contact.string("email")?.takeIf(::isValidEmail) ?: return null
        val date = expectedDate() ?: return null
        val time = expectedTime() ?: return null
        val name = contact.string("name")?.takeIf(String::isNotBlank) ?: return null
        return buildJsonObject {
            put("title", "${name}님과 일정")
            put("start_time", LocalDateTime.of(date, time).toString())
            putJsonArray("attendee_emails") { add(JsonPrimitive(email)) }
        }
    }

    /**
     * Whether a phrase matched by [regex] designates a person this request is aimed at.
     *
     * The run glued to the particle decides, exactly as it always did — except when that run is a
     * job title, a team or a company. Those are not identifiers: "품질팀장에게 메일 작성해줘" names
     * whoever currently holds a role, and accepting it made the agent open a mail to a person the
     * user had never mentioned. When the last run *is* a role, an earlier run in the same phrase can
     * still be the person: "박민수 영업팀장에게" names 박민수 and then describes them.
     *
     * Everything else is unchanged on purpose, including the agent's own vocabulary: "그 사람에게"
     * has always satisfied this and still does, because the anaphora path depends on it.
     */
    private fun namesAContactTarget(regex: Regex): Boolean =
        regex.findAll(userText).any { match ->
            val tokens = match.groupValues[1].trim().split(Regex("\\s+")).filter { it.length >= 2 }
            val last = tokens.lastOrNull() ?: return@any false
            if (!PersonNameMask.namesARoleOrOrganisation(last)) return@any true
            tokens.dropLast(1).any {
                !PersonNameMask.namesARoleOrOrganisation(it) && !PersonNameMask.isAgentVocabulary(it)
            }
        }

    private fun contactLookupTerminal(): Boolean =
        contactTarget() && (
            searchResultIds?.isEmpty() == true ||
                (searchResultIds?.size ?: 0) > 1 ||
                selectedContact?.let { contact ->
                    when {
                        userText.contains("메일") || userText.contains("이메일") ->
                            contact.string("email").isNullOrBlank()
                        userText.contains("문자") ->
                            contact.string("mobile").isNullOrBlank() &&
                                contact.string("phone").isNullOrBlank()
                        else -> false
                    }
                } == true
            )

    private fun hasResolvableRecipient(): Boolean =
        directEmails.isNotEmpty() || directPhones.isNotEmpty() || contactTarget()

    private fun validateSearch(): WorkflowValidationResult {
        if (!(contactTarget() && (composeIntent || calendarIntent || updateIntent)) &&
            !contactReadIntent && !contactSearchRequestedByRouter
        ) {
            return reject(
                WorkflowRejectReason.TOOL_NOT_REQUESTED,
                "연락처 검색이 필요한 요청이 아닙니다.",
            )
        }
        return WorkflowValidationResult.Allow
    }

    private fun validateGetContact(call: ModelToolCall): WorkflowValidationResult {
        val requestedId = call.arguments.string("card_id").orEmpty()
        if (requestedId.startsWith("card-") && userText.contains(requestedId)) {
            return WorkflowValidationResult.Allow
        }
        val ids = searchResultIds ?: return reject(
            WorkflowRejectReason.CONTACT_LOOKUP_REQUIRED,
            "먼저 연락처를 검색해야 합니다.",
            setOf(SEARCH_CONTACTS),
        )
        if (ids.isEmpty()) {
            return reject(
                WorkflowRejectReason.CONTACT_NOT_FOUND,
                "검색 결과가 없어 다음 작업을 실행할 수 없습니다.",
            )
        }
        if (ids.size > 1) {
            return reject(
                WorkflowRejectReason.CONTACT_SELECTION_REQUIRED,
                "같은 이름의 연락처가 여러 명입니다. 사용자가 대상을 선택해야 합니다.",
            )
        }
        if (requestedId != ids.single()) {
            return reject(
                WorkflowRejectReason.CONTACT_VALUE_NOT_VERIFIED,
                "검색 결과에 포함된 명함 ID만 조회할 수 있습니다.",
                setOf(GET_CONTACT),
            )
        }
        val requestedPurpose = call.arguments.string("purpose")?.lowercase() ?: "display"
        val requiredPurpose = requiredFreshReadPurpose()
        if (requestedPurpose != requiredPurpose) {
            return freshReadRejection(requiredPurpose)
        }
        return WorkflowValidationResult.Allow
    }

    private fun validateCompose(call: ModelToolCall): WorkflowValidationResult {
        if (!composeIntent || !hasExplicitComposeExecution()) {
            return reject(
                WorkflowRejectReason.TOOL_NOT_REQUESTED,
                "작성 화면 실행 의도가 명확하지 않아 도구를 실행하지 않습니다.",
            )
        }
        val channel = call.arguments.string("channel")
        val to = call.arguments.string("to").orEmpty()
        val body = call.arguments.string("body").orEmpty()
        if (body.isBlank()) {
            return invalid("메시지 본문은 비어 있을 수 없습니다.", setOf(OPEN_COMPOSE))
        }
        if (channel == "email") {
            if (!isValidEmail(to)) {
                return reject(WorkflowRejectReason.INVALID_EMAIL, "올바른 이메일 주소가 필요합니다.")
            }
            if (call.arguments.string("subject").isNullOrBlank()) {
                return invalid("이메일 제목은 비어 있을 수 없습니다.", setOf(OPEN_COMPOSE))
            }
        } else if (channel == "sms") {
            if (!isValidPhone(to)) {
                return reject(WorkflowRejectReason.INVALID_PHONE, "올바른 전화번호가 필요합니다.")
            }
            if (!call.arguments.string("subject").isNullOrBlank()) {
                return invalid("문자에는 subject를 넣을 수 없습니다.", setOf(OPEN_COMPOSE))
            }
        }

        if (contactTarget()) {
            val contact = selectedContact ?: return contactStageRejection(requiredFreshReadPurpose())
            val verified = when (channel) {
                "email" -> setOfNotNull(contact.string("email")?.lowercase())
                "sms" -> setOfNotNull(
                    contact.string("mobile")?.let(::normalizePhone),
                    contact.string("phone")?.let(::normalizePhone),
                )
                else -> emptySet()
            }
            val normalizedTo = if (channel == "email") to.lowercase() else normalizePhone(to)
            if (normalizedTo !in verified) {
                return reject(
                    WorkflowRejectReason.CONTACT_VALUE_NOT_VERIFIED,
                    "조회한 연락처의 주소 또는 번호만 사용할 수 있습니다.",
                    setOf(OPEN_COMPOSE),
                )
            }
        } else {
            val directMatch = when (channel) {
                "email" -> to.lowercase() in directEmails
                "sms" -> normalizePhone(to) in directPhones
                else -> false
            }
            if (!directMatch) {
                return reject(
                    WorkflowRejectReason.CONTACT_VALUE_NOT_VERIFIED,
                    "사용자가 제공했거나 연락처 조회로 확인된 수신자만 사용할 수 있습니다.",
                )
            }
        }
        return WorkflowValidationResult.Allow
    }

    private fun validateCalendar(call: ModelToolCall): WorkflowValidationResult {
        if (!calendarIntent) {
            return reject(WorkflowRejectReason.TOOL_NOT_REQUESTED, "일정 생성 요청이 아닙니다.")
        }
        val rawStart = call.arguments.string("start_time").orEmpty()
        // The kernel has already run [normalizeArguments], so a zero-seconds value arrives here in
        // canonical form. Anything still not canonical is refused with the specific reason rather
        // than a generic "invalid datetime", because "you wrote seconds" and "that date does not
        // exist" are different problems for whoever has to fix them.
        val start = when (val canonical = LocalDateTimeCanonicalizer.canonicalize(rawStart)) {
            is LocalDateTimeCanonicalizer.Result.Canonical -> LocalDateTime.parse(canonical.value)
            is LocalDateTimeCanonicalizer.Result.Rejected -> return reject(
                WorkflowRejectReason.INVALID_DATETIME,
                canonical.reasonKo,
                setOf(CREATE_CALENDAR_EVENT),
            )
        }
        // end_time was never validated here, so a value the canonicalizer had already refused was
        // left in the call verbatim and travelled straight to the plugin. Both fields go through the
        // same contract, and a refusal is a validation error rather than a value that survives.
        call.arguments.string("end_time")?.let { rawEnd ->
            val end = when (val canonical = LocalDateTimeCanonicalizer.canonicalize(rawEnd)) {
                is LocalDateTimeCanonicalizer.Result.Canonical -> LocalDateTime.parse(canonical.value)
                is LocalDateTimeCanonicalizer.Result.Rejected -> return reject(
                    WorkflowRejectReason.INVALID_DATETIME,
                    "end_time: ${canonical.reasonKo}",
                    setOf(CREATE_CALENDAR_EVENT),
                )
            }
            if (!end.isAfter(start)) {
                return reject(
                    WorkflowRejectReason.INVALID_DATETIME,
                    "종료 시각은 시작 시각보다 늦어야 합니다.",
                    setOf(CREATE_CALENDAR_EVENT),
                )
            }
        }
        if (relativeDateIntent && currentDate == null) {
            return reject(
                WorkflowRejectReason.CURRENT_DATETIME_REQUIRED,
                "상대 날짜를 계산하려면 먼저 현재 날짜와 시각을 조회해야 합니다.",
                setOf(GET_CURRENT_DATETIME),
            )
        }
        expectedDate()?.let { expected ->
            if (start.toLocalDate() != expected) {
                return reject(
                    WorkflowRejectReason.INVALID_DATETIME,
                    "상대 또는 절대 날짜 계산 결과가 사용자 요청과 일치하지 않습니다.",
                    setOf(CREATE_CALENDAR_EVENT),
                )
            }
        }
        expectedTime()?.let { expected ->
            if (start.toLocalTime() != expected) {
                return reject(
                    WorkflowRejectReason.INVALID_DATETIME,
                    "일정 시작 시각이 사용자 요청과 일치하지 않습니다.",
                    setOf(CREATE_CALENDAR_EVENT),
                )
            }
        }
        if (contactTarget()) {
            val contact = selectedContact ?: return contactStageRejection(requiredFreshReadPurpose())
            val verifiedEmail = contact.string("email")?.takeIf(::isValidEmail)
                ?: return reject(
                    WorkflowRejectReason.CONTACT_VALUE_NOT_VERIFIED,
                    "조회한 연락처에 유효한 이메일 주소가 없습니다.",
                )
            val attendees = (call.arguments["attendee_emails"] as? JsonArray)
                .orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
            if (verifiedEmail !in attendees) {
                return reject(
                    WorkflowRejectReason.CONTACT_VALUE_NOT_VERIFIED,
                    "연락처 일정에는 조회로 확인한 참석자 이메일이 필요합니다.",
                    setOf(CREATE_CALENDAR_EVENT),
                )
            }
        }
        return WorkflowValidationResult.Allow
    }

    private fun validateUpdate(call: ModelToolCall): WorkflowValidationResult {
        if (!updateIntent) {
            return reject(WorkflowRejectReason.TOOL_NOT_REQUESTED, "명함 수정 요청이 아닙니다.")
        }
        if (contactTarget()) {
            val contact = selectedContact ?: return contactStageRejection(requiredFreshReadPurpose())
            if (call.arguments.string("card_id") != contact.string("card_id")) {
                return reject(
                    WorkflowRejectReason.CONTACT_VALUE_NOT_VERIFIED,
                    "조회로 확인한 명함만 수정할 수 있습니다.",
                    setOf(UPDATE_BUSINESS_CARD),
                )
            }
        }
        val updates = call.arguments["updates"] as? JsonObject
        val clears = call.arguments["clear_fields"] as? JsonArray
        if (updates.isNullOrEmpty() && clears.isNullOrEmpty()) {
            return invalid("수정할 필드와 값이 필요합니다.")
        }
        val overlap = updates.orEmpty().keys intersect clears.orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
        if (overlap.isNotEmpty()) {
            return invalid("같은 필드를 수정하면서 동시에 비울 수 없습니다.", setOf(UPDATE_BUSINESS_CARD))
        }
        return WorkflowValidationResult.Allow
    }

    private fun validateCurrentDatetime(): WorkflowValidationResult {
        if (!currentTimeIntent && !relativeDateIntent) {
            return reject(
                WorkflowRejectReason.UNNECESSARY_TOOL_CALL,
                "현재 시각 조회가 필요하지 않은 요청입니다.",
                when {
                    calendarIntent -> setOf(CREATE_CALENDAR_EVENT)
                    composeIntent -> setOf(OPEN_COMPOSE)
                    else -> emptySet()
                },
            )
        }
        return WorkflowValidationResult.Allow
    }

    private fun contactFreshReadGate(call: ModelToolCall): WorkflowValidationResult? {
        if (!contactTarget()) return null
        val purpose = when (call.modelToolName) {
            OPEN_COMPOSE -> when (call.arguments.string("channel")?.lowercase()) {
                "sms" -> "sms"
                else -> "email"
            }
            CREATE_CALENDAR_EVENT -> "calendar"
            UPDATE_BUSINESS_CARD -> "display"
            else -> return null
        }
        val expectedId = searchResultIds?.singleOrNull()
        val freshId = selectedContact?.string("card_id")
        if (expectedId == null || freshId != expectedId || selectedContactPurpose != purpose) {
            return contactStageRejection(purpose)
        }
        return null
    }

    private fun contactStageRejection(purpose: String = requiredFreshReadPurpose()): WorkflowValidationResult {
        val ids = searchResultIds
        return when {
            ids == null -> reject(
                WorkflowRejectReason.CONTACT_LOOKUP_REQUIRED,
                "이름으로 지정된 연락처입니다. 먼저 연락처를 검색해야 합니다.",
                setOf(SEARCH_CONTACTS),
            )
            ids.isEmpty() -> reject(
                WorkflowRejectReason.CONTACT_NOT_FOUND,
                "검색 결과가 없어 다음 작업을 실행할 수 없습니다.",
            )
            ids.size > 1 -> reject(
                WorkflowRejectReason.CONTACT_SELECTION_REQUIRED,
                "같은 이름의 연락처가 여러 명입니다. 사용자가 대상을 선택해야 합니다.",
            )
            else -> freshReadRejection(purpose)
        }
    }

    private fun freshReadRejection(purpose: String): WorkflowValidationResult {
        val cardId = searchResultIds?.singleOrNull()
        return reject(
            WorkflowRejectReason.CONTACT_DETAIL_REQUIRED,
            "현재 요청의 연락처 상세정보를 다시 확인해야 합니다.",
            setOf(GET_CONTACT),
            cardId?.let { id -> buildJsonObject {
                put("card_id", id)
                put("purpose", purpose)
            } },
        )
    }

    private fun validateSchema(arguments: JsonObject, schema: JsonObject): WorkflowRejection? {
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = (schema["required"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
        val unknown = arguments.keys - properties.keys
        if (unknown.isNotEmpty() && (schema["additionalProperties"] as? JsonPrimitive)?.content == "false") {
            return WorkflowRejection(
                WorkflowRejectReason.INVALID_ARGUMENTS,
                "허용되지 않은 argument가 있습니다: ${unknown.sorted().joinToString()}",
            )
        }
        for (name in required) {
            val value = arguments[name]
                ?: return WorkflowRejection(
                    WorkflowRejectReason.INVALID_ARGUMENTS,
                    "필수 argument가 없습니다: $name",
                )
            if (value is JsonPrimitive && value.isString && value.content.isBlank()) {
                return WorkflowRejection(
                    WorkflowRejectReason.INVALID_ARGUMENTS,
                    "필수 argument는 비어 있을 수 없습니다: $name",
                )
            }
        }
        for ((name, value) in arguments) {
            val property = properties[name] as? JsonObject ?: continue
            val type = property.string("type")
            val typeValid = when (type) {
                "string" -> value is JsonPrimitive && value.isString
                "integer" -> value is JsonPrimitive && value.intOrNull != null
                "array" -> value is JsonArray
                "object" -> value is JsonObject
                "boolean" -> value is JsonPrimitive && !value.isString &&
                    value.content in setOf("true", "false")
                else -> true
            }
            if (!typeValid) {
                return WorkflowRejection(
                    WorkflowRejectReason.INVALID_ARGUMENTS,
                    "argument 타입이 올바르지 않습니다: $name",
                )
            }
            val allowed = (property["enum"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.content }
            if (allowed.isNotEmpty() && (value as? JsonPrimitive)?.content !in allowed) {
                return WorkflowRejection(
                    WorkflowRejectReason.INVALID_ARGUMENTS,
                    "argument 값이 허용 범위를 벗어났습니다: $name",
                )
            }
            if (value is JsonPrimitive && value.isString &&
                name in NON_EMPTY_FIELDS && value.content.isBlank()
            ) {
                return WorkflowRejection(
                    WorkflowRejectReason.INVALID_ARGUMENTS,
                    "argument는 비어 있을 수 없습니다: $name",
                )
            }
            if (value is JsonArray &&
                ((property["items"] as? JsonObject)?.string("format") == "email") &&
                value.any { !isValidEmail((it as? JsonPrimitive)?.content.orEmpty()) }
            ) {
                return WorkflowRejection(
                    WorkflowRejectReason.INVALID_EMAIL,
                    "유효하지 않은 이메일 주소가 포함되어 있습니다.",
                )
            }
        }
        return null
    }

    private fun expectedDate(): LocalDate? {
        val base = currentDate
        if (relativeDateIntent && base != null) {
            if (userText.contains("내일")) return base.plusDays(1)
            if (userText.contains("오늘")) return base
            NEXT_WEEKDAY_REGEX.find(userText)?.groupValues?.getOrNull(1)?.let { label ->
                val target = KOREAN_WEEKDAYS[label] ?: return@let
                val nextMonday = base.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                return nextMonday.plusDays((target.value - DayOfWeek.MONDAY.value).toLong())
            }
        }
        ABSOLUTE_DATE_REGEX.find(userText)?.destructured?.let { (year, month, day) ->
            return try {
                LocalDate.of(year.toInt(), month.toInt(), day.toInt())
            } catch (_: RuntimeException) {
                null
            }
        }
        return null
    }

    private fun expectedTime(): LocalTime? {
        val match = CALENDAR_TIME_REGEX.find(userText) ?: return null
        val marker = match.groupValues[1]
        var hour = match.groupValues[2].toInt()
        val minute = match.groupValues[3].takeIf(String::isNotBlank)?.toInt() ?: 0
        if (marker == "오후" && hour < 12) hour += 12
        if (marker == "오전" && hour == 12) hour = 0
        return try {
            LocalTime.of(hour, minute)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun hasExplicitComposeExecution(): Boolean =
        EXPLICIT_COMPOSE_MARKERS.any(userText::contains) ||
            (EXTERNAL_UI_MARKERS.any(userText::contains) && composeIntent)

    private fun contactTarget(): Boolean = contactNameTarget || trustedSessionContactTarget

    private fun unresolvedContactDownstream(): Boolean = unresolvedContactObligation &&
        (calendarIntent || updateIntent ||
            (composeIntent && directEmails.isEmpty() && directPhones.isEmpty()))

    private val CONTACT_DEPENDENT_TOOLS = setOf(
        GET_CONTACT, GET_CURRENT_DATETIME, OPEN_COMPOSE, CREATE_CALENDAR_EVENT,
        UPDATE_BUSINESS_CARD,
    )

    private fun invalid(
        message: String,
        allowed: Set<String> = emptySet(),
    ) = reject(WorkflowRejectReason.INVALID_ARGUMENTS, message, allowed)

    private fun reject(
        reason: WorkflowRejectReason,
        message: String,
        allowed: Set<String> = emptySet(),
        repairArguments: JsonObject? = null,
    ) = WorkflowValidationResult.Reject(
        WorkflowRejection(reason, message, allowed, repairArguments),
    )

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()

    private fun List<JsonElement>.isNullOrEmpty() = isEmpty()

    companion object {
        const val SEARCH_CONTACTS = "search_contacts"
        const val GET_CONTACT = "get_contact"
        const val UPDATE_BUSINESS_CARD = "update_business_card"
        const val CREATE_CALENDAR_EVENT = "create_calendar_event"
        const val OPEN_COMPOSE = "open_compose"
        const val GET_CURRENT_DATETIME = "get_current_datetime"

        private val NON_EMPTY_FIELDS = setOf(
            "query", "card_id", "channel", "to", "body", "title", "start_time",
        )
        private val EMAIL_REGEX =
            Regex("""(?i)\b[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+(?![A-Z0-9.-])""")
        private val PHONE_REGEX = Regex("""(?<!\d)(?:\+82[- ]?|0)\d{1,2}[- ]?\d{3,4}[- ]?\d{4}(?!\d)""")
        private val INVALID_EMAIL_LIKE_REGEX =
            Regex("""(?i)(?<![a-z0-9._%+-])[a-z0-9._%+-]+(?:-at-|(?:\s+at\s+))[a-z0-9.-]+(?![a-z0-9.-])""")
        private val MALFORMED_SPACED_EMAIL_REGEX =
            Regex("""(?i)(?<![a-z0-9._%+-])[a-z0-9._%+-]+\s+[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}(?![a-z0-9.-])""")
        /**
         * No trailing `\b?`: an optional word boundary matches the empty string unconditionally, so
         * it changed nothing here — but Android's regex engine rejects a quantifier on a zero-width
         * assertion outright, and this pattern is compiled in a companion object, so the whole
         * policy class failed to initialise on the first turn of any real device run.
         */
        /**
         * A person named as the other party of the request.
         *
         * 이랑/랑/하고 are the everyday spoken forms of 와/과 — "표하윤이랑 일정 잡아줘" is the same
         * request as "표하윤과 일정 잡아줘". Recognising only the written forms meant the workflow
         * decided no contact was involved, refused the lookup, and the schedule lost the person it
         * was for. 께 is the honorific of 에게 and was missing for the same reason.
         */
        // The whole phrase in front of the particle is captured, not just the run glued to it.
        // "박민수 영업팀장에게" is one target described two ways, and reading only the last run made
        // the job title the target; see [namesAContactTarget].
        private val NAME_RECIPIENT_REGEX =
            Regex("""((?:[가-힣]{2,12}\s+){0,2}[가-힣]{2,12})(?:에게|한테|께|이랑|랑|하고|와|과)""")
        private val NAME_CARD_REGEX = Regex("""((?:[가-힣]{2,12}\s+){0,2}[가-힣]{2,12})\s*명함""")
        /** Arguments whose value is a device-local datetime. */
        private val DATETIME_ARGUMENTS = listOf("start_time", "end_time")
        private val ABSOLUTE_DATE_REGEX = Regex("""(\d{4})년\s*(\d{1,2})월\s*(\d{1,2})일""")
        private val CALENDAR_TIME_REGEX =
            Regex("""(오전|오후)\s*(\d{1,2})시(?:\s*(\d{1,2})분)?""")
        private val NEXT_WEEKDAY_REGEX = Regex("""다음\s*주\s*(월|화|수|목|금|토|일)요일""")
        private val KOREAN_WEEKDAYS = mapOf(
            "월" to DayOfWeek.MONDAY,
            "화" to DayOfWeek.TUESDAY,
            "수" to DayOfWeek.WEDNESDAY,
            "목" to DayOfWeek.THURSDAY,
            "금" to DayOfWeek.FRIDAY,
            "토" to DayOfWeek.SATURDAY,
            "일" to DayOfWeek.SUNDAY,
        )
        private val PREVIEW_MARKERS = listOf("예시", "방법", "먼저 작성해서 보여", "초안을 먼저 보여")
        private val EXTERNAL_UI_MARKERS = listOf("화면 열어", "작성 화면", "캘린더 열어")
        private val UNSUPPORTED_REQUEST_MARKERS =
            listOf("삭제해", "삭제 해", "완전히 삭제", "전화 걸어", "전화해줘", "전화해 줘")
        private val REAL_SEND_MARKERS = listOf("실제로 전송", "지금 바로 전송", "자동 전송")
        /** One vocabulary, shared with the pre-router. See [ActionVocabulary]. */
        private val CALENDAR_MARKERS = ActionVocabulary.CALENDAR
        private val COMPOSE_MARKERS = ActionVocabulary.COMPOSE
        private val SMS_MARKERS = listOf("문자", "sms")
        private val CARD_OBJECT_MARKERS = ContactReadIntent.CARD_OBJECTS
        /** Editable card fields, named the way a user names them. */
        private val CARD_FIELD_MARKERS = listOf(
            "메모", "직함", "직책", "직급", "회사", "부서", "이메일", "메일 주소", "메일주소",
            "전화번호", "휴대폰", "핸드폰", "주소", "업종", "지역", "홈페이지", "웹사이트", "이름",
        )
        /** "VIP로", "영업팀장으로", "‘VIP’라고" — a concrete new value, not just a field name. */
        private val UPDATE_VALUE_REGEX = Regex(CardUpdateIntent.UPDATE_VALUE_PATTERN)
        private val RELATIVE_DATE_MARKERS = listOf("오늘", "내일", "모레", "다음 주", "이번 주")
        private val EXPLICIT_COMPOSE_MARKERS =
            listOf(
                "작성해줘", "작성해 줘", "작성해", "써줘", "써 줘", "초안 작성",
                "초안 열어", "초안 열어줘", "초안 열어 줘",
                "초안까지 열어", "초안까지 열어줘", "초안까지 열어 줘",
            )
        /**
         * Claims the agent is not entitled to make, keyed by what the tool actually did.
         *
         * The distinction is semantic, not lexical. `open_compose` opens a composer — the user still
         * has to press send — so "전송했습니다" is false whether or not the tool succeeded.
         * `create_calendar_event` opens an insert screen, so "저장했습니다" is false the same way.
         * `update_business_card` genuinely writes, so its completion claim is only false when the
         * write did not happen.
         *
         * Grouping them by tool is what makes this a check on meaning rather than a banned-word list:
         * the same sentence is honest after one tool and false after another.
         */
        /**
         * Tools whose success is the only thing that can justify a completion claim.
         *
         * Visible so the kernel counts a completed terminal action from the same set the validator
         * judges one by. Two lists here would be two definitions of "the agent did something
         * irreversible", and the counter would eventually disagree with the guard.
         */
        val SIDE_EFFECTING_TOOLS = setOf(OPEN_COMPOSE, CREATE_CALENDAR_EVENT, UPDATE_BUSINESS_CARD)
        val CONTACT_DEPENDENT_TERMINAL_TOOLS =
            setOf(OPEN_COMPOSE, CREATE_CALENDAR_EVENT, UPDATE_BUSINESS_CARD)

        private val SCREEN_ONLY_COMPLETION_CLAIMS: Map<String, List<String>> = mapOf(
            OPEN_COMPOSE to listOf(
                "전송했습니다", "전송 완료", "보냈습니다", "발송했습니다", "발송 완료", "전송하였습니다",
            ),
            CREATE_CALENDAR_EVENT to listOf(
                "일정을 생성했습니다", "일정을 저장했습니다", "일정이 생성되었습니다", "일정이 저장되었습니다",
                "일정 등록 완료", "캘린더에 저장했습니다", "일정을 등록했습니다",
            ),
        )

        /** What a screen-opening tool may honestly claim. */
        private val SCREEN_ONLY_CORRECTION: Map<String, String> = mapOf(
            OPEN_COMPOSE to "작성 화면을 열었습니다. 실제 전송 여부는 작성 화면에서 확인해 주세요.",
            CREATE_CALENDAR_EVENT to
                "일정 작성 화면을 열었습니다. 실제 저장 여부는 캘린더 앱에서 확인해 주세요.",
        )

        /** Claims that assert work happened at all. False whenever no tool did that work. */
        private val COMPLETION_CLAIMS = listOf(
            "완료했습니다", "완료되었습니다", "처리했습니다", "수정했습니다", "저장했습니다",
            "생성했습니다", "등록했습니다", "보냈습니다", "전송했습니다", "발송했습니다",
        )

        fun isValidEmail(value: String): Boolean = EMAIL_REGEX.matches(value.trim())

        fun isValidPhone(value: String): Boolean = PHONE_REGEX.matches(value.trim())

        fun normalizePhone(value: String): String = value.filter(Char::isDigit).let {
            if (it.startsWith("82") && !it.startsWith("820")) "0" + it.drop(2) else it
        }
    }
}
