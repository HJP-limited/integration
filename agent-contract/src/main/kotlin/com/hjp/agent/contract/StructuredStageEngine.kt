package com.hjp.agent.contract

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

/** One model reply for a constrained stage, reduced to what the decode loop needs. */
data class StageAttempt(
    val rawText: String,
    val arguments: JsonObject?,
)

/**
 * Carries a constrained stage to the model. The LiteRT gateway implements this over a native
 * `Conversation`; tests implement it with scripted replies so parsing, repair, timeout and error
 * conversion are verifiable without the runtime.
 */
interface StageTransport {
    suspend fun send(prompt: String): StageAttempt
    suspend fun repair(errors: List<String>, targetedMessageKo: String?): StageAttempt
}

sealed interface StagedDecode<out T> {
    data class Success<T>(val value: T, val attempts: Int) : StagedDecode<T>
    data class Failure(val errors: List<String>, val attempts: Int) : StagedDecode<Nothing>
}

/**
 * Attempt/repair loop for constrained stages.
 *
 * Extracted from the LiteRT gateway so the contract — how many attempts run, when a targeted
 * repair replaces the generic one, what a transport timeout turns into, and which errors reach the
 * caller — is testable on the JVM.
 */
class StructuredStageEngine(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val stageTimeoutMillis: Long = 0,
) {
    suspend fun <T> run(
        toolName: String,
        prompt: String,
        transport: StageTransport,
        rawOutputs: MutableList<String>,
        attempts: Int = maxAttempts,
        targetedRepair: (JsonObject?, List<String>) -> String? = { _, _ -> null },
        decode: (JsonObject) -> StructuredDecodeResult<T>,
    ): StagedDecode<T> {
        val limit = attempts.coerceAtLeast(1)
        var lastErrors = listOf(nativeCallRequired(toolName))
        var attempt = 0
        var reply = withStageTimeout(toolName) { transport.send(prompt) }
            ?: return StagedDecode.Failure(listOf(timedOut(toolName)), 1)
        while (attempt < limit) {
            rawOutputs += "stage=$toolName attempt=${attempt + 1} raw=${reply.rawText}"
            val decoded = reply.arguments?.let(decode)
            if (decoded is StructuredDecodeResult.Success) {
                return StagedDecode.Success(decoded.value, attempt + 1)
            }
            lastErrors = (decoded as? StructuredDecodeResult.Failure)?.errors
                ?: listOf(nativeCallRequired(toolName))
            attempt += 1
            if (attempt >= limit) break
            val targeted = targetedRepair(reply.arguments, lastErrors)
            reply = withStageTimeout(toolName) { transport.repair(lastErrors, targeted) }
                ?: return StagedDecode.Failure(listOf(timedOut(toolName)), attempt + 1)
        }
        return StagedDecode.Failure(lastErrors, limit)
    }

    private suspend fun withStageTimeout(
        toolName: String,
        block: suspend () -> StageAttempt,
    ): StageAttempt? {
        if (stageTimeoutMillis <= 0) return block()
        return try {
            withTimeout(stageTimeoutMillis) { block() }
        } catch (_: TimeoutCancellationException) {
            null
        }
    }

    private fun nativeCallRequired(toolName: String) = "$toolName native call is required"

    private fun timedOut(toolName: String) = "$toolName stage timed out"

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 2
    }
}
