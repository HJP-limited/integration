package com.example.hjp.search

import com.example.hjp.data.BusinessCardEntity
import com.example.hjp.data.CardEmbeddingEntity

/**
 * 명함 저장소. [CardSearchService] 가 쓰는 질의만 노출한다.
 *
 * 안드로이드는 Room DAO 를, 데스크톱 러너는 같은 SQL 을 JDBC 로 실행하는 구현을 끼운다.
 * **두 구현은 같은 SQL 을 써야 한다** — FTS MATCH 의미를 코드로 흉내 내면 같은 질의가
 * 기기와 노트북에서 다른 결과를 내고, 그때부터 노트북 지표는 앱을 대변하지 못한다.
 */
interface CardStore {
    fun countCards(): Int

    fun allCards(): List<BusinessCardEntity>

    fun findCard(id: String): BusinessCardEntity?

    fun upsertCards(cards: List<BusinessCardEntity>)

    /** 명함·임베딩을 모두 지우고 새 목록으로 교체한다. */
    fun replaceAllCards(cards: List<BusinessCardEntity>)

    fun clearFts()

    fun rebuildFts()

    fun searchFtsIds(matchQuery: String, limit: Int): List<String>

    fun searchLikeIds(like: String, limit: Int): List<String>

    fun countEmbeddingsForModel(modelName: String): Int

    fun findEmbedding(cardId: String, modelName: String, sourceHash: String): CardEmbeddingEntity?

    fun upsertEmbedding(embedding: CardEmbeddingEntity)

    fun embeddingsForModel(modelName: String): List<CardEmbeddingEntity>
}

/**
 * 최초 실행 시 채울 번들 데이터. 안드로이드는 APK assets, 데스크톱은 파일에서 읽는다.
 */
interface SeedSource {
    /** `cards_seed.json` 내용. 없으면 null. */
    fun cardsSeedJson(): String?

    /**
     * 사전 계산 임베딩 — (id 배열 JSON, 벡터 blob). 없으면 null.
     *
     * 임베딩 모델이 안 뜨는 기기에서도 시맨틱 검색이 동작하게 하는 용도다.
     */
    fun precomputedEmbeddings(): Pair<String, ByteArray>?
}

/** 번들 데이터가 없는 환경(테스트, 빈 데스크톱 실행). */
object EmptySeedSource : SeedSource {
    override fun cardsSeedJson(): String? = null

    override fun precomputedEmbeddings(): Pair<String, ByteArray>? = null
}
