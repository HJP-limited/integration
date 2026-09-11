package com.hjp.agent.core

import com.hjp.agent.contract.ContactCandidate
import com.hjp.agent.contract.ContactReference
import com.hjp.agent.contract.ContactSelectionBasis
import com.hjp.agent.contract.ConversationMemory
import com.hjp.agent.contract.MemoryProvenance
import com.hjp.agent.contract.ModelConversationRole
import com.hjp.agent.contract.TranscriptEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelContextSelectorTest {
    @Test
    fun `new explicit target scopes actionable history without erasing retained history`() {
        val transcript = listOf(
            TranscriptEntry("old", ModelConversationRole.USER, "배채원님과 내일 미팅을 잡아줘", 1),
            TranscriptEntry("old", ModelConversationRole.ASSISTANT, "작성 화면을 열었습니다", 2),
            TranscriptEntry("same", ModelConversationRole.USER, "한지연님에게 전에 말한 내용을 보내줘", 3),
            TranscriptEntry("same", ModelConversationRole.ASSISTANT, "확인했습니다", 4),
        )
        val context = ModelContextSelector().select(
            ContextRequest(
                transcript = transcript,
                memory = ConversationMemory(),
                currentInput = "한지연씨 찾아서 메일 초안까지 열어줘",
                currentTurnId = "new",
                currentTarget = CurrentContactTarget(
                    cardId = "S03139", name = "한지연",
                    evidence = TurnContactTargetResolver.Evidence.EXPLICIT_NAME,
                    requiresFreshRead = true, freshReadPurpose = "email",
                ),
                targetScopedHistory = true,
                nativeConversationTurns = 1,
            ),
        )

        val rendered = context.render("한지연씨 찾아서 메일 초안까지 열어줘")
        assertTrue(context.startNewNativeConversation)
        assertTrue(rendered.contains("한지연님에게 전에 말한 내용"))
        assertTrue(!rendered.contains("배채원님과 내일 미팅"))
    }

    @Test
    fun `an empty session renders no sections so a first turn matches a single turn prompt`() {
        val context = ModelContextSelector().select(
            ContextRequest(emptyList(), ConversationMemory(), "김지원 명함 찾아줘.", "t1"),
        )

        assertTrue(context.sections.isEmpty())
        assertEquals("김지원 명함 찾아줘.", context.render("김지원 명함 찾아줘."))
    }

    @Test
    fun `sections keep policy order and the current request stays last`() {
        val transcript = (1..12).flatMap { index ->
            listOf(
                TranscriptEntry("t$index", ModelConversationRole.USER, "요청 $index 박민수 관련", index * 2L),
                TranscriptEntry("t$index", ModelConversationRole.ASSISTANT, "응답 $index", index * 2L + 1),
            )
        }
        val memory = ConversationMemory(selectedContact = selected())

        val context = ModelContextSelector().select(
            ContextRequest(transcript, memory, "박민수에게 메일 작성해줘.", "t13"),
        )

        val names = context.sections.map { it.name }
        assertEquals(
            listOf("session_state", "relevant_history", "recent_conversation", "history_digest"),
            names,
        )
        val rendered = context.render("박민수에게 메일 작성해줘.")
        assertTrue(rendered.indexOf("[session_state]") < rendered.indexOf("[recent_conversation]"))
        assertTrue(rendered.endsWith("[current_user]\n박민수에게 메일 작성해줘."))
    }

    @Test
    fun `a person named outside the recent window is pulled back by relevance`() {
        val transcript = mutableListOf(
            TranscriptEntry("t1", ModelConversationRole.USER, "박민수 명함 찾아줘.", 1),
            TranscriptEntry("t1", ModelConversationRole.ASSISTANT, "박민수 명함을 찾았습니다.", 2),
        )
        repeat(10) { index ->
            transcript += TranscriptEntry("t${index + 2}", ModelConversationRole.USER, "다른 요청 $index", 10L + index * 2)
            transcript += TranscriptEntry("t${index + 2}", ModelConversationRole.ASSISTANT, "다른 응답 $index", 11L + index * 2)
        }

        val context = ModelContextSelector().select(
            ContextRequest(transcript, ConversationMemory(), "박민수에게 메일 작성해줘.", "t99"),
        )

        val relevant = context.sections.single { it.name == "relevant_history" }
        assertTrue(relevant.body.contains("박민수 명함 찾아줘."))
        val recent = context.sections.single { it.name == "recent_conversation" }
        assertTrue(!recent.body.contains("박민수 명함 찾아줘."))
    }

    @Test
    fun `history is dropped before the current request when the budget is tight`() {
        val transcript = (1..20).flatMap { index ->
            listOf(
                TranscriptEntry("t$index", ModelConversationRole.USER, "아주 긴 요청 문장 $index ".repeat(20), index * 2L),
                TranscriptEntry("t$index", ModelConversationRole.ASSISTANT, "아주 긴 응답 문장 $index ".repeat(20), index * 2L + 1),
            )
        }
        val selector = ModelContextSelector(
            ContextBudget(maxPromptTokens = 1_024, reservedForToolCatalogTokens = 700),
        )

        val context = selector.select(
            ContextRequest(transcript, ConversationMemory(), "짧은 요청", "t99"),
        )

        assertTrue(context.estimatedTokens <= 1_024)
    }

    @Test
    fun `session state carries provenance and never carries contact channels`() {
        val memory = ConversationMemory(
            selectedContact = selected(),
            candidateContacts = listOf(
                ContactCandidate("C001", "박민수", "한빛물산", "영업팀장"),
                ContactCandidate("C002", "박민수", "그린테크", "연구원"),
            ),
        )

        val body = ModelContextSelector().select(
            ContextRequest(emptyList(), memory, "그 사람 이메일 알려줘", "t1"),
        ).sections.single { it.name == "session_state" }.body

        assertTrue(body.contains("provenance=TOOL_VERIFIED"))
        assertTrue(body.contains("basis=SINGLE_RESULT"))
        assertTrue(body.contains("1. 박민수 · 한빛물산 · 영업팀장"))
        assertTrue(!body.contains("@"))
        assertTrue(body.contains("card_id로 다시 조회"))
    }

    @Test
    fun `current target is distinct from the prior tool verified selection`() {
        val memory = ConversationMemory(
            selectedContact = selected(),
            candidateContacts = listOf(
                ContactCandidate("T001", "손다은"),
                ContactCandidate("S00633", "복정"),
            ),
        )

        val body = ModelContextSelector().select(
            ContextRequest(
                transcript = emptyList(),
                memory = memory,
                currentInput = "첫 번째 사람에게 이메일 작성해줘",
                currentTurnId = "t2",
                currentTarget = CurrentContactTarget(
                    cardId = "T001",
                    name = "손다은",
                    evidence = TurnContactTargetResolver.Evidence.ROUTER_GROUNDED,
                    requiresFreshRead = true,
                    freshReadPurpose = "email",
                ),
            ),
        ).sections.single { it.name == "session_state" }.body

        assertTrue(body.contains("current_target: card_id=T001 name=손다은"))
        assertTrue(body.contains("fresh_read_purpose=email"))
        assertTrue(body.contains("selected_contact: card_id=${selected().cardId}"))
    }

    @Test
    fun `resolved target detail request gets a narrow get contact guidance`() {
        val target = CurrentContactTarget(
            cardId = "C002", name = "이서연",
            evidence = TurnContactTargetResolver.Evidence.ROUTER_GROUNDED,
            requiresFreshRead = true, freshReadPurpose = "display",
        )
        val body = ModelContextSelector().select(
            ContextRequest(emptyList(), ConversationMemory(), "두 번째 사람 상세 정보 보여줘", "t1", currentTarget = target),
        ).sections.single { it.name == "session_state" }.body
        assertTrue(body.contains("resolved_target_detail_guidance"))
        assertTrue(body.contains("get_contact(card_id=C002, purpose=display)"))
    }

    @Test
    fun `detail guidance stays off for ambiguity and context-answerable questions`() {
        val target = CurrentContactTarget(
            cardId = "C002", name = "이서연",
            evidence = TurnContactTargetResolver.Evidence.ROUTER_GROUNDED,
            requiresFreshRead = true, freshReadPurpose = "display",
        )
        listOf("직급이 뭐야?", "회사가 어디야?").forEach { input ->
            val body = ModelContextSelector().select(
                ContextRequest(emptyList(), ConversationMemory(), input, "t1", currentTarget = target),
            ).sections.single { it.name == "session_state" }.body
            assertTrue(input, !body.contains("resolved_target_detail_guidance"))
        }
        val ambiguous = ModelContextSelector().select(
            ContextRequest(
                emptyList(),
                ConversationMemory(candidateContacts = listOf(ContactCandidate("C1", "박민수"), ContactCandidate("C2", "박민수"))),
                "그 사람 상세 정보 보여줘", "t1",
            ),
        )
        assertTrue(ambiguous.sections.none { it.body.contains("resolved_target_detail_guidance") })
    }

    @Test
    fun `rotation is requested only once the native conversation fills the budget`() {
        val request = ContextRequest(
            transcript = listOf(
                TranscriptEntry("t1", ModelConversationRole.USER, "첫 질문", 1),
                TranscriptEntry("t1", ModelConversationRole.ASSISTANT, "응답", 2),
            ),
            memory = ConversationMemory(),
            currentInput = "다음 질문",
            currentTurnId = "t2",
            nativeConversationTurns = 3,
            nativeConversationTokens = 0,
        )
        val selector = ModelContextSelector()

        val steady = selector.select(request)
        assertTrue(steady.sections.none { it.name == "recent_conversation" })
        assertTrue(!steady.startNewNativeConversation)

        val rotated = selector.select(request.copy(nativeConversationTokens = 5_000))
        assertTrue(rotated.startNewNativeConversation)
        assertTrue(rotated.sections.any { it.name == "recent_conversation" })
    }

    @Test
    fun `a pulled back turn keeps its user request and answer together and in order`() {
        // Every entry shares one millisecond, which is what the real session produced and what made
        // the previous message-level selection reorder answers under the wrong question.
        val transcript = mutableListOf(
            entry("t1", ModelConversationRole.USER, "박민수 영업팀장 명함 찾아줘."),
            entry("t1", ModelConversationRole.ASSISTANT, "박민수 명함 검색 결과입니다."),
        )
        repeat(10) { index ->
            transcript += entry("t${index + 2}", ModelConversationRole.USER, "메모 $index 확인만 해줘.")
            transcript += entry("t${index + 2}", ModelConversationRole.ASSISTANT, "메모 $index 확인했습니다.")
        }

        val context = ModelContextSelector().select(
            ContextRequest(transcript, ConversationMemory(), "박민수에게 메일 작성해줘.", "t99"),
        )

        val lines = context.sections.single { it.name == "relevant_history" }.body.lines()
        val user = lines.indexOfFirst { it.startsWith("User: 박민수 영업팀장 명함") }
        val assistant = lines.indexOfFirst { it.startsWith("Assistant: 박민수 명함 검색") }
        assertTrue("user request must be present", user >= 0)
        assertTrue("its answer must be present", assistant >= 0)
        assertEquals("the answer must follow its own request", user + 1, assistant)
    }

    @Test
    fun `several pulled back turns stay in transcript order`() {
        val transcript = mutableListOf<TranscriptEntry>()
        listOf("김지원", "박민수", "최영희").forEachIndexed { index, name ->
            transcript += entry("t$index", ModelConversationRole.USER, "$name 명함 찾아줘.")
            transcript += entry("t$index", ModelConversationRole.ASSISTANT, "$name 명함을 찾았습니다.")
        }
        repeat(8) { index ->
            transcript += entry("f$index", ModelConversationRole.USER, "무관한 요청 $index")
            transcript += entry("f$index", ModelConversationRole.ASSISTANT, "무관한 응답 $index")
        }

        val body = ModelContextSelector().select(
            ContextRequest(transcript, ConversationMemory(), "김지원 박민수 최영희 중 누구였지?", "t99"),
        ).sections.single { it.name == "relevant_history" }.body

        val order = listOf("김지원", "박민수", "최영희").map { body.indexOf("User: $it 명함 찾아줘.") }
        assertTrue("all three turns must be present: $order", order.none { it < 0 })
        assertEquals(order.sorted(), order)
    }

    @Test
    fun `an answer is never emitted without its request`() {
        val transcript = listOf(
            entry("t1", ModelConversationRole.ASSISTANT, "요청 없이 남은 응답"),
            entry("t2", ModelConversationRole.USER, "정상 요청"),
            entry("t2", ModelConversationRole.ASSISTANT, "정상 응답"),
        )

        val rendered = ModelContextSelector().select(
            ContextRequest(transcript, ConversationMemory(), "정상 요청 다시", "t99"),
        ).render("정상 요청 다시")

        assertTrue(!rendered.contains("요청 없이 남은 응답"))
        assertTrue(rendered.contains("정상 요청"))
    }

    @Test
    fun `relevant and recent never share a turn`() {
        val transcript = mutableListOf(
            entry("t1", ModelConversationRole.USER, "박민수 명함 찾아줘."),
            entry("t1", ModelConversationRole.ASSISTANT, "박민수 명함입니다."),
        )
        repeat(3) { index ->
            transcript += entry("t${index + 2}", ModelConversationRole.USER, "박민수 관련 후속 $index")
            transcript += entry("t${index + 2}", ModelConversationRole.ASSISTANT, "박민수 응답 $index")
        }

        val context = ModelContextSelector().select(
            ContextRequest(transcript, ConversationMemory(), "박민수에게 메일 작성해줘.", "t99"),
        )

        val relevant = turnLabels(context.sections.firstOrNull { it.name == "relevant_history" }?.body.orEmpty())
        val recent = turnLabels(context.sections.single { it.name == "recent_conversation" }.body)
        assertEquals(emptySet<String>(), relevant intersect recent)
    }

    @Test
    fun `the turn being executed is never restated as history`() {
        val transcript = listOf(
            entry("t1", ModelConversationRole.USER, "이전 요청"),
            entry("t1", ModelConversationRole.ASSISTANT, "이전 응답"),
            entry("t2", ModelConversationRole.USER, "현재 요청"),
        )

        val rendered = ModelContextSelector().select(
            ContextRequest(transcript, ConversationMemory(), "현재 요청", "t2"),
        ).render("현재 요청")

        assertEquals(1, Regex("현재 요청").findAll(rendered).count())
    }

    @Test
    fun `contact channels from tool results never reach the prompt`() {
        val memory = ConversationMemory(
            selectedContact = selected(),
            candidateContacts = listOf(ContactCandidate("C001", "박민수", "한빛물산", "영업팀장")),
        )
        val transcript = listOf(
            entry("t1", ModelConversationRole.USER, "박민수 명함 찾아줘."),
            entry("t1", ModelConversationRole.ASSISTANT, "박민수 · 한빛물산 · 영업팀장"),
        )

        val rendered = ModelContextSelector().select(
            ContextRequest(transcript, memory, "그 사람에게 메일 작성해줘.", "t99"),
        ).render("그 사람에게 메일 작성해줘.")

        assertTrue(!rendered.contains("@"))
        assertTrue(!PHONE_REGEX.containsMatchIn(rendered))
    }

    @Test
    fun `a long stress session stays bounded ordered and duplicate free`() {
        val transcript = mutableListOf<TranscriptEntry>()
        repeat(40) { index ->
            transcript += entry("s$index", ModelConversationRole.USER, "요청 $index 박민수 관련 내용")
            transcript += entry("s$index", ModelConversationRole.ASSISTANT, "응답 $index")
        }

        val context = ModelContextSelector().select(
            ContextRequest(transcript, ConversationMemory(), "박민수에게 메일 작성해줘.", "s99"),
        )

        val relevant = turnLabels(context.sections.firstOrNull { it.name == "relevant_history" }?.body.orEmpty())
        val recent = turnLabels(context.sections.single { it.name == "recent_conversation" }.body)
        assertEquals(emptySet<String>(), relevant intersect recent)
        assertTrue(context.estimatedTokens <= ContextBudget().maxPromptTokens)
        assertTrue(context.render("박민수에게 메일 작성해줘.").endsWith("[current_user]\n박민수에게 메일 작성해줘."))
    }

    private fun turnLabels(body: String): Set<String> =
        TURN_LABEL_REGEX.findAll(body).map { it.groupValues[1] }.toSet()

    private fun entry(turnId: String, role: ModelConversationRole, text: String) =
        TranscriptEntry(turnId, role, text, FIXED_MILLIS)

    private fun selected() = ContactReference(
        cardId = "C001",
        name = "박민수",
        company = "한빛물산",
        title = "영업팀장",
        selection = ContactSelectionBasis.SINGLE_RESULT,
        provenance = MemoryProvenance.TOOL_VERIFIED,
        confirmedAtEpochMillis = 1,
    )

    private companion object {
        /** All entries share a millisecond, exactly as the real session produced them. */
        const val FIXED_MILLIS = 1_700_000_000_000L
        val TURN_LABEL_REGEX = Regex("^turn (\\d+)$", RegexOption.MULTILINE)
        val PHONE_REGEX = Regex("01[016-9]-?\\d{3,4}-?\\d{4}")
    }
}
