package com.hjp.agent.core

import com.hjp.tool.contract.ConfirmationPolicy
import com.hjp.tool.contract.ContractVersion
import com.hjp.tool.contract.PiiLevel
import com.hjp.tool.contract.ToolCapabilityId
import com.hjp.tool.contract.ToolContract
import com.hjp.tool.contract.ToolEffect
import com.hjp.tool.contract.ToolPresentation
import java.io.File
import java.io.RandomAccessFile
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The context budget must follow the artifact's bytes, not its file name.
 *
 * Renaming `gemma-4-E2B-it.litertlm` to `hjp-agent.litertlm` changes nothing inside the file, and
 * naming an old artifact `gemma-4…` does not give it a larger context. Both directions are tested.
 */
class ModelDeploymentTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a gemma artifact renamed to the legacy name keeps the gemma budget`() {
        val file = sparseFile("hjp-agent.litertlm", GEMMA_SIZE)

        val deployment = ModelDeploymentResolver.resolve(file)

        assertEquals("gemma-4-E2B-it", deployment.artifactId)
        assertEquals(ArtifactIdentification.KNOWN_ARTIFACT_SIZE, deployment.identifiedBy)
        assertEquals(3_072, deployment.appContextLimitTokens)
        assertEquals(null, deployment.declaredNativeContextTokens)
    }

    @Test
    fun `a legacy artifact renamed to a gemma name keeps the legacy limit`() {
        val file = sparseFile("gemma-4-E2B-it.litertlm", LEGACY_SIZE)

        val deployment = ModelDeploymentResolver.resolve(file)

        assertEquals("hjp-agent-legacy", deployment.artifactId)
        assertEquals(1_024, deployment.appContextLimitTokens)
        assertEquals(1_024, deployment.declaredNativeContextTokens)
    }

    @Test
    fun `an unidentified artifact falls back to the smallest known limit`() {
        val file = sparseFile("something-else.litertlm", 12_345L)

        val deployment = ModelDeploymentResolver.resolve(file)

        assertEquals(ArtifactIdentification.UNIDENTIFIED, deployment.identifiedBy)
        assertEquals(ModelDeploymentResolver.FALLBACK_CONTEXT_TOKENS, deployment.appContextLimitTokens)
        assertEquals(null, deployment.declaredNativeContextTokens)
    }

    @Test
    fun `a manifest is honoured only when its declared size matches the bytes on disk`() {
        val file = sparseFile("staged.litertlm", 4_096L)
        val honest = ModelDeploymentManifest("custom-artifact", 4_096L, null, 2_048)
        val lying = ModelDeploymentManifest("custom-artifact", 999_999L, null, 8_192)

        assertEquals(2_048, ModelDeploymentResolver.resolve(file, honest).appContextLimitTokens)
        assertEquals(
            ModelDeploymentResolver.FALLBACK_CONTEXT_TOKENS,
            ModelDeploymentResolver.resolve(file, lying).appContextLimitTokens,
        )
    }

    @Test
    fun `a missing model is reported instead of silently using a budget`() {
        val deployment = ModelDeploymentResolver.resolve(File(folder.root, "absent.litertlm"))

        assertEquals(ArtifactIdentification.MISSING, deployment.identifiedBy)
        assertTrue(!deployment.usable)
        val result = ContextPreflight.check(deployment, "system", "tools")
        assertTrue(result is ContextPreflightResult.Failure)
    }

    @Test
    fun `preflight fails when the tool catalog cannot fit the artifact budget`() {
        val deployment = ModelDeploymentResolver.resolve(sparseFile("hjp.litertlm", LEGACY_SIZE))
        val catalog = ContextPreflight.toolCatalogText(List(6) { contract("tool_$it") })

        val result = ContextPreflight.check(deployment, LONG_SYSTEM_INSTRUCTION, catalog)

        val failure = result as ContextPreflightResult.Failure
        assertTrue(failure.reasonKo.contains("도구 목록"))
        assertEquals("hjp-agent-legacy", failure.diagnostics["artifact_id"])
        assertTrue(failure.diagnostics.getValue("fixed_cost_tokens").toInt() > 1_024)
    }

    @Test
    fun `preflight passes and reports the remaining dynamic allowance`() {
        val deployment = ModelDeploymentResolver.resolve(sparseFile("gemma.litertlm", GEMMA_SIZE))
        val catalog = ContextPreflight.toolCatalogText(List(6) { contract("tool_$it") })

        val result = ContextPreflight.check(deployment, LONG_SYSTEM_INSTRUCTION, catalog)

        val ok = result as ContextPreflightResult.Ok
        assertTrue("allowance=${ok.dynamicInputAllowanceTokens}", ok.dynamicInputAllowanceTokens > 0)
    }

    @Test
    fun `diagnostics never carry a file path or contact data`() {
        val deployment = ModelDeploymentResolver.resolve(sparseFile("gemma.litertlm", GEMMA_SIZE))

        val summary = deployment.diagnosticSummary()

        assertTrue(summary.values.none { it.contains("/") })
        assertTrue(summary.values.none { it.contains("@") })
        assertTrue(summary.containsKey("app_limit_rationale"))
    }

    @Test
    fun `a known artifact is verified by size and digest together`() {
        val file = sparseFile("hjp-agent.litertlm", GEMMA_SIZE)

        val deployment = ModelDeploymentResolver.resolve(file, digestProvider = { GEMMA_SHA })

        assertEquals(ArtifactIdentification.KNOWN_ARTIFACT_VERIFIED, deployment.identifiedBy)
        assertEquals("gemma-4-E2B-it", deployment.artifactId)
        assertEquals(3_072, deployment.appContextLimitTokens)
        assertTrue(deployment.usable)
    }

    @Test
    fun `official policy accepts only the gemma artifact with its verified digest`() {
        val file = sparseFile("hjp-agent.litertlm", GEMMA_SIZE)

        val deployment = ModelDeploymentResolver.resolve(
            file,
            digestProvider = { GEMMA_SHA },
            verificationPolicy = ArtifactVerificationPolicy.REQUIRE_OFFICIAL_GENERATIVE_ARTIFACT,
        )

        assertEquals(ArtifactIdentification.KNOWN_ARTIFACT_VERIFIED, deployment.identifiedBy)
        assertEquals(ModelDeploymentResolver.OFFICIAL_GENERATIVE_ARTIFACT_ID, deployment.artifactId)
        assertEquals(GEMMA_SHA, deployment.verifiedSha256)
        assertTrue(deployment.usable)
    }

    @Test
    fun `official policy refuses the legacy artifact even when its known size matches`() {
        val file = sparseFile("hjp-agent.litertlm", LEGACY_SIZE)

        val deployment = ModelDeploymentResolver.resolve(
            file,
            digestProvider = { error("legacy artifact must not be trusted by size") },
            verificationPolicy = ArtifactVerificationPolicy.REQUIRE_OFFICIAL_GENERATIVE_ARTIFACT,
        )

        assertEquals("hjp-agent-legacy", deployment.artifactId)
        assertEquals(ArtifactIdentification.REJECTED_UNVERIFIED_ARTIFACT, deployment.identifiedBy)
        assertTrue(!deployment.usable)
        assertTrue(ContextPreflight.check(deployment, "system", "tools") is ContextPreflightResult.Failure)
    }

    @Test
    fun `official policy refuses gemma size when no digest was verified`() {
        val file = sparseFile("hjp-agent.litertlm", GEMMA_SIZE)

        val deployment = ModelDeploymentResolver.resolve(
            file,
            verificationPolicy = ArtifactVerificationPolicy.REQUIRE_OFFICIAL_GENERATIVE_ARTIFACT,
        )

        assertEquals("gemma-4-E2B-it", deployment.artifactId)
        assertEquals(ArtifactIdentification.REJECTED_UNVERIFIED_ARTIFACT, deployment.identifiedBy)
        assertTrue(!deployment.usable)
    }

    @Test
    fun `the right size with the wrong bytes is refused instead of trusted`() {
        val file = sparseFile("gemma-4-E2B-it.litertlm", GEMMA_SIZE)

        val deployment = ModelDeploymentResolver.resolve(file, digestProvider = { "0".repeat(64) })

        assertEquals(ArtifactIdentification.REJECTED_DIGEST_MISMATCH, deployment.identifiedBy)
        assertTrue(!deployment.usable)
        assertEquals(ModelDeploymentResolver.FALLBACK_CONTEXT_TOKENS, deployment.appContextLimitTokens)
    }

    @Test
    fun `a refused artifact never reaches inference`() {
        val file = sparseFile("gemma-4-E2B-it.litertlm", GEMMA_SIZE)
        val deployment = ModelDeploymentResolver.resolve(file, digestProvider = { "0".repeat(64) })

        val result = ContextPreflight.check(deployment, "system", "tools")

        assertTrue(result is ContextPreflightResult.Failure)
    }

    @Test
    fun `a matching digest cannot rescue an artifact of the wrong size`() {
        val file = sparseFile("gemma-4-E2B-it.litertlm", GEMMA_SIZE - 1)

        val deployment = ModelDeploymentResolver.resolve(file, digestProvider = { GEMMA_SHA })

        assertEquals(ArtifactIdentification.UNIDENTIFIED, deployment.identifiedBy)
        assertEquals(ModelDeploymentResolver.FALLBACK_CONTEXT_TOKENS, deployment.appContextLimitTokens)
    }

    @Test
    fun `an unreadable artifact fails safely rather than guessing`() {
        val deployment = ModelDeploymentResolver.resolve(
            File(folder.root, "absent.litertlm"),
            digestProvider = { GEMMA_SHA },
        )

        assertEquals(ArtifactIdentification.MISSING, deployment.identifiedBy)
        assertTrue(!deployment.usable)
    }

    @Test
    fun `the digest of one file is computed once and recomputed when the bytes change`() {
        val file = sparseFile("artifact.litertlm", 1_024L)
        var computations = 0
        val caching = CachingArtifactDigestProvider { computations += 1; "abc" }

        caching.sha256(file)
        caching.sha256(file)
        assertEquals(1, computations)

        // Replacing the file in place keeps the path; only the identity change forces a re-read.
        RandomAccessFile(file, "rw").use { it.setLength(2_048L) }
        caching.sha256(file)
        assertEquals(2, computations)
    }

    /** Creates a file of the exact length without writing gigabytes. */
    private fun sparseFile(name: String, size: Long): File {
        val file = File(folder.root, name)
        RandomAccessFile(file, "rw").use { it.setLength(size) }
        return file
    }

    private fun contract(name: String) = ToolContract(
        ToolCapabilityId("fake.$name"),
        name,
        ContractVersion(1, 0),
        "이 도구는 저장된 명함 데이터를 다루며 여러 인자를 받습니다. 설명은 카탈로그 비용을 반영합니다.",
        buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("query", buildJsonObject { put("type", "string") })
                put("limit", buildJsonObject { put("type", "integer") })
                put("purpose", buildJsonObject { put("type", "string") })
            })
        },
        buildJsonObject { put("type", "object") },
        ToolEffect.READ_ONLY,
        ConfirmationPolicy.NONE,
        PiiLevel.NONE,
        PiiLevel.NONE,
        defaultTimeoutMillis = 1_000,
        presentation = ToolPresentation("실행 중", "완료", "사용 불가"),
    )

    private companion object {
        const val GEMMA_SIZE = 2_588_147_712L
        const val LEGACY_SIZE = 284_426_240L
        const val GEMMA_SHA = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
        val LONG_SYSTEM_INSTRUCTION = "당신은 Android 기기 안에서만 동작하는 HJP 명함 에이전트입니다. ".repeat(12)
    }
}
