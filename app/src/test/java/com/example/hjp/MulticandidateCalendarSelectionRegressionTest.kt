package com.example.hjp

import com.example.hjp.eval.clock.EvaluationClock
import com.hjp.tool.contact.BusinessCardRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for resolving one persisted candidate before a contact-bound calendar turn. */
class MulticandidateCalendarSelectionRegressionTest {
    private val candidates = listOf(
        card("AI-1", "서수", "suseo@example.invalid"),
        card("AI-2", "오하늘", "haneul@example.invalid"),
        card("AI-3", "차누리", "nuri@example.invalid"),
        card("AI-4", "윤가람", "garam@example.invalid"),
    )
    private val newTarget = BusinessCardRecord(
        id = "NEW-1", name = "박새봄", company = "합성 디자인", title = "디자이너",
        email = "saebom@example.invalid",
    )

    @Test
    fun `generic multi-result then unique candidate name runs relative calendar prerequisites in order`() = runBlocking {
        val h = harness(candidates + newTarget)
        try {
            val search = h.turn("AI 개발자 찾아줘")
            assertEquals(candidates.map { it.id }, search.memory.candidateContacts.map { it.cardId })
            assertNull(search.memory.selectedContact)

            val calendar = h.turn("서수씨랑 내일 3시에 일정 잡아줘")

            assertFalse(calendar.answer, calendar.isError)
            assertEquals(listOf(DATETIME, GET, CALENDAR), calendar.executedTools)
            assertEquals(listOf("AI-1"), calendar.fetched)
            assertEquals("AI-1", calendar.memory.selectedContact?.cardId)
            assertEquals(listOf("suseo@example.invalid"), calendar.newCalendarDrafts.single().attendeeEmails)
            assertEquals(1, calendar.executedTools.count { it == DATETIME })
            assertEquals(1, calendar.executedTools.count { it == GET })
            assertEquals(1, calendar.executedTools.count { it == CALENDAR })
        } finally {
            h.close()
        }
    }

    @Test
    fun `first fourth and last ordinal select the exact persisted candidate id`() = runBlocking {
        listOf(
            "첫 번째 사람과 내일 3시에 일정 잡아줘" to "AI-1",
            "네 번째 사람과 내일 3시에 일정 잡아줘" to "AI-4",
            "마지막 사람과 내일 3시에 일정 잡아줘" to "AI-4",
        ).forEach { (request, expectedId) ->
            val h = harness(candidates)
            try {
                h.turn("AI 개발자 찾아줘")
                val calendar = h.turn(request)

                assertFalse("$request -> ${calendar.answer}", calendar.isError)
                assertEquals("$request calls=${calendar.toolCalls}", listOf(DATETIME, GET, CALENDAR), calendar.executedTools)
                assertEquals(request, listOf(expectedId), calendar.fetched)
                assertEquals(request, expectedId, calendar.memory.selectedContact?.cardId)
                val expectedEmail = candidates.single { it.id == expectedId }.email
                assertEquals(request, listOf(expectedEmail), calendar.newCalendarDrafts.single().attendeeEmails)
            } finally {
                h.close()
            }
        }
    }

    @Test
    fun `actual namesakes stay unresolved and calendar remains blocked`() = runBlocking {
        val twins = listOf(
            card("TWIN-A", "김지원", "a@example.invalid", company = "합성 A"),
            card("TWIN-B", "김지원", "b@example.invalid", company = "합성 B"),
        )
        val h = harness(twins)
        try {
            val search = h.turn("AI 개발자 찾아줘")
            assertEquals(2, search.memory.candidateContacts.size)
            assertNull(search.memory.selectedContact)

            val blocked = h.turn("김지원씨랑 내일 3시에 일정 잡아줘")

            assertTrue(blocked.isError || blocked.answer.contains("특정") || blocked.answer.contains("선택"))
            assertTrue(blocked.newCalendarDrafts.isEmpty())
            assertFalse(blocked.executedTools.contains(CALENDAR))
            assertNull(blocked.memory.selectedContact)
        } finally {
            h.close()
        }
    }

    @Test
    fun `selecting an exact candidate clears unresolved state for the action turn`() = runBlocking {
        val h = harness(candidates)
        try {
            h.turn("AI 개발자 찾아줘")
            val selected = h.turn("차누리씨랑 내일 3시에 일정 잡아줘")

            assertFalse(selected.answer, selected.isError)
            assertEquals("AI-3", selected.memory.selectedContact?.cardId)
            assertEquals(listOf(DATETIME, GET, CALENDAR), selected.executedTools)
            assertEquals(1, selected.newCalendarDrafts.size)
        } finally {
            h.close()
        }
    }

    @Test
    fun `a new explicit name retires the old selection and verifies the new card`() = runBlocking {
        val h = harness(candidates + newTarget)
        try {
            h.turn("AI 개발자 찾아줘")
            val first = h.turn("서수씨랑 내일 3시에 일정 잡아줘")
            assertEquals("AI-1", first.memory.selectedContact?.cardId)

            val replacement = h.turn("박새봄에게 내일 4시에 일정 잡아줘")

            assertFalse(replacement.answer, replacement.isError)
            assertEquals(listOf(DATETIME, SEARCH, GET, CALENDAR), replacement.executedTools)
            assertEquals(listOf("NEW-1"), replacement.fetched)
            assertEquals("NEW-1", replacement.memory.selectedContact?.cardId)
            assertTrue(replacement.toolArguments.none { (_, args) -> args.toString().contains("AI-1") })
            assertEquals(listOf(newTarget.email), replacement.newCalendarDrafts.single().attendeeEmails)
        } finally {
            h.close()
        }
    }

    private fun harness(cards: List<BusinessCardRecord>) = MultiturnScenarioHarness(
        cards = cards,
        clock = EvaluationClock.fixed(1_758_240_000_000L),
    )

    private fun card(
        id: String,
        name: String,
        email: String,
        company: String = "합성 AI 연구소",
    ) = BusinessCardRecord(
        id = id, name = name, company = company, title = "AI 개발자", industry = "IT", email = email,
    )

    private companion object {
        const val SEARCH = "search_contacts"
        const val DATETIME = "get_current_datetime"
        const val GET = "get_contact"
        const val CALENDAR = "create_calendar_event"
    }
}
