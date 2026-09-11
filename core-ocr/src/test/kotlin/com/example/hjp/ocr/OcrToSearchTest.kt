package com.example.hjp.ocr

import com.hjp.tool.contact.BusinessCardRecord
import com.hjp.tool.contact.BusinessCardRepository
import com.hjp.tool.contact.RyeongContactSearchBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCR 로 찍은 명함이 **검색에 걸리는지** 본다. 두 트랙이 실제로 이어졌다는 주장을 여기서 건다.
 *
 * 저장소는 인메모리 대역이다 — Room 없이 배선(인식 결과 → 레코드 → 저장 → 검색)만 본다.
 * 키워드 순위 자체는 기기에서 Room FTS4 가 매기고(앱의 BusinessCardKeywordIndex),
 * 여기서는 그게 없으므로 search-core 의 LIKE 폴백이 돈다 — 이 시험의 관심사는 순위가 아니라
 * "방금 찍은 명함이 검색 대상에 들어갔는가" 다.
 */
class OcrToSearchTest {

    private class InMemoryRepository : BusinessCardRepository {
        val cards = mutableListOf<BusinessCardRecord>()
        override suspend fun loadAll(): List<BusinessCardRecord> = cards.toList()
        override suspend fun getById(cardId: String): BusinessCardRecord? =
            cards.firstOrNull { it.id == cardId.trim() }
    }

    private fun fields(vararg pairs: Pair<String, String>): List<CardParser.Field> =
        pairs.map { (label, value) -> CardParser.Field("", label, value) }

    @Test
    fun `촬영한 명함이 이름으로 검색된다`() = runBlocking {
        val repository = InMemoryRepository()
        val backend = RyeongContactSearchBackend(repository)

        val card = OcrCardMapper.toCard(
            fields(
                "이름" to "한도윤",
                "회사" to "주식회사 마루전자",
                "직함" to "선임연구원",
                "주소" to "경기도 성남시 분당구 판교로 10",
                "휴대폰" to "010-2345-6789",
            ),
            id = "S001",
            updatedAtMillis = 1_700_000_000_000L,
        )
        repository.cards += card

        // 매핑이 검색에 필요한 칸을 채웠는지 — 지역은 주소 전체가 아니라 지역명이어야 한다.
        assertEquals("한도윤", card.name)
        assertEquals("경기도", card.location)
        assertEquals("010-2345-6789", card.phone)

        val found = backend.search("한도윤", 5).hits.map { it.card.id }
        assertTrue("방금 저장한 명함이 검색돼야 한다: $found", "S001" in found)
    }

    @Test
    fun `엔티티에 자리 없는 값도 메모로 남아 검색 대상이 된다`() = runBlocking {
        val repository = InMemoryRepository()
        val backend = RyeongContactSearchBackend(repository)

        val card = OcrCardMapper.toCard(
            fields("이름" to "나예솔", "회사" to "코비", "로고" to "코비하우스"),
            id = "S002",
            updatedAtMillis = 1L,
        )
        repository.cards += card
        assertTrue("로고명이 메모에 남아야 한다: ${card.memo}", card.memo.contains("코비하우스"))

        val found = backend.search("코비하우스", 5).hits.map { it.card.id }
        assertTrue("메모의 로고명으로도 찾아야 한다: $found", "S002" in found)
    }
}
