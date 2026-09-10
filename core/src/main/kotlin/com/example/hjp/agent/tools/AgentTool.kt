package com.example.hjp.agent.tools

import org.json.JSONObject

/**
 * 에이전트가 호출할 수 있는 도구의 공통 인터페이스.
 *
 * 새 도구를 추가하려면:
 * 1. 이 인터페이스를 구현
 * 2. [declaration]에 LLM에게 줄 function declaration(JSON Schema)을 정의
 * 3. [ToolRegistry]에 등록
 */
interface AgentTool {

    /** LLM이 tool call 시 사용하는 도구 이름 (예: "create_calendar_event") */
    val name: String

    /**
     * LLM 시스템 프롬프트에 삽입할 function declaration.
     * Gemini/OpenAI function calling 포맷과 호환되는 JSON Schema 형식:
     * { "name": ..., "description": ..., "parameters": { "type": "object", ... } }
     */
    val declaration: JSONObject

    /**
     * 도구 실행. LLM이 출력한 인자(JSON)를 받아 결과를 JSON 문자열로 반환한다.
     *
     * 반환된 문자열은 그대로 LLM에게 다시 전달되므로,
     * 실패하더라도 예외를 던지지 말고 [ToolResults.error]로 반환할 것.
     * (그래야 ReAct 루프에서 LLM이 재시도하거나 ask_clarification으로 넘어갈 수 있음)
     */
    suspend fun execute(args: JSONObject): String
}

/** 도구 실행 결과를 일관된 JSON 형식으로 만들어 주는 헬퍼 */
object ToolResults {

    fun success(message: String): String =
        JSONObject()
            .put("status", "success")
            .put("message", message)
            .toString()

    fun error(message: String): String =
        JSONObject()
            .put("status", "error")
            .put("message", message)
            .toString()
}
