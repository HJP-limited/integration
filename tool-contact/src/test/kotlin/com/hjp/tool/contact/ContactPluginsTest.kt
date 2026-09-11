package com.hjp.tool.contact

import com.hjp.searchlookup.OnDeviceEmbeddingEngine
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolExecutionResult
import com.hjp.tool.contract.ToolRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactPluginsTest {
    @Test
    fun `search result omits phone and email while get returns detail`() = runBlocking {
        val card = BusinessCardRecord("C001", "김지원", company = "비전글로벌", title = "대표",
            industry = "finance", location = "서울", phone = "010-0000", email = "test@example.com", tags = listOf("투자"))
        val repository = object : BusinessCardRepository {
            override suspend fun loadAll() = listOf(card)
            override suspend fun getById(cardId: String) = card.takeIf { it.id == cardId }
        }
        val backend = RyeongContactSearchBackend(repository)
        val context = ToolExecutionContext("s", "t", "ko-KR", "Asia/Seoul")
        val search = SearchContactsPlugin(backend).execute(
            ToolRequest("1", ContactToolContracts.Search.capabilityId, ContactToolContracts.Search.version,
                buildJsonObject { put("query", "투자 대표") }), context,
        ) as ToolExecutionResult.Success

        assertFalse(search.data.toString().contains("010-0000"))
        assertFalse(search.data.toString().contains("test@example.com"))
        assertFalse(search.data.toString().contains("\"location\""))
        assertTrue(search.data.toString().contains("\"mode\":\"KEYWORD_ONLY\""))
        assertTrue(search.data.toString().contains("\"fallback_used\":true"))
        assertTrue(search.data.toString().contains("\"match_summary\""))
        assertTrue(search.sessionUpdates.single().value.toString().contains("\"focus_card_id\":\"C001\""))

        val get = GetContactPlugin(backend).execute(
            ToolRequest("2", ContactToolContracts.Get.capabilityId, ContactToolContracts.Get.version,
                buildJsonObject { put("card_id", "C001") }), context,
        ) as ToolExecutionResult.Success
        assertTrue(get.data.toString().contains("test@example.com"))
    }

    @Test
    fun `update business card mutates repository and returns before and after`() = runBlocking {
        var card = BusinessCardRecord("C001", "김지원", company = "비전글로벌", memo = "old")
        val repository = object : MutableBusinessCardRepository {
            override suspend fun loadAll() = listOf(card)
            override suspend fun getById(cardId: String) = card.takeIf { it.id == cardId }
            override suspend fun update(
                cardId: String,
                updates: Map<String, String>,
                clearFields: Set<String>,
                updatedAt: String,
            ): BusinessCardUpdateResult? {
                val before = card.takeIf { it.id == cardId } ?: return null
                val after = before.copy(
                    memo = updates["memo"] ?: before.memo,
                    phone = if ("phone" in clearFields) "" else before.phone,
                    updatedAt = updatedAt,
                )
                card = after
                return BusinessCardUpdateResult(before, after)
            }
        }

        val result = UpdateBusinessCardPlugin(repository, clockMillis = { 0L }).execute(
            ToolRequest(
                "3",
                ContactToolContracts.Update.capabilityId,
                ContactToolContracts.Update.version,
                buildJsonObject {
                    put("card_id", "C001")
                    put("updates", buildJsonObject { put("memo", "VIP") })
                    put("clear_fields", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("phone"))
                    })
                },
            ),
            ToolExecutionContext("s", "t", "ko-KR", "Asia/Seoul"),
        ) as ToolExecutionResult.Success

        assertTrue(result.data.toString().contains("\"before\""))
        assertTrue(result.data.toString().contains("\"after\""))
        assertEquals("VIP", card.memo)
        assertEquals("", card.phone)
        assertEquals("1970-01-01T00:00:00Z", card.updatedAt)
    }

    @Test
    fun `search then get keeps exact repository card id and minimal search payload`() = runBlocking {
        val cards = listOf(
            BusinessCardRecord(
                "room-42",
                "김지원",
                company = "비전글로벌",
                title = "대표",
                email = "private@example.com",
                phone = "010-1111-2222",
                memo = "투자 파트너십 미팅",
            ),
            BusinessCardRecord("room-99", "김지원", company = "동명이인회사"),
        )
        val repository = fixtureRepository(cards)
        val backend = RyeongContactSearchBackend(repository)
        val context = ToolExecutionContext("session", "turn", "ko-KR", "Asia/Seoul")
        val search = SearchContactsPlugin(backend).execute(
            ToolRequest(
                "search",
                ContactToolContracts.Search.capabilityId,
                ContactToolContracts.Search.version,
                buildJsonObject { put("query", "비전글로벌 투자") },
            ),
            context,
        ) as ToolExecutionResult.Success

        assertTrue(search.data.toString().contains("\"card_id\":\"room-42\""))
        assertFalse(search.data.toString().contains("private@example.com"))
        assertFalse(search.data.toString().contains("010-1111-2222"))
        assertFalse(search.data.toString().contains("투자 파트너십 미팅"))

        val get = GetContactPlugin(backend).execute(
            ToolRequest(
                "get",
                ContactToolContracts.Get.capabilityId,
                ContactToolContracts.Get.version,
                buildJsonObject { put("card_id", "room-42"); put("purpose", "email") },
            ),
            context,
        ) as ToolExecutionResult.Success
        assertTrue(get.data.toString().contains("private@example.com"))
    }

    @Test
    fun `duplicate names remain multiple results for workflow disambiguation`() = runBlocking {
        val backend = RyeongContactSearchBackend(fixtureRepository(listOf(
            BusinessCardRecord("room-1", "김지원", company = "A사"),
            BusinessCardRecord("room-2", "김지원", company = "B사"),
        )))

        val response = backend.search("김지원", 5)

        assertEquals(2, response.hits.size)
        assertEquals(setOf("room-1", "room-2"), response.hits.map { it.card.id }.toSet())
        assertTrue(response.fallbackUsed)
    }

    @Test
    fun `model embeddings persist through repository adapter and hybrid uses rrf`() = runBlocking {
        val repository = EmbeddingFixtureRepository(listOf(
            BusinessCardRecord("room-ai", "오성령", company = "코어AI", memo = "머신러닝"),
            BusinessCardRecord("room-fin", "김지원", company = "비전글로벌", memo = "투자"),
        ))
        val backend = RyeongContactSearchBackend(
            repository,
            embeddingEngineFactory = {
                com.hjp.searchlookup.OnDeviceEmbeddingEngine.production(TestEmbeddingGemma())
            },
        )

        val response = backend.search("인공지능 전문가", 5)

        assertEquals("HYBRID", response.mode)
        assertFalse(response.fallbackUsed)
        assertEquals("room-ai", response.hits.first().card.id)
        assertTrue(response.hits.first().retrievalSources.contains("semantic"))
        assertEquals(2, repository.embeddings.size)
        assertTrue(repository.embeddings.all { it.dimension == 768 })
    }

    @Test
    fun `embedding inference failure returns keyword result with fallback metadata`() = runBlocking {
        val repository = fixtureRepository(listOf(
            BusinessCardRecord("room-1", "김지원", company = "비전글로벌"),
        ))
        val backend = RyeongContactSearchBackend(
            repository,
            embeddingEngineFactory = {
                com.hjp.searchlookup.OnDeviceEmbeddingEngine.production(
                    object : TestEmbeddingGemma() {
                        override fun embedDocument(input: String): FloatArray =
                            error("device inference failed")
                    },
                )
            },
        )

        val response = backend.search("김지원", 5)

        assertEquals("KEYWORD_ONLY", response.mode)
        assertTrue(response.fallbackUsed)
        assertTrue(response.fallbackReason.contains("INFERENCE_FAILED"))
        assertEquals("room-1", response.hits.single().card.id)
    }

    @Test
    fun `matching model and source hashes reuse vectors while a model change reindexes`() = runBlocking {
        val repository = EmbeddingFixtureRepository(listOf(
            BusinessCardRecord("room-ai", "오성령", company = "코어AI", memo = "머신러닝"),
            BusinessCardRecord("room-fin", "김지원", company = "비전글로벌", memo = "투자"),
        ))
        val firstEngine = CountingEmbeddingGemma("model#hash-a")
        RyeongContactSearchBackend(
            repository,
            embeddingEngineFactory = { OnDeviceEmbeddingEngine.production(firstEngine) },
        ).search("인공지능", 5)
        assertEquals(2, firstEngine.documentCalls)

        val reusedEngine = CountingEmbeddingGemma("model#hash-a")
        RyeongContactSearchBackend(
            repository,
            embeddingEngineFactory = { OnDeviceEmbeddingEngine.production(reusedEngine) },
        ).search("인공지능", 5)
        assertEquals(0, reusedEngine.documentCalls)

        val changedEngine = CountingEmbeddingGemma("model#hash-b")
        RyeongContactSearchBackend(
            repository,
            embeddingEngineFactory = { OnDeviceEmbeddingEngine.production(changedEngine) },
        ).search("인공지능", 5)
        assertEquals(2, changedEngine.documentCalls)
    }

    @Test
    fun `get rejects a card id that no longer resolves in room repository`() = runBlocking {
        val card = BusinessCardRecord("room-stale", "김지원")
        var available = true
        val repository = object : BusinessCardRepository {
            override suspend fun loadAll() = listOf(card)
            override suspend fun getById(cardId: String) =
                card.takeIf { available && it.id == cardId }
        }
        val backend = RyeongContactSearchBackend(repository)
        backend.search("김지원", 5)
        available = false

        val result = GetContactPlugin(backend).execute(
            ToolRequest(
                "stale",
                ContactToolContracts.Get.capabilityId,
                ContactToolContracts.Get.version,
                buildJsonObject { put("card_id", "room-stale") },
            ),
            ToolExecutionContext("s", "t", "ko-KR", "Asia/Seoul"),
        )

        assertTrue(result is ToolExecutionResult.Failure)
    }

    private fun fixtureRepository(cards: List<BusinessCardRecord>) =
        object : BusinessCardRepository {
            override suspend fun loadAll() = cards
            override suspend fun getById(cardId: String) = cards.firstOrNull { it.id == cardId }
        }

    private class EmbeddingFixtureRepository(
        private val cards: List<BusinessCardRecord>,
    ) : BusinessCardRepository, BusinessCardEmbeddingStore {
        val embeddings = mutableListOf<StoredCardEmbedding>()
        override suspend fun loadAll() = cards
        override suspend fun getById(cardId: String) = cards.firstOrNull { it.id == cardId }
        override suspend fun loadEmbeddings(modelName: String) =
            embeddings.filter { it.modelName == modelName }
        override suspend fun upsertEmbeddings(embeddings: List<StoredCardEmbedding>) {
            embeddings.forEach { candidate ->
                this.embeddings.removeAll {
                    it.cardId == candidate.cardId && it.modelName == candidate.modelName
                }
                this.embeddings += candidate
            }
        }
    }

    private open class TestEmbeddingGemma : com.hjp.searchlookup.EmbeddingEngine {
        override fun embed(input: String): FloatArray = embedQuery(input)
        override fun embedQuery(input: String): FloatArray = vector(input)
        override fun embedDocument(input: String): FloatArray = vector(input)
        override fun name() = "test-embeddinggemma"
        override fun isModelBacked() = true

        private fun vector(input: String): FloatArray {
            val result = FloatArray(768)
            when {
                input.contains("AI", ignoreCase = true) ||
                    input.contains("인공지능") ||
                    input.contains("머신러닝") -> result[0] = 1f
                input.contains("투자") -> result[1] = 1f
                else -> result[767] = 1f
            }
            return result
        }
    }

    private class CountingEmbeddingGemma(
        private val modelName: String,
    ) : com.hjp.searchlookup.EmbeddingEngine {
        var documentCalls = 0
        override fun embed(input: String): FloatArray = embedQuery(input)
        override fun embedQuery(input: String): FloatArray = vector(input)
        override fun embedDocument(input: String): FloatArray {
            documentCalls += 1
            return vector(input)
        }
        override fun name() = modelName
        override fun isModelBacked() = true

        private fun vector(input: String): FloatArray = FloatArray(768).also {
            it[if (input.contains("AI", true) || input.contains("머신러닝") || input.contains("인공지능")) 0 else 1] = 1f
        }
    }
}
