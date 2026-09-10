package com.example.hjp.agent

/**
 * 구조화 멀티턴 메모리 — sojung 저장소(`HJP-limited/sojung`)의
 * `agent/structured-multiturn-memory` 브랜치, `agent-contract/AgentModel.kt`의
 * `ConversationMemory`/`ConversationMemoryItem`/`MemoryConfidence`를 그대로 이식한 것.
 *
 * 이전의 flat `conversationSummary: String` rolling summary를 대체한다. 대화를
 * "누가 무슨 말을 했나"의 뭉뚱그린 텍스트가 아니라, 주제·확정된 사실·선호·제약·진행중/완료된
 * 요청으로 분류해서 들고 있는다 — 몇 턴 지나도 "정중한 말투로 답해달라" 같은 제약이나
 * "이전에 확인해달라고 했던 요청" 같은 진행 상태가 rolling summary의 압축 과정에서
 * 뭉개지지 않는다.
 */
data class ConversationMemory(
    val schemaVersion: Int = 1,
    val topic: String = "",
    val confirmedFacts: List<ConversationMemoryItem> = emptyList(),
    val preferences: List<ConversationMemoryItem> = emptyList(),
    val constraints: List<ConversationMemoryItem> = emptyList(),
    val pendingActions: List<ConversationMemoryItem> = emptyList(),
    val resolvedActions: List<ConversationMemoryItem> = emptyList(),
    val historyDigest: String = "",
) {
    /**
     * 모델에 보여줄 뷰. 작업 생명주기 기록(pending/resolved)은 **내부 장부**라 뺀다.
     *
     * 우리도 같은 문제를 실측으로 겪었다: beginTurn 이 이번 질문을 pendingActions 에
     * 걸어두는데 그게 프롬프트에 들어가면 [current_user]와 똑같은 텍스트가 두 번 나와서
     * 2B 모델이 새로 답하지 않고 그 텍스트를 그대로 에코했다. 그땐 '이번 턴 것만' 뺐지만,
     * sojung 최신본은 두 버킷을 통째로 뺀다 — 모델이 답을 만드는 데 쓰는 정보가 아니라
     * 런타임이 미완료 요청을 추적하려고 들고 있는 값이기 때문이다. 저장은 그대로 둔다.
     */
    fun modelView(): ConversationMemory = copy(
        pendingActions = emptyList(),
        resolvedActions = emptyList(),
    )
}

data class ConversationMemoryItem(
    val content: String,
    val sourceTurnId: String,
    val updatedAtEpochMillis: Long,
    val confidence: MemoryConfidence = MemoryConfidence.EXPLICIT,
    /**
     * 같은 종류의 항목을 가리키는 식별자("user.company", "response.tone").
     * 정정을 처리하려고 있다 — "내 회사는 A야" 뒤에 "내 회사는 B야"가 오면 내용이 달라서
     * content 비교로는 둘 다 남는다. 같은 key 면 덮어써서 최신 값 하나만 유지한다.
     * 비어 있으면 예전처럼 content 로만 중복을 판정한다.
     */
    val key: String = "",
)

enum class MemoryConfidence { EXPLICIT, TOOL_VERIFIED }
