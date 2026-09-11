package com.hjp.agent.core

import com.hjp.agent.contract.TurnOutcomeType
import com.hjp.agent.contract.TurnRoutePlan

/**
 * The single place a turn's typed outcome is decided.
 *
 * Deriving it from the route and the tools that actually succeeded — rather than from the answer
 * text — is what keeps the value honest: a turn cannot be labelled COMPOSE_OPENED unless
 * `open_compose` really ran, and it cannot be labelled COMPLETED-anything while a tool is still
 * broken.
 */
object TurnOutcomeClassifier {
    fun classify(
        route: TurnRoutePlan,
        executedTools: List<String>,
        status: TurnOutcome,
        terminalToolFailure: Boolean,
    ): TurnOutcomeType {
        if (status == TurnOutcome.FAILED || terminalToolFailure) return TurnOutcomeType.FAILED
        if (status == TurnOutcome.NEEDS_CLARIFICATION) return TurnOutcomeType.CLARIFICATION_REQUIRED

        // Irreversible work outranks the route: whatever the turn was *about*, what it *did* is the
        // side effect it performed.
        when {
            AgentWorkflowSession.OPEN_COMPOSE in executedTools -> return TurnOutcomeType.COMPOSE_OPENED
            AgentWorkflowSession.CREATE_CALENDAR_EVENT in executedTools ->
                return TurnOutcomeType.CALENDAR_OPENED
            AgentWorkflowSession.UPDATE_BUSINESS_CARD in executedTools ->
                return TurnOutcomeType.UPDATE_COMPLETED
        }

        return when (route) {
            is TurnRoutePlan.Unsupported -> TurnOutcomeType.UNSUPPORTED
            is TurnRoutePlan.GeneralInformation -> TurnOutcomeType.GENERAL_INFORMATION
            is TurnRoutePlan.AnswerFromHistory -> TurnOutcomeType.ANSWER_FROM_HISTORY
            is TurnRoutePlan.Clarify -> TurnOutcomeType.CLARIFICATION_REQUIRED
            is TurnRoutePlan.ContactDetail ->
                if (AgentWorkflowSession.GET_CONTACT in executedTools) TurnOutcomeType.CONTACT_DETAIL_SHOWN
                else TurnOutcomeType.FAILED
            else -> when {
                AgentWorkflowSession.GET_CONTACT in executedTools -> TurnOutcomeType.CONTACT_DETAIL_SHOWN
                AgentWorkflowSession.SEARCH_CONTACTS in executedTools -> TurnOutcomeType.CONTACT_SELECTED
                else -> TurnOutcomeType.GENERAL_INFORMATION
            }
        }
    }
}
