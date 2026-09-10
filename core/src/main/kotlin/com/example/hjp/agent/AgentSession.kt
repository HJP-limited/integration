package com.example.hjp.agent

/**
 * 멀티턴 세션 — sojung 저장소(`HJP-limited/sojung`)의 `agent/structured-multiturn-memory`
 * 브랜치(`agent-core/AgentSession.kt` + `agent-contract/AgentModel.kt` +
 * `llm-litert-common/LiteRtNativeAgentModelGateway.kt`)에서 멀티턴 메커니즘만 이식한 것.
 * 검색/조회(CardSearchService, CardGazetteer 등)는 이 이식과 무관하게 이 저장소 것을
 * 그대로 쓴다 — 이 파일이 건드리는 건 대화 상태 관리뿐이다.
 *
 * 앱 프로세스가 살아있는 동안 하나의 AgentSession을 유지한다. 앱을 종료하거나 세션을
 * 초기화하면 대화 내역과 모델 conversation이 모두 폐기된다.
 *
 * 모델 입력에는 현재 요청만 보내지 않고 다음 블록을 함께 전달한다.
 *
 *   [conversation_memory]   구조화 메모리 — 주제/확정된 사실/선호/제약/진행중·완료된 요청/
 *                            윈도우에서 밀려난 대화의 history digest
 *   [recent_conversation]   최근 user/assistant 메시지 최대 8개
 *   [current_user]          현재 사용자 요청
 *   [tool_session_context]  tool이 저장한 안전한 세션 상태
 *
 * sojung 원본 대비 이식하지 않은 것: `AgentSessionManager`/`AgentSessionStore`의 분리와
 * mutex 기반 동시성 처리. sojung은 앱 전체가 공유하는 native tool-calling 커널
 * (`AgentKernel`) 아래서 여러 화면이 세션 하나를 동시에 건드릴 수 있어 그 구조가
 * 필요하지만, 이 앱은 채팅 화면 하나가 `remember { AgentSession() }`로 세션을 단독
 * 소유하므로 그 분리가 불필요하다. 데이터 모델(`ConversationMemory`)과 리듀서 로직
 * (`ConversationMemoryReducer`), history digest 병합 알고리즘은 원본과 동일하게 맞췄다.
 */
class AgentSession {

    enum class Role { User, Assistant }

    data class Message(val role: Role, val text: String)

    private val recent = ArrayDeque<Message>()

    /** tool 이 저장하는 안전한 세션 상태. 자유 텍스트가 아니라 tool 이 검증한 값만 넣는다. */
    private val toolContext = LinkedHashMap<String, String>()

    val recentMessages: List<Message> get() = recent.toList()

    var conversationMemory: ConversationMemory = ConversationMemory()
        private set

    /**
     * [beginTurn]이 마지막으로 걸어둔 턴 id — 아직 [recordTurn]으로 끝맺지 않은 "진행 중인
     * 턴"을 가리킨다. [buildContextBlocks]가 이 턴 자신의 pendingActions 항목을 프롬프트
     * 렌더링에서 제외하는 데 쓴다(왜인지는 그 함수 주석 참고).
     */
    private var inFlightTurnId: String? = null

    /**
     * 턴 시작 시 호출한다. 이번 발화를 pendingActions에 걸어둔다 — 턴이 끝까지 완료되지
     * 못해도(예외 등) 다음 턴에서 "아직 처리 못한 요청"으로 남는다. `turnId`는 이 턴을
     * 끝맺을 [recordTurn] 호출에도 그대로 넘겨야 pendingActions에서 정확히 이 항목을
     * resolvedActions로 옮길 수 있다.
     */
    fun beginTurn(turnId: String, userText: String) {
        inFlightTurnId = turnId
        conversationMemory = ConversationMemoryReducer.beginTurn(
            previous = conversationMemory,
            turnId = turnId,
            timestamp = System.currentTimeMillis(),
            userText = userText,
        )
    }

    /**
     * 턴이 정상 완료됐을 때 호출한다. 구조화 메모리를 갱신하고, 최근 메시지 윈도우에
     * user/assistant 메시지를 추가한 뒤 윈도우를 넘긴 만큼 history digest로 접는다.
     *
     * @param executedTools 이번 턴에 실제로 실행된 도구 이름(예: "search_business_cards").
     *   이 앱은 native tool-calling이 아니라 결정적으로 먼저 검색부터 하므로, 호출부에서
     *   "이번 턴에 새로 검색을 했는가"로 판단해서 넘긴다(후속 발화 재사용 턴은 빈 리스트).
     */
    fun recordTurn(
        turnId: String,
        userText: String,
        assistantText: String,
        executedTools: List<String> = emptyList(),
        maxRecentMessages: Int = MAX_RECENT_MESSAGES,
        maxHistoryDigestChars: Int = MAX_HISTORY_DIGEST_CHARS,
    ) {
        if (userText.isBlank() || assistantText.isBlank()) return
        if (turnId == inFlightTurnId) inFlightTurnId = null
        val now = System.currentTimeMillis()
        conversationMemory = ConversationMemoryReducer.reduceCompletedTurn(
            previous = conversationMemory,
            turnId = turnId,
            timestamp = now,
            userText = userText,
            executedTools = executedTools,
        )
        if (maxRecentMessages > 0) {
            recent.addLast(Message(Role.User, userText))
            recent.addLast(Message(Role.Assistant, assistantText))
        } else {
            recent.clear()
        }
        val overflow = recent.size - maxRecentMessages
        if (overflow > 0) {
            val evicted = List(overflow) { recent.removeFirst() }
            conversationMemory = conversationMemory.copy(
                historyDigest = mergeHistoryDigest(
                    previous = conversationMemory.historyDigest,
                    evicted = evicted,
                    maxChars = maxHistoryDigestChars,
                )
            )
        }
    }

    fun putToolContext(key: String, value: String?) {
        if (value.isNullOrBlank()) toolContext.remove(key) else toolContext[key] = value
    }

    fun toolContextValue(key: String): String? = toolContext[key]

    fun reset() {
        recent.clear()
        toolContext.clear()
        conversationMemory = ConversationMemory()
        inFlightTurnId = null
    }

    /**
     * sojung 원본의 `AgentSessionManager.mergeHistoryDigest`를 그대로 이식했다: 밀려난
     * 메시지를 한 줄(최대 500자)로 줄여 이어붙이고, 예산을 넘으면 뒤(최신)를 남기고
     * 앞(오래된 것)부터 버린다. `substringAfter('\n')`로 줄 경계에 맞춰 자른다 —
     * 그냥 `takeLast`만 쓰면 문장 중간이 잘려 시작 줄이 의미 없는 조각이 된다.
     */
    private fun mergeHistoryDigest(
        previous: String,
        evicted: List<Message>,
        maxChars: Int,
    ): String {
        if (maxChars <= 0 || evicted.isEmpty()) return ""
        val addition = evicted.joinToString("\n") { message ->
            val speaker = if (message.role == Role.User) "사용자" else "어시스턴트"
            "$speaker: ${message.text.normalizeForDigest().take(MAX_DIGEST_MESSAGE_CHARS)}"
        }
        val merged = listOf(previous, addition).filter(String::isNotBlank).joinToString("\n")
        return if (merged.length <= maxChars) merged
        else merged.takeLast(maxChars).substringAfter('\n', missingDelimiterValue = merged.takeLast(maxChars))
    }

    private fun String.normalizeForDigest(): String = replace(Regex("\\s+"), " ").trim()

    /**
     * 블록을 규격대로 조립한다. 비어 있는 블록은 넣지 않는다(2B 모델에 빈 헤더는 노이즈).
     * sojung 원본의 `formatConversationMemory`/`formatRecentConversation`과 같은 구조·
     * 같은 섹션 이름을 쓴다.
     *
     * pendingActions 렌더링에서 **이번에 진행 중인 턴 자신의 항목만** 뺀다. [beginTurn]이
     * 답변 생성 전에 이번 질문을 pendingActions에 미리 걸어두는데, 그 memory를 같은 턴의
     * 프롬프트에도 그대로 넣으면 `[current_user]`의 질문과 완전히 같은 텍스트가 두 번
     * 나온다 — 실측 결과 소형(2B) 모델이 새로 답을 만드는 대신 그 텍스트를 그대로
     * 되돌려주는 원인이었다("메일은?" 질문에 "메일은?" 그대로 에코, A/B로 원인 확정).
     * 저장(conversationMemory 자체)은 안 건드린다 — 렌더링만 거른다. 그래서 이 턴이
     * 실패하면 다음 턴에는(그때는 inFlightTurnId가 새 턴 id로 바뀌므로) 정상적으로
     * "아직 처리 못한 요청"으로 나타난다.
     */
    fun buildContextBlocks(currentUser: String): String = buildString {
        // 작업 장부(pending/resolved)는 modelView() 가 걷어낸다 — 모델에 안 보낸다.
        val m = conversationMemory.modelView()
        if (m != ConversationMemory()) {
            append("[conversation_memory]\n")
            append("schema_version: ").append(m.schemaVersion).append('\n')
            if (m.topic.isNotBlank()) append("topic: ").append(m.topic).append('\n')
            appendItems("confirmed_facts", m.confirmedFacts)
            appendItems("preferences", m.preferences)
            appendItems("constraints", m.constraints)
            if (m.historyDigest.isNotBlank()) append("history_digest:\n").append(m.historyDigest).append('\n')
            append('\n')
        }
        if (recent.isNotEmpty()) {
            append("[recent_conversation]\n")
            recent.forEach { msg ->
                val speaker = if (msg.role == Role.User) "사용자" else "어시스턴트"
                append(speaker).append(": ")
                    .append(msg.text.replace('\n', ' ').take(MAX_RECENT_MESSAGE_CHARS))
                    .append('\n')
            }
            append('\n')
        }
        if (toolContext.isNotEmpty()) {
            append("[tool_session_context]\n")
            toolContext.forEach { (k, v) -> append(k).append(": ").append(v).append('\n') }
            append('\n')
        }
        append("[current_user]\n").append(currentUser)
    }

    private fun StringBuilder.appendItems(label: String, items: List<ConversationMemoryItem>) {
        if (items.isEmpty()) return
        append(label).append(":\n")
        items.forEach { append("- ").append(it.content).append('\n') }
    }

    companion object {
        const val MAX_RECENT_MESSAGES = 8

        /**
         * sojung 원본은 1,500자다(구조화 메모리가 선호/제약/사실을 따로 들고 있어서, 예전
         * flat summary의 3,000자보다 history digest에 덜 의존해도 된다).
         */
        const val MAX_HISTORY_DIGEST_CHARS = 1_500
        private const val MAX_DIGEST_MESSAGE_CHARS = 500
        private const val MAX_RECENT_MESSAGE_CHARS = 1_000

        /** tool_session_context 키 — 검색 tool 이 저장하는 값. */
        const val KEY_FOCUS_PERSON = "focus_person"
        const val KEY_LAST_CARD_IDS = "last_card_ids"
        const val KEY_LAST_QUERY = "last_query"

        /**
         * 직전 턴이 물어본 속성("회사", "주소", "직급" …).
         * 다음 턴이 주어만 말하고 속성을 생략했을 때("음가영씨는?") 이어받으려고 둔다.
         */
        const val KEY_LAST_ATTRIBUTE = "last_attribute"

        /**
         * 직전 턴에 적용된 필드 조건어("대전", "변호사" …).
         * 다음 턴이 "그중에 …" 로 좁히려 할 때 이어받으려고 둔다.
         */
        const val KEY_LAST_FILTER_TERMS = "last_filter_terms"

        /**
         * 대화에서 화제가 된 인물을 **처음 나온 순서대로** 쉼표로 이어 둔 목록.
         *
         * "처음에 물어본 사람 전화번호는?" 같은 담화 순서 지시를 풀려면 지난 발화의
         * 인물을 알아야 하는데, [recentMessages] 는 최근 8개(4턴)만 들고 있어서
         * **그보다 오래된 인물에는 닿지 않는다**. 그래서 창과 무관하게 여기에 쌓아 둔다.
         * 이름만 모으므로 길어져도 몇백 바이트다.
         */
        const val KEY_SUBJECT_HISTORY = "subject_history"

        /**
         * 이름 -> 카드 id 를 "이름=id" 로 이어 둔 목록. **동명이인을 이 대화가 어느 쪽으로
         * 정했는지** 기억한다.
         *
         * [KEY_SUBJECT_HISTORY] 는 이름만 담아서 "백다인이 나왔다"까지만 안다. 그런데
         * 카드에 백다인은 두 장이다. 회사로 한 명을 특정한 뒤("샤인기계 백다인씨") 몇 턴
         * 지나서 이름만으로 다시 부르면, focus 도 직전 카드 id 도 그새 방해 인물로 덮여서
         * 어느 백다인인지 알 길이 없다 — 통합 벤치 v1.3 실패 3건이 전부 이것이고,
         * 되묻지도 않고 틀린 번호를 줬다.
         *
         * 이름이 **한 장으로 좁혀진** 턴에서만 기록한다. 애매한 채로 지나간 턴을 기억하면
         * 나중에 그 애매함을 확신으로 둔갑시킨다.
         */
        const val KEY_SUBJECT_CARDS = "subject_cards"
    }
}
