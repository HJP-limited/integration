package com.hjp.tool.contact

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 공백 없이 붙여 쓴 한국어 질의.
 *
 * FTS4 의 unicode61 은 공백과 구두점에서만 자른다. 한국어는 붙여 써도 말이 되므로 "판교개발자"
 * 는 낱말 하나가 되고, 색인에는 "판교"(주소)와 "개발자"(직함)가 따로 들어 있어 어느 티어에서도
 * 맞지 않았다 — 실기기에서 키워드 0건으로 확인된 결함이다.
 *
 * 여기서 색인은 [SearchIndexText] 가 실제로 만드는 문자열을 쓴다. 시험이 자기만의 색인을 만들면
 * 정작 운영에서 무엇이 색인되는지는 확인하지 못한다.
 */
class GluedQueryTest {

    /** 합성 명함 한 장. 어떤 평가 픽스처에도 나오지 않는다. */
    private val indexed = SearchIndexText.build(
        name = "남다은",
        nameEn = "Nam Daeun",
        company = "비전글로벌",
        title = "개발자",
        department = "플랫폼팀",
        industry = "소프트웨어",
        location = "경기도 성남시 분당구",
        address = "경기도 성남시 분당구 판교역로 123",
        email = "daeun@example.invalid",
        website = "example.invalid",
        memo = "기술교류회에서 만남",
        tags = "AI 백엔드",
        phone = "031-000-0000",
        mobile = "010-0000-0000",
    )

    /** 색인 한 건 조회를 흉내낸다 — FTS 의 낱말 단위 일치와 같은 판정이다. */
    private val inIndex: suspend (String) -> Boolean = { term ->
        indexed.split(" ").any { it == term.lowercase() }
    }

    @Test
    fun `a glued query splits into the words the index actually holds`() = runBlocking {
        val terms = GluedTermSplitter.split(listOf("판교개발자"), inIndex)
        assertEquals(listOf("판교", "개발자"), terms)
    }

    @Test
    fun `the spaced and the glued form resolve to the same words`() = runBlocking {
        val glued = GluedTermSplitter.split(listOf("판교개발자"), inIndex)
        val spaced = GluedTermSplitter.split(listOf("판교", "개발자"), inIndex)
        assertEquals(spaced, glued)
    }

    @Test
    fun `a word that is genuinely in the index is never split`() = runBlocking {
        // "비전글로벌" 은 회사 이름 그대로다. 갈랐다면 "비전" 과 "글로벌" 을 따로 요구하게 되고,
        // 회사명을 정확히 친 질의가 오히려 약해진다.
        assertEquals(listOf("비전글로벌"), GluedTermSplitter.split(listOf("비전글로벌"), inIndex))
    }

    @Test
    fun `an unsplittable word is left alone`() = runBlocking {
        // 가를 자리를 못 찾으면 원본을 그대로 둔다 — LIKE 티어가 받는다.
        assertEquals(listOf("알수없는말"), GluedTermSplitter.split(listOf("알수없는말"), inIndex))
    }

    @Test
    fun `phone numbers and e-mail addresses are left to their own rules`() = runBlocking {
        val terms = listOf("01000000000", "daeun@example.invalid")
        assertEquals(terms, GluedTermSplitter.split(terms, inIndex))
    }

    // ---- 문서 쪽 바이그램 (ryeong BusinessCardEntity.hangulBigrams) -------------------------

    @Test
    fun `the index holds inner fragments of Korean words`() {
        // 접두어 색인은 낱말 앞쪽만 훑는다. "전글" 은 낱말 안쪽이라 바이그램이 없으면 못 찾는다.
        val tokens = indexed.split(" ")
        assertTrue("비전 missing from $indexed", tokens.contains("비전"))
        assertTrue("전글 missing from $indexed", tokens.contains("전글"))
        assertTrue("글로 missing from $indexed", tokens.contains("글로"))
    }

    @Test
    fun `two-syllable words add no bigrams of their own`() {
        // 자기 자신이 이미 색인에 있으므로 보탤 것이 없다.
        assertEquals("", SearchIndexText.hangulBigrams("판교"))
    }

    @Test
    fun `latin and digit tokens produce no bigrams`() {
        assertEquals("", SearchIndexText.hangulBigrams("example 01012345678"))
    }

    @Test
    fun `every field of the card reaches the index`() {
        // 주소·이메일이 빠져 "판교" 명함 9장이 키워드로 안 잡혔던 적이 있다.
        listOf("판교역로", "daeun@example.invalid", "기술교류회에서", "플랫폼팀", "01000000000")
            .forEach { assertTrue("$it missing from index", indexed.contains(it)) }
    }
}
