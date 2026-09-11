package com.hjp.tool.contract

data class CatalogContext(
    val sessionId: String,
    val localeTag: String,
    val grantedPermissions: Set<String> = emptySet(),
    val deviceCapabilities: Set<String> = emptySet(),
)

data class ToolBinding(
    val capabilityId: ToolCapabilityId,
    val modelName: String,
    val contractVersion: ContractVersion,
    val implementationId: ToolImplementationId,
)

data class ToolCatalogSnapshot(
    val revision: String,
    val bindingRevision: String,
    val createdAtEpochMillis: Long,
    val bindings: List<ToolBinding>,
    val contractsByModelName: Map<String, ToolContract>,
) {
    fun bindingFor(modelName: String): ToolBinding? = bindings.firstOrNull { it.modelName == modelName }
}

interface ToolRegistry {
    suspend fun snapshot(context: CatalogContext): ToolCatalogSnapshot
    fun resolve(binding: ToolBinding): ToolPlugin?
}
