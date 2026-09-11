package com.example.hjp.characterization

import com.example.hjp.MultiturnScenarioHarness
import com.hjp.searchlookup.OnDeviceEmbeddingEngine
import com.hjp.tool.contact.RyeongContactSearchBackend
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a schedule request does, slot by slot, and what it must never carry with it.
 *
 * Two separate questions live here and they have different answers, both of which are already
 * decided by production and are characterised rather than changed:
 *
 *  - **`start_time` is genuinely required and genuinely absent** in a request that names no day or
 *    no hour. `PendingAction.Calendar.missingMessage()` turns that into a question, typed
 *    `MISSING_REQUIRED_SLOT`, and no calendar tool runs.
 *  - **`title` is required by the tool schema but never absent**, because `CalendarPromptParser`
 *    supplies "일정" / "회의" / "약속" from the request's own wording when the user states no title,
 *    and marks it `titleExplicit = false`. That default is an explicit, long-standing production
 *    contract — `CalendarTitlePolicy` exists to strip it back off again, and the frozen held-out v3
 *    cases assert it on more than twenty schedule requests. So a missing title is not a missing slot
 *    and nothing here asks production to change.
 *
 * The third question is the one the Ryeong contract cares about: a schedule request that follows a
 * contact lookup must not smuggle that contact into the event. The event may be created — the agent
 * really does support calendars — but it may not carry the previous person's card id, name, email,
 * phone, company, department or address.
 */
class CalendarRequiredSlotCharacterizationTest {

    // ---- the slot that really is required --------------------------------------------------------

    @Test
    fun `a schedule request missing the time asks instead of scheduling`() = runBlocking {
        val incomplete = listOf(
            "일정 잡아줘",              // neither day nor hour
            "일정 만들어줘",
            "내일 일정 잡아줘",          // day only
            "모레 회의 잡아줘",
            "다음 주에 미팅 잡아줘",
            "3시에 일정 잡아줘",         // hour only, no day
            "오후 2시에 회의 잡아줘",
        )
        val failures = mutableListOf<String>()
        incomplete.forEach { question ->
            val outcome = runFreshTurn(question)
            if (outcome.calendarCalls != 0) {
                failures += "$question -> scheduled anyway with ${outcome.calendarArguments}"
            }
            if (outcome.outcomeType != "CLARIFICATION_REQUIRED") {
                failures += "$question -> outcome ${outcome.outcomeType}, expected CLARIFICATION_REQUIRED"
            }
            if (!outcome.answer.contains("날짜") && !outcome.answer.contains("시각") &&
                !outcome.answer.contains("시간")
            ) {
                failures += "$question -> answer does not ask for the missing time: ${outcome.answer}"
            }
        }
        assertEquals(
            "incomplete schedule requests handled wrongly:\n" + failures.joinToString("\n"),
            emptyList<String>(),
            failures,
        )
    }

    // ---- the slot that is defaulted, on purpose ---------------------------------------------------

    @Test
    fun `a complete schedule request with no stated title uses the documented default`() = runBlocking {
        val cases = mapOf(
            "내일 오후 3시에 일정 잡아줘" to "일정",
            "내일 오후 3시에 회의 잡아줘" to "회의",
            "내일 오후 3시에 미팅 잡아줘" to "회의",
            "내일 오후 3시에 약속 잡아줘" to "약속",
            "2027년 3월 4일 오후 3시 일정 만들어줘" to "일정",
        )
        val failures = mutableListOf<String>()
        cases.forEach { (question, expectedTitle) ->
            val outcome = runFreshTurn(question)
            if (outcome.calendarCalls != 1) {
                failures += "$question -> ${outcome.calendarCalls} calendar call(s), expected 1"
                return@forEach
            }
            val title = outcome.calendarArguments.single().stringOrNull("title")
            if (title != expectedTitle) failures += "$question -> title=$title, expected $expectedTitle"
            if (outcome.calendarArguments.single().stringOrNull("start_time").isNullOrBlank()) {
                failures += "$question -> start_time missing"
            }
        }
        assertEquals(
            "default-title contract broken:\n" + failures.joinToString("\n"), emptyList<String>(), failures,
        )
    }

    @Test
    fun `an explicitly stated title is used verbatim`() = runBlocking {
        val outcome = runFreshTurn("내일 오후 3시에 제목은 분기 리뷰 일정 잡아줘")
        assertEquals("one calendar call expected", 1, outcome.calendarCalls)
        assertEquals("분기 리뷰", outcome.calendarArguments.single().stringOrNull("title"))
    }

    // ---- speech that only talks about scheduling --------------------------------------------------

    @Test
    fun `reported negated hypothetical and quoted schedule talk creates nothing`() = runBlocking {
        val nonRequests = listOf(
            "내일 3시에 일정 잡아달라고 했나?",
            "내일 3시에 일정 잡아달라고 한 적 없어",
            "만약 내일 3시에 일정을 잡으면 어떻게 돼?",
            "‘내일 3시에 일정 잡아줘’라고 말하면 되나요?",
            "내일 3시에 일정 잡는 거 가능해?",
            "내일 3시에 일정 잡지 마",
        )
        val failures = mutableListOf<String>()
        nonRequests.forEach { question ->
            val outcome = runFreshTurn(question)
            if (outcome.calendarCalls != 0) failures += "$question -> created ${outcome.calendarArguments}"
        }
        assertEquals(
            "talking about a schedule created one:\n" + failures.joinToString("\n"),
            emptyList<String>(),
            failures,
        )
    }

    // ---- the Ryeong contract: no contact may leak into an unrelated action ------------------------

    /**
     * Exactly the shape of upstream's "도구 범위 밖" scenario: look somebody up, then ask for
     * something that has nothing to do with them.
     *
     * The calendar action itself is allowed — this agent supports calendars, and refusing to
     * schedule would be a worse answer than scheduling. What is not allowed is for 남궁여진 to end up
     * inside the event, or for a card to come back on that turn.
     */
    @Test
    fun `a schedule request after a contact lookup carries none of that contact`() = runBlocking {
        val harness = harness()
        try {
            harness.turn("남궁여진씨 회사가 어디야?")
            val second = harness.turn("내일 3시에 일정 잡아줘")

            val contact = ContactDirectoryFixture.NAMGUNG
            val forbidden = listOf(
                contact.id, contact.name, contact.email, contact.mobile, contact.phone,
                contact.company, contact.department, contact.address,
            ).filter { it.isNotBlank() }

            val arguments = second.toolArguments
                .filter { it.first == "create_calendar_event" }
                .map { it.second.toString() }
            arguments.forEach { rendered ->
                forbidden.forEach { secret ->
                    assertTrue(
                        "calendar arguments leaked '$secret': $rendered",
                        !rendered.contains(secret),
                    )
                }
            }
            forbidden.forEach { secret ->
                assertTrue(
                    "the final response leaked '$secret': ${second.answer}",
                    !second.answer.contains(secret),
                )
            }
            assertEquals(
                "an unrelated request must return no cards",
                emptyList<List<String>>(),
                second.searchRankings,
            )
            assertTrue(
                "an unrelated request must not re-read the contact's card",
                second.toolCalls.none { it.startsWith("get:") },
            )
        } finally {
            harness.close()
        }
    }

    /** The same guarantee for the SMS half of upstream's out-of-scope set. */
    @Test
    fun `an anaphoric message request after a lookup resolves to that contact or asks`() = runBlocking {
        val harness = harness()
        try {
            harness.turn("남궁여진씨 회사가 어디야?")
            val second = harness.turn("이 사람한테 문자 보내줘")
            // Either outcome is defensible — production may ask for the body, or open the composer
            // for the person the pronoun points at. What it may not do is address somebody else.
            val others = ContactDirectoryFixture.ALL.filterNot { it.id == ContactDirectoryFixture.NAMGUNG.id }
            second.newComposeDrafts.forEach { draft ->
                others.forEach { other ->
                    assertTrue(
                        "the composer was addressed to ${other.name}: $draft",
                        !draft.to.contains(other.mobile) && !draft.to.contains(other.email),
                    )
                }
            }
        } finally {
            harness.close()
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun harness() = MultiturnScenarioHarness(
        cards = ContactDirectoryFixture.ALL,
        searchBackendFactory = { repository ->
            RyeongContactSearchBackend(
                repository = repository,
                embeddingEngineFactory = { OnDeviceEmbeddingEngine.production() },
            )
        },
    )

    private data class TurnOutcome(
        val answer: String,
        val outcomeType: String?,
        val calendarCalls: Int,
        val calendarArguments: List<JsonObject>,
    )

    private suspend fun runFreshTurn(question: String): TurnOutcome {
        val harness = harness()
        return try {
            val record = harness.turn(question)
            val calendar = record.toolArguments.filter { it.first == "create_calendar_event" }.map { it.second }
            TurnOutcome(
                answer = record.answer,
                outcomeType = record.outcomeType?.name,
                calendarCalls = calendar.size,
                calendarArguments = calendar,
            )
        } finally {
            harness.close()
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
}
