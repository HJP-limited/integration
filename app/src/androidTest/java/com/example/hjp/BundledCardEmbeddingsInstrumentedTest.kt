package com.example.hjp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.hjp.data.CardEmbeddingEntity
import com.example.hjp.data.HjpDatabase
import com.example.hjp.data.RoomBusinessCardRepository
import com.example.hjp.data.toBusinessCardEntity
import com.hjp.searchlookup.EmbeddingInput
import com.hjp.searchlookup.EmbeddingEngine
import com.hjp.searchlookup.EmbeddingUpdater
import com.hjp.searchlookup.FloatVectorCodec
import com.hjp.searchlookup.OnDeviceEmbeddingEngine
import com.hjp.tool.contact.BusinessCardRecord
import com.hjp.tool.contact.ContactToolContracts
import com.hjp.tool.contact.RyeongContactSearchBackend
import com.hjp.tool.contact.UpdateBusinessCardPlugin
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolExecutionResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** 사전계산한 1000장 문서 벡터가 자산 검증 후 Room에 들어가는지 확인한다. */
@RunWith(AndroidJUnit4::class)
class BundledCardEmbeddingsInstrumentedTest {
    @Test
    fun validatedBundleSeedsCurrentModelCacheWithoutLiveDocumentInference() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "hjp-bundled-embeddings-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, HjpDatabase::class.java, databaseName).build()
        try {
            val repository = RoomBusinessCardRepository(context, database.businessCardDao())
            val cards = repository.loadAll()
            assertEquals(1_000, cards.size)

            // loadAll() itself establishes the APK's baseline index. Search initialization is not
            // responsible for making the first 1,000 cards usable.
            assertEquals(1_000, database.businessCardDao().countEmbeddings(MODEL_NAME))
            val embeddings = repository.loadEmbeddings(MODEL_NAME)
            assertEquals(1_000, embeddings.size)
            assertEquals(1_000, repository.loadEmbeddings(MODEL_NAME).size)

            val card = cards.first { it.id == "T001" }
            val expectedInput = EmbeddingInput.forCard(
                card.name, card.nameEn, card.company, card.title, card.department,
                card.industry, card.location, card.memo, card.tags,
            )
            val stored = embeddings.first { it.cardId == card.id }
            assertEquals(768, stored.dimension)
            assertEquals(768 * Float.SIZE_BYTES, stored.vectorBlob.size)
            assertEquals(EmbeddingUpdater.sha256(expectedInput), stored.sourceTextHash)
            val vector = FloatVectorCodec.fromBlob(stored.vectorBlob)
            assertTrue(vector.all(Float::isFinite))
            assertTrue(vector.any { it != 0f })
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun legacyInputCacheMigratesFromBundleWithoutLiveDocumentInference() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "hjp-legacy-embedding-migration-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, HjpDatabase::class.java, databaseName).build()
        try {
            val initialRepository = RoomBusinessCardRepository(context, database.businessCardDao())
            val cards = initialRepository.loadAll()
            installLegacyCache(database, cards)

            // A new repository instance represents the first process after upgrading from the
            // unversioned embedding-input contract.
            val upgradedRepository = RoomBusinessCardRepository(context, database.businessCardDao())
            val engine = CountingDocumentEngine()
            val backend = RyeongContactSearchBackend(
                upgradedRepository,
                embeddingEngineFactory = { OnDeviceEmbeddingEngine.production(engine) },
            )
            val response = backend.search("AI 개발자", 5)

            assertTrue(response.hits.isNotEmpty())
            assertEquals(0, engine.documentCalls)
            val migrated = upgradedRepository.loadEmbeddings(MODEL_NAME)
            assertEquals(1_000, migrated.size)
            val cardsById = cards.associateBy(BusinessCardRecord::id)
            migrated.forEach { stored ->
                val card = cardsById.getValue(stored.cardId)
                assertEquals(canonicalHash(card), stored.sourceTextHash)
            }
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun modifiedRoomCardIsPreservedReembeddedOnceAndReusedAfterRestart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "hjp-modified-embedding-migration-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, HjpDatabase::class.java, databaseName).build()
        try {
            val initialRepository = RoomBusinessCardRepository(context, database.businessCardDao())
            val cards = initialRepository.loadAll()
            installLegacyCache(database, cards)
            val original = cards.first { it.id == "T001" }
            val modified = original.copy(
                memo = "사용자가 보존해야 하는 수정 메모",
                updatedAt = "2026-09-20T00:00:00Z",
            )
            database.businessCardDao().updateAndReindex(modified.toBusinessCardEntity(Json))

            val upgradedRepository = RoomBusinessCardRepository(context, database.businessCardDao())
            val firstEngine = CountingDocumentEngine()
            val firstBackend = RyeongContactSearchBackend(
                upgradedRepository,
                embeddingEngineFactory = { OnDeviceEmbeddingEngine.production(firstEngine) },
            )
            assertTrue(firstBackend.search("AI 개발자", 5).hits.isNotEmpty())
            assertEquals(1, firstEngine.documentCalls)
            assertEquals(modified, upgradedRepository.getById(modified.id))
            val refreshed = upgradedRepository.loadEmbeddings(MODEL_NAME)
            assertEquals(1_000, refreshed.size)
            assertEquals(canonicalHash(modified), refreshed.first { it.cardId == modified.id }.sourceTextHash)

            // A fresh repository/backend is the cache contract across process restart.
            val restartedRepository = RoomBusinessCardRepository(context, database.businessCardDao())
            val restartedEngine = CountingDocumentEngine()
            val restartedBackend = RyeongContactSearchBackend(
                restartedRepository,
                embeddingEngineFactory = { OnDeviceEmbeddingEngine.production(restartedEngine) },
            )
            assertTrue(restartedBackend.search("수정 메모", 5).hits.isNotEmpty())
            assertEquals(0, restartedEngine.documentCalls)
            assertEquals(modified, restartedRepository.getById(modified.id))
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun addingCardImmediatelyAppendsItsVectorToRoom() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "hjp-live-card-embedding-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, HjpDatabase::class.java, databaseName).build()
        try {
            val repository = RoomBusinessCardRepository(context, database.businessCardDao())
            val engine = CountingDocumentEngine()
            val backend = RyeongContactSearchBackend(
                repository,
                embeddingEngineFactory = { OnDeviceEmbeddingEngine.production(engine) },
            )
            val directory = CardDirectory(repository, backend, backend::refreshAfterCardChange)

            // Initialize from the 1,000 vectors bundled in the APK. Their hashes match this model.
            backend.search("AI", 5)
            assertEquals(0, engine.documentCalls)

            val added = BusinessCardRecord(
                id = "S999",
                name = "Live Added Person",
                company = "Live Vector Company",
                memo = "newly embedded card",
                updatedAt = "9999999999999",
            )
            directory.addCard(added)

            val stored = repository.loadEmbeddings(engine.name())
            assertEquals(1_001, stored.size)
            assertEquals(1, engine.documentCalls)
            assertTrue(stored.any { it.cardId == added.id })
            assertEquals(added.id, backend.get(added.id)?.id)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun editingCardImmediatelyReplacesItsPersistedVector() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "hjp-live-card-reembedding-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, HjpDatabase::class.java, databaseName).build()
        try {
            val repository = RoomBusinessCardRepository(context, database.businessCardDao())
            val engine = CountingDocumentEngine()
            val backend = RyeongContactSearchBackend(
                repository,
                embeddingEngineFactory = { OnDeviceEmbeddingEngine.production(engine) },
            )
            backend.search("AI", 5)
            assertEquals(0, engine.documentCalls)

            val before = repository.loadEmbeddings(engine.name()).first { it.cardId == "T001" }
            val result = UpdateBusinessCardPlugin(
                repository = repository,
                clockMillis = { 1_700_000_000_000L },
                onUpdated = { backend.refreshAfterCardChange() },
            ).execute(
                com.hjp.tool.contract.ToolRequest(
                    callId = "update-and-reembed",
                    capabilityId = ContactToolContracts.Update.capabilityId,
                    contractVersion = ContactToolContracts.Update.version,
                    arguments = buildJsonObject {
                        put("card_id", "T001")
                        put("updates", buildJsonObject { put("memo", "즉시 재임베딩 검증") })
                    },
                ),
                ToolExecutionContext("session", "turn", "ko-KR", "Asia/Seoul"),
            )

            assertTrue(result is ToolExecutionResult.Success)
            assertEquals(1, engine.documentCalls)
            val stored = repository.loadEmbeddings(engine.name())
            assertEquals(1_000, stored.size)
            val after = stored.first { it.cardId == "T001" }
            assertTrue(before.sourceTextHash != after.sourceTextHash)
            val updatedCard = requireNotNull(repository.getById("T001"))
            assertEquals(
                EmbeddingUpdater.sha256(
                    EmbeddingInput.forCard(
                        updatedCard.name, updatedCard.nameEn, updatedCard.company, updatedCard.title,
                        updatedCard.department, updatedCard.industry, updatedCard.location,
                        updatedCard.memo, updatedCard.tags,
                    ),
                ),
                after.sourceTextHash,
            )
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    private class CountingDocumentEngine : EmbeddingEngine {
        var documentCalls = 0

        override fun embed(input: String): FloatArray = embedQuery(input)
        override fun embedQuery(input: String): FloatArray = vector(input)
        override fun embedDocument(input: String): FloatArray {
            documentCalls += 1
            return vector(input)
        }
        override fun name(): String = MODEL_NAME
        override fun isModelBacked(): Boolean = true

        private fun vector(input: String) = FloatArray(768).also {
            it[(input.hashCode() and Int.MAX_VALUE) % it.size] = 1f
        }
    }

    private suspend fun installLegacyCache(
        database: HjpDatabase,
        cards: List<BusinessCardRecord>,
    ) {
        val dao = database.businessCardDao()
        val cardsById = cards.associateBy(BusinessCardRecord::id)
        val legacy = dao.loadEmbeddings(MODEL_NAME).map { current ->
            val card = cardsById.getValue(current.cardId)
            CardEmbeddingEntity(
                cardId = current.cardId,
                modelName = LEGACY_MODEL_NAME,
                dimension = current.dimension,
                vectorBlob = current.vectorBlob,
                sourceTextHash = EmbeddingUpdater.sha256(legacyEmbeddingInput(card)),
                createdAtMillis = current.createdAtMillis,
                updatedAtMillis = current.updatedAtMillis,
            )
        }
        dao.upsertEmbeddingsAtomically(legacy)
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM card_embeddings WHERE model_name = ?",
            arrayOf(MODEL_NAME),
        )
        assertEquals(0, dao.countEmbeddings(MODEL_NAME))
        assertEquals(1_000, dao.countEmbeddings(LEGACY_MODEL_NAME))
    }

    private fun canonicalHash(card: BusinessCardRecord): String = EmbeddingUpdater.sha256(
        EmbeddingInput.forCard(
            card.name, card.nameEn, card.company, card.title, card.department,
            card.industry, card.location, card.memo, card.tags,
        ),
    )

    private fun legacyEmbeddingInput(card: BusinessCardRecord): String = (
        card.name + " " + card.nameEn + " " + card.company + " " + card.title + " " +
            card.department + " " + card.industry + " " + card.location + " " + card.memo +
            " " + card.tags.joinToString(" ") { "#$it" }
        ).lowercase(Locale.KOREAN)

    private companion object {
        const val LEGACY_MODEL_NAME = "google/embeddinggemma-300m-ai-edge-rag#" +
            "m=37115ef7bff76cd3;t=d6daa52d93d7aad1"
        const val MODEL_NAME = "$LEGACY_MODEL_NAME;i=canonical-v1"
    }
}
