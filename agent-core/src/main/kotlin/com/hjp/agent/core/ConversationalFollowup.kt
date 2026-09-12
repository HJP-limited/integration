package com.hjp.agent.core

import com.hjp.agent.contract.ModelConversationRole
import com.hjp.agent.contract.TurnContext

/**
 * '새 검색'이 아니라 '직전 답변에 대한 반응'인 발화를 가려낸다.
 *
 * ryeong 저장소의 `ConversationalFollowup` 이식본이다. 원본이 실기기에서 재고 고쳐 둔
 * 결함이 통합본에는 없었다: "판교에서 일하는 사람 몇명이지" 다음에 **"5명인데?"** 라고
 * 정정하면 그게 새 검색어로 취급돼 엉뚱한 카드(충청북도의 무관한 사람)를 물어오고, 그걸
 * 근거로 답을 만들어 대화가 끊겼다. 이런 발화에는 검색할 내용이 아예 없으므로 직전 턴의
 * 결과를 그대로 근거로 써야 한다.
 *
 * 개념형 질의("돈 관리하는 사람")를 잘못 삼키지 않도록 '내용어가 없다' 같은 느슨한 판정이
 * 아니라 정정/확인/메타 발화의 **명시적 패턴만** 본다. 애매하면 false 를 돌려주고 평소처럼
 * 검색하게 둔다 — 잘못 삼키면 멀쩡한 검색이 사라지지만, 놓치면 예전과 같을 뿐이다.
 *
 * 단수 대명사("그 사람", "그분")는 여기서 다루지 않는다. 그건 [DeterministicTurnRouter] 가
 * focus 로 치환해 정상 검색으로 보낸다 — 여기 넣으면 "그 사람 부서는?" 처럼 **속성을 새로
 * 묻는** 질문에까지 직전 답변을 재생하게 된다(상류 실측).
 */
internal object ConversationalFollowup {

    private val META_PATTERNS = listOf(
        "인데", "아닌데", "아니야", "아니고", "아냐", "맞아", "맞나", "맞지", "틀렸", "잘못",
        "다시", "왜", "진짜", "정말", "확실", "그래서",
    )

    private val COUNT_UNITS = listOf("명", "개", "건", "곳", "군데")

    /**
     * 무언가를 **해 달라는** 말. 이게 있으면 반응이 아니라 요청이다.
     *
     * 상류에도 같은 거름망(`hasExplicitSearchIntent`)이 있다. 없으면 "다시" 하나 때문에
     * "다시 시도", "다시 검색해줘" 가 되짚기로 잡혀 직전 답변만 되풀이하고 정작 요청은
     * 실행되지 않는다 — 이식하면서 이걸 빠뜨려 커널 회귀 시험이 바로 잡아냈다.
     */
    private val EXPLICIT_REQUEST = listOf(
        "검색", "찾아", "조회", "최신", "새로", "시도", "알려", "보여", "해줘", "해 줘",
    )

    /**
     * 복수 지시어 — "그 사람들 이름 알려줘" 처럼 **직전 결과 집합 전체**를 가리키는 발화.
     *
     * 단수 focus 치환으로는 못 잡는다. 지시어가 명시적으로 앞을 가리키므로 새로 검색하지
     * 않고 직전 후보 집합을 근거로 쓰는 게 정의상 맞다.
     */
    private val GROUP_REFERENCES = listOf(
        "그 사람들", "그사람들", "그분들", "그 분들", "이 사람들", "이사람들",
        "저 사람들", "저사람들", "그들", "걔네", "그 명단", "그 목록", "위 사람들", "방금 그",
    )

    /** 직전 집합 전체를 가리키는 발화인가. */
    fun pointsAtPreviousGroup(question: String): Boolean {
        val q = question.trim()
        return GROUP_REFERENCES.any { it in q }
    }

    /**
     * 직전 답변에 대한 정정·확인·되묻기인가. true 면 새로 검색하지 않는다.
     *
     * 문장이 길면 새 질문일 가능성이 크므로 낱말 넷까지만 본다 — "왜"나 "다시" 같은 흔한
     * 낱말이 긴 문장 안에 우연히 들어 있다고 되짚기로 볼 수는 없다.
     */
    fun isReactionToLastAnswer(question: String): Boolean {
        val q = question.trim().trimEnd('?', '!', '.', ' ')
        if (q.isEmpty()) return false
        if (EXPLICIT_REQUEST.any { it in q }) return false
        val tokens = q.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size > 4) return false
        if (META_PATTERNS.any { it in q }) return true
        // "5명", "3개" 처럼 수량만 말한 정정도 대화형으로 본다.
        return tokens.size <= 2 && q.any { it.isDigit() } && COUNT_UNITS.any { it in q }
    }

    /**
     * 직전 집합을 그대로 읽어 준다. 가리킬 집합이 없으면 null — 그러면 평소대로 검색한다.
     *
     * 이름만 나열하지 않고 회사·직함까지 붙이는 이유: 같은 이름이 둘 이상일 때 목록만으로는
     * 누가 누군지 못 고른다. [com.hjp.agent.contract.ContactCandidate] 가 이미 그 용도로
     * 구별 문구를 들고 있다.
     */
    fun groupAnswer(context: TurnContext): String? {
        val candidates = context.memory.candidateContacts
        if (candidates.isEmpty()) return null
        return buildString {
            append("직전에 찾은 ").append(candidates.size).append("명입니다.")
            candidates.forEach { append("\n- ").append(it.distinguishingLabel) }
        }
    }

    /**
     * 직전 답변을 근거로 삼아 되돌려 준다. 되돌릴 답이 없으면 null.
     *
     * 새 근거를 만들지 않는다는 점이 중요하다 — 정정 발화에는 검색할 내용이 없으므로,
     * 없는 근거를 지어내는 대신 방금 한 말을 다시 놓고 사용자가 이어 말하게 한다.
     */
    fun lastAnswer(context: TurnContext): String? =
        context.transcript.asReversed()
            .firstOrNull { it.role == ModelConversationRole.ASSISTANT }
            ?.text
            ?.takeIf { it.isNotBlank() }
}
