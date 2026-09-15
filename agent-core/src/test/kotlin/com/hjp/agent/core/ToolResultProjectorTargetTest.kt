package com.hjp.agent.core

import com.hjp.agent.contract.ConversationMemory
import com.hjp.agent.contract.ContactCandidate
import com.hjp.tool.contract.ContractVersion
import com.hjp.tool.contract.SessionStateKey
import com.hjp.tool.contract.StoredSessionState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolResultProjectorTargetTest {
    @Test
    fun `retired focus is removed from model capability state too`() = runBlocking {
        val store = InMemoryAgentSessionStore()
        val key = SessionStateKey("contact", "selected_contact")
        val data = buildJsonObject { put("card_id", "old"); put("name", "김지원") }
        val memory = ToolResultProjector.project(ConversationMemory(), "get_contact", data, 1L)
        store.update {
            it.conversationMemory = memory
            it.capabilityState[key] = StoredSessionState(ContractVersion(1, 0), data)
        }
        store.retireActionableFocus()
        assertNull(store.getOrCreate().capabilityState[key])
        assertEquals(ToolResultProjector.retireActionableFocus(memory), store.getOrCreate().conversationMemory)
    }

    @Test
    fun `new acquisition retires both candidate stores but preserves other memory`() = runBlocking {
        val store = InMemoryAgentSessionStore()
        val data = buildJsonObject {
            put("results", buildJsonArray {
                add(result("S1", "김민", "이름 일치"))
                add(result("S2", "김민", "이름 일치"))
            })
        }
        val memory = ToolResultProjector.project(ConversationMemory(), "search_contacts", data, 1L)
        val searchKey = SessionStateKey("contact", "last_search_results")
        val unrelatedKey = SessionStateKey("calendar", "draft")
        val state = StoredSessionState(ContractVersion(1, 0), data)
        store.update {
            it.conversationMemory = memory
            it.capabilityState[searchKey] = state
            it.capabilityState[unrelatedKey] = state
        }

        store.retireCandidates()

        val session = store.getOrCreate()
        assertEquals(memory.copy(candidateContacts = emptyList()), session.conversationMemory)
        assertNull(session.capabilityState[searchKey])
        assertEquals(state, session.capabilityState[unrelatedKey])
    }

    @Test
    fun `multiple search results remain ambiguous even with one exact hit`() {
        val data = buildJsonObject {
            put("results", buildJsonArray {
                add(result("S04915", "심수민", "이름·의미 일치"))
                add(result("S01590", "심민서", "의미 일치"))
            })
        }
        val memory = ToolResultProjector.project(ConversationMemory(), "search_contacts", data, 1L)
        assertNull(memory.selectedContact)
        assertEquals(listOf("S04915", "S01590"), memory.candidateContacts.map { it.cardId })
    }

    @Test
    fun `multiple exact name hits remain ambiguous`() {
        val data = buildJsonObject {
            put("results", buildJsonArray {
                add(result("S1", "김민", "이름·의미 일치"))
                add(result("S2", "김민", "이름·의미 일치"))
            })
        }
        val memory = ToolResultProjector.project(ConversationMemory(), "search_contacts", data, 1L)
        assertNull(memory.selectedContact)
    }

    @Test
    fun `get projects only safe verified display fields`() {
        val data = buildJsonObject {
            put("card_id", "C001"); put("name", "김지원")
            put("location", "대구광역시 동구"); put("department", "재무팀")
            put("email", "keep-out@example.com"); put("phone", "010-1234-5678")
        }
        val memory = ToolResultProjector.project(ConversationMemory(), "get_contact", data, 1L)

        assertEquals(
            mapOf("department" to "재무팀", "location" to "대구광역시 동구"),
            memory.selectedContact?.verifiedDisplayFields,
        )
    }

    @Test
    fun `grounded update target keeps identity without selecting or verifying fields`() {
        val memory = ToolResultProjector.persistGroundedTarget(
            ConversationMemory(), ContactCandidate("T011", "라정"),
        )
        assertEquals("T011", memory.groundedTargetIdentity?.cardId)
        assertNull(memory.selectedContact)
        val retired = ToolResultProjector.retireActionableFocus(memory)
        assertNull(retired.groundedTargetIdentity)
        assertNull(retired.selectedContact)
    }

    private fun result(cardId: String, name: String, summary: String) = buildJsonObject {
        put("card_id", cardId)
        put("name", name)
        put("match_summary", summary)
    }
}
