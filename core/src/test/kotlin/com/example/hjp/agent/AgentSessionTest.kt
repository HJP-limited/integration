package com.example.hjp.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * sojung 저장소 `agent/structured-multiturn-memory` 브랜치의
 * `agent-core/src/test/kotlin/com/hjp/agent/core/AgentKernelTest.kt`에 있던 멀티턴
 * 시나리오를 이 저장소의 [AgentSession] API(코루틴 없는 동기 호출)로 옮겨 검증한다.
 * 원본은 `AgentKernel`/`AgentSessionManager`를 통해 간접적으로 이 로직을 검증했지만,
 * 이 저장소는 검색 파이프라인이 native tool-calling 루프가 아니라 [AgentSession]을
 * 직접 호출하므로 세션 API를 직접 두드려서 같은 동작을 확인한다.
 */
class AgentSessionTest {

    @Test
    fun `최근 대화가 다음 턴에 전달된다`() {
        val session = AgentSession()
        session.beginTurn("t1", "첫 질문")
        session.recordTurn("t1", "첫 질문", "응답 1")

        assertEquals(2, session.recentMessages.size)
        assertEquals("첫 질문", session.recentMessages[0].text)
        assertEquals("응답 1", session.recentMessages[1].text)
    }

    @Test
    fun `완료된 턴은 구조화 메모리를 갱신하고 윈도우를 넘긴 대화는 digest로 접힌다`() {
        val session = AgentSession()
        // sojung 원본 테스트처럼 윈도우를 2로 좁혀서 롤오버를 쉽게 유도한다.
        // 발화는 **작업성 표현**이어야 한다 — 리듀서가 작업 발화만 resolvedActions 에
        // 넣기 때문이다("첫 질문" 같은 잡담은 작업 메모리를 오염시키지 않는다).
        session.beginTurn("t1", "첫 질문 검색해줘")
        session.recordTurn("t1", "첫 질문 검색해줘", "응답 1", maxRecentMessages = 2)
        session.beginTurn("t2", "두 번째 질문 검색해줘")
        session.recordTurn("t2", "두 번째 질문 검색해줘", "응답 2", maxRecentMessages = 2)
        session.beginTurn("t3", "세 번째 질문 검색해줘")

        // t2가 완료된 시점의 메모리를 기준으로 확인한다(t3는 아직 beginTurn만 한 상태).
        val memory = session.conversationMemory
        assertEquals("두 번째 질문 검색해줘", memory.resolvedActions.last().content)
        assertTrue(memory.historyDigest.contains("첫 질문"))
        assertTrue(memory.historyDigest.contains("응답 1"))
        assertEquals(2, session.recentMessages.size)
        assertEquals("두 번째 질문 검색해줘", session.recentMessages[0].text)
        assertEquals("응답 2", session.recentMessages[1].text)
    }

    @Test
    fun `명시적 선호와 제약은 구조화 메모리에 보존된다`() {
        val session = AgentSession()
        session.beginTurn("t1", "앞으로 정중한 말투를 선호해. 전송 전에 꼭 확인해줘.")
        session.recordTurn("t1", "앞으로 정중한 말투를 선호해. 전송 전에 꼭 확인해줘.", "확인했습니다.")

        val memory = session.conversationMemory
        assertEquals(1, memory.preferences.size)
        assertEquals(1, memory.constraints.size)
        assertTrue(memory.preferences.single().sourceTurnId.isNotBlank())
    }

    @Test
    fun `선호는 발화 전체가 아니라 정규화된 값으로 저장된다`() {
        // 구버전은 마커에 걸리면 발화 전체를 통째로 저장했다. 그러면 같은 뜻을 다르게
        // 말할 때마다 항목이 쌓이고, 8개 버킷이 금방 찬다.
        val session = AgentSession()
        session.beginTurn("t1", "앞으로 정중한 말투로 해줘")
        session.recordTurn("t1", "앞으로 정중한 말투로 해줘", "확인했습니다.")

        assertEquals("정중한 말투", session.conversationMemory.preferences.single().content)
    }

    @Test
    fun `같은 항목을 다시 말하면 최신 값으로 정정된다`() {
        // 구버전은 content 로만 중복을 걸러서 "내 회사는 A야"/"내 회사는 B야"가 둘 다 남았다.
        // 이제 key(user.company)로 덮어쓰므로 최신 값 하나만 남는다.
        val session = AgentSession()
        session.beginTurn("t1", "내 회사는 블루오션이야")
        session.recordTurn("t1", "내 회사는 블루오션이야", "확인했습니다.")
        session.beginTurn("t2", "내 회사는 한빛테크야")
        session.recordTurn("t2", "내 회사는 한빛테크야", "확인했습니다.")

        val facts = session.conversationMemory.confirmedFacts
        assertEquals(1, facts.size)
        assertEquals("한빛테크", facts.single().content)
        assertEquals("user.company", facts.single().key)
    }

    @Test
    fun `일반 대화는 작업 메모리를 오염시키지 않는다`() {
        // 구버전은 모든 발화를 작업으로 취급해서 잡담이 resolvedActions 에 쌓였고,
        // 그게 다음 턴 프롬프트에 그대로 들어갔다.
        val session = AgentSession()
        session.beginTurn("t1", "오늘 날씨가 좋네")
        session.recordTurn("t1", "오늘 날씨가 좋네", "그렇네요.")

        val memory = session.conversationMemory
        assertTrue("잡담은 작업으로 기록되면 안 됨", memory.resolvedActions.isEmpty())
        assertTrue(memory.pendingActions.isEmpty())
        assertEquals("잡담으로 주제가 바뀌면 안 됨", "", memory.topic)
        // 최근 대화로는 남아야 한다 — 문맥 자체를 버리는 게 아니다.
        assertEquals(2, session.recentMessages.size)
    }

    @Test
    fun `완료되지 못한 턴은 다음 턴까지 pending 으로 남는다`() {
        val session = AgentSession()
        // beginTurn만 호출하고 recordTurn을 호출하지 않는다 — 중간에 예외가 나서
        // 턴이 끝까지 완료되지 못한 상황을 흉내낸다.
        session.beginTurn("t1", "완료되지 않을 메일 작성 요청")

        assertTrue(session.conversationMemory.pendingActions.any { it.content == "완료되지 않을 메일 작성 요청" })

        session.beginTurn("t2", "다시 시도")
        assertTrue(
            "이전 턴의 pending 항목이 사라지면 안 됨",
            session.conversationMemory.pendingActions.any { it.content == "완료되지 않을 메일 작성 요청" },
        )
    }

    @Test
    fun `작업 장부는 프롬프트에 들어가지 않는다`() {
        // 실측 회귀 테스트: beginTurn이 답변 생성 전에 이번 질문을 pendingActions에 걸어두는데,
        // 그 memory를 같은 턴의 프롬프트에도 그대로 넣으면 [current_user]와 완전히 같은
        // 텍스트("메일은?")가 두 번 나와서 2B 모델이 새로 답하지 않고 그 텍스트를 그대로
        // 에코했다(A/B 테스트로 원인 확정).
        //
        // 예전에는 '이번 턴 것만' 빼고 이전 턴 미해결 항목은 보여줬는데, sojung 최신본을
        // 따라 pending/resolved 두 버킷을 통째로 뺀다(ConversationMemory.modelView()).
        // 모델이 답을 만드는 데 쓰는 정보가 아니라 런타임 장부이기 때문이다. 저장은 유지된다.
        val session = AgentSession()
        session.beginTurn("t1", "완료되지 않을 메일 작성 요청")  // t1은 recordTurn을 안 해서 여전히 pending
        session.beginTurn("t2", "메일은?")  // 지금 진행 중인 턴

        val ctx = session.buildContextBlocks("메일은?")

        assertFalse("pending_actions 는 프롬프트에 없어야 함", "pending_actions:" in ctx)
        assertFalse("resolved_actions 는 프롬프트에 없어야 함", "resolved_actions:" in ctx)
        assertFalse("작업 장부 내용이 새면 안 됨", "완료되지 않을 메일 작성 요청" in ctx)
        // 저장 자체는 그대로다 — 런타임이 미완료 요청을 계속 추적할 수 있어야 한다.
        assertTrue(
            session.conversationMemory.pendingActions.any { it.content == "완료되지 않을 메일 작성 요청" },
        )
    }

    @Test
    fun `문맥 참조 발화는 검색 없이 이미 아는 것으로 답한다`() {
        val session = AgentSession()
        session.beginTurn("t1", "판교에 있는 디자이너 찾아줘")
        session.recordTurn("t1", "판교에 있는 디자이너 찾아줘", "오동주, 고예린")

        // 새로 검색하라는 말이 없는 문맥 참조 -> 직전 답변을 근거로 답한다.
        val answer = ConversationalFollowup.contextAnswer(session, "아까 찾은 사람 누구였지")
        assertTrue(answer != null && "오동주, 고예린" in answer)

        // 명시적 검색 의도가 있으면 가로채지 않는다 -> 평소대로 검색한다.
        assertNull(ConversationalFollowup.contextAnswer(session, "아까 그 회사 다시 검색해줘"))
        // 문맥 참조가 아니면 가로채지 않는다.
        assertNull(ConversationalFollowup.contextAnswer(session, "대전에 있는 변호사"))
    }

    @Test
    fun `순서 지시를 직전 집합의 인덱스로 읽는다`() {
        // 실측: "대전에 있는 변호사"로 2명을 찾은 뒤 "두 번째 사람 연락처"가
        // 새 검색으로 빠져 무관한 5명을 데려왔다. 직전 집합을 가리키는 발화다.
        assertEquals(0, ConversationalFollowup.ordinalIndex("첫 번째 사람 연락처"))
        assertEquals(1, ConversationalFollowup.ordinalIndex("두 번째 사람은?"))
        assertEquals(2, ConversationalFollowup.ordinalIndex("세 번째"))
        assertEquals(2, ConversationalFollowup.ordinalIndex("3번째 사람"))
        assertEquals(-1, ConversationalFollowup.ordinalIndex("마지막 사람 회사"))
        // 순서 표현이 없으면 새 검색이다.
        assertNull(ConversationalFollowup.ordinalIndex("대전에 있는 변호사 찾아줘"))
        assertNull(ConversationalFollowup.ordinalIndex("번호 뒷자리 4312인 분"))
    }

    @Test
    fun `대명사로 속성을 새로 묻는 질문은 가로채지 않는다`() {
        // 실측: "그 사람"·"그 회사"가 CONTEXT_REFERENCES 에 있어서 "그 사람 부서는?" 이
        // contextAnswer 로 새고 직전 답변을 그대로 재생했다. 대명사는 focus 치환
        // (FOLLOWUP_PRONOUNS)이 담당해야 하고, 이건 되짚기가 아니라 새 질문이다.
        val session = AgentSession()
        session.beginTurn("t1", "설지아씨 회사가 어디야?")
        session.recordTurn("t1", "설지아씨 회사가 어디야?", "(주) lumina 입니다.")

        assertNull(ConversationalFollowup.contextAnswer(session, "그 사람 부서는?"))
        assertNull(ConversationalFollowup.contextAnswer(session, "그 회사 주소는?"))
        // 되짚는 표현은 그대로 가로챈다.
        assertTrue(ConversationalFollowup.contextAnswer(session, "아까 말한 회사 뭐였지") != null)
    }

    @Test
    fun `본인 회사를 말해뒀어도 명함 인물의 회사 질문은 가로채지 않는다`() {
        // 원래 알려진 오답이었다: `user.company`("내 회사는 …")를 저장해 둔 상태에서
        // "그 사람 회사 어디야"를 물으면 명함 인물이 아니라 **본인** 회사를 답했다.
        // CONTEXT_REFERENCES 에서 대명사("그 사람")를 빼면서 함께 해결됐다 —
        // 이제 focus 치환을 거쳐 정상 검색으로 가고, 명함 속 인물의 회사를 답한다.
        val session = AgentSession()
        session.beginTurn("t1", "내 회사는 블루오션이야")
        session.recordTurn("t1", "내 회사는 블루오션이야", "확인했습니다.")

        assertNull(ConversationalFollowup.contextAnswer(session, "그 사람 회사 어디야"))
        // 되짚는 표현으로 물으면 본인 회사를 답하는 게 맞다.
        val recalled = ConversationalFollowup.contextAnswer(session, "아까 말한 내 회사 뭐였지")
        assertTrue(recalled != null && "블루오션" in recalled)
    }

    @Test
    fun `세션 초기화는 구조화 메모리와 최근 대화를 모두 폐기한다`() {
        val session = AgentSession()
        session.beginTurn("t1", "질문")
        session.recordTurn("t1", "질문", "답변")
        session.putToolContext(AgentSession.KEY_FOCUS_PERSON, "오동주")

        session.reset()

        assertEquals(ConversationMemory(), session.conversationMemory)
        assertTrue(session.recentMessages.isEmpty())
        assertNull(session.toolContextValue(AgentSession.KEY_FOCUS_PERSON))
    }

    @Test
    fun `컨텍스트 블록은 규격대로 조립된다`() {
        val session = AgentSession()
        session.beginTurn("t1", "판교에 있는 디자이너 찾아줘")
        session.recordTurn("t1", "판교에 있는 디자이너 찾아줘", "오동주, 고예린", listOf("search_business_cards"))
        session.putToolContext(AgentSession.KEY_FOCUS_PERSON, "오동주")

        val ctx = session.buildContextBlocks("그 사람 회사 어디야")

        // topic 이 모델에 보여줄 유일한 메모리다 — 작업 장부는 modelView() 가 걷어내므로,
        // 작업성 발화가 아니면 [conversation_memory] 블록 자체가 안 만들어진다.
        assertTrue("[conversation_memory]" in ctx)
        assertTrue("topic: 판교에 있는 디자이너 찾아줘" in ctx)
        // resolved_actions("도구 실행 완료: …")는 modelView() 가 걷어내므로 프롬프트에 없다.
        // 대신 메모리에는 남아 있다.
        assertFalse("resolved_actions" in ctx)
        assertTrue(
            session.conversationMemory.resolvedActions.any {
                it.content == "도구 실행 완료: search_business_cards"
            },
        )
        assertTrue("[recent_conversation]" in ctx)
        assertTrue("[tool_session_context]" in ctx)
        assertTrue("focus_person: 오동주" in ctx)
        assertTrue(ctx.trimEnd().endsWith("그 사람 회사 어디야"))
        // [current_user] 는 항상 맨 마지막 블록이어야 한다.
        assertTrue(ctx.indexOf("[current_user]") > ctx.indexOf("[recent_conversation]"))
    }

    @Test
    fun `빈 메모리에서는 conversation_memory 블록을 만들지 않는다`() {
        val session = AgentSession()
        val ctx = session.buildContextBlocks("첫 질문")
        assertFalse("[conversation_memory]" in ctx)
        assertFalse("[recent_conversation]" in ctx)
        assertTrue(ctx.trimEnd().endsWith("첫 질문"))
    }
}
