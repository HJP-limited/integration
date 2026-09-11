package com.hjp.agent.core

import com.hjp.agent.contract.TurnRoutePlan
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Renders a contact answer from the card that was just read.
 *
 * Every value printed here comes from the tool result of this turn. Nothing is taken from the
 * remembered projection, so an answer can never show a field that changed since it was cached, and a
 * field the card does not have is reported as missing rather than filled in.
 */
object ContactDetailAnswer {
    private val LABELS = linkedMapOf(
        "company" to "회사",
        "department" to "부서",
        "title" to "직함",
        "industry" to "업종",
        "location" to "지역",
        "email" to "이메일",
        "mobile" to "휴대폰",
        "phone" to "전화번호",
        "address" to "주소",
        "memo" to "메모",
    )

    /** Asking for a phone number should not fail just because the card stores it as a mobile. */
    private val EQUIVALENTS = mapOf(
        "phone" to listOf("phone", "mobile"),
        "mobile" to listOf("mobile", "phone"),
    )

    fun render(route: TurnRoutePlan.ContactDetail, card: JsonObject): String {
        val name = card.string("name") ?: route.name
        if (route.requestedFields.isEmpty()) return renderCard(name, card)

        val lines = route.requestedFields.distinct().map { field ->
            val value = EQUIVALENTS.getOrDefault(field, listOf(field))
                .firstNotNullOfOrNull { card.string(it) }
            val label = LABELS[field] ?: field
            if (value == null) "- $label: 이 명함에는 정보가 없습니다."
            else "- $label: $value"
        }
        return "$name 명함 정보입니다.\n" + lines.joinToString("\n")
    }

    private fun renderCard(name: String, card: JsonObject): String {
        val lines = LABELS.mapNotNull { (field, label) ->
            card.string(field)?.let { "- $label: $it" }
        }
        if (lines.isEmpty()) return "$name 명함을 찾았지만 표시할 상세 항목이 없습니다."
        return "$name 명함 정보입니다.\n" + lines.joinToString("\n")
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.trim()
            ?.takeIf(String::isNotEmpty)
}
