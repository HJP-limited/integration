package com.example.hjp.agent

/**
 * '새 검색'이 아니라 '직전 답변에 대한 반응'인 발화를 가려낸다.
 *
 * 왜 필요한가(실측): "판교에서 일하는 사람 몇명이지" 다음에 "5명인데?" 라고 정정하면
 * 그게 새 검색어로 취급돼 검색이 엉뚱한 카드(충청북도의 무관한 사람)를 물어오고,
 * 그걸 근거로 답을 만들어 대화가 끊겼다. 이런 발화에는 검색할 내용이 아예 없으므로
 * 직전 턴의 결과를 그대로 근거로 재사용해야 한다.
 *
 * 개념형 질의("돈 관리하는 사람")를 잘못 삼키지 않도록 '내용어가 없다' 같은 느슨한 판정이
 * 아니라 정정/확인/메타 발화의 명시적 패턴만 본다.
 */
object ConversationalFollowup {

    private val META_PATTERNS = listOf(
        "인데", "아닌데", "아니야", "아니고", "아냐", "맞아", "맞나", "맞지", "틀렸", "잘못",
        "다시", "왜", "진짜", "정말", "확실", "그래서", "응", "네", "아니",
    )

    private val COUNT_UNITS = listOf("명", "개", "건", "곳", "군데")

    /**
     * 복수 지시어 — "그 사람들 이름 알려줘" 처럼 '직전 결과 집합 전체'를 가리키는 발화.
     * focus(단일 인물) 치환으로는 못 잡는다. 지시어가 명시적으로 앞을 가리키므로
     * 새로 검색하지 않고 직전 카드 집합을 근거로 쓰는 게 정의상 맞다.
     */
    private val GROUP_REFERENCES = listOf(
        "그 사람들", "그사람들", "그분들", "그 분들", "이 사람들", "이사람들",
        "저 사람들", "그들", "걔네", "그 명단", "그 목록", "위 사람들", "방금 그",
    )

    /**
     * 직전 답변에 대한 정정·확인·되묻기인지 판정한다. true 면 새로 검색하지 않고
     * 직전 턴의 카드를 그대로 근거로 써서 답한다.
     * 보수적으로 판정한다 — 애매하면 false 를 반환해 평소처럼 검색하게 둔다.
     */
    fun isFollowup(question: String): Boolean {
        val q = question.trim().trimEnd('?', '!', '.', ' ')
        if (q.isEmpty()) return false
        // 복수 지시어는 길이와 무관하게 직전 집합을 가리킨다("그 사람들 이름이랑 회사 알려줘").
        if (GROUP_REFERENCES.any { it in q }) return true
        val tokens = q.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size > 4) return false // 문장이 길면 새 질문일 가능성이 크다
        if (META_PATTERNS.any { it in q }) return true
        // "5명", "3개" 처럼 수량만 말한 정정도 대화형으로 본다.
        return tokens.size <= 2 && q.any { it.isDigit() } && COUNT_UNITS.any { it in q }
    }

    // --- 아래는 sojung `feature/runtime-multiturn-summary` 의 AgentKernel 에 있는
    // contextAnswerForSearch / isContextReference / hasExplicitSearchIntent 를 옮긴 것.
    // 원본은 모델이 search_contacts 를 호출하려 할 때 가로채는데, 이 저장소는 도구 호출
    // 루프가 아니라 검색을 직접 부르므로 검색 직전에 같은 판정을 한다.

    /**
     * **되짚는 표현만** 넣는다. "그 사람"·"그 회사" 같은 대명사는 뺐다 —
     * 그건 FOLLOWUP_PRONOUNS(focus 치환)가 담당하는데, 여기 있으면 contextAnswer 가 먼저
     * 채가서 "그 사람 부서는?", "그 회사 주소는?" 처럼 **속성을 새로 묻는** 질문에도
     * 직전 답변을 그대로 재생했다(실측). 대명사를 빼면 focus 로 치환돼 정상 검색으로 간다.
     */
    private val CONTEXT_REFERENCES =
        listOf("아까", "방금", "앞서", "이전", "말한", "찾은", "기억")

    /**
     * 사람을 가리키는 지시어. 되짚기와 새 질문을 가르는 기준이다.
     *
     * 대명사를 [CONTEXT_REFERENCES] 에서 뺐어도 "아까"가 남아 여기로 새고 있었다 —
     * "아까 그 사람 회사 알려줘" 가 되짚기로 잡혀 직전 답변을 재생했다
     * (실측: Final50 v3 에서 sc_03·sc_08·lr_04·er_04 가 전부 이 경로).
     * "아까 말한 회사 뭐였지"(되짚기)는 사람을 가리키지 않으므로 그대로 남는다.
     */
    private val PERSON_DEIXIS = listOf(
        "그 사람", "그사람", "그 분", "그분", "이 사람", "이사람",
        "저 사람", "저사람", "이 분", "이분", "걔",
    )

    internal fun pointsAtPerson(text: String) = PERSON_DEIXIS.any(text::contains)

    private val EXPLICIT_SEARCH_INTENT =
        listOf("검색", "찾아줘", "찾아 줘", "조회", "최신", "새로")

    private val ORDINAL_WORDS = listOf(
        "첫" to 0, "두" to 1, "세" to 2, "네" to 3, "다섯" to 4,
        "여섯" to 5, "일곱" to 6, "여덟" to 7, "아홉" to 8, "열" to 9,
    )

    /**
     * "두 번째 사람", "3번째", "마지막" — 직전 결과 **집합 안에서** 하나를 고르는 표현.
     *
     * 0부터 세는 인덱스를 돌려주고, "마지막"은 -1(호출부에서 마지막 원소로 해석).
     * 이게 없을 때는 새 검색으로 빠져서 무관한 사람이 나왔다(실측: "대전에 있는 변호사"
     * 로 2명을 찾은 뒤 "두 번째 사람 연락처" 가 엉뚱한 5명을 데려왔다).
     * 직전 집합을 가리키는 발화이므로 정의상 재검색이 아니라 후속이다.
     */
    fun ordinalIndex(question: String): Int? {
        if ("마지막" in question) return -1
        Regex("(\\d+)\\s*번째").find(question)?.let {
            val n = it.groupValues[1].toIntOrNull() ?: return@let
            if (n >= 1) return n - 1
        }
        for ((word, idx) in ORDINAL_WORDS) {
            if (Regex("$word\\s*번째").containsMatchIn(question)) return idx
        }
        return null
    }

    fun isContextReference(text: String) = CONTEXT_REFERENCES.any(text::contains)

    fun hasExplicitSearchIntent(text: String) = EXPLICIT_SEARCH_INTENT.any(text::contains)

    /**
     * 문맥을 가리키는 발화이고 새로 검색하라는 말이 없으면, 검색을 건너뛰고 이미 아는
     * 것으로 답한다. 답할 근거가 없으면 null 을 돌려주고 평소대로 검색한다.
     *
     * 주의: `user.*` 사실은 **사용자 본인**에 대한 것이다("내 회사는 …"라고 직접 말했을
     * 때만 생긴다). 이 앱에서 "회사"는 보통 명함 속 인물의 회사라, 사용자가 자기 회사를
     * 말해 둔 상태에서 "그 사람 회사 어디야?"를 물으면 엉뚱하게 자기 회사를 답할 수 있다.
     * 원본 방식을 그대로 따르되 이 지점은 실사용으로 확인할 것.
     */
    fun contextAnswer(session: AgentSession, question: String): String? {
        if (!isContextReference(question) || hasExplicitSearchIntent(question)) return null
        // 사람을 가리키면 되짚기가 아니라 그 사람에 대한 **새 질문**이다.
        if (pointsAtPerson(question)) return null
        val normalized = question.lowercase()
        val facts = session.conversationMemory.confirmedFacts
        val fact = when {
            "회사" in normalized -> facts.firstOrNull { it.key == "user.company" }
            "직책" in normalized || "직함" in normalized -> facts.firstOrNull { it.key == "user.title" }
            "이름" in normalized -> facts.firstOrNull { it.key == "user.name" }
            else -> null
        }
        if (fact != null) return "이전 대화에서 확인된 정보입니다: ${fact.content}"
        val previous = session.recentMessages.asReversed()
            .firstOrNull { it.role == AgentSession.Role.Assistant }
            ?.text
        return previous?.takeIf { it.isNotBlank() }?.let { "이전 대화 기준으로 답변합니다.\n$it" }
    }
}
