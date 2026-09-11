package com.hjp.agent.core

import com.hjp.agent.contract.AgentIntent
import com.hjp.agent.contract.StructuredIntentPlan

/**
 * Normalizes model slots using only text grounded in the original request.
 * It never chooses an intent, action, recipient, or tool.
 */
internal object StructuredPlanPolicy {
    private val agentDirective = Regex(
        """(?:[,.;]?\s*)?(?:(?:작성\s*화면|메시지\s*화면|캘린더\s*화면).{0,20}(?:열|준비|연결).{0,12}|(?:전송|발송|저장|등록).{0,12}(?:완료|됐다고|했다고).{0,12}(?:말|알려).{0,12})""",
        RegexOption.IGNORE_CASE,
    )

    fun normalize(plan: StructuredIntentPlan, originalUserText: String): StructuredIntentPlan =
        when (plan.intent) {
            AgentIntent.COMPOSE_EMAIL, AgentIntent.COMPOSE_SMS -> plan.copy(
                contentGoal = recipientFacingGoal(plan.contentGoal),
            )
            AgentIntent.CREATE_CALENDAR_EVENT -> plan.copy(
                calendarTitle = CalendarTitlePolicy.canonicalize(
                    plan.calendarTitle,
                    originalUserText,
                ),
            )
            else -> plan
        }

    fun recipientFacingGoal(goal: String): String = goal
        .replace(agentDirective, " ")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', ',', '.', ';')
}
