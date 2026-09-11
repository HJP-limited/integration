package com.hjp.agent.core

/**
 * Non-PII snapshot of the last turn, for debug builds and device bring-up.
 *
 * Contains only shapes and identifiers the agent itself generated: no names, addresses, phone
 * numbers, card fields, user text or tool payloads. Safe to log or show on a debug screen; it is
 * never surfaced in the release UI.
 */
data class TurnDiagnostics(
    val kernelMode: AgentKernelMode,
    val sessionGeneration: Long,
    val turnId: String,
    val routePlan: String,
    /**
     * The dialogue act the kernel itself classified this turn as.
     *
     * Recorded so an evaluator can read the decision the kernel actually routed on instead of
     * re-deriving it with a second copy of the rules. An enum name carries no user text, no name and
     * no card field, so it is as safe to surface as the rest of this record.
     */
    val dialogueAct: String,
    val evidence: String,
    val outcome: String?,
    val executedTools: List<String>,
    val promptSections: List<String>,
    val promptTokensEstimated: Int,
    val nativeContext: NativeContextSnapshot,
    val staleAborted: Boolean,
) {
    fun asMap(): Map<String, String> = mapOf(
        "kernel_mode" to kernelMode.name,
        "session_generation" to sessionGeneration.toString(),
        "turn_id" to turnId,
        "route_plan" to routePlan,
        "dialogue_act" to dialogueAct,
        "evidence" to evidence,
        "outcome" to (outcome ?: "none"),
        "executed_tools" to executedTools.joinToString("|"),
        "prompt_sections" to promptSections.joinToString("|"),
        "prompt_tokens_estimated" to promptTokensEstimated.toString(),
        "native_fixed_tokens" to nativeContext.fixedTokens.toString(),
        "native_history_tokens" to nativeContext.historyTokens.toString(),
        "native_total_tokens" to nativeContext.totalTokens.toString(),
        "native_rotations" to nativeContext.rotations.toString(),
        "stale_aborted" to staleAborted.toString(),
    )
}

/** Holds the most recent [TurnDiagnostics]. Only the latest turn is kept; nothing is persisted. */
class AgentDiagnosticsRecorder {
    @Volatile
    var last: TurnDiagnostics? = null
        private set

    fun record(diagnostics: TurnDiagnostics) {
        last = diagnostics
    }

    fun clear() {
        last = null
    }
}
