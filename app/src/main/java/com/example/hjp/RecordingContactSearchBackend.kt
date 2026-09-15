package com.example.hjp

import com.hjp.tool.contact.BusinessCardRecord
import com.hjp.tool.contact.ContactSearchBackend
import com.hjp.tool.contact.ContactSearchResponse
import java.util.concurrent.atomic.AtomicReference

/**
 * 도구가 실제로 찾아온 명함을 화면이 볼 수 있게 기록만 하는 껍데기.
 *
 * 에이전트의 답변은 글자만 흘러나오는데([com.hjp.agent.contract.AgentEvent]), 화면은 답변
 * 아래에 근거가 된 명함을 띄운다. 화면이 같은 질문으로 **다시 검색해서** 카드를 채우면
 * 재작성된 질의를 모르는 채로 검색하게 돼 답변과 카드가 어긋난다(예전 설계에서 실제로
 * 겪은 문제다). 그래서 새로 검색하지 않고 도구가 쓴 결과를 그대로 가져온다.
 *
 * 위임만 하고 결과를 바꾸지 않는다 — 순위도 기권 판정도 감싸기 전과 같다.
 */
class RecordingContactSearchBackend(
    private val delegate: ContactSearchBackend,
) : ContactSearchBackend by delegate {

    private data class Recorded(val generation: Long, val hits: List<BusinessCardRecord>)
    private val recorded = AtomicReference(Recorded(0, emptyList()))
    val lastHits: List<BusinessCardRecord> get() = recorded.get().hits

    /** 턴을 시작할 때 비운다. 안 비우면 검색을 안 한 턴이 앞 턴의 카드를 물려받는다. */
    fun clear() {
        recorded.updateAndGet { Recorded(it.generation + 1, emptyList()) }
    }

    override suspend fun search(query: String, limit: Int): ContactSearchResponse {
        val generation = recorded.get().generation
        val response = delegate.search(query, limit)
        val hits = response.hits.map { it.card }
        recorded.updateAndGet { current ->
            if (current.generation == generation) Recorded(generation, hits) else current
        }
        return response
    }
}
