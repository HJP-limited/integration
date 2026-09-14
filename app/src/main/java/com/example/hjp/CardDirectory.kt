package com.example.hjp

import com.example.hjp.data.RoomBusinessCardRepository
import com.hjp.tool.contact.BusinessCardRecord
import com.hjp.tool.contact.ContactSearchBackend

/**
 * 화면이 명함 데이터에 닿는 단 하나의 창구.
 *
 * 에이전트는 도구(`search_contacts` / `get_contact`)로 같은 저장소를 보고, 화면은 이 클래스로
 * 본다. **둘은 같은 [RoomBusinessCardRepository] 인스턴스를 공유한다** — 화면 전용 사본을
 * 두면 방금 촬영해 저장한 명함이 목록에는 뜨는데 에이전트는 "그런 사람 없다"고 답하는,
 * 재현하기 까다로운 어긋남이 생긴다.
 *
 * 예전 `CardSearchService` 가 하던 화면 쪽 역할만 물려받았다. 검색 자체는 하지 않고
 * Agent_0910 의 [ContactSearchBackend] 에 넘긴다 — 순위 규칙이 화면과 에이전트에서
 * 갈라지지 않게 하려는 것이다.
 */
class CardDirectory(
    private val repository: RoomBusinessCardRepository,
    private val backend: ContactSearchBackend,
    private val onCardAdded: suspend () -> Unit,
) {
    suspend fun allCards(): List<BusinessCardRecord> = repository.loadAll()

    /** 최근 추가 순. OCR 로 방금 넣은 명함이 맨 위에 온다. */
    suspend fun recentCards(limit: Int): List<BusinessCardRecord> =
        repository.loadAll().sortedByDescending { it.updatedAt }.take(limit)

    suspend fun findCard(id: String): BusinessCardRecord? = repository.getById(id)

    suspend fun totalCardCount(): Int = repository.count()

    suspend fun search(query: String, limit: Int = 20): List<BusinessCardRecord> =
        backend.search(query, limit).hits.map { it.card }

    /** 검색 엔진 상태 문구. 설정 화면이 보여 준다. */
    fun engineStatus(): String = backend.engineName()

    /**
     * 촬영으로 만든 명함에 줄 다음 id.
     *
     * `S` 접두어는 촬영본이라는 뜻이다 — 번들 시드는 `T`/`C` 로 시작하므로 섞이지 않고,
     * 상세 화면이 원본 이미지를 `<id>.png` 로 찾을 때도 이 규칙에 기댄다.
     */
    suspend fun nextOcrCardId(): String {
        val used = repository.loadAll()
            .mapNotNull { it.id.removePrefix("S").toIntOrNull().takeIf { _ -> it.id.startsWith("S") } }
        return "S" + ((used.maxOrNull() ?: 0) + 1).toString().padStart(3, '0')
    }

    suspend fun addCard(record: BusinessCardRecord) {
        repository.insert(record)
        // Saving is not complete until every consumer has dropped its old snapshot and the new
        // document vector has been generated and persisted. The callback throws if that cannot be
        // guaranteed, so the UI never claims an embedding-less card was fully registered.
        onCardAdded()
    }
}
