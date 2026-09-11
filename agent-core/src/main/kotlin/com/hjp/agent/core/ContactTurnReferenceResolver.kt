package com.hjp.agent.core

import com.hjp.tool.contract.SessionStateKey
import com.hjp.tool.contract.StoredSessionState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Superseded by [DeterministicTurnRouter], which is the single runtime authority for reference
 * resolution across the ReAct kernel, the structured kernel and the emulator router.
 *
 * This object reads raw `capabilityState` rather than the projected [com.hjp.agent.contract.ConversationMemory],
 * so it cannot see mention history, provenance or candidate ambiguity, and it is referenced by no
 * production code path. It is kept only so its 40-scenario regression stays runnable while the
 * router's equivalent coverage is extended; do not wire it into a kernel.
 */
@Deprecated("Use DeterministicTurnRouter, the single runtime reference authority.")
object ContactTurnReferenceResolver {
    data class Resolution(val modelText: String, val groundedCardId: String? = null)
    private val searchKey = SessionStateKey("contact", "last_search_results")
    private val selectedKey = SessionStateKey("contact", "selected_contact")
    private val pronouns = listOf("그 사람", "그분", "그 분", "걔")
    private val attributes = listOf(
        "회사", "직급", "직책", "부서", "번호", "전화", "이메일", "메일", "주소", "지역",
    )

    fun resolve(
        userText: String,
        state: Map<SessionStateKey, StoredSessionState>,
    ): Resolution {
        val raw = userText.trim()
        if (raw.isEmpty() || raw.any(Char::isDigit)) return Resolution(raw)
        val searchResults = (state[searchKey]?.value?.get("results") as? JsonArray)
            .orEmpty()
            .mapNotNull { it as? JsonObject }
        val knownNames = searchResults.mapNotNull { it.string("name") }
        val ordinal = when {
            raw.noSpaces().contains("첫번째") || raw.contains("1번") -> 0
            raw.noSpaces().contains("두번째") || raw.contains("2번") -> 1
            raw.noSpaces().contains("세번째") || raw.contains("3번") -> 2
            else -> -1
        }
        if (ordinal in searchResults.indices) {
            val selected = searchResults[ordinal]
            val name = selected.string("name") ?: return Resolution(raw)
            val cardId = selected.string("card_id")
            return Resolution("$name ${raw}", cardId)
        }
        if (knownNames.any { raw.noSpaces().contains(it.noSpaces()) }) return Resolution(raw)
        val selectedName = state[selectedKey]?.value?.string("name")
        val selectedId = state[selectedKey]?.value?.string("card_id")
        val searchFocusName = state[searchKey]?.value?.string("focus_name")
        val searchFocusId = state[searchKey]?.value?.string("focus_card_id")
        val focus = selectedName ?: searchFocusName ?: knownNames.singleOrNull() ?: return Resolution(raw)
        val focusId = selectedId ?: searchFocusId ?: searchResults.singleOrNull()?.string("card_id")

        var resolved = raw
            .replace("그 회사", "$focus 회사")
            .replace("그 사람에게", "${focus}에게")
            .replace("그에게", "${focus}에게")
        pronouns.forEach { pronoun -> resolved = resolved.replace(pronoun, focus) }
        if (resolved != raw) return Resolution(resolved, focusId)
        return if (attributes.any { raw.noSpaces().startsWith(it) }) {
            Resolution("$focus $raw", focusId)
        } else {
            Resolution(raw)
        }
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.trim()
            ?.takeIf(String::isNotEmpty)

    private fun String.noSpaces(): String = replace(Regex("\\s+"), "")
}
