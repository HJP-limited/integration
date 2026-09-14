package com.example.hjp

import com.hjp.agent.contract.DialogueAct
import com.hjp.agent.contract.TurnOutcomeType
import com.hjp.tool.contact.BusinessCardRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A request with no recipient in it must ask who, on either channel.
 *
 * "메일 작성해줘." used to behave differently from "문자 작성해줘." for no reason a user could see:
 * 메일 was in the router's attribute-starter list and 문자 was not, so the first sentence was read as
 * "그 사람의 메일", silently addressed to whoever had last been looked up, and then died with
 * "작성 화면 열기 절차를 완료하지 못했습니다" — never asking the question it needed to ask. These tests
 * pin both halves: the two channels behave the same, and an actual reference still resolves.
 */
class RecipientClarificationTest {

    private val jiwon = BusinessCardRecord(
        id = "C001", name = "김지원", company = "비전글로벌", title = "대표이사",
        industry = "IT", email = "jiwon@example.com", mobile = "010-1111-0001",
    )
    private val minsu = BusinessCardRecord(
        id = "C002", name = "박민수", company = "한빛물산", title = "영업팀장",
        industry = "유통", email = "minsu@example.com", mobile = "010-1111-0002",
    )
    private val noEmail = BusinessCardRecord(
        id = "C004", name = "최영희", company = "코어에이아이", title = "디자이너",
        industry = "IT", mobile = "010-1111-0004",
    )

    private fun harness(vararg cards: BusinessCardRecord) =
        MultiturnScenarioHarness(cards = if (cards.isEmpty()) listOf(jiwon, minsu) else cards.toList())

    // ---- symmetry -------------------------------------------------------------------------------

    @Test
    fun `both channels ask who, with a contact already in focus`() = runBlocking {
        listOf(
            "메일 작성해줘." to "메일 수신자",
            "문자 작성해줘." to "문자 수신자",
            "메일 써줘." to "메일 수신자",
            "문자 보내줘." to "문자 수신자",
        ).forEach { (text, expected) ->
            val h = harness()
            h.turn("김지원 명함 찾아줘.")

            val turn = h.turn(text)

            assertEquals("$text ran a tool", emptyList<String>(), turn.executedTools)
            assertEquals("$text opened a screen", 0, turn.newComposeDrafts.size)
            assertTrue("$text -> ${turn.answer}", turn.answer.contains(expected))
            // The focus itself is untouched: it is a memory, not this turn's target.
            assertEquals(jiwon.id, turn.memory.selectedContact?.cardId)
            h.close()
        }
    }

    @Test
    fun `both channels ask who, with an empty session`() = runBlocking {
        listOf("메일 작성해줘." to "메일 수신자", "문자 작성해줘." to "문자 수신자").forEach { (text, expected) ->
            val h = harness()
            val turn = h.turn(text)
            assertEquals(emptyList<String>(), turn.executedTools)
            assertTrue("$text -> ${turn.answer}", turn.answer.contains(expected))
            h.close()
        }
    }

    /** The clarification must not be the workflow's "retry" message. */
    @Test
    fun `the clarification names what is missing instead of reporting a failure`() = runBlocking {
        val h = harness()
        h.turn("김지원 명함 찾아줘.")

        val turn = h.turn("메일 작성해줘.")

        assertTrue(turn.answer, !turn.answer.contains("완료하지 못했습니다"))
        assertTrue(turn.answer, !turn.answer.contains("다시 시도해"))
        // v4 contract: a turn that stopped because a required slot is missing is a clarification,
        // not a general answer. v1-v3 froze the older typing and are read through
        // EvaluationContractVersion.LEGACY_V1_V3 instead of the app being held to it.
        assertEquals(TurnOutcomeType.CLARIFICATION_REQUIRED, turn.outcomeType)
        assertEquals(DialogueAct.ACTION_COMPOSE, turn.act)
        h.close()
    }

    // ---- an actual reference still resolves ------------------------------------------------------

    @Test
    fun `an explicit reference still reaches the contact in focus`() = runBlocking {
        val h = harness()
        h.turn("김지원 명함 찾아줘.")

        val turn = h.turn("그 사람에게 제목은 견적, 내용은 단가표 부탁드립니다 라고 메일 작성해줘.")

        assertEquals(listOf(GET, COMPOSE), turn.executedTools)
        assertEquals(jiwon.email, turn.newComposeDrafts.single().to)
        h.close()
    }

    /** An attribute noun at the front is a reference only when the sentence asks about it. */
    @Test
    fun `a bare attribute question still resolves against the focus`() = runBlocking {
        val h = harness()
        h.turn("김지원 명함 찾아줘.")

        val turn = h.turn("이메일 주소가 뭐야?")

        assertEquals(listOf(GET), turn.executedTools)
        assertEquals(DialogueAct.CONTACT_DETAIL, turn.act)
        assertTrue(turn.answer, turn.answer.contains(jiwon.email!!))
        h.close()
    }

    @Test
    fun `naming a different person overrides the focus on either channel`() = runBlocking {
        val h = harness()
        h.turn("김지원 명함 찾아줘.")

        val turn = h.turn("박민수에게 제목은 견적, 내용은 단가표 부탁드립니다 라고 메일 작성해줘.")

        assertEquals(minsu.email, turn.newComposeDrafts.single().to)
        assertTrue(h.messages.drafts.none { it.to == jiwon.email })
        h.close()
    }

    // ---- missing field, false completion, idempotency ---------------------------------------------

    @Test
    fun `a card with no address says so instead of pretending it can send`() = runBlocking {
        val h = harness(noEmail)
        h.turn("최영희 명함 찾아줘.")

        val turn = h.turn("그 사람에게 제목은 견적, 내용은 단가표 부탁드립니다 라고 메일 작성해줘.")

        assertEquals(listOf(GET), turn.executedTools)
        assertEquals(0, turn.newComposeDrafts.size)
        assertTrue(turn.answer, turn.answer.contains("이메일 주소 정보가 없습니다"))
        assertTrue(turn.answer, !turn.answer.contains("열었습니다"))
        h.close()
    }

    @Test
    fun `opening a compose screen is never reported as sending`() = runBlocking {
        val h = harness()
        h.turn("김지원 명함 찾아줘.")

        val turn = h.turn("그 사람에게 제목은 견적, 내용은 단가표 부탁드립니다 라고 메일 작성해줘.")

        assertTrue(turn.answer, turn.answer.contains("작성 화면을 열었습니다"))
        listOf("전송했습니다", "발송했습니다", "전송 완료", "보냈습니다").forEach {
            assertTrue("$it leaked into ${turn.answer}", !turn.answer.contains(it))
        }
        h.close()
    }

    /** An address is printed once. A duplicated recipient is a wrong answer even with a right call. */
    @Test
    fun `an address never appears doubled in the answer`() = runBlocking {
        val h = harness()
        h.turn("김지원 명함 찾아줘.")
        val compose = h.turn("그 사람에게 제목은 견적, 내용은 단가표 부탁드립니다 라고 메일 작성해줘.")
        val detail = h.turn("이메일 주소가 뭐야?")

        listOf(compose.answer, detail.answer).forEach { answer ->
            val local = jiwon.email!!.substringBefore('@')
            assertTrue(
                "doubled address in $answer",
                !answer.contains("$local@$local@") && !answer.contains("${jiwon.email}${jiwon.email}"),
            )
        }
        h.close()
    }

    @Test
    fun `repeating the same request opens exactly one screen per turn`() = runBlocking {
        val h = harness()
        h.turn("김지원 명함 찾아줘.")
        val body = "그 사람에게 제목은 견적, 내용은 단가표 부탁드립니다 라고 메일 작성해줘."

        val first = h.turn(body)
        val second = h.turn(body)

        assertEquals(1, first.newComposeDrafts.size)
        assertEquals(1, second.newComposeDrafts.size)
        assertEquals(2, h.messages.drafts.size)
        h.close()
    }

    /** The chain has to reach the acting tool, not stop after the lookup. */
    @Test
    fun `a contact-bound action continues past get_contact to the acting tool`() = runBlocking {
        val h = harness()
        h.turn("김지원 명함 찾아줘.")

        val compose = h.turn("그 사람에게 제목은 견적, 내용은 단가표 부탁드립니다 라고 메일 작성해줘.")
        val calendar = h.turn("그 사람과 2027년 5월 20일 오후 2시 협의 일정 만들어줘.")
        val update = h.turn("그 사람 메모를 재검토로 수정해줘.")

        assertEquals(listOf(GET, COMPOSE), compose.executedTools)
        assertEquals(listOf(GET, CALENDAR), calendar.executedTools)
        assertEquals(listOf(GET, UPDATE), update.executedTools)
        assertEquals(listOf(jiwon.email), calendar.newCalendarDrafts.single().attendeeEmails)
        h.close()
    }

    private companion object {
        const val GET = "get_contact"
        const val COMPOSE = "open_compose"
        const val CALENDAR = "create_calendar_event"
        const val UPDATE = "update_business_card"
    }
}
