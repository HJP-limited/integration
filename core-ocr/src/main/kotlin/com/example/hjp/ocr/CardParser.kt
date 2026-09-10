package com.example.hjp.ocr

import kotlin.math.hypot

/**
 * 정규식·글자크기 기반 명함 필드 분류기.
 *
 * [KieParser] (fine-tuned MiniLM, 라인 정확도 98.0%) 가 정식 경로이고 이쪽은
 * **KIE 모델이 없을 때의 폴백**이다(85.3%). 값 정제 정규식은 KIE 경로도 재사용한다.
 */
object CardParser {

    data class Field(val icon: String, val label: String, val value: String)

    private val EMAIL = Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""")
    private val URL = Regex("""(?i)\b(?:www\.|https?://)?[\w-]+(\.[\w-]+)*\.(?:com|net|org|io|co\.kr|kr|ai)\b""")
    private val PHONE = Regex("""(?:0\d{1,2}|1\d{3})[-. ]?\d{3,4}[-. ]?\d{4}""")
    private val MOBILE_PREFIX = Regex("""(?i)^\s*(M|Mobile|HP|H\.P|휴대폰|핸드폰)\b|^\s*01[016789]""")
    private val FAX_PREFIX = Regex("""(?i)^\s*(F|Fax|팩스)[.: ]""")
    private val ADDRESS = Regex("""(?:특별시|광역시|자치시|[가-힣]{1,8}(?:시|도)\s)|(?:Address|주소)|(?:[가-힣]+(?:로|길)\s?\d)""")
    private val TITLES = setOf(
        "대표", "대표이사", "이사", "상무", "전무", "부사장", "사장", "회장", "감사",
        "본부장", "실장", "팀장", "부장", "차장", "과장", "대리", "주임", "사원", "수석",
        "책임", "선임", "연구원", "매니저", "프로",
        "CEO", "CTO", "CFO", "CMO", "COO", "CIO", "Director", "Manager", "Lead")
    private val TITLE_PATTERN = Regex("""(?i)^(?:Head of [\w ]+|Chief [\w ]+ Officer)$""")
    private val DEPT_SUFFIX = Regex("""[가-힣A-Za-z]+(팀|부|실|본부|센터|사업부|연구소)$""")
    private val KO_NAME = Regex("""^[가-힣]{2,4}$""")
    private val EN_NAME = Regex("""^[A-Z][a-z]+(?: [A-Z][a-z]+){1,2}$""")
    private val COMPANY = Regex("""^\(주\)|^\(유\)|(?:주식회사|Inc\.|Co\.,? ?Ltd)""")

    fun parse(regions: List<OcrPipeline.Region>): List<Field> {
        val fields = ArrayList<Field>()
        val used = BooleanArray(regions.size)

        fun claim(i: Int, icon: String, label: String, value: String) {
            fields.add(Field(icon, label, value)); used[i] = true
        }

        // pass 1 — unambiguous patterns
        regions.forEachIndexed { i, r ->
            val t = r.text.trim()
            when {
                EMAIL.containsMatchIn(t) ->
                    claim(i, "✉️", "이메일", EMAIL.find(t)!!.value)
                FAX_PREFIX.containsMatchIn(t) && PHONE.containsMatchIn(t) ->
                    claim(i, "📠", "팩스", PHONE.find(t)!!.value)
                PHONE.containsMatchIn(t) -> {
                    val num = PHONE.find(t)!!.value
                    val isMobile = MOBILE_PREFIX.containsMatchIn(t) ||
                        num.replace(Regex("[-. ]"), "").startsWith("01")
                    claim(i, if (isMobile) "📱" else "📞",
                        if (isMobile) "휴대폰" else "전화", num)
                }
                ADDRESS.containsMatchIn(t) && t.length >= 8 ->
                    claim(i, "📍", "주소",
                        t.replace(Regex("""(?i)^\s*(Address|주소)[.: ]*"""), "")
                            .trim { c -> c.isWhitespace() || c in "↑↓·°" })
                COMPANY.containsMatchIn(t) ->
                    claim(i, "🏢", "회사", t)
                URL.containsMatchIn(t) && !t.contains('@') ->
                    claim(i, "🌐", "웹", URL.find(t)!!.value
                        .let { u -> t.replace(Regex("""(?i)^\s*(W|Web|Website|Homepage)[.: ]*"""), "").trim().ifEmpty { u } })
            }
        }

        // pass 2 — dictionary/shape based (title, dept, names)
        regions.forEachIndexed { i, r ->
            if (used[i]) return@forEachIndexed
            val t = r.text.trim()
            when {
                t in TITLES || TITLE_PATTERN.matches(t) ->
                    claim(i, "💼", "직함", t)
                DEPT_SUFFIX.matches(t) ->
                    claim(i, "👥", "부서", t)
            }
        }

        // pass 3 — Korean name: prefer the tallest unclaimed 2–4 char hangul line
        val nameCandidates = regions.withIndex().filter { (i, r) ->
            !used[i] && KO_NAME.matches(r.text.trim())
        }
        nameCandidates.maxByOrNull { (_, r) -> polyHeight(r) }?.let { (i, r) ->
            fields.add(0, Field("👤", "이름", r.text.trim())); used[i] = true
        }
        regions.forEachIndexed { i, r ->
            if (!used[i] && EN_NAME.matches(r.text.trim()))
                claim(i, "👤", "영문명", r.text.trim())
        }

        // leftovers worth showing (logo text etc.) — skip noise and bare
        // field labels whose value landed in another detection box
        val labelOnly = Regex("""(?i)^(W|Web|Website|T|Tel|M|Mobile|F|Fax|E|E-?mail|H|HP|Address|주소|전화|휴대폰|팩스|이메일)[.:]?$""")
        regions.forEachIndexed { i, r ->
            val t = r.text.trim()
            if (!used[i] && t.length >= 2 && !labelOnly.matches(t))
                claim(i, "🔖", "기타", t)
        }

        // stable ordering by label priority
        val order = listOf("이름", "영문명", "직함", "부서", "회사",
            "휴대폰", "전화", "팩스", "이메일", "웹", "주소", "기타")
        return fields.sortedBy { order.indexOf(it.label).let { p -> if (p < 0) 99 else p } }
    }

    private fun polyHeight(r: OcrPipeline.Region): Float {
        val p = r.poly
        if (p.size < 4) return 0f
        return hypot((p[0].x - p[3].x).toDouble(), (p[0].y - p[3].y).toDouble()).toFloat()
    }
}
