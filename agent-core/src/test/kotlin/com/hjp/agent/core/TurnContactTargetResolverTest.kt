package com.hjp.agent.core

import com.hjp.agent.contract.ContactCandidate
import com.hjp.agent.contract.ContactReference
import com.hjp.agent.contract.ContactSelectionBasis
import com.hjp.agent.contract.ConversationMemory
import com.hjp.agent.contract.MemoryProvenance
import com.hjp.agent.contract.TurnRoutePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Session focus is not the turn's target.
 *
 * Everything here is one distinction: the conversation remembering who it is about, versus the
 * current sentence saying who it acts on. Collapsing the two is what made an ordinary schedule
 * request inherit whoever had last been looked up.
 */
class TurnContactTargetResolverTest {

    @Test
    fun `a grounded route is already a verified target`() {
        val target = TurnContactTargetResolver.resolve(
            TurnRoutePlan.GroundedContact("김지원에게 메일 작성해줘.", "C001", "김지원"),
            "그 사람에게 메일 작성해줘.",
            focusOn("C001", "김지원"),
        )

        assertEquals(
            TurnContactTargetResolver.Target.Confirmed(
                "C001", TurnContactTargetResolver.Evidence.ROUTER_GROUNDED,
            ),
            target,
        )
    }

    @Test
    fun `an anaphor promotes the contact in focus`() {
        val target = TurnContactTargetResolver.resolve(
            TurnRoutePlan.Continue("그분 메모 확인해줘."),
            "그분 메모 확인해줘.",
            focusOn("C001", "김지원"),
        )

        assertEquals(
            TurnContactTargetResolver.Target.Confirmed(
                "C001", TurnContactTargetResolver.Evidence.ANAPHOR,
            ),
            target,
        )
    }

    @Test
    fun `naming the person in focus confirms them`() {
        val target = TurnContactTargetResolver.resolve(
            TurnRoutePlan.Continue("김지원에게 문자 작성해줘."),
            "김지원에게 문자 작성해줘.",
            focusOn("C001", "김지원"),
        )

        assertEquals(
            TurnContactTargetResolver.Target.Confirmed(
                "C001", TurnContactTargetResolver.Evidence.EXPLICIT_NAME,
            ),
            target,
        )
    }

    /** The defect, stated directly: a schedule with no attendee in it has no contact target. */
    @Test
    fun `a request that mentions nobody does not inherit the focus`() {
        listOf(
            "2027년 2월 18일 오후 3시 교육 협의 일정 만들어줘.",
            "내일 오후 3시에 회의 일정 만들어줘.",
            "오늘 날짜 알려줘.",
            "고맙습니다.",
        ).forEach { text ->
            assertNull(
                text,
                TurnContactTargetResolver.resolveCardId(
                    TurnRoutePlan.Continue(text), text, focusOn("C001", "김지원"),
                ),
            )
        }
    }

    @Test
    fun `naming somebody new never reuses the remembered card`() {
        assertNull(
            TurnContactTargetResolver.resolveCardId(
                TurnRoutePlan.Continue("박민수에게 메일 작성해줘."),
                "박민수에게 메일 작성해줘.",
                focusOn("C001", "김지원"),
            ),
        )
    }

    @Test
    fun `a correction retires the rejected target`() {
        assertNull(
            TurnContactTargetResolver.resolveCardId(
                TurnRoutePlan.CorrectionReplacement("박민수 명함 찾아줘.", "김지원"),
                "김지원 말고 박민수 명함 찾아줘.",
                focusOn("C001", "김지원"),
            ),
        )
    }

    @Test
    fun `a focus the session never verified is not a target`() {
        val inferred = ConversationMemory(
            selectedContact = ContactReference(
                cardId = "C001",
                name = "김지원",
                selection = ContactSelectionBasis.MODEL_INFERRED,
                provenance = MemoryProvenance.MODEL_INFERRED,
                confirmedAtEpochMillis = 1,
            ),
        )

        assertNull(
            TurnContactTargetResolver.resolveCardId(
                TurnRoutePlan.Continue("그 사람에게 메일 작성해줘."),
                "그 사람에게 메일 작성해줘.",
                inferred,
            ),
        )
    }

    @Test
    fun `an empty session has no target at all`() {
        assertNull(
            TurnContactTargetResolver.resolveCardId(
                TurnRoutePlan.Continue("그 사람에게 메일 작성해줘."),
                "그 사람에게 메일 작성해줘.",
                ConversationMemory(),
            ),
        )
    }

    private fun focusOn(cardId: String, name: String) = ConversationMemory(
        selectedContact = ContactReference(
            cardId = cardId,
            name = name,
            company = "비전글로벌",
            title = "대표이사",
            selection = ContactSelectionBasis.SINGLE_RESULT,
            provenance = MemoryProvenance.TOOL_VERIFIED,
            confirmedAtEpochMillis = 1,
        ),
        candidateContacts = listOf(ContactCandidate(cardId, name, "비전글로벌", "대표이사")),
    )
}
