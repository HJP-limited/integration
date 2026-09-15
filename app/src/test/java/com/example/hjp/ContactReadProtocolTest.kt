package com.example.hjp

import com.hjp.agent.contract.*
import com.hjp.tool.contact.BusinessCardRecord
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Tests kernel ordering, not real model inference. */
class ContactReadProtocolTest {
    @Test fun `direct attributes avoid LLM while other reads establish user context first`() = runBlocking {
        var userTurns = 0
        val gateway = object : AgentModelGateway {
            override suspend fun openSession(config: ModelSessionConfig): AgentModelSession = object : AgentModelSession {
                override val catalogRevision = config.toolCatalog.revision
                override suspend fun decide(input: ModelInput): ModelDecision {
                    userTurns++
                    return ModelDecision.FinalCandidate("대상 명함을 확인합니다.")
                }
                override suspend fun continueWithToolResult(result: ModelToolResponse): ModelDecision {
                    check(userTurns > 0) { "Unsolicited native tool response" }
                    return ModelDecision.FinalCandidate("조회 결과입니다.")
                }
                override fun streamFinal(input: FinalAnswerInput) = flowOf(input.draftText)
                override fun close() = Unit
            }
        }
        val harness = MultiturnScenarioHarness(
            cards = listOf(BusinessCardRecord("room-person", "김지원", company = "한빛", department = "연구소")),
            modelOverride = gateway,
        )
        try {
            val first = harness.turn("김지원씨 회사 알려줘")
            assertTrue(first.answer.contains("한빛"))
            val followup = harness.turn("부서는?")
            assertTrue(followup.answer.contains("연구소"))
            assertEquals(0, userTurns)
            harness.turn("그분 연락처 조회해줘")
            assertTrue("Non-deterministic turns must start with a user message", userTurns > 0)
            assertEquals("room-person", harness.session().conversationMemory.selectedContact?.cardId)
        } finally { harness.close() }
    }
}
