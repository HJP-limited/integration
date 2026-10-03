package com.example.hjp.desktop

import com.hjp.searchlookup.EmbeddingEngine
import com.hjp.tool.contact.BusinessCardRecord
import com.hjp.tool.contact.BusinessCardRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RequiredDesktopSearchTest {
    private val repository = object : BusinessCardRepository {
        val card = BusinessCardRecord("required", "Test Person", memo = "queryword")
        override suspend fun loadAll() = listOf(card)
        override suspend fun getById(cardId: String) = card.takeIf { it.id == cardId }
    }

    @Test fun productionCompositionStopsForUnavailableModelAndFailedInference() = runBlocking {
        for (failure in listOf("missing", "document", "query")) {
            val engine = object : EmbeddingEngine {
                override fun name() = "controlled-desktop-failure"
                override fun isModelBacked() = failure != "missing"
                override fun embed(input: String) = embedQuery(input)
                override fun embedQuery(input: String): FloatArray {
                    if (failure == "query") error("injected query failure")
                    return FloatArray(768).also { it[0] = 1f }
                }
                override fun embedDocument(input: String): FloatArray {
                    if (failure == "document") error("injected document failure")
                    return FloatArray(768).also { it[0] = 1f }
                }
            }
            val backend = requiredDesktopSearchBackend(repository, engine)
            val error = runCatching { backend.search("queryword", 5) }.exceptionOrNull()
            assertNotNull("Production composition must stop on $failure", error)
            assertTrue(error is IllegalStateException)
        }
    }
}
