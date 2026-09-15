package com.example.hjp

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.hjp.data.HjpDatabase
import com.example.hjp.data.RoomBusinessCardRepository
import com.example.hjp.search.AndroidEmbeddingGemmaEngine
import com.hjp.agent.contract.AgentModelGateway
import com.hjp.agent.core.AgentKernel
import com.hjp.agent.core.AgentKernelMode
import com.hjp.agent.core.AgentTurnEngine
import com.hjp.agent.core.AgentTurnPolicy
import com.hjp.agent.core.ArtifactVerificationPolicy
import com.hjp.agent.core.AgentRuntimeEnvironment
import com.hjp.agent.core.AgentSessionManager
import com.hjp.agent.core.ContextPreflight
import com.hjp.agent.core.ContextPreflightResult
import com.hjp.agent.core.ConversationHistoryStrategy
import com.hjp.agent.core.DefaultToolExecutor
import com.hjp.agent.core.DefaultToolObservationMapper
import com.hjp.agent.core.DefaultToolPolicyEngine
import com.hjp.agent.core.DefaultToolRegistry
import com.hjp.agent.core.InMemoryAgentSessionStore
import com.hjp.agent.core.ModelContextSelector
import com.hjp.agent.core.ModelDeployment
import com.hjp.agent.core.CachingArtifactDigestProvider
import com.hjp.agent.core.ModelDeploymentResolver
import com.hjp.agent.core.StructuredAgentKernel
import com.hjp.agent.core.ToolImplementationCandidate
import com.hjp.agent.litert.LiteRtAgentModelGateway
import com.hjp.agent.litert.LiteRtBackendPreference
import com.hjp.agent.litert.LiteRtStructuredAgentModelGateway
import com.hjp.tool.android.AndroidCalendarComposerBackend
import com.hjp.tool.android.AndroidMessageComposerBackend
import com.hjp.tool.android.CreateCalendarEventPlugin
import com.hjp.tool.android.OpenComposePlugin
import com.hjp.tool.contact.UpdateBusinessCardPlugin
import com.hjp.tool.contact.GetContactPlugin
import com.hjp.tool.contact.RyeongContactSearchBackend
import com.hjp.tool.contact.CountContactsPlugin
import com.hjp.tool.contact.SearchContactsPlugin
import com.hjp.tool.contract.ConfirmationGateway
import com.hjp.tool.contract.PermissionGateway
import com.hjp.tool.contract.ToolEventSink
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.datetime.GetCurrentDateTimePlugin
import com.hjp.searchlookup.OnDeviceEmbeddingEngine
import java.io.File
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CompletableDeferred

class AppContainer(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val modelRoot = appContext.getExternalFilesDir("models") ?: File(appContext.filesDir, "models")

    /**
     * 생성 모델 파일. **이름을 하나로 못 박지 않는다.**
     *
     * 예전에는 `hjp-agent.litertlm` 만 봤는데, 우리가 가진 파일은 `gemma-4-E2B-it.litertlm`
     * 이다. 폰에 올바른 모델을 밀어 넣어도 앱은 다른 이름을 찾고 있었고, 아무 말 없이
     * "모델 없음"이 됐다 — 폰 앞에서 한참 헤매게 되는 종류의 실패다.
     *
     * 그래서 규칙 이름을 **먼저** 보고, 없으면 모델 폴더의 `.litertlm` 중 가장 큰 것을 쓴다.
     * 이름으로 고르는 게 위험하지 않은 이유는 뒤에 내용 검증이 있기 때문이다:
     * [ModelDeploymentResolver] 가 바이트 크기와 SHA-256 으로 공식 아티팩트인지 확인하므로,
     * 엉뚱한 파일을 집으면 여기서 통과하지 못하고 "모델 없음"으로 정확히 보고된다.
     * 가장 큰 것을 고르는 것도 같은 이유로 안전하다 — 크기가 곧 자격이 아니라, 후보를
     * 하나 정하는 방법일 뿐이다.
     */
    val modelFile: File = resolveGenerativeArtifact(modelRoot)

    /**
     * One counter set for the whole composition.
     *
     * The model boundary, the artefact loader and the search backend all write into it, so a report
     * reads one object rather than assembling a claim from three places that could disagree. It
     * holds counts and durations only; nothing here can carry a name, an address or a prompt.
     */
    val runtimeCounters = com.hjp.agent.contract.AgentRuntimeCounters()

    private val confirmationCoordinator = AgentConfirmationCoordinator()
    private val database = HjpDatabase.getInstance(appContext)
    // 화면(명함 목록·홈·OCR 저장)도 같은 저장소를 쓴다. 도구가 보는 데이터와 화면이 보는
    // 데이터가 갈라지면, 방금 저장한 명함이 목록에는 있는데 에이전트는 못 찾는 일이 생긴다.
    val contactRepository = RoomBusinessCardRepository(appContext, database.businessCardDao())
    private val embeddingGemmaEngine = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidEmbeddingGemmaEngine(appContext)
    }
    private val rawContactBackend = RyeongContactSearchBackend(
        repository = contactRepository,
        embeddingEngineFactory = {
            OnDeviceEmbeddingEngine.required(embeddingGemmaEngine.value)
        },
        requireModelBacked = true,
        diagnostics = { event ->
            // The backend's own report of what it did, turned into counts. Read from its declared
            // mode rather than inferred from how many results came back: a semantic pass that
            // returns nothing still ran, and a keyword pass that returns plenty did not.
            runtimeCounters.recordRetrieval(
                semanticUsed = event.mode != "KEYWORD_ONLY",
                candidateCount = event.keywordResultCount + event.semanticResultCount,
                semanticResultCount = event.semanticResultCount,
                keywordFallback = event.fallbackUsed,
                elapsedMillis = event.elapsedMillis,
            )
            if (event.initializationMillis > 0) {
                runtimeCounters.recordSemanticInitializationAttempt()
                if (event.mode != "KEYWORD_ONLY") runtimeCounters.recordSemanticInitializationSuccess()
                else runtimeCounters.recordSemanticInitializationFailure()
            }
            Log.i(
                "HjpContactSearch",
                "mode=${event.mode} fallback=${event.fallbackUsed} " +
                    "reason=${event.fallbackReason} engine=${event.engine} " +
                    "keyword=${event.keywordResultCount} semantic=${event.semanticResultCount} " +
                    "ranked=${event.rankings} init_ms=${event.initializationMillis} " +
                    "query_embedding_ms=${event.queryEmbeddingMillis} " +
                    "elapsed_ms=${event.elapsedMillis}",
            )
        },
    )
    /**
     * 도구가 찾은 명함을 화면이 그대로 쓰도록 기록한다. 감싸기만 하고 결과는 바꾸지 않는다.
     */
    val contactBackend = RecordingContactSearchBackend(rawContactBackend)

    /**
     * Which spans of an utterance name somebody in the store. Consulted by the kernel before
     * routing, so "박수빈씨 회사가 어디야?" is a lookup rather than a sentence with no route.
     */
    private val contactDirectory = RepositoryContactDirectory(contactRepository)

    /** Completes an OCR insert by rebuilding every card-derived index and persisting its vector. */
    suspend fun refreshAfterCardAdded() {
        contactDirectory.invalidate()
        rawContactBackend.refreshAfterCardChange()
    }
    private val plugins = listOf(
        SearchContactsPlugin(contactBackend),
        CountContactsPlugin(contactBackend),
        GetContactPlugin(contactBackend),
        UpdateBusinessCardPlugin(
            contactRepository,
            onUpdated = {
                // An edit can rename a card, and the name index is what decides whether a sentence
                // is about a person. Leaving it stale would keep answering with the old name.
                contactDirectory.invalidate()
                // Do not report completion until the updated document vector is persisted and live.
                rawContactBackend.refreshAfterCardChange()
            },
        ),
        CreateCalendarEventPlugin(AndroidCalendarComposerBackend(appContext)),
        OpenComposePlugin(AndroidMessageComposerBackend(appContext)),
        GetCurrentDateTimePlugin(),
    )
    private val registry = DefaultToolRegistry(plugins.map { ToolImplementationCandidate(it) })
    private val emulatorCompatibilityMode = isAndroidEmulator()
    private val sessionStore = InMemoryAgentSessionStore()

    /**
     * Hashing a 2.6 GB artifact is a startup cost, not a per-turn one. The cache keys on path,
     * length and modification time, so a file swapped in place is hashed again rather than trusted.
     */
    private val artifactDigests = CachingArtifactDigestProvider()

    /**
     * The context budget follows the artifact's own bytes, never its file name. Renaming a file
     * changes nothing inside it, so an unidentified artifact falls back to the smallest known limit
     * rather than assuming the largest.
     */
    val deployment: ModelDeployment = ModelDeploymentResolver.resolve(
        modelFile,
        digestProvider = artifactDigests,
        verificationPolicy = if (emulatorCompatibilityMode) {
            ArtifactVerificationPolicy.COMPATIBILITY
        } else {
            ArtifactVerificationPolicy.REQUIRE_OFFICIAL_GENERATIVE_ARTIFACT
        },
    )
    private val contextSelector = ModelContextSelector(deployment.budget)

    private fun preflight(snapshot: com.hjp.tool.contract.ToolCatalogSnapshot): ContextPreflightResult =
        if (emulatorCompatibilityMode) {
            // The emulator path never loads LiteRT, so an artifact budget does not apply.
            ContextPreflightResult.Ok(Int.MAX_VALUE)
        } else {
            ContextPreflight.check(
                deployment,
                SYSTEM_INSTRUCTION,
                ContextPreflight.toolCatalogText(snapshot.contractsByModelName.values),
            )
        }

    // Wrapped, not replaced. The decorator forwards every call unchanged and returns the delegate's
    // own value, so nothing about routing, tool results or the answer differs because counting is
    // on; it only makes "did an actual model produce this?" a measured number instead of a claim.
    private val modelGateway: AgentModelGateway = com.hjp.agent.contract.CountingAgentModelGateway(
        if (emulatorCompatibilityMode) {
            LocalToolRoutingModelGateway()
        } else {
            LiteRtAgentModelGateway(
                modelFile,
                File(appContext.cacheDir, "litertlm"),
                LiteRtBackendPreference.CPU_ONLY,
                counters = runtimeCounters,
            )
        },
        runtimeCounters,
    )
    private val sessionManager = AgentSessionManager(
        sessionStore, modelGateway, SYSTEM_INSTRUCTION,
        Locale.getDefault().toLanguageTag(),
    )

    private val reactKernel = AgentKernel(
        registry = registry,
        toolExecutor = DefaultToolExecutor(registry),
        policyEngine = DefaultToolPolicyEngine(),
        sessionManager = sessionManager,
        observationMapper = DefaultToolObservationMapper(),
        environment = AndroidAgentRuntimeEnvironment(confirmationCoordinator),
        turnPolicy = AgentTurnPolicy(
            maxToolCalls = 6,
            maxProtocolCorrections = 1,
            historyStrategy = ConversationHistoryStrategy.APP_CANONICAL_BOOTSTRAP,
        ),
        contextSelector = contextSelector,
        contextPreflight = ::preflight,
        contactDirectory = contactDirectory,
        runtimeCounters = runtimeCounters,
    )

    /**
     * Debug-only alternative path. It is built lazily so a release build never constructs the
     * structured gateway, and it shares the session store with the ReAct kernel so both paths see
     * exactly the same memory on the same fixture.
     */
    private val structuredKernel = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        StructuredAgentKernel(
            registry = registry,
            toolExecutor = DefaultToolExecutor(registry),
            policyEngine = DefaultToolPolicyEngine(),
            sessionStore = sessionStore,
            observationMapper = DefaultToolObservationMapper(),
            environment = AndroidAgentRuntimeEnvironment(confirmationCoordinator),
            modelGateway = LiteRtStructuredAgentModelGateway(
                modelFile,
                File(appContext.cacheDir, "litertlm-structured"),
                LiteRtBackendPreference.CPU_ONLY,
            ),
            contextSelector = contextSelector,
            contactDirectory = contactDirectory,
        )
    }

    /** Only debug builds may switch; release always runs the ReAct kernel. */
    val kernelSwitchAvailable: Boolean =
        BuildConfig.DEBUG && !emulatorCompatibilityMode && deployment.usable

    @Volatile
    var kernelMode: AgentKernelMode = AgentKernelMode.REACT
        private set

    val engine: AgentTurnEngine
        get() = when (kernelMode) {
            AgentKernelMode.REACT -> reactKernel
            AgentKernelMode.STRUCTURED -> structuredKernel.value
        }

    /** Returns the mode actually in effect after the request. */
    suspend fun selectKernel(mode: AgentKernelMode): AgentKernelMode {
        if (!kernelSwitchAvailable) return kernelMode
        if (mode == kernelMode) return kernelMode
        resetSession()
        kernelMode = mode
        return kernelMode
    }

    val modelReady: Boolean get() = emulatorCompatibilityMode || deployment.usable

    /** Runs real embedding inference off the UI thread; never substitutes a keyword engine. */
    suspend fun checkEmbeddingModel(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val vector = embeddingGemmaEngine.value.embedQuery("검색 모델 동작 확인")
        check(embeddingGemmaEngine.value.isModelBacked()) { "필수 임베딩 모델을 사용할 수 없습니다." }
        "실제 임베딩 생성 성공: ${vector.size}차원"
    }

    /** Non-PII snapshot for debug diagnostics. Never contains names, addresses or tool payloads. */
    fun diagnosticsSnapshot(): Map<String, String> = buildMap {
        put("kernel_mode", kernelMode.name)
        put("kernel_switch_available", kernelSwitchAvailable.toString())
        put("emulator_compatibility_mode", emulatorCompatibilityMode.toString())
        putAll(deployment.diagnosticSummary())
        put("embedding_initialized", embeddingGemmaEngine.isInitialized().toString())
        if (embeddingGemmaEngine.isInitialized()) {
            put("embedding_model_backed", embeddingGemmaEngine.value.isModelBacked().toString())
            put("embedding_status", embeddingGemmaEngine.value.diagnosticStatus())
        }
        // Release builds still expose no turn detail; only debug bring-up needs it.
        if (BuildConfig.DEBUG) reactKernel.diagnostics.last?.asMap()?.forEach(::put)
    }

    /**
     * 화면에 보이는 대화. 커널 세션과 같은 자리에 둔다 — 둘의 수명이 다르면 사람이 읽는
     * 대화와 모델이 기억하는 대화가 어긋난다. 자세한 이유는 [ChatSession] 참고.
     *
     * lazy 인 이유는 [ChatSession] 이 이 컨테이너를 되잡기 때문이다. 생성자에서 만들면
     * 아직 다 만들어지지 않은 `this` 를 넘기게 된다.
     */
    /**
     * 대화가 한 번이라도 쓰였음을 앱에 알리는 통로. [HjpApplication] 이 채운다.
     *
     * 앱 수명 규칙("작업 목록에서 지웠다가 다시 열면 빈 대화")이 이 신호에 달려 있는데,
     * Agent_0910 에서는 `AgentViewModel` 이 화면을 만들 때 함께 넘겨받던 것이다. 통합하면서
     * 그 클래스가 빠지자 [HjpApplication.markSessionUsed] 는 정의만 남고 부르는 곳이 없어져
     * 규칙이 조용히 죽어 있었다.
     */
    var onSessionUsed: () -> Unit = {}

    private val chatSession = lazy(LazyThreadSafetyMode.NONE) { ChatSession(this) }
    internal val chat: ChatSession get() = chatSession.value

    /**
     * 화면의 말풍선을 비운다. 대화를 아직 연 적이 없으면 아무 일도 하지 않는다 —
     * 비우려고 [ChatSession] 을 새로 만드는 건 앞뒤가 맞지 않는다.
     */
    fun clearChatTranscript() {
        if (chatSession.isInitialized()) chat.clearTranscript()
    }

    /** Replaces the session atomically and returns the new generation. */
    suspend fun resetSession(): Long = engine.resetSession()

    /**
     * The live session, for instrumentation that has to observe lifetime rules on a real device.
     *
     * It exposes the transcript and the bounded memory projection, both of which the app already
     * holds in process; it adds no persistence and no new storage of contact data.
     */
    suspend fun sessionSnapshot(): com.hjp.agent.core.AgentSession = sessionStore.getOrCreate()

    fun answerConfirmation(accepted: Boolean) = confirmationCoordinator.answer(accepted)

    override fun close() {
        if (chatSession.isInitialized()) chat.close()
        sessionManager.close()
        if (structuredKernel.isInitialized()) structuredKernel.value.close()
        if (embeddingGemmaEngine.isInitialized()) embeddingGemmaEngine.value.close()
        database.close()
    }

    // Internal rather than private so the context-budget measurement reads the prompt that actually
    // ships. A copy in the test would drift from the real one exactly when it mattered most.
    internal companion object {
        /** 프롬프트는 [HjpSystemInstruction] 한 곳에만 둔다 — 노트북 러너도 같은 것을 쓴다. */
        const val SYSTEM_INSTRUCTION = HjpSystemInstruction.TEXT

        /** 규칙 이름. 이게 있으면 그대로 쓴다. */
        private const val PREFERRED_ARTIFACT = "hjp-agent.litertlm"

        /**
         * 모델 폴더에서 생성 모델로 쓸 파일을 고른다.
         *
         * 못 찾으면 규칙 이름을 그대로 돌려준다 — 그래야 화면과 로그가 "여기에 넣으세요"
         * 라고 말할 경로를 갖는다.
         */
        fun resolveGenerativeArtifact(modelRoot: File): File {
            val preferred = File(modelRoot, PREFERRED_ARTIFACT)
            if (preferred.isFile) return preferred
            val candidates = modelRoot.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".litertlm", ignoreCase = true) }
                .orEmpty()
            return candidates.maxByOrNull { it.length() } ?: preferred
        }

        fun isAndroidEmulator(): Boolean =
            Build.HARDWARE.equals("ranchu", ignoreCase = true) ||
                Build.HARDWARE.equals("goldfish", ignoreCase = true) ||
                Build.MODEL.startsWith("sdk_gphone", ignoreCase = true) ||
                Build.PRODUCT.contains("sdk_gphone", ignoreCase = true) ||
                Build.FINGERPRINT.startsWith("generic", ignoreCase = true)
    }
}

private class AndroidAgentRuntimeEnvironment(
    private val confirmationCoordinator: AgentConfirmationCoordinator,
) : AgentRuntimeEnvironment {
    override val localeTag: String get() = Locale.getDefault().toLanguageTag()
    override val timeZoneId: String get() = TimeZone.getDefault().id
    override suspend fun grantedPermissions(): Set<String> = emptySet()
    override suspend fun deviceCapabilities(): Set<String> = setOf("android.external_ui", "contact.local_search", "contact.local_update", "datetime.current")

    override suspend fun toolContext(sessionId: String, turnId: String) = ToolExecutionContext(
        sessionId, turnId, localeTag, timeZoneId,
        permissionGateway = PermissionGateway { emptySet() },
        confirmationGateway = ConfirmationGateway { prompt -> confirmationCoordinator.confirm(prompt) },
        eventSink = ToolEventSink { },
    )
}

private class AgentConfirmationCoordinator {
    private var pending: CompletableDeferred<Boolean>? = null

    suspend fun confirm(promptKo: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        synchronized(this) {
            pending?.complete(false)
            pending = deferred
        }
        return try {
            deferred.await()
        } finally {
            synchronized(this) {
                if (pending === deferred) pending = null
            }
        }
    }

    fun answer(accepted: Boolean) {
        synchronized(this) {
            pending?.complete(accepted)
            pending = null
        }
    }
}
