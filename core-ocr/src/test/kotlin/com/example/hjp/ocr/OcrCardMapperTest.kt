package com.example.hjp.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrCardMapperTest {

    /** 데스크톱 러너가 out/final/images/000000.png 에서 실제로 뽑아낸 필드. */
    private fun realCardFields() = listOf(
        CardParser.Field("👤", "이름", "김정"),
        CardParser.Field("💼", "직함", "본부장"),
        CardParser.Field("🏢", "회사", "캐피탈건설"),
        CardParser.Field("📞", "전화", "1588-4313"),
        CardParser.Field("✉️", "이메일", "jeonggim@example.net"),
        CardParser.Field("📍", "주소", "충청남도당진시합덕로130-25"),
        CardParser.Field("🔖", "로고", "코비하우스"),
    )

    @Test
    fun `인식 필드를 명함 레코드로 옮긴다`() {
        val card = OcrCardMapper.toCard(realCardFields(), id = "OCR001", updatedAtMillis = 1L)

        assertEquals("OCR001", card.id)
        assertEquals("김정", card.name)
        assertEquals("본부장", card.title)
        assertEquals("캐피탈건설", card.company)
        assertEquals("1588-4313", card.phone)
        assertEquals("jeonggim@example.net", card.email)
        assertEquals("충청남도당진시합덕로130-25", card.address)
    }

    @Test
    fun `주소에서 지역명을 뽑아 location 에 넣는다`() {
        // 지역 필터는 주소 전문이 아니라 이 필드로 매칭한다. 비어 있으면 "충청남도 사람 찾아줘"가 걸리지 않는다.
        val card = OcrCardMapper.toCard(realCardFields(), id = "OCR001", updatedAtMillis = 1L)
        assertEquals("충청남도", card.location)
    }

    @Test
    fun `주소 형태가 달라도 첫 지역명 하나만 뽑는다`() {
        // OCR 은 공백을 자주 잃는다. 붙어 나온 주소에서 두 지역이 한 덩어리로 잡히면 안 된다.
        val cases = mapOf(
            "충청남도당진시합덕로130-25" to "충청남도",
            "서울특별시 강남구 테헤란로 123" to "서울특별시",
            "경기도 성남시 분당구 판교역로 20" to "경기도",
            "부산광역시해운대구센텀로 45" to "부산광역시",
        )
        for ((address, expected) in cases) {
            val card = OcrCardMapper.toCard(
                listOf(CardParser.Field("📍", "주소", address)),
                id = "X", updatedAtMillis = 1L,
            )
            assertEquals("주소 '$address' 에서", expected, card.location)
        }
    }

    @Test
    fun `휴대폰이 있으면 대표 번호로 삼는다`() {
        val fields = realCardFields() + CardParser.Field("📱", "휴대폰", "010-6948-6596")
        val card = OcrCardMapper.toCard(fields, id = "OCR001", updatedAtMillis = 1L)
        assertEquals("010-6948-6596", card.phone)
    }

    @Test
    fun `엔티티에 자리 없는 필드는 메모로 보존해 검색에 걸리게 한다`() {
        val card = OcrCardMapper.toCard(realCardFields(), id = "OCR001", updatedAtMillis = 1L)

        // memo 는 FTS 인덱스에 들어가는 칸이라(app 의 BusinessCardDao.toFtsEntity) 로고명으로도
        // 검색이 걸린다. 인덱스 문자열을 만드는 일은 이제 저장소 쪽 관심사라 여기서는
        // **잃지 않았다는 것**만 본다.
        assertTrue("로고명이 메모에 남아야 한다: ${card.memo}", card.memo.contains("코비하우스"))
    }

    @Test
    fun `필드가 하나도 없어도 빈 문자열로 안전하게 만든다`() {
        val card = OcrCardMapper.toCard(emptyList(), id = "EMPTY", updatedAtMillis = 1L)

        assertEquals("", card.name)
        assertEquals("", card.location)
        assertEquals("", card.memo)
    }
}
