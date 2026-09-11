package com.hjp.agent.contract

import java.util.concurrent.atomic.AtomicLong

/**
 * What the agent's runtime actually did, counted where it happens.
 *
 * ## Why this exists
 *
 * Every evaluation this project has run so far had to *guess* whether a real model had been
 * involved. The v3 device runner wrote `"actual_model_executed": true` as a string literal, and the
 * host runners reported `actual_model_invocation_successes` from a field hard-coded to `0`. Both
 * numbers were therefore assertions about the runner's intent, not observations of the run — and an
 * assertion of intent cannot fail when the intent is wrong. A device run whose model silently fell
 * back, or never loaded at all, would have reported exactly the same figure.
 *
 * So the counters live at the boundaries where the work happens: the gateway that opens a native
 * session and sends it messages, the kernel that decides whether a turn ever reached the model, and
 * the search backend that either ran an embedding or fell back to keywords. A reporter reads them;
 * it never computes them.
 *
 * ## What is and is not counted
 *
 *  - Entering the model boundary is **not** a successful invocation. `modelInvocationAttempts` is
 *    incremented on the way in and `modelInvocationSuccesses` only after a decision comes back, so a
 *    boundary that threw, timed out or was cancelled cannot be read as one that answered.
 *  - A non-empty response is **not** evidence of a model. The deterministic local gateway produces
 *    non-empty responses for every turn; it simply is not a language model, and nothing here counts
 *    it as one.
 *  - [actualModelExecuted] and [actualSemanticExecuted] are derived from success counters and from
 *    nothing else.
 *
 * ## Safety
 *
 * Every field is a count or a duration. No user text, no prompt, no card field and no tool payload
 * can be stored here, which is what makes the snapshot safe to write into an evidence file. This is
 * checked, not merely intended — see `RuntimeCounterContractTest`.
 *
 * ## Recomputability
 *
 * [snapshot] is a value, and [RuntimeCounterSnapshot.minus] gives the delta between two of them. A
 * runner that snapshots around every turn therefore has per-turn records whose sum is the aggregate
 * by construction, so "the totals do not match the turns" is a detectable condition rather than an
 * assumption.
 *
 * Thread-safe: turns are serialised by the kernel, but the search backend and the gateway can be
 * touched from a background dispatcher.
 */
class AgentRuntimeCounters {

    // ---- model artefact lifecycle ---------------------------------------------------------------
    private val modelLoadAttempts = AtomicLong()
    private val modelLoadSuccesses = AtomicLong()
    private val modelLoadFailures = AtomicLong()
    private val modelBackendFallbacks = AtomicLong()

    // ---- boundary traffic, whatever is behind the boundary ---------------------------------------
    private val boundaryRequests = AtomicLong()
    private val boundaryDecisions = AtomicLong()
    private val boundaryFailures = AtomicLong()

    // ---- native inference, only when an actual model artefact is behind the boundary --------------
    private val modelSessionOpenAttempts = AtomicLong()
    private val modelSessionOpenSuccesses = AtomicLong()
    private val modelSessionOpenFailures = AtomicLong()
    private val modelInvocationAttempts = AtomicLong()
    private val modelInvocationSuccesses = AtomicLong()
    private val modelInvocationFailures = AtomicLong()
    private val modelInvocationCancellations = AtomicLong()
    private val modelInvocationTimeouts = AtomicLong()
    private val modelLatencySamples = AtomicLong()
    private val modelLatencyTotalMillis = AtomicLong()
    private val modelLatencyMaxMillis = AtomicLong()

    // ---- turn shape -----------------------------------------------------------------------------
    private val turnsStarted = AtomicLong()
    private val modelBoundaryTurns = AtomicLong()
    private val deterministicOnlyTurns = AtomicLong()
    private val repairInvocations = AtomicLong()
    private val terminalActionCompletions = AtomicLong()
    private val contextPreflightRejections = AtomicLong()

    // ---- semantic retrieval ---------------------------------------------------------------------
    private val semanticInitializationAttempts = AtomicLong()
    private val semanticInitializationSuccesses = AtomicLong()
    private val semanticInitializationFailures = AtomicLong()
    private val semanticInvocations = AtomicLong()
    private val semanticCandidateTotal = AtomicLong()
    private val semanticResultTotal = AtomicLong()
    private val semanticCandidateProducingInvocations = AtomicLong()
    private val keywordFallbackInvocations = AtomicLong()
    private val semanticLatencySamples = AtomicLong()
    private val semanticLatencyTotalMillis = AtomicLong()
    private val semanticLatencyMaxMillis = AtomicLong()

    // ---- recording ------------------------------------------------------------------------------

    fun recordModelLoadAttempt() { modelLoadAttempts.incrementAndGet() }
    fun recordModelLoadSuccess() { modelLoadSuccesses.incrementAndGet() }
    fun recordModelLoadFailure() { modelLoadFailures.incrementAndGet() }
    fun recordModelBackendFallback() { modelBackendFallbacks.incrementAndGet() }

    fun recordSessionOpenAttempt() { modelSessionOpenAttempts.incrementAndGet() }
    fun recordSessionOpenSuccess() { modelSessionOpenSuccesses.incrementAndGet() }
    fun recordSessionOpenFailure() { modelSessionOpenFailures.incrementAndGet() }

    fun recordBoundaryRequest() { boundaryRequests.incrementAndGet() }
    fun recordBoundaryDecision() { boundaryDecisions.incrementAndGet() }
    fun recordBoundaryFailure() { boundaryFailures.incrementAndGet() }

    fun recordModelInvocationAttempt() { modelInvocationAttempts.incrementAndGet() }

    /** A decision came back. [elapsedMillis] is the wall time of that one native call. */
    fun recordModelInvocationSuccess(elapsedMillis: Long) {
        modelInvocationSuccesses.incrementAndGet()
        modelLatencySamples.incrementAndGet()
        modelLatencyTotalMillis.addAndGet(maxOf(0L, elapsedMillis))
        modelLatencyMaxMillis.accumulateAndGet(maxOf(0L, elapsedMillis), ::maxOf)
    }

    fun recordModelInvocationFailure() { modelInvocationFailures.incrementAndGet() }
    fun recordModelInvocationCancelled() { modelInvocationCancellations.incrementAndGet() }
    fun recordModelInvocationTimeout() { modelInvocationTimeouts.incrementAndGet() }

    fun recordTurnStarted() { turnsStarted.incrementAndGet() }
    fun recordModelBoundaryTurn() { modelBoundaryTurns.incrementAndGet() }
    fun recordDeterministicOnlyTurn() { deterministicOnlyTurns.incrementAndGet() }
    fun recordRepairInvocation() { repairInvocations.incrementAndGet() }
    fun recordTerminalActionCompletion() { terminalActionCompletions.incrementAndGet() }
    fun recordContextPreflightRejection() { contextPreflightRejections.incrementAndGet() }

    fun recordSemanticInitializationAttempt() { semanticInitializationAttempts.incrementAndGet() }
    fun recordSemanticInitializationSuccess() { semanticInitializationSuccesses.incrementAndGet() }
    fun recordSemanticInitializationFailure() { semanticInitializationFailures.incrementAndGet() }

    /**
     * One retrieval, as the backend itself reported it.
     *
     * [semanticUsed] is the backend's own mode, not an inference from the result count: a semantic
     * pass that returns nothing still ran, and a keyword-only pass that returns plenty did not.
     */
    fun recordRetrieval(
        semanticUsed: Boolean,
        candidateCount: Int,
        semanticResultCount: Int,
        keywordFallback: Boolean,
        elapsedMillis: Long,
    ) {
        if (semanticUsed) {
            semanticInvocations.incrementAndGet()
            semanticCandidateTotal.addAndGet(maxOf(0, candidateCount).toLong())
            semanticResultTotal.addAndGet(maxOf(0, semanticResultCount).toLong())
            if (semanticResultCount > 0) semanticCandidateProducingInvocations.incrementAndGet()
            semanticLatencySamples.incrementAndGet()
            semanticLatencyTotalMillis.addAndGet(maxOf(0L, elapsedMillis))
            semanticLatencyMaxMillis.accumulateAndGet(maxOf(0L, elapsedMillis), ::maxOf)
        }
        if (keywordFallback) keywordFallbackInvocations.incrementAndGet()
    }

    fun snapshot(): RuntimeCounterSnapshot = RuntimeCounterSnapshot(
        boundaryRequests = boundaryRequests.get(),
        boundaryDecisions = boundaryDecisions.get(),
        boundaryFailures = boundaryFailures.get(),
        modelLoadAttempts = modelLoadAttempts.get(),
        modelLoadSuccesses = modelLoadSuccesses.get(),
        modelLoadFailures = modelLoadFailures.get(),
        modelBackendFallbacks = modelBackendFallbacks.get(),
        modelSessionOpenAttempts = modelSessionOpenAttempts.get(),
        modelSessionOpenSuccesses = modelSessionOpenSuccesses.get(),
        modelSessionOpenFailures = modelSessionOpenFailures.get(),
        modelInvocationAttempts = modelInvocationAttempts.get(),
        modelInvocationSuccesses = modelInvocationSuccesses.get(),
        modelInvocationFailures = modelInvocationFailures.get(),
        modelInvocationCancellations = modelInvocationCancellations.get(),
        modelInvocationTimeouts = modelInvocationTimeouts.get(),
        modelLatencySamples = modelLatencySamples.get(),
        modelLatencyTotalMillis = modelLatencyTotalMillis.get(),
        modelLatencyMaxMillis = modelLatencyMaxMillis.get(),
        turnsStarted = turnsStarted.get(),
        modelBoundaryTurns = modelBoundaryTurns.get(),
        deterministicOnlyTurns = deterministicOnlyTurns.get(),
        repairInvocations = repairInvocations.get(),
        terminalActionCompletions = terminalActionCompletions.get(),
        contextPreflightRejections = contextPreflightRejections.get(),
        semanticInitializationAttempts = semanticInitializationAttempts.get(),
        semanticInitializationSuccesses = semanticInitializationSuccesses.get(),
        semanticInitializationFailures = semanticInitializationFailures.get(),
        semanticInvocations = semanticInvocations.get(),
        semanticCandidateTotal = semanticCandidateTotal.get(),
        semanticResultTotal = semanticResultTotal.get(),
        semanticCandidateProducingInvocations = semanticCandidateProducingInvocations.get(),
        keywordFallbackInvocations = keywordFallbackInvocations.get(),
        semanticLatencySamples = semanticLatencySamples.get(),
        semanticLatencyTotalMillis = semanticLatencyTotalMillis.get(),
        semanticLatencyMaxMillis = semanticLatencyMaxMillis.get(),
    )
}

/**
 * An immutable reading of [AgentRuntimeCounters].
 *
 * Every field is a `Long`. That is the whole type: there is nowhere to put a name, an address or a
 * sentence, which is what makes it safe to serialise into evidence unedited.
 */
data class RuntimeCounterSnapshot(
    val boundaryRequests: Long = 0,
    val boundaryDecisions: Long = 0,
    val boundaryFailures: Long = 0,
    val modelLoadAttempts: Long = 0,
    val modelLoadSuccesses: Long = 0,
    val modelLoadFailures: Long = 0,
    val modelBackendFallbacks: Long = 0,
    val modelSessionOpenAttempts: Long = 0,
    val modelSessionOpenSuccesses: Long = 0,
    val modelSessionOpenFailures: Long = 0,
    val modelInvocationAttempts: Long = 0,
    val modelInvocationSuccesses: Long = 0,
    val modelInvocationFailures: Long = 0,
    val modelInvocationCancellations: Long = 0,
    val modelInvocationTimeouts: Long = 0,
    val modelLatencySamples: Long = 0,
    val modelLatencyTotalMillis: Long = 0,
    val modelLatencyMaxMillis: Long = 0,
    val turnsStarted: Long = 0,
    val modelBoundaryTurns: Long = 0,
    val deterministicOnlyTurns: Long = 0,
    val repairInvocations: Long = 0,
    val terminalActionCompletions: Long = 0,
    val contextPreflightRejections: Long = 0,
    val semanticInitializationAttempts: Long = 0,
    val semanticInitializationSuccesses: Long = 0,
    val semanticInitializationFailures: Long = 0,
    val semanticInvocations: Long = 0,
    val semanticCandidateTotal: Long = 0,
    val semanticResultTotal: Long = 0,
    val semanticCandidateProducingInvocations: Long = 0,
    val keywordFallbackInvocations: Long = 0,
    val semanticLatencySamples: Long = 0,
    val semanticLatencyTotalMillis: Long = 0,
    val semanticLatencyMaxMillis: Long = 0,
) {
    /**
     * Whether an actual model produced anything in this run.
     *
     * Derived from the success counter and nothing else. Not from a flag, not from the presence of a
     * response, and not from having reached the boundary.
     */
    val actualModelExecuted: Boolean get() = modelInvocationSuccesses > 0

    /** Whether an actual semantic retrieval ran. Same rule, same reason. */
    val actualSemanticExecuted: Boolean
        get() = semanticInitializationSuccesses > 0 && semanticInvocations > 0

    /** Mean native call time, or null when there is no sample. There is no such thing as a mean of none. */
    val modelLatencyMeanMillis: Double?
        get() = if (modelLatencySamples == 0L) null
        else modelLatencyTotalMillis.toDouble() / modelLatencySamples

    val semanticLatencyMeanMillis: Double?
        get() = if (semanticLatencySamples == 0L) null
        else semanticLatencyTotalMillis.toDouble() / semanticLatencySamples

    /** Field-by-field difference, for per-turn records that must sum back to the aggregate. */
    operator fun minus(other: RuntimeCounterSnapshot) = RuntimeCounterSnapshot(
        boundaryRequests = boundaryRequests - other.boundaryRequests,
        boundaryDecisions = boundaryDecisions - other.boundaryDecisions,
        boundaryFailures = boundaryFailures - other.boundaryFailures,
        modelLoadAttempts = modelLoadAttempts - other.modelLoadAttempts,
        modelLoadSuccesses = modelLoadSuccesses - other.modelLoadSuccesses,
        modelLoadFailures = modelLoadFailures - other.modelLoadFailures,
        modelBackendFallbacks = modelBackendFallbacks - other.modelBackendFallbacks,
        modelSessionOpenAttempts = modelSessionOpenAttempts - other.modelSessionOpenAttempts,
        modelSessionOpenSuccesses = modelSessionOpenSuccesses - other.modelSessionOpenSuccesses,
        modelSessionOpenFailures = modelSessionOpenFailures - other.modelSessionOpenFailures,
        modelInvocationAttempts = modelInvocationAttempts - other.modelInvocationAttempts,
        modelInvocationSuccesses = modelInvocationSuccesses - other.modelInvocationSuccesses,
        modelInvocationFailures = modelInvocationFailures - other.modelInvocationFailures,
        modelInvocationCancellations = modelInvocationCancellations - other.modelInvocationCancellations,
        modelInvocationTimeouts = modelInvocationTimeouts - other.modelInvocationTimeouts,
        modelLatencySamples = modelLatencySamples - other.modelLatencySamples,
        modelLatencyTotalMillis = modelLatencyTotalMillis - other.modelLatencyTotalMillis,
        // A maximum is not additive; a delta reports this window's own maximum only when it is the
        // one that set the running maximum, and 0 otherwise. Stated rather than silently wrong.
        modelLatencyMaxMillis = if (modelLatencyMaxMillis > other.modelLatencyMaxMillis) modelLatencyMaxMillis else 0,
        turnsStarted = turnsStarted - other.turnsStarted,
        modelBoundaryTurns = modelBoundaryTurns - other.modelBoundaryTurns,
        deterministicOnlyTurns = deterministicOnlyTurns - other.deterministicOnlyTurns,
        repairInvocations = repairInvocations - other.repairInvocations,
        terminalActionCompletions = terminalActionCompletions - other.terminalActionCompletions,
        contextPreflightRejections = contextPreflightRejections - other.contextPreflightRejections,
        semanticInitializationAttempts = semanticInitializationAttempts - other.semanticInitializationAttempts,
        semanticInitializationSuccesses = semanticInitializationSuccesses - other.semanticInitializationSuccesses,
        semanticInitializationFailures = semanticInitializationFailures - other.semanticInitializationFailures,
        semanticInvocations = semanticInvocations - other.semanticInvocations,
        semanticCandidateTotal = semanticCandidateTotal - other.semanticCandidateTotal,
        semanticResultTotal = semanticResultTotal - other.semanticResultTotal,
        semanticCandidateProducingInvocations =
            semanticCandidateProducingInvocations - other.semanticCandidateProducingInvocations,
        keywordFallbackInvocations = keywordFallbackInvocations - other.keywordFallbackInvocations,
        semanticLatencySamples = semanticLatencySamples - other.semanticLatencySamples,
        semanticLatencyTotalMillis = semanticLatencyTotalMillis - other.semanticLatencyTotalMillis,
        semanticLatencyMaxMillis = if (semanticLatencyMaxMillis > other.semanticLatencyMaxMillis) semanticLatencyMaxMillis else 0,
    )

    /** Every field, by name, for serialisation. Ordered so two snapshots render comparably. */
    fun asLongMap(): Map<String, Long> = linkedMapOf(
        "model_boundary_requests" to boundaryRequests,
        "model_boundary_decisions" to boundaryDecisions,
        "model_boundary_failures" to boundaryFailures,
        "model_load_attempts" to modelLoadAttempts,
        "model_load_successes" to modelLoadSuccesses,
        "model_load_failures" to modelLoadFailures,
        "model_backend_fallbacks" to modelBackendFallbacks,
        "model_session_open_attempts" to modelSessionOpenAttempts,
        "model_session_open_successes" to modelSessionOpenSuccesses,
        "model_session_open_failures" to modelSessionOpenFailures,
        "actual_model_invocation_attempts" to modelInvocationAttempts,
        "actual_model_invocation_successes" to modelInvocationSuccesses,
        "actual_model_invocation_failures" to modelInvocationFailures,
        "model_invocation_cancellations" to modelInvocationCancellations,
        "model_invocation_timeouts" to modelInvocationTimeouts,
        "model_latency_samples" to modelLatencySamples,
        "model_latency_total_millis" to modelLatencyTotalMillis,
        "model_latency_max_millis" to modelLatencyMaxMillis,
        "turns_started" to turnsStarted,
        "model_boundary_turns" to modelBoundaryTurns,
        "deterministic_only_turns" to deterministicOnlyTurns,
        "repair_invocations" to repairInvocations,
        "terminal_action_completions" to terminalActionCompletions,
        "context_preflight_rejections" to contextPreflightRejections,
        "semantic_initialization_attempts" to semanticInitializationAttempts,
        "semantic_initialization_successes" to semanticInitializationSuccesses,
        "semantic_initialization_failures" to semanticInitializationFailures,
        "semantic_invocations" to semanticInvocations,
        "semantic_candidate_total" to semanticCandidateTotal,
        "semantic_result_total" to semanticResultTotal,
        "semantic_candidate_producing_invocations" to semanticCandidateProducingInvocations,
        "keyword_fallback_invocations" to keywordFallbackInvocations,
        "semantic_latency_samples" to semanticLatencySamples,
        "semantic_latency_total_millis" to semanticLatencyTotalMillis,
        "semantic_latency_max_millis" to semanticLatencyMaxMillis,
    )

    companion object {
        /** Field names, in serialisation order. Lets a reader validate a record's shape. */
        val FIELD_NAMES: List<String> = RuntimeCounterSnapshot().asLongMap().keys.toList()
    }
}
