package com.example.hjp

import com.hjp.agent.contract.AgentEvent
import com.hjp.agent.core.AgentKernel
import com.hjp.agent.core.AgentRuntimeEnvironment
import com.hjp.agent.core.AgentSessionManager
import com.hjp.agent.core.AgentTurnEngine
import com.hjp.agent.core.AgentTurnPolicy
import com.hjp.agent.core.ContextBudget
import com.hjp.agent.core.ConversationHistoryStrategy
import com.hjp.agent.core.DefaultToolExecutor
import com.hjp.agent.core.DefaultToolObservationMapper
import com.hjp.agent.core.DefaultToolPolicyEngine
import com.hjp.agent.core.DefaultToolRegistry
import com.hjp.agent.core.InMemoryAgentSessionStore
import com.hjp.agent.core.ModelContextSelector
import com.hjp.agent.core.ToolImplementationCandidate
import com.hjp.tool.android.CalendarComposerBackend
import com.hjp.tool.android.CalendarDraft
import com.hjp.tool.android.CreateCalendarEventPlugin
import com.hjp.tool.android.MessageChannel
import com.hjp.tool.android.MessageComposerBackend
import com.hjp.tool.android.MessageDraft
import com.hjp.tool.android.OpenComposePlugin
import com.hjp.tool.contact.BusinessCardRecord
import com.hjp.tool.contact.BusinessCardUpdateResult
import com.hjp.tool.contact.ContactSearchBackend
import com.hjp.tool.contact.ContactSearchHit
import com.hjp.tool.contact.ContactSearchResponse
import com.hjp.tool.contact.GetContactPlugin
import com.hjp.tool.contact.MutableBusinessCardRepository
import com.hjp.tool.contact.SearchContactsPlugin
import com.hjp.tool.contact.UpdateBusinessCardPlugin
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.datetime.GetCurrentDateTimePlugin
import kotlinx.coroutines.flow.toList

/**
 * Drives the real production wiring in a unit test: the deterministic router as the model, the real
 * contact/compose/calendar plugins over in-memory backends, and the real kernel, policy engine and
 * context selector. Only the Android intent surface and the LiteRT engine are replaced.
 */
class MultiturnScenarioHarness(
    cards: List<BusinessCardRecord> = DEFAULT_CARDS,
    strategy: ConversationHistoryStrategy = ConversationHistoryStrategy.APP_CANONICAL_BOOTSTRAP,
    val searchFailure: Boolean = false,
    /** Whether the user approves the confirmation a card edit requires. */
    val confirmUpdates: Boolean = true,
    /**
     * The instant this run pretends it is at.
     *
     * Defaults to the system clock, so every existing caller behaves exactly as before. A frozen
     * replay passes the reference instant its dataset declares, which is what makes a dataset with
     * relative dates replayable on a day other than the one it was written on.
     */
    val clock: com.example.hjp.eval.clock.EvaluationClock =
        com.example.hjp.eval.clock.EvaluationClock.system(),
    /**
     * A stand-in for the model boundary.
     *
     * Defaults to the production local gateway, so every existing caller is unchanged. A test that
     * needs to reproduce what the real model does — ending a turn in prose while the workflow still
     * owes a tool — scripts the decisions here and still exercises the production kernel, workflow
     * validator, registry and executor underneath.
     */
    private val modelOverride: com.hjp.agent.contract.AgentModelGateway? = null,
    /**
     * The clock the *session* reads, separately from [clock].
     *
     * [clock] pins what "today" is for the datetime tool. This one drives memory timestamps, so a
     * test can move time forward between turns without sleeping and without touching the wall clock.
     * Defaults to the same instant source, so every existing caller is unchanged.
     */
    private val sessionClockMillis: (() -> Long)? = null,
    /**
     * The contact search backend the tools run against.
     *
     * Defaults to [RecordingBackend], so every existing caller is unchanged. The Ryeong
     * compatibility run passes the production `RyeongContactSearchBackend` instead, because a
     * retrieval metric measured against a stand-in ranker measures the stand-in.
     */
    private val searchBackendFactory: ((RecordingRepository) -> ContactSearchBackend)? = null,
    /**
     * Where this harness's runtime records what it did.
     *
     * Defaults to a private instance, so every existing caller is unchanged. An evaluation run
     * passes one object for the whole run, because "did an actual model produce these answers?" is
     * a question about the run, not about one scenario — and each scenario builds its own harness.
     */
    val runtimeCounters: com.hjp.agent.contract.AgentRuntimeCounters =
        com.hjp.agent.contract.AgentRuntimeCounters(),
) {
    val repository = RecordingRepository(cards)

    /**
     * The backend the tools see, with the call trace layered on top of it.
     *
     * The trace lives in the decorator rather than in [RecordingBackend] so that a real backend
     * can be swapped underneath without losing `search:` / `get:` observation. The decorator
     * forwards every call untouched and never reorders or filters a result.
     */
    val backend = TracingContactBackend(
        searchBackendFactory?.invoke(repository) ?: RecordingBackend(repository, searchFailure),
    )
    val calendar = RecordingCalendarBackend()
    val messages = RecordingMessageBackend()

    /** The production name index, over the same repository the tools read. */
    val directory = RepositoryContactDirectory(repository)

    private val plugins = listOf(
        SearchContactsPlugin(backend),
        GetContactPlugin(backend),
        UpdateBusinessCardPlugin(repository),
        CreateCalendarEventPlugin(calendar),
        OpenComposePlugin(messages),
        // The one place the agent can learn the time, so pinning it here pins every relative date.
        GetCurrentDateTimePlugin(clock.millis),
    )
    private val registry = DefaultToolRegistry(plugins.map { ToolImplementationCandidate(it) })

    /** Full tool trace: the contact backend alone cannot see calendar, compose or datetime calls. */
    val executedTools = mutableListOf<String>()

    /** Arguments of every call the kernel actually dispatched, for argument-level assertions. */
    val toolArguments = mutableListOf<Pair<String, kotlinx.serialization.json.JsonObject>>()
    private val recordingExecutor = object : com.hjp.agent.core.ToolExecutor {
        private val delegate = DefaultToolExecutor(registry)

        override suspend fun execute(
            call: com.hjp.agent.contract.ModelToolCall,
            snapshot: com.hjp.tool.contract.ToolCatalogSnapshot,
            context: ToolExecutionContext,
        ): com.hjp.tool.contract.ToolExecutionResult {
            executedTools += call.modelToolName
            toolArguments += call.modelToolName to call.arguments
            return delegate.execute(call, snapshot, context)
        }
    }
    private val store = InMemoryAgentSessionStore()
    val nativeLedger = com.hjp.agent.core.NativeContextLedger()

    /** Accumulated native tokens at the exact moment a request is handed to the model. */
    // Counted, then prompt-recorded. The counting decorator forwards every call untouched, so the
    // prompts and decisions the harness observes are exactly what they were before.
    val gateway = PromptRecordingGateway(
        com.hjp.agent.contract.CountingAgentModelGateway(
            modelOverride ?: LocalToolRoutingModelGateway(), runtimeCounters,
        ),
    ) { nativeLedger.totalTokens() }
    private val sessions = AgentSessionManager(
        store, gateway, "system", "ko-KR",
        clockMillis = sessionClockMillis ?: clock.millis,
    )

    val kernel: AgentKernel = AgentKernel(
        registry = registry,
        toolExecutor = recordingExecutor,
        policyEngine = DefaultToolPolicyEngine(),
        sessionManager = sessions,
        observationMapper = DefaultToolObservationMapper(),
        environment = FakeEnvironment(confirmUpdates),
        turnPolicy = AgentTurnPolicy(maxToolCalls = 6, historyStrategy = strategy),
        contextSelector = ModelContextSelector(ContextBudget(maxPromptTokens = 3_072)),
        nativeLedger = nativeLedger,
        // The same store-backed name resolution production wires in AppContainer. Without it the
        // router would be asked to decide who a sentence is about with no store to ask, which is a
        // different agent from the one that ships.
        contactDirectory = directory,
        runtimeCounters = runtimeCounters,
    )

    val engine: AgentTurnEngine = kernel

    /** Model tool names the catalog exposes, for building a router context outside the kernel. */
    val toolNames = plugins.map { it.contract.modelName }.toSet()

    suspend fun turn(text: String): TurnRecord {
        backend.clearObservations()
        executedTools.clear()
        toolArguments.clear()
        val composedBefore = messages.drafts.size
        val calendarBefore = calendar.drafts.size
        // Classified with the production router against the state the kernel will see, so the
        // recorded act is the same decision the kernel routes on, not a second rule set. That
        // includes the store lookup: without it the observation would be made against a router that
        // knows of nobody, and would disagree with the kernel on every sentence naming a contact.
        val act = com.hjp.agent.core.DeterministicTurnRouter.act(
            store.getOrCreate().turnContext(
                text.trim(),
                toolNames,
                null,
                directory.resolve(
                    com.hjp.agent.core.ContactNameCandidates.candidates(text.trim()),
                ),
            ),
        )
        val events = engine.runTurn(text).toList()
        val answer = events.filterIsInstance<AgentEvent.FinalMessage>().lastOrNull()?.text
            ?: events.filterIsInstance<AgentEvent.UserError>().lastOrNull()?.messageKo
            ?: ""
        val session = store.getOrCreate()
        return TurnRecord(
            text = text,
            answer = answer,
            events = events,
            toolCalls = backend.calls.toList(),
            executedTools = executedTools.toList(),
            composed = messages.drafts.toList(),
            calendarDrafts = calendar.drafts.toList(),
            act = act,
            outcomeType = session.conversationMemory.actions.lastOrNull()?.outcomeType,
            memory = session.conversationMemory,
            newComposeDrafts = messages.drafts.drop(composedBefore),
            newCalendarDrafts = calendar.drafts.drop(calendarBefore),
            toolArguments = toolArguments.toList(),
            searchRankings = backend.rankings.toList(),
            retrievalModes = backend.modes.toList(),
        )
    }

    suspend fun reset(): Long = engine.resetSession()

    suspend fun session() = store.getOrCreate()

    fun close() = sessions.close()

    data class TurnRecord(
        val text: String,
        val answer: String,
        val events: List<AgentEvent>,
        val toolCalls: List<String>,
        val executedTools: List<String>,
        val composed: List<MessageDraft>,
        val calendarDrafts: List<CalendarDraft>,
        val act: com.hjp.agent.contract.DialogueAct = com.hjp.agent.contract.DialogueAct.OTHER,
        val outcomeType: com.hjp.agent.contract.TurnOutcomeType? = null,
        val memory: com.hjp.agent.contract.ConversationMemory = com.hjp.agent.contract.ConversationMemory(),
        val newComposeDrafts: List<MessageDraft> = emptyList(),
        val newCalendarDrafts: List<CalendarDraft> = emptyList(),
        val toolArguments: List<Pair<String, kotlinx.serialization.json.JsonObject>> = emptyList(),
        /** Ranked card IDs of every search this turn, exactly as the backend returned them. */
        val searchRankings: List<List<String>> = emptyList(),
        /** Retrieval mode of every search this turn (e.g. KEYWORD_ONLY, HYBRID). */
        val retrievalModes: List<String> = emptyList(),
    ) {
        val searched: Boolean get() = toolCalls.any { it.startsWith("search:") }
        val fetched: List<String>
            get() = toolCalls.filter { it.startsWith("get:") }.map { it.removePrefix("get:") }
        val isError: Boolean get() = events.any { it is AgentEvent.UserError }
    }

    /**
     * Records what was asked of the contact backend and hands the answer back unchanged.
     *
     * Read-only: it observes the query and the returned ranking. It does not re-rank, truncate,
     * substitute or inject anything, so a retrieval metric computed downstream measures the
     * backend underneath and not this class.
     */
    class TracingContactBackend(
        private val delegate: ContactSearchBackend,
    ) : ContactSearchBackend {
        val calls = mutableListOf<String>()

        /** Ranked card IDs of each search this turn, in the order the backend returned them. */
        val rankings = mutableListOf<List<String>>()

        /** Retrieval mode of each search, so a keyword-only run cannot be reported as semantic. */
        val modes = mutableListOf<String>()

        override suspend fun search(query: String, limit: Int): ContactSearchResponse {
            calls += "search:$query"
            val response = delegate.search(query, limit)
            rankings += response.hits.map { it.card.id }
            modes += response.mode
            return response
        }

        override suspend fun get(cardId: String): BusinessCardRecord? {
            calls += "get:$cardId"
            return delegate.get(cardId)
        }

        override fun engineName(): String = delegate.engineName()
        override fun configurationAvailable(): Boolean = delegate.configurationAvailable()

        fun clearObservations() {
            calls.clear()
            rankings.clear()
            modes.clear()
        }
    }

    class RecordingBackend(
        private val repository: RecordingRepository,
        private val failSearch: Boolean,
    ) : ContactSearchBackend {
        val calls = mutableListOf<String>()

        override suspend fun search(query: String, limit: Int): ContactSearchResponse {
            calls += "search:$query"
            if (failSearch) throw IllegalStateException("search backend unavailable")
            // Ranked like the real hybrid retriever: keep only the best-scoring cards, so
            // "박민수 영업팀장" narrows to one person while "박민수" keeps both namesakes.
            val terms = query.split(" ").map { it.trim() }.filter { it.length >= 2 }
            val scored = repository.cards
                .map { card -> card to terms.count { term -> card.matches(term) } }
                .filter { it.second > 0 }
            val best = scored.maxOfOrNull { it.second } ?: 0
            val hits = scored
                .filter { it.second == best }
                .mapIndexed { index, (card, score) ->
                    ContactSearchHit(card, score.toDouble(), "match", index + 1, listOf("keyword"))
                }
                .take(limit)
            return ContactSearchResponse(hits, "KEYWORD_ONLY", false, "", "fake", 1)
        }

        override suspend fun get(cardId: String): BusinessCardRecord? {
            calls += "get:$cardId"
            return repository.cards.firstOrNull { it.id == cardId }
        }

        override fun engineName(): String = "fake"
        override fun configurationAvailable(): Boolean = true

        private fun BusinessCardRecord.matches(term: String): Boolean {
            val needle = term.trim().trimEnd('의', '을', '를', '은', '는', '이', '가')
            if (needle.length < 2) return false
            return name.contains(needle) || company.contains(needle) ||
                title.contains(needle) || industry.contains(needle)
        }
    }

    class RecordingRepository(initial: List<BusinessCardRecord>) : MutableBusinessCardRepository {
        var cards: List<BusinessCardRecord> = initial
            private set

        override suspend fun loadAll(): List<BusinessCardRecord> = cards

        override suspend fun getById(cardId: String): BusinessCardRecord? =
            cards.firstOrNull { it.id == cardId }

        override suspend fun update(
            cardId: String,
            updates: Map<String, String>,
            clearFields: Set<String>,
            updatedAt: String,
        ): BusinessCardUpdateResult? {
            val before = cards.firstOrNull { it.id == cardId } ?: return null
            val after = before.copy(
                memo = updates["memo"] ?: if ("memo" in clearFields) "" else before.memo,
                title = updates["title"] ?: before.title,
                company = updates["company"] ?: before.company,
                updatedAt = updatedAt,
            )
            cards = cards.map { if (it.id == cardId) after else it }
            return BusinessCardUpdateResult(before, after)
        }
    }

    /** Captures exactly what each strategy would send to the model, for token measurement. */
    class PromptRecordingGateway(
        private val delegate: com.hjp.agent.contract.AgentModelGateway,
        private val nativeTokensNow: () -> Int = { 0 },
    ) : com.hjp.agent.contract.AgentModelGateway {
        val prompts = mutableListOf<String>()
        val estimatedTokens = mutableListOf<Int>()

        /**
         * What the runtime is actually asked to prefill. The kernel appends the rendered request to
         * the ledger immediately before this call, so it is the whole native input for the request —
         * and, unlike a post-turn reading, it does not yet include the answer that the output
         * reserve is there to cover.
         */
        val nativeTokensAtSend = mutableListOf<Int>()
        var nativeResets = 0
            private set

        /**
         * How many times the model boundary was asked something, and what came back.
         *
         * Counted separately from [prompts] because a turn can call the boundary more than once: the
         * user's request, then once per tool result. A metric that says "every turn was generated"
         * has to be able to prove it against a number, and these are those numbers. None of them is
         * evidence that an actual model ran — the boundary here is deterministic.
         */
        var userRequests = 0
            private set
        var toolResultRequests = 0
            private set
        var decisionsToolCalls = 0
            private set
        var decisionsFinal = 0
            private set
        var decisionsInvalid = 0
            private set
        var boundaryFailures = 0
            private set

        private fun record(decision: com.hjp.agent.contract.ModelDecision) {
            when (decision) {
                is com.hjp.agent.contract.ModelDecision.ToolCalls -> decisionsToolCalls += 1
                is com.hjp.agent.contract.ModelDecision.FinalCandidate -> decisionsFinal += 1
                is com.hjp.agent.contract.ModelDecision.Invalid -> decisionsInvalid += 1
            }
        }

        override suspend fun openSession(
            config: com.hjp.agent.contract.ModelSessionConfig,
        ): com.hjp.agent.contract.AgentModelSession {
            val inner = delegate.openSession(config)
            return object : com.hjp.agent.contract.AgentModelSession by inner {
                override suspend fun decide(
                    input: com.hjp.agent.contract.ModelInput,
                ): com.hjp.agent.contract.ModelDecision {
                    if (input is com.hjp.agent.contract.ModelInput.User) {
                        prompts += input.promptContext.render(input.text)
                        estimatedTokens += input.promptContext.estimatedTokens
                        nativeTokensAtSend += nativeTokensNow()
                        userRequests += 1
                    }
                    return try {
                        inner.decide(input).also(::record)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        boundaryFailures += 1
                        throw error
                    }
                }

                override suspend fun continueWithToolResult(
                    result: com.hjp.agent.contract.ModelToolResponse,
                ): com.hjp.agent.contract.ModelDecision {
                    toolResultRequests += 1
                    return try {
                        inner.continueWithToolResult(result).also(::record)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        boundaryFailures += 1
                        throw error
                    }
                }

                override suspend fun resetConversation() {
                    nativeResets += 1
                    inner.resetConversation()
                }
            }
        }
    }

    class RecordingCalendarBackend : CalendarComposerBackend {
        val drafts = mutableListOf<CalendarDraft>()
        override fun isAvailable(): Boolean = true
        override suspend fun open(draft: CalendarDraft): Boolean {
            drafts += draft
            return true
        }
    }

    class RecordingMessageBackend : MessageComposerBackend {
        val drafts = mutableListOf<MessageDraft>()
        override fun isAvailable(channel: MessageChannel?): Boolean = true
        override suspend fun open(draft: MessageDraft): Boolean {
            drafts += draft
            return true
        }
    }

    private class FakeEnvironment(private val confirm: Boolean) : AgentRuntimeEnvironment {
        override val localeTag = "ko-KR"
        override val timeZoneId = "Asia/Seoul"
        override suspend fun grantedPermissions(): Set<String> = emptySet()
        override suspend fun deviceCapabilities(): Set<String> = setOf(
            "android.external_ui", "contact.local_search", "contact.local_update", "datetime.current",
        )

        override suspend fun toolContext(sessionId: String, turnId: String) =
            ToolExecutionContext(
                sessionId, turnId, localeTag, timeZoneId,
                confirmationGateway = com.hjp.tool.contract.ConfirmationGateway { confirm },
            )
    }

    companion object {
        val JIWON = BusinessCardRecord(
            id = "C001", name = "김지원", company = "비전글로벌", title = "대표이사",
            industry = "IT", email = "jiwon@example.com", mobile = "010-1111-0001",
        )
        val MINSU = BusinessCardRecord(
            id = "C002", name = "박민수", company = "한빛물산", title = "영업팀장",
            industry = "유통", email = "minsu@example.com", mobile = "010-1111-0002",
        )
        val MINSU_TWIN = BusinessCardRecord(
            id = "C003", name = "박민수", company = "그린테크", title = "연구원",
            industry = "IT", email = "minsu2@example.com", mobile = "010-1111-0003",
        )
        val NO_EMAIL = BusinessCardRecord(
            id = "C004", name = "최영희", company = "코어에이아이", title = "디자이너",
            industry = "IT", mobile = "010-1111-0004",
        )
        val DEFAULT_CARDS = listOf(JIWON, MINSU, MINSU_TWIN, NO_EMAIL)
    }
}
