package com.hjp.agent.contract
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put

sealed interface StructuredDecodeResult<out T> {
    data class Success<T>(val value: T) : StructuredDecodeResult<T>
    data class Failure(val errors: List<String>) : StructuredDecodeResult<Nothing>
}

object StructuredIntentCodec {
    fun decode(arguments: JsonObject): StructuredDecodeResult<StructuredIntentPlan> {
        val errors = mutableListOf<String>()
        val intent = arguments.string("intent")?.let {
            runCatching { AgentIntent.valueOf(it) }.getOrNull()
        } ?: run {
            errors += "intent must be a supported enum value"
            null
        }
        val execute = (arguments["execute"] as? JsonPrimitive)?.booleanOrNull ?: run {
            errors += "execute must be boolean"
            null
        }
        val nonExecutingIntent = intent in setOf(
            AgentIntent.ANSWER_ONLY,
            AgentIntent.CLARIFY,
            AgentIntent.UNSUPPORTED,
        )
        val recipientType = arguments.string("recipient_type")?.let {
            runCatching { RecipientType.valueOf(it) }.getOrNull()
        } ?: if (nonExecutingIntent) {
            RecipientType.NONE
        } else {
            errors += "recipient_type must be a supported enum value"
            null
        }
        val recipientValue = arguments.string("recipient_value") ?: if (nonExecutingIntent) {
            ""
        } else {
            errors += "recipient_value must be a string"
            ""
        }
        val legacyUpdates = arguments["updates"] as? JsonObject
        val updateField = arguments.string("update_field")
        val updateValue = arguments.string("update_value")
        val updates = legacyUpdates ?: updateField?.let { field ->
            buildJsonObject { put(field, updateValue.orEmpty()) }
        }

        if (intent != null && execute != null) {
            val mustNotExecute = intent in setOf(
                AgentIntent.ANSWER_ONLY,
                AgentIntent.CLARIFY,
                AgentIntent.UNSUPPORTED,
            )
            if (mustNotExecute == execute) {
                errors += "execute contradicts intent"
            }
        }
        if (recipientType != null) {
            when (recipientType) {
                RecipientType.NONE -> if (recipientValue.isNotBlank()) {
                    errors += "NONE recipient must have an empty value"
                }
                RecipientType.EMAIL -> if (!EMAIL_REGEX.matches(recipientValue)) {
                    errors += "recipient_value is not a valid email"
                }
                RecipientType.PHONE -> if (!PHONE_REGEX.matches(recipientValue)) {
                    errors += "recipient_value is not a valid phone"
                }
                RecipientType.CONTACT_NAME, RecipientType.CARD_ID ->
                    if (recipientValue.isBlank()) errors += "recipient_value is required"
            }
        }
        if (intent in setOf(AgentIntent.COMPOSE_EMAIL, AgentIntent.COMPOSE_SMS) &&
            recipientType == RecipientType.NONE
        ) {
            errors += "compose intent requires a recipient"
        }
        if (intent == AgentIntent.COMPOSE_EMAIL &&
            recipientType !in setOf(RecipientType.EMAIL, RecipientType.CONTACT_NAME)
        ) {
            errors += "COMPOSE_EMAIL requires EMAIL or CONTACT_NAME"
        }
        if (intent == AgentIntent.COMPOSE_SMS &&
            recipientType !in setOf(RecipientType.PHONE, RecipientType.CONTACT_NAME)
        ) {
            errors += "COMPOSE_SMS requires PHONE or CONTACT_NAME"
        }
        if (intent == AgentIntent.CREATE_CALENDAR_EVENT) {
            if (arguments.string("date_expression").isNullOrBlank()) {
                errors += "calendar date_expression is required"
            }
            if (arguments.string("time_expression").isNullOrBlank()) {
                errors += "calendar time_expression is required"
            }
            if (arguments.string("calendar_title").isNullOrBlank()) {
                errors += "calendar_title is required"
            }
        }
        if (intent == AgentIntent.UPDATE_CONTACT &&
            (updates.isNullOrEmpty() || updates.values.any {
                (it as? JsonPrimitive)?.content?.isBlank() != false
            })
        ) {
            errors += "UPDATE_CONTACT requires updates"
        }
        if (intent != null && intent != AgentIntent.UPDATE_CONTACT && !updates.isNullOrEmpty()) {
            errors += "updates are allowed only for UPDATE_CONTACT"
        }
        if (intent == AgentIntent.CLARIFY &&
            arguments.string("clarification_question").isNullOrBlank()
        ) {
            errors += "CLARIFY requires clarification_question"
        }
        if (errors.isNotEmpty() || intent == null || execute == null || recipientType == null) {
            return StructuredDecodeResult.Failure(errors.distinct())
        }
        return StructuredDecodeResult.Success(
            StructuredIntentPlan(
                intent = intent,
                execute = execute,
                recipient = IntentRecipient(recipientType, recipientValue.trim()),
                contentGoal = arguments.string("content_goal").orEmpty(),
                dateExpression = arguments.string("date_expression").notBlank(),
                timeExpression = arguments.string("time_expression").notBlank(),
                calendarTitle = arguments.string("calendar_title").notBlank(),
                updates = updates,
                clarificationQuestion = arguments.string("clarification_question").notBlank(),
            ),
        )
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun String?.notBlank(): String? = this?.trim()?.takeIf(String::isNotEmpty)

    private val EMAIL_REGEX =
        Regex("""(?i)^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+$""")
    private val PHONE_REGEX = Regex("""^(?:\+82[- ]?|0)\d{1,2}[- ]?\d{3,4}[- ]?\d{4}$""")
}

object ComposeContentValidator {
    fun validate(
        request: ComposeContentRequest,
        content: GeneratedComposeContent,
    ): List<String> {
        val errors = mutableListOf<String>()
        if (content.body.isBlank()) errors += "body is required"
        when (request.channel) {
            ComposeChannel.EMAIL -> if (content.subject.isNullOrBlank()) {
                errors += "email subject is required"
            }
            ComposeChannel.SMS -> if (content.subject != null) {
                errors += "sms subject is forbidden"
            }
        }
        val combined = listOfNotNull(content.subject, content.body).joinToString("\n")
        if (PLACEHOLDER_REGEX.containsMatchIn(combined) ||
            FORBIDDEN_PLACEHOLDER_WORDS.any { combined.contains(it, ignoreCase = true) }
        ) {
            errors += "placeholder is forbidden"
        }
        if (FALSE_COMPLETION_REGEX.containsMatchIn(combined)) {
            errors += "message content must not claim completed sending or saving"
        }
        if (INSTRUCTION_ECHO_REGEX.containsMatchIn(combined)) {
            errors += "message content must not copy UI or agent instructions"
        }
        val allowedFacts = request.originalUserText + "\n" + request.contentGoal
        val hallucinatedTime = TIME_FACT_REGEX.findAll(combined)
            .map { it.value }
            .firstOrNull { it !in allowedFacts }
        if (hallucinatedTime != null) {
            errors += "unsupported date or time fact: $hallucinatedTime"
        }
        return errors
    }

    private val PLACEHOLDER_REGEX = Regex("""\[[^\]]+]""")
    private val FORBIDDEN_PLACEHOLDER_WORDS = listOf("client님", "ooo님", "당신의 이름")
    private val FALSE_COMPLETION_REGEX = Regex(
        """(?:메일|이메일|문자).{0,12}(?:전송(?:이)?\s*완료|보냈습니다|발송(?:이)?\s*완료)|일정.{0,12}(?:저장|등록)\s*완료""",
    )
    private val INSTRUCTION_ECHO_REGEX = Regex(
        """(?:작성\s*화면\s*열|전송했다고\s*말|저장\s*완료됐다고\s*말)""",
    )
    private val TIME_FACT_REGEX = Regex(
        """(?:오늘|내일|모레|다음\s*주|이번\s*주|월요일|화요일|수요일|목요일|금요일|토요일|일요일|오전\s*\d{1,2}시|오후\s*\d{1,2}시|\d{4}년\s*\d{1,2}월\s*\d{1,2}일)""",
    )
}
