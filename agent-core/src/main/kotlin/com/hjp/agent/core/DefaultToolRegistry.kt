package com.hjp.agent.core

import com.hjp.tool.contract.CatalogContext
import com.hjp.tool.contract.ToolAvailability
import com.hjp.tool.contract.ToolBinding
import com.hjp.tool.contract.ToolCatalogSnapshot
import com.hjp.tool.contract.ToolPlugin
import com.hjp.tool.contract.ToolRegistry
import kotlinx.coroutines.CancellationException

data class ToolImplementationCandidate(
    val plugin: ToolPlugin,
    val priority: Int = 0,
    val requiredCapabilities: Set<String> = emptySet(),
    val enabled: () -> Boolean = { true },
)

class DefaultToolRegistry(
    candidates: List<ToolImplementationCandidate>,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : ToolRegistry {
    private val candidates = candidates.toList()
    private val byImplementation = candidates.associateBy { it.plugin.implementationId }

    init {
        require(this.candidates.isNotEmpty()) { "At least one tool implementation is required" }
        require(byImplementation.size == this.candidates.size) { "Duplicate implementation id" }
        this.candidates.forEach { validateContract(it.plugin) }
    }

    override suspend fun snapshot(context: CatalogContext): ToolCatalogSnapshot {
        val eligible = candidates.filter { candidate ->
            candidate.enabled() &&
                context.deviceCapabilities.containsAll(candidate.requiredCapabilities) &&
                context.deviceCapabilities.containsAll(candidate.plugin.contract.requiredDeviceCapabilities) &&
                isReady(candidate.plugin)
        }
        val selected = eligible.groupBy { it.plugin.contract.capabilityId }
            .map { (capability, choices) ->
                val ordered = choices.sortedByDescending { it.priority }
                require(ordered.size == 1 || ordered[0].priority != ordered[1].priority) {
                    "Ambiguous primary implementation for ${capability.value}"
                }
                ordered.first().plugin
            }
            .sortedBy { it.contract.modelName }

        require(selected.map { it.contract.modelName }.distinct().size == selected.size) {
            "Duplicate model tool name in active catalog"
        }
        val bindings = selected.map { plugin ->
            ToolBinding(
                plugin.contract.capabilityId,
                plugin.contract.modelName,
                plugin.contract.version,
                plugin.implementationId,
            )
        }
        val contracts = selected.associate { it.contract.modelName to it.contract }
        val contractIdentity = selected.joinToString("|") { plugin ->
            with(plugin.contract) {
                listOf(modelName, version, description, JsonCanonicalizer.canonical(inputSchema),
                    JsonCanonicalizer.canonical(outputSchema)).joinToString(":")
            }
        }
        val bindingIdentity = bindings.joinToString("|") {
            "${it.capabilityId.value}:${it.contractVersion}:${it.implementationId.value}"
        }
        return ToolCatalogSnapshot(
            revision = JsonCanonicalizer.sha256(contractIdentity),
            bindingRevision = JsonCanonicalizer.sha256(bindingIdentity),
            createdAtEpochMillis = clockMillis(),
            bindings = bindings,
            contractsByModelName = contracts,
        )
    }

    override fun resolve(binding: ToolBinding): ToolPlugin? {
        val plugin = byImplementation[binding.implementationId]?.plugin ?: return null
        return plugin.takeIf {
            it.contract.capabilityId == binding.capabilityId &&
                it.contract.modelName == binding.modelName &&
                it.contract.version == binding.contractVersion
        }
    }

    private suspend fun isReady(plugin: ToolPlugin): Boolean = try {
        plugin.availability() is ToolAvailability.Ready
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        false
    }

    private fun validateContract(plugin: ToolPlugin) {
        val contract = plugin.contract
        require(contract.inputSchema["type"]?.toString()?.trim('"') == "object") {
            "${contract.modelName} input schema must describe an object"
        }
        require(contract.outputSchema["type"]?.toString()?.trim('"') == "object") {
            "${contract.modelName} output schema must describe an object"
        }
    }
}
