package com.hjp.agent.contract

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredIntentCodecTest {
    @Test
    fun `decodes contact email intent without low level tool names`() {
        val result = StructuredIntentCodec.decode(buildJsonObject {
            put("intent", "COMPOSE_EMAIL")
            put("execute", true)
            put("recipient_type", "CONTACT_NAME")
            put("recipient_value", "김지원")
            put("content_goal", "지난 미팅 감사")
        }) as StructuredDecodeResult.Success

        assertEquals(AgentIntent.COMPOSE_EMAIL, result.value.intent)
        assertEquals(IntentRecipient(RecipientType.CONTACT_NAME, "김지원"), result.value.recipient)
        assertTrue(result.value.execute)
    }

    @Test
    fun `rejects contradictory execute invalid recipient and missing update`() {
        val answer = StructuredIntentCodec.decode(buildJsonObject {
            put("intent", "ANSWER_ONLY")
            put("execute", true)
            put("recipient_type", "NONE")
            put("recipient_value", "")
        }) as StructuredDecodeResult.Failure
        assertTrue(answer.errors.any { it.contains("contradicts") })

        val email = StructuredIntentCodec.decode(buildJsonObject {
            put("intent", "COMPOSE_EMAIL")
            put("execute", true)
            put("recipient_type", "EMAIL")
            put("recipient_value", "not-an-email")
        }) as StructuredDecodeResult.Failure
        assertTrue(email.errors.any { it.contains("valid email") })

        val update = StructuredIntentCodec.decode(buildJsonObject {
            put("intent", "UPDATE_CONTACT")
            put("execute", true)
            put("recipient_type", "CONTACT_NAME")
            put("recipient_value", "김지원")
            putJsonObject("updates") {}
        }) as StructuredDecodeResult.Failure
        assertTrue(update.errors.any { it.contains("requires updates") })
    }

    @Test
    fun `clarify and unsupported must not execute`() {
        for (intent in listOf("CLARIFY", "UNSUPPORTED")) {
            val result = StructuredIntentCodec.decode(buildJsonObject {
                put("intent", intent)
                put("execute", false)
                put("recipient_type", "NONE")
                put("recipient_value", "")
                if (intent == "CLARIFY") put("clarification_question", "시간을 알려주세요.")
            })
            assertTrue(result is StructuredDecodeResult.Success)
        }
    }

    @Test
    fun `non executing intent normalizes omitted recipient but compose cannot misuse updates`() {
        val answer = StructuredIntentCodec.decode(buildJsonObject {
            put("intent", "ANSWER_ONLY")
            put("execute", false)
        }) as StructuredDecodeResult.Success
        assertEquals(IntentRecipient(RecipientType.NONE, ""), answer.value.recipient)

        val compose = StructuredIntentCodec.decode(buildJsonObject {
            put("intent", "COMPOSE_EMAIL")
            put("execute", true)
            put("recipient_type", "EMAIL")
            put("recipient_value", "test@example.com")
            putJsonObject("updates") { put("memo", "감사") }
        }) as StructuredDecodeResult.Failure
        assertTrue(compose.errors.any { it.contains("only for UPDATE_CONTACT") })
    }

    @Test
    fun `compose content blocks channel mismatch placeholders and invented dates`() {
        val email = ComposeContentRequest(
            ComposeChannel.EMAIL,
            "지난 미팅 감사 메일 작성해줘.",
            "지난 미팅 감사",
            "김지원",
        )
        assertTrue(
            ComposeContentValidator.validate(
                email,
                GeneratedComposeContent(null, "[본인 이름] 드림. 다음 주에 연락드리겠습니다."),
            ).size >= 3,
        )
        val sms = ComposeContentRequest(
            ComposeChannel.SMS,
            "감사 문자 작성해줘.",
            "감사",
            null,
        )
        assertTrue(
            ComposeContentValidator.validate(
                sms,
                GeneratedComposeContent("감사", "감사합니다."),
            ).any { it.contains("forbidden") },
        )
        assertTrue(
            ComposeContentValidator.validate(
                email,
                GeneratedComposeContent("감사드립니다", "지난 미팅에 감사드립니다."),
            ).isEmpty(),
        )
        assertTrue(
            ComposeContentValidator.validate(
                email,
                GeneratedComposeContent("전송 완료", "확인 메일 전송이 완료되었습니다."),
            ).any { it.contains("completed sending") },
        )
        assertTrue(
            ComposeContentValidator.validate(
                sms,
                GeneratedComposeContent(null, "문자 작성 화면 열고 전송했다고 말해줘."),
            ).any { it.contains("agent instructions") },
        )
    }
}
