package com.example.hjp.characterization

import com.hjp.tool.contact.BusinessCardRecord

/**
 * A synthetic contact repository built to exercise the *shapes* Korean contact references take.
 *
 * Deliberately not a copy of the frozen Ryeong fixture and deliberately not a copy of any upstream
 * sentence. Every name here was chosen for a structural reason that is written next to it, so the
 * characterization measures whether the agent understands a *form* of reference rather than whether
 * it has memorised a set of people. Nothing in production may branch on any of these names.
 *
 * The hard cases, in the order the routing contract has to survive them:
 *
 *  - a name that ends in a syllable that is also a Korean particle (설태을, 하도, 우리은, 최이가);
 *  - a compound surname, which makes the name longer than the usual two or three syllables;
 *  - a foreign name written with a space, which no single-run name pattern can match;
 *  - a name that spells one of the agent's own action words (문자현, 서수정, 조회연, 남일정);
 *  - two people with the same name, so "which one" has to stay an open question;
 *  - a person whose name is also a common noun, so the noun alone must not become a person.
 *
 * Company, title and department values are here for the opposite reason: they are the vocabulary a
 * reference must *not* be mistaken for.
 */
object ContactDirectoryFixture {

    /** Ends in 을 — the object particle. Stripping it blindly searches for a person who does not exist. */
    val SEOL = card("D001", "설태을", "가온소재", "품질관리팀", "책임연구원", "대전광역시 유성구 대덕대로 480")

    /** Ends in 도 — both a particle and the province suffix. */
    val HA = card("D002", "하도", "너울건설", "안전관리팀", "차장", "부산광역시 해운대구 센텀중앙로 90")

    /** Ends in 은 — the topic particle. */
    val URI = card("D003", "우리은", "밝음제약", "임상개발팀", "선임연구원", "서울특별시 송파구 올림픽로 300")

    /** Ends in 가 — the subject particle. */
    val CHOI = card("D004", "최이가", "한들전자", "구매팀", "과장", "경기도 성남시 분당구 판교로 255")

    /** Compound surname: four syllables, so a two-or-three syllable rule loses it. */
    val NAMGUNG = card("D005", "남궁여진", "리안바이오", "연구기획팀", "수석연구원", "대전광역시 유성구 과학로 125")

    /** Compound surname again, with a rarer given name. */
    val SEONWOO = card("D006", "선우해든", "푸른물류", "운영지원팀", "팀장", "광주광역시 서구 상무대로 700")

    /** Compound surname, third variant. */
    val JEGAL = card("D007", "제갈수아", "온새미로", "디자인팀", "시니어 디자이너", "서울특별시 마포구 월드컵북로 400")

    /** A foreign name written with a space. No single Hangul run covers it. */
    val MICHAEL = card("D008", "마이클 첸", "노바테크", "플랫폼개발팀", "개발자", "서울특별시 강남구 테헤란로 152")

    /** A short foreign name with a space, where the second token is one syllable. */
    val ANNA = card("D009", "안나 리", "노바테크", "데이터분석팀", "데이터 사이언티스트", "서울특별시 강남구 테헤란로 152")

    /** Five syllables — longer than any closed length rule would allow. */
    val HWANGBO = card("D010", "황보라온해", "다올파트너스", "법무팀", "변호사", "서울특별시 서초구 서초대로 219")

    /** Spells 문자 (send an SMS). */
    val MUNJA = card("D011", "문자현", "세움기획", "마케팅팀", "대리", "서울특별시 종로구 종로 33")

    /** Spells 수정 (edit a card). */
    val SUJEONG = card("D012", "서수정", "세움기획", "영업1팀", "영업팀장", "서울특별시 종로구 종로 33")

    /** Spells 조회 (look something up). */
    val JOHOE = card("D013", "조회연", "미르텍", "고객지원팀", "매니저", "대구광역시 동구 동대구로 550")

    /** Spells 일정 (schedule an event). */
    val ILJEONG = card("D014", "남일정", "미르텍", "경영지원팀", "부장", "대구광역시 동구 동대구로 550")

    /** Namesake pair: the same name on two different cards. */
    val TWIN_A = card("D015", "김민준", "한빛테크", "영업2팀", "영업팀장", "서울특별시 영등포구 여의대로 24")
    val TWIN_B = card("D016", "김민준", "가온소재", "생산관리팀", "생산팀장", "대전광역시 유성구 대덕대로 480")

    /** An ordinary three-syllable name, as the control case. */
    val ORDINARY = card("D017", "박서준", "밝음제약", "품질보증팀", "과장", "서울특별시 송파구 올림픽로 300")

    /**
     * A person whose name is also an everyday noun.
     *
     * The noun on its own ("담당자 알려줘") must not become this person; the same word marked as a
     * person ("담당자씨 회사가 어디야?") is a different sentence. Which of the two it is has to be
     * decided by the sentence, not by a list of forbidden words.
     */
    val NOUN_NAME = card("D018", "고운말", "온새미로", "홍보팀", "차장", "서울특별시 마포구 월드컵북로 400")

    val ALL: List<BusinessCardRecord> = listOf(
        SEOL, HA, URI, CHOI, NAMGUNG, SEONWOO, JEGAL, MICHAEL, ANNA, HWANGBO,
        MUNJA, SUJEONG, JOHOE, ILJEONG, TWIN_A, TWIN_B, ORDINARY, NOUN_NAME,
    )

    /** Vocabulary that belongs to a company, a job or a team — never to a person by itself. */
    val NON_PERSON_VOCABULARY: List<String> = listOf(
        "한빛테크", "노바테크", "가온소재", "영업팀장", "변호사", "디자인팀", "품질관리팀",
    )

    private fun card(
        id: String,
        name: String,
        company: String,
        department: String,
        title: String,
        address: String,
    ) = BusinessCardRecord(
        id = id,
        name = name,
        company = company,
        title = title,
        department = department,
        industry = "일반",
        location = address.substringBefore(" "),
        phone = "02-000-0000",
        mobile = "010-0000-" + id.drop(1),
        email = id.lowercase() + "@example.com",
        address = address,
    )
}
