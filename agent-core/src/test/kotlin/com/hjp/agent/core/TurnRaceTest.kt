package com.hjp.agent.core

import com.hjp.agent.contract.AgentEvent
import com.hjp.agent.contract.AgentModelGateway
import com.hjp.agent.contract.AgentModelSession
import com.hjp.agent.contract.FinalAnswerInput
import com.hjp.agent.contract.ModelDecision
import com.hjp.agent.contract.ModelInput
import com.hjp.agent.contract.ModelSessionConfig
import com.hjp.agent.contract.ModelToolCall
import com.hjp.agent.contract.ModelToolResponse
import com.hjp.tool.contract.ConfirmationPolicy
import com.hjp.tool.contract.ContractVersion
import com.hjp.tool.contract.PiiLevel
import com.hjp.tool.contract.ToolAvailability
import com.hjp.tool.contract.ToolCapabilityId
import com.hjp.tool.contract.ToolContract
import com.hjp.tool.contract.ToolEffect
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
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `새 대화` can land while a turn is inside an uninterruptible native inference.
 *
 * Cancellation alone cannot stop that, so every boundary re-checks the generation the turn started
 * under. These tests drive the reset from inside the fake gateway and the fake plugins, which is
 * exactly where the interleaving happens on device.
 */
class TurnRaceTest {
    @Test
    fun `a reset during model inference stops the turn before any tool runs`() = runBlocking {
        val harness = RaceHarness()
        harness.resetDuringDecide = true

        val events = harness.kernel.runTurn("요청 D").toList()

        assertEquals(emptyList<String>(), harness.executed)
        assertTrue(events.none { it is AgentEvent.FinalMessage })
        assertTrue(harness.session().transcript.isEmpty())
        harness.close()
    }

    @Test
    fun `a reset before the first tool blocks the executor`() = runBlocking {
        val harness = RaceHarness()
        harness.resetAfterDecide = true

        harness.kernel.runTurn("요청 D").toList()

        assertEquals(emptyList<String>(), harness.executed)
        harness.close()
    }

    @Test
    fun `a reset between search and get blocks the second tool`() = runBlocking {
        val harness = RaceHarness(script = RaceHarness.Script.SEARCH_THEN_GET)
        harness.resetInsidePlugin = "demo_read_a"

        harness.kernel.runTurn("요청 A").toList()

        assertEquals(listOf("demo_read_a"), harness.executed)
        harness.close()
    }

    @Test
    fun `a reset between get and compose blocks the side effect`() = runBlocking {
        val harness = RaceHarness(script = RaceHarness.Script.GET_THEN_COMPOSE)
        harness.resetInsidePlugin = "demo_read_b"

        harness.kernel.runTurn("요청 B").toList()

        assertEquals(listOf("demo_read_b"), harness.executed)
        assertTrue("side effect must never run", harness.executed.none { it == "demo_side_effect" })
        harness.close()
    }

    @Test
    fun `a duplicated side effect call executes only once in a turn`() = runBlocking {
        val harness = RaceHarness(script = RaceHarness.Script.COMPOSE_TWICE)

        harness.kernel.runTurn("요청 C").toList()

        assertEquals(listOf("demo_side_effect"), harness.executed)
        harness.close()
    }

    @Test
    fun `a read only tool may still run twice with different arguments`() = runBlocking {
        val harness = RaceHarness(script = RaceHarness.Script.SEARCH_TWICE)

        harness.kernel.runTurn("요청 A").toList()

        assertEquals(listOf("demo_read_a", "demo_read_a"), harness.executed)
        harness.close()
    }

    @Test
    fun `results from a stale generation never reach memory or the transcript`() = runBlocking {
        val harness = RaceHarness(script = RaceHarness.Script.SEARCH_THEN_GET)
        harness.resetInsidePlugin = "demo_read_a"

        harness.kernel.runTurn("요청 A").toList()

        val session = harness.session()
        assertEquals(1L, session.generation)
        assertTrue(session.transcript.isEmpty())
        assertTrue(session.conversationMemory.actions.isEmpty())
        assertEquals(null, session.conversationMemory.selectedContact)
        harness.close()
    }

    @Test
    fun `turns are serialized so a second turn cannot interleave with the first`() = runBlocking {
        val harness = RaceHarness()

        harness.kernel.runTurn("첫 요청").toList()
        harness.kernel.runTurn("두 번째 요청").toList()

        assertEquals(2, harness.session().transcript.count { it.text.endsWith("요청") })
        harness.close()
    }

    @Test
    fun `turn diagnostics record shapes only and never user or contact text`() = runBlocking {
        val harness = RaceHarness(script = RaceHarness.Script.SEARCH_THEN_GET)

        harness.kernel.runTurn("김지원 명함 찾아줘 010-1234-5678 jiwon@example.com").toList()

        val diagnostics = harness.kernel.diagnostics.last!!.asMap()
        assertEquals(AgentKernelMode.REACT.name, diagnostics["kernel_mode"])
        assertTrue(diagnostics.containsKey("native_total_tokens"))
        assertTrue(diagnostics.containsKey("route_plan"))
        assertTrue(diagnostics.values.none { it.contains("김지원") })
        assertTrue(diagnostics.values.none { it.contains("@") })
        assertTrue(diagnostics.values.none { it.contains("010-") })
        harness.close()
    }

    private class RaceHarness(script: Script = Script.FINAL_ONLY) {
        enum class Script { FINAL_ONLY, SEARCH_THEN_GET, GET_THEN_COMPOSE, COMPOSE_TWICE, SEARCH_TWICE }

        val executed = mutableListOf<String>()
        var resetDuringDecide = false
        var resetAfterDecide = false
        var resetInsidePlugin: String? = null

        private val store = InMemoryAgentSessionStore()
        private val plugins = listOf(
            plugin("demo.read.a", "demo_read_a", ToolEffect.READ_ONLY),
            plugin("demo.read.b", "demo_read_b", ToolEffect.READ_ONLY),
            plugin("demo.side.effect", "demo_side_effect", ToolEffect.EXTERNAL_UI),
        )
        private val registry = DefaultToolRegistry(plugins.map { ToolImplementationCandidate(it) })
        private val gateway = ScriptedGateway(script) { hook() }
        private val sessions = AgentSessionManager(store, gateway, "system", "ko-KR")

        val kernel: AgentKernel = AgentKernel(
            registry, DefaultToolExecutor(registry), DefaultToolPolicyEngine(), sessions,
            DefaultToolObservationMapper(), FakeEnvironment,
            turnPolicy = AgentTurnPolicy(maxToolCalls = 6, rejectRepeatedCall = false),
        )

        suspend fun session() = store.getOrCreate()

        fun close() = sessions.close()

        private suspend fun hook() {
            if (resetDuringDecide || resetAfterDecide) {
                resetDuringDecide = false
                resetAfterDecide = false
                kernel.resetSession()
            }
        }

        private fun plugin(capability: String, modelName: String, effect: ToolEffect) =
            object : ToolPlugin {
                override val implementationId = ToolImplementationId("fake.$modelName")
                override val contract = ToolContract(
                    ToolCapabilityId(capability), modelName, ContractVersion(1, 0), "fake",
                    OBJECT_SCHEMA, OBJECT_SCHEMA, effect, ConfirmationPolicy.NONE,
                    PiiLevel.NONE, PiiLevel.NONE, defaultTimeoutMillis = 1_000,
                    presentation = ToolPresentation("실행 중", "완료", "사용 불가"),
                )

                override suspend fun availability() = ToolAvailability.Ready

                override suspend fun execute(
                    request: ToolRequest,
                    context: ToolExecutionContext,
                ): ToolExecutionResult {
                    executed += modelName
                    if (resetInsidePlugin == modelName) {
                        resetInsidePlugin = null
                        kernel.resetSession()
                    }
                    return ToolExecutionResult.Success(
                        request.callId, request.capabilityId, request.contractVersion, 1,
                        buildJsonObject { put("ok", true) },
                    )
                }
            }
    }

    private class ScriptedGateway(
        private val script: RaceHarness.Script,
        private val onDecide: suspend () -> Unit,
    ) : AgentModelGateway {
        override suspend fun openSession(config: ModelSessionConfig): AgentModelSession =
            ScriptedSession(config.toolCatalog.revision, script, onDecide)
    }

    private class ScriptedSession(
        override val catalogRevision: String,
        private val script: RaceHarness.Script,
        private val onDecide: suspend () -> Unit,
    ) : AgentModelSession {
        private var step = 0

        override suspend fun decide(input: ModelInput): ModelDecision {
            onDecide()
            return when (script) {
                RaceHarness.Script.FINAL_ONLY -> ModelDecision.FinalCandidate("완료")
                RaceHarness.Script.SEARCH_THEN_GET, RaceHarness.Script.SEARCH_TWICE ->
                    call("demo_read_a", "김지원")
                RaceHarness.Script.GET_THEN_COMPOSE -> call("demo_read_b", "C001")
                RaceHarness.Script.COMPOSE_TWICE -> call("demo_side_effect", "first")
            }
        }

        override suspend fun continueWithToolResult(result: ModelToolResponse): ModelDecision {
            step += 1
            return when (script) {
                RaceHarness.Script.SEARCH_THEN_GET ->
                    if (step == 1) call("demo_read_b", "C001") else ModelDecision.FinalCandidate("완료")
                RaceHarness.Script.SEARCH_TWICE ->
                    if (step == 1) call("demo_read_a", "박민수") else ModelDecision.FinalCandidate("완료")
                RaceHarness.Script.GET_THEN_COMPOSE ->
                    if (step == 1) call("demo_side_effect", "body") else ModelDecision.FinalCandidate("완료")
                RaceHarness.Script.COMPOSE_TWICE ->
                    if (step == 1) call("demo_side_effect", "second") else ModelDecision.FinalCandidate("완료")
                RaceHarness.Script.FINAL_ONLY -> ModelDecision.FinalCandidate("완료")
            }
        }

        override fun streamFinal(input: FinalAnswerInput): Flow<String> = flowOf(input.draftText)

        override fun close() = Unit

        private fun call(name: String, value: String) = ModelDecision.ToolCalls(
            listOf(ModelToolCall("call-$name-$value", name, buildJsonObject { put("q", value) })),
        )
    }

    private object FakeEnvironment : AgentRuntimeEnvironment {
        override val localeTag = "ko-KR"
        override val timeZoneId = "Asia/Seoul"
        override suspend fun grantedPermissions() = emptySet<String>()
        override suspend fun deviceCapabilities() = emptySet<String>()
        override suspend fun toolContext(sessionId: String, turnId: String) =
            ToolExecutionContext(sessionId, turnId, localeTag, timeZoneId)
    }

    private companion object {
        val OBJECT_SCHEMA = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        }
    }
}
