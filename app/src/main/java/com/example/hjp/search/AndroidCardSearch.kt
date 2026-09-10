package com.example.hjp.search

import android.content.Context
import android.os.Build
import com.example.hjp.data.BusinessCardDao
import com.example.hjp.data.BusinessCardEntity
import com.example.hjp.data.CardEmbeddingEntity
import com.example.hjp.data.HjpDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** [CardStore] 의 Room 구현. SQL 은 전부 [BusinessCardDao] 에 있다. */
class RoomCardStore(private val dao: BusinessCardDao) : CardStore {
    override fun countCards(): Int = dao.countCards()

    override fun allCards(): List<BusinessCardEntity> = dao.allCards()

    override fun findCard(id: String): BusinessCardEntity? = dao.findCard(id)

    override fun upsertCards(cards: List<BusinessCardEntity>) = dao.upsertCards(cards)

    override fun replaceAllCards(cards: List<BusinessCardEntity>) = dao.replaceAllCards(cards)

    override fun clearFts() = dao.clearFts()

    override fun rebuildFts() = dao.rebuildFts()

    override fun searchFtsIds(matchQuery: String, limit: Int): List<String> =
        dao.searchFtsIds(matchQuery, limit)

    override fun searchLikeIds(like: String, limit: Int): List<String> =
        dao.searchLikeIds(like, limit)

    override fun countEmbeddingsForModel(modelName: String): Int =
        dao.countEmbeddingsForModel(modelName)

    override fun findEmbedding(cardId: String, modelName: String, sourceHash: String): CardEmbeddingEntity? =
        dao.findEmbedding(cardId, modelName, sourceHash)

    override fun upsertEmbedding(embedding: CardEmbeddingEntity) = dao.upsertEmbedding(embedding)

    override fun embeddingsForModel(modelName: String): List<CardEmbeddingEntity> =
        dao.embeddingsForModel(modelName)
}

/** APK assets 에 번들된 시드 명함·사전 계산 임베딩. */
class AssetSeedSource(context: Context) : SeedSource {
    private val appContext = context.applicationContext

    override fun cardsSeedJson(): String? =
        readAsset("cards/cards_seed.json")?.toString(Charsets.UTF_8)

    override fun precomputedEmbeddings(): Pair<String, ByteArray>? {
        val ids = readAsset("cards/cards_embeddings_ids.json")?.toString(Charsets.UTF_8) ?: return null
        val vectors = readAsset("cards/cards_embeddings.bin") ?: return null
        return ids to vectors
    }

    private fun readAsset(path: String): ByteArray? =
        try {
            appContext.assets.open(path).use { it.readBytes() }
        } catch (_: Throwable) {
            null
        }
}

/**
 * 안드로이드용 [CardSearchService] 조립. 검색 규칙 자체는 :core 에 있고 여기서는
 * 저장소(Room)·임베더(EmbeddingGemma)·시드(assets)만 꽂는다.
 */
fun createCardSearchService(context: Context): CardSearchService {
    val appContext = context.applicationContext
    return CardSearchService(
        store = RoomCardStore(HjpDatabase.getInstance(appContext).businessCardDao()),
        embeddingProviderFactory = { GemmaEmbeddingProvider(appContext) },
        seedSource = AssetSeedSource(appContext),
        platformDiagnostics = {
            JSONObject()
                .put("device_abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                .put("internal_models_dir", File(appContext.filesDir, "models").absolutePath)
                .put("external_models_dir", appContext.getExternalFilesDir("models")?.absolutePath ?: "")
        },
    )
}
