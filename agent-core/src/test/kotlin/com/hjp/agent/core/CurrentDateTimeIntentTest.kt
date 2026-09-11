package com.hjp.agent.core

import com.hjp.agent.contract.ConversationMemory
import com.hjp.agent.contract.DialogueAct
import com.hjp.agent.contract.ModelToolCall
import com.hjp.agent.contract.TurnContext
import com.hjp.tool.contract.ConfirmationPolicy
import com.hjp.tool.contract.ContractVersion
import com.hjp.tool.contract.PiiLevel
import com.hjp.tool.contract.ToolCapabilityId
import com.hjp.tool.contract.ToolContract
import com.hjp.tool.contract.ToolEffect
import com.hjp.tool.contract.ToolPresentation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One detector, three consumers.
 *
 * The defect this guards against was not a missing phrasing but a missing *owner*: the pre-router,
 * the workflow validator and the emulator gateway each carried a private pattern for "is this a
 * clock question", and they disagreed. "지금 몇 시인지 알려줘" was routed as a clock query and then
 * validated as a clock query that had failed to consult the clock, because the third copy did not
 * recognise the sentence and never emitted the tool call. So these assertions are as much about the
 * three consumers agreeing as about any individual sentence.
 */
class CurrentDateTimeIntentTest {

    private val directQuestions = listOf(
        "지금 몇 시인지 알려줘.",
        "지금 몇 시야?",
        "지금 몇시야?",
        "현재 시각 알려줘.",
        "현재 시간 확인해줘.",
        "오늘 날짜 알려줘.",
        "오늘 무슨 요일이야?",
        "현재 시간 좀 알려줘.",
        "오늘 날짜 알려주세요.",
        "오늘 며칠이야?",
        "날짜 알려줘.",
    )

    /**
     * Sentences where a time expression belongs to a more specific request. Answering any of these
     * with the clock would silently drop the action the user actually asked for.
     */
    private val actionOwnsTheTime = listOf(
        "내일 오후 3시에 회의 일정 만들어줘.",
        "회의 시간을 변경해줘.",
        "김민수에게 현재 시간을 알려주는 문자 작성해줘.",
        "2027년 2월 18일 오후 3시 교육 협의 일정 만들어줘.",
        "지금부터 한 시간 뒤 일정 잡아줘.",
        "오늘 날짜로 명함 메모를 수정해줘.",
        "지금 시각 기준으로 일정 잡아줘.",
    )

    @Test
    fun `every ordinary way of asking the clock is a direct query`() {
        directQuestions.forEach {
            assertTrue("expected a clock query: $it", CurrentDateTimeIntent.isDirectQuery(it))
        }
    }

    @Test
    fun `a more specific action keeps the turn even when it names a time`() {
        actionOwnsTheTime.forEach {
            assertFalse("expected not a clock query: $it", CurrentDateTimeIntent.isDirectQuery(it))
        }
    }

    @Test
    fun `the router labels exactly the direct queries as datetime turns`() {
        directQuestions.forEach {
            assertEquals(it, DialogueAct.DATETIME_QUERY, DeterministicTurnRouter.act(context(it)))
        }
        actionOwnsTheTime.forEach {
            assertNotEquals(it, DialogueAct.DATETIME_QUERY, DeterministicTurnRouter.act(context(it)))
        }
    }

    /**
     * The validator's view has to be the same view: a clock question may call
     * `get_current_datetime`, and a sentence a more specific action owns may not.
     */
    @Test
    fun `the workflow validator agrees with the router about which turns need the clock`() {
        directQuestions.forEach { text ->
            assertEquals(
                "$text should be allowed to read the clock",
                WorkflowValidationResult.Allow,
                ProductionAgentWorkflowPolicy().startTurn(text, TZ).validate(NOW_CALL, DATETIME),
            )
        }
        actionOwnsTheTime.forEach { text ->
            val result = ProductionAgentWorkflowPolicy().startTurn(text, TZ)
                .validate(NOW_CALL, DATETIME)
            // A relative-date calendar turn legitimately resolves its date through the clock; what
            // must never happen is the clock being accepted as the *answer* to the turn.
            if (result is WorkflowValidationResult.Allow) {
                assertTrue(
                    "$text was allowed the clock without being a relative-date calendar turn",
                    RELATIVE_DATE_WORDS.any(text::contains),
                )
            }
        }
    }

    /** A turn that is not a clock question is never failed for not having asked the clock. */
    @Test
    fun `an action turn is not accused of an unfinished clock lookup`() {
        actionOwnsTheTime.forEach { text ->
            val verdict = ProductionAgentWorkflowPolicy().startTurn(text, TZ)
                .validateFinal("확인했습니다.")
            val message = (verdict as? WorkflowFinalValidationResult.Replace)?.safeMessageKo.orEmpty()
            assertFalse("$text -> $message", message.contains("현재 시각 조회를 완료하지 못했습니다"))
        }
    }

    private fun context(text: String) = TurnContext(text, ConversationMemory(), emptyList(), TOOLS)

    private companion object {
        const val TZ = "Asia/Seoul"
        val RELATIVE_DATE_WORDS = listOf("오늘", "내일", "모레", "다음 주", "이번 주")
        val TOOLS = setOf(
            "search_contacts", "get_contact", "open_compose",
            "create_calendar_event", "update_business_card", "get_current_datetime",
        )
        val NOW_CALL = ModelToolCall(
            callId = "c1",
            modelToolName = AgentWorkflowSession.GET_CURRENT_DATETIME,
            arguments = buildJsonObject { },
        )
        val DATETIME = ToolContract(
            ToolCapabilityId("test.get_current_datetime"),
            AgentWorkflowSession.GET_CURRENT_DATETIME,
            ContractVersion(1, 0),
            "current date and time",
            buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                put("required", JsonArray(emptyList()))
                put("properties", buildJsonObject { })
            },
            buildJsonObject { put("type", "object") },
            ToolEffect.READ_ONLY,
            ConfirmationPolicy.NONE,
            PiiLevel.NONE,
            PiiLevel.NONE,
            defaultTimeoutMillis = 1_000,
            presentation = ToolPresentation("실행", "완료", "불가"),
        )
    }
}
