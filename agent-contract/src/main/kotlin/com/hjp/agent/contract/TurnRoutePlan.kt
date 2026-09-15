package com.hjp.agent.contract

/**
 * What the deterministic pre-router decided about a turn, before any tool runs.
 *
 * The router never performs a side effect itself. It returns one of these decisions and the common
 * validator/workflow decides whether and how to execute, so the ReAct kernel, the structured
 * kernel and the emulator router all obey the same safety rules.
 */
sealed interface TurnRoutePlan {
    /** Nothing in memory was needed; continue normal parsing on [text]. */
    data class Continue(
        val text: String,
        val searchRequired: Boolean = false,
        val searchQuery: String? = null,
        /** True only for unresolved explicit person-name acquisition, never attribute search. */
        val namedTargetAcquisition: Boolean = false,
    ) : TurnRoutePlan

    /**
     * A reference such as "방금 찾은 사람" resolved to a tool-verified contact. [requiresFreshRead]
     * means the turn must re-read the card by [cardId] before any recipient-facing action; the
     * remembered projection deliberately carries no email or phone number.
     */
    data class GroundedContact(
        val text: String,
        val cardId: String,
        val name: String,
        val requiresFreshRead: Boolean = true,
        /** True when this target explicitly replaces and invalidates the prior selected contact. */
        val replacesPreviousTarget: Boolean = false,
    ) : TurnRoutePlan

    /**
     * A question about a field of a contact this session already established.
     *
     * The remembered projection carries no email or phone, and even the fields it does carry may be
     * stale, so the turn is answered from a fresh read of [cardId] rather than from memory. It is a
     * read-only lookup: no model inference and no side effect are involved.
     */
    data class ContactDetail(
        val cardId: String,
        val name: String,
        /** Field keys the user asked about. Empty means "show the card". */
        val requestedFields: List<String> = emptyList(),
    ) : TurnRoutePlan

    /**
     * "김지원 말고 최영희" — the user replaced the target mid-conversation.
     *
     * [text] names only the replacement, so a downstream search cannot retrieve the rejected person
     * as well, and [rejectedTerm] is what the kernel drops from memory before continuing.
     */
    data class CorrectionReplacement(
        val text: String,
        val rejectedTerm: String,
    ) : TurnRoutePlan

    /** The target or a required slot is missing or ambiguous. Ask instead of guessing. */
    data class Clarify(val questionKo: String, val reason: ClarifyReason) : TurnRoutePlan

    /** The user asked about the conversation itself; answer from memory without any tool. */
    data class AnswerFromHistory(val messageKo: String) : TurnRoutePlan

    /**
     * A question about a concept, format or method. It names no target and requests no execution, so
     * it is answered directly and can never produce a side effect.
     */
    data class GeneralInformation(val messageKo: String) : TurnRoutePlan

    /** The request names a capability this agent does not have. */
    data class Unsupported(val messageKo: String) : TurnRoutePlan

    /** Not decidable by rules; let the model decide. */
    data object DeferToModel : TurnRoutePlan
}

enum class ClarifyReason {
    NO_KNOWN_TARGET,
    AMBIGUOUS_TARGET,
    MISSING_REQUIRED_SLOT,
    UNGROUNDED_REFERENCE,
}
