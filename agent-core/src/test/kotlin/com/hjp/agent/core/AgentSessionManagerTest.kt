package com.hjp.agent.core

import com.hjp.agent.contract.AgentModelGateway
import com.hjp.agent.contract.AgentModelSession
import com.hjp.agent.contract.FinalAnswerInput
import com.hjp.agent.contract.ModelDecision
import com.hjp.agent.contract.ModelInput
import com.hjp.agent.contract.ModelSessionConfig
import com.hjp.agent.contract.ModelToolResponse
import com.hjp.tool.contract.ToolCatalogSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSessionManagerTest {
    @Test
    fun `reset closes model conversation and creates a fresh agent session`() = runBlocking {
        val gateway = RecordingGateway()
        val manager = AgentSessionManager(
            store = InMemoryAgentSessionStore(),
            modelGateway = gateway,
            systemInstructionText = "system",
            localeTag = "ko-KR",
        )
        val firstSessionId = manager.getOrCreate().sessionId
        manager.requireModelSession(EMPTY_CATALOG)

        manager.reset()

        assertTrue(gateway.lastSession.closed)
        assertNotEquals(firstSessionId, manager.getOrCreate().sessionId)
        manager.close()
    }

    private class RecordingGateway : AgentModelGateway {
        lateinit var lastSession: RecordingSession

        override suspend fun openSession(config: ModelSessionConfig): AgentModelSession =
            RecordingSession(config.toolCatalog.revision).also { lastSession = it }
    }

    private class RecordingSession(
        override val catalogRevision: String,
    ) : AgentModelSession {
        var closed = false

        override suspend fun decide(input: ModelInput) = ModelDecision.FinalCandidate("done")

        override suspend fun continueWithToolResult(result: ModelToolResponse) =
            ModelDecision.FinalCandidate("done")

        override fun streamFinal(input: FinalAnswerInput): Flow<String> = emptyFlow()

        override fun close() {
            closed = true
        }
    }

    private companion object {
        val EMPTY_CATALOG = ToolCatalogSnapshot(
            revision = "empty",
            bindingRevision = "empty",
            createdAtEpochMillis = 0,
            bindings = emptyList(),
            contractsByModelName = emptyMap(),
        )
    }
}
