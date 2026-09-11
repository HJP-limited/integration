package com.hjp.agent.core

import com.hjp.agent.contract.ModelToolCall
import com.hjp.tool.contract.ConfirmationPolicy
import com.hjp.tool.contract.StandardToolErrorCodes
import com.hjp.tool.contract.ToolContract
import com.hjp.tool.contract.ToolEffect
import com.hjp.tool.contract.ToolError

data class AgentSessionView(
    val sessionId: String,
    val grantedPermissions: Set<String>,
)

sealed interface ToolPolicyDecision {
    data object Allow : ToolPolicyDecision
    data class RequireConfirmation(val promptKo: String) : ToolPolicyDecision
    data class RequirePermission(val permissions: Set<String>) : ToolPolicyDecision
    data class Deny(val error: ToolError) : ToolPolicyDecision
}

interface ToolPolicyEngine {
    suspend fun evaluate(
        contract: ToolContract,
        call: ModelToolCall,
        session: AgentSessionView,
    ): ToolPolicyDecision
}

class DefaultToolPolicyEngine : ToolPolicyEngine {
    override suspend fun evaluate(
        contract: ToolContract,
        call: ModelToolCall,
        session: AgentSessionView,
    ): ToolPolicyDecision {
        val missing = contract.requiredPermissions - session.grantedPermissions
        if (missing.isNotEmpty()) return ToolPolicyDecision.RequirePermission(missing)
        if (contract.effect == ToolEffect.EXTERNAL_MUTATION) {
            return ToolPolicyDecision.Deny(ToolError(
                StandardToolErrorCodes.CONFIRMATION_REQUIRED,
                "외부 데이터 변경 기능은 현재 허용되지 않습니다.",
                false,
            ))
        }
        if (contract.confirmationPolicy == ConfirmationPolicy.BEFORE_EXECUTION ||
            contract.effect == ToolEffect.LOCAL_MUTATION
        ) {
            return ToolPolicyDecision.RequireConfirmation("${contract.description} 작업을 실행할까요?")
        }
        return ToolPolicyDecision.Allow
    }
}
