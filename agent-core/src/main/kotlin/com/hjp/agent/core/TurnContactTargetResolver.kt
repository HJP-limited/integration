package com.hjp.agent.core

import com.hjp.agent.contract.ConversationMemory
import com.hjp.agent.contract.TurnRoutePlan

/**
 * Whether *this* turn is about a contact, and if so which one.
 *
 * The distinction this type exists to make is between two things that used to be the same value:
 *
 * - **Session focus** (`ConversationMemory.selectedContact`) — who the conversation is currently
 *   about. It survives filler turns, topic changes and unrelated requests, which is exactly what
 *   makes a follow-up like "그 사람에게 메일 써줘" work.
 * - **Turn target** — who *this* request acts on. It exists only when the current utterance actually
 *   refers to somebody.
 *
 * Treating the focus as the target meant that once any contact had been looked up, every later
 * request inherited them: "2027년 2월 18일 오후 3시 교육 협의 일정 만들어줘" — a schedule with no
 * attendee in it at all — was validated as a contact-bound calendar event, and the turn died asking
 * for a contact lookup the user had never requested. Deleting the focus would have broken anaphora
 * instead, so focus is kept and the promotion rule is what became explicit.
 *
 * A turn target is established by exactly three kinds of evidence and by nothing else:
 *
 *  1. the pre-router already resolved a reference onto a tool-verified card ([TurnRoutePlan.GroundedContact]);
 *  2. the utterance uses an anaphor for the person in focus ("그 사람", "그분", "이 사람", "그 연락처");
 *  3. the utterance names the person in focus, so it is the same person by their own name.
 *
 * Anything else — a bare schedule, a general question, a request naming somebody new, a correction —
 * has no contact target, and the turn proceeds without one rather than guessing.
 */
object TurnContactTargetResolver {

    /** How the current utterance established its target. Recorded so a decision can be explained. */
    enum class Evidence {
        /** The pre-router resolved a reference onto a verified card id. */
        ROUTER_GROUNDED,

        /** "그 사람", "그분" — the utterance points at the contact in focus. */
        ANAPHOR,

        /** The utterance names the contact in focus. */
        EXPLICIT_NAME,
    }

    sealed interface Target {
        /** This turn acts on [cardId], on the strength of [evidence]. */
        data class Confirmed(val cardId: String, val evidence: Evidence) : Target

        /** This turn refers to no contact. The session focus, if any, stays untouched. */
        data object None : Target
    }

    fun resolve(
        route: TurnRoutePlan,
        userText: String,
        memory: ConversationMemory,
    ): Target {
        // The router only ever grounds a reference on an id this session obtained from a tool, so
        // this is already a verified target and needs no second opinion.
        if (route is TurnRoutePlan.GroundedContact) {
            return Target.Confirmed(route.cardId, Evidence.ROUTER_GROUNDED)
        }
        // A correction retires the remembered person: they are precisely who the user just rejected.
        if (route is TurnRoutePlan.CorrectionReplacement) return Target.None

        return focusReferencedBy(userText, memory)
    }

    /**
     * The contact in focus, but only when [userText] actually refers to them.
     *
     * Split out so every component that has to answer "is this turn about the remembered person?"
     * asks the same question of the same words, rather than each testing `selectedContact != null`
     * and getting a different answer for the same sentence.
     */
    fun focusReferencedBy(userText: String, memory: ConversationMemory): Target {
        val focus = memory.selectedContact?.takeIf { it.isActionable } ?: return Target.None
        val squeezed = userText.squeeze()
        // Naming the person in focus confirms them; naming somebody else leaves this turn with no
        // target at all, which is what stops a new name from silently inheriting the old card.
        if (focus.name.isNotBlank() && squeezed.contains(focus.name.squeeze())) {
            return Target.Confirmed(focus.cardId, Evidence.EXPLICIT_NAME)
        }
        if (ContactAnaphora.isPresent(userText)) {
            return Target.Confirmed(focus.cardId, Evidence.ANAPHOR)
        }
        return Target.None
    }

    /**
     * True when this utterance names a person who is not the contact currently in focus.
     *
     * The point is what happens *afterwards*. Held-out v3 caught a real wrong-recipient failure this
     * way: the user asked for 봉예람, that turn failed to run any lookup, the previous person stayed
     * in focus, and the next turn's "그분에게" opened a mail composer addressed to the previous
     * person. So a turn that names somebody new has to retire the old target whether or not its own
     * lookup succeeds — fail closed, not fall back.
     *
     * It reads [PersonNameMask.nameSpans], the same span finder the keyword masking uses, so there is
     * no second and differently-behaved idea of where a name is.
     */
    fun namesSomeoneOtherThanFocus(userText: String, memory: ConversationMemory): Boolean {
        // The broader detector: a mention this misses leaves a stale target reachable by the next
        // anaphor, which is the wrong-recipient failure this guard exists to prevent.
        val spans = PersonNameMask.mentionSpans(userText)
        if (spans.isEmpty()) return false
        val known = buildSet {
            memory.selectedContact?.name?.let { add(it.squeeze()) }
            memory.candidateContacts.forEach { add(it.name.squeeze()) }
            memory.contactMentions.forEach { add(it.name.squeeze()) }
        }
        // A span that matches nobody this session has surfaced is a new person. Substring matching
        // both ways so "봉예람" and "봉예람씨" count as the same person.
        return spans.any { span ->
            val needle = span.squeeze()
            known.none { it.contains(needle) || needle.contains(it) }
        }
    }

    /** Convenience for callers that only need the id. */
    fun resolveCardId(
        route: TurnRoutePlan,
        userText: String,
        memory: ConversationMemory,
    ): String? = (resolve(route, userText, memory) as? Target.Confirmed)?.cardId

    private fun String.squeeze(): String = replace(Regex("\\s+"), "")
}
