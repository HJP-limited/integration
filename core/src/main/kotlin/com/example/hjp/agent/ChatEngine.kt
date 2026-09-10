package com.example.hjp.agent

/** LLM 이 유효한 답을 못 냈을 때의 표지. 호출부가 이 값으로 실패를 구분한다. */
const val EMPTY_LLM_RESPONSE = "(empty LLM response)"

enum class LlmRole(val fileNames: List<String>, val assetPath: String, val displayName: String) {
    ToolCalling(
        fileNames = listOf("functiongemma_270m.litertlm"),
        assetPath = "gemma/functiongemma_270m.litertlm",
        displayName = "FunctionGemma 270M",
    ),

    // 후보 순서대로 찾는다: Gemma 4 E2B(최우선, RAM 8GB+ 기기) -> Gemma 3 1B -> Gemma 3 270M IT(저사양 기기용 경량)
    Chat(
        fileNames = listOf("gemma-4-E2B-it.litertlm", "gemma3-1b-it-int4.litertlm", "gemma3-270m-it-q8.litertlm"),
        assetPath = "gemma/gemma-4-E2B-it.litertlm",
        displayName = "Chat Gemma 4 E2B",
    ),
    ;

    val fileName: String get() = fileNames.first()
}

/** 로드된 생성 모델 하나. */
interface ChatEngine {
    val loadedFileName: String

    /** 맥락 없는 단발성 생성. 대화 기억은 [AgentSession] 이 프롬프트로 넣는다. */
    fun generate(prompt: String): String
}

/**
 * 플랫폼별 모델 탐색·로드. 코어의 턴 파이프라인은 "이 역할을 쓸 수 있나"와 "생성"만 알면 된다.
 *
 * 안드로이드는 LiteRT-LM, 데스크톱 러너는 자기 런타임을 끼운다 — 그래야 라우팅·프롬프트
 * 규칙이 한 곳(:core)에만 있고 두 실행 경로가 갈라지지 않는다.
 */
interface ChatEngineProvider {
    /** 역할별 모델 상태. `"missing:"` 으로 시작하면 그 역할은 쓸 수 없다. */
    fun modelStatus(role: LlmRole): String

    /** 공유 엔진을 연다. 반복 로드 시 네이티브 메모리가 새므로 호출부는 닫지 않는다. */
    fun openShared(role: LlmRole): ChatEngine
}
