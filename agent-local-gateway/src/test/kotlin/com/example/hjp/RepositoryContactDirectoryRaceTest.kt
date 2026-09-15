package com.example.hjp

import com.hjp.agent.core.ContactNameCandidates
import com.hjp.tool.contact.BusinessCardRecord
import com.hjp.tool.contact.BusinessCardRepository
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RepositoryContactDirectoryRaceTest {
    @Test
    fun `invalidation waits for an older build before clearing the index`() = runBlocking {
        val loaded = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cards = listOf(BusinessCardRecord("old", "김지원"))
        var first = true
        val directory = RepositoryContactDirectory(object : BusinessCardRepository {
            override suspend fun loadAll(): List<BusinessCardRecord> {
                val snapshot = cards.toList()
                if (first) {
                    first = false
                    loaded.complete(Unit)
                    release.await()
                }
                return snapshot
            }
            override suspend fun getById(cardId: String) = cards.find { it.id == cardId }
        })
        val oldRead = async { directory.resolve(ContactNameCandidates.candidates("김지원씨 찾아줘")) }
        loaded.await()
        cards = cards + BusinessCardRecord("new", "이서연")
        val invalidation = async(start = CoroutineStart.UNDISPATCHED) { directory.invalidate() }
        try { assertFalse("An in-flight build must not overwrite invalidation", invalidation.isCompleted) }
        finally { release.complete(Unit) }
        oldRead.await()
        invalidation.await()
        assertEquals(listOf("new"), directory.resolve(ContactNameCandidates.candidates("이서연씨 찾아줘")).flatMap { it.cardIds })
    }
}
