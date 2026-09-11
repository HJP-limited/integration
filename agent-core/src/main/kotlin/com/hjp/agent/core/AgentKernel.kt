package com.hjp.agent.core

import com.hjp.agent.contract.requestsAgentAction
import com.hjp.agent.contract.AgentEvent
import com.hjp.agent.contract.FinalAnswerInput
import com.hjp.agent.contract.ContactCandidate
import com.hjp.agent.contract.ModelDecision
import com.hjp.agent.contract.ModelInput
import com.hjp.agent.contract.ModelToolCall
import com.hjp.agent.contract.ModelToolResponse
import com.hjp.agent.contract.ModelWorkflowNote
import com.hjp.agent.contract.TurnOutcomeType
import com.hjp.agent.contract.TurnRoutePlan
import com.hjp.tool.contract.CatalogContext
import com.hjp.tool.contract.StandardToolErrorCodes
import com.hjp.tool.contract.ToolContract
import com.hjp.tool.contract.ToolError
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolExecutionResult
import com.hjp.tool.contract.ToolRegistry
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class AgentTurnPolicy(
    val maxToolCalls: Int = 3,
    val maxProtocolCorrections: Int = 1,
    val rejectMultipleCallsPerDecision: Boolean = true,
    val rejectRepeatedCall: Boolean = true,
    /**
     * How many times a turn may ask the model to finish a workflow it ended in prose.
     *
     * Bounded on purpose: a model that narrates instead of calling the terminal tool usually does it
     * again, and an unbounded retry turns one confused turn into a loop. One repair is enough to
     * recover the common case; after that the turn ends as unfinished rather than pretending.
     */
    val maxWorkflowRepairs: Int = 1,
    val historyStrategy: ConversationHistoryStrategy = ConversationHistoryStrategy.APP_CANONICAL_BOOTSTRAP,
)

interface AgentRuntimeEnvironment {
    val localeTag: String
    val timeZoneId: String
    suspend fun grantedPermissions(): Set<String>
    suspend fun deviceCapabilities(): Set<String>
    suspend fun toolContext(sessionId: String, turnId: String): ToolExecutionContext
}

class AgentKernel(
    private val registry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val policyEngine: ToolPolicyEngine,
    private val sessionManager: AgentSessionManager,
    private val observationMapper: ToolObservationMapper,
    private val environment: AgentRuntimeEnvironment,
    private val turnPolicy: AgentTurnPolicy = AgentTurnPolicy(),
    private val workflowPolicy: AgentWorkflowPolicy = ProductionAgentWorkflowPolicy(),
    private val contextSelector: ModelContextSelector = ModelContextSelector(),
    /**
     * Verifies that the fixed prompt cost fits the deployed artifact's budget before any inference.
     * Without it an over-budget catalog is discovered only as garbled native output.
     */
    private val contextPreflight: (com.hjp.tool.contract.ToolCatalogSnapshot) -> ContextPreflightResult =
        { ContextPreflightResult.Ok(Int.MAX_VALUE) },
    /**
     * Mirrors what the native conversation accumulates. Rotation decisions use the whole native
     * input, not just the block being sent now.
     */
    val nativeLedger: NativeContextLedger = NativeContextLedger(),
    /** Debug-only, non-PII record of the last turn. Never surfaced in the release UI. */
    val diagnostics: AgentDiagnosticsRecorder = AgentDiagnosticsRecorder(),
    /**
     * Which spans of an utterance are the name of somebody in the contact store.
     *
     * Defaults to [ContactDirectory.None], so a kernel built without a store behaves exactly as it
     * did before: no sentence names anybody, and every existing rule decides the turn.
     */
    private val contactDirectory: ContactDirectory = ContactDirectory.None,
    /** Production default: a resolved explicit detail read owns its validated display lookup. */
    private val resolvedDetailOwnership: Boolean = true,
    /**
     * Where this kernel records what it actually did.
     *
     * Counts only — see [com.hjp.agent.contract.AgentRuntimeCounters]. Defaults to a private
     * instance, so a kernel built without one behaves exactly as before and nothing has to be
     * threaded through the callers that do not care. A runner that has to report whether an actual
     * model ran passes its own and reads it afterwards, instead of asserting the answer.
     */
    val runtimeCounters: com.hjp.agent.contract.AgentRuntimeCounters =
        com.hjp.agent.contract.AgentRuntimeCounters(),
) : AgentTurnEngine {
    override val mode = AgentKernelMode.REACT

    private val turnMutex = Mutex()
    private var nativeConversationTurns = 0
    private var nativeConversationTokens = 0

    override suspend fun resetSession(): Long {
        nativeConversationTurns = 0
        nativeConversationTokens = 0
        nativeLedger.clear()
        diagnostics.clear()
        return sessionManager.reset()
    }

    override fun close() = sessionManager.close()

    /**
     * The contacts this sentence names, or none if the store could not be reached.
     *
     * A store that is unavailable is a reason to fall back to the rules that never needed it, not to
     * fail the user's turn — so a failure here is swallowed and the turn proceeds with no matches.
     */
    private suspend fun resolveDirectory(
        userText: String,
    ): List<com.hjp.agent.contract.DirectoryNameMatch> = try {
        contactDirectory.resolve(ContactNameCandidates.candidates(userText))
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        emptyList()
    }

    override fun runTurn(userText: String): Flow<AgentEvent> = flow {
        val normalized = userText.trim()
        if (normalized.isEmpty()) {
            emit(AgentEvent.UserError("메시지를 입력해 주세요."))
            return@flow
        }
        turnMutex.withLock {
            val turnId = UUID.randomUUID().toString()
            runtimeCounters.recordTurnStarted()
            emit(AgentEvent.TurnStarted(turnId))
            val session = sessionManager.getOrCreate()
            val generation = session.generation
            val permissions = environment.grantedPermissions()
            val snapshot = registry.snapshot(CatalogContext(
                session.sessionId,
                environment.localeTag,
                permissions,
                environment.deviceCapabilities(),
            ))
            // Which spans of this sentence name somebody the store actually holds. Resolved once,
            // before routing, and handed to the router as typed state: whether 하도 is a person or a
            // province is a question only the store can answer, and the router must stay pure.
            val directoryMatches = resolveDirectory(normalized)
            fun turnContext(text: String = normalized, groundedCardId: String? = null) =
                session.turnContext(
                    text, snapshot.contractsByModelName.keys, groundedCardId, directoryMatches,
                )

            // Classified before the turn is recorded, by the same router that routes it, so the
            // action ledger holds requests rather than every sentence the user typed. The catalog is
            // needed first because the classification is made against the tools actually available.
            val dialogueAct = DeterministicTurnRouter.act(turnContext())
            val requestsAction = dialogueAct
                .requestsAgentAction &&
                // A sentence that quotes, recalls, negates or hypothesises about an action is about
                // an action rather than asking for one. The same rule already vetoes the capability
                // check; reusing it keeps one authority instead of two.
                !ReportedSpeechIntent.isAboutAnActionRatherThanARequest(normalized)
            sessionManager.beginTurn(turnId, normalized, requestsAction)

            // Rule-based pre-pass. It runs before the model so an ungrounded reference can never
            // become a guessed recipient, and so a question about the conversation never becomes a
            // contact search.
            val route = DeterministicTurnRouter.route(turnContext())
            // Recorded here rather than after the `when` below, because four of the branches return
            // without ever reaching the later call. A turn that ended deterministically used to
            // leave the previous turn's record in place, so anything reading the diagnostics saw a
            // decision belonging to a different turn. The richer calls further down overwrite this.
            recordDiagnostics(turnId, generation, route, dialogueAct, emptyList(), emptyList(), 0, null, false)
            // Four of the branches below finish the turn without ever reaching the model, and so
            // does the contact-detail path further down. Counted as deterministic-only turns so a
            // report can state how much of a run the model was actually responsible for, instead of
            // implying it produced every answer.
            when (route) {
                is TurnRoutePlan.AnswerFromHistory -> {
                    runtimeCounters.recordDeterministicOnlyTurn()
                    finishWithoutTools(
                        route.messageKo, turnId, normalized, TurnOutcome.COMPLETED, generation,
                        ::emit, outcomeType = TurnOutcomeType.ANSWER_FROM_HISTORY,
                    )
                    return@withLock
                }
                is TurnRoutePlan.GeneralInformation -> {
                    runtimeCounters.recordDeterministicOnlyTurn()
                    finishWithoutTools(
                        route.messageKo, turnId, normalized, TurnOutcome.COMPLETED, generation,
                        ::emit, outcomeType = TurnOutcomeType.GENERAL_INFORMATION,
                    )
                    return@withLock
                }
                is TurnRoutePlan.Clarify -> {
                    runtimeCounters.recordDeterministicOnlyTurn()
                    finishWithoutTools(
                        route.questionKo, turnId, normalized, TurnOutcome.NEEDS_CLARIFICATION,
                        generation, ::emit, detailKo = route.reason.name,
                        outcomeType = TurnOutcomeType.CLARIFICATION_REQUIRED,
                    )
                    return@withLock
                }
                is TurnRoutePlan.Unsupported -> {
                    runtimeCounters.recordDeterministicOnlyTurn()
                    finishWithoutTools(
                        route.messageKo, turnId, normalized, TurnOutcome.COMPLETED, generation,
                        ::emit, outcomeType = TurnOutcomeType.UNSUPPORTED,
                    )
                    return@withLock
                }
                else -> Unit
            }
            // A correction retires the old target before anything else in the turn runs, so neither
            // the trusted-provenance seed below nor a later reference can still reach that person.
            if (route is TurnRoutePlan.CorrectionReplacement && isCurrent(generation)) {
                sessionManager.rejectContactsNamed(route.rejectedTerm)
                // A correction without a resolvable replacement is still a hard boundary for the
                // old actionable focus. Keep transcript/mentions for historical recall, but
                // remove the selected contact before rendering this turn's model context.
                sessionManager.retireActionableFocus()
            }
            // Some correction utterances (for example a rejection without a replacement name)
            // are classified as CORRECTION but remain a Continue plan. They still retire the old
            // actionable focus; only a grounded replacement may carry a target forward.
            if (dialogueAct == com.hjp.agent.contract.DialogueAct.CORRECTION &&
                route !is TurnRoutePlan.GroundedContact &&
                route !is TurnRoutePlan.CorrectionReplacement &&
                isCurrent(generation)
            ) {
                sessionManager.retireActionableFocus()
            }
            // A grounded correction identifies a new current target but does not pretend it has
            // already passed get_contact. Retire only the rejected selection; a successful fresh
            // read below will establish the replacement as selected_contact. If repair fails, a
            // later pronoun clarifies instead of falling back to the person the user rejected.
            if (route is TurnRoutePlan.GroundedContact && route.replacesPreviousTarget &&
                isCurrent(generation)
            ) {
                sessionManager.retireActionableFocus()
            }
            // Keep only the identity of an update target when the model answers the grounding
            // turn with prose.  This is not a contact verification: fields remain absent until
            // the ordinary purpose=display fresh read succeeds.
            if (route is TurnRoutePlan.GroundedContact &&
                CardUpdateIntent.hasUpdateVerb(normalized) &&
                !route.replacesPreviousTarget &&
                isCurrent(generation)
            ) {
                sessionManager.persistGroundedTarget(
                    ContactCandidate(route.cardId, route.name),
                )
            }
            val groundedCardId = (route as? TurnRoutePlan.GroundedContact)?.cardId
            val preservesCorrectionInput = normalized.contains("다시 찾아")
            val modelText = when (route) {
                is TurnRoutePlan.Continue -> if (preservesCorrectionInput) normalized else route.text
                // A replacement target may carry a deterministic synthetic search text for
                // routing, but the model must see the user's correction verbatim. Otherwise the
                // old focus can be rendered as the current request and the new name is lost.
                // Grounding identifies the card, not a replacement user request.  The synthetic
                // router text used to discard a same-turn compose/calendar suffix ("찾아서 메일
                // 초안까지 열어줘"), leaving Gemma able to read the new card but unaware of the
                // requested downstream action.
                is TurnRoutePlan.GroundedContact -> normalized
                // Preserve the literal correction utterance at the model boundary. The router
                // may carry a synthetic search rewrite, but replacing it here can resurrect the
                // previous focus and hide the newly named person from Gemma.
                is TurnRoutePlan.CorrectionReplacement -> normalized
                else -> normalized
            }

            // "회사가 어디야?" about an already-verified person is a read, not a plan. Answering it
            // from a fresh card read keeps the value grounded and current, and makes the behaviour
            // identical whatever model is deployed. No model inference and no side effect run here.
            if (route is TurnRoutePlan.ContactDetail) {
                runtimeCounters.recordDeterministicOnlyTurn()
                answerContactDetail(route, snapshot, session, turnId, normalized, generation, ::emit)
                return@withLock
            }

            when (val preflight = contextPreflight(snapshot)) {
                is ContextPreflightResult.Failure -> {
                    runtimeCounters.recordContextPreflightRejection()
                    runtimeCounters.recordDeterministicOnlyTurn()
                    failTurn(preflight.reasonKo, turnId, normalized, generation, ::emit)
                    return@withLock
                }
                is ContextPreflightResult.Ok -> Unit
            }

            val model = try {
                sessionManager.requireModelSession(snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                failTurn("온디바이스 모델을 시작하지 못했습니다.", turnId, normalized, generation, ::emit)
                return@withLock
            }
            val toolContext = environment.toolContext(session.sessionId, turnId)
            val lease = TurnLease(generation) { sessionManager.getOrCreate().generation }
            val sideEffects = SideEffectGuard()
            val observations = mutableListOf<ModelToolResponse>()
            val executedTools = mutableListOf<String>()
            val fingerprints = mutableSetOf<String>()
            var callCount = 0
            var protocolCorrections = 0
            val workflow = workflowPolicy.startTurn(modelText, environment.timeZoneId)
            // The router is the authority on whether this turn is a contact search.  Preserve that
            // typed result for the per-turn workflow, which otherwise cannot distinguish a compact
            // person search ("우성씨 찾아줘") from an arbitrary model-initiated search by looking
            // only for literal card-object words.
            if (dialogueAct == com.hjp.agent.contract.DialogueAct.CONTACT_SEARCH ||
                route is TurnRoutePlan.CorrectionReplacement ||
                (dialogueAct == com.hjp.agent.contract.DialogueAct.CORRECTION &&
                    route is TurnRoutePlan.Continue && route.text.contains("명함 찾아줘"))
            ) {
                workflow.seedContactSearchIntent()
            }
            // A previous ambiguous search remains an unresolved contact obligation until the
            // user chooses a candidate. Carry that typed fact into contact-bound turns so the
            // model cannot start a calendar/compose/update prerequisite against nobody (or an old
            // focus). Standalone non-contact calendar requests are unaffected by this flag.
            if (session.conversationMemory.selectedContact == null &&
                session.conversationMemory.candidateContacts.size > 1
            ) {
                workflow.seedUnresolvedContactObligation(
                    session.conversationMemory.candidateContacts.size,
                )
            }
            // Session focus is not the same thing as this turn's target. A card the session has
            // verified stays in memory so a later "그 사람에게 메일 써줘" still works, but it becomes
            // *this* turn's contact only when this turn actually refers to somebody — see
            // [TurnContactTargetResolver] for the three kinds of evidence that count. Seeding the
            // provenance unconditionally made every later request contact-bound, so an ordinary
            // "2027년 2월 18일 오후 3시 교육 협의 일정 만들어줘" was validated as an event for whoever
            // had last been looked up and died asking for a contact lookup nobody had requested.
            val routedTarget = TurnContactTargetResolver.resolve(
                route, normalized, session.conversationMemory,
            )
            // Update follow-ups may omit the name after a grounded update turn.  Reuse the
            // persisted identity only for that update request; it still requires a fresh read.
            val turnTarget = if (routedTarget is TurnContactTargetResolver.Target.None &&
                CardUpdateIntent.hasUpdateVerb(normalized)
            ) {
                session.conversationMemory.groundedTargetIdentity?.let {
                    TurnContactTargetResolver.Target.Confirmed(
                        it.cardId, TurnContactTargetResolver.Evidence.ROUTER_GROUNDED,
                    )
                } ?: routedTarget
            } else routedTarget
            val currentTarget = (turnTarget as? TurnContactTargetResolver.Target.Confirmed)?.let { target ->
                val name = when (route) {
                    is TurnRoutePlan.GroundedContact -> route.name
                    else -> sequenceOf(
                        session.conversationMemory.selectedContact
                            ?.takeIf { it.cardId == target.cardId }?.name,
                        session.conversationMemory.candidateContacts
                            .firstOrNull { it.cardId == target.cardId }?.name,
                        session.conversationMemory.activeMentions
                            .firstOrNull { it.cardId == target.cardId }?.name,
                    ).filterNotNull().firstOrNull().orEmpty()
                }
                CurrentContactTarget(
                    cardId = target.cardId,
                    name = name,
                    evidence = target.evidence,
                    requiresFreshRead = (route as? TurnRoutePlan.GroundedContact)
                        ?.requiresFreshRead ?: true,
                    freshReadPurpose = workflow.requiredFreshReadPurpose(),
                )
            }
            // An explicitly resolved candidate (ordinal, reference, or named candidate) becomes
            // actionable only here.  The id must come from the persisted candidate list; search
            // results with multiple candidates are never promoted implicitly by the projector.
            if (route is TurnRoutePlan.GroundedContact &&
                session.conversationMemory.candidateContacts.size > 1 &&
                session.conversationMemory.candidateContacts.any { it.cardId == route.cardId } &&
                isCurrent(generation)
            ) {
                sessionManager.promoteCandidate(route.cardId)
            }
            // Fail closed. If this turn names somebody the session has not surfaced, the person
            // currently in focus stops being an actionable target the moment the turn begins — not
            // only if the lookup fails. Falling back to them is how a mail addressed to the previous
            // person got sent in the held-out v3 run.
            val pendingNewTarget = TurnContactTargetResolver.namesSomeoneOtherThanFocus(
                normalized, session.conversationMemory,
            )
            // A new grounded name is a replacement for this turn even when the user did not phrase
            // it as a correction.  Leaving the previous selected_contact in model context makes a
            // freshly named recipient compete with the old card's tool provenance.
            val groundedReplacement = (turnTarget as? TurnContactTargetResolver.Target.Confirmed)
                ?.cardId
                ?.let { it != session.conversationMemory.selectedContact?.cardId }
                ?: false
            val staleFocusCardId = session.conversationMemory.selectedContact
                ?.cardId
                ?.takeIf {
                    groundedReplacement ||
                        (pendingNewTarget && turnTarget is TurnContactTargetResolver.Target.None)
                }
            (turnTarget as? TurnContactTargetResolver.Target.Confirmed)
                ?.let { workflow.seedTrustedContactProvenance(it.cardId) }
            // Retire it before the model runs, so no path in this turn or the next can reach it
            // without a fresh verification of whoever the user actually named.
            if (staleFocusCardId != null && isCurrent(generation)) {
                sessionManager.retireActionableFocus()
            }

            nativeLedger.setFixedContext(
                sessionManager.systemInstructionText,
                ContextPreflight.toolCatalogText(snapshot.contractsByModelName.values),
            )
            val promptContext = contextSelector.select(ContextRequest(
                transcript = session.transcript.toList(),
                memory = session.conversationMemory,
                currentInput = modelText,
                currentTurnId = turnId,
                    capabilityContext = buildCapabilityContext(
                        session,
                        currentTarget?.cardId,
                        suppressSelectedContact = dialogueAct == com.hjp.agent.contract.DialogueAct.CORRECTION &&
                            currentTarget == null,
                    ),
                strategy = turnPolicy.historyStrategy,
                nativeConversationTurns = nativeConversationTurns,
                // Rotation is judged on the whole accumulated native input, not this block alone.
                nativeConversationTokens = nativeLedger.totalTokens(),
                // The real fixed cost, so a bootstrap is sized against the prompt that will actually
                // be sent rather than against an assumed catalog reserve.
                fixedContextTokens = nativeLedger.fixedTokens(),
                currentTarget = currentTarget,
                targetScopedHistory = groundedReplacement,
            ))
            if (promptContext.startNewNativeConversation) {
                model.resetConversation()
                nativeLedger.rotate()
                nativeConversationTurns = 0
                nativeConversationTokens = 0
            }
            nativeConversationTurns += 1
            nativeConversationTokens += promptContext.estimatedTokens
            nativeLedger.appendUser(promptContext.render(modelText))
            recordDiagnostics(
                turnId, generation, route, dialogueAct, emptyList(),
                promptContext.sections.map { it.name }, promptContext.estimatedTokens, null, false,
            )

            // Bounded repair state. `lastToolCallId` is the channel a continuation is sent on: a
            // repair only makes sense once a tool has actually run, which is exactly the case the
            // gap covers (a lookup succeeded and the terminal action never followed).
            var workflowRepairs = 0
            // A fresh-read workflow has two independently bounded transitions: rejected terminal
            // -> get_contact, then successful get_contact -> terminal. Counting both against one
            // slot let the first transition consume the only reminder and stranded the action in
            // prose after the read succeeded.
            var terminalContinuationRepairs = 0
            var workflowTransitionRejections = 0
            var lastToolCallId: String? = null
            var lastToolName: String? = null

            // From here the turn depends on the boundary, whatever the boundary turns out to be.
            // Whether an *actual model* answered is a separate question, counted separately, by
            // [com.hjp.agent.contract.CountingAgentModelGateway].
            runtimeCounters.recordModelBoundaryTurn()
            val ownedDetailCall = if (
                resolvedDetailOwnership &&
                isResolvedExplicitDetailFetch(
                    normalized = normalized,
                    dialogueAct = dialogueAct,
                    route = route,
                    currentTarget = currentTarget,
                    session = session,
                )
            ) {
                val contract = snapshot.contractsByModelName[AgentWorkflowSession.GET_CONTACT]
                contract?.let {
                    ModelToolCall(
                        callId = UUID.randomUUID().toString(),
                        modelToolName = AgentWorkflowSession.GET_CONTACT,
                        arguments = buildJsonObject {
                            put("card_id", currentTarget!!.cardId)
                            put("purpose", "display")
                        },
                    )
                }
            } else null
            var decision = ownedDetailCall?.let { ModelDecision.ToolCalls(listOf(it)) }
                ?: model.decide(ModelInput.User(
                    text = modelText,
                        safeCapabilityContext = buildCapabilityContext(
                            session,
                            currentTarget?.cardId,
                            suppressSelectedContact = dialogueAct == com.hjp.agent.contract.DialogueAct.CORRECTION &&
                                currentTarget == null,
                        ),
                    promptContext = promptContext,
                    turnContext = turnContext(modelText, groundedCardId),
                ))
            // A native inference cannot be interrupted, so the first thing to check when it returns
            // is whether the session it belongs to still exists.
            if (lease.isStale()) return@withLock

            while (true) {
                currentCoroutineContext().ensureActive()
                if (lease.isStale()) return@withLock
                when (val current = decision) {
                    is ModelDecision.FinalCandidate -> {
                        if (lease.isStale()) return@withLock
                        // The model ended in prose while the workflow still owes a terminal tool.
                        // Accepting this is how "search → get_contact → (prose)" was recorded as a
                        // completed request that never opened anything. Give it one bounded chance
                        // to call the tool, through the ordinary tool-result channel.
                        workflow.deterministicCalendarPrerequisiteCall()?.let { prerequisiteCall ->
                            decision = ModelDecision.ToolCalls(listOf(prerequisiteCall))
                            continue
                        }
                        workflow.deterministicComposePrerequisiteCall()?.let { prerequisiteCall ->
                            decision = ModelDecision.ToolCalls(listOf(prerequisiteCall))
                            continue
                        }
                        workflow.deterministicCalendarDatetimePrerequisiteCall()?.let { prerequisiteCall ->
                            decision = ModelDecision.ToolCalls(listOf(prerequisiteCall))
                            continue
                        }
                        val initialPrerequisiteTool = workflow.initialPrerequisiteTool()
                        if (initialPrerequisiteTool != null &&
                            workflowRepairs < turnPolicy.maxWorkflowRepairs &&
                            callCount < turnPolicy.maxToolCalls
                        ) {
                            workflowRepairs += 1
                            runtimeCounters.recordRepairInvocation()
                            decision = model.continueWithWorkflowNote(
                                workflow.initialPrerequisitePrompt(initialPrerequisiteTool),
                            )
                            continue
                        }
                        // Once the calendar read, clock read and all terminal arguments are typed
                        // and verified, terminal execution no longer needs a second generative
                        // decision. This remains a normal validated tool call below, including the
                        // side-effect guard and confirmation policy; it merely cannot be stranded
                        // by prose or a malformed re-generated calendar call.
                        workflow.deterministicCalendarTerminalCall()?.let { terminalCall ->
                            decision = ModelDecision.ToolCalls(listOf(terminalCall))
                            continue
                        }
                        val rejectionRepairTool = workflow.pendingRejectionRepairTool()
                        val pendingTool = rejectionRepairTool ?: workflow.pendingTerminalTool()
                        val availableRepairBudget = if (rejectionRepairTool != null) {
                            workflowRepairs < turnPolicy.maxWorkflowRepairs
                        } else {
                            terminalContinuationRepairs < turnPolicy.maxWorkflowRepairs
                        }
                        if (pendingTool != null &&
                            availableRepairBudget &&
                            callCount < turnPolicy.maxToolCalls &&
                            lastToolCallId != null
                        ) {
                            if (rejectionRepairTool != null) {
                                workflowRepairs += 1
                            } else {
                                terminalContinuationRepairs += 1
                            }
                            runtimeCounters.recordRepairInvocation()
                            val note = workflow.continuationPrompt(
                                pendingTool, lastToolCallId!!, lastToolName!!,
                            )
                            // Sent on its own channel rather than as a tool result. A repair follows
                            // prose, so there is no pending tool call to answer, and emitting a
                            // tool-role message for a call the conversation already completed is not
                            // an ordering the native protocol defines.
                            decision = model.continueWithWorkflowNote(note)
                            continue
                        }
                        // Decided from workflow state, not from the boundary's wording or from a
                        // flag it may or may not have set. An actual model that asks "수신자를 알려
                        // 주세요" in prose reaches this line with clarification == null, and the turn
                        // still has to be typed as a clarification and answer with the missing slot.
                        val missingSlot = workflow.missingRequiredSlot()
                        val validated = when (val finalValidation = workflow.validateFinal(current.draftText)) {
                            WorkflowFinalValidationResult.Allow -> current.draftText
                            is WorkflowFinalValidationResult.Replace -> finalValidation.safeMessageKo
                        }
                        // When the boundary already produced a slot-specific question — the local
                        // gateway marks its own — that wording is kept, because it is more specific
                        // than a generic one ("이 명함에는 이메일 주소가 없습니다" beats "누구에게
                        // 보낼지 알려 주세요"). A boundary that returned bare prose gets the app's
                        // grounded question substituted, so an actual model cannot leave the user
                        // without the thing that is missing.
                        val safeDraft = if (current.clarification == null && missingSlot != null) {
                            missingSlot.questionKo
                        } else {
                            validated
                        }
                        val assembled = StringBuilder()
                        model.streamFinal(FinalAnswerInput(safeDraft, observations)).collect { token ->
                            assembled.append(token)
                            emit(AgentEvent.Token(token))
                        }
                        if (assembled.isEmpty() && safeDraft.isNotBlank()) {
                            assembled.append(safeDraft)
                            emit(AgentEvent.Token(safeDraft))
                        }
                        val finalText = assembled.toString()
                        nativeLedger.appendAssistant(finalText)
                        // A turn whose tool broke is a failed turn even when the wording is calm.
                        // Recording it as COMPLETED left a later "왜 실패했어?" with no failure to
                        // explain, so the agent answered that nothing had failed.
                        val brokeATool = workflow.hasTerminalExecutionFailure ||
                            workflow.hasUnrecoveredRejection
                        // A boundary that answered because a required slot was missing did not complete the
                        // turn — it asked. Typing that as COMPLETED made "asked the user for the missing time"
                        // and "gave a general reply and ran nothing" the same recorded outcome, which is how a
                        // turn that did nothing scored as a success. This is the v4 contract; v1–v3 froze the
                        // older typing and are read through an explicit legacy translation instead.
                        // A turn that could not pin its target down also ended by asking, even
                        // though the boundary produced a perfectly specific sentence for it
                        // ("대상 명함을 하나로 특정해 주세요"). Typed from workflow state rather than
                        // from that wording, so an actual model writing its own prose is typed the
                        // same way. See [AgentWorkflowSession.unresolvedActionTarget].
                        val unresolvedTarget = workflow.unresolvedActionTarget()
                        val status = when {
                            brokeATool -> TurnOutcome.FAILED
                            missingSlot != null || current.clarification != null || unresolvedTarget ->
                                TurnOutcome.NEEDS_CLARIFICATION
                            else -> TurnOutcome.COMPLETED
                        }
                        val outcomeType = TurnOutcomeClassifier.classify(
                            route, executedTools, status, brokeATool,
                        )
                        recordDiagnostics(
                            turnId, generation, route, dialogueAct, executedTools,
                            promptContext.sections.map { it.name }, promptContext.estimatedTokens,
                            status.name, false,
                        )
                        if (isCurrent(generation)) {
                            sessionManager.completeTurn(
                                turnId, finalText, status, executedTools,
                                workflow.failureDetailKo, outcomeType,
                            )
                        }
                        emit(AgentEvent.FinalMessage(finalText))
                        return@withLock
                    }
                    is ModelDecision.Invalid -> {
                        // A terminal surface may already have opened when a later natural-language
                        // generation fails. Its typed result is sufficient for a truthful final
                        // response; never ask the model to retry or execute the surface again.
                        workflow.terminalSurfaceFallback()?.let { fallback ->
                            nativeLedger.appendAssistant(fallback)
                            recordDiagnostics(
                                turnId, generation, route, dialogueAct, executedTools,
                                promptContext.sections.map { it.name }, promptContext.estimatedTokens,
                                TurnOutcome.COMPLETED.name, false,
                            )
                            if (isCurrent(generation)) {
                                sessionManager.completeTurn(
                                    turnId, fallback, TurnOutcome.COMPLETED, executedTools,
                                    null, TurnOutcomeClassifier.classify(
                                        route, executedTools, TurnOutcome.COMPLETED, false,
                                    ),
                                )
                            }
                            emit(AgentEvent.Token(fallback))
                            emit(AgentEvent.FinalMessage(fallback))
                            return@withLock
                        }
                        // A native parser can reject a malformed/duplicate tool-call before a
                        // structured decision reaches the kernel. Give the model one bounded,
                        // non-side-effecting protocol correction; a second failure follows the
                        // normal safe failure path below.
                        if (current.retryable && protocolCorrections < turnPolicy.maxProtocolCorrections &&
                            callCount < turnPolicy.maxToolCalls
                        ) {
                            protocolCorrections += 1
                            runtimeCounters.recordRepairInvocation()
                            decision = model.continueWithWorkflowNote(
                                ModelWorkflowNote(
                                    text = "도구 호출 형식이 올바르지 않습니다. 한 번에 하나의 도구만 " +
                                        "JSON schema의 required/enum 인자 그대로 호출하세요. 설명은 출력하지 마세요.",
                                    pendingTool = "protocol_repair",
                                    afterCallId = "protocol-repair",
                                    afterToolName = "protocol",
                                ),
                            )
                            continue
                        }
                        val pendingTool = workflow.pendingRejectionRepairTool()
                        if (pendingTool != null &&
                            workflowRepairs < turnPolicy.maxWorkflowRepairs &&
                            callCount < turnPolicy.maxToolCalls &&
                            lastToolCallId != null
                        ) {
                            workflowRepairs += 1
                            runtimeCounters.recordRepairInvocation()
                            decision = model.continueWithWorkflowNote(
                                workflow.continuationPrompt(
                                    pendingTool,
                                    lastToolCallId!!,
                                    lastToolName!!,
                                ),
                            )
                            continue
                        }
                        failTurn(
                            current.safeReason.ifBlank { "요청을 안전하게 해석하지 못했습니다." },
                            turnId, normalized, generation, ::emit, executedTools,
                        )
                        return@withLock
                    }
                    is ModelDecision.ToolCalls -> {
                        if (turnPolicy.rejectMultipleCallsPerDecision && current.calls.size != 1) {
                            failTurn(
                                "한 번에 하나의 도구만 실행할 수 있습니다.", turnId, normalized, generation, ::emit,
                                executedTools, StandardToolErrorCodes.MULTIPLE_TOOL_CALLS_NOT_ALLOWED,
                            )
                            return@withLock
                        }
                        val rawCall = current.calls.singleOrNull()
                        if (rawCall == null) {
                            failTurn("모델이 빈 도구 호출을 반환했습니다.", turnId, normalized, generation, ::emit, executedTools)
                            return@withLock
                        }
                        if (callCount >= turnPolicy.maxToolCalls) {
                            val rejection = WorkflowRejection(
                                WorkflowRejectReason.TOOL_CALL_LIMIT_REACHED,
                                "한 요청에서 실행할 수 있는 도구 횟수를 초과했습니다.",
                            )
                            if (protocolCorrections >= turnPolicy.maxProtocolCorrections) {
                                failTurn(
                                    rejection.messageKo, turnId, normalized, generation, ::emit,
                                    executedTools, StandardToolErrorCodes.TOOL_CALL_LIMIT_REACHED,
                                )
                                return@withLock
                            }
                            protocolCorrections += 1
                            val response = workflow.rejectionResponse(rawCall, rejection)
                            observations += response
                            emit(AgentEvent.ToolFinished(rejection.messageKo))
                            decision = model.continueWithToolResult(response)
                            continue
                        }
                        val contract = snapshot.contractsByModelName[rawCall.modelToolName]
                        if (contract == null) {
                            val rejection = WorkflowRejection(
                                WorkflowRejectReason.TOOL_NOT_ALLOWED,
                                "현재 제공하지 않는 기능입니다.",
                            )
                            if (protocolCorrections >= turnPolicy.maxProtocolCorrections) {
                                failTurn(rejection.messageKo, turnId, normalized, generation, ::emit, executedTools)
                                return@withLock
                            }
                            protocolCorrections += 1
                            callCount += 1
                            val response = workflow.rejectionResponse(rawCall, rejection)
                            observations += response
                            emit(AgentEvent.ToolFinished(rejection.messageKo))
                            decision = model.continueWithToolResult(response)
                            continue
                        }
                        // Canonicalise before anything reads the arguments, so the fingerprint, the
                        // validator and the executor all see one form. The real model writes
                        // "2027-05-06T16:00:00" for a contract that says minute precision; treating
                        // that as a different request from "2027-05-06T16:00" cost three otherwise
                        // correct calendar turns in the desktop Gemma run.
                        val call = workflow.normalizeArguments(rawCall, contract)
                        val fingerprint = call.modelToolName + ":" +
                            JsonCanonicalizer.sha256(JsonCanonicalizer.canonical(call.arguments))
                        if (turnPolicy.rejectRepeatedCall && fingerprint in fingerprints) {
                            val rejection = WorkflowRejection(
                                WorkflowRejectReason.REPEATED_TOOL_CALL,
                                "동일한 도구 호출이 반복되었습니다.",
                            )
                            if (protocolCorrections >= turnPolicy.maxProtocolCorrections) {
                                failTurn(
                                    "동일한 오류가 반복되어 안전하게 중단했습니다.", turnId, normalized, generation,
                                    ::emit, executedTools, StandardToolErrorCodes.REPEATED_TOOL_CALL,
                                )
                                return@withLock
                            }
                            protocolCorrections += 1
                            callCount += 1
                            val response = workflow.rejectionResponse(call, rejection)
                            observations += response
                            emit(AgentEvent.ToolFinished(rejection.messageKo))
                            decision = model.continueWithToolResult(response)
                            continue
                        }
                        callCount += 1
                        when (val validation = workflow.validate(call, contract)) {
                            WorkflowValidationResult.Allow -> Unit
                            is WorkflowValidationResult.Reject -> {
                                val workflowTransition = validation.rejection.repairArguments != null &&
                                    validation.rejection.allowedNextTools.singleOrNull() ==
                                    AgentWorkflowSession.GET_CONTACT
                                if (workflowTransition) {
                                    if (workflowTransitionRejections >= turnPolicy.maxWorkflowRepairs) {
                                        failTurn(
                                            "연락처 확인 절차를 안전하게 복구하지 못했습니다. " +
                                                validation.rejection.messageKo,
                                            turnId, normalized, generation, ::emit, executedTools,
                                        )
                                        return@withLock
                                    }
                                    workflowTransitionRejections += 1
                                } else {
                                    if (protocolCorrections >= turnPolicy.maxProtocolCorrections) {
                                        failTurn(
                                            "같은 요청을 안전하게 수정하지 못했습니다. " +
                                                validation.rejection.messageKo,
                                            turnId, normalized, generation, ::emit, executedTools,
                                        )
                                        return@withLock
                                    }
                                    protocolCorrections += 1
                                }
                                val response = workflow.rejectionResponse(call, validation.rejection)
                                observations += response
                                emit(AgentEvent.ToolFinished(validation.rejection.messageKo))
                                // A rejected terminal call is still the protocol point a bounded
                                // workflow note follows if the model answers with prose or no output
                                // instead of taking the advertised read repair.
                                lastToolCallId = call.callId
                                lastToolName = call.modelToolName
                                decision = model.continueWithToolResult(response)
                                continue
                            }
                        }
                        fingerprints += fingerprint
                        // One irreversible action per turn. A retry or a duplicated model call must
                        // not open a second compose screen or write a card twice.
                        if (!sideEffects.tryReserve(contract)) {
                            val rejection = WorkflowRejection(
                                WorkflowRejectReason.REPEATED_TOOL_CALL,
                                "같은 요청에서 외부 작업을 두 번 실행할 수 없습니다.",
                            )
                            val response = workflow.rejectionResponse(call, rejection)
                            observations += response
                            emit(AgentEvent.ToolFinished(rejection.messageKo))
                            decision = model.continueWithToolResult(response)
                            if (lease.isStale()) return@withLock
                            continue
                        }
                        if (lease.isStale()) return@withLock
                        val policy = policyEngine.evaluate(
                            contract,
                            call,
                            AgentSessionView(session.sessionId, permissions),
                        )
                        val policyFailure = when (policy) {
                            ToolPolicyDecision.Allow -> null
                            is ToolPolicyDecision.RequirePermission -> {
                                emit(AgentEvent.PermissionRequested(policy.permissions))
                                ToolError(StandardToolErrorCodes.PERMISSION_REQUIRED,
                                    "필요한 권한이 없습니다.", true)
                            }
                            is ToolPolicyDecision.RequireConfirmation -> {
                                emit(AgentEvent.ConfirmationRequested(policy.promptKo))
                                if (toolContext.confirmationGateway.confirm(policy.promptKo)) null
                                else ToolError(StandardToolErrorCodes.CONFIRMATION_REJECTED,
                                    "사용자가 작업을 취소했습니다.", false)
                            }
                            is ToolPolicyDecision.Deny -> policy.error
                        }
                        // Last gate before an irreversible action.
                        if (lease.isStale()) return@withLock
                        val result = if (policyFailure != null) {
                            sideEffects.release(contract)
                            policyFailureResult(call, contract, policyFailure)
                        } else {
                            emit(AgentEvent.ToolStarted(contract.presentation.runningMessageKo))
                            toolExecutor.execute(call, snapshot, toolContext)
                        }
                        if (lease.isStale()) return@withLock
                        if (result is ToolExecutionResult.Success) {
                            if (isCurrent(generation)) {
                                sessionManager.apply(result.sessionUpdates)
                                sessionManager.projectToolResult(contract.modelName, result.data, turnId)
                            }
                            executedTools += contract.modelName
                            if (contract.modelName in AgentWorkflowSession.SIDE_EFFECTING_TOOLS) {
                                runtimeCounters.recordTerminalActionCompletion()
                            }
                        }
                        if (result is ToolExecutionResult.Failure &&
                            contract.modelName == ToolResultProjector.GET
                        ) {
                            // The remembered card no longer resolves; dropping the focus stops a
                            // later turn from acting on a person the store cannot confirm.
                            call.arguments["card_id"]?.let { value ->
                                val staleId = value.toString().trim('"')
                                if (isCurrent(generation)) sessionManager.invalidateStaleCard(staleId)
                            }
                        }
                        workflow.recordResult(call, result)
                        // Remember the channel a repair would be sent on.
                        lastToolCallId = call.callId
                        lastToolName = call.modelToolName
                        val observation = observationMapper.toModelResponse(result, contract)
                        observations += observation.modelResponse
                        nativeLedger.appendToolCall(call.modelToolName, call.arguments.toString())
                        nativeLedger.appendToolResponse(
                            observation.modelResponse.modelToolName,
                            observation.modelResponse.payload.toString(),
                        )
                        emit(AgentEvent.ToolFinished(observation.safeUiMessageKo))
                        decision = model.continueWithToolResult(observation.modelResponse)
                        if (lease.isStale()) return@withLock
                    }
                }
            }
        }
    }

    private fun isResolvedExplicitDetailFetch(
        normalized: String,
        dialogueAct: com.hjp.agent.contract.DialogueAct,
        route: TurnRoutePlan,
        currentTarget: CurrentContactTarget?,
        session: AgentSession,
    ): Boolean {
        if (currentTarget == null || dialogueAct == com.hjp.agent.contract.DialogueAct.CONTACT_SEARCH ||
            dialogueAct == com.hjp.agent.contract.DialogueAct.CORRECTION ||
            route is TurnRoutePlan.CorrectionReplacement ||
            (route as? TurnRoutePlan.GroundedContact)?.replacesPreviousTarget == true ||
            session.conversationMemory.candidateContacts.size > 1 &&
                session.conversationMemory.selectedContact == null
        ) return false
        val text = normalized.replace(Regex("\\s+"), " ").trim().lowercase()
        if (listOf("메일", "이메일", "작 일정", "캘린더", "일정", "미팅", "회의", "compose", "업데이트", "수정", "변경", "저장").any(text::contains)) return false
        val detail = listOf("상세", "연락처", "전화번호", "이메일", "메일 주소", "명함 정보", "주소")
        val fetch = listOf("보여", "알려", "조회", "확인", "찾아")
        return detail.any(text::contains) && fetch.any(text::contains)
    }

    /**
     * Reads one verified card and answers from its current values.
     *
     * The card id comes from the router, which only ever hands over an id this session obtained from
     * a tool, so this cannot widen into a lookup of an arbitrary contact. A lookup failure retires
     * the focus instead of answering from the stale projection.
     */
    private suspend fun answerContactDetail(
        route: TurnRoutePlan.ContactDetail,
        snapshot: com.hjp.tool.contract.ToolCatalogSnapshot,
        session: AgentSession,
        turnId: String,
        userText: String,
        generation: Long,
        emit: suspend (AgentEvent) -> Unit,
    ) {
        val contract = snapshot.contractsByModelName[AgentWorkflowSession.GET_CONTACT]
        if (contract == null) {
            failTurn("명함 상세 조회 기능을 사용할 수 없습니다.", turnId, userText, generation, emit)
            return
        }
        val lease = TurnLease(generation) { sessionManager.getOrCreate().generation }
        val call = ModelToolCall(
            callId = UUID.randomUUID().toString(),
            modelToolName = AgentWorkflowSession.GET_CONTACT,
            arguments = buildJsonObject {
                put("card_id", route.cardId)
                put("purpose", "display")
            },
        )
        emit(AgentEvent.ToolStarted(contract.presentation.runningMessageKo))
        val result = toolExecutor.execute(call, snapshot, environment.toolContext(session.sessionId, turnId))
        if (lease.isStale()) return
        if (result !is ToolExecutionResult.Success) {
            if (isCurrent(generation)) sessionManager.invalidateStaleCard(route.cardId)
            val reason = (result as? ToolExecutionResult.Failure)?.error?.safeMessageKo
                ?.takeIf(String::isNotBlank)
                ?: "명함 상세정보를 확인하지 못했습니다."
            emit(AgentEvent.ToolFinished(reason))
            failTurn(reason, turnId, userText, generation, emit, listOf(contract.modelName))
            return
        }
        if (isCurrent(generation)) {
            sessionManager.apply(result.sessionUpdates)
            sessionManager.projectToolResult(contract.modelName, result.data, turnId)
        }
        emit(AgentEvent.ToolFinished(contract.presentation.runningMessageKo))
        val message = ContactDetailAnswer.render(route, result.data)
        if (isCurrent(generation)) {
            sessionManager.completeTurn(
                turnId, message, TurnOutcome.COMPLETED, listOf(contract.modelName),
                outcomeType = TurnOutcomeType.CONTACT_DETAIL_SHOWN,
            )
        }
        emit(AgentEvent.Token(message))
        emit(AgentEvent.FinalMessage(message))
    }

    private fun recordDiagnostics(
        turnId: String,
        generation: Long,
        route: TurnRoutePlan,
        dialogueAct: com.hjp.agent.contract.DialogueAct,
        executedTools: List<String>,
        sections: List<String>,
        promptTokens: Int,
        outcome: String?,
        staleAborted: Boolean,
    ) {
        diagnostics.record(TurnDiagnostics(
            kernelMode = mode,
            sessionGeneration = generation,
            turnId = turnId,
            routePlan = route::class.simpleName ?: "Unknown",
            dialogueAct = dialogueAct.name,
            evidence = when (route) {
                is TurnRoutePlan.GroundedContact -> "TOOL_VERIFIED_CARD"
                is TurnRoutePlan.CorrectionReplacement -> "CORRECTION_FOCUS_RETIRED"
                is TurnRoutePlan.Clarify -> route.reason.name
                is TurnRoutePlan.AnswerFromHistory -> "SESSION_TRANSCRIPT"
                is TurnRoutePlan.Unsupported -> "CAPABILITY_VETO"
                else -> if (dialogueAct == com.hjp.agent.contract.DialogueAct.CORRECTION) {
                    "CORRECTION_FOCUS_RETIRED"
                } else {
                    "USER_INPUT_ONLY"
                }
            },
            outcome = outcome,
            executedTools = executedTools.toList(),
            promptSections = sections,
            promptTokensEstimated = promptTokens,
            nativeContext = nativeLedger.snapshot(),
            staleAborted = staleAborted,
        ))
    }

    private suspend fun isCurrent(generation: Long): Boolean =
        sessionManager.getOrCreate().generation == generation

    private suspend fun finishWithoutTools(
        message: String,
        turnId: String,
        userText: String,
        outcome: TurnOutcome,
        generation: Long,
        emit: suspend (AgentEvent) -> Unit,
        detailKo: String? = null,
        outcomeType: TurnOutcomeType? = null,
    ) {
        if (isCurrent(generation)) {
            sessionManager.completeTurn(turnId, message, outcome, emptyList(), detailKo, outcomeType)
        }
        emit(AgentEvent.Token(message))
        emit(AgentEvent.FinalMessage(message))
    }

    private suspend fun failTurn(
        message: String,
        turnId: String,
        userText: String,
        generation: Long,
        emit: suspend (AgentEvent) -> Unit,
        executedTools: List<String> = emptyList(),
        code: com.hjp.tool.contract.ToolErrorCode? = null,
    ) {
        if (isCurrent(generation)) {
            sessionManager.completeTurn(
                turnId, message, TurnOutcome.FAILED, executedTools, message, TurnOutcomeType.FAILED,
            )
        }
        emit(AgentEvent.UserError(message, code))
    }

    private fun buildCapabilityContext(
        session: AgentSession,
        actionableCardId: String? = null,
        suppressSelectedContact: Boolean = false,
    ) = buildJsonObject {
        val now = System.currentTimeMillis()
        session.capabilityState.entries
            .filter { (_, state) -> state.expiresAtEpochMillis?.let { it > now } ?: true }
            .groupBy { it.key.namespace }
            .forEach { (namespace, entries) ->
                putJsonObject(namespace) {
                    entries.forEach { (key, state) ->
                        // Preserve all capability state. A prior selected contact is merely not
                        // actionable model context while a distinct explicit target is in flight.
                        val isForeignSelectedContact = namespace == "contact" &&
                            key.key == "selected_contact" &&
                            (suppressSelectedContact || (
                                actionableCardId != null &&
                                    !state.value.toString().contains("\"card_id\":\"$actionableCardId\"")
                            ))
                        // Once this turn has an authoritative target, the typed candidate list in
                        // ConversationMemory/session_state is sufficient.  Do not repeat the same
                        // search payload in tool_session_context; retain it in memory so an
                        // explicit ordinal/reference on a later turn can still resolve normally.
                        val isRedundantSearchResults = namespace == "contact" &&
                            key.key == "last_search_results" && actionableCardId != null
                        if (!isForeignSelectedContact && !isRedundantSearchResults) put(key.key, state.value)
                    }
                }
            }
    }

    private fun policyFailureResult(
        call: ModelToolCall,
        contract: ToolContract,
        error: ToolError,
    ) = ToolExecutionResult.Failure(
        call.callId,
        contract.capabilityId,
        contract.version,
        0,
        error,
    )
}
