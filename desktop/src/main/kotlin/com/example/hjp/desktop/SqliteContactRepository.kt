package com.example.hjp.desktop

import com.hjp.searchlookup.QueryAnalyzer
import com.hjp.tool.contact.BusinessCardEmbeddingStore
import com.hjp.tool.contact.BusinessCardKeywordIndex
import com.hjp.tool.contact.BusinessCardRecord
import com.hjp.tool.contact.BusinessCardUpdateResult
import com.hjp.tool.contact.KeywordSearchCandidate
import com.hjp.tool.contact.MutableBusinessCardRepository
import com.hjp.tool.contact.GluedTermSplitter
import com.hjp.tool.contact.SearchIndexText
import com.hjp.tool.contact.StemDisambiguation
import com.hjp.tool.contact.StoredCardEmbedding
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * 노트북 쪽 명함 저장소. 안드로이드의 `RoomBusinessCardRepository` 와 **같은 스키마·같은
 * SQL 을 같은 엔진(SQLite)** 에 던진다.
 *
 * 이게 이 클래스가 존재하는 이유다. FTS4 의 MATCH 의미(구문·접두어·unicode61 토크나이즈)를
 * 코틀린으로 흉내 내면 같은 질의가 폰과 노트북에서 다른 결과를 내고, 그 순간부터 노트북에서
 * 잰 지표는 앱을 대변하지 못한다. 컬럼 이름까지 Room 엔티티와 맞춰 두었으니 **바꿀 때는
 * 양쪽을 같이** 고쳐야 한다.
 *
 * 키워드 4단 티어도 앱과 같은 순서다(정확 구문 → 전체 단어 → 접두어 → 동의어 LIKE).
 */
class SqliteContactRepository(dbPath: String) :
    MutableBusinessCardRepository,
    BusinessCardKeywordIndex,
    BusinessCardEmbeddingStore,
    AutoCloseable {

    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
    private val analyzer = QueryAnalyzer()

    /** 마지막 검색이 어느 티어에서 후보를 채웠는지. 러너가 눈으로 보려고 쓴다. */
    @Volatile
    private var lastTiers: List<String> = emptyList()

    fun lastKeywordTiers(): List<String> = lastTiers

    init {
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS business_cards (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL DEFAULT '', name_en TEXT NOT NULL DEFAULT '',
                    company TEXT NOT NULL DEFAULT '', title TEXT NOT NULL DEFAULT '',
                    department TEXT NOT NULL DEFAULT '', industry TEXT NOT NULL DEFAULT '',
                    location TEXT NOT NULL DEFAULT '', phone TEXT NOT NULL DEFAULT '',
                    mobile TEXT NOT NULL DEFAULT '', email TEXT NOT NULL DEFAULT '',
                    address TEXT NOT NULL DEFAULT '', website TEXT NOT NULL DEFAULT '',
                    memo TEXT NOT NULL DEFAULT '', tags_json TEXT NOT NULL DEFAULT '[]',
                    updated_at TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent(),
            )
            // Room 의 @Fts4(tokenizer = unicode61, prefix = {2,3,4}) 와 같은 설정.
            st.executeUpdate(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS business_cards_fts USING fts4(
                    card_id, searchable_text, tokenize=unicode61, prefix='2,3,4'
                )
                """.trimIndent(),
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS card_embeddings (
                    card_id TEXT NOT NULL, model_name TEXT NOT NULL,
                    dimension INTEGER NOT NULL, vector_blob BLOB NOT NULL,
                    source_text_hash TEXT NOT NULL,
                    created_at_millis INTEGER NOT NULL, updated_at_millis INTEGER NOT NULL,
                    PRIMARY KEY (card_id, model_name)
                )
                """.trimIndent(),
            )
        }
    }

    // ---- 읽기 ---------------------------------------------------------------------------------

    override suspend fun loadAll(): List<BusinessCardRecord> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM business_cards ORDER BY id").use { rs ->
                buildList { while (rs.next()) add(rs.toRecord()) }
            }
        }

    override suspend fun getById(cardId: String): BusinessCardRecord? =
        conn.prepareStatement("SELECT * FROM business_cards WHERE id = ?").use { ps ->
            ps.setString(1, cardId.trim())
            ps.executeQuery().use { rs -> if (rs.next()) rs.toRecord() else null }
        }

    fun count(): Int = conn.createStatement().use { st ->
        st.executeQuery("SELECT COUNT(*) FROM business_cards").use { rs ->
            if (rs.next()) rs.getInt(1) else 0
        }
    }

    // ---- 쓰기 ---------------------------------------------------------------------------------

    /** 넣고 **바로** FTS 를 다시 만든다 — 안 그러면 방금 넣은 명함이 검색에 안 걸린다. */
    fun insertAll(records: List<BusinessCardRecord>) {
        if (records.isEmpty()) return
        conn.prepareStatement(
            "INSERT OR REPLACE INTO business_cards VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        ).use { ps ->
            records.forEach { card ->
                val values = listOf(
                    card.id, card.name, card.nameEn, card.company, card.title, card.department,
                    card.industry, card.location, card.phone, card.mobile, card.email,
                    card.address, card.website, card.memo, encodeTags(card.tags), card.updatedAt,
                )
                values.forEachIndexed { i, v -> ps.setString(i + 1, v) }
                ps.addBatch()
            }
            ps.executeBatch()
        }
        rebuildFts()
    }

    override suspend fun update(
        cardId: String,
        updates: Map<String, String>,
        clearFields: Set<String>,
        updatedAt: String,
    ): BusinessCardUpdateResult? {
        val before = getById(cardId) ?: return null
        fun field(name: String, current: String) = when {
            name in updates -> updates.getValue(name)
            name in clearFields -> ""
            else -> current
        }
        val after = before.copy(
            name = field("name", before.name),
            nameEn = field("name_en", before.nameEn),
            company = field("company", before.company),
            title = field("title", before.title),
            department = field("department", before.department),
            industry = field("industry", before.industry),
            location = field("location", before.location),
            phone = field("phone", before.phone),
            mobile = field("mobile", before.mobile),
            email = field("email", before.email),
            address = field("address", before.address),
            website = field("website", before.website),
            memo = field("memo", before.memo),
            updatedAt = updatedAt,
        )
        insertAll(listOf(after))
        return BusinessCardUpdateResult(before, after)
    }

    /**
     * FTS 를 통째로 다시 만든다. 앱의 `BusinessCardDao.rebuildFts` 와 같은 동작이고,
     * 인덱스 문자열도 같은 칸을 같은 순서로 이어 붙인다(전화번호는 숫자만 남긴 형태도 함께).
     */
    private fun rebuildFts() {
        conn.createStatement().use { it.executeUpdate("DELETE FROM business_cards_fts") }
        conn.prepareStatement(
            "INSERT INTO business_cards_fts (rowid, card_id, searchable_text) VALUES (?,?,?)",
        ).use { ps ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT * FROM business_cards ORDER BY id").use { rs ->
                    var row = 1
                    while (rs.next()) {
                        val card = rs.toRecord()
                        ps.setInt(1, row++)
                        ps.setString(2, card.id)
                        ps.setString(3, card.searchableText())
                        ps.addBatch()
                    }
                }
            }
            ps.executeBatch()
        }
    }

    // ---- 키워드 인덱스 (앱의 TieredFtsQuery 와 같은 4단) ----------------------------------------

    override suspend fun searchKeywordCandidates(query: String, limit: Int): List<KeywordSearchCandidate> {
        val safeLimit = limit.coerceIn(1, 200)
        // 안전하지 않은 글자는 붙이지 않고 **쪼갠다** — 앱의 TieredFtsQuery 와 같은 규칙.
        val analyzed = analyzer.analyze(query).tokens
            .flatMap { it.split(UNSAFE) }
            .filter { it.length >= 2 && it.uppercase() !in OPERATORS }
            .distinct()
        // 앱과 **같은 규칙**으로 조각/원본을 가린다(StemDisambiguation).
        val inIndex: suspend (String) -> Boolean = { term ->
            runCatching { matchIds(term, 1).isNotEmpty() }.getOrDefault(false)
        }
        val resolved = StemDisambiguation.resolve(
            StemDisambiguation.dropRedundantGluedDigits(analyzed),
            inIndex,
        )
        // 붙여 쓴 질의를 색인에 실재하는 낱말로 가른다 — 앱과 같은 규칙.
        val terms = GluedTermSplitter.split(resolved, inIndex)
        if (terms.isEmpty()) return emptyList()

        val ranked = LinkedHashMap<String, String>()
        fun collect(match: String, tier: String) {
            if (match.isBlank() || ranked.size >= safeLimit) return
            runCatching { matchIds(match, safeLimit) }.getOrDefault(emptyList())
                .forEach { ranked.putIfAbsent(it, tier) }
        }
        val joined = terms.joinToString(" ")
        collect("\"" + joined + "\"", "keyword-fts-phrase")
        collect(joined, "keyword-fts-all")
        collect(terms.joinToString(" ") { it + "*" }, "keyword-fts-prefix")
        if (ranked.size < safeLimit) {
            expandForLike(terms).forEach { term ->
                likeIds("%" + term + "%", safeLimit).forEach {
                    ranked.putIfAbsent(it, "keyword-like-synonym")
                }
            }
        }
        lastTiers = ranked.values.distinct()
        return ranked.entries.take(safeLimit).mapIndexed { index, e ->
            KeywordSearchCandidate(e.key, index + 1, e.value)
        }
    }

    private fun matchIds(matchQuery: String, limit: Int): List<String> =
        conn.prepareStatement(
            "SELECT card_id FROM business_cards_fts WHERE business_cards_fts MATCH ? LIMIT ?",
        ).use { ps ->
            ps.setString(1, matchQuery)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }

    private fun likeIds(like: String, limit: Int): List<String> =
        conn.prepareStatement(
            "SELECT card_id FROM business_cards_fts WHERE searchable_text LIKE ? LIMIT ?",
        ).use { ps ->
            ps.setString(1, like)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }

    // ---- 임베딩 -------------------------------------------------------------------------------

    override suspend fun loadEmbeddings(modelName: String): List<StoredCardEmbedding> =
        conn.prepareStatement(
            "SELECT * FROM card_embeddings WHERE model_name = ? ORDER BY card_id",
        ).use { ps ->
            ps.setString(1, modelName)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            StoredCardEmbedding(
                                cardId = rs.getString("card_id"),
                                modelName = rs.getString("model_name"),
                                dimension = rs.getInt("dimension"),
                                vectorBlob = rs.getBytes("vector_blob") ?: ByteArray(0),
                                sourceTextHash = rs.getString("source_text_hash") ?: "",
                                createdAtMillis = rs.getLong("created_at_millis"),
                                updatedAtMillis = rs.getLong("updated_at_millis"),
                            ),
                        )
                    }
                }
            }
        }

    override suspend fun upsertEmbeddings(embeddings: List<StoredCardEmbedding>) {
        if (embeddings.isEmpty()) return
        conn.prepareStatement("INSERT OR REPLACE INTO card_embeddings VALUES (?,?,?,?,?,?,?)").use { ps ->
            embeddings.forEach { e ->
                ps.setString(1, e.cardId)
                ps.setString(2, e.modelName)
                ps.setInt(3, e.dimension)
                ps.setBytes(4, e.vectorBlob)
                ps.setString(5, e.sourceTextHash)
                ps.setLong(6, e.createdAtMillis)
                ps.setLong(7, e.updatedAtMillis)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    override fun close() = conn.close()

    private fun ResultSet.str(column: String): String = getString(column) ?: ""

    private fun ResultSet.toRecord() = BusinessCardRecord(
        id = str("id"),
        name = str("name"),
        nameEn = str("name_en"),
        company = str("company"),
        title = str("title"),
        department = str("department"),
        industry = str("industry"),
        location = str("location"),
        phone = str("phone"),
        mobile = str("mobile"),
        email = str("email"),
        address = str("address"),
        website = str("website"),
        memo = str("memo"),
        tags = decodeTags(str("tags_json")),
        updatedAt = str("updated_at"),
    )

    private companion object {
        val UNSAFE = Regex("[^\\p{L}\\p{N}]+")
        val OPERATORS = setOf("AND", "OR", "NOT", "NEAR")

        /**
         * 앱의 `TieredFtsQuery` 와 **같은 표**. 한쪽만 늘리면 같은 질의가 양쪽에서 달라지고,
         * 그 순간 노트북에서 잰 값은 앱을 대변하지 못한다.
         */
        val SYNONYMS = mapOf(
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

        fun expandForLike(terms: List<String>): List<String> = LinkedHashSet<String>().apply {
            terms.forEach { term ->
                add(term)
                addAll(SYNONYMS[term.lowercase()].orEmpty())
            }
        }.toList()

        fun encodeTags(tags: List<String>): String =
            if (tags.isEmpty()) "[]" else tags.joinToString(",", "[", "]") { "\"" + it + "\"" }

        fun decodeTags(raw: String): List<String> =
            raw.trim().removePrefix("[").removeSuffix("]")
                .split(",")
                .map { it.trim().trim('"') }
                .filter { it.isNotBlank() }
    }
}

/** 앱과 **같은** 인덱스 문자열. 규칙은 [SearchIndexText] 한 곳에만 있다. */
internal fun BusinessCardRecord.searchableText(): String = SearchIndexText.build(
    name = name,
    nameEn = nameEn,
    company = company,
    title = title,
    department = department,
    industry = industry,
    location = location,
    address = address,
    email = email,
    website = website,
    memo = memo,
    tags = tags.joinToString(" "),
    phone = phone,
    mobile = mobile,
)
