package com.example.hjp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.hjp.data.HjpDatabase
import com.example.hjp.data.RoomBusinessCardRepository
import com.hjp.searchlookup.EmbeddingInput
import com.hjp.searchlookup.EmbeddingUpdater
import com.hjp.searchlookup.FloatVectorCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

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

            val modelName = "google/embeddinggemma-300m-ai-edge-rag#" +
                "m=37115ef7bff76cd3;t=d6daa52d93d7aad1"
            val embeddings = repository.loadEmbeddings(modelName)
            assertEquals(1_000, embeddings.size)
            assertEquals(1_000, repository.loadEmbeddings(modelName).size)

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
}
