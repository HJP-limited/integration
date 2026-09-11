package com.hjp.agent.core

import com.hjp.agent.contract.ContactReference
import com.hjp.agent.contract.ContactSelectionBasis
import com.hjp.agent.contract.ConversationMemory
import com.hjp.agent.contract.DialogueAct
import com.hjp.agent.contract.MemoryProvenance
import com.hjp.agent.contract.TurnContext
import com.hjp.agent.contract.TurnRoutePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asking what was said, versus asking what is true now.
 *
 * The characterization test pins this through whole conversations. This pins the decision itself,
 * one sentence at a time, in wordings that file never uses — because a rule that only works on the
 * sentences it was written against is not a rule.
 *
 * Three things have to hold at once and they pull in opposite directions:
 *
 *  - a question about what was already said must not touch a card;
 *  - a request for current information must actually go and get it;
 *  - and a sentence that only *mentions* searching — quoted, hypothetical, negated, or asked about
 *    in the past tense — must do neither.
 */
class RecallFreshnessRoutingTest {

    private val focus = ContactReference(
        cardId = "RF001",
        name = "표하윤",
        company = "너울건설",
        title = "안전관리",
        selection = ContactSelectionBasis.SINGLE_RESULT,
        provenance = MemoryProvenance.TOOL_VERIFIED,
        confirmedAtEpochMillis = 1_000,
    )

    private fun withFocus(text: String) = TurnContext(
        userText = text,
        memory = ConversationMemory(selectedContact = focus),
        availableTools = setOf("search_contacts", "get_contact", "open_compose"),
    )

    private fun withoutFocus(text: String) = TurnContext(
        userText = text,
        memory = ConversationMemory(),
        availableTools = setOf("search_contacts", "get_contact", "open_compose"),
    )

    private fun route(context: TurnContext) = DeterministicTurnRouter.route(context)
    private fun act(context: TurnContext) = DeterministicTurnRouter.act(context)

    /**
     * A route that will read or search this turn.
     *
     * `Continue` only counts when the router *rewrote* the sentence into a lookup. Handing the
     * user's own words back to the model is a deferral, not a decision — counting it as a search
     * would flag every sentence that merely contains the word 조회.
     */
    private fun readsOrSearches(plan: TurnRoutePlan, original: String): Boolean = when (plan) {
        is TurnRoutePlan.ContactDetail -> true
        is TurnRoutePlan.GroundedContact -> true
        is TurnRoutePlan.Continue -> plan.text != original &&
            ContactReadIntent.SEARCH_VERBS.any { plan.text.contains(it) }
        else -> false
    }

    // ---- historical recall: the user's own statement ------------------------------------------

    @Test
    fun `a self stated fact asked about in the past tense is answered from the session`() {
        val phrasings = listOf(
            "제가 전에 말씀드린 회사가 어디였죠?",
            "내가 아까 얘기한 직장이 어디였지?",
            "제가 알려드린 부서가 뭐였나요?",
        )
        val wrong = phrasings.filterNot { route(withoutFocus(it)) is TurnRoutePlan.AnswerFromHistory }
        assertEquals(
            "a question about something the user themselves said is answered from what they said: $wrong",
            emptyList<String>(), wrong,
        )
    }

    // ---- historical recall: a contact already established ---------------------------------------

    @Test
    fun `a past tense question about the focused person touches no card`() {
        val phrasings = listOf(
            "앞서 확인한 그분 직함이 뭐였죠?",
            "지난번에 알려준 그 사람 부서가 어디였나요?",
            "이전에 찾은 명함의 회사가 뭐였지?",
        )
        val wrong = phrasings.filter { readsOrSearches(route(withFocus(it)), it) }
        assertEquals(
            "asking what was said is not asking what the card says now: $wrong",
            emptyList<String>(), wrong,
        )
    }

    @Test
    fun `a past tense question about the focused person is labelled a recall`() {
        assertEquals(DialogueAct.QUOTED_RECALL, act(withFocus("앞서 확인한 그분 직함이 뭐였죠?")))
    }

    // ---- explicit fresh search --------------------------------------------------------------------

    @Test
    fun `an explicit request for current information searches`() {
        val phrasings = listOf(
            "이 분 최신 정보로 다시 검색해 주세요.",
            "지금 기준으로 새로 찾아봐 줘.",
            "현재 상태를 검색해서 알려주세요.",
        )
        val wrong = phrasings.filterNot { text ->
            val plan = route(withFocus(text))
            plan is TurnRoutePlan.Continue && ContactReadIntent.SEARCH_VERBS.any { plan.text.contains(it) }
        }
        assertEquals("a request for current information must go and look: $wrong", emptyList<String>(), wrong)
    }

    @Test
    fun `a fresh search queries the verified name rather than the sentence`() {
        val plan = route(withFocus("이 분 최신 정보로 다시 검색해 주세요."))
        assertTrue("expected a search continuation, got $plan", plan is TurnRoutePlan.Continue)
        val text = (plan as TurnRoutePlan.Continue).text
        assertTrue("the query must name the verified person: $text", text.contains(focus.name))
        assertTrue("and must not carry the whole sentence as a name: $text", !text.contains("최신"))
    }

    @Test
    fun `a fresh search with nobody established asks instead of searching broadly`() {
        val plan = route(withoutFocus("지금 기준으로 새로 찾아봐 줘."))
        assertTrue("expected a clarification, got $plan", plan is TurnRoutePlan.Clarify)
        assertEquals(DialogueAct.CLARIFICATION_REQUIRED, act(withoutFocus("지금 기준으로 새로 찾아봐 줘.")))
    }

    // ---- explicit fresh detail / read ---------------------------------------------------------------

    @Test
    fun `an explicit request to re-read the card reads the card`() {
        val phrasings = listOf(
            "이 명함 정보를 지금 다시 조회해 주시겠어요?",
            "현재 카드 정보를 새로 확인해 줘.",
            "상세 내용을 지금 다시 읽어 줘.",
        )
        val wrong = phrasings.filterNot { route(withFocus(it)) is TurnRoutePlan.ContactDetail }
        assertEquals("a re-read must re-read: $wrong", emptyList<String>(), wrong)
    }

    @Test
    fun `a fresh read uses the focused card id`() {
        val plan = route(withFocus("이 명함 정보를 지금 다시 조회해 주시겠어요?"))
        assertTrue("expected a card read, got $plan", plan is TurnRoutePlan.ContactDetail)
        assertEquals(focus.cardId, (plan as TurnRoutePlan.ContactDetail).cardId)
    }

    @Test
    fun `a fresh read with nobody established asks instead of reading`() {
        val plan = route(withoutFocus("현재 카드 정보를 새로 확인해 줘."))
        assertTrue("expected a clarification, got $plan", plan is TurnRoutePlan.Clarify)
    }

    // ---- sentences that only mention looking things up -------------------------------------------------

    @Test
    fun `asking whether a search was requested is not a search`() {
        val text = "최신 정보를 검색해달라고 했었나?"
        assertTrue("must not search: ${route(withFocus(text))}", !readsOrSearches(route(withFocus(text)), text))
    }

    @Test
    fun `a quoted lookup sentence is not a lookup`() {
        val text = "“현재 명함을 조회해줘”라는 문장은 어떤 뜻이야?"
        assertTrue("must not read: ${route(withFocus(text))}", !readsOrSearches(route(withFocus(text)), text))
    }

    @Test
    fun `a refusal to search is not a search`() {
        val text = "지금 검색하지 마."
        assertTrue("must not search: ${route(withFocus(text))}", !readsOrSearches(route(withFocus(text)), text))
    }

    @Test
    fun `a hypothetical lookup is not a lookup`() {
        val text = "다시 조회한다면 어떤 결과가 나와?"
        assertTrue("must not read: ${route(withFocus(text))}", !readsOrSearches(route(withFocus(text)), text))
    }

    // ---- the rules stay out of each other's way -----------------------------------------------------

    @Test
    fun `an ordinary field question still reads the card`() {
        // No backward reference and no freshness word: the existing contract is unchanged.
        val plan = route(withFocus("그분 부서가 어디야?"))
        assertTrue("an ordinary field question is still a card read: $plan", plan is TurnRoutePlan.ContactDetail)
    }

    @Test
    fun `a backward word alone does not make a request a recall`() {
        // "아까 그 사람에게 메일 써줘" points backwards but asks for something to be done.
        val plan = route(withFocus("아까 그 사람에게 메일 써줘."))
        assertTrue("a request is not a recall: $plan", plan !is TurnRoutePlan.AnswerFromHistory)
    }

    @Test
    fun `a freshness word alone does not make a request a lookup`() {
        // "지금 시간 알려줘" is about the clock, not about a card.
        val plan = route(withoutFocus("지금 시간 알려줘."))
        assertTrue("must not become a contact lookup: $plan", !readsOrSearches(plan, "지금 시간 알려줘."))
    }

    @Test
    fun `a compose request keeps its own route`() {
        // Freshness words appear inside ordinary compose requests; they must not divert the turn.
        val plan = route(withFocus("그분에게 지금 바로 확인 부탁드린다고 메일 작성해줘."))
        assertTrue("a compose is not a card read: $plan", plan !is TurnRoutePlan.ContactDetail)
    }
}
