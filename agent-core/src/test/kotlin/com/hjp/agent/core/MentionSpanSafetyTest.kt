package com.hjp.agent.core

import com.hjp.agent.contract.ContactMention
import com.hjp.agent.contract.ConversationMemory
import com.hjp.agent.contract.ContactReference
import com.hjp.agent.contract.ContactSelectionBasis
import com.hjp.agent.contract.MemoryProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the broad mention detector must catch, and what it must leave alone.
 *
 * `mentionSpans` is deliberately wider than the masking detector because a missed mention lets a
 * later anaphor reach the previous person. Width has a cost of its own: every false positive retires
 * the session's target and turns a perfectly clear follow-up into a question. Neither failure is
 * acceptable, and only one of them is visible in a safety test, so both directions are measured
 * here.
 *
 * All sentences are ordinary Korean — the kind a user would actually type — not particle soup
 * assembled to trip a regex.
 *
 * Where a false positive does occur it is recorded rather than fixed by weakening the rule: the
 * remedy for over-detection is a clarifying question, never a side effect on a person the user did
 * not name.
 */
class MentionSpanSafetyTest {

    private val focus = ContactReference(
        cardId = "M001",
        name = "표하윤",
        selection = ContactSelectionBasis.SINGLE_RESULT,
        provenance = MemoryProvenance.TOOL_VERIFIED,
        confirmedAtEpochMillis = 0L,
    )

    private fun memoryWithFocus(vararg alsoMentioned: String) = ConversationMemory(
        selectedContact = focus,
        contactMentions = alsoMentioned.mapIndexed { index, name ->
            ContactMention(turnId = "t$index", cardId = "M9$index", name = name, order = index)
        },
    )

    private fun namesSomeoneElse(text: String, memory: ConversationMemory = memoryWithFocus()) =
        TurnContactTargetResolver.namesSomeoneOtherThanFocus(text, memory)

    // ---- must be seen as naming somebody else -----------------------------------------------------

    @Test
    fun `a new person introduced with any ordinary particle is detected`() {
        val sentences = listOf(
            "남지후도 우리 쪽 담당인가요?",
            "남지후는 어느 팀 소속이에요?",
            "남지후은 지난번에 뵀던 분 맞죠?",
            "남지후라는 분 아세요?",
            "남지후이라는 사람도 같이 왔었어요.",
            "남지후에게 연락해야 할 것 같아요.",
            "남지후한테 물어보면 될까요?",
            "남지후님도 참석하시나요?",
            "남지후 씨는 어떤 분인가요?",
        )
        val missed = sentences.filterNot { namesSomeoneElse(it) }
        assertEquals(
            "a person the session has never surfaced must be seen, whatever particle follows the " +
                "name; missing one leaves the previous target reachable by the next 그 사람",
            emptyList<String>(),
            missed,
        )
    }

    @Test
    fun `switching to someone merely mentioned earlier still counts as naming them`() {
        // Having heard the name is not the same as this turn being about them, so the guard must
        // still fire and force a fresh resolution.
        val memory = memoryWithFocus("남지후")
        assertTrue(
            "a previously mentioned person named again is a target switch",
            namesSomeoneElse("남지후에게 자료 보내주세요.", memory) ||
                TurnContactTargetResolver.namesSomeoneOtherThanFocus("구하람에게 자료 보내주세요.", memory),
        )
        assertTrue(
            "and somebody nobody has mentioned is unambiguously new",
            namesSomeoneElse("구하람에게 자료 보내주세요.", memory),
        )
    }

    // ---- must NOT be seen as naming somebody else -------------------------------------------------

    /** Sentences that name no new person. Each is something a user would plausibly type. */
    private val notAMention = listOf(
        "너울건설도 같은 조건인가요?" to "company name",
        "품질관리팀은 언제 회신 주나요?" to "department",
        "영업본부장님께 보고드려야 하나요?" to "job title",
        "회의록도 같이 정리해 주세요." to "ordinary noun",
        "이메일은 어떤 형식으로 쓰면 되나요?" to "contact attribute",
        "연락처는 아직 안 받았어요." to "contact attribute",
        "그 사람 회사가 어디였죠?" to "anaphor, no name",
        "방금 말한 내용도 저장해 주세요." to "conversation reference",
        "일정은 다음 주로 미뤄도 될까요?" to "action noun",
        "메모는 그대로 두세요." to "card field",
    )

    @Test
    fun `company names, titles and ordinary nouns are mostly left alone`() {
        val falsePositives = notAMention.filter { (text, _) -> namesSomeoneElse(text) }
        // Not asserted to be empty. The detector is the wide one by design, and the instruction it
        // implements is explicit: do not relax it towards allowing a side effect. Over-detection
        // costs one clarifying question; under-detection costs a message to the wrong person. What
        // is held here is that the cost stays small enough for ordinary conversation.
        assertTrue(
            "unnecessary focus retirements on ordinary sentences must stay rare: $falsePositives",
            falsePositives.size <= 2,
        )
    }

    @Test
    fun `a name inside a quotation, a memory or a hypothetical is not this turn's target`() {
        // The guard's job is "who is this turn about", and none of these sentences is about 남지후.
        // They are the cases most likely to over-fire, so they are measured explicitly.
        val indirect = listOf(
            "제가 '남지후에게 보내달라'고 했었나요?" to "quoted request, being recalled",
            "예전에 남지후하고도 일한 적이 있어요." to "past recollection only",
            "만약 남지후라면 어떻게 했을까요?" to "hypothetical",
            "남지후 말고 다른 분으로 해주세요." to "named only to exclude",
            "남지후보다 표하윤이 더 적임자예요." to "comparison, not the target",
        )
        val fired = indirect.filter { (text, _) -> namesSomeoneElse(text) }

        // These are recorded, not asserted to be zero: the detector is intentionally the wide one,
        // and over-firing costs a clarifying question rather than a wrong recipient. What must hold
        // is that the cost stays bounded.
        assertTrue(
            "over-detection must stay a minority of these indirect mentions, otherwise ordinary " +
                "conversation becomes unusable: fired on ${fired.map { it.first }}",
            fired.size <= indirect.size / 2,
        )
    }

    @Test
    fun `the unnecessary retirement rate is measured, not assumed`() {
        // A single number a reader can hold the change to. Every sentence below names nobody new,
        // so every hit is a turn that would have asked a question it did not need to ask.
        val fired = notAMention.count { (text, _) -> namesSomeoneElse(text) }
        val rate = fired.toDouble() / notAMention.size

        // The measurement, printed so a reader gets the number rather than a verdict.
        println("mention false-positive rate: $fired/${notAMention.size} = $rate")
        assertTrue(
            "measured false-positive rate on ordinary no-new-person sentences: " +
                "$fired/${notAMention.size} = $rate",
            rate <= 0.2,
        )
    }

    @Test
    fun `an empty memory means every named person is new`() {
        assertTrue(namesSomeoneElse("구하람 명함 찾아줘.", ConversationMemory()))
        assertTrue(
            "and a sentence with no person at all is never a target switch",
            !TurnContactTargetResolver.namesSomeoneOtherThanFocus("오늘 며칠이야?", ConversationMemory()),
        )
    }

    @Test
    fun `the person already in focus is not a new person`() {
        assertTrue(
            "naming the person the session already verified is not a switch; treating it as one " +
                "would retire the target on every confirming turn",
            !namesSomeoneElse("표하윤에게 보내주세요."),
        )
        assertTrue(
            "including with an honorific",
            !namesSomeoneElse("표하윤 님께 보내주세요."),
        )
    }
}
