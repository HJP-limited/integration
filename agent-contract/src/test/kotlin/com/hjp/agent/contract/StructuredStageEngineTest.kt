package com.hjp.agent.contract

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the Stage 1/Stage 2 loop used by `LiteRtStructuredAgentModelGateway`.
 * The transport is scripted, so parsing, repair, timeout and error conversion are verified without
 * the LiteRT runtime.
 */
class StructuredStageEngineTest {
    @Test
    fun `a valid first reply is accepted without a repair round`() = runBlocking {
        val transport = ScriptedTransport(listOf(reply(buildJsonObject { put("intent", "SEARCH_CONTACT") })))
        val raw = mutableListOf<String>()

        val result = StructuredStageEngine().run(
            toolName = "submit_action",
            prompt = "요청",
            transport = transport,
            rawOutputs = raw,
            decode = { StructuredDecodeResult.Success(it.string("intent").orEmpty()) },
        )

        assertEquals("SEARCH_CONTACT", (result as StagedDecode.Success).value)
        assertEquals(1, result.attempts)
        assertEquals(0, transport.repairs.size)
        assertEquals(1, raw.size)
    }

    @Test
    fun `a rejected reply gets exactly one repair round`() = runBlocking {
        val transport = ScriptedTransport(listOf(
            reply(buildJsonObject { put("intent", "") }),
            reply(buildJsonObject { put("intent", "COMPOSE_EMAIL") }),
        ))

        val result = StructuredStageEngine().run(
            toolName = "submit_action",
            prompt = "요청",
            transport = transport,
            rawOutputs = mutableListOf(),
            decode = { arguments ->
                val intent = arguments.string("intent").orEmpty()
                if (intent.isEmpty()) StructuredDecodeResult.Failure(listOf("intent is required"))
                else StructuredDecodeResult.Success(intent)
            },
        )

        assertEquals("COMPOSE_EMAIL", (result as StagedDecode.Success).value)
        assertEquals(2, result.attempts)
        assertEquals(listOf(listOf("intent is required")), transport.repairs.map { it.first })
    }

    @Test
    fun `the decode errors reach the caller when every attempt fails`() = runBlocking {
        val transport = ScriptedTransport(listOf(
            reply(buildJsonObject { put("intent", "") }),
            reply(buildJsonObject { put("intent", "") }),
        ))

        val result = StructuredStageEngine().run(
            toolName = "submit_action",
            prompt = "요청",
            transport = transport,
            rawOutputs = mutableListOf(),
            decode = { StructuredDecodeResult.Failure(listOf("GENERAL cannot use EXECUTE")) },
        )

        val failure = result as StagedDecode.Failure
        assertEquals(listOf("GENERAL cannot use EXECUTE"), failure.errors)
        assertEquals(2, failure.attempts)
    }

    @Test
    fun `a reply without the expected native call is converted to a native call error`() = runBlocking {
        val transport = ScriptedTransport(listOf(StageAttempt("자유 서술 응답", null)))

        val result = StructuredStageEngine(maxAttempts = 1).run(
            toolName = "submit_action",
            prompt = "요청",
            transport = transport,
            rawOutputs = mutableListOf(),
            decode = { StructuredDecodeResult.Success(Unit) },
        )

        assertEquals(
            listOf("submit_action native call is required"),
            (result as StagedDecode.Failure).errors,
        )
    }

    @Test
    fun `a targeted repair message replaces the generic one when supplied`() = runBlocking {
        val transport = ScriptedTransport(listOf(
            reply(buildJsonObject { put("intent", "COMPOSE_EMAIL"); put("action", "ANSWER") }),
            reply(buildJsonObject { put("intent", "COMPOSE_EMAIL"); put("action", "EXECUTE") }),
        ))

        StructuredStageEngine().run(
            toolName = "submit_action",
            prompt = "요청",
            transport = transport,
            rawOutputs = mutableListOf(),
            targetedRepair = { _, _ -> "intent는 고정하고 action만 고치세요." },
            decode = { arguments ->
                if (arguments.string("action") == "EXECUTE") StructuredDecodeResult.Success(Unit)
                else StructuredDecodeResult.Failure(listOf("ANSWER or PREVIEW requires GENERAL"))
            },
        )

        assertEquals("intent는 고정하고 action만 고치세요.", transport.repairs.single().second)
    }

    @Test
    fun `a stalled stage times out instead of hanging the turn`() = runBlocking {
        val transport = object : StageTransport {
            override suspend fun send(prompt: String): StageAttempt {
                delay(10_000)
                return reply(buildJsonObject { })
            }

            override suspend fun repair(errors: List<String>, targetedMessageKo: String?) =
                reply(buildJsonObject { })
        }

        val result = StructuredStageEngine(stageTimeoutMillis = 20).run(
            toolName = "submit_action",
            prompt = "요청",
            transport = transport,
            rawOutputs = mutableListOf(),
            decode = { StructuredDecodeResult.Success(Unit) },
        )

        val failure = result as StagedDecode.Failure
        assertEquals(listOf("submit_action stage timed out"), failure.errors)
        assertTrue(failure.attempts >= 1)
    }

    @Test
    fun `an empty reply is converted to the same native call error`() = runBlocking {
        val transport = ScriptedTransport(listOf(StageAttempt("", null)))

        val result = StructuredStageEngine(maxAttempts = 1).run(
            toolName = "submit_slots",
            prompt = "요청",
            transport = transport,
            rawOutputs = mutableListOf(),
            decode = { StructuredDecodeResult.Success(Unit) },
        )

        assertEquals(
            listOf("submit_slots native call is required"),
            (result as StagedDecode.Failure).errors,
        )
    }

    @Test
    fun `a truncated or malformed payload is reported as a decode failure not a crash`() = runBlocking {
        val transport = ScriptedTransport(listOf(
            StageAttempt("{\"intent\": \"COMPOSE_EM", null),
            StageAttempt("{\"intent\": \"COMPOSE_EM", null),
        ))

        val result = StructuredStageEngine().run(
            toolName = "submit_action",
            prompt = "요청",
            transport = transport,
            rawOutputs = mutableListOf(),
            decode = { StructuredDecodeResult.Success(Unit) },
        )

        assertTrue(result is StagedDecode.Failure)
        assertEquals(2, (result as StagedDecode.Failure).attempts)
    }

    @Test
    fun `wrong types wrong enums and extra fields all surface as decode errors`() = runBlocking {
        val cases = mapOf(
            "wrong_type" to buildJsonObject { put("intent", 42) },
            "wrong_enum" to buildJsonObject { put("intent", "NOT_AN_INTENT") },
            "extra_field" to buildJsonObject { put("intent", "SEARCH_CONTACT"); put("bonus", "x") },
        )
        val allowed = setOf("SEARCH_CONTACT", "COMPOSE_EMAIL")

        cases.forEach { (name, arguments) ->
            val result = StructuredStageEngine(maxAttempts = 1).run(
                toolName = "submit_action",
                prompt = "요청",
                transport = ScriptedTransport(listOf(reply(arguments))),
                rawOutputs = mutableListOf(),
                decode = { value ->
                    val intent = (value["intent"] as? JsonPrimitive)
                    when {
                        intent == null || !intent.isString ->
                            StructuredDecodeResult.Failure(listOf("intent must be a string"))
                        intent.content !in allowed ->
                            StructuredDecodeResult.Failure(listOf("intent is not an allowed enum"))
                        value.keys != setOf("intent") ->
                            StructuredDecodeResult.Failure(listOf("unexpected field"))
                        else -> StructuredDecodeResult.Success(Unit)
                    }
                },
            )
            assertTrue(name, result is StagedDecode.Failure)
        }
    }

    @Test
    fun `cancellation propagates instead of being reported as a timeout`() {
        val transport = object : StageTransport {
            override suspend fun send(prompt: String): StageAttempt =
                throw kotlinx.coroutines.CancellationException("cancelled by caller")

            override suspend fun repair(errors: List<String>, targetedMessageKo: String?) =
                reply(buildJsonObject { })
        }

        var cancelled = false
        try {
            runBlocking {
                StructuredStageEngine(stageTimeoutMillis = 10_000).run(
                    toolName = "submit_action",
                    prompt = "요청",
                    transport = transport,
                    rawOutputs = mutableListOf(),
                    decode = { StructuredDecodeResult.Success(Unit) },
                )
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }

        assertTrue("cancellation must not be swallowed as a timeout", cancelled)
    }

    @Test
    fun `the repair budget is never exceeded`() = runBlocking {
        val transport = ScriptedTransport(List(5) { reply(buildJsonObject { put("intent", "") }) })

        StructuredStageEngine(maxAttempts = 2).run(
            toolName = "submit_action",
            prompt = "요청",
            transport = transport,
            rawOutputs = mutableListOf(),
            decode = { StructuredDecodeResult.Failure(listOf("intent is required")) },
        )

        assertEquals("exactly one repair round", 1, transport.repairs.size)
    }

    private fun reply(arguments: JsonObject) = StageAttempt(arguments.toString(), arguments)

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private class ScriptedTransport(private val replies: List<StageAttempt>) : StageTransport {
        val repairs = mutableListOf<Pair<List<String>, String?>>()
        private var index = 0

        override suspend fun send(prompt: String): StageAttempt = replies[index++]

        override suspend fun repair(errors: List<String>, targetedMessageKo: String?): StageAttempt {
            repairs += errors to targetedMessageKo
            return replies[index++]
        }
    }
}
