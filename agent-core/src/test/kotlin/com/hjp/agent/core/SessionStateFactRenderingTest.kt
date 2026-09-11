package com.hjp.agent.core

import com.hjp.agent.contract.ConversationMemory
import com.hjp.agent.contract.ConversationMemoryItem
import com.hjp.agent.contract.TrackedAction
import com.hjp.agent.contract.TrackedActionStatus
import com.hjp.agent.contract.TurnOutcomeType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session-state block, rendered directly.
 *
 * [com.example.hjp.integration.ModelContextMemoryProjectionCharacterizationTest] pins the same
 * contract through the whole kernel, which is what makes it believable. This pins it one layer down,
 * where the memory that goes in can be stated exactly rather than produced by a conversation — so a
 * later change to the renderer fails here with the rendered text in the message, instead of failing
 * six turns into a scenario.
 *
 * It also writes what it rendered to the module's build directory, so the green evidence can quote
 * the actual block rather than describe it.
 */
class SessionStateFactRenderingTest {

    private val selector = ModelContextSelector()

    private fun item(content: String, key: String?, updatedAt: Long) = ConversationMemoryItem(
        content = content,
        sourceTurnId = "t-source",
        updatedAtEpochMillis = updatedAt,
        key = key,
    )

    /**
     * A memory that has already been through the store: the superseded company is gone, because
     * `upsert` removed it when the second statement arrived. The renderer is not the place that
     * decides which value is current.
     */
    private fun memory() = ConversationMemory(
        confirmedFacts = listOf(
            item("내 직책은 라온책임이야.", "user.title", 1_000),
            item("내 회사는 델타보름스야.", "user.company", 2_000),
            item("여름에 이사할 예정이라고 기억해줘.", null, 3_000),
        ),
        preferences = listOf(item("앞으로는 누리말투 스타일로 답해줘.", null, 1_500)),
        constraints = listOf(item("하마루확인 없이는 반드시 먼저 물어봐줘.", null, 1_600)),
        actions = listOf(
            TrackedAction(
                turnId = "turn-internal-77",
                request = "표하윤 명함 찾아줘.",
                status = TrackedActionStatus.FAILED,
                updatedAtEpochMillis = 2_500,
                detailKo = "검색 백엔드가 응답하지 않아 실패했습니다.",
                executedTools = listOf("search_contacts", "get_contact"),
                outcomeType = TurnOutcomeType.FAILED,
                requestsAction = true,
            ),
        ),
    )

    private fun render(memory: ConversationMemory): String =
        selector.select(
            ContextRequest(
                transcript = emptyList(),
                memory = memory,
                currentInput = "미르바람 이라고 메모해두면 좋겠네요.",
                currentTurnId = "turn-now",
            ),
        ).sections.single { it.name == "session_state" }.body

    @Test
    fun `a remembered fact is stated with its key and its current value`() {
        val state = render(memory())

        assertTrue("the facts block must exist: $state", state.contains("facts:"))
        assertTrue("the key names what the statement is about: $state", state.contains("user.company"))
        assertTrue("and the current value is stated: $state", state.contains("델타보름스"))
        assertEquals(
            "one key, stated once: $state",
            1, Regex(Regex.escape("user.company")).findAll(state).count(),
        )
    }

    @Test
    fun `a second key survives beside the first`() {
        val state = render(memory())

        assertTrue("a different key is a different fact: $state", state.contains("user.title"))
        assertTrue(state.contains("라온책임"))
    }

    @Test
    fun `a statement the key derivation did not recognise is still stated`() {
        // Not every remembered sentence yields a key. Dropping those would quietly lose whatever the
        // user asked to be remembered in a phrasing the derivation does not cover.
        val state = render(memory())

        assertTrue("a keyless fact is rendered as plain text: $state", state.contains("여름에 이사할"))
    }

    @Test
    fun `a fact alone is enough for the state block to exist`() {
        val onlyFacts = ConversationMemory(
            confirmedFacts = listOf(item("내 회사는 델타보름스야.", "user.company", 2_000)),
        )
        val sections = selector.select(
            ContextRequest(
                transcript = emptyList(),
                memory = onlyFacts,
                currentInput = "고맙습니다.",
                currentTurnId = "turn-now",
            ),
        ).sections

        assertTrue(
            "a session whose only memory is a fact still has state worth stating: $sections",
            sections.any { it.name == "session_state" },
        )
        assertTrue(sections.single { it.name == "session_state" }.body.contains("델타보름스"))
    }

    @Test
    fun `an empty memory still produces no state block`() {
        // The first-turn contract: with nothing remembered the prompt must stay byte-identical to a
        // single-turn prompt, so an empty state block must not appear just because facts are now
        // rendered.
        val sections = selector.select(
            ContextRequest(
                transcript = emptyList(),
                memory = ConversationMemory(),
                currentInput = "안녕하세요.",
                currentTurnId = "turn-now",
            ),
        ).sections

        assertEquals(
            "nothing remembered, nothing stated",
            emptyList<com.hjp.agent.contract.ModelPromptSection>(), sections,
        )
    }

    @Test
    fun `the action ledger is still not rendered`() {
        val state = render(memory())

        val leaked = listOf(
            "turn-internal-77", "FAILED", "TOOL_FAILURE",
            "search_contacts", "get_contact", "검색 백엔드가 응답하지 않아",
            "open_actions", "closed_actions",
        ).filter(state::contains)
        assertEquals(
            "adding facts must not have opened a door for the agent's own bookkeeping: $state",
            emptyList<String>(), leaked,
        )
    }

    @Test
    fun `preferences and constraints are unchanged`() {
        val state = render(memory())

        assertTrue(state.contains("preferences:"))
        assertTrue(state.contains("누리말투"))
        assertTrue(state.contains("constraints:"))
        assertTrue(state.contains("하마루확인"))
    }

    @Test
    fun `the same memory renders the same block every time`() {
        val first = render(memory())
        repeat(3) { assertEquals("order must not move between requests", first, render(memory())) }
    }

    @Test
    fun `the rendered block carries no timestamp, source turn or confidence`() {
        val state = render(memory())

        val leaked = listOf("t-source", "1000", "2000", "3000", "EXPLICIT", "TOOL_VERIFIED")
            .filter(state::contains)
        assertEquals(
            "only the key and the statement belong in the prompt: $state",
            emptyList<String>(), leaked,
        )
    }

    @Test
    fun `what was rendered is written down for the record`() {
        val state = render(memory())
        val directory = File("build/model-context-observations")
        if (directory.isDirectory || directory.mkdirs()) {
            File(directory, "session_state_with_facts.txt").writeText(state)
        }
        assertFalse("something must have been rendered", state.isBlank())
    }
}
