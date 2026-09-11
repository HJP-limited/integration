package com.hjp.agent.contract

import kotlinx.serialization.json.JsonObject

enum class AgentIntent {
    ANSWER_ONLY,
    CLARIFY,
    SEARCH_CONTACT,
    VIEW_CONTACT,
    COMPOSE_EMAIL,
    COMPOSE_SMS,
    CREATE_CALENDAR_EVENT,
    UPDATE_CONTACT,
    GET_CURRENT_DATETIME,
    UNSUPPORTED,
}

enum class RecipientType {
    NONE,
    CONTACT_NAME,
    EMAIL,
    PHONE,
    CARD_ID,
}

data class IntentRecipient(
    val type: RecipientType,
    val value: String,
)

data class StructuredIntentPlan(
    val intent: AgentIntent,
    val execute: Boolean,
    val recipient: IntentRecipient,
    val contentGoal: String,
    val dateExpression: String?,
    val timeExpression: String?,
    val calendarTitle: String?,
    val updates: JsonObject?,
    val clarificationQuestion: String?,
    val tone: String? = null,
)

enum class ComposeChannel { EMAIL, SMS }

data class ComposeContentRequest(
    val channel: ComposeChannel,
    val originalUserText: String,
    val contentGoal: String,
    val recipientDisplayName: String?,
    val recipientCompany: String? = null,
    val recipientTitle: String? = null,
    val requestedTone: String? = null,
    val userProvidedFacts: String = originalUserText,
    val validationFeedback: List<String> = emptyList(),
    val conversation: ConversationContext = ConversationContext(),
)

data class GeneratedComposeContent(
    val subject: String?,
    val body: String,
)

data class StructuredFinalRequest(
    val originalUserText: String,
    val plan: StructuredIntentPlan,
    val safeObservations: List<ModelToolResponse>,
    val completionHintKo: String,
    val conversation: ConversationContext = ConversationContext(),
)

sealed interface StructuredModelResult<out T> {
    data class Success<T>(
        val value: T,
        val rawOutputs: List<String>,
        val attempts: Int,
    ) : StructuredModelResult<T>

    data class Failure(
        val safeReasonKo: String,
        val rawOutputs: List<String>,
        val attempts: Int,
    ) : StructuredModelResult<Nothing>
}

/**
 * High-level model boundary. Implementations expose only constrained intent or
 * content schemas; Android low-level tools never appear in these model calls.
 */
interface StructuredAgentModelGateway : AutoCloseable {
    suspend fun analyzeIntent(
        userText: String,
        conversation: ConversationContext = ConversationContext(),
    ): StructuredModelResult<StructuredIntentPlan>

    suspend fun generateComposeContent(
        request: ComposeContentRequest,
    ): StructuredModelResult<GeneratedComposeContent>

    suspend fun generateFinalText(
        request: StructuredFinalRequest,
    ): StructuredModelResult<String>

    override fun close() = Unit
}
