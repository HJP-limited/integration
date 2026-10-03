package com.example.hjp.desktop

import com.hjp.tool.contact.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SqliteCardRegistrationTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun vector(id: String) = StoredCardEmbedding(id, "controlled", 1,
        byteArrayOf(0, 0, -128, 63), "test-hash", 1L, 1L)

    @Test fun failedRefreshRemovesCardFtsAndPersistedVectorThenRetryCommits() = runBlocking {
        val db = temporary.newFile("registration.db").absolutePath
        val card = BusinessCardRecord("S001", "Registration Person", memo = "registrationword",
            phone = "02-1234-5678", mobile = "010-9876-5432", website = "https://example.test")
        SqliteContactRepository(db).use { repository ->
            var fail = true
            var rollbackObserved = false
            val registration = BusinessCardRegistration(repository, onAdded = {
                assertEquals(card, repository.getById(card.id))
                repository.upsertEmbeddings(listOf(vector(card.id)))
                if (fail) error("required embedding refresh failed")
            }, onRolledBack = {
                assertNull(repository.getById(card.id))
                assertTrue(repository.loadEmbeddings("controlled").isEmpty())
                rollbackObserved = true
            })
            val failure = runCatching { registration.add(card) }.exceptionOrNull()
            assertEquals("required embedding refresh failed", failure?.message)
            assertTrue(rollbackObserved)
            assertEquals(0, repository.count())
            assertTrue(repository.searchKeywordCandidates("registrationword", 5).isEmpty())
            fail = false
            registration.add(card)
            assertEquals(1, repository.count())
            assertEquals(card, repository.getById(card.id))
            assertEquals(listOf(card.id), repository.searchKeywordCandidates("registrationword", 5).map { it.cardId })
            assertEquals(listOf(card.id), repository.loadEmbeddings("controlled").map { it.cardId })
            val duplicate = runCatching { registration.add(card.copy(name = "Replacement")) }.exceptionOrNull()
            assertNotNull(duplicate)
            assertEquals(card, repository.getById(card.id))
        }
        SqliteContactRepository(db).use { repository ->
            assertEquals(card, repository.getById(card.id))
            assertEquals(1, repository.count())
            assertEquals(listOf(card.id), repository.searchKeywordCandidates("registrationword", 5).map { it.cardId })
        }
    }

    @Test fun cancellationAtCompletedInsertRollsBackBeforeModelRefresh() = runBlocking {
        withTimeout(10000) {
            SqliteContactRepository(temporary.newFile("cancel.db").absolutePath).use { sqlite ->
                val inserted = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val repository = object : InsertableBusinessCardRepository {
                    override suspend fun loadAll() = sqlite.loadAll()
                    override suspend fun getById(cardId: String) = sqlite.getById(cardId)
                    override suspend fun delete(cardId: String) = sqlite.delete(cardId)
                    override suspend fun insert(record: BusinessCardRecord) {
                        sqlite.insert(record)
                        inserted.complete(Unit)
                        release.await()
                    }
                }
                var refreshed = false
                var rolledBack = false
                val registration = BusinessCardRegistration(repository,
                    onAdded = { refreshed = true }, onRolledBack = { rolledBack = true })
                val job = launch { registration.add(BusinessCardRecord("S001", "Cancelled Person")) }
                inserted.await()
                job.cancel()
                release.complete(Unit)
                job.join()
                assertTrue(rolledBack)
                assertFalse(refreshed)
                assertEquals(0, sqlite.count())
                assertNull(sqlite.getById("S001"))
            }
        }
    }
}
