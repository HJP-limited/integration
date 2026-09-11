package com.hjp.agent.contract

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * Counts what the model boundary was actually asked and what actually came back.
 *
 * A decorator rather than a change to any gateway, for two reasons. It works for every boundary
 * this project has — the LiteRT one, the deterministic local one, and a scripted one in a test —
 * so the same numbers mean the same thing whichever is deployed. And it is provably
 * behaviour-neutral: it forwards each call unchanged and returns the delegate's own value, so no
 * routing decision, tool result or response text can differ because counting was switched on. That
 * property is asserted by `RuntimeCounterContractTest`; it is the reason a run may be scored with
 * the counters in place.
 *
 * The counts distinguish three things that were previously one:
 *
 *  - **attempts** — the boundary was asked. Incremented before the call, so a call that never
 *    returns still leaves a trace.
 *  - **successes** — a decision came back. Only this counter may be read as "an actual model
 *    produced something".
 *  - **failures and cancellations** — kept apart, because a cancelled turn is a user action and a
 *    failed one is a defect, and merging them hides whichever is rarer.
 *
 * Nothing about the prompt, the decision or the user's words is recorded. Only counts and durations.
 */
class CountingAgentModelGateway(
    private val delegate: AgentModelGateway,
    private val counters: AgentRuntimeCounters,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AgentModelGateway {

    override val boundaryKind: ModelBoundaryKind get() = delegate.boundaryKind

    /** Only an actual model artefact may increment the actual-model counters. */
    private val isActualModel: Boolean
        get() = delegate.boundaryKind == ModelBoundaryKind.ACTUAL_MODEL

    override suspend fun openSession(config: ModelSessionConfig): AgentModelSession {
        if (isActualModel) counters.recordSessionOpenAttempt()
        val session = try {
            delegate.openSession(config)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (isActualModel) counters.recordSessionOpenFailure()
            throw error
        }
        if (isActualModel) counters.recordSessionOpenSuccess()
        return CountingSession(session)
    }

    override fun close() = delegate.close()

    private inner class CountingSession(
        private val inner: AgentModelSession,
    ) : AgentModelSession {

        override val catalogRevision: String get() = inner.catalogRevision

        /**
         * Counts one call to the boundary.
         *
         * Boundary traffic is counted for every gateway; the actual-model counters are touched only
         * when a real artefact is underneath. Without that split a keyword-only baseline reports
         * hundreds of "model invocations" and the number that is supposed to mean "a language model
         * ran" means "something answered".
         */
        private suspend fun <T> counted(block: suspend () -> T): T {
            counters.recordBoundaryRequest()
            if (isActualModel) counters.recordModelInvocationAttempt()
            val began = nowMillis()
            return try {
                block().also {
                    counters.recordBoundaryDecision()
                    if (isActualModel) counters.recordModelInvocationSuccess(nowMillis() - began)
                }
            } catch (cancelled: CancellationException) {
                if (isActualModel) counters.recordModelInvocationCancelled()
                throw cancelled
            } catch (timeout: ModelInvocationTimeoutException) {
                counters.recordBoundaryFailure()
                if (isActualModel) {
                    counters.recordModelInvocationTimeout()
                    counters.recordModelInvocationFailure()
                }
                throw timeout
            } catch (error: Throwable) {
                counters.recordBoundaryFailure()
                if (isActualModel) counters.recordModelInvocationFailure()
                throw error
            }
        }

        override suspend fun decide(input: ModelInput): ModelDecision = counted { inner.decide(input) }

        override suspend fun continueWithToolResult(result: ModelToolResponse): ModelDecision =
            counted { inner.continueWithToolResult(result) }

        override suspend fun continueWithWorkflowNote(note: ModelWorkflowNote): ModelDecision =
            counted { inner.continueWithWorkflowNote(note) }

        override fun streamFinal(input: FinalAnswerInput): Flow<String> = inner.streamFinal(input)

        override suspend fun resetConversation() = inner.resetConversation()

        override fun close() = inner.close()
    }
}

/**
 * A native call that exceeded its budget.
 *
 * Declared here so a boundary that enforces a deadline has one type to throw and the counter has one
 * type to recognise. Without it a timeout is indistinguishable from any other failure, and "the model
 * is slow" reads as "the model is broken".
 */
class ModelInvocationTimeoutException(message: String) : RuntimeException(message)
