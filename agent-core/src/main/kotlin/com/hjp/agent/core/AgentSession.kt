package com.hjp.agent.core

import com.hjp.agent.contract.AgentModelGateway
import com.hjp.agent.contract.AgentModelSession
import com.hjp.agent.contract.ConversationMemory
import com.hjp.agent.contract.ContactCandidate
import com.hjp.agent.contract.ModelConversationRole
import com.hjp.agent.contract.ModelSessionConfig
import com.hjp.agent.contract.SamplingProfile
import com.hjp.agent.contract.TrackedActionStatus
import com.hjp.agent.contract.TranscriptEntry
import com.hjp.agent.contract.TurnContext
import com.hjp.tool.contract.SessionStateKey
import com.hjp.tool.contract.SessionStateUpdate
import com.hjp.tool.contract.StoredSessionState
import com.hjp.tool.contract.ToolCatalogSnapshot
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one temporary session the agent keeps alive. Nothing here is persisted: the process holds it,
 * `새 대화` replaces it, and process death drops it.
 *
 * [generation] increases on every replacement. Work that started under an older generation must not
 * write into the new session.
 */
data class AgentSession(
    val sessionId: String,
    val generation: Long,
    val createdAtEpochMillis: Long,
    var catalogRevision: String? = null,
    var conversationMemory: ConversationMemory = ConversationMemory(),
    val capabilityState: MutableMap<SessionStateKey, StoredSessionState> = mutableMapOf(),
    /** Complete conversation. Never truncated for model-context reasons. */
    val transcript: MutableList<TranscriptEntry> = mutableListOf(),
) {
    fun turnContext(
        userText: String,
        availableTools: Set<String>,
        groundedCardId: String? = null,
        directoryMatches: List<com.hjp.agent.contract.DirectoryNameMatch> = emptyList(),
    ) = TurnContext(
        userText = userText,
        memory = conversationMemory,
        transcript = transcript.toList(),
        availableTools = availableTools,
        groundedCardId = groundedCardId,
        directoryMatches = directoryMatches,
    )
}

interface AgentSessionStore {
    suspend fun getOrCreate(): AgentSession
    suspend fun update(transform: (AgentSession) -> Unit)

    /** Replaces the session with an empty one and returns the new generation. */
    suspend fun replace(): Long
}

class InMemoryAgentSessionStore(
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : AgentSessionStore {
    private val mutex = Mutex()
    private var session: AgentSession? = null
    private var generation = 0L

    override suspend fun getOrCreate(): AgentSession = mutex.withLock { requireSession() }

    override suspend fun update(transform: (AgentSession) -> Unit) {
        mutex.withLock { transform(requireSession()) }
    }

    override suspend fun replace(): Long = mutex.withLock {
        generation += 1
        session = newSession()
        generation
    }

    private fun requireSession(): AgentSession = session ?: newSession().also { session = it }

    private fun newSession() =
        AgentSession(UUID.randomUUID().toString(), generation, clockMillis())
}

/** How a turn ended. Only open statuses stay resumable in memory. */
enum class TurnOutcome(val status: TrackedActionStatus) {
    COMPLETED(TrackedActionStatus.COMPLETED),
    NEEDS_CLARIFICATION(TrackedActionStatus.NEEDS_CLARIFICATION),
    FAILED(TrackedActionStatus.FAILED),
    CANCELLED(TrackedActionStatus.CANCELLED),
}

/** Marks the request as pending and appends it to the transcript before the turn runs. */
suspend fun AgentSessionStore.beginConversationTurn(
    turnId: String,
    userText: String,
    nowEpochMillis: Long,
    requestsAction: Boolean,
) {
    if (userText.isBlank()) return
    update { session ->
        session.conversationMemory = ConversationMemoryReducer.beginTurn(
            previous = session.conversationMemory,
            turnId = turnId,
            timestamp = nowEpochMillis,
            userText = userText,
            requestsAction = requestsAction,
        )
        session.transcript += TranscriptEntry(
            turnId, ModelConversationRole.USER, userText, nowEpochMillis,
        )
    }
}

/**
 * Closes the turn. The assistant text always reaches the transcript — including failures, so the
 * user can still ask why something failed — but only open statuses remain resumable work.
 */
suspend fun AgentSessionStore.completeConversationTurn(
    turnId: String,
    assistantText: String,
    outcome: TurnOutcome,
    executedTools: List<String>,
    nowEpochMillis: Long,
    detailKo: String? = null,
    outcomeType: com.hjp.agent.contract.TurnOutcomeType? = null,
) {
    update { session ->
        session.conversationMemory = ConversationMemoryReducer.completeTurn(
            previous = session.conversationMemory,
            turnId = turnId,
            timestamp = nowEpochMillis,
            outcome = outcome,
            executedTools = executedTools,
            detailKo = detailKo,
            outcomeType = outcomeType,
        )
        if (assistantText.isNotBlank()) {
            session.transcript += TranscriptEntry(
                turnId, ModelConversationRole.ASSISTANT, assistantText, nowEpochMillis,
                status = outcome.status,
            )
        }
    }
}

/** Folds a successful tool result into the bounded memory projection. */
suspend fun AgentSessionStore.projectToolResult(
    toolName: String,
    data: kotlinx.serialization.json.JsonObject,
    nowEpochMillis: Long,
    turnId: String = "",
) {
    update { session ->
        session.conversationMemory = ToolResultProjector.project(
            session.conversationMemory, toolName, data, nowEpochMillis, turnId,
        )
    }
}

/** Promotes a candidate only after the router has explicitly resolved its persisted card id. */
suspend fun AgentSessionStore.promoteCandidate(cardId: String, nowEpochMillis: Long) {
    if (cardId.isBlank()) return
    update { session ->
        val candidate = session.conversationMemory.candidateContacts
            .firstOrNull { it.cardId == cardId } ?: return@update
        session.conversationMemory = ToolResultProjector.selectCandidate(
            session.conversationMemory, candidate, nowEpochMillis,
        )
    }
}

/** Persists a route-grounded identity without claiming that contact fields were verified. */
suspend fun AgentSessionStore.persistGroundedTarget(candidate: ContactCandidate) {
    if (candidate.cardId.isBlank() || candidate.name.isBlank()) return
    update { session ->
        session.conversationMemory = ToolResultProjector.persistGroundedTarget(
            session.conversationMemory, candidate,
        )
    }
}

/** Drops every remembered target the user just rejected in a correction. */
suspend fun AgentSessionStore.rejectContactsNamed(term: String) {
    if (term.isBlank()) return
    update { session ->
        session.conversationMemory = ToolResultProjector.rejectContactsNamed(
            session.conversationMemory, term,
        )
    }
}

/** Drops a focus whose card id a lookup could not resolve, so no later turn acts on it. */
suspend fun AgentSessionStore.invalidateStaleCard(cardId: String) {
    if (cardId.isBlank()) return
    update { session ->
        session.conversationMemory = ToolResultProjector.invalidateStaleCard(
            session.conversationMemory, cardId,
        )
    }
}

/**
 * Retires the focus as an actionable target while leaving the mention history intact, so a later
 * "처음 말한 사람" still finds the person a later "그 사람" may no longer act on.
 */
suspend fun AgentSessionStore.retireActionableFocus() {
    update { session ->
        session.conversationMemory = ToolResultProjector.retireActionableFocus(session.conversationMemory)
    }
}

class AgentSessionManager(
    private val store: AgentSessionStore,
    private val modelGateway: AgentModelGateway,
    val systemInstructionText: String,
    private val localeTag: String,
    private val samplingProfile: SamplingProfile = SamplingProfile(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val modelMutex = Mutex()
    private var modelSession: AgentModelSession? = null

    suspend fun getOrCreate(): AgentSession = store.getOrCreate()

    suspend fun requireModelSession(snapshot: ToolCatalogSnapshot): AgentModelSession = modelMutex.withLock {
        val current = modelSession
        if (current != null && current.catalogRevision == snapshot.revision) return@withLock current
        current?.close()
        modelGateway.openSession(ModelSessionConfig(systemInstructionText, snapshot, localeTag, samplingProfile))
            .also { opened ->
                modelSession = opened
                store.update { it.catalogRevision = snapshot.revision }
            }
    }

    suspend fun apply(updates: List<SessionStateUpdate>) {
        if (updates.isEmpty()) return
        store.update { session ->
            updates.forEach { update ->
                val value = update.value
                if (value == null) session.capabilityState.remove(update.key)
                else session.capabilityState[update.key] = StoredSessionState(
                    update.schemaVersion, value, update.expiresAtEpochMillis)
            }
        }
    }

    /**
     * [requestsAction] comes from the router's classification of this turn. Small talk, recalled or
     * hypothetical speech and general questions are remembered as conversation, not as work the
     * agent owes.
     */
    suspend fun beginTurn(turnId: String, userText: String, requestsAction: Boolean) =
        store.beginConversationTurn(turnId, userText, clockMillis(), requestsAction)

    suspend fun completeTurn(
        turnId: String,
        assistantText: String,
        outcome: TurnOutcome,
        executedTools: List<String>,
        detailKo: String? = null,
        outcomeType: com.hjp.agent.contract.TurnOutcomeType? = null,
    ) = store.completeConversationTurn(
        turnId, assistantText, outcome, executedTools, clockMillis(), detailKo, outcomeType,
    )

    suspend fun projectToolResult(
        toolName: String,
        data: kotlinx.serialization.json.JsonObject,
        turnId: String,
    ) = store.projectToolResult(toolName, data, clockMillis(), turnId)

    suspend fun invalidateStaleCard(cardId: String) = store.invalidateStaleCard(cardId)

    /** Retires the focus as a target while leaving the conversation's mention history intact. */
    suspend fun retireActionableFocus() = store.retireActionableFocus()

    suspend fun persistGroundedTarget(candidate: ContactCandidate) =
        store.persistGroundedTarget(candidate)

    suspend fun rejectContactsNamed(term: String) = store.rejectContactsNamed(term)

    suspend fun promoteCandidate(cardId: String) =
        store.promoteCandidate(cardId, clockMillis())

    /**
     * Discards the whole session atomically: memory, transcript, tool state, and the native model
     * conversation. Returns the new generation so callers can drop stale in-flight work.
     */
    suspend fun reset(): Long = modelMutex.withLock {
        modelSession?.close()
        modelSession = null
        store.replace()
    }

    override fun close() {
        modelSession?.close()
        modelSession = null
        modelGateway.close()
    }
}

internal object ConversationMemoryReducer {
    private const val MAX_TOPIC_CHARS = 240
    private const val MAX_ITEM_CHARS = 300
    private const val MAX_ITEMS_PER_BUCKET = 8
    private const val MAX_TRACKED_ACTIONS = 12

    /**
     * How long a request stays resumable.
     *
     * Thirty minutes, matching the upstream structured-memory branch's `MAX_PENDING_AGE_MILLIS`.
     */
    private const val MAX_PENDING_AGE_MILLIS = 30L * 60 * 1_000

    private val preferenceMarkers = listOf("선호", "좋아", "싫어", "말투", "스타일", "앞으로")
    private val constraintMarkers = listOf("하지 마", "하지마", "말아", "반드시", "꼭", "전에 확인", "동의 없이")
    private val explicitFactMarkers = listOf("나는 ", "내 이름", "내 회사", "내 직책", "기억해", "라고 불러")
    private val correctionMarkers = listOf("아니", "말고", "잘못", "틀렸", "다시 말하면", "정정")

    fun beginTurn(
        previous: ConversationMemory,
        turnId: String,
        timestamp: Long,
        userText: String,
        requestsAction: Boolean,
    ): ConversationMemory {
        val normalized = userText.normalizeMemoryText()
        if (normalized.isEmpty()) return previous
        val item = ConversationMemoryItemFactory.create(normalized, turnId, timestamp)
        val corrections = if (correctionMarkers.any(normalized::contains)) {
            previous.corrections.upsert(item)
        } else {
            previous.corrections
        }
        // An action left open past the limit stops being work the agent owes. Checked here, at the
        // start of the next turn, because that is when the session next has a timestamp to compare
        // against — nothing polls, and no turn ages anything by itself.
        //
        // The boundary is inclusive: an action exactly MAX_PENDING_AGE_MILLIS old survives, and
        // expiry begins one millisecond later. Ported from the upstream structured-memory branch so
        // the two agree rather than each inventing an edge.
        val aged = previous.actions.map { action ->
            if (action.status.isOpen && timestamp - action.updatedAtEpochMillis > MAX_PENDING_AGE_MILLIS) {
                action.copy(status = TrackedActionStatus.EXPIRED, updatedAtEpochMillis = timestamp)
            } else {
                action
            }
        }

        // Every turn gets a record, because TrackedAction is also where the turn's typed outcome
        // lives. What changes is the flag: only a turn the router classified as a request counts as
        // work the agent owes, and only those form ConversationMemory.actionMemory.
        val pending = com.hjp.agent.contract.TrackedAction(
            turnId = turnId,
            request = normalized.take(MAX_ITEM_CHARS),
            status = TrackedActionStatus.PENDING,
            updatedAtEpochMillis = timestamp,
            requestsAction = requestsAction,
        )
        val actions = (aged.filterNot { it.turnId == turnId } + pending)
            .takeLast(MAX_TRACKED_ACTIONS)
        return previous.copy(
            topic = normalized.take(MAX_TOPIC_CHARS),
            corrections = corrections,
            actions = actions,
        )
    }

    fun completeTurn(
        previous: ConversationMemory,
        turnId: String,
        timestamp: Long,
        outcome: TurnOutcome,
        executedTools: List<String>,
        detailKo: String?,
        outcomeType: com.hjp.agent.contract.TurnOutcomeType? = null,
    ): ConversationMemory {
        val existing = previous.action(turnId)
        val request = existing?.request ?: previous.topic
        val updated = com.hjp.agent.contract.TrackedAction(
            turnId = turnId,
            request = request,
            status = outcome.status,
            updatedAtEpochMillis = timestamp,
            detailKo = detailKo?.normalizeMemoryText()?.take(MAX_ITEM_CHARS),
            executedTools = executedTools.distinct(),
            outcomeType = outcomeType,
            requestsAction = existing?.requestsAction ?: true,
        )
        val normalized = request.normalizeMemoryText()
        val item = ConversationMemoryItemFactory.create(normalized, turnId, timestamp)
        val preferences = if (preferenceMarkers.any(normalized::contains)) previous.preferences.upsert(item)
        else previous.preferences
        val constraints = if (constraintMarkers.any(normalized::contains)) previous.constraints.upsert(item)
        else previous.constraints
        val facts = if (explicitFactMarkers.any(normalized::contains)) previous.confirmedFacts.upsert(item)
        else previous.confirmedFacts
        return previous.copy(
            confirmedFacts = facts,
            preferences = preferences,
            constraints = constraints,
            actions = (previous.actions.filterNot { it.turnId == turnId } + updated)
                .takeLast(MAX_TRACKED_ACTIONS),
        )
    }

    /**
     * Replaces the previous value of the same key, or the same text when no key was derived.
     *
     * Keeping both values of one key is the failure this exists to prevent: the model would be handed
     * a stale company alongside the current one with nothing to distinguish them.
     */
    private fun List<com.hjp.agent.contract.ConversationMemoryItem>.upsert(
        item: com.hjp.agent.contract.ConversationMemoryItem,
    ): List<com.hjp.agent.contract.ConversationMemoryItem> {
        val withoutSuperseded = filterNot { existing ->
            if (item.key != null) existing.key == item.key else existing.content == item.content
        }
        return (withoutSuperseded + item).takeLast(MAX_ITEMS_PER_BUCKET)
    }

    /**
     * The subject and attribute a statement is about, as a stable key, or null.
     *
     * Derived from the sentence's own words — a first-person possessive plus a card attribute — so it
     * generalises to phrasings nobody wrote down. It names no person and no value.
     */
    internal object MemoryKeys {
        private val SUBJECT = Regex("(?:^|\\s)(?:내|제|나의|저의|우리|저희)\\s*")
        private val ATTRIBUTES: List<Pair<Regex, String>> = listOf(
            Regex("회사|직장|소속사") to "user.company",
            Regex("직책|직함|직급") to "user.title",
            Regex("부서|팀") to "user.department",
            Regex("이름|성함") to "user.name",
            Regex("이메일|메일\\s*주소") to "user.email",
            Regex("전화번호|휴대폰|핸드폰|연락처") to "user.phone",
            Regex("주소") to "user.address",
        )

        fun of(text: String): String? {
            if (!SUBJECT.containsMatchIn(" $text")) return null
            return ATTRIBUTES.firstOrNull { (pattern, _) -> pattern.containsMatchIn(text) }?.second
        }
    }

    private object ConversationMemoryItemFactory {
        fun create(content: String, turnId: String, timestamp: Long) =
            com.hjp.agent.contract.ConversationMemoryItem(
                content = content.take(MAX_ITEM_CHARS),
                sourceTurnId = turnId,
                updatedAtEpochMillis = timestamp,
                // Derived from the statement itself, so a restatement replaces rather than accumulates.
                key = MemoryKeys.of(content),
            )
    }
}

internal fun String.normalizeMemoryText(): String = replace(Regex("\\s+"), " ").trim()
