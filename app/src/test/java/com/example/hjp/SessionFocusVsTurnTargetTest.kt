package com.example.hjp

import com.hjp.agent.contract.DialogueAct
import com.hjp.agent.contract.TurnOutcomeType
import com.hjp.tool.contact.BusinessCardRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session focus versus this turn's contact target, end to end through the real kernel.
 *
 * The two used to be one value, so once any contact had been looked up every later request was
 * validated as belonging to that person. The observable symptom was a schedule that named nobody
 * dying with "주소나 번호를 확인하려면 연락처 상세 조회가 필요합니다" — a lookup the user had never
 * asked for, for an attendee the request did not have.
 *
 * The fix had to keep the focus, because anaphora depends on it. So each test below is a pair: the
 * turn that must *not* inherit the remembered person, and the turn that must still reach them.
 */
class SessionFocusVsTurnTargetTest {

    private val jiwon = BusinessCardRecord(
        id = "C001", name = "김지원", company = "비전글로벌", title = "대표이사",
        industry = "IT", email = "jiwon@example.com", mobile = "010-1111-0001",
    )
    private val minsu = BusinessCardRecord(
        id = "C002", name = "박민수", company = "한빛물산", title = "영업팀장",
        industry = "유통", email = "minsu@example.com", mobile = "010-1111-0002",
    )

    private fun harness() = MultiturnScenarioHarness(cards = listOf(jiwon, minsu))

    // 1 ------------------------------------------------------------------------------------------

    @Test
    fun `an empty session schedules an absolute-date event with no attendee`() = runBlocking {
        val h = harness()

        val turn = h.turn("2027년 3월 4일 오전 9시 분기 점검 일정 만들어줘.")

        assertEquals(listOf(CALENDAR), turn.executedTools)
        assertEquals(1, turn.newCalendarDrafts.size)
        assertEquals(emptyList<String>(), turn.newCalendarDrafts.single().attendeeEmails)
        assertEquals(TurnOutcomeType.CALENDAR_OPENED, turn.outcomeType)
        h.close()
    }

    // 2 ------------------------------------------------------------------------------------------

    @Test
    fun `a search does not make the next attendee-less schedule contact-bound`() = runBlocking {
        val h = harness()
        h.turn("김지원 명함 찾아줘.")

        val turn = h.turn("2027년 3월 4일 오전 9시 분기 점검 일정 만들어줘.")

        assertEquals(listOf(CALENDAR), turn.executedTools)
        assertEquals(emptyList<String>(), turn.newCalendarDrafts.single().attendeeEmails)
        // The focus is a memory, not a target: it survives the turn untouched.
        assertEquals("C001", turn.memory.selectedContact?.cardId)
        h.close()
    }

    // 3 ------------------------------------------------------------------------------------------

    @Test
    fun `search then compose then an attendee-less schedule`() = runBlocking {
        val h = harness()
        h.turn("김지원 명함 찾아줘.")
        val compose = h.turn("그 사람에게 제목은 견적 문의, 내용은 단가표 부탁드립니다 라고 메일 작성해줘.")
        assertEquals(listOf(GET, COMPOSE), compose.executedTools)
        assertEquals(jiwon.email, compose.newComposeDrafts.single().to)

        val calendar = h.turn("2027년 2월 18일 오후 3시 교육 협의 일정 만들어줘.")

        assertEquals(listOf(CALENDAR), calendar.executedTools)
        assertEquals(1, calendar.newCalendarDrafts.size)
        assertEquals(emptyList<String>(), calendar.newCalendarDrafts.single().attendeeEmails)
        assertEquals(TurnOutcomeType.CALENDAR_OPENED, calendar.outcomeType)
        h.close()
    }

    // 4 ------------------------------------------------------------------------------------------

    @Test
    fun `search then update then small talk then an attendee-less schedule`() = runBlocking {
        val h = harness()
        h.turn("김지원 명함 찾아줘.")
        val update = h.turn("그 사람 메모를 재계약검토로 수정해줘.")
        assertEquals(listOf(GET, UPDATE), update.executedTools)
        h.turn("네 알겠습니다.")

        val calendar = h.turn("2027년 6월 1일 오전 11시 사내 점검 일정 만들어줘.")

        assertEquals(listOf(CALENDAR), calendar.executedTools)
        assertEquals(emptyList<String>(), calendar.newCalendarDrafts.single().attendeeEmails)
        h.close()
    }

    // 5 ------------------------------------------------------------------------------------------

    @Test
    fun `an explicit reference still schedules with the verified attendee`() = runBlocking {
        val h = harness()
        h.turn("김지원 명함 찾아줘.")

        val turn = h.turn("그 사람과 2027년 5월 20일 오후 2시에 일정 만들어줘.")

        assertEquals(listOf(GET, CALENDAR), turn.executedTools)
        // The address is re-read from the card in this turn; memory never carried it.
        assertEquals(listOf(jiwon.email), turn.newCalendarDrafts.single().attendeeEmails)
        assertEquals(1, turn.newCalendarDrafts.size)
        h.close()
    }

    // 6 ------------------------------------------------------------------------------------------

    @Test
    fun `an anaphor after a second search resolves to the newer person`() = runBlocking {
        val h = harness()
        h.turn("김지원 명함 찾아줘.")
        h.turn("박민수 명함 찾아줘.")

        val turn = h.turn("그 사람에게 제목은 견적 문의, 내용은 단가표 부탁드립니다 라고 메일 작성해줘.")

        assertEquals(listOf(GET, COMPOSE), turn.executedTools)
        assertEquals(minsu.email, turn.newComposeDrafts.single().to)
        assertTrue(h.messages.drafts.none { it.to == jiwon.email })
        h.close()
    }

    // 7 ------------------------------------------------------------------------------------------

    @Test
    fun `naming a different person overrides the remembered focus`() = runBlocking {
        val h = harness()
        h.turn("김지원 명함 찾아줘.")

        val turn = h.turn("박민수에게 제목은 견적 문의, 내용은 단가표 부탁드립니다 라고 메일 작성해줘.")

        assertEquals(minsu.email, turn.newComposeDrafts.single().to)
        assertTrue(h.messages.drafts.none { it.to == jiwon.email })
        h.close()
    }

    // 8 ------------------------------------------------------------------------------------------

    @Test
    fun `an anaphor with nobody in focus asks instead of guessing`() = runBlocking {
        val h = harness()

        val turn = h.turn("그 사람에게 제목은 견적 문의, 내용은 단가표 부탁드립니다 라고 메일 작성해줘.")

        assertEquals(emptyList<String>(), turn.executedTools)
        assertEquals(0, turn.newComposeDrafts.size)
        assertEquals(TurnOutcomeType.CLARIFICATION_REQUIRED, turn.outcomeType)
        assertNull(turn.memory.selectedContact)
        h.close()
    }

    // 9 ------------------------------------------------------------------------------------------

    @Test
    fun `an attribute question about the person in focus is read from the card`() = runBlocking {
        val h = harness()
        h.turn("김지원 명함 찾아줘.")

        val turn = h.turn("그 사람 회사가 어디야?")

        assertEquals(listOf(GET), turn.executedTools)
        assertEquals(DialogueAct.CONTACT_DETAIL, turn.act)
        assertEquals(TurnOutcomeType.CONTACT_DETAIL_SHOWN, turn.outcomeType)
        assertTrue(turn.answer, turn.answer.contains(jiwon.company))
        assertEquals(0, turn.newComposeDrafts.size + turn.newCalendarDrafts.size)
        h.close()
    }

    // 10 -----------------------------------------------------------------------------------------

    @Test
    fun `an unrelated schedule after a search runs exactly one calendar call`() = runBlocking {
        val h = harness()
        h.turn("김지원 명함 찾아줘.")

        val turn = h.turn("2028년 1월 9일 오후 5시 워크숍 일정 만들어줘.")

        assertEquals(listOf(CALENDAR), turn.executedTools)
        assertEquals(1, turn.newCalendarDrafts.size)
        assertTrue(turn.executedTools.none { it == GET || it == SEARCH })
        assertNotNull(turn.memory.selectedContact)
        h.close()
    }

    private companion object {
        const val SEARCH = "search_contacts"
        const val GET = "get_contact"
        const val COMPOSE = "open_compose"
        const val CALENDAR = "create_calendar_event"
        const val UPDATE = "update_business_card"
    }
}
