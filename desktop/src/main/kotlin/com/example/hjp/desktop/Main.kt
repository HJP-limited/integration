package com.example.hjp.desktop

import com.example.hjp.agent.AgentSession
import com.example.hjp.agent.ChatEngine
import com.example.hjp.agent.ChatEngineProvider
import com.example.hjp.agent.LlmRole
import com.example.hjp.agent.tools.ToolRegistry
import com.example.hjp.ocr.CardParser
import com.example.hjp.ocr.KieParser
import com.example.hjp.ocr.OcrAssets
import com.example.hjp.ocr.OcrCardMapper
import com.example.hjp.ocr.OcrPipeline
import com.example.hjp.runChat
import com.example.hjp.search.CardSearchService
import com.example.hjp.search.NoEmbeddingProvider
import com.example.hjp.search.SeedSource
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.system.exitProcess

/** 모델을 디렉터리에서 읽는다. 안드로이드의 assets/ocr/ 과 같은 파일 구성. */
class FileOcrAssets(private val dir: File) : OcrAssets {
    override fun bytes(name: String): ByteArray? =
        File(dir, name).takeIf { it.isFile }?.readBytes()
}

/** 안드로이드의 assets/cards/ 와 같은 시드 파일을 읽는다. */
class FileSeedSource(private val dir: File) : SeedSource {
    override fun cardsSeedJson(): String? =
        File(dir, "cards_seed.json").takeIf { it.isFile }?.readText()

    override fun precomputedEmbeddings(): Pair<String, ByteArray>? {
        val ids = File(dir, "cards_embeddings_ids.json").takeIf { it.isFile } ?: return null
        val vectors = File(dir, "cards_embeddings.bin").takeIf { it.isFile } ?: return null
        return ids.readText() to vectors.readBytes()
    }
}

/**
 * 생성 모델이 없는 러너. 두 역할 모두 "missing:" 을 돌려주므로 [runChat] 이 LLM 을 부르지
 * 않고 검색 결과까지만 만든다.
 *
 * 이게 쓸모 있는 이유: 멀티턴 평가의 1~3층(라우팅·슬롯·턴별 R@5)은 **생성이 필요 없다**.
 * 그 층들은 규칙 변경마다 돌려야 하는데, 노트북에서 LLM 없이 초 단위로 끝난다.
 */
private object NoChatEngineProvider : ChatEngineProvider {
    override fun modelStatus(role: LlmRole) = "missing: desktop runner has no generative model"
    override fun openShared(role: LlmRole): ChatEngine =
        throw IllegalStateException("desktop runner has no generative model")
}

/**
 * 저장소 루트. `gradle :desktop:run` 은 작업 디렉터리가 모듈 폴더라 상대경로가 조용히
 * 어긋난다 — 실제로 시드를 못 찾아 샘플 3장으로 폴백하는 걸 보고 이 방식으로 바꿨다.
 */
private val repoRoot: File by lazy {
    generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File(".").absoluteFile
}

private val DEFAULT_MODEL_DIR get() = File(repoRoot, "app/src/main/assets/ocr").path
private val DEFAULT_SEED_DIR get() = File(repoRoot, "app/src/main/assets/cards").path
private val DEFAULT_DB get() = File(repoRoot, "build/hjp-desktop.db").path

private fun usage(): Nothing {
    println(
        """
        HJP 데스크톱 러너 — 앱(:app)과 같은 :core/:core-ocr 코드를 노트북에서 돌린다.

          ocr <이미지> [모델디렉터리]      명함 한 장 인식 (기본: $DEFAULT_MODEL_DIR)
          search <질의>                   하이브리드 검색 (SQLite FTS, 앱과 같은 SQL)
          turn <질의1>|<질의2>|…           멀티턴 — 한 세션으로 연속 처리 ('|' 로 구분)
          import <이미지> [모델디렉터리]   인식해서 DB 에 저장 (OCR→검색 연결 확인)

        생성 모델은 싣지 않는다. 그래서 turn 이 검증하는 범위는:
          O  기능/자기참조/전체개수/조건개수 우회, 질의 재작성(담화참조·정정·속성이월·조건누적),
             검색·필드필터·기권
          X  followup/context_answer 분기와 focus 인물 치환 — 이들은 코드상 LLM 로드 이후라
             모델 없이는 지나가지 않는다. 그 층은 실기기나 litert-lm 러너가 필요하다.

        DB: $DEFAULT_DB · 시드: $DEFAULT_SEED_DIR
        """.trimIndent()
    )
    exitProcess(1)
}

fun main(args: Array<String>) {
    if (args.isEmpty()) usage()
    when (args[0]) {
        "ocr" -> runOcr(args.drop(1))
        "search" -> runSearch(args.drop(1))
        "turn" -> runTurns(args.drop(1))
        "import" -> runImport(args.drop(1))
        else -> usage()
    }
}

/**
 * EmbeddingGemma ONNX 위치. 없으면 임베더 없이(키워드 전용) 돈다 — 1.2GB 라 저장소에
 * 넣지 않으므로 기계마다 없을 수 있고, 그때 죽는 것보다 폴백이 낫다.
 */
private val EMBED_MODEL_DIR: File by lazy {
    val fromEnv = System.getenv("HJP_EMBED_MODEL_DIR")
    if (fromEnv != null) File(fromEnv)
    else File(repoRoot.parentFile, "HJP_limitededition-main/models/embeddinggemma-300m-onnx")
}

private fun openSearch(): CardSearchService {
    File(DEFAULT_DB).parentFile?.mkdirs()
    return CardSearchService(
        store = SqliteCardStore(DEFAULT_DB),
        embeddingProviderFactory = {
            if (EMBED_MODEL_DIR.isDirectory) OnnxEmbeddingProvider(EMBED_MODEL_DIR)
            else NoEmbeddingProvider
        },
        seedSource = FileSeedSource(File(DEFAULT_SEED_DIR)),
    )
}

private fun loadOcr(modelDirArg: String?): Pair<OcrPipeline, KieParser?> {
    val modelDir = File(modelDirArg ?: DEFAULT_MODEL_DIR)
    if (!modelDir.isDirectory) {
        System.err.println("모델 디렉터리가 없다: ${modelDir.absolutePath}")
        exitProcess(1)
    }
    nu.pattern.OpenCV.loadShared()
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
    val fields = kie?.parse(regions) ?: CardParser.parse(regions)
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

private fun runImport(args: List<String>) {
    if (args.isEmpty()) usage()
    val fields = recognize(args[0], args.getOrNull(1))
    if (fields.isEmpty()) {
        println("\n저장할 필드가 없어 건너뛴다.")
        return
    }
    openSearch().use { search ->
        val card = OcrCardMapper.toCard(fields, search.nextOcrCardId(), System.currentTimeMillis())
        search.addCard(card)
        println()
        println("저장: ${card.id} · ${card.name} · ${card.company} · location=${card.location}")
        println("전체 ${search.totalCardCount()}장")
    }
}

private fun runSearch(args: List<String>) {
    if (args.isEmpty()) usage()
    val query = args.joinToString(" ")
    openSearch().use { search ->
        val response = search.search(query, limit = 5)
        println("질의: $query")
        println("임베더: ${search.diagnostics().optString("active_embedding_status")}")
        println("경로: ${response.retrieval}${if (response.abstained) " · 기권" else ""}")
        println("필터: ${response.fieldFilters}")
        println("-".repeat(60))
        if (response.results.isEmpty()) {
            println("  (결과 없음)")
        } else {
            response.results.forEachIndexed { i, hit ->
                println("  ${i + 1}. ${hit.card.name} · ${hit.card.company} · ${hit.card.title} " +
                    "· ${hit.card.location}  [score %.4f]".format(hit.score))
            }
        }
    }
}

/**
 * 여러 질의를 **한 세션으로** 연속 처리한다. 후속 발화("그 사람 부서는?")가 앞 턴의 결과를
 * 제대로 물고 가는지 보는 게 목적이라 세션을 새로 만들면 안 된다.
 */
private fun runTurns(args: List<String>) {
    if (args.isEmpty()) usage()
    // Gradle 의 --args 는 공백으로 쪼개서 넘긴다. 질문마다 공백이 있으므로 다시 이어 붙인 뒤
    // '|' 로 나눈다 — 안 그러면 낱말 하나가 턴 하나가 된다.
    val questions = args.joinToString(" ").split("|").map { it.trim() }.filter { it.isNotEmpty() }
    if (questions.isEmpty()) usage()

    openSearch().use { search ->
        val session = AgentSession()
        val tools = ToolRegistry()
        questions.forEachIndexed { index, question ->
            val turnId = "t${index + 1}"
            session.beginTurn(turnId, question)
            val result = runChat(search, NoChatEngineProvider, tools, question, session)
            val cards = result.search?.results.orEmpty()
            println("[$turnId] $question")
            // 검색에 실제로 들어간 질의. 앞 턴을 물어 재작성됐으면 원문과 달라진다.
            val resolved = result.search?.query
            if (!resolved.isNullOrBlank() && resolved != question) {
                println("      재작성 → $resolved")
            }
            println("      route=${result.route ?: "search(생성없음)"} · 후보 ${cards.size}건")
            cards.take(3).forEach { println("        - ${it.card.name} · ${it.card.company}") }
            result.error?.let { println("      error=$it") }
            session.recordTurn(
                turnId = turnId,
                userText = question,
                assistantText = result.answer,
                // 재검색 없이 직전 결과로 답한 턴은 도구를 쓴 게 아니다 — 앱과 같은 판정.
                executedTools = if (result.conversationalFollowup) emptyList()
                else listOf("search_business_cards"),
            )
            println()
        }
    }
}

private inline fun <T> CardSearchService.use(body: (CardSearchService) -> T): T = body(this)
