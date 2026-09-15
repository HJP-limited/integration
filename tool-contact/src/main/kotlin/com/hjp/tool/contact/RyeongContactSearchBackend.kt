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
import com.hjp.searchlookup.SearchPlanSnapshot
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
    private val requireModelBacked: Boolean = false,
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
    private data class InitializedSearch(
        val service: SearchLookupService,
        val modelBacked: Boolean,
        val fallbackReason: String,
        val initializationMillis: Long,
    )
    @Volatile private var initialized: InitializedSearch? = null

    override suspend fun search(query: String, limit: Int): ContactSearchResponse = withContext(Dispatchers.Default) {
        val state = requireService()
        val searchService = state.service
        val mode = if (state.modelBacked) RetrievalMode.HYBRID else RetrievalMode.KEYWORD_ONLY
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
        var observedPlan: SearchPlanSnapshot? = null
        val response = searchService.retrieve(query, limit, mode, indexedKeyword, SearchPlanObserver { plan ->
            observedPlan = plan
            searchPlanObserver.onSearchPlan(plan)
        })
        val fallbackUsed = response.fallbackUsed || !state.modelBacked
        val fallbackReason = response.fallbackReason.ifBlank { state.fallbackReason }
        if (requireModelBacked && response.mode == RetrievalMode.KEYWORD_ONLY &&
            fallbackReason != "IDENTIFIER_QUERY_SEMANTIC_EXCLUDED"
        ) {
            error("Required semantic search failed: ${fallbackReason.ifBlank { "unknown reason" }}")
        }
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
            initializationMillis = state.initializationMillis,
        ))
        ContactSearchResponse(
            hits = hits,
            mode = response.mode.name,
            fallbackUsed = fallbackUsed,
            fallbackReason = fallbackReason,
            engine = response.engineName,
            elapsedMillis = response.elapsedMillis,
            appliedConstraints = observedPlan?.let { plan ->
                AppliedSearchConstraints(plan.locations(), plan.titles(), plan.companies(), plan.departments(),
                    plan.strictFilterApplied())
            },
        )
    }

    override suspend fun countMatching(query: String): Int? = withContext(Dispatchers.Default) {
        val counted = requireService().service.countMatching(query)
        if (counted == SearchLookupService.COUNT_NOT_COUNTABLE) null else counted
    }

    override suspend fun get(cardId: String): BusinessCardRecord? = withContext(Dispatchers.Default) {
        val indexedCard = requireService().service.getCard(cardId) ?: return@withContext null
        repository.getById(indexedCard.id)
    }

    override fun engineName(): String =
        initialized?.service?.engineName() ?: "ryeong-llm-integration-work@b543a18"
    // Availability describes whether this backend is configured, not whether its last IO succeeded.
    // Latching it false after one storage/model-load error hides all contact tools on later turns,
    // so their otherwise-retryable requireService() would never run again. Each execution still
    // enforces required models and propagates failures; this is not a fallback or an automatic loop.
    override fun configurationAvailable(): Boolean = true

    suspend fun invalidate() = initMutex.withLock {
        initialized = null
    }

    /**
     * Rebuilds the immutable search snapshot after the card store changes.
     *
     * Construction refreshes only missing or stale document vectors and persists them through
     * [BusinessCardEmbeddingStore]. This method is deliberately strict: adding a card must not be
     * reported as complete when the required embedding model silently fell back to keyword search.
     */
    suspend fun refreshAfterCardChange() = withContext(Dispatchers.Default) {
        invalidate()
        val state = requireService()
        check(state.modelBacked) {
            "Business-card embedding refresh failed: " +
                state.fallbackReason.ifBlank { "model-backed embedding unavailable" }
        }
    }

    private suspend fun requireService(): InitializedSearch {
        initialized?.let { return it }
        return initMutex.withLock {
            initialized?.let { return@withLock it }
            try {
                val cards = repository.loadAll()
                require(cards.map { it.id }.distinct().size == cards.size) {
                    "Business card IDs must be unique"
                }
                val initializationStarted = System.nanoTime()
                val embeddingEngine = embeddingEngineFactory()
                if (requireModelBacked) {
                    check(embeddingEngine.isModelBacked) {
                        "Required embedding model unavailable: ${embeddingEngine.diagnosticStatus()}"
                    }
                }
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
                val initializationMillis =
                    (System.nanoTime() - initializationStarted) / 1_000_000L
                val embeddingModelBacked = embeddingEngine.isModelBacked
                val embeddingFallbackReason = if (embeddingModelBacked) "" else embeddingEngine.diagnosticStatus()
                if (embeddingModelBacked && embeddingStore != null) {
                    embeddingStore.upsertEmbeddings(
                        snapshot.allEmbeddings().map { it.toStoredEmbedding() },
                    )
                }
                // Publish only after persistence succeeds. A failed write must leave initialization
                // retryable instead of returning an unpersisted snapshot on the next call.
                InitializedSearch(createdService, embeddingModelBacked, embeddingFallbackReason, initializationMillis)
                    .also { initialized = it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
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
