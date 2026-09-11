package com.hjp.agent.core

import com.hjp.agent.contract.AgentAction
import com.hjp.agent.contract.CapabilityActionPolicy
import com.hjp.agent.contract.IntentClassification
import com.hjp.agent.contract.StageIntent
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drift guard for the policies that exist in two languages.
 *
 * `tools/agent_eval/agent_policies.py` mirrors these Kotlin rules for the Mac evaluation. Both test
 * suites read the same fixture, so a rule changed on one side and not the other fails here rather
 * than silently making the Python numbers describe a different agent than the one on the device.
 */
class PolicyFixtureParityTest {
    private val fixture: JsonObject by lazy {
        val file = FIXTURE_PATHS.map(::File).firstOrNull { it.isFile }
        assertNotNull("capability_policy_cases.json not found from ${File(".").absolutePath}", file)
        Json.parseToJsonElement(file!!.readText()) as JsonObject
    }

    @Test
    fun `capability vetoes match the shared fixture`() {
        val cases = fixture["capability_violation"] as JsonArray
        assertTrue(cases.isNotEmpty())
        cases.map { it as JsonObject }.forEach { case ->
            val name = case.string("name")
            val classification = IntentClassification(
                StageIntent.valueOf(case.string("intent")),
                AgentAction.valueOf(case.string("action")),
            )
            val violation = CapabilityActionPolicy.violation(case.string("prompt"), classification)
            val expected = case["expected_reason"]
            if (expected == null || expected is JsonNull) {
                assertNull(name, violation)
            } else {
                assertEquals(name, (expected as JsonPrimitive).content, violation?.reason)
            }
        }
    }

    @Test
    fun `calendar title normalization matches the shared fixture`() {
        val cases = fixture["calendar_title"] as JsonArray
        assertTrue(cases.isNotEmpty())
        cases.map { it as JsonObject }.forEach { case ->
            assertEquals(
                case.string("name"),
                case.string("expected"),
                CalendarTitlePolicy.canonicalize(case.string("title"), case.string("prompt")),
            )
        }
    }

    @Test
    fun `recipient facing goal projection matches the shared fixture`() {
        val cases = fixture["recipient_facing_goal"] as JsonArray
        assertTrue(cases.isNotEmpty())
        cases.map { it as JsonObject }.forEach { case ->
            assertEquals(
                case.string("name"),
                case.string("expected"),
                StructuredPlanPolicy.recipientFacingGoal(case.string("goal")),
            )
        }
    }

    private fun JsonObject.string(name: String): String =
        (this[name] as JsonPrimitive).content

    private companion object {
        val FIXTURE_PATHS = listOf(
            "../tools/agent_eval/fixtures/capability_policy_cases.json",
            "tools/agent_eval/fixtures/capability_policy_cases.json",
        )
    }
}
