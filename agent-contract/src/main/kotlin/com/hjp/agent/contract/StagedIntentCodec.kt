package com.hjp.agent.contract

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class StageIntent {
    SEARCH_CONTACT,
    VIEW_CONTACT,
    COMPOSE_EMAIL,
    COMPOSE_SMS,
    CREATE_CALENDAR_EVENT,
    UPDATE_CONTACT,
    GET_CURRENT_DATETIME,
    GENERAL,
}

enum class AgentAction {
    EXECUTE,
    PREVIEW,
    ANSWER,
    CLARIFY,
    UNSUPPORTED,
}

data class IntentClassification(
    val intent: StageIntent,
    val action: AgentAction,
)

object StagedIntentCodec {
    fun decodeClassification(arguments: JsonObject): StructuredDecodeResult<IntentClassification> {
        val errors = mutableListOf<String>()
        val intent = arguments.enumValue<StageIntent>("intent", errors)
        val action = arguments.enumValue<AgentAction>("action", errors)
        if (arguments.keys != setOf("intent", "action")) {
            errors += "Stage 1 permits only intent and action"
        }
        if (intent == StageIntent.GENERAL && action == AgentAction.EXECUTE) {
            errors += "GENERAL cannot use EXECUTE"
        }
        if (action in setOf(AgentAction.ANSWER, AgentAction.PREVIEW) &&
            intent != StageIntent.GENERAL
        ) {
            errors += "ANSWER or PREVIEW requires GENERAL"
        }
        return if (errors.isEmpty() && intent != null && action != null) {
            StructuredDecodeResult.Success(IntentClassification(intent, action))
        } else {
            StructuredDecodeResult.Failure(errors.distinct())
        }
    }

    fun decodeSlots(
        classification: IntentClassification,
        arguments: JsonObject,
        originalUserText: String? = null,
    ): StructuredDecodeResult<StructuredIntentPlan> {
        if (classification.action != AgentAction.EXECUTE) {
            return StructuredDecodeResult.Failure(listOf("Stage 2 is allowed only for EXECUTE"))
        }
        val errors = mutableListOf<String>()
        val expected = requiredAndOptionalFields(classification.intent)
        val required = expected.first
        val allowed = required + expected.second
        if (arguments.keys.any { it !in allowed }) {
            errors += "Stage 2 contains fields not allowed for ${classification.intent}"
        }
        required.forEach { field ->
            val emptyCalendarContact = classification.intent == StageIntent.CREATE_CALENDAR_EVENT &&
                field == "contact_name" && arguments.string("attendee_type") == "NONE"
            if (!emptyCalendarContact && arguments.string(field).isNullOrBlank()) {
                errors += "$field is required"
            }
        }
        val plan = when (classification.intent) {
            StageIntent.SEARCH_CONTACT -> plan(
                AgentIntent.SEARCH_CONTACT,
                RecipientType.CONTACT_NAME,
                arguments.string("contact_name"),
            )
            StageIntent.VIEW_CONTACT -> {
                val type = arguments.recipientType(errors, setOf(RecipientType.CONTACT_NAME, RecipientType.CARD_ID))
                plan(AgentIntent.VIEW_CONTACT, type, arguments.string("recipient_value"))
            }
            StageIntent.COMPOSE_EMAIL -> {
                val type = arguments.recipientType(
                    errors,
                    setOf(RecipientType.CONTACT_NAME, RecipientType.EMAIL, RecipientType.CARD_ID),
                )
                val value = arguments.string("recipient_value").orEmpty()
                if (type == RecipientType.EMAIL && !EMAIL_REGEX.matches(value)) {
                    errors += "recipient_value is not a valid email"
                }
                plan(
                    AgentIntent.COMPOSE_EMAIL,
                    type,
                    value,
                    contentGoal = arguments.string("content_goal").orEmpty(),
                    tone = arguments.string("tone"),
                )
            }
            StageIntent.COMPOSE_SMS -> {
                val type = arguments.recipientType(
                    errors,
                    setOf(RecipientType.CONTACT_NAME, RecipientType.PHONE, RecipientType.CARD_ID),
                )
                val value = arguments.string("recipient_value").orEmpty()
                if (type == RecipientType.PHONE && !PHONE_REGEX.matches(value)) {
                    errors += "recipient_value is not a valid phone"
                }
                plan(
                    AgentIntent.COMPOSE_SMS,
                    type,
                    value,
                    contentGoal = arguments.string("content_goal").orEmpty(),
                    tone = arguments.string("tone"),
                )
            }
            StageIntent.CREATE_CALENDAR_EVENT -> {
                val attendeeType = arguments.string("attendee_type")
                val contactName = arguments.string("contact_name").orEmpty()
                if (attendeeType !in setOf("NONE", "CONTACT_NAME")) {
                    errors += "attendee_type is not supported"
                }
                if (attendeeType == "NONE" && contactName.isNotBlank()) {
                    errors += "contact_name must be empty for NONE"
                }
                val explicitAttendee = originalUserText?.let {
                    CALENDAR_ATTENDEE_REGEX.find(it)?.groupValues?.getOrNull(1)
                }
                if (explicitAttendee != null && (
                    attendeeType != "CONTACT_NAME" || contactName != explicitAttendee
                )) {
                    errors += "explicit calendar attendee must be preserved as CONTACT_NAME/$explicitAttendee"
                }
                plan(
                    AgentIntent.CREATE_CALENDAR_EVENT,
                    if (attendeeType == "CONTACT_NAME") {
                        RecipientType.CONTACT_NAME
                    } else {
                        RecipientType.NONE
                    },
                    contactName,
                    dateExpression = arguments.string("date_expression"),
                    timeExpression = arguments.string("time_expression"),
                    calendarTitle = arguments.string("title"),
                )
            }
            StageIntent.UPDATE_CONTACT -> {
                val field = arguments.string("update_field").orEmpty()
                if (field !in UPDATE_FIELDS) errors += "update_field is not supported"
                plan(
                    AgentIntent.UPDATE_CONTACT,
                    RecipientType.CONTACT_NAME,
                    arguments.string("contact_name"),
                    updates = buildJsonObject { put(field, arguments.string("update_value").orEmpty()) },
                )
            }
            StageIntent.GET_CURRENT_DATETIME -> plan(
                AgentIntent.GET_CURRENT_DATETIME,
                RecipientType.NONE,
                "",
            )
            StageIntent.GENERAL -> {
                errors += "GENERAL has no executable Stage 2"
                plan(AgentIntent.ANSWER_ONLY, RecipientType.NONE, "")
            }
        }
        return if (errors.isEmpty()) {
            StructuredDecodeResult.Success(plan)
        } else {
            StructuredDecodeResult.Failure(errors.distinct())
        }
    }

    fun nonExecutingPlan(classification: IntentClassification): StructuredIntentPlan {
        val intent = when (classification.action) {
            AgentAction.CLARIFY -> AgentIntent.CLARIFY
            AgentAction.UNSUPPORTED -> AgentIntent.UNSUPPORTED
            AgentAction.ANSWER, AgentAction.PREVIEW -> AgentIntent.ANSWER_ONLY
            AgentAction.EXECUTE -> error("EXECUTE requires Stage 2")
        }
        val question = if (intent == AgentIntent.CLARIFY) {
            when (classification.intent) {
                StageIntent.COMPOSE_EMAIL, StageIntent.COMPOSE_SMS ->
                    "받는 사람을 올바른 이메일 주소, 전화번호 또는 연락처 이름으로 알려주세요."
                StageIntent.CREATE_CALENDAR_EVENT -> "일정 날짜와 시작 시각을 모두 알려주세요."
                StageIntent.UPDATE_CONTACT -> "수정할 연락처, 항목과 새 값을 알려주세요."
                else -> "요청을 처리하는 데 필요한 정보를 더 알려주세요."
            }
        } else {
            null
        }
        return plan(intent, RecipientType.NONE, "", clarificationQuestion = question)
    }

    private fun plan(
        intent: AgentIntent,
        recipientType: RecipientType,
        recipientValue: String?,
        contentGoal: String = "",
        dateExpression: String? = null,
        timeExpression: String? = null,
        calendarTitle: String? = null,
        updates: JsonObject? = null,
        clarificationQuestion: String? = null,
        tone: String? = null,
    ) = StructuredIntentPlan(
        intent = intent,
        execute = intent !in setOf(AgentIntent.ANSWER_ONLY, AgentIntent.CLARIFY, AgentIntent.UNSUPPORTED),
        recipient = IntentRecipient(recipientType, recipientValue.orEmpty().trim()),
        contentGoal = contentGoal.trim(),
        dateExpression = dateExpression.notBlank(),
        timeExpression = timeExpression.notBlank(),
        calendarTitle = calendarTitle.notBlank(),
        updates = updates,
        clarificationQuestion = clarificationQuestion,
        tone = tone.notBlank(),
    )

    private fun requiredAndOptionalFields(intent: StageIntent): Pair<Set<String>, Set<String>> =
        when (intent) {
            StageIntent.SEARCH_CONTACT -> setOf("contact_name") to emptySet()
            StageIntent.VIEW_CONTACT -> setOf("recipient_type", "recipient_value") to emptySet()
            StageIntent.COMPOSE_EMAIL, StageIntent.COMPOSE_SMS ->
                setOf("recipient_type", "recipient_value", "content_goal") to setOf("tone")
            StageIntent.CREATE_CALENDAR_EVENT ->
                setOf(
                    "title", "date_expression", "time_expression",
                    "attendee_type", "contact_name",
                ) to emptySet()
            StageIntent.UPDATE_CONTACT ->
                setOf("contact_name", "update_field", "update_value") to emptySet()
            StageIntent.GET_CURRENT_DATETIME -> emptySet<String>() to emptySet()
            StageIntent.GENERAL -> emptySet<String>() to emptySet()
        }

    private inline fun <reified T : Enum<T>> JsonObject.enumValue(
        name: String,
        errors: MutableList<String>,
    ): T? = string(name)?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: run {
        errors += "$name must be an allowed enum"
        null
    }

    private fun JsonObject.recipientType(
        errors: MutableList<String>,
        allowed: Set<RecipientType>,
    ): RecipientType {
        val value = string("recipient_type")?.let {
            runCatching { RecipientType.valueOf(it) }.getOrNull()
        }
        if (value !in allowed) {
            errors += "recipient_type must be one of ${allowed.joinToString()}"
            return RecipientType.NONE
        }
        return requireNotNull(value)
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()

    private fun String?.notBlank(): String? = this?.trim()?.takeIf(String::isNotEmpty)

    internal val UPDATE_FIELDS = setOf(
        "name", "name_en", "company", "department", "title", "industry",
        "location", "phone", "mobile", "email", "address", "website", "memo",
    )
    private val EMAIL_REGEX =
        Regex("""(?i)^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+$""")
    private val PHONE_REGEX = Regex("""^(?:\+82[- ]?|0)\d{1,2}[- ]?\d{3,4}[- ]?\d{4}$""")
    private val CALENDAR_ATTENDEE_REGEX =
        Regex("""([가-힣]{2,8})(?:과|와)\s+(?=(?:\d{4}년|오늘|내일|모레|다음\s*주|이번\s*주))""")
}
