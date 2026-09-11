package com.hjp.agent.core

import com.hjp.tool.contract.ToolContract
import com.hjp.tool.contract.ToolEffect

/**
 * Authorises a turn to keep going.
 *
 * `새 대화` replaces the session while a turn may already be inside a native inference that cannot
 * be interrupted. Coroutine cancellation alone is therefore not enough: every boundary that could
 * cause a side effect or write into the session re-checks the generation it started under, and a
 * stale turn stops without executing, recording or emitting anything.
 */
class TurnLease(
    private val generation: Long,
    private val currentGeneration: suspend () -> Long,
) {
    suspend fun isValid(): Boolean = currentGeneration() == generation

    suspend fun isStale(): Boolean = !isValid()
}

/**
 * Turn-local guard against running the same irreversible action twice.
 *
 * A retry, a protocol correction or a duplicated model tool call must never open two compose
 * screens or write a card twice, so a side-effecting capability may execute at most once per turn.
 */
class SideEffectGuard {
    private val executed = mutableSetOf<String>()

    fun isSideEffect(contract: ToolContract): Boolean = contract.effect != ToolEffect.READ_ONLY

    /** Returns false when this side effect already ran in the current turn. */
    fun tryReserve(contract: ToolContract): Boolean {
        if (!isSideEffect(contract)) return true
        return executed.add(contract.capabilityId.value)
    }

    fun release(contract: ToolContract) {
        if (isSideEffect(contract)) executed.remove(contract.capabilityId.value)
    }

    fun executedCapabilities(): Set<String> = executed.toSet()
}
