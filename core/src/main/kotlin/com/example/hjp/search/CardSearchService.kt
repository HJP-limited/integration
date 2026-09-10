package com.example.hjp.search

import com.example.hjp.data.BusinessCardEntity
import com.example.hjp.data.CardEmbeddingEntity
import org.json.JSONArray
import org.json.JSONObject
import kotlin.system.measureTimeMillis

// FTS4 MATCH 쿼리 문자열 빌더 — 검색 담당 브랜치의 Fts4QueryBuilder 이식.
private val ftsUnsafeChars = Regex("[^\\p{L}\\p{N}]+")
private val ftsOperators = setOf("AND", "OR", "NOT", "NEAR")

private fun ftsSafeTerms(tokens: List<String>): List<String> =
    tokens.map { it.replace(ftsUnsafeChars, "") }
        .filter { it.isNotBlank() && it.uppercase() !in ftsOperators }

/** 정확 구문 매치(어순·인접 그대로) — 가장 정밀. */
private fun ftsPhraseMatch(terms: List<String>): String? =
    terms.joinToString(" ").takeIf { it.isNotBlank() }?.let { "\"$it\"" }

/** 전체 단어 AND(어순 무관, 인접 불필요). */
private fun ftsAllTermsMatch(terms: List<String>): String? =
    terms.joinToString(" ").takeIf { it.isNotBlank() }

/** 접두어 AND — "강서연씨"처럼 존칭/조사가 붙어도 접두어로 잡힌다(prefix 인덱스 활용). */
private fun ftsPrefixMatch(terms: List<String>): String? =
    terms.filter { it.length >= 2 }
        .joinToString(" ") { "$it*" }
        .takeIf { it.isNotBlank() }

// 키워드 동의어 확장 — sojung 브랜치(ymj/embedding-search-android-eval와 같은 분기점) 참고.
// 키워드는 정확히 그 단어가 있어야만 매치되고 "AI"↔"인공지능" 같은 동의어를 모른다.
// LIKE 폴백(가장 느슨한 티어)에만 적용해서, 정밀한 ①②③ 티어의 정확도는 그대로 두고
// 개념형 질의의 재현율만 싸게 보강한다. 임베딩이 이미 하는 일과 겹치지만, 임베딩이 안 뜨는
// 기기(키워드 전용 폴백)에서도 최소한의 동의어 커버리지를 준다.
private val SYNONYMS: Map<String, List<String>> = mapOf(
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

/** LIKE 폴백 전용 — 원래 토큰 + 동의어를 합쳐서 돌려준다(중복 제거, 순서 보존). */
private fun expandSynonymsForFallback(terms: List<String>): List<String> {
    val out = LinkedHashSet<String>()
    for (t in terms) {
        out.add(t)
        SYNONYMS[t.lowercase()]?.let { out.addAll(it) }
    }
    return out.toList()
}

data class CardSearchHit(
    val card: BusinessCardEntity,
    val score: Double,
    val keywordRank: Int?,
    val vectorRank: Int?,
    val similarity: Float,
)

data class CardSearchResponse(
    val query: String,
    val engine: String,
    val retrieval: String,
    val keywordQuery: String,
    val semanticQuery: String,
    val results: List<CardSearchHit>,
    /** 가제티어 기권 — 없는 이름/지역/식별자를 지목했다. true 면 results 는 비어 있다. */
    val abstained: Boolean = false,
    /** 식별자 질의로 라우팅되어 시맨틱 축을 융합에서 뺐는가. */
    val identifierRouted: Boolean = false,
    /** 적용된 필드 하드 필터. */
    val fieldFilters: FieldFilters = FieldFilters(),
) {
    /**
     * LLM 에 넣을 컨텍스트. 각 명함에 번호를 붙인다.
     *
     * **후보 개수를 헤더에 쓰지 않는다.** 예전에는 "검색 후보: 총 N명"을 맨 앞에 넣었다.
     * 2B 모델이 목록 개수를 세다 틀리는 걸 막으려던 것인데(실측: 5명인데 "4명입니다"),
     * 정작 모델이 **그 숫자를 답으로 그대로 옮겨 적는** 부작용이 더 컸다:
     *   "디자인하는 사람 찾아줘" -> 이름 대신 "총 3명" (실제 디자인 직군은 훨씬 많다)
     * 후보 개수는 '보여준 카드 수'일 뿐 질문의 답이 아니다. 셀 수 있는 조건이면
     * [totalMatches] 로 **진짜 전체 수**를 받아 그때만 숫자를 넣는다.
     */
    fun ragContext(limit: Int = 5, totalMatches: Int? = null): String {
        val taken = results.take(limit)
        if (taken.isEmpty()) return "검색 후보 없음"
        val blocks = taken.mapIndexed { i, hit -> "[${i + 1}번]\n${hit.card.ragContext()}" }
        // 개수는 **모델이 볼 수 없는 정보일 때만** 넣는다(총계 > 보여준 수).
        // total == shown 이면 모델이 카드를 다 보고 있어서 개수를 알려줄 이유가 없는데,
        // 넣으면 그 숫자를 답으로 옮겨 적는다(실측: "그 사람 부서는?" -> "총 1명").
        val header =
            if (totalMatches != null && totalMatches > taken.size) {
                "조건에 맞는 사람: 총 ${totalMatches}명 (아래는 그중 ${taken.size}명)"
            } else {
                "검색 후보"
            }
        return "$header\n\n" + blocks.joinToString("\n\n")
    }

    fun toJson(): JSONObject {
        val cards = JSONArray()
        results.forEach { hit ->
            cards.put(
                JSONObject()
                    .put("card_id", hit.card.id)
                    .put("name", hit.card.name)
                    .put("company", hit.card.company)
                    .put("title", hit.card.title)
                    .put("department", hit.card.department)
                    .put("industry", hit.card.industry)
                    .put("location", hit.card.location)
                    .put("score", hit.score)
                    .put("keyword_rank", hit.keywordRank ?: JSONObject.NULL)
                    .put("vector_rank", hit.vectorRank ?: JSONObject.NULL)
                    .put("similarity", hit.similarity.toDouble())
                    .put("context", hit.card.ragContext())
            )
        }
        return JSONObject()
            .put("query", query)
            .put("engine", engine)
            .put("retrieval", retrieval)
            .put("keyword_query", keywordQuery)
            .put("semantic_query", semanticQuery)
            .put("cards", cards)
            .put("abstained", abstained)
            .put("identifier_routed", identifierRouted)
            .put("field_filters", fieldFilters.toString())
            .put("rag_context", ragContext())
    }
}

/**
 * 하이브리드 명함 검색. 저장소와 임베더를 주입받아 플랫폼에 묶이지 않는다 —
 * 안드로이드(:app)와 데스크톱 러너(:desktop)가 **같은 인스턴스 코드**를 돌린다.
 */
class CardSearchService(
    private val store: CardStore,
    private val embeddingProviderFactory: () -> TextEmbeddingProvider,
    private val seedSource: SeedSource = EmptySeedSource,
    /** 기기/런타임 정보처럼 코어가 알 수 없는 진단 항목을 플랫폼이 채운다. */
    private val platformDiagnostics: () -> JSONObject = { JSONObject() },
) {
    private companion object {
        /** RRF 상수. scripts/eval_search.py 의 RRF_K 와 같은 값이어야 한다. */
        const val RRF_K = 60.0

        /**
         * 융합에 넣을 축별 후보 수. eval_search.py 의 FUSION_POOL 과 같은 값이어야 한다.
         *
         * 40 -> 150 (실측 근거): 개념형 질의는 정답이 60~147명인데 풀이 40이면 후보가 최대
         * 80개라 완벽해도 P@R 상한이 0.5~0.6 에 걸린다. 늘렸더니 개념형(hard) 0.318 -> 0.403,
         * (easy) 0.509 -> 0.614 로 올랐고 150 에서 포화됐다(300·1000 도 동일).
         * 이름·전화·회사명은 정답이 1~2명이라 풀 크기와 무관하게 변화 없음.
         * 대가는 '지역+직함' 0.923 -> 0.892. 개념형 이득이 훨씬 커서 채택했다.
         * LLM 에 넘기는 건 여전히 ragContext(5) 라 온디바이스 생성 시간에는 영향이 없다.
         */
        const val FUSION_POOL = 150
    }

    private var embeddingProvider: TextEmbeddingProvider = embeddingProviderFactory()

    private var ftsRebuilt = false

    // 가제티어는 카드 전체를 훑어 만들므로(5000장 기준) 질의마다 다시 만들면 안 된다.
    // 카드가 바뀌는 지점(임포트/시드)에서만 무효화한다.
    @Volatile
    private var cachedGazetteer: CardGazetteer? = null

    private fun gazetteer(): CardGazetteer =
        cachedGazetteer ?: synchronized(this) {
            cachedGazetteer ?: CardGazetteer(store.allCards()).also { cachedGazetteer = it }
        }

    /**
     * 질의에서 **데이터에 실재하는** 사람 이름만 뽑는다.
     *
     * 대상 정정("A가 아니라 B야")을 풀 때 쓴다 — 검색과 같은 어휘로 판정해야 두 곳이
     * 갈라지지 않는다. 가제티어는 카드에서 만들어지므로 새 명함이 들어오면 자동으로 따라온다.
     */
    fun knownNamesIn(query: String): List<String> =
        extractFieldFilters(KeywordSearchRanker.analyze(query), gazetteer()).names

    private fun invalidateGazetteer() {
        cachedGazetteer = null
    }

    /** 모델 파일을 새로 가져온 뒤 앱 재시작 없이 다시 로드를 시도한다. */
    fun reloadEmbeddingProvider() {
        // 이전 임베더의 네이티브 메모리를 먼저 해제해야 반복 로드 시 메모리가 쌓이지 않는다
        embeddingProvider.close()
        embeddingProvider = embeddingProviderFactory()
    }

    /** JSON 배열 문자열(명함 목록)로 DB 전체를 교체한다. 기존 임베딩도 함께 지운다. */
    fun importCardsJson(json: String): Int {
        val array = JSONArray(json)
        val now = System.currentTimeMillis()
        val cards = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            BusinessCardEntity(
                o.optString("id").ifBlank { "J%05d".format(i) },
                o.optString("name"),
                o.optString("nameEn"),
                o.optString("company"),
                o.optString("title"),
                o.optString("department"),
                o.optString("industry"),
                o.optString("location"),
                o.optString("phone"),
                o.optString("email"),
                o.optString("address"),
                o.optString("memo"),
                o.optString("tags"),
                now,
            )
        }
        require(cards.isNotEmpty()) { "JSON에 명함이 없습니다." }
        store.replaceAllCards(cards)
        ftsRebuilt = true
        invalidateGazetteer()
        return cards.size
    }

    /**
     * 명함 한 장을 추가하거나 갱신한다. OCR 로 새로 찍은 카드가 들어오는 경로다.
     *
     * 가제티어를 무효화해야 방금 넣은 이름·지역이 곧바로 검색 어휘가 된다 — 안 하면
     * 저장 직후 "김정 찾아줘"가 기권 처리된다. 벡터는 다음 하이브리드 검색에서
     * indexEmbeddings() 가 개수 차이를 보고 채운다.
     */
    fun addCard(card: BusinessCardEntity) {
        seedIfEmpty()
        store.upsertCards(listOf(card))
        invalidateGazetteer()
    }

    /** 최근 추가·수정된 순서로 [limit] 장. 홈 화면의 "최근 추가 명함". */
    fun recentCards(limit: Int): List<BusinessCardEntity> {
        seedIfEmpty()
        return store.allCards()
            .sortedByDescending { it.updatedAtMillis }
            .take(limit.coerceAtLeast(0))
    }

    /** 아직 쓰이지 않은 카드 id. OCR 로 만든 카드에 붙인다. */
    fun nextOcrCardId(): String {
        seedIfEmpty()
        val used = store.allCards().mapTo(HashSet()) { it.id }
        var n = used.size + 1
        while ("OCR%05d".format(n) in used) n++
        return "OCR%05d".format(n)
    }

    val engineStatus: String
        get() = "${embeddingProvider.name} / Room FTS"

    // 어떤 provider 인스턴스가 만들었는지와 무관하게 같은 값을 가리켜야 해서 고정 문자열로 둔다.
    // (provider.name은 로드 상태에 따라 문자열이 바뀌어서 키로 쓰면 안 됨)
    // 임베딩 입력 형식이 바뀌면 버전 접미사를 올려서 기존 벡터가 재계산되게 할 것.
    // query/document 프롬프트 형식은 RAG SDK(GemmaEmbeddingModel)가 자동으로 붙인다.
    private val embeddingModelKey: String = "EmbeddingGemma-300M#doc-v3"

    private fun cardEmbeddingInput(card: BusinessCardEntity): String {
        val parts = listOf(
            card.name, card.nameEn, card.company, card.title, card.department,
            card.industry, card.location, card.memo, card.tags,
        ).filter { it.isNotBlank() }
        return parts.joinToString(", ")
    }

    fun diagnostics(): JSONObject {
        seedIfEmpty()
        var dimensions = 0
        var elapsedMs = 0L
        val error = try {
            if (embeddingProvider.isModelBacked) {
                elapsedMs = measureTimeMillis {
                    dimensions = embeddingProvider.embedQuery("AI developer in Pangyo").size
                }
            }
            ""
        } catch (e: Throwable) {
            e.javaClass.simpleName + ": " + e.message.orEmpty()
        }
        val report = JSONObject()
            .put("active_embedding_provider", embeddingProvider.name)
            .put("active_embedding_model_backed", embeddingProvider.isModelBacked)
            .put("active_embedding_status", embeddingProvider.diagnosticStatus)
            .put("embedding_dimensions", dimensions)
            .put("sample_embedding_ms", elapsedMs)
            .put("room_card_count", store.countCards())
            .put("error", error)
        val platform = platformDiagnostics()
        platform.keys().forEach { key -> report.put(key, platform.get(key)) }
        return report
    }

    fun seedIfEmpty() {
        if (store.countCards() == 0) {
            store.clearFts()
            if (seedFromAsset()) {
                seedEmbeddingsFromAsset()
            } else {
                store.upsertCards(sampleCards())
            }
            ftsRebuilt = true
            invalidateGazetteer()
            return
        }
        if (!ftsRebuilt) {
            store.rebuildFts()
            ftsRebuilt = true
        }
    }

    /** 등록된 전체 명함 수. "전체 몇 장이야?" 류 질문에 검색을 태우지 않고 바로 답하는 데 쓴다. */
    fun totalCardCount(): Int {
        seedIfEmpty()
        return store.countCards()
    }

    /**
     * 조건이 있는 카운트 질문("판교에 몇 명 있어?", "이사 직급 몇 명이야?")에 검색(top-N 컷)을
     * 안 태우고 전체 카드를 직접 세서 정확한 개수를 낸다. 가제티어가 아는 조건(지역/직함/이름)이
     * 하나도 없으면(개념형 질의) null — 호출자가 기존 검색+LLM 경로로 폴백해야 한다.
     */
    fun countByCondition(question: String): List<BusinessCardEntity>? {
        seedIfEmpty()
        return countByKnownCondition(question, gazetteer(), store.allCards())
    }

    /**
     * OCR로 새 카드가 들어오기 전까지는, 라이브 임베딩 모델이 기기에서 안 뜨더라도
     * 검색 기능 자체는 미리 계산해둔 벡터로 동작해야 한다. 그래서 실패해도 조용히 건너뛴다.
     */
    fun indexEmbeddings(onProgress: ((done: Int, total: Int) -> Unit)? = null) {
        seedIfEmpty()
        if (!embeddingProvider.isModelBacked) return
        // 수천 장 규모에서 검색할 때마다 전체 카드를 훑지 않도록, 개수가 맞으면 건너뛴다.
        // (카드 내용이 바뀌면 임포트 시 임베딩을 함께 지우므로 개수 비교로 충분)
        val total = store.countCards()
        if (onProgress == null && store.countEmbeddingsForModel(embeddingModelKey) >= total) return
        var done = 0
        store.allCards().forEach { card ->
            val text = cardEmbeddingInput(card)
            val hash = sha256(text)
            val existing = store.findEmbedding(card.id, embeddingModelKey, hash)
            if (existing == null) {
                val vector = embeddingProvider.embedDocument(text)
                if (vector.isNotEmpty()) {
                    store.upsertEmbedding(
                        CardEmbeddingEntity(
                            card.id,
                            embeddingModelKey,
                            vector.toBlob(),
                            vector.size,
                            hash,
                            System.currentTimeMillis()
                        )
                    )
                }
            }
            done++
            onProgress?.invoke(done, total)
        }
    }

    fun search(rawQuery: String, limit: Int = 5): CardSearchResponse = searchHybrid(rawQuery, limit)

    /**
     * 검색하지 않고 지정한 카드들을 그대로 결과로 돌려준다.
     * 정정/확인 발화("5명인데?")에서 직전 턴의 근거를 재사용하기 위한 경로 —
     * 그런 발화를 검색어로 쓰면 엉뚱한 카드가 근거가 되어 대화가 끊긴다.
     */
    fun searchByIds(cardIds: List<String>, limit: Int = 5): CardSearchResponse {
        seedIfEmpty()
        val hits = cardIds.mapNotNull { store.findCard(it) }
            .take(limit.coerceIn(1, 20))
            .mapIndexed { index, card ->
                CardSearchHit(card, (limit - index).toDouble(), index + 1, null, 0f)
            }
        return CardSearchResponse(
            query = "",
            engine = engineStatus,
            retrieval = "previous_turn_reuse (no re-search)",
            keywordQuery = "",
            semanticQuery = "",
            results = hits,
        )
    }

    fun searchKeywordOnly(rawQuery: String, limit: Int = 20): CardSearchResponse {
        seedIfEmpty()
        val safeLimit = limit.coerceIn(1, 20)
        val analyzed = KeywordSearchRanker.analyze(rawQuery)
        var hits = keywordHits(analyzed, safeLimit)
        // 기권 판정에는 '필터 적용 전' 키워드 히트 수를 쓴다. 필터로 0개가 된 것과
        // 애초에 문자 근거가 없던 것은 다른 상황인데, 필터 후 개수를 넘기면 둘이 섞여
        // 하이브리드 경로와 판정이 갈라진다.
        val rawKeywordHitCount = hits.size

        // 키워드 전용 경로에도 필드 필터·기권을 동일하게 적용한다. 임베딩이 안 뜨는 기기에서
        // 이 경로로 폴백되는데, 여기만 규칙이 빠지면 같은 질의가 기기마다 다르게 동작한다.
        val gaz = gazetteer()
        val filters = extractFieldFilters(analyzed, gaz)
        if (!filters.isEmpty) {
            val kept = applyFieldFilters(hits.map { it.card }, filters).map { it.id }.toSet()
            hits = hits.filter { it.card.id in kept }
        }
        val abstained = shouldAbstain(analyzed, rawKeywordHitCount, gaz)
        if (abstained) hits = emptyList()

        return CardSearchResponse(
            query = analyzed.raw,
            engine = "Room FTS keyword",
            retrieval = "room_fts_keyword_only",
            keywordQuery = analyzed.keywordQuery,
            semanticQuery = "",
            results = hits,
            abstained = abstained,
            identifierRouted = CardGazetteer.isIdentifierQuery(analyzed.raw),
            fieldFilters = filters,
        )
    }

    fun searchHybrid(rawQuery: String, limit: Int = 5): CardSearchResponse {
        seedIfEmpty()
        // 라이브 임베딩 모델이 이 기기에서 안 뜨면 쿼리를 임베딩할 수 없으니 키워드 검색으로 내려간다.
        // 문서(명함) 쪽은 번들된 사전 계산 벡터가 이미 있어도, 쿼리 쪽이 없으면 벡터 검색 자체가 불가능하다.
        val vectorSearchAvailable = embeddingProvider.isModelBacked
        if (vectorSearchAvailable) indexEmbeddings()
        val safeLimit = limit.coerceIn(1, 20)
        val analyzed = KeywordSearchRanker.analyze(rawQuery)
        val keywordHits = keywordHits(analyzed, 40)
        val keywordIds = keywordHits.map { it.card.id }

        // 융합 라우팅: 전화/이메일 같은 식별자 질의는 시맨틱을 섞지 않는다.
        // 근거(오프라인 측정): 식별자 질의에서 시맨틱은 R@5=0.000 인데 대등하게 융합하면
        // 좋은 키워드 결과를 밀어내 P@5 가 1.000 -> 0.233 으로 떨어졌다.
        val identifierRouted = CardGazetteer.isIdentifierQuery(analyzed.raw)
        val useSemantic = vectorSearchAvailable && !identifierRouted

        val queryVector = if (useSemantic) embeddingProvider.embedQuery(analyzed.semanticQuery) else FloatArray(0)
        val vectorScores = if (queryVector.isNotEmpty()) vectorScores(queryVector, FUSION_POOL) else emptyList()

        val keywordRank = keywordIds.take(FUSION_POOL).mapIndexed { index, id -> id to index + 1 }.toMap()
        val vectorRank = vectorScores.mapIndexed { index, hit -> hit.first to index + 1 }.toMap()
        val similarityById = vectorScores.toMap()
        val candidateIds = LinkedHashSet<String>().apply {
            addAll(keywordRank.keys)
            addAll(vectorScores.map { it.first })
        }
        if (candidateIds.isEmpty()) candidateIds.addAll(store.allCards().map { it.id })

        var hits = candidateIds.mapNotNull { id ->
            val card = store.findCard(id) ?: return@mapNotNull null
            val score = rrf(keywordRank[id]) + rrf(vectorRank[id])
            CardSearchHit(card, score, keywordRank[id], vectorRank[id], similarityById[id] ?: 0f)
        }.sortedWith(
            compareByDescending<CardSearchHit> { it.score }
                .thenByDescending { it.similarity }
                .thenBy { it.card.name }
        )

        // 필드 하드 필터 — 질의가 '실제 존재하는' 이름/지역을 지목했으면 그 조건을 만족하는 카드만
        // 남긴다. 이게 없으면 "안정우씨 어디서 만났더라"에 무관한 사람이 top-5 를 채우느라 딸려온다.
        val gaz = gazetteer()
        val filters = extractFieldFilters(analyzed, gaz)
        if (!filters.isEmpty) {
            val cards = applyFieldFilters(hits.map { it.card }, filters).map { it.id }.toSet()
            hits = hits.filter { it.card.id in cards }
        }

        // 기권 — 없는 이름/지역/식별자를 지목했으면 아무것도 반환하지 않는다.
        val abstained = shouldAbstain(analyzed, keywordIds.size, gaz)
        if (abstained) hits = emptyList()

        return CardSearchResponse(
            query = analyzed.raw,
            engine = engineStatus,
            retrieval = when {
                !vectorSearchAvailable -> "room_fts_keyword_only (embedding model not loaded)"
                identifierRouted -> "room_fts_identifier_routed (semantic excluded)"
                else -> "room_fts_plus_embeddinggemma_rrf"
            },
            keywordQuery = analyzed.keywordQuery,
            semanticQuery = analyzed.semanticQuery,
            results = hits.take(safeLimit),
            abstained = abstained,
            identifierRouted = identifierRouted,
            fieldFilters = filters,
        )
    }

    /**
     * 티어드 FTS 검색(검색 담당 브랜치 ymj/embedding-search-android-eval 이식):
     * 정확 구문(phrase) → 전체 단어 AND(allTerms) → 접두어 AND(prefix) → LIKE 폴백.
     * 순서대로 후보를 채워 나가고, 그 등장 순서를 그대로 랭킹으로 쓴다(재점수화하지 않음).
     * scripts/eval_search.py 오프라인 평가에서 기존 LIKE+커스텀 점수 방식보다
     * Recall@1 0.76→0.94, MRR 0.81→0.96로 뚜렷이 우수했다(특히 전화번호 조회 0.19→1.00).
     */
    private fun keywordHits(query: AnalyzedSearchQuery, limit: Int): List<CardSearchHit> {
        val ids = keywordIdsTiered(query, limit * 4)
        val ranked = if (ids.isEmpty() && query.keywordQuery.isBlank()) {
            store.allCards().map { it.id }
        } else {
            ids
        }
        return ranked
            .mapNotNull { store.findCard(it) }
            .take(limit)
            .mapIndexed { index, card ->
                CardSearchHit(
                    card = card,
                    score = (limit - index).toDouble(),
                    keywordRank = index + 1,
                    vectorRank = null,
                    similarity = 0f,
                )
            }
    }

    private fun keywordIdsTiered(query: AnalyzedSearchQuery, limit: Int): List<String> {
        val terms = ftsSafeTerms(query.keywordTokens)
        if (terms.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        ftsPhraseMatch(terms)?.let { out.addAll(safeFtsSearch(it, limit)) }
        if (out.size < limit) ftsAllTermsMatch(terms)?.let { out.addAll(safeFtsSearch(it, limit)) }
        if (out.size < limit) ftsPrefixMatch(terms)?.let { out.addAll(safeFtsSearch(it, limit)) }
        // LIKE 폴백에서만 동의어 확장 — "인공지능" 검색이 "AI" 태그 카드도 잡게.
        if (out.size < limit) expandSynonymsForFallback(terms).forEach { out.addAll(store.searchLikeIds("%$it%", limit)) }
        return out.take(limit)
    }

    private fun safeFtsSearch(match: String, limit: Int): List<String> =
        try {
            store.searchFtsIds(match, limit)
        } catch (_: Throwable) {
            emptyList()
        }

    private fun vectorScores(queryVector: FloatArray, limit: Int): List<Pair<String, Float>> =
        store.embeddingsForModel(embeddingModelKey)
            .mapNotNull { embedding ->
                val vector = embedding.vector.toFloatArray(embedding.dimensions)
                embedding.cardId to cosine(queryVector, vector)
            }
            .sortedByDescending { it.second }
            .take(limit)

    private fun rrf(rank: Int?): Double = if (rank == null) 0.0 else 1.0 / (RRF_K + rank)

    /** 번들된 시드 데이터(안드로이드: assets/cards/cards_seed.json)가 있으면 그걸로 채운다. */
    private fun seedFromAsset(): Boolean =
        try {
            seedSource.cardsSeedJson()?.let { importCardsJson(it) > 0 } ?: false
        } catch (_: Throwable) {
            false
        }

    /**
     * APK에 번들된 사전 계산 임베딩(assets/cards/cards_embeddings_*)이 있으면 DB에 채운다.
     * 라이브 임베딩 모델을 이 기기에서 못 띄워도 시맨틱 검색이 바로 동작하게 하기 위함 —
     * OCR로 새 카드가 들어오기 전까지 임시로 이 방식을 쓴다.
     */
    private fun seedEmbeddingsFromAsset() {
        try {
            val (idsJson, bytes) = seedSource.precomputedEmbeddings() ?: return
            val ids = JSONArray(idsJson)
            val dimensions = 768
            val vectorBytes = dimensions * 4
            val now = System.currentTimeMillis()
            for (i in 0 until ids.length()) {
                val cardId = ids.getString(i)
                val card = store.findCard(cardId) ?: continue
                val start = i * vectorBytes
                if (start + vectorBytes > bytes.size) break
                val blob = bytes.copyOfRange(start, start + vectorBytes)
                val hash = sha256(cardEmbeddingInput(card))
                store.upsertEmbedding(CardEmbeddingEntity(cardId, embeddingModelKey, blob, dimensions, hash, now))
            }
        } catch (_: Throwable) {
            // 번들된 사전 계산 벡터가 없거나 손상된 경우 — 검색은 키워드로, 벡터는 필요할 때 온디바이스로 계산됨
        }
    }

    private fun sampleCards(): List<BusinessCardEntity> = listOf(
        BusinessCardEntity(
            "C001", "김지원", "Jiwon Kim", "비전글로벌", "대표이사", "전략팀",
            "business", "서울", "010-0000-0001", "jiwon@example.com", "서울 강남구",
            "스타트업 투자와 파트너십 미팅에서 만난 대표", "대표, 투자, 파트너십",
            System.currentTimeMillis()
        ),
        BusinessCardEntity(
            "C002", "오성령", "Sungryung Oh", "코어AI", "AI 엔지니어", "AI 개발팀",
            "it", "판교", "010-0000-0002", "ai@example.com", "경기도 성남시",
            "EmbeddingGemma와 로컬 벡터 검색을 실험 중", "AI, 개발자, 임베딩, 검색",
            System.currentTimeMillis()
        ),
        BusinessCardEntity(
            "C003", "박민수", "Minsu Park", "네오팩토리", "품질 팀장", "제조혁신팀",
            "manufacturing", "부산", "010-0000-0003", "factory@example.com", "부산 해운대구",
            "스마트공장 품질 검사 프로젝트 담당", "제조, 품질, 스마트공장",
            System.currentTimeMillis()
        )
    )
}
