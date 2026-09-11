package com.hjp.tool.contact

/**
 * 조사를 뗀 조각과 원본 중 **데이터에 실재하는 쪽만** 남긴다.
 *
 * 토크나이저는 손으로 적은 조사 목록으로 꼬리를 떼므로 잘못 뗄 수 있다. 그래서 뗀 조각과
 * 원본을 둘 다 내놓는다 — 어느 쪽이 맞는지 토크나이저는 모르기 때문이다. 문제는 그 다음이다:
 *
 * ```
 * "정하은"  ->  ["정하", "정하은"]     전체단어 티어: 정하 AND 정하은  -> 실패
 * "판교에"  ->  ["판교", "판교에"]     전체단어 티어: 판교 AND 판교에  -> 실패
 *                                      접두어   티어: 판교* 판교에*    -> 실패 (LIKE 까지 밀림)
 * ```
 *
 * 둘 다 필수 조건으로 넣으면 **반드시 하나는 안 맞는다.** 정답을 못 찾는 건 아니지만 티어가
 * 아래로 밀리고, 가장 약한 LIKE 단계가 순위를 매기게 된다. 티어를 나눈 이유가 사라진다.
 *
 * 판정은 어휘가 아니라 **색인**에게 묻는다. "정하은"이 색인에 있으면 은은 조사가 아니었고,
 * "판교에"가 없으면 에는 조사였다. 사람이 적은 목록으로 맞히려 하지 않는다 — 그게 애초에
 * 이 문제를 만든 방식이다.
 *
 * 쌍이 아닌 낱말은 그대로 둔다. 확인은 쌍 하나에 색인 조회 한 번이다.
 */
object StemDisambiguation {

    /**
     * @param terms 토크나이저가 내놓은 낱말들. 뗀 조각과 원본이 섞여 있다.
     * @param existsInIndex 그 낱말이 색인에 있는지. 보통 FTS MATCH 한 건 조회다.
     */
    suspend fun resolve(terms: List<String>, existsInIndex: suspend (String) -> Boolean): List<String> {
        if (terms.size < 2) return terms
        val dropped = HashSet<String>()
        for (stem in terms) {
            for (original in terms) {
                if (stem == original || original in dropped || stem in dropped) continue
                if (!isStemOf(stem, original)) continue
                // 원본이 색인에 있으면 꼬리는 낱말의 일부였다 — 조각을 버린다.
                // 없으면 꼬리는 조사였다 — 원본을 버린다.
                if (existsInIndex(original)) dropped += stem else dropped += original
            }
        }
        return terms.filterNot { it in dropped }
    }

    /**
     * 숫자만 남긴 사본이 쪼갠 조각들을 이어 붙인 것과 같으면 버린다.
     *
     * "010-3000-6000" 은 낱말 셋(010·3000·6000)으로 쪼개지는데, 토크나이저가 숫자만 남긴
     * 사본("01030006000")도 함께 내놓는다. 둘 다 필수 조건으로 넣으면 **정확 구문 티어가
     * 네 낱말이 나란히 있기를** 요구하게 되고, 색인에서 그 넷이 붙어 있는지는 다른 칸(휴대폰)이
     * 비었는지 같은 우연에 달린다. 조각들이 이미 있으면 이어 붙인 사본은 보탤 게 없다.
     *
     * 반대로 사용자가 "01030006000" 처럼 붙여서 친 경우에는 쪼갤 조각이 없으므로 그대로 남는다 —
     * 그때는 색인에 함께 넣어 둔 숫자 사본이 받아 준다.
     */
    fun dropRedundantGluedDigits(terms: List<String>): List<String> {
        val digitTerms = terms.filter { term -> term.all(Char::isDigit) }
        if (digitTerms.size < 2) return terms
        val parts = digitTerms.filter { it.length < MAX_DIGIT_PART }
        if (parts.isEmpty()) return terms
        val glued = parts.joinToString("")
        return terms.filterNot { it.all(Char::isDigit) && it == glued }
    }

    /** 이보다 짧은 숫자 덩어리는 전화번호를 쪼갠 조각으로 본다. */
    private const val MAX_DIGIT_PART = 8

    /**
     * [stem] 이 [original] 에서 꼬리를 뗀 모양인가.
     *
     * 꼬리는 한두 음절까지만 본다. 그보다 길면 조사가 아니라 다른 낱말이고, 우연히 앞이
     * 겹치는 남남("판교"와 "판교역로")을 쌍으로 묶으면 멀쩡한 조건이 사라진다.
     */
    private fun isStemOf(stem: String, original: String): Boolean {
        if (stem.length < 2 || original.length <= stem.length) return false
        val tail = original.length - stem.length
        return tail <= 2 && original.startsWith(stem)
    }
}
