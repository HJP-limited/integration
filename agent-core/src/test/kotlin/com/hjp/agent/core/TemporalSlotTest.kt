package com.hjp.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A date and a time are two slots, and the agent has to be able to tell them apart.
 *
 * The workflow decided "this request states a time" with a single pattern: 오전/오후, digits, 시. Every
 * other way Koreans say a time was invisible to it — `14시`, `2시`, `정오`, `자정`, `새벽 3시`, `저녁
 * 7시`, `14:30`. Combined with the cycle 9 rule that a calendar request without a time must ask for
 * one, that turned a stream of perfectly complete requests into questions.
 *
 * The mirror defect is treating a *date* as a time: `내일 점검 일정 만들어줘` names a day and nothing
 * else, and `create_calendar_event` needs a start time.
 *
 * So the two are separate typed facts, and this is the table that says which is which.
 */
class TemporalSlotTest {

    private fun assertTime(vararg utterances: String) {
        val missed = utterances.filterNot { TemporalSlotIntent.hasTime(it) }
        assertEquals(
            "these state a time of day and must be recognised as such",
            emptyList<String>(),
            missed,
        )
    }

    private fun assertNoTime(vararg utterances: String) {
        val wrong = utterances.filter { TemporalSlotIntent.hasTime(it) }
        assertEquals(
            "these state no time of day",
            emptyList<String>(),
            wrong,
        )
    }

    // ---- a time is stated ---------------------------------------------------------------------------

    @Test
    fun `am and pm forms`() = assertTime(
        "오후 2시에 점검 일정 만들어줘.",
        "오전 9시 30분에 회의 잡아줘.",
        "오후 2시 30분 미팅 등록해줘.",
        "오전10시 일정 추가해줘.",
    )

    @Test
    fun `twenty-four hour and bare hour forms`() = assertTime(
        "14시에 점검 일정 만들어줘.",
        "14시 30분에 회의 잡아줘.",
        "2시에 미팅 잡아줘.",
        "9시 반에 회의 잡아줘.",
    )

    @Test
    fun `colon forms`() = assertTime(
        "14:30에 회의 잡아줘.",
        "09:05에 점검 일정 만들어줘.",
    )

    @Test
    fun `named times of day`() = assertTime(
        "정오에 회의 잡아줘.",
        "자정에 점검 일정 만들어줘.",
        "새벽 3시에 회의 잡아줘.",
        "저녁 7시에 약속 등록해줘.",
        "아침 8시에 미팅 잡아줘.",
        "밤 11시에 일정 추가해줘.",
    )

    @Test
    fun `polite, contracted and reordered variants still state a time`() = assertTime(
        "점검 일정을 오후 2시로 만들어 주시겠어요?",
        "회의 2시에 잡아줘",
        "내일 오후 2시에 점검 일정 만들어줘.",
        "2027년 3월 4일 14시에 점검 일정 만들어줘.",
    )

    // ---- only a date is stated ----------------------------------------------------------------------

    @Test
    fun `a day with no time of day states no time`() = assertNoTime(
        "내일 점검 일정 만들어줘.",
        "다음 주에 회의 잡아줘.",
        "2026년 9월 1일 미팅 추가해줘.",
        "금요일 약속 등록해줘.",
        "모레 일정 만들어줘.",
    )

    @Test
    fun `a date is recognised as a date even when no time is present`() {
        listOf(
            "내일 점검 일정 만들어줘.",
            "다음 주에 회의 잡아줘.",
            "2026년 9월 1일 미팅 추가해줘.",
            "금요일 약속 등록해줘.",
        ).forEach { utterance ->
            assertTrue(
                "\"$utterance\" states a day: $utterance",
                TemporalSlotIntent.hasDate(utterance),
            )
            assertFalse(
                "and it states no time, which is the slot that is missing",
                TemporalSlotIntent.hasTime(utterance),
            )
        }
    }

    // ---- things that only look like times -----------------------------------------------------------

    @Test
    fun `numbers that are not times of day`() = assertNoTime(
        "명함 5개 찾아줘.",
        "2026년에 있었던 일이야.",
        "3번째 사람에게 보내줘.",
        "010-1234-5678로 문자 작성해줘.",
    )

    @Test
    fun `an out-of-range hour is not a usable time`() {
        // Recognising "25시" as a time and then failing to canonicalise it would report a slot the
        // tool cannot use. It is not a time.
        assertNoTime("25시에 회의 잡아줘.", "오후 13시에 회의 잡아줘.", "99시 일정 만들어줘.")
    }

    @Test
    fun `a person whose name contains a time word is not a time`() = assertNoTime(
        "정오영 명함 찾아줘.",
        "김새벽 연락처 알려줘.",
    )

    // ---- the two facts are independent ---------------------------------------------------------------

    @Test
    fun `date and time are separate facts`() {
        val both = "내일 오후 3시에 회의 잡아줘."
        assertTrue(TemporalSlotIntent.hasDate(both))
        assertTrue(TemporalSlotIntent.hasTime(both))

        val dateOnly = "내일 회의 잡아줘."
        assertTrue(TemporalSlotIntent.hasDate(dateOnly))
        assertFalse(TemporalSlotIntent.hasTime(dateOnly))

        val timeOnly = "오후 3시에 회의 잡아줘."
        assertFalse(
            "no day is named here; the agent may still need to ask which day",
            TemporalSlotIntent.hasDate(timeOnly),
        )
        assertTrue(TemporalSlotIntent.hasTime(timeOnly))
    }
}
