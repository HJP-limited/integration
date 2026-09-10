package com.example.hjp.search

import com.example.hjp.data.BusinessCardEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordSearchRankerTest {
    private val jiwon = card(
        id = "C001",
        name = "김지원",
        nameEn = "Jiwon Kim",
        company = "비전글로벌",
        title = "AI 개발자",
        department = "플랫폼팀",
        location = "판교",
        phone = "010-1234-5678",
        email = "jiwon@example.com",
        memo = "스타트업 파트너십 미팅",
        tags = "AI, 개발자, 판교",
    )

    private val minsu = card(
        id = "C002",
        name = "박민수",
        nameEn = "Minsu Park",
        company = "오토팩토리",
        title = "품질 관리자",
        department = "제조혁신팀",
        location = "부산",
        phone = "010-9999-1111",
        email = "factory@example.com",
        memo = "스마트공장 프로젝트 담당",
        tags = "제조, 품질",
    )

    @Test
    fun analyze_removes_command_words_but_keeps_search_terms() {
        val query = KeywordSearchRanker.analyze("판교에 있는 AI 개발자 찾아줘")

        assertTrue(query.keywordTokens.contains("판교에"))
        assertTrue(query.keywordTokens.contains("ai"))
        assertTrue(query.keywordTokens.contains("개발자"))
        assertTrue(query.ngramTokens.contains("개발"))
        assertTrue(query.ngramTokens.contains("발자"))
        assertTrue("찾아줘" !in query.keywordTokens)
    }

    @Test
    fun searchable_text_contains_normalized_and_bigram_terms() {
        val text = jiwon.searchableText()

        assertTrue(text.contains("비전글로벌"))
        assertTrue(text.contains("비전"))
        assertTrue(text.contains("전글"))
        assertTrue(text.contains("지원"))
        assertTrue(text.contains("010-1234-5678"))
        assertTrue(text.contains("jiwon@example.com"))
    }

    @Test
    fun partial_korean_query_scores_expected_card_higher() {
        val query = KeywordSearchRanker.analyze("지원")

        assertTrue(KeywordSearchRanker.score(jiwon, query) > 0.0)
        assertEquals(0.0, KeywordSearchRanker.score(minsu, query), 0.0)
    }

    @Test
    fun company_partial_query_uses_bigrams_without_field_weighting() {
        val query = KeywordSearchRanker.analyze("비전")

        assertTrue(KeywordSearchRanker.score(jiwon, query) > KeywordSearchRanker.score(minsu, query))
    }

    @Test
    fun spaced_and_unspaced_job_queries_match() {
        val spaced = KeywordSearchRanker.analyze("AI 개발자")
        val compact = KeywordSearchRanker.analyze("AI개발자")

        assertTrue(KeywordSearchRanker.score(jiwon, spaced) > 0.0)
        assertTrue(KeywordSearchRanker.score(jiwon, compact) > 0.0)
    }

    @Test
    fun phone_and_email_are_searchable() {
        assertTrue(KeywordSearchRanker.score(jiwon, KeywordSearchRanker.analyze("1234")) > 0.0)
        assertTrue(KeywordSearchRanker.score(jiwon, KeywordSearchRanker.analyze("example.com")) > 0.0)
    }

    private fun card(
        id: String,
        name: String,
        nameEn: String,
        company: String,
        title: String,
        department: String,
        location: String,
        phone: String,
        email: String,
        memo: String,
        tags: String,
    ) = BusinessCardEntity(
        id,
        name,
        nameEn,
        company,
        title,
        department,
        "business",
        location,
        phone,
        email,
        "$location 오피스",
        memo,
        tags,
        1L,
    )

    @Test
    fun `정정 말투의 이름을 추출한다`() {
        // "손서윤씨가 아니라 남다은씨야" 에서 새 이름이 안 잡히면 정정이 통째로 무시되고
        // focus 가 옛 대상에 머문다(실측: Final50 v3 정정 6/10 실패).
        val t = KeywordSearchRanker.analyze("정정할게. 손서윤씨가 아니라 남다은씨야. 그분 회사는 어디야?").keywordTokens
        assertTrue("남다은씨 미추출: $t", t.contains("남다은씨"))
        assertTrue("손서윤씨 미추출: $t", t.contains("손서윤씨"))
    }

    @Test
    fun `문장 끝 구두점이 붙어도 토큰이 나온다`() {
        assertTrue(KeywordSearchRanker.analyze("남다은씨야.").keywordTokens.contains("남다은씨"))
        assertTrue(KeywordSearchRanker.analyze("판교에는.").keywordTokens.contains("판교"))
    }

    @Test
    fun `이메일과 전화번호는 구두점 제거에 망가지지 않는다`() {
        // normalize 가 이메일 때문에 '.'을 남긴다. 끝에 붙은 것만 떼는 이유다.
        val e = KeywordSearchRanker.analyze("hong@abc.co.kr 알려줘").keywordTokens
        assertTrue("이메일 훼손: $e", e.contains("hong@abc.co.kr"))
        val p = KeywordSearchRanker.analyze("010-1234-5678").keywordTokens
        assertTrue("전화 훼손: $p", p.contains("010-1234-5678"))
        assertTrue("숫자 사본 없음: $p", p.contains("01012345678"))
    }
}
