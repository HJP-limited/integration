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
    val appliedConstraints: AppliedSearchConstraints? = null,
)

/** Observed after retrieval, not inferred by re-parsing the user's query. */
data class AppliedSearchConstraints(
    val locations: List<String>,
    val titles: List<String>,
    val companies: List<String>,
    val departments: List<String>,
    val strictFilterApplied: Boolean,
)

interface ContactSearchBackend {
    suspend fun search(query: String, limit: Int): ContactSearchResponse
    suspend fun get(cardId: String): BusinessCardRecord?
    fun engineName(): String
    fun configurationAvailable(): Boolean

    /**
     * 조건에 맞는 명함이 **모두 몇 장인지**. 검색이 아니라 세기다 — top-N 으로 세면
     * "판교에 몇 명 있어?"가 언제나 5가 된다.
     *
     * 질의가 비면 전체 개수. 조건을 읽지 못하면(개념형) null — 부르는 쪽이 보통의 검색으로
     * 가야 한다는 뜻이다. 셀 줄 모르는 백엔드도 null 을 돌려주면 된다.
     */
    suspend fun countMatching(query: String): Int? = null
}
