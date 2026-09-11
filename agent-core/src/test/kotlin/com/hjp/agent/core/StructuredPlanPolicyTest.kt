package com.hjp.agent.core

import com.hjp.agent.contract.AgentIntent
import com.hjp.agent.contract.IntentRecipient
import com.hjp.agent.contract.RecipientType
import com.hjp.agent.contract.StructuredIntentPlan
import org.junit.Assert.assertEquals
import org.junit.Test

class StructuredPlanPolicyTest {
    @Test
    fun `keeps recipient fact but removes completion instruction`() {
        val normalized = StructuredPlanPolicy.normalize(
            plan(AgentIntent.COMPOSE_EMAIL, contentGoal = "확인했습니다. 전송 완료됐다고 말해줘."),
            "test@example.com에게 확인했습니다라고 메일을 보내고 전송 완료됐다고 말해줘.",
        )
        assertEquals("확인했습니다", normalized.contentGoal)
    }

    @Test
    fun `canonicalizes grounded calendar title without inventing one`() {
        val normalized = StructuredPlanPolicy.normalize(
            plan(AgentIntent.CREATE_CALENDAR_EVENT, calendarTitle = "계약 검토 일정"),
            "내일 오후 2시에 계약 검토 일정을 만들어 주세요.",
        )
        assertEquals("계약 검토", normalized.calendarTitle)
    }

    private fun plan(
        intent: AgentIntent,
        contentGoal: String = "",
        calendarTitle: String? = null,
    ) = StructuredIntentPlan(
        intent = intent,
        execute = true,
        recipient = IntentRecipient(RecipientType.NONE, ""),
        contentGoal = contentGoal,
        dateExpression = null,
        timeExpression = null,
        calendarTitle = calendarTitle,
        updates = null,
        clarificationQuestion = null,
    )
}
