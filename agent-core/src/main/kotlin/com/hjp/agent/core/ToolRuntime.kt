package com.hjp.agent.core

import com.hjp.agent.contract.ModelToolCall
import com.hjp.tool.contract.StandardToolErrorCodes
import com.hjp.tool.contract.ToolCatalogSnapshot
import com.hjp.tool.contract.ToolEffect
import com.hjp.tool.contract.ToolError
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolExecutionResult
import com.hjp.tool.contract.ToolRegistry
import com.hjp.tool.contract.ToolRequest
import java.util.Collections
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

interface ToolExecutor {
    suspend fun execute(
        call: ModelToolCall,
        snapshot: ToolCatalogSnapshot,
        context: ToolExecutionContext,
    ): ToolExecutionResult
}

class DefaultToolExecutor(private val registry: ToolRegistry) : ToolExecutor {
    private val executedKeys = Collections.synchronizedMap(object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 2_048
    })

    override suspend fun execute(
        call: ModelToolCall,
        snapshot: ToolCatalogSnapshot,
        context: ToolExecutionContext,
    ): ToolExecutionResult {
        val binding = snapshot.bindingFor(call.modelToolName)
            ?: return unresolved(call, "현재 사용할 수 없는 기능입니다.")
        val contract = snapshot.contractsByModelName[call.modelToolName]
            ?: return unresolved(call, "도구 계약을 찾을 수 없습니다.")
        val key = "${context.sessionId}:${context.turnId}:${call.callId}"
        val firstExecution = synchronized(executedKeys) {
            if (executedKeys.containsKey(key)) false else {
                executedKeys[key] = true
                true
            }
        }
        if (!firstExecution) {
            return failure(call, contract, StandardToolErrorCodes.REPEATED_TOOL_CALL,
                "같은 도구 호출이 반복되어 중단했습니다.", false)
        }
        val plugin = registry.resolve(binding)
            ?: return failure(call, contract, StandardToolErrorCodes.TOOL_NOT_AVAILABLE,
                contract.presentation.unavailableMessageKo, true)
        val request = ToolRequest(call.callId, binding.capabilityId, binding.contractVersion, call.arguments)
        return try {
            withTimeout(contract.defaultTimeoutMillis) { plugin.execute(request, context) }
        } catch (_: TimeoutCancellationException) {
            failure(call, contract, StandardToolErrorCodes.TIMEOUT, "도구 실행 시간이 초과되었습니다.",
                contract.effect == ToolEffect.READ_ONLY)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: VirtualMachineError) {
            throw fatal
        } catch (_: Throwable) {
            failure(call, contract, StandardToolErrorCodes.TOOL_EXECUTION_FAILED,
                "도구를 실행하지 못했습니다.", false)
        }
    }

    private fun unresolved(call: ModelToolCall, message: String): ToolExecutionResult {
        return ToolExecutionResult.Failure(call.callId,
            com.hjp.tool.contract.ToolCapabilityId("agent.unknown"),
            com.hjp.tool.contract.ContractVersion(1, 0), 0,
            ToolError(StandardToolErrorCodes.TOOL_NOT_AVAILABLE, message, true))
    }

    private fun failure(
        call: ModelToolCall,
        contract: com.hjp.tool.contract.ToolContract,
        code: com.hjp.tool.contract.ToolErrorCode,
        message: String,
        retryable: Boolean,
    ) = ToolExecutionResult.Failure(
        call.callId,
        contract.capabilityId,
        contract.version,
        0,
        ToolError(code, message, retryable),
    )
}
