package com.hjp.agent.core

import com.hjp.agent.contract.AgentEvent
import com.hjp.agent.contract.AgentModelGateway
import com.hjp.agent.contract.AgentModelSession
import com.hjp.agent.contract.ConversationMemory
import com.hjp.agent.contract.TrackedActionStatus
import com.hjp.agent.contract.FinalAnswerInput
import com.hjp.agent.contract.ModelDecision
import com.hjp.agent.contract.ModelInput
import com.hjp.agent.contract.ModelSessionConfig
import com.hjp.agent.contract.ModelToolCall
import com.hjp.agent.contract.ModelToolResponse
import com.hjp.tool.contract.CatalogContext
import com.hjp.tool.contract.ConfirmationPolicy
import com.hjp.tool.contract.ContractVersion
import com.hjp.tool.contract.PiiLevel
import com.hjp.tool.contract.StandardToolErrorCodes
import com.hjp.tool.contract.ToolAvailability
import com.hjp.tool.contract.ToolCapabilityId
import com.hjp.tool.contract.ToolContract
import com.hjp.tool.contract.ToolEffect
import com.hjp.tool.contract.ToolError
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolExecutionResult
import com.hjp.tool.contract.ToolImplementationId
import com.hjp.tool.contract.ToolPlugin
import com.hjp.tool.contract.ToolPresentation
import com.hjp.tool.contract.ToolRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentKernelTest {
    @Test
    fun `tool result is returned to model and final answer completes`() = runBlocking {
        val plugin = FakePlugin("fake.v1")
        val registry = DefaultToolRegistry(listOf(ToolImplementationCandidate(plugin)))
        val gateway = FakeGateway()
        val sessions = AgentSessionManager(InMemoryAgentSessionStore(), gateway, "system", "ko-KR")
        val kernel = AgentKernel(
            registry, DefaultToolExecutor(registry), DefaultToolPolicyEngine(), sessions,
            DefaultToolObservationMapper(), FakeEnvironment(),
        )

        val events = kernel.runTurn("검색해 줘").toList()

        assertTrue(events.any { it is AgentEvent.ToolStarted })
        assertEquals("완료", (events.last() as AgentEvent.FinalMessage).text)
        assertTrue(gateway.session.receivedResult)
        sessions.close()
    }

    @Test
    fun `implementation swap changes binding revision only`() = runBlocking {
        val first = DefaultToolRegistry(listOf(ToolImplementationCandidate(FakePlugin("fake.v1"))))
            .snapshot(CatalogContext("s", "ko-KR"))
        val second = DefaultToolRegistry(listOf(ToolImplementationCandidate(FakePlugin("fake.v2"))))
            .snapshot(CatalogContext("s", "ko-KR"))

        assertEquals(first.revision, second.revision)
        assertTrue(first.bindingRevision != second.bindingRevision)
    }

    @Test
    fun `rejected model call gets one correction and only corrected call executes`() = runBlocking {
        val plugin = ComposeFakePlugin()
        val registry = DefaultToolRegistry(listOf(ToolImplementationCandidate(plugin)))
        val gateway = CorrectionGateway(repeatInvalid = false)
        val sessions = AgentSessionManager(InMemoryAgentSessionStore(), gateway, "system", "ko-KR")
        val kernel = AgentKernel(
            registry, DefaultToolExecutor(registry), DefaultToolPolicyEngine(), sessions,
            DefaultToolObservationMapper(), FakeEnvironment(),
        )

        val events = kernel.runTurn("test@example.com에게 감사 메일 작성해줘.").toList()

        assertEquals(1, plugin.executionCount)
        assertEquals(1, gateway.session.rejectionCount)
        assertEquals("완료", (events.last() as AgentEvent.FinalMessage).text)
        sessions.close()
    }

    @Test
    fun `same validation error twice stops without tool execution`() = runBlocking {
        val plugin = ComposeFakePlugin()
        val registry = DefaultToolRegistry(listOf(ToolImplementationCandidate(plugin)))
        val gateway = CorrectionGateway(repeatInvalid = true)
        val sessions = AgentSessionManager(InMemoryAgentSessionStore(), gateway, "system", "ko-KR")
        val kernel = AgentKernel(
            registry, DefaultToolExecutor(registry), DefaultToolPolicyEngine(), sessions,
            DefaultToolObservationMapper(), FakeEnvironment(),
        )

        val events = kernel.runTurn("test@example.com에게 감사 메일 작성해줘.").toList()

        assertEquals(0, plugin.executionCount)
        assertTrue(events.last() is AgentEvent.UserError)
        sessions.close()
    }

    @Test
    fun `unrepaired rejection cannot finish as a successful model claim`() = runBlocking {
        val plugin = ComposeFakePlugin()
        val registry = DefaultToolRegistry(listOf(ToolImplementationCandidate(plugin)))
        val gateway = RejectionThenClaimGateway()
        val store = InMemoryAgentSessionStore()
        val sessions = AgentSessionManager(store, gateway, "system", "ko-KR")
        val kernel = AgentKernel(
            registry, DefaultToolExecutor(registry), DefaultToolPolicyEngine(), sessions,
            DefaultToolObservationMapper(), FakeEnvironment(),
        )

        val events = kernel.runTurn("test@example.com에게 감사 메일 작성해줘.").toList()
        val final = events.last() as AgentEvent.FinalMessage
        val action = store.getOrCreate().conversationMemory.actions.single()

        assertEquals(0, plugin.executionCount)
        assertTrue(final.text.contains("올바른 이메일"))
        assertTrue(!final.text.contains("작성했습니다"))
        assertEquals(TrackedActionStatus.FAILED, action.status)
        sessions.close()
    }

    @Test
    fun `recent conversation reaches the next model turn`() = runBlocking {
        val fixture = KernelFixture(ConversationHistoryStrategy.DUPLICATE_BASELINE)

        fixture.kernel.runTurn("첫 질문").toList()
        fixture.kernel.runTurn("다음 질문").toList()

        val second = fixture.gateway.session.inputs[1] as ModelInput.User
        val recent = second.promptContext.sections.single { it.name == "recent_conversation" }
        assertTrue(recent.body.contains("첫 질문"))
        assertTrue(recent.body.contains("응답 1"))
        fixture.close()
    }

    @Test
    fun `app canonical strategy does not restate history on a live native conversation`() = runBlocking {
        val fixture = KernelFixture(ConversationHistoryStrategy.APP_CANONICAL_BOOTSTRAP)

        fixture.kernel.runTurn("첫 질문").toList()
        fixture.kernel.runTurn("이어지는 질문").toList()
        fixture.kernel.runTurn("마지막 질문").toList()

        val names = fixture.gateway.session.inputs.map { input ->
            (input as ModelInput.User).promptContext.sections.map { it.name }
        }
        assertEquals(emptyList<String>(), names[0])
        assertEquals(listOf("session_state"), names[1])
        assertEquals(listOf("session_state"), names[2])
        assertEquals(0, fixture.gateway.session.resets)
        fixture.close()
    }

    @Test
    fun `duplicate baseline restates history on every turn`() = runBlocking {
        val fixture = KernelFixture(ConversationHistoryStrategy.DUPLICATE_BASELINE)

        fixture.kernel.runTurn("첫 질문").toList()
        fixture.kernel.runTurn("이어지는 질문").toList()
        fixture.kernel.runTurn("마지막 질문").toList()

        val third = fixture.gateway.session.inputs[2] as ModelInput.User
        val recent = third.promptContext.sections.single { it.name == "recent_conversation" }
        assertTrue(recent.body.contains("첫 질문"))
        assertTrue(recent.body.contains("이어지는 질문"))
        fixture.close()
    }

    @Test
    fun `explicit preferences and constraints are retained as structured memory`() = runBlocking {
        val fixture = KernelFixture()

        fixture.kernel.runTurn("정중한 말투를 선호해. 전송 전에 꼭 확인해줘.").toList()
        fixture.kernel.runTurn("다음 요청").toList()

        val memory = (fixture.gateway.session.inputs[1] as ModelInput.User).turnContext.memory
        assertEquals(1, memory.preferences.size)
        assertEquals(1, memory.constraints.size)
        assertTrue(memory.preferences.single().sourceTurnId.isNotBlank())
        fixture.close()
    }

    @Test
    fun `failed turn stays in the transcript but is not resumable work`() = runBlocking {
        val fixture = KernelFixture(gateway = FailingThenHistoryGateway())

        fixture.kernel.runTurn("완료되지 않을 요청").toList()
        fixture.kernel.runTurn("다시 시도").toList()

        val second = fixture.gateway.session.inputs[1] as ModelInput.User
        val failed = second.turnContext.memory.actions.first { it.request == "완료되지 않을 요청" }
        assertEquals(TrackedActionStatus.FAILED, failed.status)
        assertTrue(second.turnContext.memory.openActions.none { it.request == "완료되지 않을 요청" })
        assertTrue(second.turnContext.transcript.any { it.text == "완료되지 않을 요청" })
        assertTrue(second.turnContext.transcript.any { it.status == TrackedActionStatus.FAILED })
        fixture.close()
    }

    @Test
    fun `only successfully executed tools become tool verified memory`() = runBlocking {
        val fixture = KernelFixture(gateway = ToolThenHistoryGateway(), plugin = FailingPlugin())

        fixture.kernel.runTurn("도구가 실패하는 요청").toList()
        fixture.kernel.runTurn("다음 질문").toList()

        val memory = (fixture.gateway.session.inputs[1] as ModelInput.User).turnContext.memory
        assertTrue(memory.actions.all { it.executedTools.isEmpty() })
        fixture.close()
    }

    @Test
    fun `session reset clears transcript memory and native conversation`() = runBlocking {
        val fixture = KernelFixture()

        fixture.kernel.runTurn("첫 질문").toList()
        fixture.kernel.resetSession()
        fixture.kernel.runTurn("새 질문").toList()

        val afterReset = fixture.gateway.session.inputs.last() as ModelInput.User
        assertTrue(afterReset.promptContext.sections.isEmpty())
        assertTrue(afterReset.turnContext.transcript.none { it.text == "첫 질문" })
        assertTrue(afterReset.turnContext.memory.actions.none { it.request == "첫 질문" })
        fixture.close()
    }

    @Test
    fun `a turn interrupted by a reset never writes into the new session`() = runBlocking {
        val store = InMemoryAgentSessionStore()
        val registry = DefaultToolRegistry(listOf(ToolImplementationCandidate(FakePlugin("fake.v1"))))
        var resetHook: (suspend () -> Unit)? = null
        val gateway = ResettingGateway { resetHook?.invoke() }
        val sessions = AgentSessionManager(store, gateway, "system", "ko-KR")
        val kernel = AgentKernel(
            registry, DefaultToolExecutor(registry), DefaultToolPolicyEngine(), sessions,
            DefaultToolObservationMapper(), FakeEnvironment(),
        )
        resetHook = { kernel.resetSession() }

        kernel.runTurn("첫 질문").toList()

        val session = store.getOrCreate()
        assertEquals(1L, session.generation)
        assertTrue(session.transcript.isEmpty())
        assertTrue(session.conversationMemory.actions.isEmpty())
        sessions.close()
    }

    private class KernelFixture(
        strategy: ConversationHistoryStrategy = ConversationHistoryStrategy.APP_CANONICAL_BOOTSTRAP,
        val gateway: RecordingGateway = HistoryGateway(),
        plugin: ToolPlugin = FakePlugin("fake.v1"),
    ) {
        private val registry = DefaultToolRegistry(listOf(ToolImplementationCandidate(plugin)))
        private val sessions = AgentSessionManager(
            InMemoryAgentSessionStore(), gateway, "system", "ko-KR",
        )
        val kernel = AgentKernel(
            registry, DefaultToolExecutor(registry), DefaultToolPolicyEngine(), sessions,
            DefaultToolObservationMapper(), FakeEnvironment(),
            turnPolicy = AgentTurnPolicy(historyStrategy = strategy),
        )

        fun close() = sessions.close()
    }

    private interface RecordingGateway : AgentModelGateway {
        val session: RecordingSession
    }

    private interface RecordingSession {
        val inputs: MutableList<ModelInput>
        var resets: Int
    }

    private class FakeGateway : AgentModelGateway {
        lateinit var session: FakeModelSession
        override suspend fun openSession(config: ModelSessionConfig): AgentModelSession =
            FakeModelSession(config.toolCatalog.revision).also { session = it }
    }

    private abstract class ScriptedSession(
        override val catalogRevision: String,
    ) : AgentModelSession, RecordingSession {
        override val inputs = mutableListOf<ModelInput>()
        override var resets = 0

        override suspend fun resetConversation() {
            resets += 1
        }

        override fun streamFinal(input: FinalAnswerInput): Flow<String> = flowOf(input.draftText)

        override fun close() = Unit
    }

    private class HistoryGateway : RecordingGateway {
        override lateinit var session: RecordingSession
        override suspend fun openSession(config: ModelSessionConfig): AgentModelSession =
            HistoryModelSession(config.toolCatalog.revision).also { session = it }
    }

    private class HistoryModelSession(catalogRevision: String) : ScriptedSession(catalogRevision) {
        override suspend fun decide(input: ModelInput): ModelDecision {
            inputs += input
            return ModelDecision.FinalCandidate("응답 ${inputs.size}")
        }

        override suspend fun continueWithToolResult(result: ModelToolResponse): ModelDecision =
            ModelDecision.FinalCandidate("unused")
    }

    private class FailingThenHistoryGateway : RecordingGateway {
        override lateinit var session: RecordingSession
        override suspend fun openSession(config: ModelSessionConfig): AgentModelSession =
            FailingThenHistorySession(config.toolCatalog.revision).also { session = it }
    }

    private class FailingThenHistorySession(catalogRevision: String) : ScriptedSession(catalogRevision) {
        override suspend fun decide(input: ModelInput): ModelDecision {
            inputs += input
            return if (inputs.size == 1) ModelDecision.Invalid("실패", retryable = false)
            else ModelDecision.FinalCandidate("완료")
        }

        override suspend fun continueWithToolResult(result: ModelToolResponse) =
            ModelDecision.FinalCandidate("unused")
    }

    private class ResettingGateway(
        private val onDecide: suspend () -> Unit,
    ) : RecordingGateway {
        override lateinit var session: RecordingSession
        override suspend fun openSession(config: ModelSessionConfig): AgentModelSession =
            ResettingSession(config.toolCatalog.revision, onDecide).also { session = it }
    }

    private class ResettingSession(
        catalogRevision: String,
        private val onDecide: suspend () -> Unit,
    ) : ScriptedSession(catalogRevision) {
        override suspend fun decide(input: ModelInput): ModelDecision {
            inputs += input
            onDecide()
            return ModelDecision.FinalCandidate("응답 ${inputs.size}")
        }

        override suspend fun continueWithToolResult(result: ModelToolResponse) =
            ModelDecision.FinalCandidate("unused")
    }

    private class ToolThenHistoryGateway : RecordingGateway {
        override lateinit var session: RecordingSession
        override suspend fun openSession(config: ModelSessionConfig): AgentModelSession =
            ToolThenHistorySession(config.toolCatalog.revision).also { session = it }
    }

    private class ToolThenHistorySession(catalogRevision: String) : ScriptedSession(catalogRevision) {
        override suspend fun decide(input: ModelInput): ModelDecision {
            inputs += input
            return if (inputs.size == 1) {
                ModelDecision.ToolCalls(listOf(
                    ModelToolCall("call-1", "fake_tool", buildJsonObject { put("query", "AI") }),
                ))
            } else {
                ModelDecision.FinalCandidate("응답 ${inputs.size}")
            }
        }

        override suspend fun continueWithToolResult(result: ModelToolResponse) =
            ModelDecision.FinalCandidate("도구를 실행하지 못했습니다.")
    }

    private class FailingPlugin : ToolPlugin {
        override val implementationId = ToolImplementationId("fake.failing.v1")
        override val contract = TEST_CONTRACT
        override suspend fun availability() = ToolAvailability.Ready
        override suspend fun execute(request: ToolRequest, context: ToolExecutionContext) =
            ToolExecutionResult.Failure(
                request.callId,
                request.capabilityId,
                request.contractVersion,
                1,
                ToolError(StandardToolErrorCodes.TOOL_EXECUTION_FAILED, "도구가 실패했습니다.", false),
            )
    }

    private class FakeModelSession(override val catalogRevision: String) : AgentModelSession {
        var receivedResult = false
        override suspend fun decide(input: ModelInput) = ModelDecision.ToolCalls(listOf(
            ModelToolCall("call-1", "fake_tool", buildJsonObject { put("query", "AI") })
        ))
        override suspend fun continueWithToolResult(result: ModelToolResponse): ModelDecision {
            receivedResult = true
            return ModelDecision.FinalCandidate("완료")
        }
        override fun streamFinal(input: FinalAnswerInput): Flow<String> = flowOf(input.draftText)
        override fun close() = Unit
    }

    private class FakePlugin(id: String) : ToolPlugin {
        override val implementationId = ToolImplementationId(id)
        override val contract = TEST_CONTRACT
        override suspend fun availability() = ToolAvailability.Ready
        override suspend fun execute(request: ToolRequest, context: ToolExecutionContext) =
            ToolExecutionResult.Success(request.callId, request.capabilityId, request.contractVersion, 1,
                buildJsonObject { put("value", "ok") })
    }

    private class CorrectionGateway(
        private val repeatInvalid: Boolean,
    ) : AgentModelGateway {
        lateinit var session: CorrectionSession
        override suspend fun openSession(config: ModelSessionConfig): AgentModelSession =
            CorrectionSession(config.toolCatalog.revision, repeatInvalid).also { session = it }
    }

    private class RejectionThenClaimGateway : AgentModelGateway {
        override suspend fun openSession(config: ModelSessionConfig): AgentModelSession =
            object : AgentModelSession {
                override val catalogRevision = config.toolCatalog.revision
                override suspend fun decide(input: ModelInput) = ModelDecision.ToolCalls(listOf(
                    ModelToolCall("bad", "open_compose", buildJsonObject {
                        put("channel", "email")
                        put("to", "not-an-email")
                        put("subject", "감사")
                        put("body", "감사드립니다.")
                    }),
                ))
                override suspend fun continueWithToolResult(result: ModelToolResponse) =
                    ModelDecision.FinalCandidate("이메일을 작성했습니다.")
                override fun streamFinal(input: FinalAnswerInput): Flow<String> = flowOf(input.draftText)
                override fun close() = Unit
            }
    }

    private class CorrectionSession(
        override val catalogRevision: String,
        private val repeatInvalid: Boolean,
    ) : AgentModelSession {
        var rejectionCount = 0
        private val invalid = ModelToolCall("compose-invalid", "open_compose", buildJsonObject {
            put("channel", "email")
            put("to", "test@example.com")
        })
        private val corrected = ModelToolCall("compose-corrected", "open_compose", buildJsonObject {
            put("channel", "email")
            put("to", "test@example.com")
            put("subject", "감사드립니다")
            put("body", "도와주셔서 감사합니다.")
        })

        override suspend fun decide(input: ModelInput) =
            ModelDecision.ToolCalls(listOf(invalid))

        override suspend fun continueWithToolResult(result: ModelToolResponse): ModelDecision {
            if ((result.payload["status"] as? JsonPrimitive)?.content == "rejected") {
                rejectionCount += 1
                return ModelDecision.ToolCalls(listOf(if (repeatInvalid) invalid else corrected))
            }
            return ModelDecision.FinalCandidate("완료")
        }

        override fun streamFinal(input: FinalAnswerInput): Flow<String> = flowOf(input.draftText)
        override fun close() = Unit
    }

    private class ComposeFakePlugin : ToolPlugin {
        var executionCount = 0
        override val implementationId = ToolImplementationId("compose.fake.v1")
        override val contract = COMPOSE_CONTRACT
        override suspend fun availability() = ToolAvailability.Ready
        override suspend fun execute(request: ToolRequest, context: ToolExecutionContext): ToolExecutionResult {
            executionCount += 1
            return ToolExecutionResult.Success(
                request.callId,
                request.capabilityId,
                request.contractVersion,
                1,
                buildJsonObject { put("opened", true) },
            )
        }
    }

    private class FakeEnvironment : AgentRuntimeEnvironment {
        override val localeTag = "ko-KR"
        override val timeZoneId = "Asia/Seoul"
        override suspend fun grantedPermissions() = emptySet<String>()
        override suspend fun deviceCapabilities() = emptySet<String>()
        override suspend fun toolContext(sessionId: String, turnId: String) =
            ToolExecutionContext(sessionId, turnId, localeTag, timeZoneId)
    }

    companion object {
        private val OBJECT_SCHEMA = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        }
        private val TEST_CONTRACT = ToolContract(
            ToolCapabilityId("fake.test"), "fake_tool", ContractVersion(1, 0), "fake",
            OBJECT_SCHEMA, OBJECT_SCHEMA, ToolEffect.READ_ONLY, ConfirmationPolicy.NONE,
            PiiLevel.NONE, PiiLevel.NONE, defaultTimeoutMillis = 1_000,
            presentation = ToolPresentation("실행 중", "완료", "사용 불가"),
        )
        private val COMPOSE_SCHEMA = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put("required", kotlinx.serialization.json.JsonArray(
                listOf("channel", "to", "body").map(::JsonPrimitive),
            ))
            put("properties", buildJsonObject {
                put("channel", buildJsonObject {
                    put("type", "string")
                    put("enum", kotlinx.serialization.json.JsonArray(
                        listOf("email", "sms").map(::JsonPrimitive),
                    ))
                })
                listOf("to", "subject", "body").forEach { name ->
                    put(name, buildJsonObject { put("type", "string") })
                }
            })
        }
        private val COMPOSE_CONTRACT = ToolContract(
            ToolCapabilityId("message.open_compose"),
            "open_compose",
            ContractVersion(1, 0),
            "compose",
            COMPOSE_SCHEMA,
            OBJECT_SCHEMA,
            ToolEffect.READ_ONLY,
            ConfirmationPolicy.NONE,
            PiiLevel.NONE,
            PiiLevel.NONE,
            defaultTimeoutMillis = 1_000,
            presentation = ToolPresentation("실행 중", "완료", "사용 불가"),
        )
    }
}
