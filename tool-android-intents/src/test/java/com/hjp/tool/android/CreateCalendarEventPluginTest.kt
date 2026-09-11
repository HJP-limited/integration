package com.hjp.tool.android

import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolExecutionResult
import com.hjp.tool.contract.ToolRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calendar plugin's own contract, tested without the kernel in front of it.
 *
 * Until now there was no test here at all: `open_compose` had one and `create_calendar_event` did
 * not, so `end_time` defaulting, the end-after-start rule and the availability branch were exercised
 * by nothing. The kernel canonicalises `start_time` before dispatch, which is exactly why the plugin
 * needs its own defence — a caller that reaches the plugin directly gets no such help.
 */
class CreateCalendarEventPluginTest {

    private class RecordingBackend(
        private val available: Boolean = true,
        private val opens: Boolean = true,
    ) : CalendarComposerBackend {
        val drafts = mutableListOf<CalendarDraft>()
        override fun isAvailable(): Boolean = available
        override suspend fun open(draft: CalendarDraft): Boolean {
            drafts += draft
            return opens
        }
    }

    private val context = ToolExecutionContext("session", "turn", "ko-KR", "Asia/Seoul")

    private fun args(
        title: String? = "분기 점검",
        start: String? = "2027-05-06T16:00",
        end: String? = null,
        location: String? = null,
        description: String? = null,
        attendees: List<String>? = null,
    ): JsonObject = buildJsonObject {
        title?.let { put("title", it) }
        start?.let { put("start_time", it) }
        end?.let { put("end_time", it) }
        location?.let { put("location", it) }
        description?.let { put("description", it) }
        attendees?.let { list -> put("attendee_emails", buildJsonArray { list.forEach { add(JsonPrimitive(it)) } }) }
    }

    private fun run(
        arguments: JsonObject,
        backend: RecordingBackend = RecordingBackend(),
    ): Pair<ToolExecutionResult, RecordingBackend> = runBlocking {
        val plugin = CreateCalendarEventPlugin(backend)
        val result = plugin.execute(
            ToolRequest(
                "call-1",
                AndroidIntentToolContracts.Calendar.capabilityId,
                AndroidIntentToolContracts.Calendar.version,
                arguments,
            ),
            context,
        )
        result to backend
    }

    private fun assertRejected(arguments: JsonObject, why: String) {
        val (result, backend) = run(arguments)
        assertTrue("$why: expected a failure, got $result", result is ToolExecutionResult.Failure)
        assertTrue("$why: the backend was still opened", backend.drafts.isEmpty())
    }

    // ---- the happy path, asserted in detail --------------------------------------------------

    @Test
    fun `a well formed request reaches the backend with every field intact`() {
        val (result, backend) = run(
            args(
                title = "분기 점검", start = "2027-05-06T16:00", end = "2027-05-06T17:30",
                location = "본사 3층", description = "설비 점검", attendees = listOf("a@example.com"),
            ),
        )

        assertTrue(result.toString(), result is ToolExecutionResult.Success)
        val draft = backend.drafts.single()
        assertEquals("분기 점검", draft.title)
        assertEquals("본사 3층", draft.location)
        assertEquals("설비 점검", draft.description)
        assertEquals(listOf("a@example.com"), draft.attendeeEmails)
        assertEquals(90 * 60 * 1000L, draft.endMillis - draft.startMillis)
    }

    /** The defaulting rule, stated as an exact duration rather than "about an hour". */
    @Test
    fun `an omitted end_time becomes exactly one hour after the start`() {
        val (result, backend) = run(args(end = null))

        assertTrue(result is ToolExecutionResult.Success)
        val draft = backend.drafts.single()
        assertEquals(3_600_000L, draft.endMillis - draft.startMillis)
    }

    // ---- end vs start ---------------------------------------------------------------------------

    @Test
    fun `an end equal to the start is refused`() {
        assertRejected(args(start = "2027-05-06T16:00", end = "2027-05-06T16:00"), "end == start")
    }

    @Test
    fun `an end before the start is refused`() {
        assertRejected(args(start = "2027-05-06T16:00", end = "2027-05-06T15:00"), "end < start")
        assertRejected(args(start = "2027-05-06T16:00", end = "2027-05-05T16:00"), "end on an earlier day")
    }

    // ---- the datetime contract, on both fields ---------------------------------------------------

    private val malformed = listOf(
        "2027-05-06T16:00:30" to "non-zero seconds",
        "2027-05-06T16:00:00" to "seconds at all",
        "2027-05-06T16:00Z" to "a zone suffix",
        "2027-05-06T16:00+09:00" to "an offset",
        "2027-05-06T16:00 오후" to "trailing text",
        "2027-05-06T16:00xyz" to "trailing junk",
        "2027-02-30T16:00" to "a date that does not exist",
        "2027-05-06T25:00" to "an hour that does not exist",
        "2027-05-06 16:00" to "a space instead of T",
        "2027/05/06T16:00" to "the wrong separators",
        "" to "an empty value",
        "내일 오후 4시" to "prose",
    )

    @Test
    fun `start_time accepts only a complete canonical local datetime`() {
        malformed.forEach { (value, why) -> assertRejected(args(start = value), "start_time with $why") }
    }

    @Test
    fun `end_time accepts only a complete canonical local datetime`() {
        malformed.filter { it.first.isNotEmpty() }.forEach { (value, why) ->
            assertRejected(args(end = value), "end_time with $why")
        }
    }

    /**
     * The parser must consume the whole string, which a lenient `parse(String)` does not.
     *
     * Surrounding whitespace is a separate question and is deliberately *not* junk: the codec trims
     * it, which cannot move the instant. Trailing content that is not whitespace can, so it is
     * refused. Both halves are pinned here so neither is accidental.
     */
    @Test
    fun `a valid prefix followed by content is refused, but surrounding whitespace is trimmed`() {
        listOf("2027-05-06T16:00 그리고 회의", "2027-05-06T16:001", "2027-05-06T16:00.", "x2027-05-06T16:00")
            .forEach { assertRejected(args(start = it), "prefix-only match: $it") }

        listOf("  2027-05-06T16:00", "2027-05-06T16:00\n", "\t2027-05-06T16:00 ").forEach { padded ->
            val (result, backend) = run(args(start = padded))
            assertTrue("whitespace-padded value was refused: ${padded.trim()}", result is ToolExecutionResult.Success)
            assertEquals(3_600_000L, backend.drafts.single().let { it.endMillis - it.startMillis })
        }
    }

    // ---- required arguments and attendees ----------------------------------------------------------

    @Test
    fun `a missing required argument is refused`() {
        assertRejected(args(title = null), "no title")
        assertRejected(args(start = null), "no start_time")
    }

    @Test
    fun `an invalid attendee address is refused and a valid one is passed through`() {
        assertRejected(args(attendees = listOf("not-an-address")), "malformed attendee")
        assertRejected(args(attendees = listOf("ok@example.com", "bad@@example")), "one bad attendee")

        val (result, backend) = run(args(attendees = listOf("a@example.com", "b@example.net")))
        assertTrue(result is ToolExecutionResult.Success)
        assertEquals(listOf("a@example.com", "b@example.net"), backend.drafts.single().attendeeEmails)
    }

    // ---- backend states ------------------------------------------------------------------------------

    @Test
    fun `an unavailable calendar app is reported, not silently succeeded`() = runBlocking {
        val backend = RecordingBackend(available = false)
        val plugin = CreateCalendarEventPlugin(backend)

        val availability = plugin.availability()

        assertTrue(availability.toString(), availability !is com.hjp.tool.contract.ToolAvailability.Ready)
        assertTrue(backend.drafts.isEmpty())
    }

    @Test
    fun `a backend that declines to open is a failure`() {
        val (result, backend) = run(args(), RecordingBackend(opens = false))

        assertTrue(result.toString(), result is ToolExecutionResult.Failure)
        // It was asked — the failure is the app declining, not the plugin refusing to try.
        assertEquals(1, backend.drafts.size)
    }

    @Test
    fun `a refusal never claims the screen was opened`() {
        val (result, _) = run(args(start = "2027-05-06T16:00:30"))
        val message = (result as ToolExecutionResult.Failure).error.safeMessageKo

        assertFalse(message, message.contains("열었습니다"))
        assertFalse(message, message.contains("생성"))
    }
}
