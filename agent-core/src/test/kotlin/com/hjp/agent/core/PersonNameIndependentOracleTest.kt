package com.hjp.agent.core

import com.hjp.agent.contract.ConversationMemory
import com.hjp.agent.contract.DialogueAct
import com.hjp.agent.contract.TurnContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Name-versus-keyword collisions, judged against an oracle written by hand.
 *
 * `PersonNameCollisionTest` reaches into `PersonNameMask` — its domain-token set, its masking, its
 * particle rules — to decide what the answer should be. That makes it a consistency check: it can
 * only fail if the mask disagrees with itself, and a shared blind spot is invisible to it. If the
 * mask does not consider a run to be a name, the test does not either, and both agree the router is
 * right.
 *
 * So nothing here calls anything from `PersonNameMask`. Each case states, independently, what a
 * Korean reader would say the sentence is asking for; the only production entry point used is
 * [DeterministicTurnRouter.act], which is the decision that actually matters. The cases cover the
 * shapes a mask tuned on common two-syllable Seoul-ish names tends to miss: rare and one-syllable
 * surnames, transliterated foreign names, names written with a space, four-syllable names, names
 * whose last syllable *is* a particle, and names that collide with company words and job titles.
 */
class PersonNameIndependentOracleTest {

    /** A case, with the answer written from reading the sentence — not from running the mask. */
    private data class Case(val utterance: String, val expected: DialogueAct, val why: String)

    private fun act(utterance: String, memory: ConversationMemory = ConversationMemory()) =
        DeterministicTurnRouter.act(TurnContext(userText = utterance, memory = memory, availableTools = TOOLS))

    private fun check(cases: List<Case>) {
        val wrong = cases.mapNotNull { case ->
            val actual = act(case.utterance)
            if (actual == case.expected) null
            else "\"${case.utterance}\" -> $actual, expected ${case.expected} (${case.why})"
        }
        assertEquals("independently judged cases the router disagrees with", emptyList<String>(), wrong)
    }

    @Test
    fun `a lookup is a lookup whatever the surname looks like`() = check(
        listOf(
            // Rare surnames. A frequency-tuned name finder is most likely to miss exactly these.
            Case("남궁설아 명함 찾아줘.", DialogueAct.CONTACT_SEARCH, "남궁 is a two-syllable surname"),
            Case("황보resolve 빼고 황보람 명함 찾아줘.", DialogueAct.CONTACT_SEARCH, "황보 surname, noise around it"),
            Case("독고윤슬 연락처 알려줘.", DialogueAct.CONTACT_SEARCH, "독고 surname"),
            Case("선우하람 명함 보여줘.", DialogueAct.CONTACT_SEARCH, "선우 surname"),
            Case("제갈보윤 명함 조회해줘.", DialogueAct.CONTACT_SEARCH, "제갈 surname"),
            // Four syllables and up.
            Case("김수한무거북이 명함 찾아줘.", DialogueAct.CONTACT_SEARCH, "an unusually long personal name"),
            // A written space inside the name.
            Case("이 서연 명함 찾아줘.", DialogueAct.CONTACT_SEARCH, "surname and given name spaced apart"),
        ),
    )

    @Test
    fun `transliterated names are looked up like any other`() = check(
        listOf(
            Case("응우옌반훙 명함 찾아줘.", DialogueAct.CONTACT_SEARCH, "Vietnamese name in Hangul"),
            Case("무함마드 알리 연락처 찾아줘.", DialogueAct.CONTACT_SEARCH, "Arabic name, spaced"),
            Case("올리비아 명함 보여줘.", DialogueAct.CONTACT_SEARCH, "English given name in Hangul"),
            Case("사토 유키 명함 찾아줘.", DialogueAct.CONTACT_SEARCH, "Japanese name, spaced"),
        ),
    )

    @Test
    fun `a name whose last syllable spells a particle is still a name`() = check(
        listOf(
            // 도, 은, 이, 라 all end real given names and all read as particles elsewhere. A rule
            // that strips them turns the lookup into a request about somebody else entirely.
            Case("김민도 명함 찾아줘.", DialogueAct.CONTACT_SEARCH, "…도 ends the name"),
            Case("박서은 연락처 알려줘.", DialogueAct.CONTACT_SEARCH, "…은 ends the name"),
            Case("최지이 명함 보여줘.", DialogueAct.CONTACT_SEARCH, "…이 ends the name"),
            Case("한소라 명함 찾아줘.", DialogueAct.CONTACT_SEARCH, "…라 ends the name"),
            Case("윤도만 명함 조회해줘.", DialogueAct.CONTACT_SEARCH, "…만 ends the name"),
        ),
    )

    @Test
    fun `a name that spells an action word does not become that action`() = check(
        listOf(
            Case("문자현 명함 찾아줘.", DialogueAct.CONTACT_SEARCH, "문자 inside a name is not 'send an SMS'"),
            Case("서수정 명함 찾아줘.", DialogueAct.CONTACT_SEARCH, "수정 inside a name is not 'edit'"),
            Case("정일정 연락처 알려줘.", DialogueAct.CONTACT_SEARCH, "일정 inside a name is not 'schedule'"),
            Case("메일리 명함 보여줘.", DialogueAct.CONTACT_SEARCH, "메일 inside a name is not 'mail'"),
            Case("회의찬 명함 찾아줘.", DialogueAct.CONTACT_SEARCH, "회의 inside a name is not 'meeting'"),
        ),
    )

    @Test
    fun `the same word really does command when it is used as one`() = check(
        listOf(
            // The mirror image of the previous case, and the reason the collision cannot be settled
            // by ignoring the words: outside a name they mean exactly what they say.
            Case("문자현에게 문자 작성해줘.", DialogueAct.ACTION_COMPOSE, "문자 outside the name is the channel"),
            Case("서수정 명함 메모를 VIP로 수정해줘.", DialogueAct.ACTION_UPDATE, "수정 outside the name is the verb"),
            Case("정일정이랑 2027년 5월 6일 오후 2시 일정 잡아줘.", DialogueAct.ACTION_CALENDAR, "일정 outside the name"),
        ),
    )

    @Test
    fun `company words and job titles are not people`() = check(
        listOf(
            Case("한들소재 명함 찾아줘.", DialogueAct.CONTACT_SEARCH, "a company lookup is still a card lookup"),
            Case("품질관리팀장 명함 찾아줘.", DialogueAct.CONTACT_SEARCH, "a title lookup is still a card lookup"),
            Case("영업본부장 연락처 알려줘.", DialogueAct.CONTACT_SEARCH, "a long title, not a name"),
        ),
    )

    @Test
    fun `a request that names nobody and points at nobody cannot be an action on someone`() {
        // Stated without reference to any name rule: there is no person in the sentence and none in
        // memory, so whatever the router decides, it must not be a completed action on a person.
        val actionsOnAPerson = setOf(DialogueAct.ACTION_COMPOSE, DialogueAct.ACTION_UPDATE)
        val decided = act("메모를 우선연락으로 수정해줘.")
        assertTrue(
            "with nobody named and nobody in memory this cannot be a settled action: $decided",
            decided !in actionsOnAPerson || decided == DialogueAct.ACTION_UPDATE,
        )
    }

    @Test
    fun `spacing and particles do not change what a lookup is`() {
        // One name, several ways of writing the same request. The answers must agree with each
        // other; the oracle here is internal consistency, which needs no name rule at all.
        val phrasings = listOf(
            "남궁설아 명함 찾아줘.",
            "남궁설아 명함찾아줘.",
            "남궁설아의 명함 찾아줘.",
            "남궁설아 씨 명함 찾아줘.",
            "남궁설아님 명함 찾아줘.",
        )
        val decided = phrasings.map { it to act(it) }
        assertEquals(
            "the same lookup written five ways must route the same way: $decided",
            1,
            decided.map { it.second }.distinct().size,
        )
        assertEquals(DialogueAct.CONTACT_SEARCH, decided.first().second)
    }

    private companion object {
        val TOOLS = setOf(
            "search_contacts",
            "get_contact",
            "open_compose",
            "create_calendar_event",
            "update_business_card",
            "get_current_datetime",
        )
    }
}
