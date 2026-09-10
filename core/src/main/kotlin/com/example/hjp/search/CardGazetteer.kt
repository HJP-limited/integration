package com.example.hjp.search

import com.example.hjp.data.BusinessCardEntity

/**
 * 가제티어(폐쇄 도메인 값 사전) 기반 기권·필드 필터.
 *
 * scripts/eval_search.py 의 Gazetteer / should_abstain / extract_field_filters 를 이식한 것.
 * 파이썬 쪽은 확인용이고 실제 동작은 이 파일이다 — 두 구현이 어긋나면 앱이 평가와 다르게 동작하므로
 * 규칙을 바꿀 때는 반드시 양쪽을 같이 고칠 것.
 *
 * 왜 '점수 컷오프'가 아니라 '값의 존재 여부'인가(오프라인 측정 결론):
 *   점수 기반 기권은 원리적으로 불가능했다. 기권시켜야 하는 "세종특별자치시 디자이너"의
 *   시맨틱 마진(0.175)이 살려야 하는 "디자인 총괄"(0.024)보다 7배 높다 — '세종특별자치시'만
 *   없고 '디자이너'는 데이터에 많으니 당연한 결과다. 임계값 스윕에서도 개념형 보존과 기권 성공이
 *   동시에 되는 지점이 없었다(0.04 -> 74%/44%, 0.06 -> 29%/63%, 0.20 -> 0%/100%).
 *   명함 도메인은 지역·이름 값 집합이 유한하므로 '그 값이 실제로 있는가'로 판정하는 게 맞다.
 */
class CardGazetteer(cards: List<BusinessCardEntity>) {

    /** 기권 판정 전용 — '행정구역 단위' 어휘만. 도로명 조각을 섞으면 없는 지역이 부분 매칭돼 기권이 안 된다. */
    private val locations = HashSet<String>()

    /** 필터 전용 — "판교"(판교역로), "강남"(강남구)처럼 접미사 없는 관용 표현 포함. 기권 판정에는 쓰지 않는다. */
    private val addressTerms = HashSet<String>()

    /** 직함 어휘. 도로명 조각과 충돌하는 것을 지역 필터에서 빼는 데 쓴다(광주 '상무중앙로' -> '상무'). */
    private val titleTerms = HashSet<String>()

    private val titles = HashSet<String>()
    private val companies = HashSet<String>()

    /**
     * 회사 **핵심어** 어휘. [companies]는 회사명 전체 문자열이라("유한회사 앰버")
     * 질의 토큰 하나로는 절대 안 맞는다 — 그래서 질의에 회사를 댔는데도 조건이 안 걸렸다.
     * 실측: "샤인기계 백다인씨 직급"이 백다인 두 장을 다 가져왔고, 몇 턴 뒤
     * "백다인씨 전화번호"가 엉뚱한 쪽을 답했다(통합 벤치 v1.3 실패 3건이 전부 이 모양).
     * 법인 표기(주식회사·유한회사)는 197·160장이 공유하므로 뺀다 — 넣으면 그게
     * 필터가 되어 357장이 한 덩어리가 된다.
     */
    private val companyTerms = HashSet<String>()
    private val names = HashSet<String>()

    /**
     * 부서 어휘("AI개발팀", "서초지점"). **카운트 경로 전용** — 기권 판정이나 필드 하드
     * 필터(공용 경로)에는 쓰지 않는다.
     *
     * 공용 필터에 넣어봤다가 뺐다(실측): ① 204질의 평가에서 발동 0건이라 회귀 여부를
     * 검증할 수가 없었고(= 안전한 게 아니라 미검증), ② 정작 목표인 "AI 개발자 몇
     * 명이야?"도 못 고쳤다 — 부서명이 "AI개발팀"(붙여쓰기)이라 어휘가 한 덩어리인데
     * 질의는 ['ai','개발자']로 쪼개져 정확 일치가 안 되기 때문이다.
     * [countByKnownCondition] 에서는 부분 문자열로 맞춰서 이 문제를 푼다.
     */
    private val departmentTerms = HashSet<String>()

    /** 데이터에 실재하는 성(姓)과 이름(given name). 호칭 없는 맨 이름 판정에 쓴다. */
    private val surnames = HashSet<String>()
    private val givenNames = HashSet<String>()

    init {
        // 직함을 먼저 모아야 아래 주소 처리에서 직함과 겹치는 조각을 걸러낼 수 있다.
        for (card in cards) {
            val t = card.title.orEmpty().trim()
            if (t.isNotBlank()) {
                val nt = KeywordSearchRanker.normalize(t)
                titles.add(nt)
                nt.split(" ").filter { it.length >= 2 }.forEach { titleTerms.add(it) }
            }
            val d = card.department.orEmpty().trim()
            if (d.isNotBlank()) {
                KeywordSearchRanker.normalize(d).split(" ")
                    .filter { it.length >= 2 }
                    .forEach { departmentTerms.add(it) }
            }
        }

        for (card in cards) {
            val loc = card.location.orEmpty().trim()
            if (isValidLocation(loc)) locations.add(KeywordSearchRanker.normalize(loc))

            val addr = card.address.orEmpty().trim()
            if (addr.isNotBlank()) {
                val parts = KeywordSearchRanker.normalize(addr).split(" ").filter { it.isNotBlank() }
                // 주소 첫 토큰(광역 단위)만 지역 '존재' 판정 어휘로 인정한다.
                if (parts.isNotEmpty()) locations.add(parts[0])
                for (part in parts) {
                    if (part.length < 2 || part.all { it.isDigit() }) continue
                    addressTerms.add(part)
                    for (suffix in ADDRESS_SUFFIXES) {
                        if (part.endsWith(suffix) && part.length - suffix.length >= 2) {
                            addressTerms.add(part.substring(0, part.length - suffix.length))
                            break
                        }
                    }
                }
            }
            if (loc.isNotBlank()) {
                KeywordSearchRanker.normalize(loc).split(" ")
                    .filter { it.length >= 2 && !it.all { c -> c.isDigit() } }
                    .forEach { addressTerms.add(it) }
            }

            val comp = card.company.orEmpty().trim()
            if (comp.isNotBlank()) {
                val normalizedCompany = KeywordSearchRanker.normalize(comp)
                companies.add(normalizedCompany)
                normalizedCompany.split(" ")
                    .filter { it.length >= 2 && it !in COMPANY_STOPWORDS }
                    .forEach { companyTerms.add(it) }
            }

            val nm = card.name.orEmpty().trim()
            if (nm.isNotBlank()) names.add(KeywordSearchRanker.normalize(nm))
        }

        // 직함/지역이 겹치는 단어("상무" — 직함 상무 / 광주 '상무대로')를 지역 어휘에서
        // 빼던 정적 규칙은 제거했다. 어휘를 깎아 충돌을 회피하는 방식이라 부작용이 있었다:
        // "상무 찾아줘"에 아무 조건도 안 걸려 후보가 전체 그대로였다.
        // 지금은 충돌 해소를 어휘가 아니라 '판정 순서'로 한다 — extractFieldFilters 와
        // countByKnownCondition 이 둘 다 직함을 먼저 보고, 직함이면 지역으로 넘기지 않는다.

        for (nm in names) {
            if (nm.length >= 2) {
                surnames.add(nm.substring(0, 1))
                givenNames.add(nm.substring(1))
            }
        }
    }

    /**
     * 지역 토큰이 데이터에 존재하는가.
     * '서울'이 '서울특별시'에 걸리도록 접두어 관계는 인정하되, 아무 위치의 부분 문자열 포함은
     * 인정하지 않는다 — 느슨하게 잡으면 없는 지역도 존재한다고 오판한다(실측: 기권 0.30 -> 0.15).
     */
    fun regionExists(token: String): Boolean =
        locations.any { it == token || it.startsWith(token) || token.startsWith(it) }

    fun looksLikeRegion(token: String): Boolean =
        token.length >= 3 && REGION_SUFFIXES.any { token.endsWith(it) }

    /** 이름 토큰이 데이터에 존재하는가. 성을 뗀 형태('하은')로 불렀을 수도 있어 부분 일치도 인정. */
    fun nameExists(token: String): Boolean = names.any { it == token || token in it }

    /**
     * '이름을 지목한 토큰'인지 본다. 두 경로로 인정한다.
     *
     * 1) 호칭이 붙은 경우("정하은씨") — 호칭 자체가 사람 지목의 확실한 신호다.
     * 2) 호칭 없는 맨 3자("정하은") — 데이터에 있는 성('정') + 데이터에 있는 이름('하은')으로
     *    조립된 경우만. 즉 "아는 부품으로 만들어졌는데 명단엔 없는 이름"이다.
     *
     * 2번을 '아는 성으로 시작하는 3자'로 넓혔더니 서커스·조련사·조종사·임원급·공무원이 전부
     * 이름으로 잡혀 개념형 R@5 가 0.660 -> 0.630 으로 떨어졌다(이름 판정이 실제로는
     * '모르는 3자 단어 판정'이었던 셈). 지금 조건에서는 개념형 오탐이 0건이다.
     */
    fun looksLikePersonName(token: String): Boolean {
        if (!token.all { it in '가'..'힣' }) return false
        // 데이터에 지역/직함/회사로 실재하는 값이면 사람 이름이 아니다.
        // 호칭 분기에도 이 검사가 필요하다: '군'이 호칭 목록에 있어서 '음성군·평창군·울주군'이
        // "음성"+호칭"군"으로 잡혔고, 명단에 없는 이름이라 기권해 버렸다
        // (실측: "충청북도 음성군에 있는 프로덕트매니저 찾아줘" 가 정답이 있는데도 기권).
        if (token in addressTerms || token in titleTerms || token in titles || token in companies) {
            return false
        }
        if (token.length in 3..5 && NAME_HONORIFICS.any { token.endsWith(it) }) return token.length - 1 >= 2
        if (token.length == 3 && token.substring(0, 1) in surnames && token.substring(1) in givenNames) {
            return true
        }
        return false
    }

    fun personNameStem(token: String): String =
        if (NAME_HONORIFICS.any { token.endsWith(it) }) token.dropLast(1) else token

    fun isKnownAddressTerm(token: String): Boolean = token in addressTerms

    fun isKnownCompanyTerm(token: String): Boolean = token in companyTerms

    /** 정규화된 질의 안에 통째로 들어 있는 회사명들. [cores] 중 하나를 포함하는 것만 본다. */
    fun companiesContainedIn(normalizedQuery: String, cores: Set<String>): List<String> =
        companies.filter { c -> c in normalizedQuery && cores.any { it in c.split(" ") } }

    fun isKnownName(token: String): Boolean = token in names

    fun isKnownTitle(token: String): Boolean = token in titles || token in titleTerms

    /** 직함을 단어 단위로 조회한다(필드 필터용 — eval_search.py 의 title_terms 와 동일 기준). */
    fun isKnownTitleTerm(token: String): Boolean = token in titleTerms

    /**
     * 어떤 부서명에든 들어있는 조각인가. 카운트 경로 전용 — 부분 문자열로 맞춘다
     * (부서명이 "AI개발팀"처럼 붙여쓰기라 정확 일치로는 'ai'가 안 걸린다).
     * 실제로 존재하는 부서명의 일부일 때만 인정해서 아무 단어나 부서로 오인하지 않게 한다.
     */
    fun isKnownDepartmentTerm(token: String): Boolean = departmentTerms.any { token in it }

    companion object {
        /**
         * 지역을 가리키는 접미사. 단일 글자 중 시/구/리/동/면/읍은 쓰지 않는다 — 일상 단어에
         * 너무 흔해 오탐이 난다(실측: "번호 뒷자리 2033인 사람"의 '뒷자리'가 '리'로 끝나
         * 지역으로 오인돼, 존재하는 번호인데 기권 처리됐다).
         *
         * '도'만 예외로 넣는다. 섬 이름(울릉도/백령도)이 여기 걸리고, 이 데이터에서는 오탐이
         * 실측상 없었다(평가 205질의에서 '도'로 끝나는 토큰은 실제 지역 18건 + 기권 대상 3건뿐,
         * 직함 어휘 중 '도'로 끝나는 것 0개). 존재하는 지역은 regionExists 가 True 라 기권으로
         * 안 빠지므로, 이 규칙은 '지역처럼 생겼는데 데이터에 없는 값'만 기권시킨다.
         *
         * 길이 조건(>=3)은 그대로 둔다 — 2글자까지 열면 '정도·태도·수도' 같은 흔한 말이
         * 지역으로 오인된다. 대가로 '독도'(2글자)는 못 잡는다.
         */
        private val REGION_SUFFIXES =
            listOf("특별자치시", "특별자치도", "특별시", "광역시", "자치도", "자치시", "도")

        /** 사람 이름을 지목하는 호칭 — "정하은씨", "정하은님". */
        val NAME_HONORIFICS = listOf("씨", "님", "군", "양")

        /** 주소 조각에서 관용 지명("판교역로" -> "판교")을 뽑을 때 떼는 접미사. 긴 것부터. */
        private val ADDRESS_SUFFIXES = listOf(
            "특별자치시", "특별자치도", "특별시", "광역시", "역로", "대로", "로", "길",
            "시", "군", "구", "동", "읍", "면",
        )

        /**
         * 지역 어휘로 등록할 값인지 본다.
         *
         * 예전에는 "A."/"Address."/"M."/"E." 같은 OCR 라벨 접두어 잔재를 걸러내는
         * 목록이 있었다. 그건 특정 데이터셋의 흔적이라 데이터를 정리하면서 없앴고,
         * 목록에 없는 오염값은 어차피 못 걸렀다. 지금은 형태로만 판정한다 —
         * 빈 값이 아니고 마침표로 끝나지 않으면 지역으로 본다.
         */
        fun isValidLocation(value: String?): Boolean {
            val v = value.orEmpty().trim()
            return v.isNotBlank() && !v.endsWith(".")
        }

        /**
         * 전화번호/이메일 같은 '식별자 조회' 질의인지 판정한다.
         *
         * 근거(측정값): 전화번호 질의에서 시맨틱은 R@5=0.000(숫자를 의미로 이해할 수 없음)인데,
         * RRF 로 대등하게 섞으면 좋은 키워드 결과를 밀어내 P@5 가 1.000 -> 0.233 으로 폭락했다.
         * 이런 질의는 시맨틱 축을 융합에서 빼는 게 명확한 개선이다.
         *
         * 4자리 기준인 이유: "번호 뒷자리 4312인 분" 같은 실제 사용 패턴을 포함해야 한다.
         */
        fun isIdentifierQuery(query: String?): Boolean {
            val q = query.orEmpty().trim()
            if (q.count { it.isDigit() } >= 4) return true
            return "@" in q
        }
    }
}

/** 질의에서 뽑아낸 필드 조건. 비어 있으면 필터를 걸지 않는다. */
/**
 * 법인 표기. normalize가 괄호를 지우므로 "(주)"는 "주", "(유)"는 "유"가 되는데 둘 다
 * 한 글자라 길이 조건에서 이미 걸러진다. 여기 남기는 것은 두 글자 이상인 것들이다.
 */
private val COMPANY_STOPWORDS = setOf("주식회사", "유한회사")

data class FieldFilters(
    val names: List<String> = emptyList(),
    val locations: List<String> = emptyList(),
    val titles: List<String> = emptyList(),
    val companies: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = names.isEmpty() && locations.isEmpty() && titles.isEmpty() && companies.isEmpty()

    override fun toString(): String = buildList {
        if (names.isNotEmpty()) add("이름=${names.joinToString(",")}")
        if (locations.isNotEmpty()) add("지역=${locations.joinToString(",")}")
        if (titles.isNotEmpty()) add("직함=${titles.joinToString(",")}")
        if (companies.isNotEmpty()) add("회사=${companies.joinToString(",")}")
    }.joinToString(" ")
}

/**
 * 질의에서 '실제 존재하는 필드 값'만 뽑는다.
 *
 * 왜 필요한가(실측): "판교에서 일하는 사람 몇명이지?" 에서 판교 근무자는 정확히 5명인데,
 * 키워드는 그 5명을 찾았지만 RRF 융합에서 시맨틱이 끌어온 무관한 사람이 끼어들고 정작
 * 판교인 고예린이 밀려났다. 지역·이름이 '점수 가산 요소'로만 쓰여서 그렇다.
 * 조건을 필터로 쓰면 이름 질의 P@5 가 0.455 -> 1.000 이 된다.
 *
 * 위험(그래서 좁게 적용): 필터가 틀리면 정답까지 잘려 결과가 0개가 된다. 그래서 가제티어에
 * '실제로 존재하는' 값을 지목했을 때만 건다. 없는 값이면 필터가 아니라 기권이 처리한다.
 * 개념형 질의는 특정 값을 지목하지 않으므로 필터가 걸리지 않는다.
 *
 * 직함도 조건으로 쓴다. 예전에는 직함을 필터로 안 쓰고, 직함/주소가 겹치는 단어를 지역
 * 어휘에서 깎아내는 정적 규칙으로만 충돌을 막았다. 그러면 "상무 찾아줘"에 아무 필터도
 * 안 걸려 후보가 전체 그대로였다 — 계단식 랭킹이 직함 상무를 상위에 정확히 올려놔도,
 * 필터가 그 순위를 안 보고 집합 조회만 하니 활용을 못 한 것이다.
 * 직함을 조건으로 승격한 뒤 실측(204질의): P@5 0.682 -> 0.773, MRR 0.928 -> 0.955,
 * '지역+직함' P@5 0.395 -> 0.815 / FullP 0.057 -> 0.777. 개념형·기권은 변화 없음
 * (개념형 질의는 데이터의 직함 단어를 그대로 쓰지 않아 이 필터가 발동하지 않는다).
 */
fun extractFieldFilters(analyzed: AnalyzedSearchQuery, gazetteer: CardGazetteer?): FieldFilters {
    if (gazetteer == null) return FieldFilters()
    val names = LinkedHashSet<String>()
    val locs = LinkedHashSet<String>()
    val titles = LinkedHashSet<String>()
    for (token in analyzed.keywordTokens) {
        val stem = if (gazetteer.looksLikePersonName(token)) gazetteer.personNameStem(token) else token
        if (stem.length >= 2 && gazetteer.isKnownName(stem)) names.add(stem)
        if (token.length < 2 || token.all { it.isDigit() }) continue
        // 직함을 먼저 본다 — "상무"처럼 직함이면서 주소 조각이기도 한 단어는 직함이 우선이다
        // (계단식 랭킹도 직함 정확일치를 주소 접두어보다 위에 올린다).
        if (gazetteer.isKnownTitleTerm(token)) {
            titles.add(token)
            continue
        }
        if (gazetteer.isKnownAddressTerm(token)) locs.add(token)
    }
    // 회사는 마지막이다. 이름/직함/지역이 먼저 가져간 토큰은 건드리지 않는다 — 회사
    // 핵심어가 사람 이름이나 지역과 겹칠 수 있고, 그때 우선순위를 뒤집으면 이름 검색이
    // 깨진다. 파이썬 쪽(extract_field_filters)과 같은 순서를 유지할 것.
    val comps = LinkedHashSet<String>()
    for (token in analyzed.keywordTokens) {
        if (token in names || token in titles || token in locs) continue
        if (gazetteer.isKnownCompanyTerm(token)) comps.add(token)
    }
    // **법인 표기까지 댔으면 그걸 살린다.** 핵심어만 쓰면 "유한회사 경기전자"와
    // "주식회사 경기전자"가 한 덩어리가 되는데 데이터에서 이 둘은 다른 회사다
    // (핵심어가 겹치는 조합이 41조 있다). 질의에 회사명 전체가 통째로 들어 있으면
    // 그 전체를 조건으로 삼고, 아니면 핵심어로 남긴다 — "경기전자 사람"은 사용자가
    // 안 가렸으므로 우리도 가리지 않는다.
    val companyFilter = if (comps.isEmpty()) emptyList() else {
        val normalizedQuery = analyzed.normalized
        val exact = gazetteer.companiesContainedIn(normalizedQuery, comps)
        if (exact.isNotEmpty()) exact.sorted() else comps.sorted()
    }
    return FieldFilters(names.sorted(), locs.sorted(), titles.sorted(), companyFilter)
}

/** 필터 조건을 모두(AND) 만족하는 카드만 남긴다. 필터가 없으면 원본 그대로. */
/**
 * 이름 조건은 필터 내에서 OR(둘 중 하나와 일치), 지역 조건도 필터 내에서 OR(언급된
 * 지역 중 하나라도 포함)로 적용한다.
 *
 * 지역을 AND로 했었으나 실측(스트레스 테스트)으로 버그를 발견했다: "판교랑 강남 중에
 * 사람 더 많은 곳이 어디야?" 같은 비교 질문은 [extractFieldFilters]가 두 지역을 모두
 * 뽑아서 locations=[강남,판교]가 되는데, AND면 "주소에 강남과 판교가 동시에 있어야
 * 함"이 되어 그런 사람은 존재할 수 없으므로 무조건 0명이 되어버렸다. OR로 바꾸면
 * "언급된 지역 중 하나에 해당하는 사람"이 되어 비교 질문에 필요한 후보군이 정상적으로
 * 모인다. 단일 지역 질의("판교에 있는 디자이너")는 항목이 하나뿐이라 AND/OR 결과가
 * 같아서 회귀가 없다.
 */
fun applyFieldFilters(cards: List<BusinessCardEntity>, filters: FieldFilters): List<BusinessCardEntity> {
    if (filters.isEmpty) return cards
    // 직함이 유일한 조건이면 '지우지' 말고 '정렬'한다.
    // 직함 정확 일치자를 앞으로 안정 정렬하고, 나머지(유사 직함)는 뒤에 그대로 남긴다.
    //
    // 왜 필터(삭제)가 아닌가: 필터는 시맨틱이 찾아낸 유사 직함을 후보에서 없애 버린다
    // ('고문변호사'는 "변호사" 질의에서 시맨틱 1위인데 삭제됐다). 정확 일치자가 적은
    // 직함에서는 그 자리를 채울 후보가 사라진다.
    //
    // 왜 그냥 두면(=미적용) 안 되는가: RRF 융합에서 시맨틱 1위가 키워드 1위를 뒤집어
    // 유사 직함이 정확 일치자보다 위로 올라온다. FTS4 티어드 기준 실측(단독 직함 질의
    // 92개)에서 '1위가 정확 일치'인 질의가 92/92 -> 86/92 로 떨어졌다
    // ("변호사 있나?" 1위가 '고문변호사', "주임 있나?" 1위가 '책임').
    // 시맨틱은 같은 직군 안에서 순서를 못 가린다 — 유사도가 0.38~0.40 에 몰려 있어
    // 1·2위 차이가 0.002 수준의 노이즈다.
    //
    // 정렬 방식은 필터의 정확도(엄격 460, 관련 460, 1위 92/92)를 그대로 내면서
    // 유사 직함을 후보에 보존한다.
    //
    // 주의: 직함 조건을 '추출'하는 것 자체는 유지해야 한다. extractFieldFilters 가
    // "상무"를 직함으로 선점해야 지역 조건으로 새지 않는다 — 지역으로 새면 광주
    // '상무대로' 사람만 남고 진짜 상무가 전멸한다.
    // 회사도 함께 본다 — 파이썬 쪽은 '직함이 유일한 조건일 때'가 조건이고, 회사가
    // 붙었는데 정렬로 빠지면 "샤인기계 상무"가 샤인기계 밖 상무까지 다 데려온다.
    if (filters.titles.isNotEmpty() && filters.names.isEmpty() &&
        filters.locations.isEmpty() && filters.companies.isEmpty()
    ) {
        return cards.sortedBy { card ->
            val titleWords = KeywordSearchRanker.normalize(card.title.orEmpty()).split(" ")
            if (filters.titles.any { it in titleWords }) 0 else 1
        }
    }
    // 조건을 다 만족하는 사람이 없으면 빈 결과를 그대로 돌려준다 = 기권한다.
    //
    // 예전에는 직함 조건만 떼고 다시 찾는 '완화'가 있었다. "판교에 디자인 디렉터는
    // 있는데 단어가 정확히 '디자이너'가 아니라 0건이 되는 걸 구제한다"는 취지였다.
    // 실측해 보니 완화는 eval 203질의에서 **한 번도 발동하지 않았다** — 질의가 데이터에
    // 실제로 있는 조합으로 만들어지기 때문이다. 즉 어떤 지표도 떠받치지 않았다.
    // 반대로 사용자가 없는 조합을 물을 때만 발동해서 거짓말을 만들었다:
    //   "판교에 있는 디자이너 알려줘" -> 판교에 디자인 계열 0명인데 직함을 떼고
    //   판교 9명을 넘겨서, LLM 이 그중 주임 한 명을 디자이너인 양 답했다.
    // 없는 조합에는 없다고 답하는 게 맞다. 기권 판단과 같은 원리다.
    return matchFieldFilters(cards, filters)
}

private fun matchFieldFilters(
    cards: List<BusinessCardEntity>,
    filters: FieldFilters,
): List<BusinessCardEntity> = cards.filter { card ->
    val nameOk = filters.names.isEmpty() ||
        filters.names.any { it == KeywordSearchRanker.normalize(card.name.orEmpty()) }
    val haystack = KeywordSearchRanker.normalize(
        card.location.orEmpty() + " " + card.address.orEmpty()
    )
    val locOk = filters.locations.isEmpty() || filters.locations.any { it in haystack }
    // 직함은 단어 단위로 맞춘다 — 부분 문자열이면 "대표이사"가 "이사"에 걸린다.
    //
    // 알려진 한계(고치려다 되돌림): 데이터에 띄어쓰기 없는 합성 직함이 섞여 있어서
    // ("고문변호사", "시니어매니저"), "변호사 있나?"에 '고문변호사'가 안 걸려 top-5에서
    // 빠진다. 부분 문자열(P@5 0.773->0.761)과 접미사 매칭(->0.769) 둘 다 실측해봤는데
    // 순정보다 나빠서 되돌렸다. 데이터 표기를 정규화하는 쪽이 맞는 해법이다.
    val titleWords = KeywordSearchRanker.normalize(card.title.orEmpty()).split(" ")
    val titleOk = filters.titles.isEmpty() || filters.titles.any { it in titleWords }
    // 회사도 단어 단위다. 부분 문자열이면 "대성전자"가 "대성"에 걸린다.
    // 두 가지를 받는다: 회사명 **전체**("유한회사 경기전자")면 통째로 일치해야 하고,
    // 핵심어("경기전자")면 단어 단위로 맞춘다. 부분 문자열이면 "대성전자"가 "대성"에 걸린다.
    val companyWhole = KeywordSearchRanker.normalize(card.company.orEmpty())
    val companyWords = companyWhole.split(" ")
    val companyOk = filters.companies.isEmpty() ||
        filters.companies.any { it == companyWhole || it in companyWords }
    nameOk && locOk && titleOk && companyOk
}

/**
 * 질문에서 가제티어가 아는 조건(지역/직함/이름)을 뽑아 전체 카드 목록을 직접 세서
 * 정확한 개수를 낸다. 검색과 달리 top-N/FUSION_POOL 컷을 전혀 거치지 않는다 —
 * "AI 개발하는 사람 몇 명이야?" 같은 질의가 top-5로 캡되어 실제보다 적게 나오던
 * 문제와 무관하게 정확하다(scripts/hybrid_server.py count_by_known_condition 이식).
 *
 * 아는 조건이 하나도 없으면(개념형 질의, 예: "AI 개발자 몇 명이야?" — "AI"는 가제티어에
 * 없는 개념어라 지역/직함/이름 어디에도 안 걸림) null을 반환한다 — 호출자가 기존
 * 검색+LLM 경로로 폴백해야 한다는 뜻이다. extractFieldFilters/applyFieldFilters(검색
 * 파이프라인 본체, 204질의 오프라인 평가로 검증된 상태)는 건드리지 않는 별도 경로다.
 */
fun countByKnownCondition(
    question: String,
    gazetteer: CardGazetteer,
    cards: List<BusinessCardEntity>,
): List<BusinessCardEntity>? {
    val analyzed = KeywordSearchRanker.analyze(question)
    val locs = LinkedHashSet<String>()
    val titles = LinkedHashSet<String>()
    val names = LinkedHashSet<String>()
    val depts = LinkedHashSet<String>()
    for (token in analyzed.keywordTokens) {
        if (token.length >= 2 && !token.all { it.isDigit() }) {
            // 직함을 먼저 본다 — "상무"처럼 직함이면서 주소 조각이기도 한 단어는 직함이
            // 우선이다(extractFieldFilters 와 같은 원칙). 둘 다에 넣으면 AND 로 겹쳐서
            // "주소에 상무가 있고 동시에 직함이 상무" = 0명이 된다.
            if (gazetteer.isKnownTitle(token)) {
                titles.add(token)
            } else if (gazetteer.isKnownAddressTerm(token)) {
                locs.add(token)
            }
            if (gazetteer.isKnownDepartmentTerm(token)) depts.add(token)
        }
        val stem = if (gazetteer.looksLikePersonName(token)) gazetteer.personNameStem(token) else token
        if (stem.length >= 2 && gazetteer.isKnownName(stem)) names.add(stem)
    }
    if (locs.isEmpty() && titles.isEmpty() && names.isEmpty() && depts.isEmpty()) return null

    return cards.filter { card ->
        val nameHay = KeywordSearchRanker.normalize(card.name.orEmpty())
        val locHay = KeywordSearchRanker.normalize(card.location.orEmpty() + " " + card.address.orEmpty())
        // 직함은 단어 단위로만 맞춘다(부분 문자열이면 "대표이사"가 "이사"에 걸려서
        // 오카운트된다 — 실측: title=="이사" 정확 매치 78명인데 부분매치로는 161명).
        val titleWords = KeywordSearchRanker.normalize(card.title.orEmpty()).split(" ")
        val deptHay = KeywordSearchRanker.normalize(card.department.orEmpty())
        val nameOk = names.isEmpty() || nameHay in names
        val locOk = locs.isEmpty() || locs.any { it in locHay }
        val titleOk = titles.isEmpty() || titles.any { it in titleWords }
        val deptOk = depts.isEmpty() || depts.any { it in deptHay }
        nameOk && locOk && titleOk && deptOk
    }
}

/**
 * 검색 결과를 반환할 근거가 있는지 판정한다. true 면 빈 결과를 돌려준다.
 *
 * 판정 규칙(점수를 쓰지 않는다):
 *   1) 식별자 질의(전화/이메일): 문자 그대로의 매칭이 유일한 근거다. 매칭이 전혀 없으면
 *      그런 번호/주소가 존재하지 않는 것이므로 기권.
 *   2) 지역을 명시적으로 지목했는데(예: "세종특별자치시") 그 지역이 데이터에 없으면 기권.
 *      질의의 다른 부분("디자이너")이 아무리 많이 매칭돼도 그 조합은 존재하지 않는다.
 *   3) 사람 이름을 지목했는데("정하은씨", 또는 검색창에 이름만 친 "정하은") 명단에 없으면 기권.
 *      이게 없으면 임베딩이 발음 비슷한 이름(백하은/하채원)을 끌어오고 LLM 이 없는 사람에 대해
 *      답을 지어낸다(실측: "채용설명회에서 만났습니다").
 *   4) 그 외에는 기권하지 않는다 — 개념형 질의를 죽이지 않기 위한 보수적 기본값.
 */
fun shouldAbstain(
    analyzed: AnalyzedSearchQuery,
    keywordHitCount: Int,
    gazetteer: CardGazetteer?,
): Boolean {
    if (CardGazetteer.isIdentifierQuery(analyzed.raw)) return keywordHitCount == 0
    if (gazetteer == null) return false
    // 질의가 **데이터에 실재하는 사람**을 이미 지목했는지 먼저 본다.
    // 지역 오탐을 막는 근거다: 분석기가 조사 붙은 형태를 그대로 남기는 탓에 "이메일도"·
    // "회사도" 가 '도(道)로 끝나는 3자 이상' 조건에 걸려 없는 지역으로 판정됐다
    // (실측: "두미영 회사와 이메일도 알려줘" -> 기권. "이메일"만 쓰면 정상).
    // 토큰 모양만으로는 "울릉도"와 구분이 안 된다 — 둘 다 같은 규칙에 걸린다. 그래서
    // '이미 실재하는 사람을 지목했으면 곁가지 토큰의 지명 오탐으로 전체를 기권시키지
    // 않는다'로 가른다. 없는 지역만 물은 질의("울릉도 근무자")는 지목된 사람이 없어 그대로 기권.
    val namedRealPerson = analyzed.keywordTokens.any {
        gazetteer.looksLikePersonName(it) && gazetteer.nameExists(gazetteer.personNameStem(it))
    }
    for (token in analyzed.keywordTokens) {
        if (gazetteer.looksLikeRegion(token) && !gazetteer.regionExists(token) && !namedRealPerson) return true
        if (gazetteer.looksLikePersonName(token)) {
            val stem = gazetteer.personNameStem(token)
            if (gazetteer.nameExists(stem)) continue
            // 호칭이 붙었으면 사람 지목이 확실하므로 바로 기권.
            if (CardGazetteer.NAME_HONORIFICS.any { token.endsWith(it) }) return true
            // 호칭 없는 맨 3자는 성으로 시작하는 일반어일 수 있다. 키워드가 무언가 찾았다면
            // 그 토큰은 데이터에 실재하는 말이므로 이름 오탐으로 보고 기권하지 않는다.
            if (keywordHitCount == 0) return true
        }
    }
    return false
}
