package com.example.hjp.data

import android.content.Context
import com.hjp.tool.contact.BusinessCardRecord
import com.hjp.tool.contact.BusinessCardUpdateResult
import com.hjp.tool.contact.BusinessCardEmbeddingStore
import com.hjp.tool.contact.BusinessCardKeywordIndex
import com.hjp.tool.contact.KeywordSearchCandidate
import com.hjp.tool.contact.MutableBusinessCardRepository
import com.hjp.tool.contact.StoredCardEmbedding
import com.hjp.searchlookup.QueryAnalyzer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class RoomBusinessCardRepository(
    context: Context,
    private val dao: BusinessCardDao,
    private val json: Json = Json,
) : MutableBusinessCardRepository, BusinessCardEmbeddingStore, BusinessCardKeywordIndex {
    private val seedRepository = AssetBusinessCardRepository(context)
    private val seedMutex = Mutex()

    override suspend fun loadAll(): List<BusinessCardRecord> {
        seedIfEmpty()
        return dao.loadAll().map { it.toBusinessCardRecord(json) }
    }

    override suspend fun getById(cardId: String): BusinessCardRecord? {
        seedIfEmpty()
        return dao.getById(cardId.trim())?.toBusinessCardRecord(json)
    }

    override suspend fun update(
        cardId: String,
        updates: Map<String, String>,
        clearFields: Set<String>,
        updatedAt: String,
    ): BusinessCardUpdateResult? {
        seedIfEmpty()
        val before = dao.getById(cardId.trim())?.toBusinessCardRecord(json) ?: return null
        val after = before.copy(
            name = resolveField("name", before.name, updates, clearFields),
            nameEn = resolveField("name_en", before.nameEn, updates, clearFields),
            company = resolveField("company", before.company, updates, clearFields),
            title = resolveField("title", before.title, updates, clearFields),
            department = resolveField("department", before.department, updates, clearFields),
            industry = resolveField("industry", before.industry, updates, clearFields),
            location = resolveField("location", before.location, updates, clearFields),
            phone = resolveField("phone", before.phone, updates, clearFields),
            mobile = resolveField("mobile", before.mobile, updates, clearFields),
            email = resolveField("email", before.email, updates, clearFields),
            address = resolveField("address", before.address, updates, clearFields),
            website = resolveField("website", before.website, updates, clearFields),
            memo = resolveField("memo", before.memo, updates, clearFields),
            updatedAt = updatedAt,
        )
        dao.updateAndReindex(after.toBusinessCardEntity(json))
        return BusinessCardUpdateResult(before, after)
    }

    /**
     * 촬영으로 새로 만든 명함을 넣는다. Agent_0910 에는 OCR 트랙이 없어 이 경로가 없었다.
     *
     * 넣고 **바로 FTS 를 다시 만든다**(insertAllAndReindex). 안 그러면 방금 찍은 명함이
     * 목록에는 보이는데 검색으로는 안 나온다 — 키워드 인덱스가 옛 상태로 남기 때문이다.
     */
    suspend fun insert(record: BusinessCardRecord) {
        seedIfEmpty()
        dao.insertAllAndReindex(listOf(record.toBusinessCardEntity(json)))
    }

    /** 화면이 쓰는 전체 개수. 시딩 전이면 0 이 아니라 시딩 후의 수를 돌려준다. */
    suspend fun count(): Int {
        seedIfEmpty()
        return dao.count()
    }

    override suspend fun searchKeywordCandidates(
        query: String,
        limit: Int,
    ): List<KeywordSearchCandidate> {
        seedIfEmpty()
        val safeLimit = limit.coerceIn(1, 200)
        val terms = TieredFtsQuery.analyze(query)
        if (terms.isEmpty()) return emptyList()
        val ranked = linkedMapOf<String, String>()
        suspend fun collect(match: String?, tier: String) {
            if (match == null || ranked.size >= safeLimit) return
            runCatching { dao.searchFtsIds(match, safeLimit) }
                .getOrDefault(emptyList())
                .forEach { ranked.putIfAbsent(it, tier) }
        }
        collect(TieredFtsQuery.phrase(terms), "keyword-fts-phrase")
        collect(TieredFtsQuery.allTerms(terms), "keyword-fts-all")
        collect(TieredFtsQuery.prefix(terms), "keyword-fts-prefix")
        if (ranked.size < safeLimit) {
            TieredFtsQuery.expandForLike(terms).forEach { term ->
                dao.searchLikeIds("%$term%", safeLimit)
                    .forEach { ranked.putIfAbsent(it, "keyword-like-synonym") }
            }
        }
        return ranked.entries.take(safeLimit).mapIndexed { index, entry ->
            KeywordSearchCandidate(entry.key, index + 1, entry.value)
        }
    }

    override suspend fun loadEmbeddings(modelName: String): List<StoredCardEmbedding> {
        seedIfEmpty()
        return dao.loadEmbeddings(modelName).map(CardEmbeddingEntity::toStoredEmbedding)
    }

    override suspend fun upsertEmbeddings(embeddings: List<StoredCardEmbedding>) {
        if (embeddings.isNotEmpty()) {
            dao.upsertEmbeddings(embeddings.map(StoredCardEmbedding::toEmbeddingEntity))
        }
    }

    private suspend fun seedIfEmpty() {
        if (dao.count() > 0) return
        seedMutex.withLock {
            if (dao.count() > 0) return@withLock
            val seedCards = seedRepository.loadAll().map { it.toBusinessCardEntity(json) }
            dao.insertAllAndReindex(seedCards)
        }
    }

    private fun resolveField(
        field: String,
        currentValue: String,
        updates: Map<String, String>,
        clearFields: Set<String>,
    ): String = when {
        field in updates -> updates.getValue(field)
        field in clearFields -> ""
        else -> currentValue
    }
}

/** Latest Ryeong safe FTS4 query construction, kept next to the concrete Room index. */
internal object TieredFtsQuery {
    private val unsafe = Regex("[^\\p{L}\\p{N}]+")
    private val operators = setOf("AND", "OR", "NOT", "NEAR")
    private val analyzer = QueryAnalyzer()
    private val synonyms = mapOf(
        "ai" to listOf("ai", "인공지능", "머신러닝", "개발", "연구"),
        "인공지능" to listOf("ai", "인공지능", "머신러닝", "개발", "연구"),
        "개발" to listOf("개발", "개발자", "엔지니어", "소프트웨어", "it", "ai"),
        "디자인" to listOf("디자인", "디자이너", "브랜드", "크리에이티브"),
        "투자" to listOf("투자", "벤처", "금융", "vc"),
        "영업" to listOf("영업", "세일즈", "파트너십", "비즈니스"),
        "마케팅" to listOf("마케팅", "브랜드", "광고", "홍보"),
        "의료" to listOf("의료", "헬스케어", "제약", "병원"),
        "대표" to listOf("대표", "ceo", "창업", "창업자"),
        "변호사" to listOf("변호사", "법무", "법률"),
        "회계" to listOf("회계", "회계사", "재무", "감사"),
    )

    fun analyze(raw: String): List<String> = analyzer.analyze(raw).tokens
        .map { it.replace(unsafe, "") }
        .filter { it.length >= 2 && it.uppercase() !in operators }
        .distinct()

    fun phrase(terms: List<String>): String? =
        terms.joinToString(" ").takeIf(String::isNotBlank)?.let { "\"$it\"" }

    fun allTerms(terms: List<String>): String? =
        terms.joinToString(" ").takeIf(String::isNotBlank)

    fun prefix(terms: List<String>): String? =
        terms.filter { it.length >= 2 }.joinToString(" ") { "$it*" }.takeIf(String::isNotBlank)

    fun expandForLike(terms: List<String>): List<String> = linkedSetOf<String>().apply {
        terms.forEach { term ->
            add(term)
            addAll(synonyms[term.lowercase()].orEmpty())
        }
    }.toList()
}
