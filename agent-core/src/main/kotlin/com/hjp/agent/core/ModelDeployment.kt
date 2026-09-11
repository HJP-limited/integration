package com.hjp.agent.core

import java.io.File

/**
 * How the deployed artifact was identified.
 *
 * A file *name* proves nothing: renaming `gemma-4-E2B-it.litertlm` to `hjp-agent.litertlm` changes
 * no byte inside it, and naming an old artifact `gemma-4…` does not give it a larger context.
 * Identification therefore uses the artifact's own bytes, or an explicit deployment manifest.
 */
enum class ArtifactIdentification {
    /** Byte size matched a known artifact entry, and no digest was available to confirm it. */
    KNOWN_ARTIFACT_SIZE,

    /** Byte size *and* SHA-256 both matched a known artifact entry. */
    KNOWN_ARTIFACT_VERIFIED,

    /**
     * The size matched a known artifact but the bytes did not. Something other than the expected
     * artifact is sitting at that path, so it is refused rather than run with that artifact's budget.
     */
    REJECTED_DIGEST_MISMATCH,

    /** The deployment policy requires the verified official artifact, but this file is not it. */
    REJECTED_UNVERIFIED_ARTIFACT,

    /** An explicit manifest next to the model declared the artifact, and its size matched. */
    DEPLOYMENT_MANIFEST,

    /** Nothing identified the artifact. The conservative fallback budget applies. */
    UNIDENTIFIED,

    /** The model file is missing or unreadable. */
    MISSING,
}

/**
 * One known `.litertlm` artifact.
 *
 * [declaredNativeContextTokens] is the limit the artifact itself declares in its `LlmMetadata`
 * (`litert-lm-peek --litertlm_file <file>`). `null` means the artifact declares none — which is not
 * the same as "unlimited", so it must never be reported as a model maximum.
 */
data class KnownModelArtifact(
    val artifactId: String,
    val sizeBytes: Long,
    val sha256: String?,
    val declaredNativeContextTokens: Int?,
    val appContextLimitTokens: Int,
    val appLimitRationale: String,
)

/**
 * The resolved model deployment: which artifact is actually on disk and what context budget the app
 * is allowed to use with it.
 */
data class ModelDeployment(
    val modelFile: File,
    val artifactId: String,
    val identifiedBy: ArtifactIdentification,
    val actualSizeBytes: Long,
    val declaredNativeContextTokens: Int?,
    val appContextLimitTokens: Int,
    val appLimitRationale: String,
    val budget: ContextBudget,
    /** Set only when a digest was actually computed for this file. */
    val verifiedSha256: String? = null,
) {
    val usable: Boolean
        get() = identifiedBy != ArtifactIdentification.MISSING &&
            identifiedBy != ArtifactIdentification.REJECTED_DIGEST_MISMATCH &&
            identifiedBy != ArtifactIdentification.REJECTED_UNVERIFIED_ARTIFACT

    /** Non-PII summary safe for debug diagnostics. */
    fun diagnosticSummary(): Map<String, String> = mapOf(
        "artifact_id" to artifactId,
        "identified_by" to identifiedBy.name,
        "size_bytes" to actualSizeBytes.toString(),
        "declared_native_context_tokens" to (declaredNativeContextTokens?.toString() ?: "none"),
        "app_context_limit_tokens" to appContextLimitTokens.toString(),
        "app_limit_rationale" to appLimitRationale,
        "sha256" to (verifiedSha256 ?: "not computed"),
        "tool_catalog_reserve" to budget.reservedForToolCatalogTokens.toString(),
        "output_reserve" to budget.reservedForResponseTokens.toString(),
        "safety_margin" to budget.safetyMarginTokens.toString(),
    )
}

/** Controls whether compatibility artifacts may run or only the verified production artifact may. */
enum class ArtifactVerificationPolicy {
    /** Preserve historical resolver behavior for offline inspection and compatibility tests. */
    COMPATIBILITY,

    /** Accept only the official Gemma artifact after both its byte size and SHA-256 are verified. */
    REQUIRE_OFFICIAL_GENERATIVE_ARTIFACT,
}

/**
 * Supplies an artifact's SHA-256.
 *
 * Injected rather than hard-wired so a test can describe a 2.6 GB artifact without writing one, and
 * so the expensive real hash can be computed once at startup instead of on every turn.
 */
fun interface ArtifactDigestProvider {
    /** SHA-256 in lowercase hex, or null when it cannot be computed. */
    fun sha256(file: File): String?

    companion object {
        /** No digest available; identification falls back to size alone. */
        val NONE = ArtifactDigestProvider { null }
    }
}

/** Streams the file so a multi-gigabyte artifact is never held in memory. */
object FileArtifactDigestProvider : ArtifactDigestProvider {
    override fun sha256(file: File): String? = try {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: java.io.IOException) {
        null
    }
}

/**
 * Computes a digest once per distinct file.
 *
 * The identity is the path together with the length and modification time: a file that was replaced
 * in place keeps its path, so caching on the path alone would keep trusting a hash for bytes that
 * are no longer there.
 */
class CachingArtifactDigestProvider(
    private val delegate: ArtifactDigestProvider = FileArtifactDigestProvider,
) : ArtifactDigestProvider {
    private data class Identity(val path: String, val length: Long, val lastModified: Long)

    private val cache = java.util.concurrent.ConcurrentHashMap<Identity, String>()

    override fun sha256(file: File): String? {
        if (!file.isFile || !file.canRead()) return null
        val identity = Identity(file.absolutePath, file.length(), file.lastModified())
        cache[identity]?.let { return it }
        val computed = delegate.sha256(file) ?: return null
        cache[identity] = computed
        return computed
    }

    fun cachedEntries(): Int = cache.size
}

object ModelDeploymentResolver {
    const val OFFICIAL_GENERATIVE_ARTIFACT_ID = "gemma-4-E2B-it"
    const val OFFICIAL_GENERATIVE_ARTIFACT_SHA256 =
        "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"

    /**
     * Artifacts this project has actually inspected. Sizes are the measured file lengths;
     * `declaredNativeContextTokens` comes from `litert-lm-peek` output recorded in
     * `docs/AGENT_MODEL_RECOVERY.md` and `docs/AGENT_PRE_DEVICE_HARDENING_REPORT.md`.
     */
    val KNOWN_ARTIFACTS = listOf(
        KnownModelArtifact(
            artifactId = OFFICIAL_GENERATIVE_ARTIFACT_ID,
            sizeBytes = 2_588_147_712L,
            sha256 = OFFICIAL_GENERATIVE_ARTIFACT_SHA256,
            declaredNativeContextTokens = null,
            appContextLimitTokens = 3_072,
            appLimitRationale = "artifact declares no limit; 3072 is an app-chosen safe budget",
        ),
        KnownModelArtifact(
            artifactId = "hjp-agent-legacy",
            sizeBytes = 284_426_240L,
            sha256 = null,
            declaredNativeContextTokens = 1_024,
            appContextLimitTokens = 1_024,
            appLimitRationale = "artifact metadata declares max_num_tokens=1024",
        ),
    )

    /** Applied when nothing identifies the artifact. Deliberately the smallest known limit. */
    const val FALLBACK_CONTEXT_TOKENS = 1_024
    private const val FALLBACK_RATIONALE =
        "artifact not identified; smallest known limit applied as a conservative fallback"

    fun resolve(
        modelFile: File,
        manifest: ModelDeploymentManifest? = null,
        digestProvider: ArtifactDigestProvider = ArtifactDigestProvider.NONE,
        verificationPolicy: ArtifactVerificationPolicy = ArtifactVerificationPolicy.COMPATIBILITY,
    ): ModelDeployment = resolveCompatible(modelFile, manifest, digestProvider)
        .enforce(verificationPolicy)

    private fun resolveCompatible(
        modelFile: File,
        manifest: ModelDeploymentManifest?,
        digestProvider: ArtifactDigestProvider,
    ): ModelDeployment {
        if (!modelFile.isFile || !modelFile.canRead()) {
            return ModelDeployment(
                modelFile = modelFile,
                artifactId = "missing",
                identifiedBy = ArtifactIdentification.MISSING,
                actualSizeBytes = 0,
                declaredNativeContextTokens = null,
                appContextLimitTokens = FALLBACK_CONTEXT_TOKENS,
                appLimitRationale = "model file is missing or unreadable",
                budget = budgetFor(FALLBACK_CONTEXT_TOKENS),
            )
        }
        val size = modelFile.length()
        // Size is checked first and is authoritative. A digest that matches a known artifact while
        // the length does not cannot identify anything: the bytes on disk are demonstrably not that
        // artifact, whatever a hash claims about them.
        val bySize = KNOWN_ARTIFACTS.firstOrNull { it.sizeBytes == size }
        if (bySize != null) {
            val expected = bySize.sha256
            val actual = if (expected == null) null else digestProvider.sha256(modelFile)
            if (expected != null && actual != null && !actual.equals(expected, ignoreCase = true)) {
                return ModelDeployment(
                    modelFile = modelFile,
                    artifactId = bySize.artifactId,
                    identifiedBy = ArtifactIdentification.REJECTED_DIGEST_MISMATCH,
                    actualSizeBytes = size,
                    declaredNativeContextTokens = null,
                    appContextLimitTokens = FALLBACK_CONTEXT_TOKENS,
                    appLimitRationale =
                        "size matched ${bySize.artifactId} but SHA-256 did not; artifact refused",
                    budget = budgetFor(FALLBACK_CONTEXT_TOKENS),
                    verifiedSha256 = actual,
                )
            }
            val identification = if (expected != null && actual != null) {
                ArtifactIdentification.KNOWN_ARTIFACT_VERIFIED
            } else {
                ArtifactIdentification.KNOWN_ARTIFACT_SIZE
            }
            return bySize.toDeployment(modelFile, size, identification, actual)
        }

        // A manifest is trusted only when the bytes it describes are the bytes on disk.
        if (manifest != null && manifest.sizeBytes == size) {
            return ModelDeployment(
                modelFile = modelFile,
                artifactId = manifest.artifactId,
                identifiedBy = ArtifactIdentification.DEPLOYMENT_MANIFEST,
                actualSizeBytes = size,
                declaredNativeContextTokens = manifest.declaredNativeContextTokens,
                appContextLimitTokens = manifest.appContextLimitTokens,
                appLimitRationale = "declared by deployment manifest",
                budget = budgetFor(manifest.appContextLimitTokens),
            )
        }
        return ModelDeployment(
            modelFile = modelFile,
            artifactId = "unidentified",
            identifiedBy = ArtifactIdentification.UNIDENTIFIED,
            actualSizeBytes = size,
            declaredNativeContextTokens = null,
            appContextLimitTokens = FALLBACK_CONTEXT_TOKENS,
            appLimitRationale = FALLBACK_RATIONALE,
            budget = budgetFor(FALLBACK_CONTEXT_TOKENS),
        )
    }

    fun budgetFor(contextTokens: Int): ContextBudget = ContextBudget(maxPromptTokens = contextTokens)

    private fun ModelDeployment.enforce(policy: ArtifactVerificationPolicy): ModelDeployment {
        if (policy == ArtifactVerificationPolicy.COMPATIBILITY) return this
        if (identifiedBy == ArtifactIdentification.MISSING ||
            identifiedBy == ArtifactIdentification.REJECTED_DIGEST_MISMATCH
        ) {
            return this
        }
        if (identifiedBy == ArtifactIdentification.KNOWN_ARTIFACT_VERIFIED &&
            artifactId == OFFICIAL_GENERATIVE_ARTIFACT_ID &&
            verifiedSha256.equals(OFFICIAL_GENERATIVE_ARTIFACT_SHA256, ignoreCase = true)
        ) {
            return this
        }
        return copy(
            identifiedBy = ArtifactIdentification.REJECTED_UNVERIFIED_ARTIFACT,
            declaredNativeContextTokens = null,
            appContextLimitTokens = FALLBACK_CONTEXT_TOKENS,
            appLimitRationale =
                "official physical-device deployment requires verified $OFFICIAL_GENERATIVE_ARTIFACT_ID SHA-256",
            budget = budgetFor(FALLBACK_CONTEXT_TOKENS),
        )
    }

    private fun KnownModelArtifact.toDeployment(
        file: File,
        size: Long,
        identification: ArtifactIdentification,
        verifiedSha256: String? = null,
    ) = ModelDeployment(
        modelFile = file,
        artifactId = artifactId,
        identifiedBy = identification,
        actualSizeBytes = size,
        declaredNativeContextTokens = declaredNativeContextTokens,
        appContextLimitTokens = appContextLimitTokens,
        appLimitRationale = appLimitRationale,
        budget = budgetFor(appContextLimitTokens),
        verifiedSha256 = verifiedSha256,
    )
}

/** Optional `model-deployment.json` placed next to the artifact by whoever staged it. */
data class ModelDeploymentManifest(
    val artifactId: String,
    val sizeBytes: Long,
    val declaredNativeContextTokens: Int?,
    val appContextLimitTokens: Int,
)

sealed interface ContextPreflightResult {
    data class Ok(val dynamicInputAllowanceTokens: Int) : ContextPreflightResult

    /** The fixed cost alone does not fit. Running would silently truncate or corrupt the prompt. */
    data class Failure(
        val reasonKo: String,
        val diagnostics: Map<String, String>,
    ) : ContextPreflightResult
}

/**
 * Checks the invariant before a model session opens:
 *
 * `system + tool catalog + minimum user input + output reserve + safety margin <= context limit`
 */
object ContextPreflight {
    const val MIN_USER_INPUT_TOKENS = 64

    fun check(
        deployment: ModelDeployment,
        systemInstruction: String,
        toolCatalogText: String,
        estimator: TokenEstimator = CalibratedGemmaTokenEstimator,
    ): ContextPreflightResult {
        val budget = deployment.budget
        val systemTokens = estimator.estimate(systemInstruction)
        val catalogTokens = estimator.estimate(toolCatalogText)
        val fixed = systemTokens + catalogTokens + MIN_USER_INPUT_TOKENS +
            budget.reservedForResponseTokens + budget.safetyMarginTokens
        val diagnostics = deployment.diagnosticSummary() + mapOf(
            "system_tokens" to systemTokens.toString(),
            "tool_catalog_tokens" to catalogTokens.toString(),
            "min_user_input_tokens" to MIN_USER_INPUT_TOKENS.toString(),
            "fixed_cost_tokens" to fixed.toString(),
        )
        if (deployment.identifiedBy == ArtifactIdentification.MISSING) {
            return ContextPreflightResult.Failure(
                "온디바이스 모델 파일을 찾지 못했습니다.",
                diagnostics,
            )
        }
        if (deployment.identifiedBy == ArtifactIdentification.REJECTED_DIGEST_MISMATCH) {
            return ContextPreflightResult.Failure(
                "모델 파일이 예상한 아티팩트와 일치하지 않습니다. 배포된 파일을 확인해 주세요.",
                diagnostics,
            )
        }
        if (deployment.identifiedBy == ArtifactIdentification.REJECTED_UNVERIFIED_ARTIFACT) {
            return ContextPreflightResult.Failure(
                "SHA-256으로 검증된 공식 온디바이스 모델만 사용할 수 있습니다.",
                diagnostics,
            )
        }
        if (fixed > deployment.appContextLimitTokens) {
            return ContextPreflightResult.Failure(
                "현재 모델 설정으로는 도구 목록과 응답 공간을 함께 담을 수 없습니다. " +
                    "필요 ${fixed}토큰 > 허용 ${deployment.appContextLimitTokens}토큰.",
                diagnostics,
            )
        }
        return ContextPreflightResult.Ok(deployment.appContextLimitTokens - fixed)
    }

    /** Approximates what the gateway registers as native tools, for budget purposes only. */
    fun toolCatalogText(contracts: Collection<com.hjp.tool.contract.ToolContract>): String =
        contracts.sortedBy { it.modelName }.joinToString("\n") { contract ->
            "${contract.modelName} ${contract.description} ${contract.inputSchema}"
        }
}
