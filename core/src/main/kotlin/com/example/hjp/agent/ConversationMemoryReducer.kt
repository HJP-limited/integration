package com.example.hjp.agent

/**
 * sojung 저장소 `feature/runtime-multiturn-summary` 브랜치의
 * `agent-core/AgentSession.kt`에 있는 `ConversationMemoryReducer`를 그대로 이식한 것.
 * 마커 단어 목록·상수·판정 순서까지 원본과 동일하게 맞췄다(드리프트 방지).
 *
 * 모델 호출 없이 **결정적으로** 분류한다 — 매 턴 LLM을 한 번 더 불러 요약시키면 2B
 * 모델에서 체감 지연이 두 배가 된다. 대신 정규식/키워드 마커로 "이건 선호다", "이건
 * 제약이다"를 판정하는 얕은 방식이라, 마커에 안 걸리는 표현은 못 잡는다는 한계가 있다
 * (원본도 같은 트레이드오프를 감수했다).
 *
 * 이전에는 구버전(`agent/structured-multiturn-memory`)을 이식해 뒀었다. 최신본과 다른 점:
 *  - 선호/사실을 **발화 전체**가 아니라 **키별로 정규화된 값**으로 저장한다
 *    ("내 회사는 블루오션이야" -> key=`user.company`, content=`블루오션`)
 *  - 그 덕에 **정정**이 된다. 예전에는 "내 회사는 A야" 뒤 "내 회사는 B야"가 오면
 *    내용이 달라 둘 다 남았다. 이제 같은 key 를 덮어써서 B 하나만 남는다.
 *  - `isActionLike()` 로 **작업 발화만** 작업 메모리에 넣는다. 예전에는 모든 발화를
 *    작업으로 취급해서 "오늘 날씨 좋네" 같은 잡담이 resolvedActions 를 오염시켰다.
 *  - 오래된 pendingActions 를 30분 지나면 버린다(예전에는 영구히 쌓였다).
 *  - topic 은 작업 발화일 때만 갱신한다(잡담으로 주제가 바뀌지 않게).
 */
internal object ConversationMemoryReducer {
    private const val MAX_TOPIC_CHARS = 240
    private const val MAX_ITEM_CHARS = 300
    private const val MAX_ITEMS_PER_BUCKET = 8

    private val preferenceMarkers = listOf("선호", "좋아", "싫어", "말투", "스타일", "앞으로")
    private val constraintMarkers = listOf("하지 마", "하지마", "말아", "반드시", "꼭", "전에 확인", "동의 없이")

    /**
     * 턴이 완료됐을 때 호출한다. 이번 턴의 사용자 발화를 마커로 분류해서 선호/제약/사실
     * 버킷에 흡수하고, `beginTurn`이 걸어둔 pendingAction을 resolvedActions로 옮긴다.
     */
    fun reduceCompletedTurn(
        previous: ConversationMemory,
        turnId: String,
        timestamp: Long,
        userText: String,
        executedTools: List<String>,
    ): ConversationMemory {
        val normalized = userText.normalizeMemoryText()
        if (normalized.isEmpty()) return previous
        val preferences = previous.preferences.merge(extractPreferences(normalized, turnId, timestamp))
        val constraints = previous.constraints.merge(extractConstraints(normalized, turnId, timestamp))
        val facts = previous.confirmedFacts.merge(extractFacts(normalized, turnId, timestamp))
        // 이 턴에 걸어둔 pending 이 있으면 그걸 완료 처리하고, 없으면 작업성 발화일 때만
        // 새로 만든다. 잡담이면 아무것도 안 남긴다.
        val action = previous.pendingActions.firstOrNull { it.sourceTurnId == turnId }
        val completed = action ?: normalized.takeIf { isActionLike(it) }?.let {
            ConversationMemoryItem(it.take(MAX_ITEM_CHARS), turnId, timestamp, key = "action:$turnId")
        }
        val resolved = if (completed != null) {
            previous.resolvedActions.upsert(completed.copy(updatedAtEpochMillis = timestamp))
        } else {
            previous.resolvedActions
        }
        val toolActions = executedTools.distinct().map { tool ->
            ConversationMemoryItem(
                "도구 실행 완료: $tool", turnId, timestamp, MemoryConfidence.TOOL_VERIFIED,
            )
        }
        return previous.copy(
            topic = if (isActionLike(normalized)) normalized.take(MAX_TOPIC_CHARS) else previous.topic,
            confirmedFacts = facts,
            preferences = preferences,
            constraints = constraints,
            pendingActions = previous.pendingActions.filterNot { it.sourceTurnId == turnId },
            resolvedActions = (resolved + toolActions).deduplicatedTail(),
        )
    }

    /**
     * 턴이 시작될 때 호출한다. 작업성 발화면 pendingActions에 걸어둬서, 이 턴이 끝까지
     * 완료되지 못하면(예외/취소) 다음 턴에서도 "아직 처리 못한 요청"으로 남아있게 한다.
     * 30분이 지난 pending 은 버린다 — 안 그러면 실패한 요청이 영구히 쌓인다.
     */
    fun beginTurn(
        previous: ConversationMemory,
        turnId: String,
        timestamp: Long,
        userText: String,
    ): ConversationMemory {
        val normalized = userText.normalizeMemoryText()
        if (normalized.isEmpty()) return previous
        if (!isActionLike(normalized)) return previous
        val pending = ConversationMemoryItem(
            normalized.take(MAX_ITEM_CHARS), turnId, timestamp, key = "action:$turnId",
        )
        val activePending = previous.pendingActions.filter {
            timestamp - it.updatedAtEpochMillis <= MAX_PENDING_AGE_MILLIS
        }
        return previous.copy(
            topic = normalized.take(MAX_TOPIC_CHARS),
            pendingActions = activePending.upsert(pending),
        )
    }

    /** 같은 key(없으면 같은 content)면 덮어쓴다 — 이게 '정정'을 만든다. */
    private fun List<ConversationMemoryItem>.upsert(item: ConversationMemoryItem) =
        (filterNot { (item.key.isNotBlank() && it.key == item.key) || it.content == item.content } + item)
            .deduplicatedTail()

    private fun List<ConversationMemoryItem>.merge(items: List<ConversationMemoryItem>): List<ConversationMemoryItem> =
        items.fold(this) { current, item -> current.upsert(item) }

    private fun List<ConversationMemoryItem>.deduplicatedTail() =
        distinctBy { it.content }.takeLast(MAX_ITEMS_PER_BUCKET)

    private fun String.normalizeMemoryText() = replace(Regex("\\s+"), " ").trim()

    private fun extractPreferences(text: String, turnId: String, timestamp: Long): List<ConversationMemoryItem> = buildList {
        if (text.contains("정중") || text.contains("존댓말")) add(item("response.tone", "정중한 말투", turnId, timestamp))
        if (text.contains("짧게") || text.contains("간단하게") || text.contains("간단히")) {
            add(item("response.length", "짧고 간결한 답변", turnId, timestamp))
        }
        if (text.contains("반말")) add(item("response.tone", "반말", turnId, timestamp))
    }

    private fun extractConstraints(text: String, turnId: String, timestamp: Long): List<ConversationMemoryItem> = buildList {
        if (constraintMarkers.any(text::contains)) {
            add(item("action.confirmation", text.take(MAX_ITEM_CHARS), turnId, timestamp))
        }
    }

    private fun extractFacts(text: String, turnId: String, timestamp: Long): List<ConversationMemoryItem> = buildList {
        val patterns = listOf(
            "user.name" to Regex("내 이름은\\s*(.+?)(?:이야|야|입니다|이라고|라고|\\.)?$"),
            "user.company" to Regex("내 회사는\\s*(.+?)(?:이야|야|입니다|라고|\\.)?$"),
            "user.title" to Regex("내 직책은\\s*(.+?)(?:이야|야|입니다|라고|\\.)?$"),
        )
        patterns.forEach { (key, pattern) ->
            pattern.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)?.let { value ->
                add(item(key, value, turnId, timestamp))
            }
        }
    }

    private fun item(key: String, value: String, turnId: String, timestamp: Long) =
        ConversationMemoryItem(value.take(MAX_ITEM_CHARS), turnId, timestamp, key = key)

    private fun isActionLike(text: String): Boolean = actionMarkers.any(text::contains)

    private val actionMarkers = listOf(
        "찾아", "검색", "조회", "보여", "알려", "작성", "보내", "전송", "수정", "등록",
        "일정", "명함", "연락처", "메일", "이메일", "문자", "sms", "캘린더",
    )

    private const val MAX_PENDING_AGE_MILLIS = 30 * 60 * 1_000L
}
