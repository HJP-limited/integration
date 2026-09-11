package com.hjp.agent.core

import com.hjp.tool.contract.ContractVersion
import com.hjp.tool.contract.SessionStateKey
import com.hjp.tool.contract.StoredSessionState
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactTurnReferenceResolverTest {
    @Test
    fun `single grounded result resolves pronouns and keeps card id provenance`() {
        val resolution = ContactTurnReferenceResolver.resolve("그분에게 문자 작성해줘", searchState())
        assertEquals("김지원에게 문자 작성해줘", resolution.modelText)
        assertEquals("room-1", resolution.groundedCardId)
    }

    @Test
    fun `ordinal selects the exact prior room id`() {
        val resolution = ContactTurnReferenceResolver.resolve(
            "두 번째 사람의 이메일 확인",
            searchState(includeSecond = true),
        )
        assertEquals("room-2", resolution.groundedCardId)
        assertEquals("이서연 두 번째 사람의 이메일 확인", resolution.modelText)
    }

    @Test
    fun `new explicit name and new phone suffix do not leak old focus`() {
        val newName = ContactTurnReferenceResolver.resolve("이서연에게 메일 작성해줘", searchState())
        assertEquals("이서연에게 메일 작성해줘", newName.modelText)
        assertNull(newName.groundedCardId)
        val digits = ContactTurnReferenceResolver.resolve("번호 뒷자리 4312인 사람 찾아줘", searchState())
        assertEquals("번호 뒷자리 4312인 사람 찾아줘", digits.modelText)
        assertNull(digits.groundedCardId)
    }

    @Test
    fun `multiple prior results require explicit ordinal`() {
        val ambiguous = ContactTurnReferenceResolver.resolve("그 사람 이메일은?", searchState(true))
        assertEquals("그 사람 이메일은?", ambiguous.modelText)
        assertNull(ambiguous.groundedCardId)
    }

    private fun searchState(includeSecond: Boolean = false) = mapOf(
        SessionStateKey("contact", "last_search_results") to StoredSessionState(
            ContractVersion(1, 0),
            buildJsonObject {
                put("results", buildJsonArray {
                    add(buildJsonObject { put("card_id", "room-1"); put("name", "김지원") })
                    if (includeSecond) {
                        add(buildJsonObject { put("card_id", "room-2"); put("name", "이서연") })
                    }
                })
            },
        ),
    )
}
