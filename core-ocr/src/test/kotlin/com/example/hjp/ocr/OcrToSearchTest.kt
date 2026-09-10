package com.example.hjp.ocr

import com.example.hjp.data.BusinessCardEntity
import com.example.hjp.data.CardEmbeddingEntity
import com.example.hjp.search.CardSearchService
import com.example.hjp.search.CardStore
import com.example.hjp.search.NoEmbeddingProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCR 로 찍은 명함이 **검색에 걸리는지** 본다. 두 트랙이 실제로 이어졌다는 주장을 여기서 건다.
 *
 * 저장소는 인메모리 대역이다 — Room/SQLite 없이 배선(매핑 → 저장 → 가제티어 무효화 → 검색)만
 * 검증한다. FTS 의미 자체는 기기·데스크톱이 같은 SQL 을 돌려 맞춘다.
 */
class OcrToSearchTest {

    private class InMemoryCardStore : CardStore {
        private val cards = LinkedHashMap<String, BusinessCardEntity>()
        private val embeddings = mutableListOf<CardEmbeddingEntity>()

        override fun countCards() = cards.size
        override fun allCards() = cards.values.sortedBy { it.name }
        override fun findCard(id: String) = cards[id]
        override fun upsertCards(cards: List<BusinessCardEntity>) {
            cards.forEach { this.cards[it.id] = it }
        }

        override fun replaceAllCards(cards: List<BusinessCardEntity>) {
            this.cards.clear()
            embeddings.clear()
            upsertCards(cards)
        }

        override fun clearFts() = Unit
        override fun rebuildFts() = Unit

        /** FTS 대역. 실제 MATCH 문법 대신 토큰 포함 여부만 본다 — 배선 검증에는 충분하다. */
        override fun searchFtsIds(matchQuery: String, limit: Int): List<String> {
            val terms = matchQuery.replace("\"", "").split(' ')
                .map { it.removeSuffix("*") }.filter { it.isNotBlank() }
            if (terms.isEmpty()) return emptyList()
            return cards.values
                .filter { card -> terms.all { it.lowercase() in card.searchableText() } }
                .map { it.id }
                .take(limit)
        }

        override fun searchLikeIds(like: String, limit: Int): List<String> {
            val needle = like.trim('%').lowercase()
            if (needle.isBlank()) return emptyList()
            return cards.values
                .filter { needle in it.searchableText() }
                .map { it.id }
                .take(limit)
        }

        override fun countEmbeddingsForModel(modelName: String) =
            embeddings.count { it.modelName == modelName }

        override fun findEmbedding(cardId: String, modelName: String, sourceHash: String) =
            embeddings.firstOrNull {
                it.cardId == cardId && it.modelName == modelName && it.sourceHash == sourceHash
            }

        override fun upsertEmbedding(embedding: CardEmbeddingEntity) {
            embeddings.removeAll { it.cardId == embedding.cardId && it.modelName == embedding.modelName }
            embeddings.add(embedding)
        }

        override fun embeddingsForModel(modelName: String) =
            embeddings.filter { it.modelName == modelName }
    }

    private fun service() = CardSearchService(
        store = InMemoryCardStore(),
        embeddingProviderFactory = { NoEmbeddingProvider },
    )

    /** 데스크톱 러너가 실제 명함에서 뽑아낸 필드. */
    private val recognized = listOf(
        CardParser.Field("👤", "이름", "김정"),
        CardParser.Field("💼", "직함", "본부장"),
        CardParser.Field("🏢", "회사", "캐피탈건설"),
        CardParser.Field("📞", "전화", "1588-4313"),
        CardParser.Field("✉️", "이메일", "jeonggim@example.net"),
        CardParser.Field("📍", "주소", "충청남도당진시합덕로130-25"),
        CardParser.Field("🔖", "로고", "코비하우스"),
    )

    @Test
    fun `촬영한 명함이 이름으로 검색된다`() {
        val search = service()
        val card = OcrCardMapper.toCard(recognized, search.nextOcrCardId(), 1L)
        search.addCard(card)

        val found = search.searchKeywordOnly("김정")
        assertTrue("검색 결과가 없다: ${found.retrieval}", found.results.isNotEmpty())
        assertEquals("김정", found.results.first().card.name)
    }

    @Test
    fun `회사명으로도 검색된다`() {
        val search = service()
        search.addCard(OcrCardMapper.toCard(recognized, search.nextOcrCardId(), 1L))

        val found = search.searchKeywordOnly("캐피탈건설")
        assertTrue(found.results.any { it.card.company == "캐피탈건설" })
    }

    @Test
    fun `저장 직후 전체 개수에 반영된다`() {
        val search = service()
        val before = search.totalCardCount()
        search.addCard(OcrCardMapper.toCard(recognized, search.nextOcrCardId(), 1L))
        assertEquals(before + 1, search.totalCardCount())
    }

    @Test
    fun `방금 저장한 카드가 최근 목록 맨 앞에 온다`() {
        val search = service()
        // 빈 저장소는 seedIfEmpty() 가 시드 카드를 '현재 시각'으로 채운다. 실제 촬영도
        // System.currentTimeMillis() 를 쓰므로 그 이후 시각이어야 앞에 온다.
        val now = System.currentTimeMillis()
        search.addCard(OcrCardMapper.toCard(recognized, search.nextOcrCardId(), now + 1_000))
        val newer = OcrCardMapper.toCard(
            listOf(CardParser.Field("👤", "이름", "박서준")),
            search.nextOcrCardId(),
            now + 2_000,
        )
        search.addCard(newer)

        assertEquals("박서준", search.recentCards(3).first().name)
    }

    @Test
    fun `카드 id 는 겹치지 않는다`() {
        val search = service()
        val first = search.nextOcrCardId()
        search.addCard(OcrCardMapper.toCard(recognized, first, 1L))
        val second = search.nextOcrCardId()

        assertTrue("두 번째 id 가 첫 번째와 같다: $second", first != second)
    }
}
