package com.hjp.tool.contract

import kotlinx.serialization.json.JsonObject

data class ToolRequest(
    val callId: String,
    val capabilityId: ToolCapabilityId,
    val contractVersion: ContractVersion,
    val arguments: JsonObject,
)

fun interface PermissionGateway {
    suspend fun grantedPermissions(): Set<String>
}

fun interface ConfirmationGateway {
    suspend fun confirm(promptKo: String): Boolean
}

fun interface ToolEventSink {
    suspend fun emit(event: ToolRuntimeEvent)
}

data class ToolRuntimeEvent(val type: String, val safeMessage: String? = null)

data class ToolExecutionContext(
    val sessionId: String,
    val turnId: String,
    val localeTag: String,
    val deviceTimeZoneId: String,
    val permissionGateway: PermissionGateway = PermissionGateway { emptySet() },
    val confirmationGateway: ConfirmationGateway = ConfirmationGateway { false },
    val eventSink: ToolEventSink = ToolEventSink { },
)

data class SessionStateKey(val namespace: String, val key: String) {
    init {
        require(namespace.isNotBlank() && key.isNotBlank())
    }
}

data class SessionStateUpdate(
    val key: SessionStateKey,
    val schemaVersion: ContractVersion,
    val value: JsonObject?,
    val expiresAtEpochMillis: Long? = null,
)

data class StoredSessionState(
    val schemaVersion: ContractVersion,
    val value: JsonObject,
    val expiresAtEpochMillis: Long? = null,
)

@JvmInline
value class ToolErrorCode(val value: String)

data class ToolError(
    val code: ToolErrorCode,
    val safeMessageKo: String,
    val retryable: Boolean,
    val details: JsonObject? = null,
)

object StandardToolErrorCodes {
    val INVALID_ARGUMENTS = ToolErrorCode("tool.invalid_arguments")
    val TOOL_NOT_AVAILABLE = ToolErrorCode("tool.not_available")
    val TOOL_UNHEALTHY = ToolErrorCode("tool.unhealthy")
    val CONTRACT_VERSION_MISMATCH = ToolErrorCode("tool.contract_version_mismatch")
    val CONFIRMATION_REQUIRED = ToolErrorCode("policy.confirmation_required")
    val CONFIRMATION_REJECTED = ToolErrorCode("policy.confirmation_rejected")
    val PERMISSION_REQUIRED = ToolErrorCode("policy.permission_required")
    val PERMISSION_DENIED = ToolErrorCode("policy.permission_denied")
    val TIMEOUT = ToolErrorCode("tool.timeout")
    val CANCELLED = ToolErrorCode("tool.cancelled")
    val REPEATED_TOOL_CALL = ToolErrorCode("agent.repeated_tool_call")
    val MULTIPLE_TOOL_CALLS_NOT_ALLOWED = ToolErrorCode("agent.multiple_tool_calls_not_allowed")
    val TOOL_CALL_LIMIT_REACHED = ToolErrorCode("agent.tool_call_limit_reached")
    val PLUGIN_OUTPUT_CONTRACT_VIOLATION = ToolErrorCode("tool.output_contract_violation")
    val TOOL_EXECUTION_FAILED = ToolErrorCode("tool.execution_failed")
}

sealed interface ToolExecutionResult {
    val callId: String
    val capabilityId: ToolCapabilityId
    val contractVersion: ContractVersion
    val durationMillis: Long

    data class Success(
        override val callId: String,
        override val capabilityId: ToolCapabilityId,
        override val contractVersion: ContractVersion,
        override val durationMillis: Long,
        val data: JsonObject,
        val userMessageKo: String? = null,
        val sessionUpdates: List<SessionStateUpdate> = emptyList(),
    ) : ToolExecutionResult

    data class Failure(
        override val callId: String,
        override val capabilityId: ToolCapabilityId,
        override val contractVersion: ContractVersion,
        override val durationMillis: Long,
        val error: ToolError,
    ) : ToolExecutionResult
}

sealed interface ToolAvailability {
    data object Ready : ToolAvailability
    data class Unavailable(val reasonCode: String) : ToolAvailability
    data class Degraded(val reasonCode: String) : ToolAvailability
}

interface ToolSessionContextContributor {
    val namespace: String
    fun buildModelContext(state: Map<SessionStateKey, StoredSessionState>): JsonObject?
}

interface ToolPlugin {
    val implementationId: ToolImplementationId
    val contract: ToolContract
    val sessionContextContributor: ToolSessionContextContributor? get() = null

    suspend fun availability(): ToolAvailability
    suspend fun execute(request: ToolRequest, context: ToolExecutionContext): ToolExecutionResult
}

sealed interface TypedToolResult<out O : Any> {
    data class Success<O : Any>(
        val value: O,
        val userMessageKo: String? = null,
        val sessionUpdates: List<SessionStateUpdate> = emptyList(),
    ) : TypedToolResult<O>

    data class Failure(val error: ToolError) : TypedToolResult<Nothing>
}

abstract class TypedToolPlugin<I : Any, O : Any>(
    final override val implementationId: ToolImplementationId,
    final override val contract: ToolContract,
    private val inputCodec: ToolInputCodec<I>,
    private val outputCodec: ToolOutputCodec<O>,
) : ToolPlugin {
    init {
        require(contract.inputSchema == inputCodec.schema) { "Input schema and codec differ" }
        require(contract.outputSchema == outputCodec.schema) { "Output schema and codec differ" }
    }

    final override suspend fun execute(
        request: ToolRequest,
        context: ToolExecutionContext,
    ): ToolExecutionResult {
        val started = System.nanoTime()
        if (request.capabilityId != contract.capabilityId || request.contractVersion != contract.version) {
            return failure(request, started, ToolError(
                StandardToolErrorCodes.CONTRACT_VERSION_MISMATCH,
                "도구 버전이 일치하지 않습니다.",
                retryable = false,
            ))
        }
        val input = when (val decoded = inputCodec.decode(request.arguments)) {
            is DecodeResult.Success -> decoded.value
            is DecodeResult.Failure -> return failure(request, started, ToolError(
                StandardToolErrorCodes.INVALID_ARGUMENTS,
                decoded.safeMessageKo,
                retryable = false,
            ))
        }
        return when (val result = executeTyped(input, request, context)) {
            is TypedToolResult.Success -> ToolExecutionResult.Success(
                request.callId,
                request.capabilityId,
                request.contractVersion,
                elapsedMillis(started),
                outputCodec.encode(result.value),
                result.userMessageKo,
                result.sessionUpdates,
            )
            is TypedToolResult.Failure -> failure(request, started, result.error)
        }
    }

    protected abstract suspend fun executeTyped(
        input: I,
        request: ToolRequest,
        context: ToolExecutionContext,
    ): TypedToolResult<O>

    private fun failure(request: ToolRequest, started: Long, error: ToolError) = ToolExecutionResult.Failure(
        request.callId,
        request.capabilityId,
        request.contractVersion,
        elapsedMillis(started),
        error,
    )

    private fun elapsedMillis(started: Long): Long = (System.nanoTime() - started) / 1_000_000
}
