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
 * Talking *about* an action is not asking for it.
 *
 * The router used to run its capability veto before it worked out what kind of move the turn was, so
 * "내가 좀 전에 명함 지워달라고 했나?" — a question about a past request — was refused as though the
 * user had just asked to delete a card. Held-out v3 caught it. The order has to be: settle whether
 * this is reported, recalled, questioned, hypothetical or negated speech first, and only apply the
 * capability rules to what is left, which is an actual request.
 *
 * Every group below has a positive counterpart, because a rule that only ever suppresses is not a
 * rule about meaning — it is a rule about avoiding work.
 */
class DialogueActPriorityTest {

    // ---- talking about an action ----------------------------------------------------------------

    private val notRequests = listOf(
        // recall
        "내가 좀 전에 명함 지워달라고 했나?",
        "아까 연락처 삭제해달라고 했었지?",
        "제가 명함 지워달라고 요청했나요?",
        // quotation
        "제가 ‘명함 지워줘’라고 말한 거예요?",
        "‘연락처 삭제해줘’라고 했잖아.",
        // negation
        "명함을 지우라는 뜻은 아니야.",
        "연락처 삭제하라고 한 적 없어.",
        // hypothetical
        "만약 명함을 지우면 어떻게 돼?",
        "명함을 삭제하면 복구할 수 있어?",
        // capability question
        "명함 삭제도 할 수 있어?",
        "연락처를 지우는 기능이 있나요?",
    )

    /** The control. These are actual requests and the capability policy must still apply. */
    private val realRequests = listOf(
        "이 명함 지워줘.",
        "연락처를 삭제해줘.",
        "그 사람 명함 삭제해 주세요.",
    )

    @Test
    fun `talking about deleting is never routed as a delete request`() {
        val wrong = notRequests.mapNotNull { text ->
            val act = DeterministicTurnRouter.act(context(text, transcript = priorTurn()))
            if (act == DialogueAct.UNSUPPORTED) "$text -> $act" else null
        }
        assertTrue(
            "${wrong.size} of ${notRequests.size} were treated as real delete requests:\n" +
                wrong.joinToString("\n"),
            wrong.isEmpty(),
        )
    }

    @Test
    fun `none of them runs a capability veto plan`() {
        notRequests.forEach { text ->
            val plan = DeterministicTurnRouter.route(context(text, transcript = priorTurn()))
            assertTrue("$text -> $plan", plan !is TurnRoutePlan.Unsupported)
        }
    }

    @Test
    fun `an actual delete request is still refused`() {
        realRequests.forEach { text ->
            assertEquals(
                text,
                DialogueAct.UNSUPPORTED,
                DeterministicTurnRouter.act(context(text, memory = focus())),
            )
            assertTrue(
                text,
                DeterministicTurnRouter.route(context(text, memory = focus())) is TurnRoutePlan.Unsupported,
            )
        }
    }

    /** The same ordering must hold for the other vetoed capabilities, not just deletion. */
    @Test
    fun `the ordering holds for sending and calling too`() {
        listOf(
            "내가 메일 실제로 발송해달라고 했나?",
            "‘지금 바로 전송해줘’라고 한 적 없어.",
            "만약 전화를 걸면 통화 기록이 남아?",
            "전화 거는 기능도 있나요?",
        ).forEach { text ->
            val act = DeterministicTurnRouter.act(context(text, transcript = priorTurn()))
            assertTrue("$text -> $act", act != DialogueAct.UNSUPPORTED)
        }

        listOf("그 사람에게 메일 지금 바로 전송해줘.", "그분한테 전화 걸어줘.").forEach { text ->
            assertEquals(
                text,
                DialogueAct.UNSUPPORTED,
                DeterministicTurnRouter.act(context(text, memory = focus())),
            )
        }
    }

    /** A recalled request must be answered from the transcript, not merely left unexecuted. */
    @Test
    fun `a recalled request is answered from the conversation`() {
        val plan = DeterministicTurnRouter.route(
            context("내가 좀 전에 명함 지워달라고 했나?", transcript = priorTurn()),
        )

        assertTrue(plan.toString(), plan is TurnRoutePlan.AnswerFromHistory)
        assertTrue(
            (plan as TurnRoutePlan.AnswerFromHistory).messageKo,
            plan.messageKo.contains("기록") || plan.messageKo.contains("말씀"),
        )
    }

    private fun context(
        text: String,
        memory: ConversationMemory = ConversationMemory(),
        transcript: List<TranscriptEntry> = emptyList(),
    ) = TurnContext(text, memory, transcript, TOOLS)

    private fun priorTurn() = listOf(
        TranscriptEntry("t0", ModelConversationRole.USER, "김지원 명함 찾아줘.", 0),
        TranscriptEntry("t1", ModelConversationRole.ASSISTANT, "명함 검색 결과입니다.", 1),
    )

    private fun focus() = ConversationMemory(
        selectedContact = ContactReference(
            cardId = "C001", name = "김지원", company = "비전글로벌", title = "대표이사",
            selection = ContactSelectionBasis.SINGLE_RESULT,
            provenance = MemoryProvenance.TOOL_VERIFIED, confirmedAtEpochMillis = 1,
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
