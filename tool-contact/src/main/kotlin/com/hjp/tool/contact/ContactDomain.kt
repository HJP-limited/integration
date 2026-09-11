package com.hjp.tool.contact

data class BusinessCardRecord(
    val id: String,
    val name: String,
    val nameEn: String = "",
    val company: String = "",
    val title: String = "",
    val department: String = "",
    val industry: String = "",
    val location: String = "",
    val phone: String = "",
    val mobile: String = "",
    val email: String = "",
    val address: String = "",
    val website: String = "",
    val memo: String = "",
    val tags: List<String> = emptyList(),
    val updatedAt: String = "",
)

interface BusinessCardRepository {
    suspend fun loadAll(): List<BusinessCardRecord>
    suspend fun getById(cardId: String): BusinessCardRecord?
}

data class BusinessCardUpdateResult(
    val before: BusinessCardRecord,
    val after: BusinessCardRecord,
)

interface MutableBusinessCardRepository : BusinessCardRepository {
    suspend fun update(
        cardId: String,
        updates: Map<String, String>,
        clearFields: Set<String>,
        updatedAt: String,
    ): BusinessCardUpdateResult?
}

data class StoredCardEmbedding(
    val cardId: String,
    val modelName: String,
    val dimension: Int,
    val vectorBlob: ByteArray,
    val sourceTextHash: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

interface BusinessCardEmbeddingStore {
    suspend fun loadEmbeddings(modelName: String): List<StoredCardEmbedding>
    suspend fun upsertEmbeddings(embeddings: List<StoredCardEmbedding>)
}

/** Ranked candidates supplied by the real Room FTS index on Android. */
data class KeywordSearchCandidate(
    val cardId: String,
    val rank: Int,
    val tier: String,
)

/** Optional production keyword index; JVM fixtures keep using the core in-memory fallback. */
interface BusinessCardKeywordIndex {
    suspend fun searchKeywordCandidates(query: String, limit: Int): List<KeywordSearchCandidate>
}

data class ContactSearchHit(
    val card: BusinessCardRecord,
    val score: Double,
    val matchSummary: String,
    val rank: Int,
    val retrievalSources: List<String>,
)

data class ContactSearchResponse(
    val hits: List<ContactSearchHit>,
    val mode: String,
    val fallbackUsed: Boolean,
    val fallbackReason: String,
    val engine: String,
    val elapsedMillis: Long,
)

interface ContactSearchBackend {
    suspend fun search(query: String, limit: Int): ContactSearchResponse
    suspend fun get(cardId: String): BusinessCardRecord?
    fun engineName(): String
    fun configurationAvailable(): Boolean
}
