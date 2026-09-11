package com.hjp.agent.contract

/**
 * What kind of move the user just made, decided before any tool runs.
 *
 * This is a *classification*, not a plan: it says what the turn is, while [TurnRoutePlan] says what
 * the agent will do about it. Keeping them apart is what lets an evaluator assert the routing
 * decision independently of the tool trace, and it is why a phrase that merely mentions "메일" can be
 * an information question rather than a compose command.
 */
enum class DialogueAct {
    /** Find people in the contact store. */
    CONTACT_SEARCH,

    /** Ask for a field of a contact this session already established. "회사가 어디야?" */
    CONTACT_DETAIL,

    /** Pick one of the candidates already on screen: an ordinal, "처음 말한 사람", a name. */
    CONTACT_SELECTION,

    ACTION_COMPOSE,
    ACTION_CALENDAR,
    ACTION_UPDATE,
    DATETIME_QUERY,

    /** A question about this conversation's own record. */
    HISTORY_QUESTION,

    /** "방금 그거 왜 실패했어?" */
    FAILURE_QUESTION,

    /** Recalling a past instruction rather than issuing it. Never executed. */
    QUOTED_RECALL,

    /**
     * A question about a concept, format, method or meaning. It shares vocabulary with the action
     * acts ("이메일 형식", "회의록 작성 방법") but names no target and requests no execution.
     */
    GENERAL_INFORMATION,

    /** "김지원 말고 최영희" — replaces the current target. */
    CORRECTION,

    /** The turn cannot proceed until the user supplies a target or a required slot. */
    CLARIFICATION_REQUIRED,

    /** The named capability does not exist. */
    UNSUPPORTED,

    /** Nothing rule-decidable; the model decides. */
    OTHER,
}

/**
 * How a turn actually ended, in terms a user would recognise.
 *
 * Derived from the route, the executed tools and the turn status by a single deterministic
 * classifier, so the value in memory, the value in diagnostics and the value an evaluator asserts
 * are always the same value.
 */
enum class TurnOutcomeType {
    ANSWER_FROM_HISTORY,
    GENERAL_INFORMATION,
    CONTACT_SELECTED,
    CONTACT_DETAIL_SHOWN,
    CLARIFICATION_REQUIRED,
    UPDATE_COMPLETED,
    COMPOSE_OPENED,
    CALENDAR_OPENED,
    UNSUPPORTED,
    FAILED,
}

/**
 * Whether this act is the user asking the agent to do something.
 *
 * The session used to record a tracked action for *every* non-empty utterance, so "오늘 날씨가 좋네",
 * a greeting, and "제가 메일 보내달라고 했었나요?" all became requests the agent owed an answer to.
 * The deterministic router and the "왜 실패했어?" path both read that list, so the pollution was not
 * cosmetic — it changed what the agent thought it had been asked.
 *
 * Defined here rather than in the session so the router's classification is the only authority: a
 * turn is a request exactly when the act says it is.
 */
val DialogueAct.requestsAgentAction: Boolean
    get() = when (this) {
        DialogueAct.CONTACT_SEARCH,
        DialogueAct.CONTACT_DETAIL,
        DialogueAct.CONTACT_SELECTION,
        DialogueAct.ACTION_COMPOSE,
        DialogueAct.ACTION_CALENDAR,
        DialogueAct.ACTION_UPDATE,
        DialogueAct.DATETIME_QUERY,
        DialogueAct.CORRECTION,
        DialogueAct.CLARIFICATION_REQUIRED,
        -> true

        // Questions about the conversation, general knowledge, refusals and everything unclassified
        // are things the user said, not things the agent was asked to carry out.
        DialogueAct.HISTORY_QUESTION,
        DialogueAct.FAILURE_QUESTION,
        DialogueAct.QUOTED_RECALL,
        DialogueAct.GENERAL_INFORMATION,
        DialogueAct.UNSUPPORTED,
        DialogueAct.OTHER,
        -> false
    }
