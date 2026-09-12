package com.example.hjp.desktop

import com.example.hjp.HjpSystemInstruction
import com.example.hjp.LocalToolRoutingModelGateway
import com.example.hjp.RepositoryContactDirectory
import com.example.hjp.ocr.CardParser
import com.example.hjp.ocr.KieParser
import com.example.hjp.ocr.OcrAssets
import com.example.hjp.ocr.OcrCardMapper
import com.example.hjp.ocr.OcrPipeline
import com.hjp.agent.contract.AgentEvent
import com.hjp.agent.core.AgentKernel
import com.hjp.agent.core.AgentRuntimeEnvironment
import com.hjp.agent.core.AgentSessionManager
import com.hjp.agent.core.AgentTurnPolicy
import com.hjp.agent.core.ContextPreflightResult
import com.hjp.agent.core.ConversationHistoryStrategy
import com.hjp.agent.core.DefaultToolExecutor
import com.hjp.agent.core.DefaultToolObservationMapper
import com.hjp.agent.core.DefaultToolPolicyEngine
import com.hjp.agent.core.DefaultToolRegistry
import com.hjp.agent.core.InMemoryAgentSessionStore
import com.hjp.agent.core.ModelContextSelector
import com.hjp.agent.core.ToolImplementationCandidate
import com.hjp.tool.contract.ToolExecutionResult
import com.hjp.tool.contract.ToolCatalogSnapshot
import com.hjp.agent.contract.ModelToolCall
import com.hjp.agent.core.ToolExecutor
import com.hjp.searchlookup.OnDeviceEmbeddingEngine
import com.hjp.tool.contact.BusinessCardRecord
import com.hjp.tool.contact.ContactSearchBackend
import com.hjp.tool.contact.GetContactPlugin
import com.hjp.tool.contact.RyeongContactSearchBackend
import com.hjp.tool.contact.CountContactsPlugin
import com.hjp.tool.contact.SearchContactsPlugin
import com.hjp.tool.contact.UpdateBusinessCardPlugin
import com.hjp.tool.contract.ConfirmationGateway
import com.hjp.tool.contract.PermissionGateway
import com.hjp.tool.contract.ToolEventSink
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.datetime.GetCurrentDateTimePlugin
import java.io.File
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.opencv.imgcodecs.Imgcodecs
import kotlin.system.exitProcess

/** 모델을 디렉터리에서 읽는다. 안드로이드의 assets/ocr/ 과 같은 파일 구성. */
class FileOcrAssets(private val dir: File) : OcrAssets {
    override fun bytes(name: String): ByteArray? =
        File(dir, name).takeIf { it.isFile }?.readBytes()
}

/**
 * 노트북 실행 환경. 앱의 `AndroidAgentRuntimeEnvironment` 와 같은 자리다.
 *
 * 캘린더·메일은 안드로이드 화면을 여는 일이라 여기에 없다 — capability 목록에서도 빼서,
 * 커널이 "할 수 있다"고 믿고 그 도구를 고르는 일이 없게 한다.
 */
private class DesktopRuntimeEnvironment(
    /**
     * 확인을 요구하는 도구(명함 수정 등)를 승인할지.
     *
     * 기본은 **거절**이다. 노트북에는 확인 UI 가 없는데 자동으로 통과시키면 사용자가 못 본
     * 동의를 대신 눌러 주는 셈이고, 러너 결과가 실제 앱보다 관대해진다. 그 연쇄까지 보려면
     * `--yes` 를 준다 — 그때는 승인했다는 사실이 출력에 남는다.
     */
    private val autoConfirm: Boolean,
) : AgentRuntimeEnvironment {
    override val localeTag: String get() = Locale.KOREA.toLanguageTag()
    override val timeZoneId: String get() = TimeZone.getDefault().id
    override suspend fun grantedPermissions(): Set<String> = emptySet()
    override suspend fun deviceCapabilities(): Set<String> =
        setOf("contact.local_search", "contact.local_update", "datetime.current")

    override suspend fun toolContext(sessionId: String, turnId: String) = ToolExecutionContext(
        sessionId,
        turnId,
        localeTag,
        timeZoneId,
        permissionGateway = PermissionGateway { emptySet() },
        // 노트북에는 확인 UI 가 없다. 자동 승인이 아니라 **거절**이 안전한 기본값이다 —
        // 사용자가 못 본 확인을 통과시키면 러너 결과가 실제 앱보다 관대해진다.
        confirmationGateway = ConfirmationGateway { prompt ->
            if (autoConfirm) println("      확인 요청 자동 승인(--yes): " + prompt)
            autoConfirm
        },
        eventSink = ToolEventSink { },
    )
}

/**
 * 앱과 같은 커널을 노트북에 세운 것. 배선의 정본은 `app/AppContainer.kt` 이고, 여기는
 * 그중 **안드로이드가 필요 없는 부분만** 같은 순서로 만든다.
 *
 * 모델 경계만 다르다: 앱은 LiteRT-LM 으로 Gemma 를 부르고 여기는 규칙 기반
 * [LocalToolRoutingModelGateway] 가 도구를 고른다(앱도 에뮬레이터에서는 같은 것을 쓴다).
 * 그래서 이 러너로 검증되는 것은 **도구 연쇄·검색·세션**이고, 모델이 문장을 어떻게 쓰는지는
 * 아니다.
 */
/**
 * 어떤 도구가 실제로 실행됐는지 **이름으로** 기록한다.
 *
 * 화면에 흘러나오는 사건([AgentEvent.ToolStarted])은 사람이 읽을 한글 문구라, "언제
 * search_contacts 를 부르는가" 같은 질문에 답하려면 도구 이름이 필요하다. 위임만 하고
 * 결과는 바꾸지 않는다.
 */
private class RecordingToolExecutor(private val delegate: ToolExecutor) : ToolExecutor {
    val calls = mutableListOf<String>()

    fun clear() = calls.clear()

    override suspend fun execute(
        call: ModelToolCall,
        snapshot: ToolCatalogSnapshot,
        context: ToolExecutionContext,
    ): ToolExecutionResult {
        calls += call.modelToolName
        return delegate.execute(call, snapshot, context)
    }
}

private class DesktopAgent(
    dbPath: String,
    embedModelDir: File,
    autoConfirm: Boolean = false,
) : AutoCloseable {

    private val onnx: OnnxEmbeddingProvider? =
        if (embedModelDir.isDirectory) runCatching { OnnxEmbeddingProvider(embedModelDir) }.getOrNull()
        else null

    val repository = SqliteContactRepository(dbPath)

    val backend: ContactSearchBackend = RyeongContactSearchBackend(
        repository = repository,
        embeddingEngineFactory = {
            if (onnx != null) OnDeviceEmbeddingEngine.production(onnx)
            else OnDeviceEmbeddingEngine.production()
        },
    )

    private val directory = RepositoryContactDirectory(repository)

    private val plugins = listOf(
        SearchContactsPlugin(backend),
        CountContactsPlugin(backend),
        GetContactPlugin(backend),
        UpdateBusinessCardPlugin(repository, onUpdated = { directory.invalidate() }),
        GetCurrentDateTimePlugin(),
    )
    private val registry = DefaultToolRegistry(plugins.map { ToolImplementationCandidate(it) })
    val executor = RecordingToolExecutor(DefaultToolExecutor(registry))
    private val sessionStore = InMemoryAgentSessionStore()
    private val sessionManager = AgentSessionManager(
        sessionStore,
        LocalToolRoutingModelGateway(),
        HjpSystemInstruction.TEXT,
        Locale.KOREA.toLanguageTag(),
    )

    val kernel = AgentKernel(
        registry = registry,
        toolExecutor = executor,
        policyEngine = DefaultToolPolicyEngine(),
        sessionManager = sessionManager,
        observationMapper = DefaultToolObservationMapper(),
        environment = DesktopRuntimeEnvironment(autoConfirm),
        turnPolicy = AgentTurnPolicy(
            maxToolCalls = 6,
            maxProtocolCorrections = 1,
            historyStrategy = ConversationHistoryStrategy.APP_CANONICAL_BOOTSTRAP,
        ),
        contextSelector = ModelContextSelector(),
        // 아티팩트 예산은 생성 모델이 있을 때의 이야기다. 규칙 게이트웨이에는 적용되지 않는다.
        contextPreflight = { ContextPreflightResult.Ok(Int.MAX_VALUE) },
        contactDirectory = directory,
    )

    val embedderStatus: String =
        if (onnx != null) "ONNX EmbeddingGemma (" + embedModelDir.name + ")"
        else "없음 — 키워드 검색만 (HJP_EMBED_MODEL_DIR 로 지정)"

    override fun close() {
        kernel.close()
        onnx?.close()
        repository.close()
    }
}

/**
 * 저장소 루트. Gradle 은 모듈 디렉터리에서 실행하므로 상대경로가 `desktop/` 기준이 된다 —
 * 그대로 두면 시드를 못 찾고 조용히 빈 DB 로 돈다(예전에 실제로 그랬다).
 */
private val repoRoot: File by lazy {
    generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File(".").absoluteFile
}

private val DEFAULT_MODEL_DIR get() = File(repoRoot, "app/src/main/assets/ocr").path
private val DEFAULT_SEED get() = File(repoRoot, "app/src/main/assets/cards/cards_seed.json")
private val DEFAULT_DB get() = File(repoRoot, "build/hjp-desktop.db").path

/**
 * EmbeddingGemma ONNX 위치. 없으면 임베더 없이(키워드 전용) 돈다 — 1.2GB 라 저장소에
 * 넣지 않으므로 기계마다 없을 수 있고, 그때 죽는 것보다 폴백이 낫다.
 */
private val EMBED_MODEL_DIR: File by lazy {
    System.getenv("HJP_EMBED_MODEL_DIR")?.let { File(it) }
        ?: File(repoRoot.parentFile, "HJP_limitededition-main/models/embeddinggemma-300m-onnx")
}

private fun usage(): Nothing {
    println(
        """
        HJP 데스크톱 러너 — 앱과 **같은 에이전트 커널·도구·검색**을 노트북에서 돌린다.

          ocr <이미지> [모델디렉터리]      명함 한 장 인식 (기본: $DEFAULT_MODEL_DIR)
          import <이미지> [모델디렉터리]   인식해서 DB 에 저장 (OCR→검색 연결 확인)
          search <질의>                   도구가 쓰는 것과 같은 검색 경로
          turn [--yes] <질의1>|<질의2>|…   멀티턴 — 한 세션으로 연속 처리 ('|' 로 구분)
                                          --yes 는 확인이 필요한 도구(명함 수정)를 승인한다

        모델 경계만 앱과 다르다. 여기서는 규칙 기반 게이트웨이가 도구를 고른다
        (앱도 에뮬레이터에서는 같은 것을 쓴다). 그래서 검증되는 것은:
          O  도구 연쇄(search_contacts→get_contact→…), 라우팅, 세션·지시어 해소,
             검색 순위(앱과 같은 FTS4 4단 티어 + RRF)
          X  Gemma 가 문장을 어떻게 쓰는지, 캘린더·메일 화면(안드로이드 전용)

        DB: $DEFAULT_DB
        시드: ${DEFAULT_SEED.path}
        임베더: HJP_EMBED_MODEL_DIR (없으면 키워드 검색만)
        """.trimIndent(),
    )
    exitProcess(1)
}

fun main(args: Array<String>) {
    if (args.isEmpty()) usage()
    when (args[0]) {
        "ocr" -> runOcr(args.drop(1))
        "import" -> runImport(args.drop(1))
        "search" -> runSearch(args.drop(1))
        "turn" -> runTurns(args.drop(1))
        else -> usage()
    }
}

private fun openAgent(warmUp: Boolean = false, autoConfirm: Boolean = false): DesktopAgent {
    File(DEFAULT_DB).parentFile?.mkdirs()
    val agent = DesktopAgent(DEFAULT_DB, EMBED_MODEL_DIR, autoConfirm)
    seedIfEmpty(agent)
    if (warmUp) warmUp(agent)
    return agent
}

/**
 * 검색 백엔드를 미리 깨운다.
 *
 * 첫 검색이 1.2GB ONNX 세션을 올리는데 그게 도구 실행 제한(10초)을 넘겨서, 첫 턴이
 * 내용과 무관하게 "도구 실행 시간이 초과되었습니다"로 끝났다. 모델을 올리는 시간은
 * 측정하려는 대상이 아니라 러너를 띄우는 비용이므로 턴 밖으로 뺀다. 폰에서는 같은 일을
 * 앱 시작 뒤 첫 검색이 하고, 거기서는 양자화 tflite 라 이만큼 걸리지 않는다.
 */
private fun warmUp(agent: DesktopAgent) = runBlocking {
    val startedAt = System.currentTimeMillis()
    runCatching { agent.backend.search("워밍업", 1) }
    println("검색 백엔드 준비 ${System.currentTimeMillis() - startedAt}ms")
}

/**
 * 앱의 `RoomBusinessCardRepository.seedIfEmpty` 와 같은 자리. 앱은 assets 에서, 여기는
 * 같은 파일을 파일 시스템에서 읽는다 — **같은 1000장**이라야 두 쪽 결과를 비교할 수 있다.
 */
private fun seedIfEmpty(agent: DesktopAgent) {
    if (agent.repository.count() > 0) return
    val file = DEFAULT_SEED
    if (!file.isFile) {
        println("시드 파일이 없다: ${file.path} — 빈 DB 로 돈다")
        return
    }
    val array = Json.parseToJsonElement(file.readText()) as? JsonArray ?: return
    val cards = array.mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        fun text(key: String) = (o[key] as? JsonPrimitive)?.content?.trim().orEmpty()
        val id = text("id")
        if (id.isBlank()) return@mapNotNull null
        BusinessCardRecord(
            id = id,
            name = text("name"),
            // 시드는 옛 카멜케이스 키를 쓴다. 앱 assets 와 **같은 파일**이라 여기서 맞춰 읽는다.
            nameEn = text("nameEn").ifBlank { text("name_en") },
            company = text("company"),
            title = text("title"),
            department = text("department"),
            industry = text("industry"),
            location = text("location"),
            phone = text("phone"),
            mobile = text("mobile"),
            email = text("email"),
            address = text("address"),
            website = text("website"),
            memo = text("memo"),
            tags = text("tags").split(',').map { it.trim() }.filter { it.isNotBlank() },
            updatedAt = text("updatedAt").ifBlank { text("updated_at") },
        )
    }
    agent.repository.insertAll(cards)
    println("시드 ${cards.size}장 적재")
}

private fun loadOcr(modelDirArg: String?): Pair<OcrPipeline, KieParser?> {
    val modelDir = File(modelDirArg ?: DEFAULT_MODEL_DIR)
    if (!modelDir.isDirectory) {
        System.err.println("모델 디렉터리가 없다: ${modelDir.absolutePath}")
        exitProcess(1)
    }
    nu.pattern.OpenCV.loadLocally()
    val assets = FileOcrAssets(modelDir)
    return OcrPipeline(assets) to KieParser.createOrNull(assets)
}

private fun recognize(imagePath: String, modelDirArg: String?): List<CardParser.Field> {
    val image = File(imagePath)
    if (!image.isFile) {
        System.err.println("이미지가 없다: ${image.absolutePath}")
        exitProcess(1)
    }
    val (pipeline, kie) = loadOcr(modelDirArg)
    println("필드 분류기: ${if (kie != null) "MiniLM KIE" else "CardParser 휴리스틱(폴백)"}")

    val bgr = Imgcodecs.imread(image.absolutePath)
    if (bgr.empty()) {
        System.err.println("이미지를 읽지 못했다: ${image.absolutePath}")
        exitProcess(1)
    }
    val startedAt = System.currentTimeMillis()
    val regions = pipeline.run(bgr)
    val ocrMs = System.currentTimeMillis() - startedAt
    // 앱과 같은 값을 넘긴다 — 주소 합치기가 이미지 너비 비율을 보므로, 여기서 빠뜨리면
    // 노트북과 폰의 인식 결과가 갈린다.
    val fields = kie?.parse(regions, bgr.cols(), bgr.rows()) ?: CardParser.parse(regions)
    bgr.release()

    println("이미지: ${image.name} · 인식 영역 ${regions.size}개 · OCR ${ocrMs}ms")
    println("-".repeat(60))
    regions.forEach { println("  [%.2f] %s".format(it.score, it.text)) }
    println()
    println("추출 필드 ${fields.size}개")
    println("-".repeat(60))
    fields.forEach { println("  ${it.icon} ${it.label}: ${it.value}") }
    return fields
}

private fun runOcr(args: List<String>) {
    if (args.isEmpty()) usage()
    recognize(args[0], args.getOrNull(1))
}

private fun runImport(args: List<String>) = runBlocking {
    if (args.isEmpty()) usage()
    val fields = recognize(args[0], args.getOrNull(1))
    if (fields.isEmpty()) {
        println("\n저장할 필드가 없어 건너뛴다.")
        return@runBlocking
    }
    openAgent().use { agent ->
        // 촬영본은 S 접두어. 번들 시드가 T/C 로 시작하므로 섞이지 않는다.
        val used = agent.repository.loadAll()
            .filter { it.id.startsWith("S") }
            .mapNotNull { it.id.removePrefix("S").toIntOrNull() }
        val id = "S" + ((used.maxOrNull() ?: 0) + 1).toString().padStart(3, '0')
        val card = OcrCardMapper.toCard(fields, id, System.currentTimeMillis())
        agent.repository.insertAll(listOf(card))
        println()
        println("저장: ${card.id} · ${card.name} · ${card.company} · location=${card.location}")
        println("전체 ${agent.repository.count()}장")
    }
}

private fun runSearch(args: List<String>) = runBlocking {
    if (args.isEmpty()) usage()
    val query = args.joinToString(" ")
    openAgent().use { agent ->
        val response = agent.backend.search(query, 5)
        println("질의: $query")
        // 어느 티어에서 잡혔는지. 아래로 밀릴수록 순위 근거가 약해지므로 눈으로 봐야 한다.
        println("키워드 티어: " + agent.repository.lastKeywordTiers().ifEmpty { listOf("(없음)") })
        println("임베더: ${agent.embedderStatus}")
        println(
            "엔진: ${response.engine} · 모드 ${response.mode}" +
                if (response.fallbackUsed) " · 폴백(${response.fallbackReason})" else "",
        )
        println("-".repeat(60))
        if (response.hits.isEmpty()) {
            println("  (결과 없음)")
        } else {
            response.hits.forEachIndexed { i, hit ->
                println(
                    "  ${i + 1}. ${hit.card.name} · ${hit.card.company} · ${hit.card.title} " +
                        "· ${hit.card.location}  [%.4f] %s".format(hit.score, hit.retrievalSources),
                )
            }
        }
    }
}

/**
 * 여러 질의를 **한 세션으로** 연속 처리한다. 후속 발화("그 사람 회사 어디야")가 앞 턴의
 * 결과를 제대로 물고 가는지 보는 게 목적이라 세션을 새로 만들면 안 된다.
 */
private fun runTurns(args: List<String>) = runBlocking {
    if (args.isEmpty()) usage()
    // Gradle 의 --args 는 공백으로 쪼개서 넘긴다. 질문마다 공백이 있으므로 다시 이어 붙인 뒤
    // '|' 로 나눈다 — 안 그러면 낱말 하나가 턴 하나가 된다.
    val autoConfirm = args.contains("--yes")
    val questions = args.filterNot { it == "--yes" }
        .joinToString(" ").split("|").map { it.trim() }.filter { it.isNotEmpty() }
    if (questions.isEmpty()) usage()

    openAgent(warmUp = true, autoConfirm = autoConfirm).use { agent ->
        println("모델 경계: 규칙 기반 게이트웨이 (Gemma 없음)")
        println("임베더: ${agent.embedderStatus}")
        println()
        questions.forEachIndexed { index, question ->
            val startedAt = System.currentTimeMillis()
            val answer = StringBuilder()
            agent.executor.clear()
            var failure: String? = null
            agent.kernel.runTurn(question).collect { event ->
                when (event) {
                    is AgentEvent.Token -> answer.append(event.text)
                    is AgentEvent.FinalMessage -> if (answer.isBlank()) answer.append(event.text)
                    is AgentEvent.UserError -> failure = event.messageKo
                    else -> Unit
                }
            }
            val elapsed = System.currentTimeMillis() - startedAt
            println("[t${index + 1}] $question")
            val tools = agent.executor.calls.toList()
            println("      도구: " + if (tools.isEmpty()) "(없음)" else tools.joinToString(" → "))
            println("      ${elapsed}ms")
            println("      답변: ${(failure ?: answer.toString()).replace('\n', ' ').take(160)}")
            println()
        }
    }
}
