package com.hjp.tool.contact

/**
 * 명함 한 장을 키워드 색인에 넣을 문자열로 만든다. **규칙은 여기 한 벌뿐이다.**
 *
 * 폰(Room FTS4)과 노트북(SQLite FTS4)이 같은 문자열을 색인해야 한다 — 둘이 갈리면 노트북에서
 * 맞춘 검색이 폰에서 안 맞고, 어느 쪽이 맞는지 판단할 근거가 없어진다. 예전에는 앱과 데스크톱이
 * 각자 같은 목록을 손으로 들고 있었고, 실제로 한쪽에만 주소·이메일이 빠져 있던 적이 있다.
 */
object SearchIndexText {

    /**
     * 색인 문자열. 명함에 적힌 것은 **전부** 들어간다.
     *
     * 사람은 기억나는 조각으로 찾는다 — 주소의 동네 이름, 메모에 적어 둔 "기술교류회에서 만남",
     * 회사 홈페이지 주소. 어느 칸에 있었는지는 기억하지 못한다.
     *
     * 주소·이메일이 빠져 있었던 적이 있다. "판교"는 location("경기도 성남시 분당구")이 아니라
     * address 에만 있어서, 판교 명함 9장이 키워드로 한 건도 안 잡히고 의미검색만 남아 엉뚱한
     * 지역이 나왔다(실측).
     */
    fun build(
        name: String,
        nameEn: String,
        company: String,
        title: String,
        department: String,
        industry: String,
        location: String,
        address: String,
        email: String,
        website: String,
        memo: String,
        tags: String,
        phone: String,
        mobile: String,
    ): String {
        val fields = listOf(
            name, nameEn, company, title, department, industry, location,
            address, email, website, memo, tags, phone, mobile,
        ).joinToString(" ")
        return buildString {
            append(fields)
            append(' ')
            // 하이픈 없이 친 전화번호("01012345678")로도 찾을 수 있게 숫자만 남긴 사본.
            append(phone.filter(Char::isDigit))
            append(' ')
            append(mobile.filter(Char::isDigit))
            append(' ')
            append(hangulBigrams(fields))
        }.lowercase()
    }

    /**
     * 한글 낱말을 두 글자씩 겹쳐 잘라 함께 색인한다.
     *
     * FTS4 의 unicode61 토크나이저는 **공백과 구두점에서만** 자른다. 한국어는 붙여 써도 말이
     * 되기 때문에, 색인에 "비전글로벌" 한 낱말만 들어가면 "전글"로는 영원히 못 찾는다. 낱말
     * 안쪽을 가리키는 질의를 받으려면 안쪽 조각도 색인에 있어야 한다.
     *
     * 접두어 색인(`prefix={2,3,4}`)으로는 부족하다 — 그건 낱말 **앞쪽**만 훑는다.
     * "비전"은 잡아도 "전글"·"글로벌"은 못 잡는다.
     *
     * 두 글자가 안 되는 낱말은 건너뛴다: 자기 자신이 이미 색인에 있으므로 보탤 것이 없다.
     * 상류(ryeong)의 `BusinessCardEntity.hangulBigrams` 와 같은 규칙이다.
     */
    internal fun hangulBigrams(normalized: String): String = buildString {
        normalized.split(WHITESPACE).forEach { token ->
            if (token.length < 3 || !token.any(::isHangul)) return@forEach
            for (i in 0 until token.length - 1) {
                append(token, i, i + 2)
                append(' ')
            }
        }
    }.trim()

    private val WHITESPACE = Regex("\\s+")

    private fun isHangul(c: Char): Boolean =
        Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HANGUL
}
