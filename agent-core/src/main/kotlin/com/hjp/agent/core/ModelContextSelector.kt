package com.hjp.agent.core

import com.hjp.agent.contract.ConversationMemory
import com.hjp.agent.contract.ConversationMemoryItem
import com.hjp.agent.contract.ConversationTurn
import com.hjp.agent.contract.ConversationTurns
import com.hjp.agent.contract.ModelPromptContext
import com.hjp.agent.contract.ModelPromptSection
import com.hjp.agent.contract.TrackedActionStatus
import com.hjp.agent.contract.TranscriptEntry
import kotlinx.serialization.json.JsonObject

/** Estimates prompt tokens without loading a tokenizer into the app process. */
fun interface TokenEstimator {
    fun estimate(text: String): Int
}

/**
 * Calibrated against the real SentencePiece tokenizer inside
 * `models/gemma-4-E2B-it.litertlm` (see `tools/agent_eval/measure_context_budget.py`).
 *
 * Measured chars/token: Korean request 1.71, Korean answer 1.93, mixed memory block 2.67,
 * ASCII tool schema 3.85. The two-class rates below never under-count any measured sample and
 * over-count by at most 28 %, the worst case being dense ASCII JSON, which SentencePiece merges
 * aggressively. That case is budgeted separately through
 * [ContextBudget.reservedForToolCatalogTokens], so the residual error stays on the safe side.
 */
object CalibratedGemmaTokenEstimator : TokenEstimator {
    private const val CJK_TOKENS_PER_CHAR = 0.86
    private const val OTHER_TOKENS_PER_CHAR = 0.29

    override fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        text.forEach { char -> if (char.isCjk()) cjk += 1 else other += 1 }
        val estimate = cjk * CJK_TOKENS_PER_CHAR + other * OTHER_TOKENS_PER_CHAR
        return maxOf(1, kotlin.math.ceil(estimate).toInt())
    }

    private fun Char.isCjk(): Boolean = this.code in 0xAC00..0xD7A3 || // Hangul syllables
        this.code in 0x1100..0x11FF || // Hangul jamo
        this.code in 0x3130..0x318F ||
        this.code in 0x4E00..0x9FFF || // CJK ideographs
        this.code in 0x3040..0x30FF
}

/**
 * Token budget for one model request.
 *
 * [maxPromptTokens] is a deployment property, not a model constant. `hjp-agent.litertlm` embeds
 * `max_num_tokens: 1024`, which the tool catalog alone already exceeds; `gemma-4-E2B-it.litertlm`
 * embeds no limit. The value must therefore be set by the composition root for the artifact that
 * is actually shipped.
 */
data class ContextBudget(
    val maxPromptTokens: Int = 3_072,
    val reservedForToolCatalogTokens: Int = 900,
    val reservedForResponseTokens: Int = 256,
    val maxRecentTurns: Int = 4,
    val maxRelevantTurns: Int = 3,
    val maxDigestChars: Int = 1_500,
    val maxMessageChars: Int = 1_000,
    /** Room kept for the request that will follow the current one. */
    val nextTurnReserveTokens: Int = 384,
    val safetyMarginTokens: Int = 128,
) {
    /**
     * How many tokens of conversation history one request may carry.
     *
     * Every reserve is subtracted, including the one for the *next* request: bootstrapping a
     * rotated conversation with more history than this leaves the fresh conversation immediately
     * inadmissible, which is how a rotation could fire on every turn and still never restore
     * headroom. [fixedContextTokens] defaults to the assumed catalog reserve, but callers that know
     * the real system+catalog cost should pass it, because an assumption that is off by a few
     * hundred tokens is exactly the error this reserve exists to absorb.
     */
    fun historyAllowance(
        sessionStateTokens: Int,
        currentInputTokens: Int,
        fixedContextTokens: Int = reservedForToolCatalogTokens,
    ): Int = (
        maxPromptTokens - fixedContextTokens - reservedForResponseTokens - nextTurnReserveTokens -
            safetyMarginTokens - sessionStateTokens - currentInputTokens
        ).coerceAtLeast(0)

    /**
     * Whether an accumulated native input still fits, reserves included.
     *
     * `raw + next_request_reserve + output_reserve + safety_margin <= budget` is the whole rule.
     * Comparing raw tokens against the budget on its own is what makes an over-budget conversation
     * look admissible: at 40 turns a 2,757-token history is under a 3,072 budget only if the 768
     * tokens of reserve are left out of the sum.
     */
    fun admit(rawNativeTokens: Int): ContextAdmission = ContextAdmission(
        rawNativeTokens = rawNativeTokens,
        nextRequestReserveTokens = nextTurnReserveTokens,
        outputReserveTokens = reservedForResponseTokens,
        safetyMarginTokens = safetyMarginTokens,
        budgetTokens = maxPromptTokens,
    )

    /**
     * Rotation must consider the whole native conversation plus what the next turn will add, not
     * just the size of the block being sent now.
     *
     * The tool catalog is *not* subtracted here: the caller passes an accumulated total that already
     * contains it, and rotating never removes it, so subtracting it again would rotate on every
     * turn. A catalog that cannot fit at all is a deployment error caught by [ContextPreflight].
     */
    fun rotationThresholdTokens(): Int =
        (maxPromptTokens - reservedForResponseTokens - nextTurnReserveTokens - safetyMarginTokens)
            .coerceAtLeast(1)
}

/**
 * One admission decision, with every term kept so a report can show the arithmetic instead of a
 * verdict. [requiredTotalTokens] is the number that must be compared against the budget.
 */
data class ContextAdmission(
    val rawNativeTokens: Int,
    val nextRequestReserveTokens: Int,
    val outputReserveTokens: Int,
    val safetyMarginTokens: Int,
    val budgetTokens: Int,
) {
    val reserveTokens: Int
        get() = nextRequestReserveTokens + outputReserveTokens + safetyMarginTokens

    val requiredTotalTokens: Int get() = rawNativeTokens + reserveTokens

    val admitted: Boolean get() = requiredTotalTokens <= budgetTokens

    val headroomTokens: Int get() = budgetTokens - requiredTotalTokens
}

/**
 * How app-side history relates to the gateway's native conversation.
 *
 * A native LiteRT `Conversation` re-renders every stored turn through the model's chat template on
 * each send, so repeating the same turns inside the user message duplicates them.
 */
enum class ConversationHistoryStrategy {
    /** Keep the native conversation; send only state and the current input after turn 1. */
    NATIVE_ONLY,

    /** App memory is canonical; bootstrap bounded context into a fresh/rotated native conversation. */
    APP_CANONICAL_BOOTSTRAP,

    /** Send full app history every turn on a persistent native conversation. */
    DUPLICATE_BASELINE,
}

data class ContextRequest(
    val transcript: List<TranscriptEntry>,
    val memory: ConversationMemory,
    val currentInput: String,
    val currentTurnId: String,
    val capabilityContext: JsonObject? = null,
    val strategy: ConversationHistoryStrategy = ConversationHistoryStrategy.APP_CANONICAL_BOOTSTRAP,
    val nativeConversationTurns: Int = 0,
    val nativeConversationTokens: Int = 0,
    /** Measured system + tool catalog cost every request pays. 0 means "use the assumed reserve". */
    val fixedContextTokens: Int = 0,
    /**
     * Contact resolved for the request being executed now.
     *
     * This is deliberately not part of [ConversationMemory]: `selected_contact` is the last
     * contact verified by a successful get_contact, while this value is the router-grounded target
     * of the current user turn. Keeping both lets a correction show the model the new target
     * without misrepresenting it as an already fresh-read selection.
     */
    val currentTarget: CurrentContactTarget? = null,
    /**
     * A newly explicit contact begins an action scope. Prior conversation remains durable memory,
     * but only turns tied to this target are actionable prompt context for this request.
     */
    val targetScopedHistory: Boolean = false,
)

data class CurrentContactTarget(
    val cardId: String,
    val name: String,
    val evidence: TurnContactTargetResolver.Evidence,
    val requiresFreshRead: Boolean,
    /** Purpose the workflow will require before a contact-dependent terminal action. */
    val freshReadPurpose: String,
)

/**
 * Builds the ordered, token-bounded model context.
 *
 * Section order is fixed so the model always sees policy, then verified state, then references,
 * then verbatim recency, then compressed history, then the current request last.
 */
class ModelContextSelector(
    private val budget: ContextBudget = ContextBudget(),
    private val estimator: TokenEstimator = CalibratedGemmaTokenEstimator,
) {
    fun select(request: ContextRequest): ModelPromptContext {
        val unscopedHistory = ConversationTurns.from(
            request.transcript.filter { it.turnId != request.currentTurnId },
        )
        val history = request.currentTarget
            ?.takeIf { request.targetScopedHistory }
            ?.let { target -> unscopedHistory.filter { it.matches(target.name) } }
            ?: unscopedHistory
        val stateBody = renderSessionState(request)
        val stateTokens = estimator.estimate(stateBody)
        val inputTokens = estimator.estimate(request.currentInput)

        val bootstrapping = request.targetScopedHistory || when (request.strategy) {
            ConversationHistoryStrategy.DUPLICATE_BASELINE -> true
            ConversationHistoryStrategy.NATIVE_ONLY -> request.nativeConversationTurns == 0
            ConversationHistoryStrategy.APP_CANONICAL_BOOTSTRAP ->
                request.nativeConversationTurns == 0 || needsRotation(request)
        }
        val startNewConversation = request.targetScopedHistory ||
            (request.strategy == ConversationHistoryStrategy.APP_CANONICAL_BOOTSTRAP &&
                request.nativeConversationTurns > 0 && needsRotation(request))

        val sections = mutableListOf<ModelPromptSection>()
        if (stateBody.isNotBlank()) sections += ModelPromptSection(SESSION_STATE, stateBody)

        if (bootstrapping && history.isNotEmpty()) {
            var allowance = budget.historyAllowance(
                stateTokens,
                inputTokens,
                request.fixedContextTokens.takeIf { it > 0 } ?: budget.reservedForToolCatalogTokens,
            )
            val recent = takeRecent(history, allowance)
            allowance -= recent.tokens
            val usedIds = recent.turns.map { it.turnId }.toMutableSet()

            val relevant = takeRelevant(history.filterNot { it.turnId in usedIds }, request, allowance)
            allowance -= relevant.tokens
            usedIds += relevant.turns.map { it.turnId }

            // Both blocks are emitted in transcript order and share no turn, so the model never sees
            // the same exchange twice or an answer detached from its request.
            if (relevant.turns.isNotEmpty()) {
                sections += ModelPromptSection(RELEVANT_HISTORY, renderTurns(relevant.turns))
            }
            if (recent.turns.isNotEmpty()) {
                sections += ModelPromptSection(RECENT_CONVERSATION, renderTurns(recent.turns))
            }
            val digest = renderDigest(history.filterNot { it.turnId in usedIds })
            if (digest.isNotBlank()) sections += ModelPromptSection(HISTORY_DIGEST, digest)
        }

        val body = sections.joinToString("\n") { "[${it.name}]\n${it.body}" }
        return ModelPromptContext(
            sections = sections,
            startNewNativeConversation = startNewConversation,
            estimatedTokens = estimator.estimate(body) + inputTokens,
        )
    }

    // Rotation and admission are the same question asked at different moments, so they share one
    // formula: rotate exactly when the accumulated input would no longer be admissible.
    private fun needsRotation(request: ContextRequest): Boolean =
        !budget.admit(request.nativeConversationTokens).admitted

    private data class Picked(val turns: List<ConversationTurn>, val tokens: Int)

    private fun takeRecent(history: List<ConversationTurn>, allowance: Int): Picked {
        val picked = ArrayDeque<ConversationTurn>()
        var tokens = 0
        for (turn in history.asReversed()) {
            if (picked.size >= budget.maxRecentTurns) break
            val cost = estimator.estimate(renderTurn(turn))
            if (tokens + cost > allowance) break
            picked.addFirst(turn)
            tokens += cost
        }
        return Picked(picked.toList(), tokens)
    }

    /**
     * Pulls back whole turns that the recent window already dropped but the current request depends
     * on, so a person named twelve turns ago is not lost to simple truncation.
     */
    private fun takeRelevant(
        candidates: List<ConversationTurn>,
        request: ContextRequest,
        allowance: Int,
    ): Picked {
        if (allowance <= 0 || candidates.isEmpty()) return Picked(emptyList(), 0)
        val terms = relevanceTerms(request)
        if (terms.isEmpty()) return Picked(emptyList(), 0)
        val scored = candidates
            .map { turn -> turn to terms.count(turn::matches) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<ConversationTurn, Int>> { it.second }
                .thenByDescending { it.first.sequence })
            .take(budget.maxRelevantTurns)
        val picked = mutableListOf<ConversationTurn>()
        var tokens = 0
        for ((turn, _) in scored) {
            val cost = estimator.estimate(renderTurn(turn))
            if (tokens + cost > allowance) break
            picked += turn
            tokens += cost
        }
        return Picked(picked.sortedBy { it.sequence }, tokens)
    }

    private fun relevanceTerms(request: ContextRequest): List<String> {
        val fromInput = TERM_REGEX.findAll(request.currentInput)
            .map { it.value }
            .flatMap { token -> sequenceOf(token, token.stripKoreanParticle()) }
            .filter { it.length >= 2 }
            .toList()
        val fromMemory = listOfNotNull(
            request.currentTarget?.name,
            request.memory.selectedContact?.name,
            request.memory.selectedContact?.company,
        )
        return (fromInput + fromMemory).distinct().take(MAX_TERMS)
    }

    /** "박민수에게" and "박민수" must match the same earlier turn. */
    private fun String.stripKoreanParticle(): String {
        KOREAN_PARTICLES.forEach { particle ->
            if (length > particle.length + 1 && endsWith(particle)) {
                return dropLast(particle.length)
            }
        }
        return this
    }

    private fun renderTurns(turns: List<ConversationTurn>): String =
        turns.sortedBy { it.sequence }.joinToString("\n") { renderTurn(it) }

    private fun renderTurn(turn: ConversationTurn): String = buildString {
        append("turn ").append(turn.sequence + 1).append('\n')
        append("User: ").append(turn.userText.singleLine(budget.maxMessageChars))
        turn.assistantText?.let { answer ->
            append('\n').append("Assistant: ").append(statusMarker(turn))
                // Verified target/candidate state is rendered separately.  Do not feed
                // assistant-generated (and occasionally malformed) identifiers back into the
                // next model decision; the transcript remains intact in ConversationMemory.
                .append(answer.stripAssistantIdentifiers().singleLine(budget.maxMessageChars))
        }
    }

    private fun renderDigest(turns: List<ConversationTurn>): String {
        if (turns.isEmpty()) return ""
        // Turn-scoped entries, so a digest line can never duplicate a turn shown above.
        val text = turns.sortedBy { it.sequence }.joinToString("\n") { turn ->
            buildString {
                append("turn ").append(turn.sequence + 1).append(": ")
                append(turn.userText.singleLine(DIGEST_MESSAGE_CHARS))
                turn.assistantText?.let {
                    append(" → ").append(statusMarker(turn))
                        .append(it.stripAssistantIdentifiers().singleLine(DIGEST_MESSAGE_CHARS))
                }
            }
        }
        if (text.length <= budget.maxDigestChars) return text
        val tail = text.takeLast(budget.maxDigestChars)
        return tail.substringAfter('\n', missingDelimiterValue = tail)
    }

    private fun statusMarker(turn: ConversationTurn): String = when (turn.status) {
        null, TrackedActionStatus.COMPLETED -> ""
        else -> "(${turn.status!!.name}) "
    }

    private fun String.singleLine(maxChars: Int): String =
        replace(Regex("\\s+"), " ").trim().take(maxChars)

    private fun String.stripAssistantIdentifiers(): String =
        replace(Regex("(?i)(?:\\bcard[ _-]?id|카드\\s*ID|명함\\s*ID)\\s*[_=:：]?\\s*[A-Za-z0-9_-]+"), "")

    /**
     * Renders verified state only. The turn currently being executed is excluded: restating the
     * live request as "pending work" would both duplicate `[current_user]` and make a first turn
     * differ from a single-turn prompt.
     */
    private fun renderSessionState(request: ContextRequest): String {
        val memory = request.memory
        val capability = request.capabilityContext
        val open = memory.actions.filter { it.status.isOpen && it.turnId != request.currentTurnId }
        val closed = memory.actions
            .filterNot { it.status.isOpen }
            .filter { it.turnId != request.currentTurnId }
            .takeLast(CLOSED_ACTION_LIMIT)
        val hasContent = request.currentTarget != null ||
            memory.selectedContact != null ||
            memory.candidateContacts.isNotEmpty() ||
            // A session whose only memory is what the user said about themselves still has state
            // worth stating. Leaving facts out of this test is what made a remembered company
            // produce no state section at all.
            memory.confirmedFacts.isNotEmpty() ||
            memory.corrections.isNotEmpty() ||
            memory.preferences.isNotEmpty() ||
            memory.constraints.isNotEmpty() ||
            open.isNotEmpty() ||
            closed.isNotEmpty() ||
            capability?.isNotEmpty() == true
        if (!hasContent) return ""
        return buildString {
            append("schema_version: ").append(memory.schemaVersion).append('\n')
            request.currentTarget?.let { target ->
                append("current_target: card_id=").append(target.cardId)
                append(" name=").append(target.name)
                append(" evidence=").append(target.evidence.name)
                append(" requires_fresh_read=").append(target.requiresFreshRead)
                append(" fresh_read_purpose=").append(target.freshReadPurpose).append('\n')
                append("current_target_note: 현재 요청의 실행 대상입니다. ")
                append("contact-dependent action 전에 이 card_id로 get_contact를 호출하고 ")
                append("검증된 상세정보만 사용하세요.\n")
                if (isExplicitDetailFetch(request.currentInput)) {
                    append("resolved_target_detail_guidance: 확정된 대상의 상세 조회입니다. ")
                    append("get_contact(card_id=").append(target.cardId)
                        .append(", purpose=display)를 호출하세요.\n")
                }
            }
            memory.selectedContact?.let { contact ->
                append("selected_contact: card_id=").append(contact.cardId)
                append(" name=").append(contact.name)
                contact.company?.let { append(" company=").append(it) }
                contact.title?.let { append(" title=").append(it) }
                contact.verifiedDisplayFields.forEach { (field, value) ->
                    append(' ').append(field).append('=').append(value)
                }
                append(" basis=").append(contact.selection.name)
                append(" provenance=").append(contact.provenance.name).append('\n')
                append("contact_note: 이메일과 전화번호는 기억하지 않습니다. 실행 전 card_id로 다시 조회하세요.\n")
            }
            if (memory.candidateContacts.isNotEmpty()) {
                append("candidates:\n")
                memory.candidateContacts.forEachIndexed { index, candidate ->
                    append("- ").append(index + 1).append(". ")
                        .append(candidate.distinguishingLabel)
                        .append(" (card_id=").append(candidate.cardId).append(")\n")
                }
            }
            // What the user has told the agent about themselves. Stated rather than left to be
            // inferred from history, because on an ordinary turn there is no history section: the
            // native conversation already holds it, so anything not stated here does not reach the
            // model at all.
            appendKeyedItems("facts", memory.confirmedFacts)
            appendItems("corrections", memory.corrections.map { it.content })
            appendItems("preferences", memory.preferences.map { it.content })
            appendItems("constraints", memory.constraints.map { it.content })
            // The action ledger is deliberately not rendered. It holds the agent's own audit
            // trail — statuses, the raw request text and the tools each turn executed — none of
            // which is a fact about the user, and all of which the model would read as context it
            // should act on. The deterministic router and the failure-question path still read it
            // from memory; it simply never becomes prompt text.
            capability?.takeIf { it.isNotEmpty() }?.let {
                append("tool_session_context: ").append(it.toString()).append('\n')
            }
        }.trimEnd()
    }

    private fun StringBuilder.appendItems(label: String, values: List<String>) {
        if (values.isEmpty()) return
        append(label).append(":\n")
        values.forEach { append("- ").append(it).append('\n') }
    }

    /**
     * A narrow model-facing hint for an explicit fresh detail request.  Field questions such as
     * "회사가 어디야?" remain answerable from verified context and deliberately do not activate it.
     */
    private fun isExplicitDetailFetch(input: String): Boolean {
        val text = input.replace(Regex("\\s+"), " ").trim().lowercase()
        val detailNouns = listOf("상세", "연락처", "전화번호", "이메일", "메일 주소", "명함 정보", "주소")
        val fetchVerbs = listOf("보여", "알려", "조회", "확인", "찾아")
        return detailNouns.any(text::contains) && fetchVerbs.any(text::contains)
    }

    /**
     * Renders any keyed memory collection: the key names what the statement is about, and the value
     * is the statement itself.
     *
     * Naming the key matters because it is what makes a restatement a replacement. The list arrives
     * already carrying one item per key — the store removes the superseded value on upsert — so this
     * renders it as it stands rather than deciding for itself which value is current. Reviving a
     * stale value from history or from the action ledger is exactly what it must not do.
     *
     * Order is the stored order, which is the same contract the neighbouring collections already
     * follow: superseded items are removed and the new one is appended, so the list reads
     * oldest-stated to most-recently-stated and is stable between requests.
     *
     * Only the key and the statement are emitted. A memory item also carries the turn it came from,
     * when it was updated and how confident the store is; none of that is a fact about the user, and
     * the neighbouring collections have never rendered their equivalents either.
     */
    private fun StringBuilder.appendKeyedItems(label: String, items: List<ConversationMemoryItem>) {
        if (items.isEmpty()) return
        append(label).append(":\n")
        items.forEach { item ->
            append("- ")
            item.key?.takeIf { it.isNotBlank() }?.let { append(it).append(": ") }
            append(item.content).append('\n')
        }
    }

    private companion object {
        const val SESSION_STATE = "session_state"
        const val RELEVANT_HISTORY = "relevant_history"
        const val RECENT_CONVERSATION = "recent_conversation"
        const val HISTORY_DIGEST = "history_digest"
        const val DIGEST_MESSAGE_CHARS = 160
        const val CLOSED_ACTION_LIMIT = 4
        const val MAX_TERMS = 12
        val TERM_REGEX = Regex("[\\p{IsHangul}A-Za-z0-9@._-]{2,}")
        val KOREAN_PARTICLES = listOf(
            "에게서", "한테서", "에게", "한테", "에서", "으로", "이랑", "라고", "께서",
            "은", "는", "이", "가", "을", "를", "의", "도", "와", "과", "로", "만",
        )
    }
}
