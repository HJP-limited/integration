package com.hjp.agent.core

import com.hjp.agent.contract.ConversationMemory
import com.hjp.agent.contract.DialogueAct
import com.hjp.agent.contract.TurnContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A person whose name spells one of the agent's own verbs is still a person.
 *
 * The two names that exposed this — 문자현 and 서수정 — appear below only as members of a much larger
 * generated set. The set is the point: the collision is a property of substring matching, so the test
 * has to be a property too. Every syllable that starts a Korean surname is combined with every action
 * and attribute word the agent knows, which produces names nobody wrote down and which no
 * implementation could have been tuned for.
 */
class PersonNameCollisionTest {

    /** Ordinary Korean surname syllables. */
    private val surnames = listOf("김", "이", "박", "최", "정", "강", "조", "윤", "장", "임", "서", "문", "남", "표")

    /**
     * Every word the agent treats as an action or an attribute. A generated name embeds one of these
     * whole, which is exactly the shape that used to hijack the route.
     */
    private val embedded = listOf(
        "문자", "메일", "수정", "조회", "검색", "일정", "시간", "회의", "약속", "주소", "번호", "변경", "작성",
    )

    /**
     * "김문자", "서수정", "남일정" … a surname followed by a colliding word.
     *
     * A generated string that *is* a domain word outright — 이 + 메일 spells 이메일 — is excluded.
     * Such a string is genuinely ambiguous without a store lookup ("이메일 명함 찾아줘" reads as
     * "find the email business card"), and no rule over the sentence alone can resolve it. The
     * limitation is stated in the report rather than papered over here.
     */
    private val collidingNames: List<String> =
        surnames.flatMap { surname -> embedded.map { "$surname$it" } }
            .distinct()
            .filterNot { it in PersonNameMask.DOMAIN_TOKENS }

    private val readPhrasings = listOf(
        "%s 명함 찾아줘.",
        "%s 명함 보여줘.",
        "%s 연락처 조회해줘.",
        "%s 연락처 확인해줘.",
        "%s 명함 검색해줘.",
        "%s 명함좀 찾아줘.",
    )

    @Test
    fun `looking a colliding name up is a contact search, whatever the phrasing`() {
        val wrong = mutableListOf<String>()
        collidingNames.forEach { name ->
            readPhrasings.forEach { phrasing ->
                val text = phrasing.format(name)
                val act = DeterministicTurnRouter.act(context(text))
                if (act != DialogueAct.CONTACT_SEARCH) wrong += "$text -> $act"
            }
        }
        assertTrue(
            "${wrong.size} of ${collidingNames.size * readPhrasings.size} lookups were misrouted:\n" +
                wrong.take(20).joinToString("\n"),
            wrong.isEmpty(),
        )
    }

    /** The regressions that exposed the defect, kept as named cases as well as generated ones. */
    @Test
    fun `the two known regressions route as searches`() {
        assertEquals(DialogueAct.CONTACT_SEARCH, DeterministicTurnRouter.act(context("문자현 명함 찾아줘.")))
        assertEquals(DialogueAct.CONTACT_SEARCH, DeterministicTurnRouter.act(context("서수정 연락처 조회해줘.")))
    }

    /**
     * The other half of the property: masking a name must not disarm a real command.
     *
     * "문자현에게 … 문자 작성해줘" still has to be a compose, or the fix would have traded one class of
     * misrouting for another.
     */
    @Test
    fun `a colliding name does not stop the same word from being a command`() {
        val wrong = mutableListOf<String>()
        collidingNames.forEach { name ->
            listOf(
                "${name}에게 자료 잘 받았다고 문자 작성해줘." to DialogueAct.ACTION_COMPOSE,
                "${name}에게 제목은 문의, 내용은 확인 부탁드립니다 라고 메일 작성해줘." to DialogueAct.ACTION_COMPOSE,
                "${name} 명함 메모를 재검토로 수정해줘." to DialogueAct.ACTION_UPDATE,
            ).forEach { (text, expected) ->
                val act = DeterministicTurnRouter.act(context(text))
                if (act != expected) wrong += "$text -> $act (expected $expected)"
            }
        }
        assertTrue(
            "${wrong.size} commands were disarmed:\n" + wrong.take(20).joinToString("\n"),
            wrong.isEmpty(),
        )
    }

    /** Masking must never blank the agent's own vocabulary, whatever position it sits in. */
    @Test
    fun `domain words are never mistaken for names`() {
        val kept = listOf(
            "메일 주소 알려줘." to "메일",
            "전화 번호 알려줘." to "번호",
            "그 사람 회사가 어디인가요?" to "회사",
            "그분 연락처 확인해줘." to "연락처",
            "회의 일정 만들어줘." to "일정",
            "문자 작성해줘." to "문자",
            "명함 수정해줘." to "수정",
        )
        kept.forEach { (text, word) ->
            assertTrue(
                "$word disappeared from ${PersonNameMask.maskNames(text)}",
                PersonNameMask.maskNames(text).contains(word),
            )
        }
    }

    @Test
    fun `masking removes a colliding word only when it is inside a name`() {
        assertFalse(PersonNameMask.containsOutsideNames("문자현 명함 찾아줘.", "문자"))
        assertTrue(PersonNameMask.containsOutsideNames("문자현에게 문자 작성해줘.", "문자"))
        assertFalse(PersonNameMask.containsOutsideNames("서수정 연락처 조회해줘.", "수정"))
        assertTrue(PersonNameMask.containsOutsideNames("서수정 명함 메모를 VIP로 수정해줘.", "수정"))
    }

    /**
     * A run longer than a name is left alone rather than half-blanked.
     *
     * Six syllables is the ceiling, so 품질관리팀장 (exactly six) is treated as a name and blanked —
     * harmlessly, since it holds no keyword — while a seven-syllable compound is left intact.
     */
    @Test
    fun `a run longer than a name is not blanked`() {
        val text = "해외영업본부장에게 문자 작성해줘."
        assertEquals(text, PersonNameMask.maskNames(text))
        assertTrue(PersonNameMask.containsOutsideNames(text, "문자"))
    }

    /** Spacing and particles vary; the classification must not. */
    @Test
    fun `spacing and particle variants of a colliding lookup agree`() {
        listOf(
            "문자현 명함 찾아줘.",
            "문자현  명함  찾아줘.",
            "문자현 명함을 찾아줘.",
            "문자현 명함 좀 찾아줘.",
            "문자현씨 명함 찾아줘.",
        ).forEach {
            assertEquals(it, DialogueAct.CONTACT_SEARCH, DeterministicTurnRouter.act(context(it)))
        }
    }

    private fun context(text: String) = TurnContext(text, ConversationMemory(), emptyList(), TOOLS)

    private companion object {
        val TOOLS = setOf(
            "search_contacts", "get_contact", "open_compose",
            "create_calendar_event", "update_business_card", "get_current_datetime",
        )
    }
}
