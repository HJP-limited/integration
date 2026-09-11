package com.hjp.agent.core

import com.hjp.agent.contract.ContactCandidate
import com.hjp.agent.contract.ContactReference
import com.hjp.agent.contract.ContactSelectionBasis
import com.hjp.agent.contract.ConversationMemory
import com.hjp.agent.contract.DialogueAct
import com.hjp.agent.contract.MemoryProvenance
import com.hjp.agent.contract.ModelConversationRole
import com.hjp.agent.contract.TranscriptEntry
import com.hjp.agent.contract.TurnContext
import com.hjp.agent.contract.TurnRoutePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The six meanings the router has to keep apart.
 *
 * Each group below is a *kind* of sentence, not a list of sentences that once failed. The wordings
 * are varied on purpose — several of them never appeared in any evaluation set — because the thing
 * being asserted is that the distinction generalises, not that a phrase was added to a list.
 */
class RouterGeneralizationTest {

    // ---- an action command is still an action command -------------------------------------------

    @Test
    fun `reading the contact store is one intent however it is worded`() {
        listOf(
            "김지원 명함 찾아줘.",
            "김지원 명함 검색해줘.",
            "김지원 연락처 조회해줘.",
            "김지원 명함 보여줘.",
            "김지원 연락처 확인해줘.",
            "김지원 명함 좀 알려줘.",
        ).forEach {
            assertEquals(it, DialogueAct.CONTACT_SEARCH, DeterministicTurnRouter.act(context(it)))
        }
    }

    @Test
    fun `a person marked search is a contact lookup without a card noun`() {
        listOf("우성씨 찾아줘.", "해나님 검색해 줘.").forEach {
            assertEquals(it, DialogueAct.CONTACT_SEARCH, DeterministicTurnRouter.act(context(it)))
        }
    }

    @Test
    fun `explicit company department and title searches seed contact search`() {
        listOf(
            "(주) 다이나믹스튜디오 사람 찾아줘",
            "개발팀 사람 검색해줘",
            "팀장 사람 찾아줘",
            "Lead Designer 사람 찾아줘",
        ).forEach {
            assertEquals(it, DialogueAct.CONTACT_SEARCH, DeterministicTurnRouter.act(context(it)))
        }
    }

    @Test
    fun `attribute questions and vague contact phrases do not seed search`() {
        listOf(
            "회사 정보 알려줘",
            "그 사람 회사가 어디야?",
            "그 사람 말고",
            "일반적인 직급 체계가 어떻게 돼?",
        ).forEach {
            assertTrue(
                it,
                DeterministicTurnRouter.act(context(it)) != DialogueAct.CONTACT_SEARCH,
            )
        }
    }

    @Test
    fun `attribute search preserves the original query on directory miss`() {
        listOf(
            "(주) 다이나믹스튜디오 사람 찾아줘",
            "개발팀 사람 검색해줘",
            "Lead Designer 사람 찾아줘",
        ).forEach { text ->
            val plan = DeterministicTurnRouter.route(context(text))
            assertTrue(text, plan is TurnRoutePlan.Continue)
            assertEquals(text, (plan as TurnRoutePlan.Continue).text)
        }
    }

    @Test
    fun `contact detail follow-up targets a unique selected focus`() {
        val plan = DeterministicTurnRouter.route(context("연락처 알려줘", memory = focus()))
        assertTrue(plan is TurnRoutePlan.ContactDetail)
        assertEquals("C001", (plan as TurnRoutePlan.ContactDetail).cardId)
    }

    @Test
    fun `contact detail follow-up stays ambiguous without a selected focus`() {
        val memory = ConversationMemory(candidateContacts = listOf(
            ContactCandidate("C001", "김지원", "비전글로벌", "대표이사"),
            ContactCandidate("C002", "박민수", "한빛", "팀장"),
        ))
        val plan = DeterministicTurnRouter.route(context("연락처 알려줘", memory = memory))
        assertTrue(plan is TurnRoutePlan.Clarify)
    }

    @Test
    fun `editing a card is one intent however the verb is inflected`() {
        listOf(
            "김지원 명함 수정해줘.",
            "김지원 명함 변경해줘.",
            "김지원 명함 고쳐줘.",
            "김지원 명함 바꿔줘.",
            "김지원 명함 업데이트해줘.",
        ).forEach {
            assertEquals(it, DialogueAct.ACTION_UPDATE, DeterministicTurnRouter.act(context(it)))
        }
    }

    // ---- a question about a concept is not a command ---------------------------------------------

    @Test
    fun `a general knowledge question names no target and runs no tool`() {
        listOf(
            "명함 스캔은 어떤 원리로 되는 거야?",
            "업무용 문자 예절 알려줘.",
            "직급 체계가 어떻게 되는지 설명해줘.",
            "회의 자료 정리 요령 알려줘.",
            "비즈니스 미팅 매너 알려줘.",
            "이메일 참조와 숨은참조 차이가 뭐야?",
        ).forEach { text ->
            val decision = DeterministicTurnRouter.route(context(text))
            assertEquals(text, DialogueAct.GENERAL_INFORMATION, DeterministicTurnRouter.act(context(text)))
            assertTrue("$text -> $decision", decision is TurnRoutePlan.GeneralInformation)
        }
    }

    /** The answer has to be about the topic, not a bare refusal to act. */
    @Test
    fun `a general knowledge answer names the topic it understood`() {
        val plan = DeterministicTurnRouter.route(context("업무용 문자 예절 알려줘."))
            as TurnRoutePlan.GeneralInformation

        assertTrue(plan.messageKo, plan.messageKo.contains("예절"))
        assertTrue(plan.messageKo, plan.messageKo.contains("일반 지식"))
    }

    /** Sharing a word with a concept question must not disarm a real request. */
    @Test
    fun `an execution verb keeps a command out of the information route`() {
        listOf(
            "그 형식으로 메일 작성해줘.",
            "김지원 명함 검색해줘.",
            "내일 오후 3시 회의 일정 만들어줘.",
        ).forEach { text ->
            assertTrue(
                text,
                DeterministicTurnRouter.route(context(text)) !is TurnRoutePlan.GeneralInformation,
            )
        }
    }

    // ---- recalling a request is not making it ----------------------------------------------------

    @Test
    fun `asking what I requested is answered from the transcript`() {
        listOf(
            "내가 방금 문자 보내달라고 했던가?",
            "아까 일정 만들어달라고 했었지?",
            "내가 메일 써달라고 했나?",
            "내가 아까 명함 찾아달라고 요청했지?",
        ).forEach { text ->
            val plan = DeterministicTurnRouter.route(
                context(text, transcript = transcript(ModelConversationRole.USER to "김지원 명함 찾아줘.")),
            )
            assertEquals(text, DialogueAct.QUOTED_RECALL, DeterministicTurnRouter.act(
                context(text, transcript = transcript(ModelConversationRole.USER to "김지원 명함 찾아줘.")),
            ))
            assertTrue("$text -> $plan", plan is TurnRoutePlan.AnswerFromHistory)
        }
    }

    /**
     * Recall grammar and an attribute question overlap in Korean. "…라고 했지?" ends both, so the
     * subject of the sentence is what decides: a field of a known card is read from the card.
     */
    @Test
    fun `an attribute question phrased as a recall reads the card`() {
        listOf(
            "그 사람 회사가 어디라고 했지?",
            "그분 직함이 뭐라고 했지?",
            "그 사람 업종이 뭐라고 했나?",
        ).forEach { text ->
            val plan = DeterministicTurnRouter.route(context(text, memory = focus()))
            assertTrue("$text -> $plan", plan is TurnRoutePlan.ContactDetail)
            assertEquals(text, "C001", (plan as TurnRoutePlan.ContactDetail).cardId)
            assertEquals(text, DialogueAct.CONTACT_DETAIL, DeterministicTurnRouter.act(context(text, focus())))
        }
    }

    /** A quoted instruction stays a recall even though the quote names the person in focus. */
    @Test
    fun `a quoted instruction is never re-executed`() {
        val text = "내가 아까 ‘김지원 연락처 확인해줘’라고 했었지?"

        val plan = DeterministicTurnRouter.route(context(text, memory = focus()))

        assertTrue(plan.toString(), plan is TurnRoutePlan.AnswerFromHistory)
        assertEquals(DialogueAct.QUOTED_RECALL, DeterministicTurnRouter.act(context(text, focus())))
    }

    // ---- showing the card in focus is a read, not a selection -----------------------------------

    @Test
    fun `asking to see the contact in focus is a detail read`() {
        listOf("그분 연락처 좀 알려줘.", "그 사람 명함 보여줘.", "그분 명함 확인해줘.").forEach { text ->
            assertEquals(text, DialogueAct.CONTACT_DETAIL, DeterministicTurnRouter.act(context(text, focus())))
        }
    }

    @Test
    fun `picking one of several people stays a selection`() {
        val memory = ConversationMemory(
            candidateContacts = listOf(
                ContactCandidate("C002", "박민수", "한빛물산"),
                ContactCandidate("C003", "박민수", "그린테크"),
            ),
        )

        assertEquals(
            DialogueAct.CONTACT_SELECTION,
            DeterministicTurnRouter.act(context("두 번째 사람 명함 보여줘.", memory)),
        )
    }

    // ---- an app we do not integrate with ---------------------------------------------------------

    @Test
    fun `an unsupported destination decides the turn, not a word inside the request`() {
        listOf(
            "회의록 요약해서 슬랙에 올려줘." to "슬랙",
            "이 일정 노션에 정리해줘." to "노션",
            "명함 정보 카톡으로 보내줘." to "카카오톡",
        ).forEach { (text, app) ->
            val plan = DeterministicTurnRouter.route(context(text))
            assertTrue("$text -> $plan", plan is TurnRoutePlan.GeneralInformation)
            val message = (plan as TurnRoutePlan.GeneralInformation).messageKo
            assertTrue(message, message.contains(app))
            assertTrue(message, message.contains("지원하지 않습니다"))
            // It must not ask for an id or a slot as if it could still be done.
            assertTrue(message, !message.contains("알려 주세요"))
        }
    }

    private fun context(
        text: String,
        memory: ConversationMemory = ConversationMemory(),
        transcript: List<TranscriptEntry> = emptyList(),
    ) = TurnContext(text, memory, transcript, TOOLS)

    private fun transcript(vararg pairs: Pair<ModelConversationRole, String>) =
        pairs.mapIndexed { index, (role, text) -> TranscriptEntry("t$index", role, text, index.toLong()) }

    private fun focus() = ConversationMemory(
        selectedContact = ContactReference(
            cardId = "C001",
            name = "김지원",
            company = "비전글로벌",
            title = "대표이사",
            selection = ContactSelectionBasis.SINGLE_RESULT,
            provenance = MemoryProvenance.TOOL_VERIFIED,
            confirmedAtEpochMillis = 1,
        ),
        candidateContacts = listOf(ContactCandidate("C001", "김지원", "비전글로벌", "대표이사")),
    )

    private companion object {
        val TOOLS = setOf(
            "search_contacts", "get_contact", "open_compose",
            "create_calendar_event", "update_business_card", "get_current_datetime",
        )
    }
}
