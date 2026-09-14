package com.example.hjp

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.hjp.data.HjpDatabase
import com.example.hjp.data.RoomBusinessCardRepository
import com.example.hjp.search.AndroidEmbeddingGemmaEngine
import com.hjp.searchlookup.OnDeviceEmbeddingEngine
import com.hjp.tool.contact.RyeongContactSearchBackend
import kotlinx.coroutines.runBlocking
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
}
