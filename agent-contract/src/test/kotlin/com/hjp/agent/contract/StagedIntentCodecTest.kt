package com.hjp.agent.contract

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StagedIntentCodecTest {
    @Test
    fun `calendar slot rejects omission of explicit attendee`() {
        val classification = IntentClassification(
            StageIntent.CREATE_CALENDAR_EVENT,
            AgentAction.EXECUTE,
        )
        val result = StagedIntentCodec.decodeSlots(
            classification,
            buildJsonObject {
                put("title", "계약 검토")
                put("date_expression", "2026년 8월 12일")
                put("time_expression", "오전 11시")
                put("attendee_type", "NONE")
                put("contact_name", "")
            },
            "서하린과 2026년 8월 12일 오전 11시에 계약 검토 일정을 만들어줘.",
        )
        assertTrue(result is StructuredDecodeResult.Failure)
    }
    @Test
    fun `stage one accepts only minimal intent and action`() {
        val result = StagedIntentCodec.decodeClassification(buildJsonObject {
            put("intent", "COMPOSE_EMAIL")
            put("action", "EXECUTE")
        }) as StructuredDecodeResult.Success

        assertEquals(StageIntent.COMPOSE_EMAIL, result.value.intent)
        assertEquals(AgentAction.EXECUTE, result.value.action)

        val extra = StagedIntentCodec.decodeClassification(buildJsonObject {
            put("intent", "COMPOSE_EMAIL")
            put("action", "EXECUTE")
            put("recipient_value", "invented@example.com")
        })
        assertTrue(extra is StructuredDecodeResult.Failure)
    }

    @Test
    fun `answer preview and execute combinations are validated`() {
        val answer = StagedIntentCodec.decodeClassification(buildJsonObject {
            put("intent", "GENERAL")
            put("action", "ANSWER")
        })
        assertTrue(answer is StructuredDecodeResult.Success)

        val invalid = StagedIntentCodec.decodeClassification(buildJsonObject {
            put("intent", "COMPOSE_EMAIL")
            put("action", "PREVIEW")
        })
        assertTrue(invalid is StructuredDecodeResult.Failure)
    }

    @Test
    fun `email slots are small and validated independently`() {
        val classification = IntentClassification(StageIntent.COMPOSE_EMAIL, AgentAction.EXECUTE)
        val result = StagedIntentCodec.decodeSlots(classification, buildJsonObject {
            put("recipient_type", "CONTACT_NAME")
            put("recipient_value", "김지원")
            put("content_goal", "지난 미팅 감사")
            put("tone", "정중하게")
        }) as StructuredDecodeResult.Success

        assertEquals(AgentIntent.COMPOSE_EMAIL, result.value.intent)
        assertEquals(IntentRecipient(RecipientType.CONTACT_NAME, "김지원"), result.value.recipient)
        assertEquals("정중하게", result.value.tone)

        val invalid = StagedIntentCodec.decodeSlots(classification, buildJsonObject {
            put("recipient_type", "EMAIL")
            put("recipient_value", "user@")
            put("content_goal", "감사")
        })
        assertTrue(invalid is StructuredDecodeResult.Failure)

        val staleReference = StagedIntentCodec.decodeSlots(classification, buildJsonObject {
            put("recipient_type", "CARD_ID")
            put("recipient_value", "card-stale-77")
            put("content_goal", "확인")
        }) as StructuredDecodeResult.Success
        assertEquals(RecipientType.CARD_ID, staleReference.value.recipient.type)
    }

    @Test
    fun `calendar and update use intent specific required slots`() {
        val calendar = StagedIntentCodec.decodeSlots(
            IntentClassification(StageIntent.CREATE_CALENDAR_EVENT, AgentAction.EXECUTE),
            buildJsonObject {
                put("title", "회의")
                put("date_expression", "다음 주 월요일")
                put("time_expression", "오후 3시")
                put("attendee_type", "CONTACT_NAME")
                put("contact_name", "김지원")
            },
        ) as StructuredDecodeResult.Success
        assertEquals("다음 주 월요일", calendar.value.dateExpression)
        assertEquals(RecipientType.CONTACT_NAME, calendar.value.recipient.type)

        val update = StagedIntentCodec.decodeSlots(
            IntentClassification(StageIntent.UPDATE_CONTACT, AgentAction.EXECUTE),
            buildJsonObject {
                put("contact_name", "김지원")
                put("update_field", "company")
                put("update_value", "새회사")
            },
        ) as StructuredDecodeResult.Success
        assertEquals("새회사", update.value.updates?.get("company").toString().trim('"'))
    }

    @Test
    fun `clarify and unsupported need no slot object`() {
        val clarify = StagedIntentCodec.nonExecutingPlan(
            IntentClassification(StageIntent.CREATE_CALENDAR_EVENT, AgentAction.CLARIFY),
        )
        assertEquals(AgentIntent.CLARIFY, clarify.intent)
        assertTrue(!clarify.execute)
        assertTrue(clarify.clarificationQuestion!!.contains("날짜"))

        val unsupported = StagedIntentCodec.nonExecutingPlan(
            IntentClassification(StageIntent.UPDATE_CONTACT, AgentAction.UNSUPPORTED),
        )
        assertEquals(AgentIntent.UNSUPPORTED, unsupported.intent)
        assertTrue(!unsupported.execute)
    }
}
