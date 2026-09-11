package com.hjp.tool.contact

import com.hjp.searchlookup.BusinessCard
import com.hjp.searchlookup.BusinessCardRepository as RyeongBusinessCardRepository
import com.hjp.searchlookup.CardEmbedding
import com.hjp.searchlookup.EmbeddingEngine
import com.hjp.searchlookup.OnDeviceEmbeddingEngine
import com.hjp.searchlookup.RetrievalMode
import com.hjp.searchlookup.ScoreBreakdown
import com.hjp.searchlookup.SearchLookupService
import com.hjp.searchlookup.SearchPlanObserver
import com.hjp.searchlookup.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RyeongContactSearchBackend(
    private val repository: BusinessCardRepository,
    private val embeddingEngineFactory: () -> EmbeddingEngine = { OnDeviceEmbeddingEngine.production() },
    private val diagnostics: (SearchDiagnostics) -> Unit = {},
    /**
     * Told which constraints each search applied, after it applied them.
     *
     * Defaults to the no-op, so the shipping app passes nothing and behaves exactly as before. The
     * compatibility evaluator passes one because a slot metric computed by re-parsing the query
     * would be measuring the re-parser rather than the search.
     */
    private val searchPlanObserver: SearchPlanObserver = SearchPlanObserver.NONE,
) : ContactSearchBackend {
    private val initMutex = Mutex()
    @Volatile private var service: SearchLookupService? = null
    @Volatile private var embeddingModelBacked = false
    @Volatile private var embeddingFallbackReason = ""
    @Volatile private var initializationMillis = 0L
    @Volatile private var initializationFailed = false

    override suspend fun search(query: String, limit: Int): ContactSearchResponse = withContext(Dispatchers.Default) {
        val searchService = requireService()
        val mode = if (embeddingModelBacked) RetrievalMode.HYBRID else RetrievalMode.KEYWORD_ONLY
        val indexedKeyword = (repository as? BusinessCardKeywordIndex)
            ?.searchKeywordCandidates(query, maxOf(limit * 8, 40))
            ?.mapNotNull { candidate ->
                val card = searchService.getCard(candidate.cardId) ?: return@mapNotNull null
                SearchResult(
                    card,
                    1.0 / candidate.rank,
                    ScoreBreakdown.keywordOnly(1.0 / candidate.rank),
                    listOf(candidate.tier),
                ).withRank(candidate.rank)
            }
        val response = searchService.retrieve(query, limit, mode, indexedKeyword, searchPlanObserver)
        val fallbackUsed = response.fallbackUsed || !embeddingModelBacked
        val fallbackReason = response.fallbackReason.ifBlank { embeddingFallbackReason }
        val hits = response.results.map { result ->
            ContactSearchHit(
                card = result.card.toRecord(),
                score = result.score,
                matchSummary = matchSummary(response.queryAnalysis.tokens, result.card, result.retrievalSources),
                rank = result.rank,
                retrievalSources = result.retrievalSources,
            )
        }
        diagnostics(SearchDiagnostics(
            query = response.query,
            mode = response.mode.name,
            fallbackUsed = fallbackUsed,
            fallbackReason = fallbackReason,
            engine = response.engineName,
            keywordResultCount = response.keywordResultCount,
            semanticResultCount = response.semanticResultCount,
            rankedCardIds = response.cardIds,
            rankings = response.results.map {
                RankedSearchDiagnostic(
                    cardId = it.cardId,
                    finalRank = it.rank,
                    keywordRank = it.keywordRank,
                    semanticRank = it.semanticRank,
                    score = it.score,
                    semanticSimilarity = it.similarity,
                    sources = it.retrievalSources,
                )
            },
            elapsedMillis = response.elapsedMillis,
            queryEmbeddingMillis = response.queryEmbeddingMillis,
            initializationMillis = initializationMillis,
        ))
        ContactSearchResponse(
            hits = hits,
            mode = response.mode.name,
            fallbackUsed = fallbackUsed,
            fallbackReason = fallbackReason,
            engine = response.engineName,
            elapsedMillis = response.elapsedMillis,
        )
    }

    override suspend fun get(cardId: String): BusinessCardRecord? = withContext(Dispatchers.Default) {
        val indexedCard = requireService().getCard(cardId) ?: return@withContext null
        repository.getById(indexedCard.id)
    }

    override fun engineName(): String =
        service?.engineName() ?: "ryeong-llm-integration-work@b543a18"
    override fun configurationAvailable(): Boolean = !initializationFailed

    fun invalidate() {
        service = null
        embeddingModelBacked = false
        embeddingFallbackReason = ""
        initializationMillis = 0L
        initializationFailed = false
    }

    private suspend fun requireService(): SearchLookupService {
        service?.let { return it }
        return initMutex.withLock {
            service?.let { return@withLock it }
            try {
                val cards = repository.loadAll()
                require(cards.map { it.id }.distinct().size == cards.size) {
                    "Business card IDs must be unique"
                }
                val initializationStarted = System.nanoTime()
                val embeddingEngine = embeddingEngineFactory()
                val embeddingStore = repository as? BusinessCardEmbeddingStore
                val cardIds = cards.mapTo(hashSetOf()) { it.id }
                val storedEmbeddings = embeddingStore
                    ?.loadEmbeddings(embeddingEngine.name())
                    .orEmpty()
                    .filter { stored ->
                        stored.cardId in cardIds &&
                            stored.dimension == OnDeviceEmbeddingEngine.DEFAULT_DIMENSION &&
                            stored.vectorBlob.size == stored.dimension * Float.SIZE_BYTES
                    }
                val snapshot = SnapshotRyeongRepository(
                    cards.map { it.toRyeongCard() },
                    storedEmbeddings.map { it.toRyeongEmbedding() },
                )
                val createdService = SearchLookupService(snapshot, embeddingEngine)
                initializationMillis =
                    (System.nanoTime() - initializationStarted) / 1_000_000L
                createdService.also {
                    embeddingModelBacked = embeddingEngine.isModelBacked
                    embeddingFallbackReason =
                        if (embeddingModelBacked) "" else embeddingEngine.diagnosticStatus()
                    service = it
                }
                if (embeddingModelBacked && embeddingStore != null) {
                    embeddingStore.upsertEmbeddings(
                        snapshot.allEmbeddings().map { it.toStoredEmbedding() },
                    )
                }
                createdService
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                initializationFailed = true
                throw error
            }
        }
    }

    private fun BusinessCardRecord.toRyeongCard() = BusinessCard(
        id,
        name,
        nameEn,
        company,
        title,
        department,
        industry,
        location,
        listOf(phone, mobile).filter(String::isNotBlank).joinToString(" "),
        email,
        address,
        memo,
        tags,
    )

    private fun BusinessCard.toRecord() = BusinessCardRecord(
        id = id,
        name = name,
        nameEn = nameEn,
        company = company,
        title = title,
        department = department,
        industry = industry,
        location = location,
        phone = phone,
        email = email,
        address = address,
        memo = memo,
        tags = tags,
    )

    private fun StoredCardEmbedding.toRyeongEmbedding() = CardEmbedding(
        cardId,
        modelName,
        dimension,
        vectorBlob,
        sourceTextHash,
        createdAtMillis,
        updatedAtMillis,
    )

    private fun CardEmbedding.toStoredEmbedding() = StoredCardEmbedding(
        cardId = cardId,
        modelName = modelName,
        dimension = dim,
        vectorBlob = vectorBlob,
        sourceTextHash = sourceTextHash,
        createdAtMillis = createdAt,
        updatedAtMillis = updatedAt,
    )

    private fun matchSummary(
        tokens: List<String>,
        card: BusinessCard,
        sources: List<String>,
    ): String {
        val fields = linkedSetOf<String>()
        val candidates = listOf(
            "이름" to listOf(card.name, card.nameEn),
            "회사" to listOf(card.company),
            "직책" to listOf(card.title),
            "부서" to listOf(card.department),
            "산업" to listOf(card.industry),
            "지역" to listOf(card.location),
            "메모" to listOf(card.memo),
            "태그" to card.tags,
        )
        candidates.forEach { (label, values) ->
            if (tokens.any { token ->
                    values.any { value -> value.contains(token, ignoreCase = true) }
                }) {
                fields += label
            }
        }
        if (sources.any { it.contains("semantic", ignoreCase = true) }) fields += "의미"
        return if (fields.isEmpty()) "검색어 관련 항목" else fields.joinToString("·") + " 일치"
    }
}

data class SearchDiagnostics(
    val query: String,
    val mode: String,
    val fallbackUsed: Boolean,
    val fallbackReason: String,
    val engine: String,
    val keywordResultCount: Int,
    val semanticResultCount: Int,
    val rankedCardIds: List<String>,
    val rankings: List<RankedSearchDiagnostic>,
    val elapsedMillis: Long,
    val queryEmbeddingMillis: Long,
    val initializationMillis: Long,
)

data class RankedSearchDiagnostic(
    val cardId: String,
    val finalRank: Int,
    val keywordRank: Int,
    val semanticRank: Int,
    val score: Double,
    val semanticSimilarity: Double,
    val sources: List<String>,
)

private class SnapshotRyeongRepository(
    cards: List<BusinessCard>,
    embeddings: List<CardEmbedding>,
) : RyeongBusinessCardRepository {
    private val cardsById = linkedMapOf<String, BusinessCard>()
    private val embeddingsByKey = linkedMapOf<String, CardEmbedding>()

    init {
        cards.forEach(::upsertCard)
        embeddings.forEach(::upsertEmbedding)
    }

    override fun getAllCards(): List<BusinessCard> = cardsById.values.toList()

    override fun getCard(cardId: String?): BusinessCard? = cardsById[cardId]

    override fun upsertCard(card: BusinessCard?) {
        if (card != null) cardsById[card.id] = card
    }

    override fun getEmbedding(cardId: String?, modelName: String?): CardEmbedding? =
        embeddingsByKey[key(cardId, modelName)]

    override fun getEmbeddings(modelName: String?): List<CardEmbedding> =
        embeddingsByKey.values.filter { it.modelName == modelName }

    override fun upsertEmbedding(embedding: CardEmbedding?) {
        if (embedding != null) {
            embeddingsByKey[key(embedding.cardId, embedding.modelName)] = embedding
        }
    }

    fun allEmbeddings(): List<CardEmbedding> = embeddingsByKey.values.toList()

    private fun key(cardId: String?, modelName: String?): String =
        "${modelName.orEmpty()}:${cardId.orEmpty()}"
}
