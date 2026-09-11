package com.hjp.agent.core

import com.hjp.agent.contract.AgentEvent
import kotlinx.coroutines.flow.Flow

/** Which execution path runs a turn. Selectable in debug builds only. */
enum class AgentKernelMode { REACT, STRUCTURED }

/**
 * Common entry point so the UI, the evaluation harness and the tests can drive either kernel
 * through the same surface and compare them on the same fixture.
 */
interface AgentTurnEngine : AutoCloseable {
    val mode: AgentKernelMode

    fun runTurn(userText: String): Flow<AgentEvent>

    /** Discards the session atomically and returns the new generation. */
    suspend fun resetSession(): Long
}
