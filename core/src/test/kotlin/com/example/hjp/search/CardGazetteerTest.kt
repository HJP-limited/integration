package com.example.hjp.search

import com.example.hjp.agent.ConversationalFollowup
import com.example.hjp.data.BusinessCardEntity
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 검색 규칙(기권 / 필드 필터 / 라우팅)이 파이썬 확인용 구현과 같은 판정을 내는지 본다.
 *
 * 기대값은 scripts/eval_search.py 와 로컬 챗 백엔드에서 실측한 결과와 동일하다.
 * 두 구현이 갈라지면 여기서 깨진다 — 앱이 평가와 다르게 동작하는 걸 막는 게 목적이다.
 */
class CardGazetteerTest {

    private val cards: List<BusinessCardEntity> = loadTestCards()
    private val gazetteer = CardGazetteer(cards)

    private fun analyze(q: String) = KeywordSearchRanker.analyze(q)

    private fun abstains(q: String, keywordHitCount: Int) =
        shouldAbstain(analyze(q), keywordHitCount, gazetteer)


    // ---- 기권: '도'로 끝나는 조사 붙은 일반어를 지명으로 오인하지 않는다 ----

    @Test
    fun `조사 도가 붙은 속성어를 없는 지명으로 보지 않는다`() {
        // 분석기는 조사를 뗀 형태와 원형을 **둘 다** 토큰으로 남긴다("이메일도", "이메일").
        // 원형이 '도로 끝나는 3자 이상' 조건에 걸려 없는 지역으로 판정되면서,
        // 실재하는 사람을 물었는데도 기권했다(실측: Final50 v3 benchmark 10/10 실패).
        val 있는이름 = cards.first().name
        listOf("회사와 이메일도 알려줘", "회사도 알려줘", "주소도", "부서도 알려줘").forEach {
            assertFalse(
                "'$있는이름 $it' 가 기권됨",
                abstains("$있는이름 $it", keywordHitCount = 1),
            )
        }
    }

    @Test
    fun `사람을 지목하지 않은 없는 지명은 그대로 기권한다`() {
        // 위 완화가 지역 기권 자체를 무력화하면 안 된다. 지목된 사람이 없으면 기존대로다.
        assertTrue(abstains("울릉도 근무자 찾아줘", keywordHitCount = 0))
        assertTrue(abstains("세종특별자치시에 있는 개발자", keywordHitCount = 0))
        assertTrue(abstains("백령도 사람 알려줘", keywordHitCount = 0))
    }

    // ---- 기권: 없는 이름 -------------------------------------------------

    @Test
    fun `호칭 붙은 없는 이름은 기권한다`() {
        assertTrue(abstains("정하은씨 어디서 만났더라", keywordHitCount = 0))
    }

    @Test
    fun `호칭 없는 맨 이름도 기권한다`() {
        // '정'(성) + '하은'(백하은에서) 조합이지만 명단에 '정하은'은 없다.
        assertTrue(abstains("정하은", keywordHitCount = 0))
        assertTrue(abstains("정하은 회사 어디야", keywordHitCount = 0))
    }

    @Test
    fun `있는 이름은 기권하지 않는다`() {
        assertFalse(abstains("안정우씨 어디서 만났더라", keywordHitCount = 1))
        assertFalse(abstains("안정우", keywordHitCount = 1))
        assertFalse(abstains("하채원 회사 어디야", keywordHitCount = 2))
    }

    @Test
    fun `일반 명사를 이름으로 오탐하지 않는다`() {
        // 성으로 시작하는 3자 일반어. 이걸 이름으로 잡으면 개념형 질의가 죽는다
        // (실측: 이 오탐 때문에 개념형 R@5 가 0.660 -> 0.630 으로 떨어졌었다).
        listOf("서커스 단장", "공무원 직급", "임원급 찾아줘", "돈 관리하는 사람", "연구하는 사람 찾아줘")
            .forEach { q ->
                assertFalse("'$q' 가 이름으로 오탐됨", abstains(q, keywordHitCount = 0))
            }
    }

    // ---- 기권: 없는 지역 / 식별자 ----------------------------------------

    @Test
    fun `없는 지역을 지목하면 기권한다`() {
        assertTrue(abstains("세종특별자치시 디자이너", keywordHitCount = 5))
    }

    @Test
    fun `군으로 끝나는 지역명을 사람 이름으로 오인하지 않는다`() {
        // '군'이 호칭 목록(씨·님·군·양)에 있어서 '음성군·평창군·울주군'이 "음성"+호칭"군"으로
        // 잡혔고, 명단에 없는 이름이라 정답이 있는 질의를 기권시켰다(실측 3건).
        val synthetic = listOf(
            card(id = "g1", name = "도가영", title = "프로덕트매니저",
                 address = "충청북도 음성군 산업로 1"),
            card(id = "g2", name = "홍예린", title = "상무",
                 address = "강원특별자치도 평창군 산업로 783"),
        )
        val gz = CardGazetteer(synthetic)
        listOf("음성군", "평창군").forEach {
            assertFalse("'$it' 가 사람 이름으로 오인됨", gz.looksLikePersonName(it))
        }
        assertFalse(
            "정답이 있는데 기권함",
            shouldAbstain(analyze("충청북도 음성군에 있는 프로덕트매니저 찾아줘"), 1, gz),
        )
    }

    // ---- 회사 필터: 질의에 회사를 대면 실제 조건으로 걸린다 ----

    private val twins = listOf(
        card(id = "c1", name = "백다인", title = "디렉터",
             company = "샤인기계", phone = "010-2975-8509"),
        card(id = "c2", name = "백다인", title = "과장",
             company = "유한회사 앰버", phone = "010-9717-8079"),
        card(id = "c3", name = "신민재", title = "상무", company = "주식회사 대성전자"),
    )

    @Test
    fun `회사 핵심어가 조건으로 걸려 동명이인을 가른다`() {
        // 이게 없을 때 "샤인기계 백다인씨"가 백다인 두 장을 다 가져왔고, 몇 턴 뒤
        // "백다인씨 전화번호"가 엉뚱한 쪽을 답했다(통합 벤치 v1.3 실패 3건).
        val gz = CardGazetteer(twins)
        val f = extractFieldFilters(analyze("샤인기계 백다인씨 직급 뭐야?"), gz)
        assertEquals(listOf("백다인"), f.names)
        assertEquals(listOf("샤인기계"), f.companies)
        assertEquals(listOf("c1"), applyFieldFilters(twins, f).map { it.id })
    }

    @Test
    fun `법인 표기 단독으로는 회사 조건이 되지 않는다`() {
        // "유한회사"는 160장이 공유한다. 이게 단독 조건이 되면 그 전부가 한 덩어리로 남는다.
        val gz = CardGazetteer(twins)
        val f = extractFieldFilters(analyze("유한회사 다니는 사람 찾아줘"), gz)
        assertTrue("법인 표기가 조건이 됐다: ${f.companies}", f.companies.isEmpty())
    }

    @Test
    fun `회사명과 함께 대면 법인 표기까지 조건이 된다`() {
        // 핵심어만 쓰면 "유한회사 경기전자"와 "주식회사 경기전자"가 한 덩어리가 되는데
        // 데이터에서 이 둘은 다른 회사다(핵심어가 겹치는 조합이 41조 있다).
        // 사용자가 법인 표기를 말했으면 그건 조건이지 군더더기가 아니다.
        val gz = CardGazetteer(twins)
        val f = extractFieldFilters(analyze("유한회사 앰버 다니는 백다인씨 주소는?"), gz)
        assertEquals(listOf("유한회사 앰버"), f.companies)
        assertEquals(listOf("c2"), applyFieldFilters(twins, f).map { it.id })
    }

    @Test
    fun `회사명을 안 가리고 부르면 후보를 좁히지 않는다`() {
        // "앰버 사람"은 앰버라는 이름을 쓰는 회사 전부가 맞다 — 사용자가 안 가렸으므로
        // 우리도 가리지 않는다. 여기서 한쪽을 고르면 그건 추측이다.
        val both = twins + card(id = "c4", name = "조은결", company = "주식회사 앰버")
        val gz = CardGazetteer(both)
        val f = extractFieldFilters(analyze("앰버 다니는 사람 찾아줘"), gz)
        assertEquals(listOf("앰버"), f.companies)
        assertEquals(listOf("c2", "c4"), applyFieldFilters(both, f).map { it.id })
    }

    @Test
    fun `회사 조건은 단어 단위로 맞춘다`() {
        // 부분 문자열이면 "대성전자"가 "대성"에 걸린다. 직함 매칭과 같은 원칙이다.
        val gz = CardGazetteer(twins)
        val f = FieldFilters(companies = listOf("대성"))
        assertTrue(applyFieldFilters(twins, f).isEmpty())
    }

    @Test
    fun `이름이 먼저 가져간 토큰은 회사가 되지 않는다`() {
        // 회사 핵심어가 사람 이름과 겹칠 수 있다. 우선순위를 뒤집으면 이름 검색이 깨진다.
        val overlap = listOf(
            card(id = "o1", name = "한선우", company = "한선우컴퍼니"),
            card(id = "o2", name = "김도윤", company = "다른회사"),
        )
        val gz = CardGazetteer(overlap)
        val f = extractFieldFilters(analyze("한선우씨 전화번호는?"), gz)
        assertEquals(listOf("한선우"), f.names)
        assertTrue("이름을 회사로도 잡았다: ${f.companies}", f.companies.isEmpty())
    }

    @Test
    fun `데이터에 없는 섬 이름도 기권한다`() {
        // '도'로 끝나는 3글자 지명(울릉도/백령도)을 지역으로 인정한다.
        // 이게 없으면 "울릉도 근무자"에 엉뚱한 5명이 그대로 떴다(실측).
        assertTrue(abstains("울릉도 근무자", keywordHitCount = 0))
        assertTrue(abstains("백령도에 있는 사람", keywordHitCount = 0))
    }

    @Test
    fun `있는 지역은 도로 끝나도 기권하지 않는다`() {
        // '경기도'처럼 데이터에 실재하는 지역은 regionExists 가 True 라 안 걸려야 한다.
        listOf("경기도에 있는 팀장 찾아줘", "충청남도에 있는 원장 찾아줘", "제주특별자치도 진료과장")
            .forEach { assertFalse("'$it' 가 기권됨", abstains(it, keywordHitCount = 3)) }
    }

    @Test
    fun `식별자는 매칭이 없을 때만 기권한다`() {
        assertTrue(abstains("010-0000-0000", keywordHitCount = 0))
        assertFalse(abstains("01030306030", keywordHitCount = 1))
    }

    @Test
    fun `뒷자리 질의를 지역으로 오인하지 않는다`() {
        // '뒷자리'가 '리'로 끝난다는 이유로 지역 오탐이 났던 회귀 케이스.
        assertFalse(abstains("번호 뒷자리 2033인 사람", keywordHitCount = 1))
    }

    // ---- 라우팅 ----------------------------------------------------------

    @Test
    fun `식별자 질의 판정`() {
        assertTrue(CardGazetteer.isIdentifierQuery("01030306030"))
        assertTrue(CardGazetteer.isIdentifierQuery("010-3030-6030"))
        assertTrue(CardGazetteer.isIdentifierQuery("chaewon00@corp.co.kr"))
        assertTrue(CardGazetteer.isIdentifierQuery("번호 뒷자리 4312인 분"))
        assertFalse(CardGazetteer.isIdentifierQuery("판교에 있는 디자이너"))
    }

    // ---- 필드 하드 필터 --------------------------------------------------

    @Test
    fun `지역을 지목하면 그 지역 사람만 남는다`() {
        val filters = extractFieldFilters(analyze("판교에서 일하는 사람 몇명이지?"), gazetteer)
        assertEquals(listOf("판교"), filters.locations)
        val kept = applyFieldFilters(cards, filters)
        assertTrue("판교 근무자가 있어야 함", kept.isNotEmpty())
        kept.forEach {
            assertTrue("${it.name} 은 판교가 아님", "판교" in (it.location + " " + it.address))
        }
    }

    @Test
    fun `이름을 지목하면 그 사람만 남는다`() {
        val filters = extractFieldFilters(analyze("안정우씨 어디서 만났더라"), gazetteer)
        assertEquals(listOf("안정우"), filters.names)
        val kept = applyFieldFilters(cards, filters)
        assertEquals(listOf("안정우"), kept.map { it.name }.distinct())
    }

    @Test
    fun `두 지역을 동시에 언급하면 OR로 둘 다 남는다`() {
        // "판교랑 강남 중에 사람 더 많은 곳이 어디야?" 같은 비교 질문 회귀 테스트.
        // AND였을 때는 두 지역에 동시에 속한 사람이 없어 무조건 0명이 되던 버그.
        val filters = extractFieldFilters(analyze("판교랑 강남 중에 사람 더 많은 곳이 어디야?"), gazetteer)
        assertEquals(listOf("강남", "판교"), filters.locations)
        val kept = applyFieldFilters(cards, filters)
        assertTrue("판교/강남 근무자가 있어야 함", kept.isNotEmpty())
        kept.forEach {
            val haystack = it.location + " " + it.address
            assertTrue("${it.name} 은 판교도 강남도 아님", "판교" in haystack || "강남" in haystack)
        }
        assertTrue("판교 근무자가 결과에 있어야 함", kept.any { "판교" in (it.location + " " + it.address) })
        assertTrue("강남 근무자가 결과에 있어야 함", kept.any { "강남" in (it.location + " " + it.address) })
    }

    @Test
    fun `동명이인은 모두 남는다`() {
        val filters = extractFieldFilters(analyze("하채원 회사 어디야"), gazetteer)
        val kept = applyFieldFilters(cards, filters)
        assertEquals(2, kept.size)
        assertEquals(setOf("하채원"), kept.map { it.name }.toSet())
    }

    @Test
    fun `직함만 물으면 지우지 않고 정확 일치자를 앞으로 정렬한다`() {
        // 삭제하면 시맨틱이 찾아낸 유사 직함('고문변호사')이 후보에서 사라지고,
        // 그냥 두면 RRF가 그걸 정확 일치자 위로 올린다(실측: 1위 정확일치 92/92 -> 86/92).
        // 그래서 지우지 않고 순서만 바로잡는다.
        val synthetic = listOf(
            card(id = "x1", name = "고문", title = "고문변호사"),
            card(id = "x2", name = "정변", title = "변호사"),
            card(id = "x3", name = "파변", title = "파트너 변호사"),
        )
        val gz = CardGazetteer(synthetic)
        val filters = extractFieldFilters(analyze("변호사 있나?"), gz)
        assertEquals(listOf("변호사"), filters.titles)

        val sorted = applyFieldFilters(synthetic, filters)
        // 아무도 삭제되지 않는다
        assertEquals(synthetic.size, sorted.size)
        // 정확 일치자('변호사' 단어 보유)가 앞으로, 합성 직함은 뒤로
        assertEquals(listOf("정변", "파변", "고문"), sorted.map { it.name })
    }

    @Test
    fun `직함과 지역이 겹치는 단어는 직함이 우선이다`() {
        // '상무'는 직함이면서 광주 '상무대로'의 조각이기도 하다. 지역으로 새면
        // 주소에 상무대로가 있는 엉뚱한 사람만 남고 진짜 상무들이 전부 잘린다.
        // 60장 테스트셋에는 이 충돌이 없어서(실측) 합성 데이터로 규칙 자체를 고정한다.
        val synthetic = listOf(
            card(id = "t1", name = "김상무", title = "상무", address = "서울특별시 강남구 테헤란로 1"),
            card(id = "t2", name = "이도로", title = "리드 디자이너", address = "광주광역시 서구 상무대로 389"),
        )
        val gz = CardGazetteer(synthetic)
        val filters = extractFieldFilters(analyze("상무 찾아줘"), gz)
        assertTrue("상무가 지역으로 오인됨", filters.locations.isEmpty())
        assertEquals(listOf("상무"), filters.titles)
    }

    @Test
    fun `지역과 직함이 함께 오면 직함 하드필터가 걸린다`() {
        // 복합 조건에서는 직함 필터가 실제 이득이다(지역만 맞고 직함 다른 사람 제거).
        val synthetic = listOf(
            card(id = "s1", name = "김상무", title = "상무", address = "경기도 성남시 분당구 판교역로 1"),
            card(id = "s2", name = "이과장", title = "과장", address = "경기도 성남시 분당구 판교역로 2"),
        )
        val gz = CardGazetteer(synthetic)
        val filters = extractFieldFilters(analyze("판교에 있는 상무 찾아줘"), gz)
        assertEquals(listOf("판교"), filters.locations)
        assertEquals(listOf("상무"), filters.titles)
        assertEquals(listOf("김상무"), applyFieldFilters(synthetic, filters).map { it.name })
    }

    @Test
    fun `없는 조합을 물으면 빈 결과를 돌려준다`() {
        // "판교에 있는 디자이너" — 판교 근무자 중 디자인 계열이 한 명도 없다.
        // 예전에는 직함 조건을 떼는 '완화'가 있어서 판교 9명을 그대로 넘겼고,
        // LLM 이 그중 주임 한 명을 디자이너인 양 답했다(실측 오답).
        // 없는 조합에는 없다고 답해야 하므로 빈 결과가 정답이다.
        val filters = extractFieldFilters(analyze("판교에 있는 디자이너 알려줘"), gazetteer)
        assertEquals(listOf("판교"), filters.locations)
        assertEquals(listOf("디자이너"), filters.titles)
        val strict = cards.filter { c ->
            "판교" in (c.location + " " + c.address) && "디자이너" in c.title.split(" ")
        }
        val kept = applyFieldFilters(cards, filters)
        assertEquals(strict.map { it.name }.toSet(), kept.map { it.name }.toSet())
        if (strict.isEmpty()) assertTrue("완화 없이 빈 결과여야 함", kept.isEmpty())
    }

    @Test
    fun `있는 조합은 완화 제거 후에도 그대로 찾는다`() {
        // 완화를 없앤 대가로 정상 조합까지 못 찾으면 안 된다.
        val target = cards.first { c ->
            "경기도" in (c.location + " " + c.address) && "상무" in c.title.split(" ")
        }
        val filters = extractFieldFilters(analyze("경기도에 있는 상무 찾아줘"), gazetteer)
        val kept = applyFieldFilters(cards, filters)
        assertTrue(kept.isNotEmpty())
        assertTrue(target.name in kept.map { it.name })
        kept.forEach {
            assertTrue("${it.name}: ${it.title}", "상무" in it.title.split(" "))
            assertTrue("${it.name}: ${it.address}", "경기도" in (it.location + " " + it.address))
        }
    }

    @Test
    fun `데이터에 없는 말로 물으면 필터가 걸리지 않는다`() {
        // '관리', '임원급'은 데이터의 이름·지역·직함 어디에도 그 단어로 존재하지 않는다.
        // 이런 개념형 질의는 필터 대신 시맨틱 검색이 처리해야 한다.
        listOf("돈 관리하는 사람 찾아줘", "임원급 찾아줘").forEach { q ->
            assertTrue("'$q' 에 필터가 걸림", extractFieldFilters(analyze(q), gazetteer).isEmpty)
        }
    }

    @Test
    fun `개념어라도 실제 직함 단어면 직함 조건으로 뽑는다`() {
        // 'AI'는 실제 직함('AI 개발자')의 단어이므로 직함 조건으로 잡힌다.
        // 다만 단독 조건이라 하드필터로는 적용하지 않는다(시맨틱 판단을 남겨 둔다).
        val filters = extractFieldFilters(analyze("AI 다루는 사람 있나"), gazetteer)
        assertEquals(listOf("ai"), filters.titles)
        assertTrue(filters.locations.isEmpty())
    }

    // ---- 조건 카운트(검색 top-N 컷을 안 거치는 별도 경로) --------------------

    @Test
    fun `지역 조건 카운트는 전체를 정확히 센다`() {
        // 검색(top-5 컷)이 아니라 전체를 세는지가 핵심이라, 개수는 데이터에서 직접 구해
        // 비교한다(데이터가 바뀌어도 테스트가 안 깨지게).
        val matched = countByKnownCondition("판교에 몇 명 있어?", gazetteer, cards)
        val bruteForce = cards.count { "판교" in (it.location + " " + it.address) }
        assertEquals(bruteForce, matched?.size)
        assertTrue("판교 근무자가 있어야 검증이 성립함", bruteForce > 5)
    }

    @Test
    fun `직함 조건 카운트는 부분문자열이 아니라 단어로 맞춘다`() {
        // "변호사"를 물었을 때 "고문변호사"까지 세면 안 된다(부분 문자열 매칭 오카운트 버그).
        val matched = countByKnownCondition("변호사 몇 명이야?", gazetteer, cards)
        val exact = cards.count { "변호사" in it.title.split(" ") }
        val compound = cards.count { it.title == "고문변호사" }
        assertTrue("검증에 합성 직함이 필요함", compound > 0)
        assertEquals(exact, matched?.size)
        matched?.forEach {
            assertTrue("${it.name}(${it.title})은 '변호사' 단어가 없음", "변호사" in it.title.split(" "))
        }
    }

    @Test
    fun `부서에만 있는 값도 셀 수 있다`() {
        // 부서 어휘가 없으면 이런 질의는 셀 수가 없어 검색(top-5 컷) 경로로 떨어진다.
        // 'AI개발팀'은 직함에는 없고 department 에만 있는 값이라 부서 어휘 없이는 못 센다.
        val matched = countByKnownCondition("AI개발팀 몇 명이야?", gazetteer, cards)
        val bruteForce = cards.count { "ai개발팀" in it.department.lowercase().replace(" ", "") }
        assertTrue("부서 AI개발팀 카드가 있어야 검증이 성립함", bruteForce > 0)
        assertEquals(bruteForce, matched?.size)
    }

    @Test
    fun `같은 토큰이 직함과 부서 양쪽에 있으면 두 조건을 모두 만족해야 한다`() {
        // 통합셋에서는 'ai'가 직함('AI 개발자')이면서 부서('AI개발팀')의 조각이기도 하다.
        // 이때 "AI 개발자 몇 명?"은 '직함이 AI 개발자이고 부서도 AI'인 사람만 센다.
        // (부서만 AI인 SRE 등은 제외 — 질의가 직함을 지목했으므로)
        // 데이터에 따라 숫자가 달라지므로 개수 대신 성질을 고정한다.
        val matched = countByKnownCondition("AI 개발자 몇 명이야?", gazetteer, cards)
        assertTrue(matched != null && matched.isNotEmpty())
        matched?.forEach {
            val titleWords = it.title.lowercase().split(" ")
            assertTrue("${it.name}: 직함 조건 불충족", "ai" in titleWords || "개발자" in titleWords)
            assertTrue("${it.name}: 부서 조건 불충족", "ai" in it.department.lowercase())
        }
    }

    @Test
    fun `부서 조건은 공용 필드필터에는 안 걸린다`() {
        // 부서는 카운트 경로 전용이다 — 공용 필터에 넣었다가 되돌린 이력이 있으므로
        // (204질의 발동 0건 = 미검증, 목표도 미해결) 여기로 새지 않는지 고정해 둔다.
        assertTrue(extractFieldFilters(analyze("AI개발팀 몇 명이야?"), gazetteer).isEmpty)
    }

    @Test
    fun `가제티어가 모르는 개념형 조건이면 null 로 폴백을 알린다`() {
        // "AI"는 지역·직함·이름 어디에도 없는 개념어 -> 검색+LLM 경로로 넘겨야 한다.
        assertEquals(null, countByKnownCondition("돈 관리하는 사람 몇 명이야?", gazetteer, cards))
    }

    // ---- 대화형 후속 발화 -------------------------------------------------

    @Test
    fun `정정과 복수지시는 후속 발화로 본다`() {
        listOf("5명인데?", "아닌데", "그 사람들 이름 알려줘", "그분들 회사 알려줘", "다시 찾아줘")
            .forEach { assertTrue("'$it' 가 후속으로 안 잡힘", ConversationalFollowup.isFollowup(it)) }
    }

    @Test
    fun `일반 검색 질의는 후속 발화가 아니다`() {
        listOf("판교에 있는 디자이너", "돈 관리하는 사람 찾아줘", "안정우씨 어디서 만났더라", "AI 개발자 찾아줘")
            .forEach { assertFalse("'$it' 가 후속으로 오판됨", ConversationalFollowup.isFollowup(it)) }
    }

    // 세션(멀티턴) 테스트는 agent.AgentSessionTest 로 옮겼다 — 이 파일은 검색 전용.

    private companion object {
        /** 규칙 자체를 고정하고 싶을 때 쓰는 합성 명함(데이터셋 우연에 기대지 않게). */
        fun card(
            id: String,
            name: String,
            title: String = "",
            address: String = "",
            location: String = "",
            department: String = "",
            company: String = "",
            phone: String = "",
        ): BusinessCardEntity = BusinessCardEntity(
            id, name, "", company, title, department, "", location, phone, "", address, "", "", 0L,
        )

        /**
         * 단위 테스트의 작업 디렉터리는 :app 모듈 폴더다. 저장소 루트의 평가 데이터셋을 읽는다.
         *
         * 평가(`scripts/eval_search.py --dataset eval1000`)와 **같은 셋**을 쓴다.
         * 예전에는 60장 통제셋을 따로 썼는데, 그 셋은 필드 분포가 프로덕션과 달라서
         * (예: 'AI'가 60장은 직함에, 5000장은 부서에만 있음) 소규모셋 결과를 일반화하다
         * 잘못된 결론을 낸 적이 있다. 통합셋은 실전 분포와 기권 검증용 통제 시나리오
         * (세종특별자치시·정하은을 일부러 비움)를 함께 담아 그 괴리를 없앤다.
         * 데이터 재생성: `python scripts/build_eval_dataset.py`
         */
        fun loadTestCards(): List<BusinessCardEntity> {
            val file = File("../data/cards_eval1000.json")
            require(file.exists()) { "테스트 명함 파일을 찾을 수 없음: ${file.absolutePath}" }
            val array = JSONArray(file.readText(Charsets.UTF_8))
            return (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                BusinessCardEntity(
                    o.optString("id"), o.optString("name"), o.optString("nameEn"),
                    o.optString("company"), o.optString("title"), o.optString("department"),
                    o.optString("industry"), o.optString("location"), o.optString("phone"),
                    o.optString("email"), o.optString("address"), o.optString("memo"),
                    o.optString("tags"), 0L,
                )
            }
        }
    }
}
