package com.hjp.agent.contract

/**
 * Structured, bounded session memory.
 *
 * This is the canonical cross-turn state of the agent. It holds *projections* of tool results,
 * never raw tool payloads, and it is rebuilt by a deterministic reducer so refreshing it never
 * costs an extra on-device inference. The full user-visible conversation lives in the session
 * transcript, not here; this type only carries what a later turn needs in order to act.
 */
data class ConversationMemory(
    val schemaVersion: Int = SCHEMA_VERSION,
    val topic: String = "",
    val confirmedFacts: List<ConversationMemoryItem> = emptyList(),
    val preferences: List<ConversationMemoryItem> = emptyList(),
    val constraints: List<ConversationMemoryItem> = emptyList(),
    val corrections: List<ConversationMemoryItem> = emptyList(),
    val actions: List<TrackedAction> = emptyList(),
    val selectedContact: ContactReference? = null,
    /** Identity pinned by a grounded route before a contact read has verified its fields. */
    val groundedTargetIdentity: ContactCandidate? = null,
    val candidateContacts: List<ContactCandidate> = emptyList(),
    /** Every person this session has surfaced, oldest first. Answers "처음 말한 사람". */
    val contactMentions: List<ContactMention> = emptyList(),
) {
    val activeMentions: List<ContactMention> get() = contactMentions.filter { it.active }

    /**
     * The turns that actually asked the agent for something.
     *
     * [actions] is the per-turn record and carries the typed outcome of every turn including small
     * talk; this is the subset that represents work.
     */
    val actionMemory: List<TrackedAction> get() = actions.filter { it.requestsAction }

    val openActions: List<TrackedAction>
        get() = actions.filter { it.status.isOpen }

    fun action(turnId: String): TrackedAction? = actions.lastOrNull { it.turnId == turnId }

    companion object {
        const val SCHEMA_VERSION = 2
    }
}

data class ConversationMemoryItem(
    val content: String,
    val sourceTurnId: String,
    val updatedAtEpochMillis: Long,
    val confidence: MemoryConfidence = MemoryConfidence.EXPLICIT,
    /**
     * What this item is *about*, when that can be named — `user.company`, `user.title`.
     *
     * Items used to be de-duplicated by their exact text, so "내 회사는 비전글로벌이야" and "내 회사는
     * 새길테크야" were two different memories and both survived. Nothing marked the first as stale and
     * both could reach the model at once, which is worse than remembering nothing. A key says the two
     * are the same fact at different times, so the later one replaces the earlier.
     *
     * Null when no key can be derived; those items keep the older content-based behaviour.
     */
    val key: String? = null,
)

enum class MemoryConfidence { EXPLICIT, TOOL_VERIFIED }

/** Where a remembered value came from. Tool-verified values outrank anything the model inferred. */
enum class MemoryProvenance { TOOL_VERIFIED, USER_STATED, MODEL_INFERRED }

/** How the agent came to treat one contact as "the" current target. */
enum class ContactSelectionBasis { SINGLE_RESULT, USER_SELECTED, MODEL_INFERRED }

/**
 * The contact a follow-up turn may refer to. Deliberately excludes email and phone: a later
 * side-effecting turn must re-read them through `get_contact` with this [cardId] instead of
 * trusting a possibly stale copy.
 */
data class ContactReference(
    val cardId: String,
    val name: String,
    val company: String? = null,
    val title: String? = null,
    /**
     * Non-channel fields from the most recent successful card read.  They are useful for a
     * read-only follow-up such as "어느 지역이야?" but intentionally exclude email and phone:
     * recipient-facing actions must still fresh-read those values by card id.
     */
    val verifiedDisplayFields: Map<String, String> = emptyMap(),
    val selection: ContactSelectionBasis,
    val provenance: MemoryProvenance,
    val confirmedAtEpochMillis: Long,
) {
    val isActionable: Boolean
        get() = provenance == MemoryProvenance.TOOL_VERIFIED &&
            selection != ContactSelectionBasis.MODEL_INFERRED &&
            cardId.isNotBlank()
}

/** A bounded, distinguishable search candidate. Ordinal references ("두 번째") index this list. */
data class ContactCandidate(
    val cardId: String,
    val name: String,
    val company: String? = null,
    val title: String? = null,
) {
    val distinguishingLabel: String
        get() = listOfNotNull(name, company, title).joinToString(" · ")
}

enum class MentionRole { SELECTED, CANDIDATE }

/**
 * One bounded record that this session surfaced a person, kept so a much later turn can still say
 * "처음 말한 사람" without replaying the whole transcript.
 *
 * Deliberately excludes every contact channel: an action must re-read the card by [cardId].
 */
data class ContactMention(
    val turnId: String,
    val cardId: String,
    val name: String,
    val company: String? = null,
    val title: String? = null,
    val toolVerified: Boolean = true,
    val role: MentionRole = MentionRole.CANDIDATE,
    val order: Int = 0,
    /** Cleared when a later lookup proves the id stale. */
    val active: Boolean = true,
) {
    val distinguishingLabel: String
        get() = listOfNotNull(name, company, title).joinToString(" · ")
}

enum class TrackedActionStatus(val isOpen: Boolean) {
    PENDING(true),
    NEEDS_CLARIFICATION(true),
    COMPLETED(false),
    FAILED(false),
    CANCELLED(false),

    /**
     * Left open long enough that it is no longer work the agent owes.
     *
     * Distinct from CANCELLED, which is the user changing their mind. The record stays so "왜 그건
     * 안 됐어?" can still be answered; it simply stops being resumable.
     */
    EXPIRED(false),
}

/**
 * One user request tracked across its lifetime. A FAILED action stays visible so the user can ask
 * why it failed, but it is not resumable work: only [TrackedActionStatus.isOpen] statuses are.
 */
data class TrackedAction(
    val turnId: String,
    val request: String,
    val status: TrackedActionStatus,
    val updatedAtEpochMillis: Long,
    /** User-level reason. Never a raw exception or an internal stack detail. */
    val detailKo: String? = null,
    val executedTools: List<String> = emptyList(),
    /** What the turn produced, in the same vocabulary the evaluator asserts on. */
    val outcomeType: TurnOutcomeType? = null,
    /**
     * Whether this turn asked the agent to do something.
     *
     * Every turn is recorded, because this type also carries the turn's typed outcome. Only turns
     * with this flag are *action memory* — the work the agent owes — so small talk, a recalled or
     * hypothetical request and a general question no longer look like outstanding requests.
     */
    val requestsAction: Boolean = true,
)
