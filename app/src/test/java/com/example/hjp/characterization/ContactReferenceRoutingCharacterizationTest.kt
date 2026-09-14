package com.example.hjp.characterization

import com.example.hjp.MultiturnScenarioHarness
import com.hjp.agent.contract.DialogueAct
import com.hjp.searchlookup.OnDeviceEmbeddingEngine
import com.hjp.tool.contact.RyeongContactSearchBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the agent does when a sentence names a real contact and asks something about them.
 *
 * Everything here runs through the production wiring: the real [com.hjp.agent.core.AgentKernel], the
 * real [com.hjp.agent.core.DeterministicTurnRouter], the real session and memory, the real
 * [RyeongContactSearchBackend] over the real `SearchLookupService`, the production local gateway and
 * recording side-effect backends. Nothing decides for the agent whether a tool runs.
 *
 * The sentences are generated from [ContactDirectoryFixture] by *form* — an attribute question, a
 * lookup request, a polite variant, a spacing variant — so a phrasing that nobody wrote down still
 * has to classify the same way. None of them is copied from the frozen upstream scenarios, and no
 * production branch may mention any name used here.
 */
class ContactReferenceRoutingCharacterizationTest {

    // ---- 8.1 positive: naming a real contact and asking about them --------------------------------

    /**
     * The attribute questions, by the card field they ask for.
     *
     * `{n}` is the contact's name. The word 명함 appears in none of them: that is the whole point —
     * a question about somebody's company is a contact lookup whether or not the user says "명함".
     */
    private val attributeForms = listOf(
        "{n}씨 회사가 어디야?",
        "{n}씨 회사 알려줘",
        "{n}씨 소속이 어디야?",
        "{n}씨 부서 알려줘",
        "{n}씨 직책이 뭐야?",
        "{n}씨 이메일 알려줘",
        "{n}씨 전화번호 알려줘",
        "{n}씨 주소가 어디야?",
        "{n}씨 찾아줘",
        // Politeness, contraction, word order and spacing variants of the same question.
        "{n}씨 회사가 어디인가요?",
        "{n}씨 회사 어디?",
        "어디 다녀요 {n}씨?",
        "{n} 씨 부서가 어떻게 되나요?",
        "{n}님 직급 알려주세요",
    )

    @Test
    fun `naming a real contact and asking about a card field is a contact lookup`() = runBlocking {
        val people = listOf(
            ContactDirectoryFixture.ORDINARY,
            ContactDirectoryFixture.SEOL,
            ContactDirectoryFixture.HA,
            ContactDirectoryFixture.URI,
            ContactDirectoryFixture.CHOI,
            ContactDirectoryFixture.NAMGUNG,
            ContactDirectoryFixture.SEONWOO,
            ContactDirectoryFixture.JEGAL,
            ContactDirectoryFixture.MICHAEL,
            ContactDirectoryFixture.ANNA,
            ContactDirectoryFixture.HWANGBO,
            ContactDirectoryFixture.MUNJA,
            ContactDirectoryFixture.SUJEONG,
            ContactDirectoryFixture.JOHOE,
            ContactDirectoryFixture.ILJEONG,
        )
        val failures = mutableListOf<String>()
        people.forEach { person ->
            attributeForms.forEach { form ->
                val question = form.replace("{n}", person.name)
                val outcome = runFreshTurn(question)
                if (!outcome.isContactLookup) {
                    failures += "$question -> act=${outcome.act} tools=${outcome.tools}"
                } else if (person.id !in outcome.reachedCardIds) {
                    failures += "$question -> looked up ${outcome.reachedCardIds}, expected ${person.id}"
                }
            }
        }
        assertEquals(
            "contact reference questions that did not reach the named contact:\n" +
                failures.joinToString("\n"),
            emptyList<String>(),
            failures,
        )
    }

    // ---- 8.2 negative: sentences that merely share vocabulary -------------------------------------

    @Test
    fun `sentences that name no contact are not contact lookups`() = runBlocking {
        val negatives = listOf(
            // A company, not a person at that company.
            "한빛테크 무슨 회사야?",
            "노바테크 어떤 업종이야?",
            // General information with no person in it.
            "반도체 시장 어때?",
            "오늘 날씨 어때?",
            "환율 얼마야?",
            "근처 맛집 찾아줘",
            "이 문서 요약해줘",
            "웹에서 검색해줘",
            // A job title or a bare common noun, with nobody named.
            "영업팀장 연봉이 얼마야?",
            "변호사 알려줘",
            "담당자 알려줘",
            // Present-tense action requests: these own their own route.
            "내일 오후 3시에 회의 일정 잡아줘",
            "문자 작성해줘",
            // Reported, recalled, negated, hypothetical and capability speech.
            "내가 아까 박서준씨 찾아달라고 했나?",
            "박서준씨 찾아달라고 한 적 없어",
            "만약 박서준씨를 찾으면 어떻게 돼?",
            "명함 검색 기능이 있나요?",
            "‘박서준씨 회사가 어디야?’라고 말하면 되는 거야?",
        )
        val failures = mutableListOf<String>()
        negatives.forEach { question ->
            val outcome = runFreshTurn(question)
            if (outcome.isContactLookup) {
                failures += "$question -> act=${outcome.act} tools=${outcome.tools} cards=${outcome.reachedCardIds}"
            }
        }
        assertEquals(
            "sentences routed as contact lookups that should not be:\n" + failures.joinToString("\n"),
            emptyList<String>(),
            failures,
        )
    }

    /**
     * A name that is also an ordinary noun is a person only when the sentence marks it as one.
     *
     * Both halves matter. Without the first, the noun alone turns into whoever happens to be called
     * that; without the second, a real person can never be reached by their own name.
     */
    @Test
    fun `a common-noun name needs a person marker to be a person`() = runBlocking {
        val marked = runFreshTurn("고운말씨 부서 알려줘")
        assertTrue(
            "a person-marked common-noun name should reach that person, got ${marked.act}/${marked.reachedCardIds}",
            marked.isContactLookup && ContactDirectoryFixture.NOUN_NAME.id in marked.reachedCardIds,
        )
    }

    // ---- 8.2 negative: an ambiguous namesake must stay an open question ---------------------------

    /**
     * Two people share this name, so the turn may search — but it must not end up acting on one of
     * them. Upstream expects a *search* here (both cards are gold), and this agent's own contract is
     * that a two-hit lookup leaves no selected contact behind.
     */
    @Test
    fun `a namesake question does not silently pick one of them`() = runBlocking {
        val outcome = runFreshTurn("김민준씨 회사가 어디야?")
        assertTrue("a namesake question should still be a contact lookup, got ${outcome.act}", outcome.isContactLookup)
        assertEquals(
            "a namesake question must not leave one of them selected",
            null,
            outcome.selectedCardId,
        )
        assertEquals("a namesake question must run no side-effect tool", emptyList<String>(), outcome.actionTools)
    }

    // ---- 8.1 positive: the follow-up shape, on a real second turn ---------------------------------

    @Test
    fun `an elliptic follow-up stays on the contact the previous turn established`() = runBlocking {
        val harness = harness()
        try {
            val first = harness.turn("남궁여진씨 회사가 어디야?")
            assertTrue(
                "the opening turn should be a contact lookup, got ${first.act}",
                first.act == DialogueAct.CONTACT_SEARCH || first.act == DialogueAct.CONTACT_DETAIL,
            )
            val second = harness.turn("부서는?")
            val reached = harness.backend.calls.filter { it.startsWith("get:") }.map { it.removePrefix("get:") }
            assertTrue(
                "the follow-up should stay on 남궁여진, got act=${second.act} reached=$reached",
                ContactDirectoryFixture.NAMGUNG.id in reached ||
                    second.memory.selectedContact?.cardId == ContactDirectoryFixture.NAMGUNG.id,
            )
        } finally {
            harness.close()
        }
    }

    /**
     * A new conversation forgets who was in focus.
     *
     * The elliptic question is identical to the one that worked a moment ago; what changed is that
     * the session was replaced, and an agent that still answers it is answering from a session the
     * user ended.
     */
    @Test
    fun `after a new conversation an elliptic follow-up has no target to inherit`() = runBlocking {
        val harness = harness()
        try {
            harness.turn("남궁여진씨 회사가 어디야?")
            harness.reset()
            val after = harness.turn("부서는?")
            assertEquals(
                "a reset session must not resolve an elliptic follow-up onto the old focus",
                null,
                after.memory.selectedContact?.cardId,
            )
            assertTrue(
                "a reset session must not re-read the old contact's card, got ${after.toolCalls}",
                after.toolCalls.none { it == "get:${ContactDirectoryFixture.NAMGUNG.id}" },
            )
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
        val act: DialogueAct,
        val tools: List<String>,
        val reachedCardIds: List<String>,
        val selectedCardId: String?,
        val actionTools: List<String>,
    ) {
        /** Reaching the store at all, by either of the two read tools the agent has. */
        val isContactLookup: Boolean
            get() = (act == DialogueAct.CONTACT_SEARCH || act == DialogueAct.CONTACT_DETAIL) &&
                tools.any { it == "search_contacts" || it == "get_contact" }
    }

    /** One turn in a session of its own, so nothing carries over between cases. */
    private suspend fun runFreshTurn(question: String): TurnOutcome {
        val harness = harness()
        return try {
            val record = harness.turn(question)
            TurnOutcome(
                act = record.act,
                tools = record.executedTools,
                reachedCardIds = (record.searchRankings.flatten() + record.fetched).distinct(),
                selectedCardId = record.memory.selectedContact?.cardId,
                actionTools = record.executedTools.filter { it in ACTION_TOOLS },
            )
        } finally {
            harness.close()
        }
    }

    private companion object {
        val ACTION_TOOLS = setOf("open_compose", "create_calendar_event", "update_business_card")
    }
}
