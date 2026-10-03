package com.example.hjp.desktop

import com.example.hjp.ocr.CardParser
import com.example.hjp.ocr.OcrCardMapper
import com.hjp.agent.contract.AgentEvent
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.opencv.imgcodecs.Imgcodecs

/**
 * 브라우저에서 같은 커널을 두드리기 위한 로컬 전용 HTTP 경계.
 *
 * 새 에이전트가 아니다 — `turn` 명령이 쓰는 [DesktopAgent] 를 그대로 하나 열어 두고, 콘솔 대신
 * HTTP 로 질문을 받는다. 그래서 웹에서 본 결과와 회귀 러너 결과가 갈릴 수 없다.
 *
 * 한계도 러너와 같다: Gemma 아티팩트는 앱과 같은 파일이지만 호스트 LiteRT 버전과 ONNX 임베딩이
 * Android 와 다르고, 메일·문자·일정은 초안까지만 만들고 외부 화면을 열지 않는다. 그 문장을 화면
 * 위에 계속 띄워 두는 것도 이 파일의 일이다.
 */
private object Web {
    /** 세션·네이티브 대화·DB 를 공유하는 한 벌이라, 턴은 한 번에 하나만 돈다. */
    val lock = Any()

    fun bytes(exchange: HttpExchange): ByteArray = exchange.requestBody.use { it.readBytes() }

    fun query(exchange: HttpExchange): Map<String, String> =
        exchange.requestURI.rawQuery.orEmpty().split("&").mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val index = pair.indexOf('=')
            if (index < 0) {
                pair to ""
            } else {
                URLDecoder.decode(pair.substring(0, index), Charsets.UTF_8) to
                    URLDecoder.decode(pair.substring(index + 1), Charsets.UTF_8)
            }
        }.toMap()

    fun send(exchange: HttpExchange, status: Int, type: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", type)
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    fun json(exchange: HttpExchange, status: Int, body: JsonObject) =
        send(exchange, status, "application/json; charset=utf-8", body.toString().toByteArray(Charsets.UTF_8))

    fun failed(exchange: HttpExchange, error: Throwable) {
        System.err.println("web: " + error.stackTraceToString())
        runCatching {
            json(exchange, 500, buildJsonObject {
                put("error", error.message ?: error::class.java.simpleName)
            })
        }
    }

    /** 브라우저가 붙잡고 있는, 한 줄씩 흘려보내는 이벤트 통로. */
    class Stream(private val exchange: HttpExchange) {
        private val out = exchange.responseBody

        fun open() {
            exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
            exchange.responseHeaders.add("Cache-Control", "no-cache")
            exchange.responseHeaders.add("Connection", "keep-alive")
            exchange.sendResponseHeaders(200, 0)
        }

        fun emit(payload: JsonObject) {
            out.write(("data: " + payload + "\n\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }

        fun close() = runCatching { out.close() }
    }
}

/** 앱과 다른 점. 화면과 상태 응답이 같은 문장을 쓰도록 한곳에 둔다. */
private val PARITY_NOTES = listOf(
    "Gemma 아티팩트는 앱과 같은 파일이지만, 실행 런타임은 PC의 Python LiteRT-LM이고 Android는 0.16.1입니다.",
    "임베딩은 PC ONNX, Android는 TFLite입니다. 같은 모델의 다른 내보내기입니다.",
    "메일·문자·일정은 초안까지만 만들고 외부 화면을 열지 않습니다(SIMULATED).",
    "명함 시드 1,000장과 커널·라우터·도구 계약·검증 코드는 앱과 같습니다.",
)

private fun statusJson(agent: DesktopAgent): JsonObject = buildJsonObject {
    put("model", agent.deployment?.let { deployment ->
        buildJsonObject {
            put("path", deployment.modelFile.absolutePath)
            put("artifactId", deployment.artifactId)
            put("identifiedBy", deployment.identifiedBy.name)
            put("sizeBytes", deployment.actualSizeBytes)
            put("sha256", deployment.verifiedSha256 ?: "(이번 실행에서 계산하지 않음)")
            put("contextLimitTokens", deployment.appContextLimitTokens)
            put("boundary", "실제 배포 Gemma · Python LiteRT-LM CPU")
        }
    } ?: buildJsonObject {
        put("boundary", "규칙 기반 게이트웨이 (--rules) · 모델 품질 검증이 아님")
    })
    put("embedder", agent.embedderStatus)
    put("db", DEFAULT_DB)
    put("cards", agent.repository.count())
    put("parity", buildJsonArray { PARITY_NOTES.forEach { add(it) } })
}

/**
 * 한 턴. CLI 의 `[t1] 질문 / 도구 / 명함ID / ms / 답변` 과 **같은 증거**를 이벤트로 흘린다.
 */
private fun runTurnStream(agent: DesktopAgent, text: String, stream: Web.Stream) {
    synchronized(Web.lock) {
        agent.executor.clear()
        agent.externalDrafts.clear()
        val startedAt = System.currentTimeMillis()
        val answer = StringBuilder()
        var failure: String? = null
        runBlocking {
            agent.kernel.runTurn(text).collect { event ->
                when (event) {
                    is AgentEvent.Token -> {
                        answer.append(event.text)
                        stream.emit(buildJsonObject { put("type", "token"); put("text", event.text) })
                    }
                    is AgentEvent.FinalMessage -> {
                        if (answer.isBlank()) answer.append(event.text)
                        stream.emit(buildJsonObject { put("type", "final"); put("text", event.text) })
                    }
                    is AgentEvent.ToolStarted -> stream.emit(buildJsonObject {
                        put("type", "tool"); put("phase", "start"); put("text", event.messageKo)
                    })
                    is AgentEvent.ToolFinished -> stream.emit(buildJsonObject {
                        put("type", "tool"); put("phase", "end"); put("text", event.messageKo)
                    })
                    is AgentEvent.UserError -> {
                        failure = event.messageKo
                        stream.emit(buildJsonObject { put("type", "userError"); put("text", event.messageKo) })
                    }
                    else -> Unit
                }
            }
        }
        val elapsed = System.currentTimeMillis() - startedAt
        val tools = agent.executor.calls.toList()
        println("[web] " + text + " · 도구: " + tools.joinToString(" → ").ifEmpty { "(없음)" } + " · " + elapsed + "ms")
        stream.emit(buildJsonObject {
            put("type", "done")
            put("ms", elapsed)
            put("answer", failure ?: answer.toString())
            put("failed", failure != null)
            put("tools", buildJsonArray { tools.forEach { add(it) } })
            put("cardIds", buildJsonArray { agent.executor.contactIds.forEach { add(it) } })
            put("toolArguments", buildJsonArray { agent.executor.callArguments.forEach { add(it) } })
            put("drafts", buildJsonArray { agent.externalDrafts.forEach { add(it) } })
        })
    }
}

/** `runImport` 와 같은 매핑. 저장 형식이 두 갈래로 갈리지 않게 같은 함수만 부른다. */
private fun cardFrom(fields: List<CardParser.Field>, id: String) =
    OcrCardMapper.toCard(fields, id, System.currentTimeMillis())

/** 업로드한 사진 한 장을 앱과 같은 OCR·KIE 로 읽는다. 저장까지 하면 색인도 즉시 다시 세운다. */
private fun runOcrUpload(agent: DesktopAgent, image: ByteArray, save: Boolean): JsonObject {
    val temp = File.createTempFile("hjp-web-", ".img")
    return try {
        temp.writeBytes(image)
        val (pipeline, kie) = loadOcr(null)
        try {
            val bgr = Imgcodecs.imread(temp.absolutePath)
            if (bgr.empty()) return buildJsonObject { put("error", "이미지를 읽지 못했습니다.") }
            val startedAt = System.currentTimeMillis()
            val regions = pipeline.run(bgr)
            val ocrMs = System.currentTimeMillis() - startedAt
            val fields = kie.parse(regions, bgr.cols(), bgr.rows())
            bgr.release()
            var savedId = ""
            if (save && fields.isNotEmpty()) {
                synchronized(Web.lock) {
                    runBlocking {
                        val used = agent.repository.loadAll()
                            .filter { it.id.startsWith("S") }
                            .mapNotNull { it.id.removePrefix("S").toIntOrNull() }
                        val id = "S" + ((used.maxOrNull() ?: 0) + 1).toString().padStart(3, '0')
                        agent.addCard(cardFrom(fields, id))
                        savedId = id
                    }
                }
            }
            buildJsonObject {
                put("ocrMs", ocrMs)
                put("regions", buildJsonArray {
                    regions.forEach { region ->
                        add(buildJsonObject { put("score", region.score); put("text", region.text) })
                    }
                })
                put("fields", buildJsonArray {
                    fields.forEach { field ->
                        add(buildJsonObject {
                            put("icon", field.icon)
                            put("label", field.label)
                            put("value", field.value)
                        })
                    }
                })
                put("savedCardId", savedId)
                put("cards", agent.repository.count())
            }
        } finally {
            kie.close()
            pipeline.close()
        }
    } finally {
        temp.delete()
    }
}

/** 개발 중에는 소스의 HTML 을 그대로 읽어, 새로고침만으로 화면을 고칠 수 있게 한다. */
private fun page(): ByteArray {
    val source = File(repoRoot, "desktop/src/main/resources/web/index.html")
    if (source.isFile) return source.readBytes()
    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream("web/index.html")
        ?: error("web/index.html 을 찾지 못했습니다.")
    return stream.use { it.readBytes() }
}

internal fun runServe(args: List<String>) {
    val port = args.indexOf("--port").takeIf { it >= 0 }
        ?.let { args.getOrNull(it + 1)?.toIntOrNull() } ?: 8765
    val autoConfirm = args.contains("--yes")
    val actualGemma = !args.contains("--rules")

    val agent = openAgent(warmUp = true, autoConfirm = autoConfirm, actualGemma = actualGemma)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    server.executor = Executors.newFixedThreadPool(4)

    server.createContext("/") { exchange ->
        try {
            if (exchange.requestURI.path != "/") {
                Web.send(exchange, 404, "text/plain; charset=utf-8", "not found".toByteArray(Charsets.UTF_8))
            } else {
                Web.send(exchange, 200, "text/html; charset=utf-8", page())
            }
        } catch (error: Throwable) {
            Web.failed(exchange, error)
        }
    }
    server.createContext("/api/status") { exchange ->
        try {
            Web.json(exchange, 200, statusJson(agent))
        } catch (error: Throwable) {
            Web.failed(exchange, error)
        }
    }
    server.createContext("/api/reset") { exchange ->
        try {
            synchronized(Web.lock) { runBlocking { agent.kernel.resetSession() } }
            Web.json(exchange, 200, buildJsonObject { put("ok", true) })
        } catch (error: Throwable) {
            Web.failed(exchange, error)
        }
    }
    server.createContext("/api/turn") { exchange ->
        val text = Web.query(exchange)["text"].orEmpty().trim()
        if (text.isEmpty()) {
            Web.json(exchange, 400, buildJsonObject { put("error", "질문이 비어 있습니다.") })
            return@createContext
        }
        val stream = Web.Stream(exchange)
        try {
            stream.open()
            runTurnStream(agent, text, stream)
        } catch (error: Throwable) {
            System.err.println("web: " + error.stackTraceToString())
            runCatching {
                stream.emit(buildJsonObject {
                    put("type", "done")
                    put("failed", true)
                    put("ms", 0)
                    put("answer", "실행 실패: " + (error.message ?: error::class.java.simpleName))
                    put("tools", JsonArray(emptyList()))
                    put("cardIds", JsonArray(emptyList()))
                    put("drafts", JsonArray(emptyList()))
                })
            }
        } finally {
            stream.close()
        }
    }
    server.createContext("/api/ocr") { exchange ->
        try {
            val save = Web.query(exchange)["save"] == "true"
            val image = Web.bytes(exchange)
            if (image.isEmpty()) {
                Web.json(exchange, 400, buildJsonObject { put("error", "이미지가 비어 있습니다.") })
            } else {
                Web.json(exchange, 200, runOcrUpload(agent, image, save))
            }
        } catch (error: Throwable) {
            Web.failed(exchange, error)
        }
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop(0)
        agent.close()
    })
    server.start()
    println()
    println("HJP 웹 테스트 서버: http://127.0.0.1:" + port)
    println(
        if (actualGemma) "모델 경계: 실제 배포 Gemma / Python LiteRT-LM CPU; Android와 런타임 버전 차이 있음"
        else "모델 경계: 규칙 기반 게이트웨이 (--rules; 모델 품질 검증이 아님)",
    )
    println("임베더: " + agent.embedderStatus)
    PARITY_NOTES.forEach { println("  · " + it) }
    println("종료: Ctrl+C")
}
