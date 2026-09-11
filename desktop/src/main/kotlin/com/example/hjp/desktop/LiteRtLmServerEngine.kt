package com.example.hjp.desktop

import com.example.hjp.agent.ChatEngine
import com.example.hjp.agent.ChatEngineProvider
import com.example.hjp.agent.EMPTY_LLM_RESPONSE
import com.example.hjp.agent.LlmRole
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 노트북에서 Gemma 를 돌리는 [ChatEngineProvider]. 구글 `litert-lm` CLI 의 serve 모드가
 * 띄우는 OpenAI 호환 엔드포인트에 붙는다.
 *
 *     litert-lm import <path.litertlm> gemma4e2b     # 1회
 *     litert-lm serve --host 127.0.0.1 --port 9379
 *
 * **앱과 같은 모델 파일**(`gemma-4-E2B-it.litertlm`)을 돌리므로 프롬프트가 같으면 답도
 * 같은 계열이 나온다. 다만 앱은 LiteRT-LM 을 인프로세스로 부르고 여기는 HTTP 를 거치므로
 * 실행 경로까지 같지는 않다 — 최종 확인은 실기기에서 해야 한다.
 *
 * 무상태 호출이라는 점이 중요하다. `runChat` 은 [ChatEngine.generate] 를 프롬프트 하나로
 * 한 번만 부르고, 대화 맥락은 AgentSession 이 그 프롬프트 안에 이미 넣어 둔다. 그래서
 * 매 요청 messages 를 새로 보내는 serve API 와 의미가 정확히 맞는다.
 */
class LiteRtLmServerEngineProvider(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val modelId: String = DEFAULT_MODEL_ID,
) : ChatEngineProvider {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val engine by lazy { ServerEngine(http, baseUrl, modelId) }

    /**
     * 서버에 등록된 모델을 확인한다. 못 붙으면 `"missing:"` 을 돌려주고, 그러면 runChat 이
     * LLM 없이 검색 결과까지만 만든다 — 서버를 안 띄운 채 돌려도 러너가 죽지 않는다.
     */
    override fun modelStatus(role: LlmRole): String {
        // 이 러너는 대화 모델만 제공한다. 도구 호출 모델은 앱 쪽 관심사다.
        if (role != LlmRole.Chat) return "missing: desktop runner serves Chat only"
        return try {
            val request = HttpRequest.newBuilder(URI.create("$baseUrl/v1/models"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                "missing: litert-lm serve at $baseUrl returned ${response.statusCode()}"
            } else if (!response.body().contains(modelId)) {
                "missing: model '$modelId' not registered (litert-lm import <file> $modelId)"
            } else {
                "$baseUrl ($modelId)"
            }
        } catch (e: Exception) {
            "missing: litert-lm serve not reachable at $baseUrl — ${e.javaClass.simpleName}"
        }
    }

    override fun openShared(role: LlmRole): ChatEngine {
        check(role == LlmRole.Chat) { "desktop runner serves Chat only" }
        return engine
    }

    private class ServerEngine(
        private val http: HttpClient,
        private val baseUrl: String,
        private val modelId: String,
    ) : ChatEngine {

        override val loadedFileName: String = modelId

        override fun generate(prompt: String): String {
            val payload = JSONObject()
                .put("model", modelId)
                .put(
                    "messages",
                    JSONArray().put(
                        JSONObject().put("role", "user").put("content", prompt)
                    )
                )
                // 앱의 EngineConfig(maxNumTokens = 2048)와 같은 상한.
                .put("max_tokens", 2048)

            val request = HttpRequest.newBuilder(URI.create("$baseUrl/v1/chat/completions"))
                // 첫 요청은 모델 로드를 기다려야 해서 넉넉히 잡는다.
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), Charsets.UTF_8))
                .build()

            val response = http.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            if (response.statusCode() != 200) {
                throw IllegalStateException("litert-lm ${response.statusCode()}: ${response.body().take(200)}")
            }
            val choices = JSONObject(response.body()).optJSONArray("choices") ?: return EMPTY_LLM_RESPONSE
            if (choices.isEmpty) return EMPTY_LLM_RESPONSE
            val text = choices.getJSONObject(0)
                .optJSONObject("message")?.optString("content").orEmpty()
                .trim()
            return text.ifBlank { EMPTY_LLM_RESPONSE }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "http://127.0.0.1:9379"
        const val DEFAULT_MODEL_ID = "gemma4e2b"
    }
}
