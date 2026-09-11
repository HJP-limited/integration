package com.hjp.agent.contract

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import com.hjp.tool.contract.ToolCatalogSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

data class SamplingProfile(
    val temperature: Float = 0.2f,
    val topK: Int = 20,
    val topP: Double = 0.95,
    val maxOutputTokens: Int = 1_024,
)

data class ModelSessionConfig(
    val systemInstruction: String,
    val toolCatalog: ToolCatalogSnapshot,
    val localeTag: String,
    val samplingProfile: SamplingProfile = SamplingProfile(),
)

/** One labelled block of the model prompt, rendered as `[name]` followed by [body]. */
data class ModelPromptSection(val name: String, val body: String)

/**
 * Prompt context assembled by the agent core, already ordered and token-bounded.
 *
 * The gateway does not decide what history to include; it only renders what it is handed. That
 * keeps every context policy in pure JVM-testable code and lets the same fixture be measured
 * against the real tokenizer.
 */
data class ModelPromptContext(
    val sections: List<ModelPromptSection> = emptyList(),
    val startNewNativeConversation: Boolean = false,
    val estimatedTokens: Int = 0,
) {
    val isEmpty: Boolean get() = sections.isEmpty()

    /**
     * Renders the full user-visible request. With no sections the result is exactly
     * [currentUserText], so a first turn stays byte-identical to a single-turn prompt.
     */
    fun render(currentUserText: String): String {
        if (sections.isEmpty()) return currentUserText
        return buildString {
            sections.forEach { section ->
                append('[').append(section.name).append("]\n")
                append(section.body.trimEnd()).append("\n\n")
            }
            append("[current_user]\n").append(currentUserText)
        }
    }
}

/** One entry of the complete, never-truncated session transcript. */
data class TranscriptEntry(
    val turnId: String,
    val role: ModelConversationRole,
    val text: String,
    val epochMillis: Long,
    val status: TrackedActionStatus? = null,
)

/**
 * A completed exchange: one user request and the answer it produced.
 *
 * Context selection works on these, never on loose messages. Selecting messages individually let an
 * assistant answer be separated from its request or reordered when several entries shared a
 * millisecond, which put a stale answer under a different question.
 */
data class ConversationTurn(
    val turnId: String,
    /** Position in the session transcript. Append order, not a clock, so ties are impossible. */
    val sequence: Int,
    val userText: String,
    val assistantText: String?,
    val status: TrackedActionStatus? = null,
) {
    fun matches(term: String): Boolean =
        userText.contains(term, ignoreCase = true) ||
            assistantText?.contains(term, ignoreCase = true) == true
}

object ConversationTurns {
    /**
     * Groups a transcript into turns in append order. An assistant entry without a preceding user
     * entry in the same turn is dropped rather than emitted on its own.
     */
    fun from(transcript: List<TranscriptEntry>): List<ConversationTurn> {
        val byTurn = LinkedHashMap<String, MutableList<TranscriptEntry>>()
        transcript.forEach { entry -> byTurn.getOrPut(entry.turnId) { mutableListOf() } += entry }
        return byTurn.entries.mapIndexedNotNull { index, (turnId, entries) ->
            val user = entries.firstOrNull { it.role == ModelConversationRole.USER } ?: return@mapIndexedNotNull null
            val assistant = entries.lastOrNull { it.role == ModelConversationRole.ASSISTANT }
            ConversationTurn(
                turnId = turnId,
                sequence = index,
                userText = user.text,
                assistantText = assistant?.text,
                status = assistant?.status,
            )
        }
    }
}

data class ModelConversationMessage(
    val role: ModelConversationRole,
    val text: String,
)

enum class ModelConversationRole { USER, ASSISTANT }

/**
 * A span of this utterance that is exactly the name of somebody in the contact store.
 *
 * The router cannot answer "is this a person?" from the sentence alone. 하도, 우리은 and 설태을 look
 * like ordinary words ending in a particle; 고운말 is an everyday noun; 마이클 첸 is two tokens. What
 * separates a person from a word is not their spelling but whether the store holds a card with that
 * exact name — a question only the store can answer. This is that answer, carried as typed state so
 * the router stays a pure function of its input and never reaches for a repository itself.
 *
 * [span] is what the user actually typed, preserved verbatim so a lookup can search for it as
 * written. [name] is the store's own spelling of the match. They differ when the span carried a
 * particle the store's name does not.
 */
data class DirectoryNameMatch(
    /** The substring of the utterance, exactly as the user wrote it. */
    val span: String,
    /** The contact name in the store that [span] matched exactly. */
    val name: String,
    /** Every card filed under [name]. More than one means the name alone does not identify a person. */
    val cardIds: List<String>,
    /**
     * Whether the utterance marked this span as a person — 씨, 님, 에게, 한테, 께 — or put it in front
     * of one of their own card fields.
     *
     * It is what lets a name that is also an ordinary noun be either. "담당자 알려줘" asks about a
     * role; "고운말씨 부서 알려줘" asks about a person who happens to be called that.
     */
    val personMarked: Boolean,
    /**
     * Whether the store also uses this exact string as a company, a job title or a department.
     *
     * A string that is both is not evidence of a person on its own; without a person marker the
     * router leaves it to the ordinary rules rather than deciding that a job title is a colleague.
     */
    val alsoNonPersonVocabulary: Boolean,
) {
    /** Whether this match is strong enough to say the sentence is about a person. */
    val identifiesAPerson: Boolean get() = personMarked || !alsoNonPersonVocabulary
}

/**
 * Structured turn state for deterministic routers. LLM gateways ignore this and read
 * [ModelPromptContext] instead; a rule-based router needs the typed state, not prose.
 */
data class TurnContext(
    val userText: String,
    val memory: ConversationMemory = ConversationMemory(),
    val transcript: List<TranscriptEntry> = emptyList(),
    val availableTools: Set<String> = emptySet(),
    /** Set when the pre-router already grounded a reference on a tool-verified card. */
    val groundedCardId: String? = null,
    /**
     * People this utterance names, as resolved against the real contact store before routing.
     *
     * Empty by default, which is what every caller that has no store gets, and what the router
     * treats as "this sentence names nobody I know".
     */
    val directoryMatches: List<DirectoryNameMatch> = emptyList(),
)

sealed interface ModelInput {
    data class User(
        val text: String,
        val safeCapabilityContext: JsonObject? = null,
        val promptContext: ModelPromptContext = ModelPromptContext(),
        val turnContext: TurnContext = TurnContext(text),
    ) : ModelInput
}

data class ModelToolCall(
    val callId: String,
    val modelToolName: String,
    val arguments: JsonObject,
)

sealed interface ModelDecision {
    data class ToolCalls(val calls: List<ModelToolCall>) : ModelDecision
    /**
     * A finished answer.
     *
     * [clarification] is set when the boundary produced this text because a *required slot was
     * missing* rather than because it had an answer. Without it the two are the same value, and a
     * turn that stopped to ask "when should I schedule it?" is typed as general information —
     * indistinguishable, to any metric, from a turn that quietly did nothing.
     */
    data class FinalCandidate(
        val draftText: String,
        val clarification: ClarifyReason? = null,
    ) : ModelDecision
    data class Invalid(val safeReason: String, val retryable: Boolean) : ModelDecision
}

/**
 * An internal instruction to the model about workflow state.
 *
 * Carries the tool call it follows only so a boundary without a separate channel can still deliver it
 * the old way; boundaries with real message roles ignore that and send [text] in its own message.
 */
data class ModelWorkflowNote(
    val text: String,
    val pendingTool: String,
    val afterCallId: String,
    val afterToolName: String,
) {
    fun asToolResponse(): ModelToolResponse = ModelToolResponse(
        callId = afterCallId,
        modelToolName = afterToolName,
        payload = buildJsonObject {
            put("status", JsonPrimitive("workflow_incomplete"))
            put("message", JsonPrimitive(text))
            put("pending_tool", JsonPrimitive(pendingTool))
        },
    )
}

data class ModelToolResponse(
    val callId: String,
    val modelToolName: String,
    val payload: JsonObject,
)

data class FinalAnswerInput(
    val draftText: String,
    val safeObservations: List<ModelToolResponse> = emptyList(),
)

/**
 * Multiturn context handed to model boundaries that do not own a native conversation, such as the
 * structured intent stages. An empty context must render to an empty prompt fragment.
 */
data class ConversationContext(
    val promptContext: ModelPromptContext = ModelPromptContext(),
) {
    val isEmpty: Boolean get() = promptContext.isEmpty

    fun promptPrefix(): String =
        if (promptContext.isEmpty) "" else promptContext.render("").removeSuffix("[current_user]\n")
}

/**
 * What kind of thing is behind a model boundary.
 *
 * The distinction exists because "the boundary answered" and "a language model answered" are not the
 * same fact, and every report this project has produced so far conflated them. The deterministic
 * local gateway returns a decision for every turn; it is a rule set, and counting its replies as
 * model invocations is how a run with no model in it came to be described as one where the model
 * generated every response.
 *
 * Declared by the gateway rather than inferred by the counter: only the gateway knows whether there
 * is an artefact underneath it.
 */
enum class ModelBoundaryKind {
    /** A rule-based stand-in. It produces decisions and is not a language model. */
    DETERMINISTIC,

    /** A real model artefact, executing on this machine. */
    ACTUAL_MODEL,
}

interface AgentModelGateway : AutoCloseable {
    /** Defaults to [ModelBoundaryKind.DETERMINISTIC]: claiming a model requires saying so. */
    val boundaryKind: ModelBoundaryKind get() = ModelBoundaryKind.DETERMINISTIC

    suspend fun openSession(config: ModelSessionConfig): AgentModelSession
    override fun close() = Unit
}

interface AgentModelSession : AutoCloseable {
    val catalogRevision: String
    suspend fun decide(input: ModelInput): ModelDecision
    suspend fun continueWithToolResult(result: ModelToolResponse): ModelDecision
    fun streamFinal(input: FinalAnswerInput): Flow<String>

    /**
     * Tells the model the workflow is unfinished, without pretending to be a tool result.
     *
     * A repair after the model ended in prose has no tool call to answer. Sending it as a tool
     * response anyway means emitting a tool-role message for a call the conversation already
     * completed, which the native protocol does not define an ordering for — a boundary is free to
     * reject it or to mis-attribute it. Boundaries that keep no native transcript can treat this as
     * an ordinary continuation; [com.hjp.agent.litert] overrides it to use a role the SDK supports.
     *
     * The note is internal. It is never written to the user transcript or to persistent memory as
     * something the user said.
     */
    suspend fun continueWithWorkflowNote(note: ModelWorkflowNote): ModelDecision =
        continueWithToolResult(note.asToolResponse())

    /** Drops any native conversation state so the next [decide] starts from a clean history. */
    suspend fun resetConversation() = Unit

}
