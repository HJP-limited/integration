package com.example.hjp

import com.example.hjp.agent.ConversationalFollowup
import com.example.hjp.data.BusinessCardEntity
import com.example.hjp.search.CardSearchHit
import com.example.hjp.search.CardSearchResponse
import com.example.hjp.search.FieldFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 스트레스 테스트로 찾은 결정적 우회/거절감지 로직이 파이썬 확인용 구현과 같은 판정을
 * 내는지 본다. 기대값은 hybrid_server.py 로 실측한 결과와 동일하다.
 */
class TurnLogicTest {

    // ---- 자기참조 질문 -----------------------------------------------------

    @Test
    fun `자기참조 질문을 감지한다`() {
        listOf("너는 누구야?", "너 뭐야", "당신은 누구세요?", "너 몇 살이야?", "너는 AI야?")
            .forEach { assertTrue("'$it' 가 자기참조로 안 잡힘", isSelfReferenceQuestion(it)) }
    }

    @Test
    fun `명함 검색 질문은 자기참조가 아니다`() {
        listOf("안정우씨 어디서 만났더라", "판교에 있는 디자이너 알려줘", "김철수 전화번호 알려줘")
            .forEach { assertFalse("'$it' 가 자기참조로 오판됨", isSelfReferenceQuestion(it)) }
    }

    @Test
    fun `빈 질문은 자기참조가 아니다`() {
        assertFalse(isSelfReferenceQuestion(""))
        assertFalse(isSelfReferenceQuestion("   "))
    }

    // ---- 전체 개수 질문 -----------------------------------------------------

    @Test
    fun `조건 없는 전체 질문을 감지한다`() {
        listOf("전체 명함이 몇 장이야?", "이름 목록 다 보여줘", "명함 전부 알려줘", "모두 보여줘")
            .forEach { assertTrue("'$it' 가 전체질문으로 안 잡힘", isUnfilteredListAllQuestion(it)) }
    }

    @Test
    fun `직급 이름이 전체와 겹쳐도 오탐하지 않는다`() {
        // "전부"/"몇" 등이 들어 있어도 구체적 조건(직급)이 남으면 전체질문이 아니다.
        listOf(
            "과장 전부 알려줘", "부장 목록 보여줘", "디자이너 전부 알려줘",
            "판교에서 일하는 사람 몇명이지?", "판교에 총 몇명이야?",
        ).forEach { assertFalse("'$it' 가 전체질문으로 오판됨", isUnfilteredListAllQuestion(it)) }
    }

    @Test
    fun `전체 모두 없이도 총과 몇이 같이 있으면 전체질문으로 본다`() {
        // "전체 명함이 몇 장이야?" 류 우회가 이 문구는 못 타서 "총 5명"으로 잘못 답했었다
        // (top-5를 전체로 착각). "총"+"몇" 신호를 추가해서 잡는다(회귀 재발 방지).
        listOf(
            "등록된 사람 총 몇 명이야?", "총 몇 명이야?", "등록된 명함 총 몇 개야?",
            "지금 내가 가진 명함 몇개야?", "현재 명함은 몇 장이야?",
        )
            .forEach { assertTrue("'$it' 가 전체질문으로 안 잡힘", isUnfilteredListAllQuestion(it)) }
    }

    // ---- 조건 있는 카운트 질문 ------------------------------------------------

    @Test
    fun `조건 있는 카운트 질문을 감지한다`() {
        listOf("판교에 몇 명 있어?", "이사 직급 몇 명이야?", "AI 개발하는 사람 몇 명이야?")
            .forEach { assertTrue("'$it' 가 조건카운트로 안 잡힘", isFilteredCountQuestion(it)) }
    }

    @Test
    fun `조건 없는 전체질문은 조건카운트가 아니다`() {
        // 이쪽은 총원수 우회(isUnfilteredListAllQuestion)가 먼저 처리해야 한다.
        listOf("등록된 사람 총 몇 명이야?", "전체 명함이 몇 장이야?")
            .forEach { assertFalse("'$it' 가 조건카운트로 오판됨", isFilteredCountQuestion(it)) }
    }

    @Test
    fun `개수를 안 묻는 질문은 조건카운트가 아니다`() {
        listOf("안정우 전화번호 뭐야?", "판교에 있는 디자이너 알려줘", "")
            .forEach { assertFalse("'$it' 가 조건카운트로 오판됨", isFilteredCountQuestion(it)) }
    }

    @Test
    fun `전체 신호 단어가 아예 없으면 전체질문이 아니다`() {
        assertFalse(isUnfilteredListAllQuestion("안정우씨 어디서 만났더라"))
        assertFalse(isUnfilteredListAllQuestion(""))
    }

    // ---- 거절 문구 감지 확장(REJECTION_MARKERS) ------------------------------

    @Test
    fun `고정 문구 외의 거절 말투도 감지한다`() {
        assertTrue(REJECTION_MARKERS.any { it in "김철수 전화번호는 찾지 못했습니다." })
        assertTrue(REJECTION_MARKERS.any { it in "해당 조건을 찾을 수 없습니다." })
    }

    @Test
    fun `필드가 없다는 정상 답변은 거절로 오인하지 않는다`() {
        // "없습니다" 단독은 REJECTION_MARKERS 에 없다 — 존재하는 사람의 빈 필드 답변까지
        // 거절로 잘못 인식하면 카드가 지워진다.
        assertFalse(REJECTION_MARKERS.any { it in "안정우는 나이가 없습니다." })
    }

    @Test
    fun `속성을 생략한 후속은 직전 턴의 속성을 이어받는다`() {
        // 실측(pass^3 3회 모두 재현): "○○씨 회사가 어디야?" 다음 "음가영씨는?" 에
        // 회사가 아니라 이름만 "음가영"으로 답했다. 검색은 맞는데 무엇을 물었는지가 빠졌다.
        assertEquals("음가영씨 회사", carryOverAttribute("음가영씨는?", "회사"))
        assertEquals("음가영 회사", carryOverAttribute("음가영은?", "회사"))
        assertEquals("하채원씨 부서", carryOverAttribute("하채원씨는?", "부서"))
    }

    @Test
    fun `속성이 이미 있거나 이어받을 게 없으면 질문을 그대로 둔다`() {
        // 속성 명사가 이미 있으면 손대지 않는다 — 반대 방향 생략은 resolveSearchQuery 담당.
        assertEquals("주소는?", carryOverAttribute("주소는?", "회사"))
        assertEquals("음가영씨 직급은?", carryOverAttribute("음가영씨 직급은?", "회사"))
        // 이어받을 속성이 없으면 그대로.
        assertEquals("음가영씨는?", carryOverAttribute("음가영씨는?", null))
        // 문장형 질문은 대상이 아니다.
        assertEquals("판교에 있는 디자이너 찾아줘", carryOverAttribute("판교에 있는 디자이너 찾아줘", "회사"))
        assertEquals("그 사람들 회사 알려줘", carryOverAttribute("그 사람들 회사 알려줘", "주소"))
    }

    @Test
    fun `그중에 로 좁히면 앞 턴 조건을 이어받는다`() {
        // 실측: "대전에 있는 사람 찾아줘" -> "그중에 변호사만" 에서 지역이 사라져
        // 전국 변호사가 나왔다. 앞 턴 조건어를 앞에 붙여 필터 추출이 합치게 한다.
        assertEquals("대전 그중에 변호사만", applyNarrowing("그중에 변호사만", "대전"))
        assertEquals("대전 변호사 그중 부장급은?", applyNarrowing("그중 부장급은?", "대전 변호사"))
    }

    @Test
    fun `좁히기 표현이 없거나 이어받을 조건이 없으면 그대로 둔다`() {
        assertEquals("변호사 찾아줘", applyNarrowing("변호사 찾아줘", "대전"))
        assertEquals("그중에 변호사만", applyNarrowing("그중에 변호사만", null))
        assertEquals("그중에 변호사만", applyNarrowing("그중에 변호사만", ""))
    }

    @Test
    fun `attributeOf 는 질문이 물어본 속성을 뽑는다`() {
        assertEquals("회사", attributeOf("음가영씨 회사가 어디야?"))
        assertEquals("부서", attributeOf("부서는?"))
        assertNull(attributeOf("판교에 있는 디자이너 찾아줘"))
    }

    @Test
    fun `narrowByAnswer 는 거절 답변이면 카드를 모두 비운다`() {
        val search = sampleSearchResponse(listOf("안정우"))
        val (narrowed, dropped) = narrowByAnswer(search, "김철수 전화번호는 찾지 못했습니다.")
        assertTrue(narrowed.results.isEmpty())
        assertEquals(listOf("안정우"), dropped)
    }

    @Test
    fun `narrowByAnswer 는 언급된 사람만 남긴다`() {
        val search = sampleSearchResponse(listOf("안정우", "하채원"))
        val (narrowed, dropped) = narrowByAnswer(search, "안정우의 회사는 그레이스패션입니다.")
        assertEquals(listOf("안정우"), narrowed.results.map { it.card.name })
        assertEquals(listOf("하채원"), dropped)
    }

    @Test
    fun `narrowByAnswer 는 이름으로 특정된 한 사람은 짧은 답변에도 남긴다`() {
        // 실측 회귀(멀티턴): "손다은씨 회사가 어디야?" 로 한 사람이 특정된 뒤
        // "직급은?" -> "AI 개발자", "부서는?" -> "데이터사이언스팀" 처럼 필드 값만
        // 짧게 답하면 이름·회사·주소가 답변에 없어서 카드가 통째로 사라졌다.
        // 후보가 한 명이면 '누구를 가리키는지' 고를 게 없으므로 그대로 둔다.
        val search = sampleSearchResponse(listOf("안정우"))
            .copy(fieldFilters = FieldFilters(names = listOf("안정우")))
        for (answer in listOf("AI 개발자", "데이터사이언스팀", "기술교류회에서 만났습니다.")) {
            val (narrowed, dropped) = narrowByAnswer(search, answer)
            assertEquals("답변='$answer'", listOf("안정우"), narrowed.results.map { it.card.name })
            assertTrue(dropped.isEmpty())
        }
    }

    @Test
    fun `narrowByAnswer 는 하드 필터를 통과한 카드는 답변이 일부만 말해도 지우지 않는다`() {
        // 실기기 실측: "대전에 있는 변호사 찾아줘" 에 검색은 2명을 맞게 찾았는데 답변이
        // 한 명만 말해서 나머지가 잘렸다. 그 상태로 "두 번째 사람 연락처" 를 물으면
        // 두 번째가 아예 없다. 필드 조건이 걸렸다는 건 검색이 이미 조건을 만족하는
        // 사람만 남겼다는 뜻이라, 그 카드들은 정의상 답이다.
        //
        // 예전에는 이 경우에도 비웠다("동명이인 2명 중 누구인지 안 가리키면 비운다").
        // 무관한 카드를 막는 게 목적이었는데, 그건 **조건이 없는** 질의에서만 필요하다.
        val filtered = sampleSearchResponse(listOf("탁예린", "방우성"))
            .copy(fieldFilters = FieldFilters(locations = listOf("대전"), titles = listOf("변호사")))
        val (kept, dropped) = narrowByAnswer(filtered, "탁예린")
        assertEquals(listOf("탁예린", "방우성"), kept.results.map { it.card.name })
        assertTrue(dropped.isEmpty())

        // 동명이인도 마찬가지 — 이름 조건이 걸렸으면 둘 다 남긴다.
        val dup = sampleSearchResponse(listOf("하채원", "하채원"))
            .copy(fieldFilters = FieldFilters(names = listOf("하채원")))
        assertEquals(2, narrowByAnswer(dup, "오늘은 화요일입니다.").first.results.size)
    }

    @Test
    fun `narrowByAnswer 는 이름 조건 없이 한 명이면 무관한 답변에 카드를 비운다`() {
        // 지역·직함으로만 좁혀져 우연히 1명이 남은 경우까지 살려두면
        // "오늘 날씨 어때?" 에 엉뚱한 명함이 뜬다.
        val search = sampleSearchResponse(listOf("안정우"))
        val (narrowed, _) = narrowByAnswer(search, "오늘은 화요일입니다.")
        assertTrue(narrowed.results.isEmpty())
    }

    @Test
    fun `narrowByAnswer 는 총 N명 집계형 답변이면 원본을 유지한다`() {
        // 이름을 안 대는 게 이 형식의 정상 동작이다(프롬프트 규칙 3번) — 카드까지
        // 비우면 안 된다.
        val search = sampleSearchResponse(listOf("정철수", "음수진", "김민재"))
        val (narrowed, dropped) = narrowByAnswer(search, "총 3명")
        assertEquals(listOf("정철수", "음수진", "김민재"), narrowed.results.map { it.card.name })
        assertTrue(dropped.isEmpty())
    }

    @Test
    fun `narrowByAnswer 는 이름 없이 회사명으로만 답해도 카드를 남긴다`() {
        // 회귀 방지: "문선영씨 회사가 어디야?" -> "주식회사 노블어패럴입니다." 처럼
        // 필드 값만 답하는 게 정상인 질문이 많은데, 이름이 없다고 카드를 지워버렸었다.
        val search = sampleSearchResponse(listOf("문선영", "차우주"))
        val (narrowed, dropped) = narrowByAnswer(search, "주식회사 노블어패럴0입니다.")
        assertEquals(listOf("문선영"), narrowed.results.map { it.card.name })
        assertEquals(listOf("차우주"), dropped)
    }

    @Test
    fun `narrowByAnswer 는 전화 뒷자리로만 답해도 카드를 남긴다`() {
        // "전화번호 뒤 4자리" -> "2069" 같은 답변(이름도 필드명도 없음).
        val search = sampleSearchResponse(listOf("문선영"))
        val (narrowed, _) = narrowByAnswer(search, "2000")
        assertEquals(listOf("문선영"), narrowed.results.map { it.card.name })
    }

    @Test
    fun `narrowByAnswer 는 주소로만 답해도 카드를 남긴다`() {
        val search = sampleSearchResponse(listOf("문선영"))
        val (narrowed, _) = narrowByAnswer(search, "서울특별시 반포대로 0")
        assertEquals(listOf("문선영"), narrowed.results.map { it.card.name })
    }

    @Test
    fun `narrowByAnswer 는 LLM이 주소 괄호를 생략해도 카드를 남긴다`() {
        // 실측: 카드가 "강원도 당진시 반포대2로 67 (승현이김리)" 인데 LLM 은 괄호를 뺀
        // "강원도 당진시 반포대2로 67" 로 답해서 값이 안 맞아 카드가 지워졌다.
        val card = BusinessCardEntity(
            "a1", "문선영", "", "주식회사 노블어패럴", "직급", "부서", "산업", "지역",
            "010-9322-2069", "m@corp.kr", "강원도 당진시 반포대2로 67 (승현이김리)", "", "", 0L,
        )
        val search = CardSearchResponse(
            query = "q", engine = "test", retrieval = "keyword",
            keywordQuery = "q", semanticQuery = "q",
            results = listOf(CardSearchHit(card, 1.0, 0, null, 0f)),
        )
        val (narrowed, _) = narrowByAnswer(search, "강원도 당진시 반포대2로 67")
        assertEquals(listOf("문선영"), narrowed.results.map { it.card.name })
    }

    @Test
    fun `narrowByAnswer 는 집계 수가 후보보다 적으면 그 수만큼만 남긴다`() {
        // 실측: "AI 개발하는 사람 찾아줘" -> '총 2명' 인데 카드 5장이 떴다.
        // 정렬이 정확 일치를 앞에 두므로 상위 N개가 그 N명이다.
        val search = sampleSearchResponse(listOf("어은지", "황보미영", "선지아", "차우주", "문선영"))
        val (narrowed, dropped) = narrowByAnswer(search, "총 2명")
        assertEquals(listOf("어은지", "황보미영"), narrowed.results.map { it.card.name })
        assertEquals(listOf("선지아", "차우주", "문선영"), dropped)
    }

    @Test
    fun `narrowByAnswer 는 집계 수가 후보와 같거나 크면 그대로 둔다`() {
        val search = sampleSearchResponse(listOf("가", "나", "다"))
        assertEquals(3, narrowByAnswer(search, "총 3명").first.results.size)
        assertEquals(3, narrowByAnswer(search, "총 9명").first.results.size)
    }

    @Test
    fun `narrowByAnswer 는 총 0명이면 카드를 비운다`() {
        // "총 0명"은 숫자로 표현된 거절이다. 집계형이라고 카드를 살려두면
        // "총 0명"이라 답하면서 명함 5장이 뜨는 모순이 된다.
        // 실측: "울릉도 근무자"(그 지역 없음) -> 답변 '총 0명', 카드 5장.
        val search = sampleSearchResponse(listOf("지하윤", "김여름", "손선우"))
        val (narrowed, dropped) = narrowByAnswer(search, "총 0명")
        assertTrue(narrowed.results.isEmpty())
        assertEquals(listOf("지하윤", "김여름", "손선우"), dropped)
    }

    @Test
    fun `narrowByAnswer 는 총 0명이 아닌 집계형은 그대로 둔다`() {
        // 경계값 확인 — 0 만 거절로 보고 10·20 같은 값은 정상 집계로 남긴다.
        listOf("총 10명", "총 20 명").forEach { answer ->
            val search = sampleSearchResponse(listOf("가", "나"))
            val (narrowed, dropped) = narrowByAnswer(search, answer)
            assertEquals("'$answer' 가 잘못 비워짐", 2, narrowed.results.size)
            assertTrue(dropped.isEmpty())
        }
    }

    @Test
    fun `narrowByAnswer 는 이름도 총N명 형식도 없으면 명함과 무관한 답변으로 보고 카드를 비운다`() {
        // 실측: "오늘 날씨 어때?" -> "날씨 정보는 명함 컨텍스트에 포함되어 있지 않습니다."
        // 인데 무관한 후보 카드가 그대로 남아있던 버그.
        val search = sampleSearchResponse(listOf("모정", "설우진", "이혜린"))
        val (narrowed, dropped) = narrowByAnswer(search, "날씨 정보는 명함 컨텍스트에 포함되어 있지 않습니다.")
        assertTrue(narrowed.results.isEmpty())
        assertEquals(listOf("모정", "설우진", "이혜린"), dropped)
    }

    @Test
    fun `narrowByAnswer 는 존재하지 않는 이름에 대한 의도 확인 답변에도 카드를 비운다`() {
        // 실측: "홍길동 명함 삭제해 줘"(존재 안 하는 이름) -> "홍길동 명함 삭제를
        // 요청하셨습니다." 인데 엉뚱한 홍씨 후보 카드가 그대로 남아있던 버그. "홍길동"이
        // 답변에 등장하지만 후보 목록엔 없으니 mentioned 는 여전히 비어 있다.
        val search = sampleSearchResponse(listOf("홍지우", "홍정민"))
        val (narrowed, dropped) = narrowByAnswer(search, "홍길동 명함 삭제를 요청하셨습니다.")
        assertTrue(narrowed.results.isEmpty())
        assertEquals(listOf("홍지우", "홍정민"), dropped)
    }

    /**
     * 각 카드에 서로 다른 회사/주소/전화를 준다 — 필드 기반 매칭을 검증할 수 있게.
     * 회사·주소는 이름을 포함하지 않는다(이름 매칭과 필드 매칭을 분리해서 보려고).
     */
    // ---- 동명이인 되부르기 ----

    // 동명이인 시험용 카드 — 8/26 본의 cardOf 는 id·이름을 받지 않아 직접 만든다.
    private fun twinCard(id: String, name: String, company: String) =
        BusinessCardEntity(
            id, name, "", company, "", "", "", "", "", "", "", "", "", 0L,
        )

    private fun twinResponse(names: List<String>) = CardSearchResponse(
        query = "q", engine = "test", retrieval = "keyword",
        keywordQuery = "q", semanticQuery = "q",
        fieldFilters = FieldFilters(names = listOf("백다인")),
        results = listOf(
            CardSearchHit(twinCard("c1", "백다인", "샤인기계"), 1.0, 0, null, 0f),
            CardSearchHit(twinCard("c2", "백다인", "앰버"), 0.9, 1, null, 0f),
        ).filter { it.card.id in names },
    )

    @Test
    fun `앞에서 정해 둔 동명이인만 남긴다`() {
        // 회사로 한 명을 특정한 뒤 몇 턴 지나 이름만으로 다시 부르는 경우다. focus 도
        // 직전 카드 id 도 그새 방해 인물로 덮여서, 이 기억이 없으면 어느 쪽인지 모른다
        // (통합 벤치 v1.3 실패 3건이 전부 이 모양이었고 되묻지도 않고 틀린 값을 줬다).
        val search = twinResponse(listOf("c1", "c2"))
        val pinned = pinAmbiguousTwin(search, "백다인=c2")
        assertEquals(listOf("c2"), pinned.results.map { it.card.id })
    }

    @Test
    fun `정해 둔 적이 없으면 후보를 그대로 둔다`() {
        // 애매한 채로 지나간 대화를 확신으로 둔갑시키지 않는다.
        val search = twinResponse(listOf("c1", "c2"))
        assertEquals(listOf("c1", "c2"), pinAmbiguousTwin(search, null).results.map { it.card.id })
        assertEquals(listOf("c1", "c2"),
            pinAmbiguousTwin(search, "신민재=c9").results.map { it.card.id })
    }

    @Test
    fun `후보가 한 장뿐이면 건드리지 않는다`() {
        val search = twinResponse(listOf("c1"))
        assertEquals(listOf("c1"), pinAmbiguousTwin(search, "백다인=c2").results.map { it.card.id })
    }

    @Test
    fun `기억은 같은 이름이 다시 확정되면 최신으로 덮는다`() {
        // 대화 중에 사용자가 다른 쪽으로 옮겨갈 수 있고, 그때는 최근 확정이 맞다.
        val once = appendSubjectCard(null, "백다인", "c1")
        assertEquals("백다인=c1", once)
        val twice = appendSubjectCard(once, "백다인", "c2")
        assertEquals("백다인=c2", twice)
        val other = appendSubjectCard(twice, "신민재", "c9")
        assertEquals("백다인=c2,신민재=c9", other)
        assertEquals("c2", subjectCardFor(other, "백다인"))
        assertEquals("c9", subjectCardFor(other, "신민재"))
        assertNull(subjectCardFor(other, "남다은"))
        assertNull(subjectCardFor(null, "백다인"))
    }

    private fun sampleSearchResponse(names: List<String>): CardSearchResponse {
        val hits = names.mapIndexed { i, name ->
            CardSearchHit(
                card = BusinessCardEntity(
                    "id$i", name, "", "주식회사 노블어패럴$i", "직급", "부서",
                    "산업", "지역", "010-1000-200$i", "$name@corp.co.kr",
                    "서울특별시 반포대로 $i", "", "", 0L,
                ),
                score = 1.0,
                keywordRank = i,
                vectorRank = null,
                similarity = 0f,
            )
        }
        return CardSearchResponse(
            query = "q", engine = "test", retrieval = "keyword",
            keywordQuery = "q", semanticQuery = "q", results = hits,
        )
    }

    // ---- 기능 질문("뭐 할 줄 알아?") 우회 ----
    // 전량 채점에서 이 질문이 검색으로 새서 컨텍스트 1등 이름을 답하고 카드까지 띄웠다.

    @Test
    fun `기능 질문을 잡아낸다`() {
        listOf(
            "너 뭐 할 줄 알아?", "뭘 할 줄 아니", "무엇을 할 수 있어?",
            "할 수 있는 게 뭐야", "어떤 기능 있어?", "기능이 뭐야",
        ).forEach { assertTrue(it, isCapabilityQuestion(it)) }
    }

    @Test
    fun `명함 질문을 기능 질문으로 오인하지 않는다`() {
        listOf(
            "판교에 있는 개발자 찾아줘", "김서영씨 전화번호", "대전에 몇 명이야?",
            "그 사람 부서는?", "돈 관리하는 사람 찾아줘",
        ).forEach { assertFalse(it, isCapabilityQuestion(it)) }
    }

    @Test
    fun `기능 질문은 자기참조보다 먼저 걸러야 한다`() {
        // "너는 뭐 할 줄 알아?" 는 양쪽 패턴에 다 걸린다. runChat 이 기능 질문을 먼저 보므로
        // 정체가 아니라 기능을 답해야 한다. 순서가 뒤집히면 이 단정이 깨진다.
        val q = "너는 뭐 할 줄 알아?"
        assertTrue(isCapabilityQuestion(q))
        assertTrue(isSelfReferenceQuestion(q))
    }

    @Test
    fun `기능 안내는 등록된 도구에서 파생된다`() {
        val answer = buildCapabilityAnswer(listOf("일정 등록 화면을 엽니다.", "메일 작성 화면을 엽니다."))
        assertTrue(answer.contains("명함을 찾습니다"))
        assertTrue(answer.contains("일정 등록 화면을 엽니다."))
        assertTrue(answer.contains("메일 작성 화면을 엽니다."))
        // 도구가 없어도 검색 한 줄은 남아야 한다(빈 목록을 답으로 내보내지 않는다).
        assertTrue(buildCapabilityAnswer(emptyList()).contains("명함을 찾습니다"))
    }

    // ---- 담화 순서 지시("처음에 물어본 사람") ----
    // 직전 결과의 N번째를 고르는 ordinalIndex 와 다른 축이다. 최근 창(4턴) 밖 인물을
    // 가리키므로 focus 치환으로는 못 닿는다.

    private val subjects = "손다은,국은영,예예준,주주원,선현우"

    @Test
    fun `대화에서 처음 나온 사람을 가리킨다`() {
        listOf(
            "처음에 물어본 사람 전화번호는?",
            "맨 처음 질문한 분 전화번호는?",
            "첫 번째로 물어본 사람 전화번호는?",
            "아까 처음에 물어본 분 전화번호는?",
            "처음 언급한 사람 전화번호는?",
            "가장 먼저 물어본 사람 전화번호는?",
            "대화 맨 처음에 물어본 분 전화번호는?",
            "두 사람 중 먼저 물어본 사람 회사와 이메일도 알려줘",
        ).forEach {
            val out = resolveDiscourseReference(it, subjects, prevCardCount = 1)
            assertTrue("$it -> $out", out.startsWith("손다은"))
        }
    }

    @Test
    fun `담화 지시를 풀어도 물어본 속성은 남는다`() {
        // 이름만 남기고 속성을 지워버리면 "손다은" 만 답하게 된다(carryOverAttribute 회귀와 같은 증상).
        val out = resolveDiscourseReference("처음에 물어본 사람 전화번호는?", subjects, prevCardCount = 1)
        assertTrue(out, out.contains("전화번호"))
    }

    @Test
    fun `직전 결과가 여러 장이면 순서 지시는 그 목록을 가리킨다`() {
        // "두 번째 사람" 은 담화 단서가 없다. 고를 후보가 있으면 기존 ordinalIndex 경로가 맞다.
        val q = "두 번째 사람 연락처"
        assertEquals(q, resolveDiscourseReference(q, subjects, prevCardCount = 5))
    }

    @Test
    fun `이름이 이미 있으면 건드리지 않는다`() {
        val q = "국은영씨 처음 회사가 어디야?"
        assertEquals(q, resolveDiscourseReference(q, subjects, prevCardCount = 1))
    }

    @Test
    fun `가리킬 인물이 없으면 원문 그대로다`() {
        val q = "처음에 물어본 사람 전화번호는?"
        assertEquals(q, resolveDiscourseReference(q, null, prevCardCount = 0))
        assertEquals(q, resolveDiscourseReference(q, "", prevCardCount = 0))
    }

    @Test
    fun `화제 인물은 순서를 지키며 중복 없이 쌓인다`() {
        var acc = appendSubject(null, "손다은")
        acc = appendSubject(acc, "국은영")
        acc = appendSubject(acc, "손다은")   // 이미 나온 사람은 순서를 바꾸지 않는다
        assertEquals("손다은,국은영", acc)
    }

    @Test
    fun `사람을 가리키는 되짚기 표현은 문맥응답이 아니다`() {
        // "아까"가 붙었다고 되짚기가 아니다. 사람을 가리키면 그 사람에 대한 새 질문이다.
        listOf(
            "아까 그 사람 회사, 직급, 부서를 알려줘",
            "아까부터 물어본 그분의 회사 알려줘",
            "방금 그 사람 연락처는?",
        ).forEach { assertTrue(it, ConversationalFollowup.pointsAtPerson(it)) }
    }

    @Test
    fun `속성을 되짚는 표현은 그대로 문맥응답이다`() {
        // 이쪽이 contextAnswer 가 원래 담당하던 발화다. 위 완화가 이걸 죽이면 안 된다.
        listOf(
            "아까 말한 회사 뭐였지",
            "방금 찾은 거 뭐였어",
            "앞서 말한 주소 기억나?",
        ).forEach { assertFalse(it, ConversationalFollowup.pointsAtPerson(it)) }
    }

    // ---- 빈 칸을 물었을 때 옆 칸으로 대체하지 않는다 ----

    private fun cardOf(company: String = "", title: String = "", department: String = "",
                       phone: String = "", email: String = "", address: String = "") =
        BusinessCardEntity(
            "x1", "빈칸", "", company, title, department, "", "", phone, email, address, "", "", 0L,
        )

    @Test
    fun `물어본 칸이 비어 있으면 없다고 답한다`() {
        // 실측: 컨텍스트에 그 칸만 없고 나머지가 차 있으면 2B 모델이 옆 칸 값을 갖다 붙였다
        // (회사를 물었는데 부서를, 직급을 물었는데 부서를).
        val c = cardOf(department = "국내영업팀", address = "제주특별자치도 제주시")
        assertEquals("회사 정보가 없습니다.", emptyFieldAnswer("그 사람 회사는?", listOf(c)))
        assertEquals("직급 정보가 없습니다.", emptyFieldAnswer("그 사람 직급은?", listOf(c)))
    }

    @Test
    fun `값이 있으면 LLM 에 맡긴다`() {
        val c = cardOf(company = "코랄글로벌", department = "개발2팀")
        assertNull(emptyFieldAnswer("그 사람 회사는?", listOf(c)))
        assertNull(emptyFieldAnswer("그 사람 부서는?", listOf(c)))
    }

    @Test
    fun `후보가 하나가 아니면 건드리지 않는다`() {
        // 여러 명이면 '그중 누구의 칸'인지 정해지지 않는다.
        val c = cardOf(department = "국내영업팀")
        assertNull(emptyFieldAnswer("그 사람 회사는?", listOf(c, cardOf(company = "있음"))))
        assertNull(emptyFieldAnswer("그 사람 회사는?", emptyList()))
    }

    @Test
    fun `속성을 안 물었으면 건드리지 않는다`() {
        assertNull(emptyFieldAnswer("판교에 있는 개발자 찾아줘", listOf(cardOf())))
        assertNull(emptyFieldAnswer("오늘 날씨 어때?", listOf(cardOf())))
    }

    // ---- 대상 정정("A가 아니라 B야") ----

    @Test
    fun `정정하면 뒤에 말한 사람으로 바뀐다`() {
        val out = resolveCorrection(
            "정정할게. 손서윤씨가 아니라 남다은씨야. 그분 회사는 어디야?",
            listOf("손서윤", "남다은"),
        )
        assertTrue(out, out.startsWith("남다은"))
        assertFalse("옛 대상이 남음: $out", out.contains("손서윤"))
        assertFalse("대명사가 남음: $out", out.contains("그분"))
        assertTrue("요청이 사라짐: $out", out.contains("회사"))
    }

    @Test
    fun `추출 순서가 아니라 발화 위치 순으로 본다`() {
        // knownNames 가 어떤 순서로 오든 '나중에 말한 쪽'이 정정된 대상이다.
        val q = "공성민씨 말고 추시우씨야. 그 사람 부서는?"
        assertTrue(resolveCorrection(q, listOf("추시우", "공성민")).startsWith("추시우"))
        assertTrue(resolveCorrection(q, listOf("공성민", "추시우")).startsWith("추시우"))
    }

    @Test
    fun `정정 표지가 없으면 손대지 않는다`() {
        val q = "손서윤씨와 남다은씨 회사 알려줘"
        assertEquals(q, resolveCorrection(q, listOf("손서윤", "남다은")))
    }

    @Test
    fun `이름이 하나뿐이면 손대지 않는다`() {
        // 고를 대상이 없다. "그 사람 말고 다른 사람" 같은 발화를 망가뜨리지 않는다.
        val q = "손서윤씨 말고 회사 알려줘"
        assertEquals(q, resolveCorrection(q, listOf("손서윤")))
        assertEquals(q, resolveCorrection(q, emptyList()))
    }

    // ---- 한 사람에게 여러 칸을 물으면 코드가 조합해 답한다 ----

    @Test
    fun `여러 칸을 물으면 다 채워서 답한다`() {
        // 실측: 2B 모델이 "회사와 이메일" 중 이메일만 답했다(4건). 값은 컨텍스트에 다 있었다.
        val c = cardOf(company = "코랄글로벌", email = "a@b.kr", title = "CDO", department = "디자인팀")
        assertEquals("회사: 코랄글로벌, 이메일: a@b.kr",
            fieldListAnswer("두미영씨 회사와 이메일도 알려줘", listOf(c)))
        assertEquals("회사: 코랄글로벌, 직급: CDO, 부서: 디자인팀",
            fieldListAnswer("그 사람 회사, 직급, 부서를 알려줘", listOf(c)))
    }

    @Test
    fun `지시 관형사 뒤의 명사는 요청이 아니다`() {
        // "그 회사 주소는?" 은 주소 하나만 묻는 것이다. 이 구분이 없으면 '대명사 체인'
        // 시나리오 7턴이 통째로 오탐된다(정적 확인).
        assertEquals(listOf("주소"), requestedFields("그 회사 주소는?").map { it.second })
        assertNull(fieldListAnswer("그 회사 주소는?", listOf(cardOf(company = "A", address = "B"))))
        // 진짜로 둘을 물으면 걸린다.
        assertEquals(listOf("주소", "전화번호"),
            requestedFields("그 회사 주소랑 전화번호 알려줘").map { it.second })
    }

    @Test
    fun `한 칸이면 LLM 에 맡긴다`() {
        val c = cardOf(company = "코랄글로벌")
        assertNull(fieldListAnswer("두미영씨 회사가 어디야?", listOf(c)))
    }

    @Test
    fun `여러 칸 요청도 후보가 하나가 아니면 건드리지 않는다`() {
        val c = cardOf(company = "A", email = "a@b.kr")
        assertNull(fieldListAnswer("회사와 이메일 알려줘", listOf(c, cardOf())))
        assertNull(fieldListAnswer("회사와 이메일 알려줘", emptyList()))
    }

    @Test
    fun `빈 칸은 정보 없음으로 표시한다`() {
        // 옆 칸 값으로 대체하던 결함의 여러 칸 버전이다.
        val c = cardOf(company = "코랄글로벌")
        assertEquals("회사: 코랄글로벌, 부서: 정보 없음",
            fieldListAnswer("그 사람 회사와 부서 알려줘", listOf(c)))
    }

    @Test
    fun `긴 명사가 짧은 명사를 이긴다`() {
        // "이메일" 안의 "메일", "전화번호" 안의 "전화"가 먼저 잡히면 라벨이 잘린다.
        assertEquals(listOf("전화번호", "이메일"),
            requestedFields("전화번호랑 이메일 알려줘").map { it.second })
    }

    // ---- 생략형 후속: 속성 명사 앞의 군말 ----

    @Test
    fun `군말이 앞에 붙어도 생략형 후속으로 본다`() {
        // "부서는?"은 되는데 "어느 부서야?"가 새 검색으로 빠지면 focus 가 엉뚱한 사람으로
        // 튀고, 그 한 턴이 뒤 대화를 통째로 무너뜨린다(실측 32건 연쇄).
        listOf("어느 부서야?", "그럼 주소는?", "혹시 이메일은?", "근데 직급이 뭐야?").forEach {
            assertEquals("$it -> focus 안 붙음", "김서영 $it", resolveSearchQuery(it, "김서영"))
        }
    }

    @Test
    fun `군말 없는 기존 형태도 그대로 동작한다`() {
        listOf("부서는?", "주소는?", "전화번호는?").forEach {
            assertEquals("김서영 $it", resolveSearchQuery(it, "김서영"))
        }
    }

    @Test
    fun `새 인물이나 독립 질문은 건드리지 않는다`() {
        // 군말 완화가 무관한 질문까지 focus 에 묶으면 안 된다.
        listOf("판교에 있는 개발자 찾아줘", "오늘 날씨 어때?", "전체 몇 장이야?").forEach {
            assertEquals(it, resolveSearchQuery(it, "김서영"))
        }
    }

    @Test
    fun `그 회사의 다른 사람은 회사 엔티티로 푼다`() {
        assertEquals(
            "유한회사 앰버 다니는 다른 사람은?",
            resolveSearchQuery("그 회사 다니는 다른 사람은?", "백다인", "유한회사 앰버"),
        )
        assertEquals(
            "유한회사 앰버 직원 연락처 알려줘",
            resolveSearchQuery("그 회사 직원 연락처 알려줘", "백다인", "유한회사 앰버"),
        )
    }

    @Test
    fun `그 회사의 속성 질문은 직전 인물 카드에 머문다`() {
        // "그 회사 주소"는 회사를 새로 찾으라는 게 아니라 그 사람 카드의 주소를 묻는 말이다.
        assertEquals(
            "백다인 주소는?",
            resolveSearchQuery("그 회사 주소는?", "백다인", "유한회사 앰버"),
        )
    }

    @Test
    fun `복합어를 두 칸으로 쪼개지 않는다`() {
        // "메일 주소는?" 은 이메일 하나를 묻는 말이다. 두 칸으로 읽으면 주소까지 답한다
        // (실측: 평가 발화를 다양화하면서 이 오작동이 드러났다).
        assertEquals(listOf("메일 주소"), requestedFields("메일 주소는?").map { it.second })
        assertEquals(listOf("이메일 주소"), requestedFields("이메일 주소 알려줘").map { it.second })
        assertEquals(listOf("회사 주소"), requestedFields("회사 주소는?").map { it.second })
        // 한 칸이므로 여러 칸 우회는 안 걸리고 LLM 이 문장으로 답한다.
        assertNull(fieldListAnswer("메일 주소는?", listOf(cardOf(email = "a@b.kr", address = "서울"))))
    }

    @Test
    fun `진짜로 두 칸을 물으면 그대로 두 칸이다`() {
        assertEquals(listOf("회사", "이메일"),
            requestedFields("회사와 이메일도 알려줘").map { it.second })
        assertEquals(listOf("전화번호", "이메일"),
            requestedFields("전화번호랑 이메일 알려줘").map { it.second })
    }

    // ---- 필드 지시어 정규화 ----

    @Test
    fun `직장과 어디 다녀를 회사로 바꾼다`() {
        // 실측: "회사가 어디야?" 41/41 인데 "직장이 어디지?" 5/6, "어디 다녀?" 3/7 이었다.
        // 값은 카드에 있고 어느 칸인지만 정하면 되는 턴이라, 모델이 잘 읽는 말투로 바꿔 보낸다.
        assertEquals("마민씨 회사가 어디지?", normalizeAttributeWords("마민씨 직장이 어디지?"))
        assertEquals("직장은 -> 회사는", "회사는 어디야?", normalizeAttributeWords("직장은 어디야?"))
        assertEquals("회사 알려줘", normalizeAttributeWords("직장 알려줘"))
        listOf("두준씨 어디 다녀?", "라민씨 어디 다니세요?", "어디서 일해?").forEach {
            assertTrue(it, normalizeAttributeWords(it).contains("회사가 어디야"))
        }
    }

    @Test
    fun `다른 낱말의 일부면 건드리지 않는다`() {
        // "직장인" 은 직장+인 이지 필드 지시어가 아니다.
        listOf("직장인 몇 명?", "직장인분들 찾아줘").forEach {
            assertEquals(it, it, normalizeAttributeWords(it))
        }
    }

    @Test
    fun `이미 정본인 말투와 무관한 질의는 그대로다`() {
        listOf("마민씨 회사가 어디야?", "판교에 몇 명이야?", "그 사람 부서는?").forEach {
            assertEquals(it, it, normalizeAttributeWords(it))
        }
    }
}
