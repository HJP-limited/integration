package com.example.hjp.desktop

import com.hjp.tool.contact.BusinessCardRecord
import com.hjp.tool.contact.ContactToolContracts
import com.hjp.tool.contact.UpdateBusinessCardPlugin
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SqliteCardRollbackTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun failedRefreshRestoresPersistedCardAndKeywordIndex() = runBlocking {
        val db = temporary.newFile("cards.db").absolutePath
        val before = BusinessCardRecord("rollback-1", "Test Person", company = "OriginalCompany",
            email = "old@example.invalid", tags = listOf("test"), updatedAt = "2026-01-01T00:00:00Z")
        SqliteContactRepository(db).use { repository ->
            repository.insertAll(listOf(before))
            val plugin = UpdateBusinessCardPlugin(repository, clockMillis = { 1L }, onUpdated = {
                assertEquals("ChangedCompany", repository.getById(before.id)?.company)
                error("required embedding refresh failed")
            })
            val failure = runCatching { plugin.execute(ToolRequest("update-1", ContactToolContracts.Update.capabilityId,
                ContactToolContracts.Update.version, buildJsonObject {
                    put("card_id", before.id)
                    put("updates", buildJsonObject { put("company", "ChangedCompany") })
                }), ToolExecutionContext("session", "turn", "ko-KR", "Asia/Seoul")) }.exceptionOrNull()

            assertEquals("required embedding refresh failed", failure?.message)
            assertEquals(before, repository.getById(before.id))
            assertEquals(listOf(before.id), repository.searchKeywordCandidates("OriginalCompany", 5).map { it.cardId })
            assertTrue(repository.searchKeywordCandidates("ChangedCompany", 5).isEmpty())
        }
        // Check committed data and the rebuilt FTS after reopening a real SQLite connection.
        SqliteContactRepository(db).use { repository ->
            assertEquals(before, repository.getById(before.id))
            assertEquals(1, repository.count())
            assertEquals(listOf(before.id), repository.searchKeywordCandidates("OriginalCompany", 5).map { it.cardId })
        }
    }
}
