package com.hjp.tool.contact

/**
 * 붙여 쓴 질의를 **색인에 실재하는 낱말들로** 가른다.
 *
 * 왜 필요한가(실측): "판교개발자" 를 치면 키워드 결과가 0건이었다. FTS4 의 unicode61 은 공백과
 * 구두점에서만 자르는데 한국어는 붙여 써도 말이 되므로, 이 질의는 낱말 **하나**가 된다:
 *
 * ```
 * 판교개발자   ->  [판교개발자]      색인에는 이런 낱말이 없다
 * 판교 개발자  ->  [판교, 개발자]     둘 다 색인에 있다
 * ```
 *
 * 색인에는 "판교"(주소)와 "개발자"(직함)가 **따로** 들어 있다. 그래서 어느 티어에서도 맞지
 * 않는다. 접두어 색인도 방향이 반대라 도움이 안 된다 — 그건 짧은 질의로 긴 낱말을 찾는 것이지,
 * 긴 질의로 짧은 낱말을 찾는 게 아니다.
 *
 * 자를 자리는 어휘가 아니라 **색인**에게 묻는다. 가능한 모든 자리에서 잘라 보고, 양쪽이 둘 다
 * 색인에 있는 자리를 고른다. 사람이 적은 낱말 목록으로 맞히려 하지 않는다 — [StemDisambiguation]
 * 과 같은 방식이고, 같은 이유다.
 *
 * 보수적으로 판정한다: 통째로 색인에 있는 낱말은 건드리지 않고, 자를 자리를 못 찾으면 원본을
 * 그대로 둔다(그때는 LIKE 티어가 받는다). 잘못 가르면 멀쩡한 검색이 사라지지만, 못 가르면
 * 예전과 같을 뿐이다.
 */
object GluedTermSplitter {

    /** 이보다 짧으면 가를 것이 없다(두 글자 + 두 글자). */
    private const val MIN_GLUED_LENGTH = 4

    /** 조각 하나의 최소 길이. 한 글자 조각은 아무 데나 걸려서 쓸모가 없다. */
    private const val MIN_PART = 2

    /** 몇 번까지 더 가를지. "서울판교개발자" 처럼 셋 이상 붙은 경우를 위한 것이다. */
    private const val MAX_DEPTH = 3

    /**
     * @param terms 토크나이저가 내놓은 낱말들.
     * @param existsInIndex 그 낱말이 색인에 있는지. 보통 FTS MATCH 한 건 조회다.
     */
    suspend fun split(
        terms: List<String>,
        existsInIndex: suspend (String) -> Boolean,
    ): List<String> {
        val out = ArrayList<String>(terms.size)
        for (term in terms) {
            splitOne(term, existsInIndex, MAX_DEPTH).forEach { part ->
                if (part !in out) out += part
            }
        }
        return out
    }

    private suspend fun splitOne(
        term: String,
        existsInIndex: suspend (String) -> Boolean,
        depth: Int,
    ): List<String> {
        if (depth <= 0 || term.length < MIN_GLUED_LENGTH) return listOf(term)
        // 숫자·기호가 섞인 것은 건드리지 않는다. 전화번호와 이메일은 다른 규칙이 맡는다.
        if (!term.all { it.isLetter() }) return listOf(term)
        // 통째로 색인에 있으면 그게 진짜 낱말이다.
        if (existsInIndex(term)) return listOf(term)

        for (cut in MIN_PART..(term.length - MIN_PART)) {
            val head = term.substring(0, cut)
            val tail = term.substring(cut)
            if (!existsInIndex(head)) continue
            if (existsInIndex(tail)) return listOf(head, tail)
            // 뒤쪽이 또 붙어 있을 수 있다("판교개발자팀장").
            val rest = splitOne(tail, existsInIndex, depth - 1)
            if (rest.size > 1) return listOf(head) + rest
        }
        return listOf(term)
    }
}
