package com.hjp.tool.android

import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolExecutionResult
import com.hjp.tool.contract.ToolRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenComposePluginTest {
    @Test
    fun `email requires valid address subject and body`() = runBlocking {
        val backend = FakeBackend()
        val plugin = OpenComposePlugin(backend)

        val missingBody = plugin.execute(request {
            put("channel", "email")
            put("to", "test@example.com")
            put("subject", "감사")
        }, context())
        val invalidAddress = plugin.execute(request {
            put("channel", "email")
            put("to", "test-at-example")
            put("subject", "감사")
            put("body", "감사합니다.")
        }, context())

        assertTrue(missingBody is ToolExecutionResult.Failure)
        assertTrue(invalidAddress is ToolExecutionResult.Failure)
        assertEquals(0, backend.openCount)
    }

    @Test
    fun `sms rejects subject and invalid phone`() = runBlocking {
        val backend = FakeBackend()
        val plugin = OpenComposePlugin(backend)

        val withSubject = plugin.execute(request {
            put("channel", "sms")
            put("to", "010-1234-5678")
            put("subject", "제목")
            put("body", "감사합니다.")
        }, context())
        val badPhone = plugin.execute(request {
            put("channel", "sms")
            put("to", "010-12")
            put("body", "감사합니다.")
        }, context())

        assertTrue(withSubject is ToolExecutionResult.Failure)
        assertTrue(badPhone is ToolExecutionResult.Failure)
        assertEquals(0, backend.openCount)
    }

    @Test
    fun `valid email draft reaches backend but is never sent`() = runBlocking {
        val backend = FakeBackend()
        val plugin = OpenComposePlugin(backend)

        val result = plugin.execute(request {
            put("channel", "email")
            put("to", "test@example.com")
            put("subject", "감사드립니다")
            put("body", "도와주셔서 감사합니다.")
        }, context())

        assertTrue(result is ToolExecutionResult.Success)
        assertEquals(1, backend.openCount)
        assertEquals("test@example.com", backend.lastDraft?.to)
        assertEquals("도와주셔서 감사합니다.", backend.lastDraft?.body)
    }

    private fun request(arguments: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        ToolRequest(
            "call-1",
            AndroidIntentToolContracts.Compose.capabilityId,
            AndroidIntentToolContracts.Compose.version,
            buildJsonObject(arguments),
        )

    private fun context() = ToolExecutionContext(
        "session",
        "turn",
        "ko-KR",
        "Asia/Seoul",
    )

    private class FakeBackend : MessageComposerBackend {
        var openCount = 0
        var lastDraft: MessageDraft? = null
        override fun isAvailable(channel: MessageChannel?) = true
        override suspend fun open(draft: MessageDraft): Boolean {
            openCount += 1
            lastDraft = draft
            return true
        }
    }
}
