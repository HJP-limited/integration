package com.hjp.agent.contract

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Drift guard for the Stage 1/Stage 2 vocabulary.
 *
 * The Kotlin codec runs on the device and `tools/litertlm_benchmark/hjp_staged_schemas.py` drives
 * the Mac evaluation. Both read this fixture, so an intent, action, recipient type or update field
 * added on one side only fails immediately instead of making the evaluation describe a different
 * agent than the one that ships.
 */
class StagedSchemaParityTest {
    private val schema: JsonObject by lazy {
        val file = FIXTURE_PATHS.map(::File).firstOrNull { it.isFile }
        assertNotNull("capability_policy_cases.json not found from ${File(".").absolutePath}", file)
        (Json.parseToJsonElement(file!!.readText()) as JsonObject)["staged_schema"] as JsonObject
    }

    @Test
    fun `stage one intents match the shared fixture`() {
        assertEquals(schema.strings("intents"), StageIntent.entries.map { it.name })
    }

    @Test
    fun `stage one actions match the shared fixture`() {
        assertEquals(schema.strings("actions"), AgentAction.entries.map { it.name })
    }

    @Test
    fun `recipient types match the shared fixture`() {
        // NONE is an internal "no recipient" marker and is never part of the wire vocabulary.
        val wireTypes = RecipientType.entries.map { it.name }.filterNot { it == "NONE" }
        assertEquals(schema.strings("recipient_types"), wireTypes)
    }

    @Test
    fun `update fields match the shared fixture`() {
        assertEquals(schema.strings("update_fields").toSet(), StagedIntentCodec.UPDATE_FIELDS)
    }

    private fun JsonObject.strings(name: String): List<String> =
        (this[name] as JsonArray).map { (it as JsonPrimitive).content }

    private companion object {
        val FIXTURE_PATHS = listOf(
            "../tools/agent_eval/fixtures/capability_policy_cases.json",
            "tools/agent_eval/fixtures/capability_policy_cases.json",
        )
    }
}
