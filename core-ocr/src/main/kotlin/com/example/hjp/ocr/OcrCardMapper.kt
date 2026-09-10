package com.example.hjp.ocr

import com.example.hjp.data.BusinessCardEntity

/**
 * 인식된 필드를 검색 가능한 명함 레코드로 바꾼다 — OCR 트랙과 검색 트랙이 실제로 만나는 지점.
 *
 * [CardParser.Field] 는 화면 표시용(아이콘·한글 라벨)이라 라벨을 키로 매핑한다.
 * [KieParser] 도 같은 라벨 집합을 쓰므로 두 분류기 어느 쪽 결과든 그대로 들어온다.
 */
object OcrCardMapper {

    /**
     * 검색의 지역 필터가 쓰는 `location` 은 주소 전체가 아니라 지역명이어야 한다
     * (가제티어가 "판교"·"서울" 같은 토큰으로 매칭한다). 주소 앞부분에서 뽑아낸다.
     */
    // 수량자가 lazy 여야 **첫 번째** 지역명에서 끊긴다. greedy 로 두면 공백 없이 붙은 주소
    // ("충청남도당진시합덕로130-25")에서 "충청남도당진시"처럼 두 지역이 한 덩어리로 잡히고,
    // 가제티어가 그런 토큰으로는 매칭하지 못해 지역 검색이 조용히 실패한다.
    private val REGION = Regex("""([가-힣]+?(?:특별자치시|특별자치도|특별시|광역시|시|도|군|구))""")

    fun toCard(
        fields: List<CardParser.Field>,
        id: String,
        updatedAtMillis: Long,
    ): BusinessCardEntity {
        fun first(vararg labels: String): String =
            labels.firstNotNullOfOrNull { label ->
                fields.firstOrNull { it.label == label }?.value
            }.orEmpty()

        val address = first("주소")
        // 휴대폰이 있으면 그쪽이 대표 번호다 — 명함에서 실제로 연락하는 번호.
        val phone = first("휴대폰", "전화")

        // 엔티티에 자리가 없는 것들(웹·로고·슬로건·팩스·한자명)은 잃지 않고 메모로 모은다.
        // searchableText 가 memo 도 인덱싱하므로 "코비하우스" 같은 로고명으로도 검색된다.
        val extras = fields
            .filter { it.label in EXTRA_LABELS }
            .joinToString(", ") { "${it.label}: ${it.value}" }

        return BusinessCardEntity(
            id,
            first("이름"),
            first("영문명"),
            first("회사", "회사(영문)"),
            first("직함"),
            first("부서"),
            "", // industry — 명함 텍스트만으로는 정할 수 없다. 사용자가 나중에 채운다.
            REGION.find(address)?.value.orEmpty(),
            phone,
            first("이메일"),
            address,
            extras,
            "", // tags — 사용자가 붙이는 값이라 인식 결과로 채우지 않는다.
            updatedAtMillis,
        )
    }

    private val EXTRA_LABELS = setOf("웹", "로고", "슬로건", "팩스", "한자명", "기타")
}
