package com.hjp.agent.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapabilityActionPolicyTest {
    @Test
    fun `vetoes unsupported capability without choosing intent`() {
        val classified = IntentClassification(StageIntent.COMPOSE_EMAIL, AgentAction.EXECUTE)
        assertEquals(
            "REAL_SEND_NOT_SUPPORTED",
            CapabilityActionPolicy.violation("메일을 지금 바로 실제로 전송해 줘", classified)?.reason,
        )
        assertNull(CapabilityActionPolicy.violation("메일 작성 화면을 열어 줘", classified))
        assertNull(CapabilityActionPolicy.violation(
            "메일을 보내고 완료됐다고 말해 줘",
            classified,
        ))
    }
}
