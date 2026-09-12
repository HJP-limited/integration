package com.example.hjp.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * KIE 분류 **뒤에** 붙는 정리 단계. `OCR/grouping.py` 이식본이다.
 *
 * 검출기는 글줄 단위로 자른다. 명함에 적힌 것과 글줄은 일대일이 아니라서 두 가지가 어긋난다:
 *
 *  - **한 칸이 여러 줄에 걸친다.** 주소가 대표적이다. "경기도 성남시 분당구" / "판교역로 123,
 *    4층" 이 두 줄로 잡히면 주소가 반 토막 난 채 저장되고, 나중에 "판교" 로도 "성남" 으로도
 *    반쪽만 맞는다.
 *  - **한 줄에 여러 칸이 들어 있다.** "TEL 031-000-0000 FAX 031-000-0001" 이나
 *    "한우겸 WooKyum Han" 처럼. 통째로 한 칸에 넣으면 전화번호 칸에 팩스까지 들어간다.
 *
 * 순서가 중요하다: **먼저 가르고, 그다음 합친다.** 반대로 하면 방금 합친 주소를 다시 가른다.
 *
 * 공간 묶기를 KIE **앞에** 두지 않는 이유도 원본에 적혀 있다: 빽빽한 한국어 명함에서는
 * 이름/직함/이메일이 줄바꿈된 주소와 세로 간격만으로는 구별되지 않아 과하게 합쳐졌다.
 * 라벨을 보고 나서 합치면 안전한 경우만 합칠 수 있다.
 */
object FieldGrouping {

    /** 분류까지 끝난 글줄 하나. UI 필드로 바뀌기 **전**의 모양이다. */
    data class Labeled(
        val poly: List<OcrPipeline.Pt>,
        val text: String,
        val field: String,
        val score: Float,
    )

    /** 줄바꿈이 정상인 칸. 이름·직함·휴대폰 같은 한 줄짜리는 건드리지 않는다. */
    private const val MERGE_ANCHOR = "address_ko"
    private val MERGE_ABSORB = setOf("address_ko", "other")

    /**
     * 주소처럼 보이는 글월. 우편번호와 도로/행정구역 낱말을 본다.
     *
     * 라벨만 믿지 않는 이유: 줄바꿈된 **영문** 주소는 KIE 가 줄마다 다르게 붙이는 일이 잦다.
     * 글월 자체가 주소를 말하면 라벨과 무관하게 합칠 수 있게 둔다.
     */
    private val ADDR_TOKENS = Regex(
        "(?i)\\b(?:Rd|Road|St|Street|Ave|Avenue|Bldg|Floor|FL|Korea|Seoul|Gyeonggi|" +
            "Bundang|Gangnam|Incheon|Busan|Daejeon|Seongnam)\\b" +
            "|[가-힣]+(?:특별시|광역시|시|구|동|읍|면|로|길)\\b|\\(\\d{4,5}\\)|\\b\\d{5}\\b",
    )

    private fun isAddressLike(text: String): Boolean = ADDR_TOKENS.containsMatchIn(text)

    /** 한 줄에 여러 연락처가 있을 때 자를 자리. 라벨 앞, 또는 숫자 뒤의 라벨 앞에서 자른다. */
    private val CONTACT_SPLIT = Regex(
        "(?i)(?=(?:H\\.?P|TEL|FAX|CELL|MOBILE|PHONE)\\b|(?<=\\d)\\s*(?:FAX|TEL|CELL))",
    )

    private val DIGIT_RUN = Regex("\\d{3,}")
    private val MOBILE = Regex("01[016-9][-. ]?\\d{3,4}[-. ]?\\d{4}")
    private val TEL = Regex("0\\d{1,2}[-. ]?\\d{3,4}[-. ]?\\d{4}")
    private val KO_EN_NAME = Regex("^\\s*([가-힣]{2,4})\\s+([A-Za-z].*[A-Za-z])\\s*$")

    /** 가르고 나서 합친다. 순서를 바꾸면 방금 합친 주소를 다시 가른다. */
    fun postprocess(fields: List<Labeled>, imageWidth: Int, imageHeight: Int): List<Labeled> =
        mergeMultiline(splitMultifield(fields), imageWidth, imageHeight)

    // ---- 1) 한 줄에 들어 있는 여러 칸을 가른다 ------------------------------------------------

    fun splitMultifield(fields: List<Labeled>): List<Labeled> =
        fields.flatMap { field -> splitContacts(field) ?: splitKoreanEnglishName(field) ?: listOf(field) }

    /**
     * "TEL 031-000-0000 FAX 031-000-0001" 을 둘로 가른다.
     *
     * 숫자 덩어리가 둘 이상일 때만 시도한다 — 하나뿐이면 가를 것이 없고, 멀쩡한 한 줄을
     * 라벨 낱말 하나 때문에 쪼갤 위험만 남는다. 가른 뒤에도 알아본 칸이 둘 미만이면 포기하고
     * 원본을 그대로 쓴다.
     */
    private fun splitContacts(field: Labeled): List<Labeled>? {
        if (DIGIT_RUN.findAll(field.text).count() < 2) return null
        val found = CONTACT_SPLIT.split(field.text)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull { segment ->
                classifyContact(segment)?.let { field.copy(field = it, text = segment) }
            }
        return found.takeIf { it.size >= 2 }
    }

    /** 조각 하나가 어떤 연락처인지. 라벨 낱말을 먼저 보고, 없으면 번호 모양으로 판단한다. */
    private fun classifyContact(segment: String): String? {
        val low = segment.lowercase()
        return when {
            "fax" in low -> "fax"
            listOf("h.p", "hp", "cell", "mobile", "phone").any { it in low } -> "mobile"
            "tel" in low -> "tel_office"
            MOBILE.containsMatchIn(segment) -> "mobile"
            TEL.containsMatchIn(segment) -> "tel_office"
            else -> null
        }
    }

    /** "한우겸 WooKyum Han" 을 한글 이름과 영문 이름으로 가른다. */
    private fun splitKoreanEnglishName(field: Labeled): List<Labeled>? {
        val m = KO_EN_NAME.matchEntire(field.text) ?: return null
        return listOf(
            field.copy(field = "name_ko", text = m.groupValues[1]),
            field.copy(field = "name_en", text = m.groupValues[2]),
        )
    }

    // ---- 2) 여러 줄에 걸친 주소를 합친다 ------------------------------------------------------

    /**
     * 주소 줄 아래에 바짝 붙어 같은 열에서 시작하는 줄들을 이어 붙인다.
     *
     * 세 조건을 **모두** 본다: 합칠 수 있는 라벨이거나 주소처럼 보일 것, 세로 간격이 글자
     * 높이보다 좁을 것(줄바꿈이지 다른 칸이 아니라는 뜻), 왼쪽 끝이 거의 같을 것(같은 열).
     * 하나라도 빠지면 이름·직함처럼 붙어 있는 다른 칸까지 주소로 삼킨다.
     *
     * 글자 높이는 이 명함에서 **실제로 잰 값**(중앙값)을 쓴다. 고정 픽셀 수로 정하면 해상도가
     * 바뀔 때마다 틀린다.
     */
    fun mergeMultiline(
        fields: List<Labeled>,
        imageWidth: Int,
        imageHeight: Int,
        verticalGapFactor: Double = 0.8,
        xAlignFraction: Double = 0.08,
    ): List<Labeled> {
        if (fields.isEmpty()) return emptyList()
        val items = fields.sortedWith(compareBy({ it.top }, { it.left }))
        val medianHeight = items.map { it.bottom - it.top }.sorted().let { heights ->
            val mid = heights.size / 2
            if (heights.size % 2 == 1) heights[mid] else (heights[mid - 1] + heights[mid]) / 2.0
        }.takeIf { it > 0.0 } ?: 1.0

        val out = ArrayList<Labeled>(items.size)
        val used = BooleanArray(items.size)
        for (i in items.indices) {
            if (used[i]) continue
            val anchor = items[i]
            if (anchor.field != MERGE_ANCHOR && !isAddressLike(anchor.text)) {
                out += anchor
                continue
            }
            val group = arrayListOf(anchor)
            used[i] = true
            val anchorLeft = anchor.left
            for (j in (i + 1) until items.size) {
                if (used[j]) continue
                val next = items[j]
                val gap = next.top - group.last().bottom
                val absorbable = next.field in MERGE_ABSORB || isAddressLike(next.text)
                if (absorbable && gap >= -medianHeight && gap < verticalGapFactor * medianHeight &&
                    abs(next.left - anchorLeft) < xAlignFraction * imageWidth
                ) {
                    group += next
                    used[j] = true
                } else if (gap > 1.5 * medianHeight) {
                    // 멀리 떨어진 줄부터는 이 주소와 무관하다. 더 내려가면 아래쪽의
                    // 주소처럼 생긴 다른 칸까지 끌어온다.
                    break
                }
            }
            out += if (group.size > 1) combine(group) else anchor
        }
        return out.sortedWith(compareBy({ it.top }, { it.left }))
    }

    /** 합친 결과 하나. 신뢰도는 **가장 낮은 조각**을 따른다 — 합친 값은 가장 약한 고리만큼만 믿을 수 있다. */
    private fun combine(group: List<Labeled>): Labeled {
        val ordered = group.sortedBy { it.top }
        val x0 = ordered.minOf { it.left }
        val y0 = ordered.minOf { it.top }
        val x1 = ordered.maxOf { it.right }
        val y1 = ordered.maxOf { it.bottom }
        return Labeled(
            poly = listOf(
                OcrPipeline.Pt(x0.toFloat(), y0.toFloat()),
                OcrPipeline.Pt(x1.toFloat(), y0.toFloat()),
                OcrPipeline.Pt(x1.toFloat(), y1.toFloat()),
                OcrPipeline.Pt(x0.toFloat(), y1.toFloat()),
            ),
            text = ordered.mapNotNull { it.text.trim().ifEmpty { null } }.joinToString(" "),
            field = MERGE_ANCHOR,
            score = ordered.minOf { it.score },
        )
    }

    // 기하 계산은 Double 로 한다. Float 로 섞으면 중앙값·간격 비교에서 자리마다 변환이 끼어
    // 읽기 어려워지고, 정밀도로 득 볼 것도 없다.
    private val Labeled.left: Double get() = poly.minOf { it.x.toDouble() }
    private val Labeled.top: Double get() = poly.minOf { it.y.toDouble() }
    private val Labeled.right: Double get() = poly.maxOf { it.x.toDouble() }
    private val Labeled.bottom: Double get() = poly.maxOf { it.y.toDouble() }
}
