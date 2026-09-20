package com.example.hjp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.hjp.data.BusinessCardEntity
import com.example.hjp.data.HjpDatabase
import com.example.hjp.data.RoomBusinessCardRepository
import com.hjp.agent.contract.ModelToolCall
import com.hjp.agent.core.DefaultToolExecutor
import com.hjp.agent.core.DefaultToolRegistry
import com.hjp.agent.core.ToolImplementationCandidate
import com.hjp.searchlookup.EmbeddingEngine
import com.hjp.tool.contact.BusinessCardRecord
import com.hjp.tool.contact.ContactToolContracts
import com.hjp.tool.contact.RyeongContactSearchBackend
import com.hjp.tool.contact.UpdateBusinessCardPlugin
import com.hjp.tool.contract.CatalogContext
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolExecutionResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardMutationAtomicityInstrumentedTest {
    @Test
    fun generationFailureRollsBackRowFtsAndVectorThenRetryRecovers() = runBlocking {
        withFixture("generation") { fixture ->
            fixture.initialize()
            val beforeHash = fixture.embeddingHash()
            fixture.engine.failDocument = true

            val failed = fixture.update("aftergenerationtoken", "generation-fail")
            assertTrue(failed is ToolExecutionResult.Failure)
            fixture.assertOldState(beforeHash)

            fixture.engine.failDocument = false
            val retried = fixture.update("aftergenerationtoken", "generation-retry")
            assertTrue(retried is ToolExecutionResult.Success)
            fixture.assertNewState(beforeHash, "aftergenerationtoken")
        }
    }

    @Test
    fun roomEmbeddingWriteFailureRollsBackEverythingThenRetryRecovers() = runBlocking {
        withFixture("storage") { fixture ->
            fixture.initialize()
            val beforeHash = fixture.embeddingHash()
            fixture.database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_atomic_embedding BEFORE INSERT ON card_embeddings " +
                    "WHEN NEW.card_id = '${CARD_ID}' AND NEW.model_name = '${ENGINE_NAME}' " +
                    "BEGIN SELECT RAISE(ABORT, 'injected embedding storage failure'); END",
            )

            val failed = fixture.update("afterstoragetoken", "storage-fail")
            assertTrue(failed is ToolExecutionResult.Failure)
            fixture.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_atomic_embedding")
            fixture.assertOldState(beforeHash)

            val retried = fixture.update("afterstoragetoken", "storage-retry")
            assertTrue(retried is ToolExecutionResult.Success)
            fixture.assertNewState(beforeHash, "afterstoragetoken")
        }
    }

    @Test
    fun ocrInsertRefreshFailureRemovesRowFtsAndVectorThenRetryRecovers() = runBlocking {
        withFixture("ocr-add") { fixture ->
            fixture.initialize()
            val directory = CardDirectory(fixture.repository, fixture.backend, fixture.backend::refreshAfterCardChange)
            val added = BusinessCardRecord(
                id = "S901",
                name = "합성인물",
                company = "합성회복회사",
                email = "synthetic901@example.invalid",
                memo = "ocraddtoken",
                updatedAt = "2026-09-19T00:00:00Z",
            )
            fixture.engine.failDocument = true
            val failed = runCatching { directory.addCard(added) }
            assertTrue(failed.isFailure)
            assertNull(fixture.repository.getById(added.id))
            assertFalse(fixture.dao.searchFtsIds("ocraddtoken", 5).contains(added.id))
            assertFalse(fixture.repository.loadEmbeddings(ENGINE_NAME).any { it.cardId == added.id })

            fixture.engine.failDocument = false
            directory.addCard(added)
            assertNotNull(fixture.repository.getById(added.id))
            assertTrue(fixture.dao.searchFtsIds("ocraddtoken", 5).contains(added.id))
            assertTrue(fixture.repository.loadEmbeddings(ENGINE_NAME).any { it.cardId == added.id })
            val response = fixture.backend.search("합성회복회사", 5)
            assertEquals(added.id, response.hits.first().card.id)
            assertEquals("HYBRID", response.mode)
            assertFalse(response.fallbackUsed)
        }
    }

    private suspend fun <T> withFixture(label: String, block: suspend (Fixture) -> T): T {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "atomic-$label-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        val database = Room.databaseBuilder(context, HjpDatabase::class.java, name).build()
        try {
            val dao = database.businessCardDao()
            dao.insertAllAndReindex(listOf(seedEntity()))
            return block(Fixture(database, RoomBusinessCardRepository(context, dao), ToggleEngine()))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private class Fixture(
        val database: HjpDatabase,
        val repository: RoomBusinessCardRepository,
        val engine: ToggleEngine,
    ) {
        val dao = database.businessCardDao()
        val backend = RyeongContactSearchBackend(
            repository,
            embeddingEngineFactory = { engine },
            requireModelBacked = true,
        )
        private val plugin = UpdateBusinessCardPlugin(
            repository,
            clockMillis = { 1_758_240_000_000L },
            onUpdated = { backend.refreshAfterCardChange() },
        )
        private val registry = DefaultToolRegistry(listOf(ToolImplementationCandidate(plugin)))
        private val executor = DefaultToolExecutor(registry)

        suspend fun initialize() {
            val response = backend.search("beforeatomictoken", 5)
            assertEquals(CARD_ID, response.hits.first().card.id)
            assertEquals("HYBRID", response.mode)
            assertFalse(response.fallbackUsed)
            assertNotNull(embeddingHash())
        }

        suspend fun embeddingHash(): String = repository.loadEmbeddings(ENGINE_NAME)
            .single { it.cardId == CARD_ID }.sourceTextHash

        suspend fun update(memo: String, callId: String): ToolExecutionResult = executor.execute(
            ModelToolCall(callId, "update_business_card", buildJsonObject {
                put("card_id", CARD_ID)
                put("updates", buildJsonObject { put("memo", memo) })
            }),
            registry.snapshot(CatalogContext("atomic", "ko-KR")),
            ToolExecutionContext("atomic", callId, "ko-KR", "Asia/Seoul"),
        )

        suspend fun assertOldState(beforeHash: String) {
            assertEquals("beforeatomictoken", repository.getById(CARD_ID)?.memo)
            assertTrue(dao.searchFtsIds("beforeatomictoken", 5).contains(CARD_ID))
            assertFalse(dao.searchFtsIds("after", 5).contains(CARD_ID))
            assertEquals(beforeHash, embeddingHash())
            engine.failDocument = false
            val followUp = backend.search("beforeatomictoken", 5)
            assertEquals(CARD_ID, followUp.hits.first().card.id)
            assertEquals("HYBRID", followUp.mode)
            assertFalse(followUp.fallbackUsed)
        }

        suspend fun assertNewState(beforeHash: String, memo: String) {
            assertEquals(memo, repository.getById(CARD_ID)?.memo)
            assertFalse(dao.searchFtsIds("beforeatomictoken", 5).contains(CARD_ID))
            assertTrue(dao.searchFtsIds(memo, 5).contains(CARD_ID))
            assertNotEquals(beforeHash, embeddingHash())
            val followUp = backend.search(memo, 5)
            assertEquals(CARD_ID, followUp.hits.first().card.id)
            assertEquals("HYBRID", followUp.mode)
            assertFalse(followUp.fallbackUsed)
        }
    }

    private class ToggleEngine : EmbeddingEngine {
        var failDocument = false
        override fun embed(input: String): FloatArray = embedQuery(input)
        override fun embedQuery(input: String): FloatArray = vector(input)
        override fun embedDocument(input: String): FloatArray {
            check(!failDocument) { "injected document generation failure" }
            return vector(input)
        }
        override fun name(): String = ENGINE_NAME
        override fun isModelBacked(): Boolean = true
        override fun diagnosticStatus(): String = "ready"

        private fun vector(input: String): FloatArray = FloatArray(768).also { values ->
            check(input.isNotBlank())
            values[0] = 1f
        }
    }

    private fun seedEntity() = BusinessCardEntity(
        id = CARD_ID,
        name = "원자성합성",
        nameEn = "Atomic Synthetic",
        company = "격리회사",
        title = "검증자",
        department = "테스트팀",
        industry = "검증",
        location = "서울",
        phone = "",
        mobile = "",
        email = "atomic@example.invalid",
        address = "서울특별시 테스트구",
        website = "",
        memo = "beforeatomictoken",
        tagsJson = "[]",
        updatedAt = "2026-09-19T00:00:00Z",
    )

    private companion object {
        const val CARD_ID = "ATOMIC-001"
        const val ENGINE_NAME = "atomic-test-model"
    }
}
