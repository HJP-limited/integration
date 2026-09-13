package com.hjp.agent.core

import com.hjp.agent.contract.ContactCandidate
import com.hjp.agent.contract.ConversationMemory
import com.hjp.agent.contract.ModelConversationRole
import com.hjp.agent.contract.TranscriptEntry
import com.hjp.agent.contract.TurnContext
import com.hjp.agent.contract.TurnRoutePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 원본 저장소에는 있었는데 통합하면서 빠졌던 동작들.
 *
 * 셋 다 "원본이 실기기에서 재고 고쳐 둔 결함이, 합치는 과정에서 되살아난" 경우다. 시험을
 * 여기 모아 두는 이유는 각각이 어느 원본에서 온 것인지와 **무엇이 빠져 있었는지**를 한자리에
 * 남기기 위해서다 — 같은 것이 또 빠지면 여기서 걸린다.
 *
 * 카드는 전부 합성이고 어떤 평가 픽스처에도 나오지 않는다.
 */
class RecoveredUpstreamBehaviourTest {

    private fun context(
        text: String,
        candidates: List<ContactCandidate> = emptyList(),
        lastAnswer: String? = null,
    ): TurnContext = TurnContext(
        userText = text,
        memory = ConversationMemory(candidateContacts = candidates),
        transcript = buildList {
            if (lastAnswer != null) {
                add(TranscriptEntry("t1", ModelConversationRole.USER, "앞선 질문", 1L))
                add(TranscriptEntry("t1", ModelConversationRole.ASSISTANT, lastAnswer, 2L))
            }
        },
    )

    private val people = listOf(
        ContactCandidate("RC001", "남다은", "너울건설", "상무"),
        ContactCandidate("RC002", "손서윤", "새벽테크", "연구소장"),
        ContactCandidate("RC003", "두미영", "물결식품", "영업이사"),
        ContactCandidate("RC004", "정하은", "밀물소프트", "팀장"),
        ContactCandidate("RC005", "차우진", "노을바이오", "이사"),
    )

    // ---- 1. 서수는 셋에서 멈추지 않는다 (ryeong ConversationalFollowup.ordinalIndex) ---------

    @Test
    fun `an ordinal past the third still selects from the previous result set`() {
        // 첫·두·세 까지만 세던 때는 네 번째부터 아무 규칙에도 안 걸려 새 검색으로 빠졌다.
        val plan = DeterministicTurnRouter.route(context("네 번째 사람 연락처", people))
        assertTrue(
            "expected a grounded selection, got $plan",
            plan is TurnRoutePlan.GroundedContact || plan is TurnRoutePlan.ContactDetail,
        )
    }

    @Test
    fun `a numeric ordinal selects from the previous result set`() {
        val plan = DeterministicTurnRouter.route(context("5번째 사람 연락처", people))
        assertTrue(
            "expected a grounded selection, got $plan",
            plan is TurnRoutePlan.GroundedContact || plan is TurnRoutePlan.ContactDetail,
        )
    }

    @Test
    fun `마지막 selects the final result rather than failing to resolve`() {
        val plan = DeterministicTurnRouter.route(context("마지막 사람 연락처", people))
        assertTrue(
            "expected a grounded selection, got $plan",
            plan is TurnRoutePlan.GroundedContact || plan is TurnRoutePlan.ContactDetail,
        )
    }

    // ---- 2. 복수 지시어 (ryeong ConversationalFollowup.GROUP_REFERENCES) --------------------

    @Test
    fun `a plural reference reads the previous set instead of searching for the pronoun`() {
        // "그 사람들" 은 단수 focus 치환으로 잡히지 않아 그대로 검색어가 됐다.
        val plan = DeterministicTurnRouter.route(context("그 사람들 이름 알려줘", people))
        val answer = plan as? TurnRoutePlan.AnswerFromHistory
            ?: error("expected AnswerFromHistory, got $plan")
        assertTrue(answer.messageKo, answer.messageKo.contains("남다은"))
        assertTrue(answer.messageKo, answer.messageKo.contains("차우진"))
    }

    @Test
    fun `a plural reference with nothing to point at still searches`() {
        // 가리킬 집합이 없으면 되짚을 것도 없다. 평소대로 내려보낸다.
        val plan = DeterministicTurnRouter.route(context("그 사람들 이름 알려줘"))
        assertFalse("expected a normal route, got $plan", plan is TurnRoutePlan.AnswerFromHistory)
    }

    // ---- 3. 정정/확인 발화 (ryeong ConversationalFollowup.isFollowup) -----------------------

    @Test
    fun `a correction about the count reuses the previous answer instead of searching`() {
        // 상류 실측: "5명인데?" 가 검색어가 돼 충청북도의 무관한 사람을 근거로 답했다.
        val plan = DeterministicTurnRouter.route(
            context("5명인데?", lastAnswer = "판교에서 일하는 분은 12명입니다."),
        )
        val answer = plan as? TurnRoutePlan.AnswerFromHistory
            ?: error("expected AnswerFromHistory, got $plan")
        assertTrue(answer.messageKo, answer.messageKo.contains("12명"))
    }

    @Test
    fun `a bare denial reuses the previous answer`() {
        val plan = DeterministicTurnRouter.route(
            context("아닌데", lastAnswer = "남다은님은 너울건설 상무입니다."),
        )
        assertTrue("expected AnswerFromHistory, got $plan", plan is TurnRoutePlan.AnswerFromHistory)
    }

    @Test
    fun `a redo request is a request, not a reaction`() {
        // "다시" 만 보고 되짚기로 판정하면 재시도 요청이 실행되지 않고 직전 답만 되풀이된다.
        assertFalse(ConversationalFollowup.isReactionToLastAnswer("다시 시도"))
        assertFalse(ConversationalFollowup.isReactionToLastAnswer("다시 검색해줘"))
    }

    @Test
    fun `a substantive question is never mistaken for a reaction`() {
        // 개념형 질의를 삼키면 멀쩡한 검색이 사라진다. 이쪽이 더 나쁜 실패다.
        assertFalse(ConversationalFollowup.isReactionToLastAnswer("돈 관리하는 사람"))
        assertFalse(ConversationalFollowup.isReactionToLastAnswer("판교에 있는 AI 개발자 찾아줘"))
    }

    @Test
    fun `a reaction with no previous answer falls through to normal handling`() {
        val plan = DeterministicTurnRouter.route(context("아닌데"))
        assertFalse("expected a normal route, got $plan", plan is TurnRoutePlan.AnswerFromHistory)
    }

    // ---- 4. 서수 파싱 자체 -----------------------------------------------------------------

    @Test
    fun `ordinal words cover 첫 through 열`() {
        val expected = listOf("첫" to 0, "두" to 1, "세" to 2, "네" to 3, "다섯" to 4,
            "여섯" to 5, "일곱" to 6, "여덟" to 7, "아홉" to 8, "열" to 9)
        expected.forEach { (word, index) ->
            val plan = DeterministicTurnRouter.route(context("$word 번째 사람 연락처", people))
            // 집합이 5명뿐이라 여섯 번째부터는 고를 수 없다 — 그때는 되물어야 하고,
            // 그것도 "검색으로 빠지지 않았다"는 증거다.
            val resolved = plan is TurnRoutePlan.GroundedContact ||
                plan is TurnRoutePlan.ContactDetail ||
                plan is TurnRoutePlan.Clarify
            assertTrue("$word 번째 (index $index) fell through to $plan", resolved)
        }
    }

    @Test
    fun `an ordinal that is not about a person is left alone`() {
        // "두 번째 질문" 은 결과 집합의 두 번째가 아니다.
        val plan = DeterministicTurnRouter.route(context("두 번째 질문이 뭐였지", people))
        assertEquals(false, plan is TurnRoutePlan.GroundedContact)
    }

    // ---- 5. 장소 조건 검색 ---------------------------------------------------------------

    @Test
    fun `a place condition with a generic person word is a search`() {
        // "분당구에 있는 사람 찾아줘" 가 아무 분류도 못 받고 정책에 거부됐다
        // ("연락처 검색이 필요한 요청이 아닙니다", 노트북 러너 실측). 장소도 회사·부서와
        // 같은 속성인데 속성 목록에 장소가 없었다.
        listOf(
            "분당구에 있는 사람 찾아줘",
            "판교에서 일하는 분 찾아줘",
            "대전에 근무하는 직원 찾아줘",
        ).forEach { text ->
            val plan = DeterministicTurnRouter.route(context(text))
            assertFalse(
                "$text was settled without searching: $plan",
                plan is TurnRoutePlan.Clarify || plan is TurnRoutePlan.Unsupported,
            )
        }
    }

    @Test
    fun `a place role marker does not turn a compose into a search`() {
        // 장소를 속성으로 인정하되 실행 의도까지 삼키면 안 된다.
        val plan = DeterministicTurnRouter.route(context("판교에서 만난 사람에게 메일 보내줘"))
        assertFalse(
            "compose was reclassified as a search: $plan",
            plan is TurnRoutePlan.AnswerFromHistory,
        )
    }
}
