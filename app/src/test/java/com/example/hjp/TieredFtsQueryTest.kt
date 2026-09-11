package com.example.hjp

import com.example.hjp.data.TieredFtsQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TieredFtsQueryTest {
    @Test
    fun `honorific particles phone and unsafe operators are normalized safely`() {
        // 호칭을 뗀 형태와 **원본을 둘 다** 남긴다. 조사·호칭 목록은 손으로 적은 것이라 잘못
        // 뗄 수 있고, 원본이 사라지면 그 말이 통째로 검색에서 없어진다(실측: "정하은"의 은을
        // 조사로 보고 "정하"만 남아 정하은을 못 찾았다). 오려낸 조각이 추가될 뿐이다.
        assertEquals(listOf("강서연", "강서연씨"), TieredFtsQuery.analyze("강서연씨 찾아줘"))
        assertEquals(
            listOf("010-1234-4312".replace("-", "")),
            TieredFtsQuery.analyze("010-1234-4312").filter { it.all(Char::isDigit) },
        )
        val hostile = TieredFtsQuery.analyze("김지원 OR * NOT")
        assertFalse(hostile.any { it.uppercase() in setOf("OR", "NOT") })
    }

    @Test
    fun `tiers and like-only synonyms preserve latest Ryeong order`() {
        val terms = listOf("판교", "개발")
        assertEquals("\"판교 개발\"", TieredFtsQuery.phrase(terms))
        assertEquals("판교 개발", TieredFtsQuery.allTerms(terms))
        assertEquals("판교* 개발*", TieredFtsQuery.prefix(terms))
        val expanded = TieredFtsQuery.expandForLike(terms)
        assertEquals(listOf("판교", "개발"), expanded.take(2))
        assertTrue("엔지니어" in expanded)
        assertTrue("ai" in expanded)
    }
}
