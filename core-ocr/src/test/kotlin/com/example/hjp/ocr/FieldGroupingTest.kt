package com.example.hjp.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `OCR/grouping.py` 이식본 검증.
 *
 * 검출기는 글줄 단위로 자르고, 명함의 칸과 글줄은 일대일이 아니다. 이 단계가 없으면
 * 줄바꿈된 주소가 반 토막 나고 "TEL … FAX …" 한 줄이 통째로 전화번호가 된다.
 *
 * 좌표는 전부 합성이다. 명함 너비는 600px 로 잡았다.
 */
class FieldGroupingTest {

    private val cardWidth = 600
    private val cardHeight = 340

    /** 글줄 하나. 높이 20px 로 고정해 세로 간격 판정을 눈에 보이게 한다. */
    private fun line(text: String, field: String, left: Int, top: Int, width: Int = 300): FieldGrouping.Labeled =
        FieldGrouping.Labeled(
            poly = listOf(
                OcrPipeline.Pt(left.toFloat(), top.toFloat()),
                OcrPipeline.Pt((left + width).toFloat(), top.toFloat()),
                OcrPipeline.Pt((left + width).toFloat(), (top + 20).toFloat()),
                OcrPipeline.Pt(left.toFloat(), (top + 20).toFloat()),
            ),
            text = text,
            field = field,
            score = 0.9f,
        )

    // ---- 여러 줄에 걸친 주소 ------------------------------------------------------------------

    @Test
    fun `a wrapped address is joined into one field`() {
        val lines = listOf(
            line("경기도 성남시 분당구", "address_ko", left = 40, top = 200),
            line("판교역로 123, 4층", "other", left = 42, top = 222),
        )
        val out = FieldGrouping.postprocess(lines, cardWidth, cardHeight)
        assertEquals(1, out.size)
        assertEquals("경기도 성남시 분당구 판교역로 123, 4층", out.single().text)
        assertEquals("address_ko", out.single().field)
    }

    @Test
    fun `a line in a different column is not absorbed into the address`() {
        // 오른쪽 열에 따로 적힌 값은 주소의 다음 줄이 아니다.
        val lines = listOf(
            line("경기도 성남시 분당구", "address_ko", left = 40, top = 200, width = 200),
            line("서울특별시 강남구", "other", left = 400, top = 222, width = 150),
        )
        val out = FieldGrouping.postprocess(lines, cardWidth, cardHeight)
        assertEquals(2, out.size)
    }

    @Test
    fun `a line far below the address is left alone`() {
        val lines = listOf(
            line("경기도 성남시 분당구", "address_ko", left = 40, top = 100),
            line("부산광역시 해운대구", "other", left = 40, top = 260),
        )
        val out = FieldGrouping.postprocess(lines, cardWidth, cardHeight)
        assertEquals(2, out.size)
    }

    @Test
    fun `single-line fields are never merged`() {
        // 이름·직함·휴대폰이 바짝 붙어 있어도 합쳐선 안 된다. KIE 앞에서 공간만 보고 묶던
        // 방식이 정확히 여기서 과하게 합쳤다.
        val lines = listOf(
            line("남다은", "name_ko", left = 40, top = 40),
            line("상무", "title", left = 40, top = 58),
            line("010-0000-0000", "mobile", left = 40, top = 76),
        )
        val out = FieldGrouping.postprocess(lines, cardWidth, cardHeight)
        assertEquals(3, out.size)
    }

    @Test
    fun `an English address KIE mislabelled line by line still merges`() {
        // 라벨만 믿으면 못 합친다 — 글월이 주소를 말하면 라벨과 무관하게 합친다.
        val lines = listOf(
            line("123 Pangyo-ro, Bundang-gu", "other", left = 40, top = 200),
            line("Seongnam, Gyeonggi, Korea", "other", left = 41, top = 222),
        )
        val out = FieldGrouping.postprocess(lines, cardWidth, cardHeight)
        assertEquals(1, out.size)
        assertTrue(out.single().text, out.single().text.contains("Seongnam"))
    }

    // ---- 한 줄에 들어 있는 여러 칸 ------------------------------------------------------------

    @Test
    fun `a line carrying both TEL and FAX is split`() {
        val lines = listOf(line("TEL 031-000-0000 FAX 031-000-0001", "tel_office", left = 40, top = 150))
        val out = FieldGrouping.splitMultifield(lines)
        assertEquals(2, out.size)
        assertEquals("tel_office", out[0].field)
        assertEquals("fax", out[1].field)
    }

    @Test
    fun `a mobile label on a shared line becomes the mobile field`() {
        val lines = listOf(line("H.P 010-0000-0000 TEL 031-000-0000", "other", left = 40, top = 150))
        val out = FieldGrouping.splitMultifield(lines)
        assertEquals(listOf("mobile", "tel_office"), out.map { it.field })
    }

    @Test
    fun `a line with a single number is left whole`() {
        // 숫자 덩어리가 하나뿐이면 가를 것이 없다. 라벨 낱말 하나로 쪼개면 멀쩡한 값이 깨진다.
        val lines = listOf(line("TEL 031-000-0000", "tel_office", left = 40, top = 150))
        val out = FieldGrouping.splitMultifield(lines)
        assertEquals(1, out.size)
        assertEquals("TEL 031-000-0000", out.single().text)
    }

    @Test
    fun `a Korean and English name on one line is split into two`() {
        val lines = listOf(line("한우겸 WooKyum Han", "name_ko", left = 40, top = 40))
        val out = FieldGrouping.splitMultifield(lines)
        assertEquals(listOf("name_ko", "name_en"), out.map { it.field })
        assertEquals("한우겸", out[0].text)
        assertEquals("WooKyum Han", out[1].text)
    }

    @Test
    fun `splitting happens before merging`() {
        // 반대 순서면 방금 합친 주소를 다시 가른다.
        val lines = listOf(
            line("경기도 성남시 분당구", "address_ko", left = 40, top = 200),
            line("판교역로 123", "other", left = 40, top = 222),
            line("TEL 031-000-0000 FAX 031-000-0001", "tel_office", left = 40, top = 150),
        )
        val out = FieldGrouping.postprocess(lines, cardWidth, cardHeight)
        assertEquals(1, out.count { it.field == "address_ko" })
        assertEquals(1, out.count { it.field == "fax" })
        assertEquals("경기도 성남시 분당구 판교역로 123", out.first { it.field == "address_ko" }.text)
    }

    @Test
    fun `an empty page yields nothing`() {
        assertEquals(
            emptyList<FieldGrouping.Labeled>(),
            FieldGrouping.postprocess(emptyList(), cardWidth, cardHeight),
        )
    }

    // ---- 값 정리 (OCR/app.py _clean) ----------------------------------------------------------

    @Test
    fun `a one letter design label is not kept in the value`() {
        // "M." 은 휴대폰이라는 **신호**다. 값에 남으면 그대로 저장돼 검색이 안 맞는다.
        assertEquals("010-0000-0000", KieParser.clean("M. 010-0000-0000"))
        assertEquals("daeun@example.invalid", KieParser.clean("E: daeun@example.invalid"))
    }

    @Test
    fun `leading design junk is dropped`() {
        assertEquals("서울특별시 강남구", KieParser.clean("↑ 서울특별시 강남구"))
        assertEquals("010-0000-0000", KieParser.clean(") 010-0000-0000"))
    }

    @Test
    fun `a stray leading zero on a phone number is repaired`() {
        assertEquals("010-0000-0000", KieParser.clean("0010-0000-0000"))
    }

    @Test
    fun `an ordinary value is returned unchanged`() {
        assertEquals("남다은", KieParser.clean("남다은"))
        assertEquals("(주)비전글로벌", KieParser.clean("(주)비전글로벌"))
    }
}
