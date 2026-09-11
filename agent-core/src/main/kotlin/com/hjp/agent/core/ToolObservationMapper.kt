package com.hjp.agent.core

import com.hjp.agent.contract.ModelToolResponse
import com.hjp.tool.contract.ToolContract
import com.hjp.tool.contract.ToolExecutionResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class SafeToolObservation(
    val modelResponse: ModelToolResponse,
    val safeUiMessageKo: String?,
)

fun interface ToolObservationMapper {
    fun toModelResponse(result: ToolExecutionResult, contract: ToolContract): SafeToolObservation
}

class DefaultToolObservationMapper : ToolObservationMapper {
    override fun toModelResponse(result: ToolExecutionResult, contract: ToolContract): SafeToolObservation {
        val payload = when (result) {
            is ToolExecutionResult.Success -> buildJsonObject {
                put("ok", true)
                put("capability", result.capabilityId.value)
                put("contract_version", result.contractVersion.toString())
                put("data", result.data)
            }
            is ToolExecutionResult.Failure -> buildJsonObject {
                put("ok", false)
                put("capability", result.capabilityId.value)
                put("contract_version", result.contractVersion.toString())
                putJsonObject("error") {
                    put("code", result.error.code.value)
                    put("message_ko", result.error.safeMessageKo)
                    put("retryable", result.error.retryable)
                }
            }
        }
        val message = when (result) {
            is ToolExecutionResult.Success -> result.userMessageKo ?: contract.presentation.successMessageKo
            is ToolExecutionResult.Failure -> result.error.safeMessageKo
        }
        return SafeToolObservation(
            ModelToolResponse(result.callId, contract.modelName, payload),
            message,
        )
    }
}
