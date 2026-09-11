package com.hjp.tool.contract

import kotlinx.serialization.json.JsonObject

@JvmInline
value class ToolCapabilityId(val value: String) {
    init {
        require(value.matches(Regex("[a-z][a-z0-9_.-]{0,127}"))) { "Invalid capability id: $value" }
    }
}

@JvmInline
value class ToolImplementationId(val value: String) {
    init {
        require(value.isNotBlank()) { "Implementation id must not be blank" }
    }
}

data class ContractVersion(val major: Int, val minor: Int) {
    init {
        require(major >= 1 && minor >= 0) { "Invalid contract version: $major.$minor" }
    }

    override fun toString(): String = "$major.$minor"
}

enum class ToolEffect { READ_ONLY, EXTERNAL_UI, LOCAL_MUTATION, EXTERNAL_MUTATION }

enum class ConfirmationPolicy { NONE, BEFORE_EXECUTION, EXTERNAL_APP_CONFIRMATION }

enum class PiiLevel { NONE, BASIC_CONTACT, SENSITIVE_CONTACT }

data class ToolPresentation(
    val runningMessageKo: String,
    val successMessageKo: String,
    val unavailableMessageKo: String,
)

data class ToolContract(
    val capabilityId: ToolCapabilityId,
    val modelName: String,
    val version: ContractVersion,
    val description: String,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject,
    val effect: ToolEffect,
    val confirmationPolicy: ConfirmationPolicy,
    val inputPii: PiiLevel,
    val outputPii: PiiLevel,
    val requiredPermissions: Set<String> = emptySet(),
    val requiredDeviceCapabilities: Set<String> = emptySet(),
    val defaultTimeoutMillis: Long,
    val presentation: ToolPresentation,
    val tags: Set<String> = emptySet(),
) {
    init {
        require(modelName.matches(Regex("[a-z][a-z0-9_]{0,63}"))) { "Invalid model tool name: $modelName" }
        require(description.isNotBlank()) { "Tool description must not be blank" }
        require(defaultTimeoutMillis in 1..120_000L) { "Invalid timeout: $defaultTimeoutMillis" }
        require(presentation.runningMessageKo.isNotBlank())
        require(presentation.unavailableMessageKo.isNotBlank())
    }
}

sealed interface DecodeResult<out T> {
    data class Success<T>(val value: T) : DecodeResult<T>
    data class Failure(val safeMessageKo: String, val field: String? = null) : DecodeResult<Nothing>
}

interface ToolInputCodec<I : Any> {
    val schema: JsonObject
    fun decode(arguments: JsonObject): DecodeResult<I>
}

interface ToolOutputCodec<O : Any> {
    val schema: JsonObject
    fun encode(value: O): JsonObject
}
