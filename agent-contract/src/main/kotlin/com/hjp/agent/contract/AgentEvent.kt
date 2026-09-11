package com.hjp.agent.contract

import com.hjp.tool.contract.ToolErrorCode

sealed interface AgentEvent {
    data class TurnStarted(val turnId: String) : AgentEvent
    data class ToolStarted(val messageKo: String) : AgentEvent
    data class ConfirmationRequested(val promptKo: String) : AgentEvent
    data class PermissionRequested(val permissions: Set<String>) : AgentEvent
    data class ToolFinished(val messageKo: String?) : AgentEvent
    data class Token(val text: String) : AgentEvent
    data class FinalMessage(val text: String) : AgentEvent
    data class UserError(val messageKo: String, val code: ToolErrorCode? = null) : AgentEvent
}

sealed interface AgentReadiness {
    data class Ready(val availableTools: Set<String>) : AgentReadiness
    data class Blocked(val reasonKo: String) : AgentReadiness
}
