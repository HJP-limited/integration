package com.hjp.agent.contract

data class CapabilityViolation(
    val reason: String,
    val requiredAction: AgentAction = AgentAction.UNSUPPORTED,
)

/**
 * Final capability veto for an already model-classified request. It cannot
 * infer or replace intent and never creates a tool call.
 */
object CapabilityActionPolicy {
    fun violation(
        userText: String,
        classification: IntentClassification,
    ): CapabilityViolation? {
        if (classification.action != AgentAction.EXECUTE) return null
        return when {
            classification.intent in setOf(StageIntent.COMPOSE_EMAIL, StageIntent.COMPOSE_SMS) &&
                REAL_SEND.containsMatchIn(userText) ->
                CapabilityViolation("REAL_SEND_NOT_SUPPORTED")
            classification.intent in setOf(StageIntent.UPDATE_CONTACT, StageIntent.CREATE_CALENDAR_EVENT) &&
                DELETE.containsMatchIn(userText) ->
                CapabilityViolation("DELETE_NOT_SUPPORTED")
            classification.intent == StageIntent.GENERAL &&
                (PHONE_CALL.containsMatchIn(userText) || UPLOAD.containsMatchIn(userText)) ->
                CapabilityViolation("EXTERNAL_ACTION_NOT_SUPPORTED")
            else -> null
        }
    }

    private val REAL_SEND = Regex(
        """(?:(?:실제로|자동(?:으로)?|지금\s*바로).{0,12}(?:전송|발송|보내)|(?:전송|발송|보내).{0,12}(?:실제로|자동(?:으로)?|지금\s*바로))""",
    )
    private val DELETE = Regex("""(?:삭제|지워|제거)(?:해|하|해\s*줘|해\s*주세요)?""")
    private val PHONE_CALL = Regex("""(?:전화|통화).{0,8}(?:걸어|해\s*줘|연결)""")
    private val UPLOAD = Regex("""(?:외부|서버|클라우드).{0,10}(?:업로드|전송)""")
}
