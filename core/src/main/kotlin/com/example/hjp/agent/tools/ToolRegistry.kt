package com.example.hjp.agent.tools

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 에이전트가 사용할 도구들을 모아두는 레지스트리.
 *
 * - [declarationsJson]: 시스템 프롬프트에 삽입할 도구 선언 목록 생성
 * - [dispatch]: LLM이 출력한 tool call을 이름으로 찾아 실행
 *
 * 사용 예:
 * ```
 * val registry = ToolRegistry(
 *     CreateCalendarEventTool(context),
 *     OpenComposeTool(context),
 * )
 * val systemPrompt = "... 사용 가능한 도구:\n${registry.declarationsJson()}"
 * val result = registry.dispatch(toolName, toolArgs) // LLM에게 다시 전달
 * ```
 */
class ToolRegistry(vararg tools: AgentTool) {

    private val byName: Map<String, AgentTool> = tools.associateBy { it.name }

    val tools: Collection<AgentTool> get() = byName.values

    /** 모든 도구의 function declaration을 JSON 배열 문자열로 반환 (프롬프트 삽입용) */
    fun declarationsJson(indent: Int = 2): String =
        JSONArray(byName.values.map { it.declaration }).toString(indent)

    /**
     * "뭐 할 줄 알아?" 에 답할 때 쓰는 기능 목록. 각 도구의 declaration description
     * 첫 문장만 뽑는다 — 뒷문장은 LLM 을 위한 사용 지침(형식·호출 순서)이라 사용자에게
     * 보여줄 내용이 아니다.
     *
     * 문구를 따로 적어 두지 않고 여기서 파생시키는 이유: 도구가 늘어날 때마다 소개 문구를
     * 같이 고쳐야 하면 반드시 어긋난다. 등록만 하면 답변이 따라오게 둔다.
     */
    fun capabilityLabels(): List<String> = byName.values.mapNotNull { tool ->
        val desc = tool.declaration.optString("description").trim()
        if (desc.isEmpty()) return@mapNotNull null
        val end = desc.indexOf(". ").let { if (it < 0) desc.length else it + 1 }
        desc.substring(0, end).trim()
    }

    /** LLM이 출력한 tool call을 실행하고 결과 JSON 문자열을 반환 */
    suspend fun dispatch(name: String, args: JSONObject): String {
        val tool = byName[name]
            ?: return ToolResults.error("알 수 없는 도구: $name. 사용 가능한 도구: ${byName.keys}")
        return tool.execute(args)
    }

    /** LLM 원시 출력에서 {"name": ..., "args": {...}} 형태의 tool call을 파싱해 실행 */
    suspend fun dispatch(rawToolCall: String): String {
        val call = try {
            JSONObject(rawToolCall)
        } catch (e: JSONException) {
            return ToolResults.error("tool call이 올바른 JSON이 아닙니다: ${e.message}")
        }
        val name = call.optString("name")
        if (name.isBlank()) return ToolResults.error("tool call에 name 필드가 없습니다.")
        return dispatch(name, call.optJSONObject("args") ?: JSONObject())
    }
}
