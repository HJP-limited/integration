package com.example.hjp

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.hjp.data.HjpDatabase
import com.example.hjp.data.RoomBusinessCardRepository
import com.example.hjp.search.AndroidEmbeddingGemmaEngine
import com.hjp.searchlookup.EmbeddingInput
import com.hjp.searchlookup.EmbeddingUpdater
import com.hjp.searchlookup.OnDeviceEmbeddingEngine
import com.hjp.tool.contact.BusinessCardRecord
import com.hjp.tool.contact.ContactToolContracts
import com.hjp.tool.contact.RyeongContactSearchBackend
import com.hjp.tool.contact.UpdateBusinessCardPlugin
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolExecutionResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 실기기에서 번들 문서 벡터 + 라이브 쿼리 벡터의 하이브리드 검색을 검증한다. */
@RunWith(AndroidJUnit4::class)
class EmbeddingGemmaArm64InstrumentedTest {
    @Test
    fun bundledDocumentsAndLiveQueryProduceSemanticHit() = runBlocking {
        assertTrue("arm64 실기기가 아니면 의미검색 검증을 통과시킬 수 없습니다",
            Build.SUPPORTED_ABIS.any { it.startsWith("arm64") })
        assertFalse("에뮬레이터에서는 실제 의미검색 검증을 통과시킬 수 없습니다",
            AppContainer.isAndroidEmulator())

        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "hjp-arm64-semantic-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, HjpDatabase::class.java, databaseName).build()
        val engine = AndroidEmbeddingGemmaEngine(context)
        try {
            assertTrue(engine.diagnosticStatus(), engine.isModelBacked)
            val queryVector = engine.embedQuery("인공지능 머신러닝 모델학습 전문가")
            assertEquals(768, queryVector.size)
            assertTrue(queryVector.all(Float::isFinite) && queryVector.any { it != 0f })

            val repository = RoomBusinessCardRepository(context, database.businessCardDao())
            assertEquals(1_000, repository.loadEmbeddings(engine.name()).size)
            val backend = RyeongContactSearchBackend(
                repository,
                embeddingEngineFactory = { OnDeviceEmbeddingEngine.production(engine) },
            )
            val response = backend.search("인공지능 머신러닝 모델학습 전문가", 5)
            assertEquals("HYBRID", response.mode)
            assertFalse(response.fallbackReason, response.fallbackUsed)
            val hit = response.hits.first { it.card.id == "T001" }
            assertTrue(hit.retrievalSources.any { it.contains("semantic") })
        } finally {
            engine.close()
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun actualEmbeddingGemmaIndexesAddedAndUpdatedSyntheticCard() = runBlocking {
        assertTrue(Build.SUPPORTED_ABIS.any { it.startsWith("arm64") })
        assertFalse(AppContainer.isAndroidEmulator())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "hjp-arm64-live-mutation-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, HjpDatabase::class.java, databaseName).build()
        val engine = AndroidEmbeddingGemmaEngine(context)
        try {
            assertTrue(engine.diagnosticStatus(), engine.isModelBacked)
            val repository = RoomBusinessCardRepository(context, database.businessCardDao())
            val backend = RyeongContactSearchBackend(
                repository,
                embeddingEngineFactory = { OnDeviceEmbeddingEngine.production(engine) },
            )
            backend.search("인공지능", 5)
            val directory = CardDirectory(repository, backend, backend::refreshAfterCardChange)
            val card = BusinessCardRecord(
                id = "SYN-E2E-001",
                name = "합성 검증인",
                company = "제피르 합성 연구소",
                memo = "온디바이스 벡터 신규 색인",
                updatedAt = "1758240000000",
            )
            directory.addCard(card)
            assertEquals(1_001, repository.loadEmbeddings(engine.name()).size)
            val before = repository.loadEmbeddings(engine.name()).single { it.cardId == card.id }
            assertEquals(EmbeddingUpdater.sha256(EmbeddingInput.forCard(
                card.name, card.nameEn, card.company, card.title, card.department,
                card.industry, card.location, card.memo, card.tags,
            )), before.sourceTextHash)
            val addedSearch = backend.search("제피르 합성 연구소", 5)
            assertEquals("HYBRID", addedSearch.mode)
            assertFalse(addedSearch.fallbackReason, addedSearch.fallbackUsed)
            assertEquals(card.id, addedSearch.hits.first().card.id)

            val update = UpdateBusinessCardPlugin(
                repository,
                clockMillis = { 1_758_240_001_000L },
                onUpdated = { backend.refreshAfterCardChange() },
            ).execute(
                com.hjp.tool.contract.ToolRequest(
                    "actual-embedding-update",
                    ContactToolContracts.Update.capabilityId,
                    ContactToolContracts.Update.version,
                    buildJsonObject {
                        put("card_id", card.id)
                        put("updates", buildJsonObject {
                            put("company", "노바 합성 연구소")
                            put("memo", "온디바이스 벡터 수정 색인")
                        })
                    },
                ),
                ToolExecutionContext("synthetic", "mutation", "ko-KR", "Asia/Seoul"),
            )
            assertTrue(update is ToolExecutionResult.Success)
            val after = repository.loadEmbeddings(engine.name()).single { it.cardId == card.id }
            assertTrue(before.sourceTextHash != after.sourceTextHash)
            val updated = requireNotNull(repository.getById(card.id))
            assertEquals("노바 합성 연구소", updated.company)
            val updatedSearch = backend.search("노바 합성 연구소", 5)
            assertEquals("HYBRID", updatedSearch.mode)
            assertFalse(updatedSearch.fallbackReason, updatedSearch.fallbackUsed)
            assertEquals(card.id, updatedSearch.hits.first().card.id)
        } finally {
            engine.close()
            database.close()
            context.deleteDatabase(databaseName)
        }
    }
}
