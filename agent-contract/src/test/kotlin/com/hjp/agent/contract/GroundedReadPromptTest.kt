package com.hjp.agent.contract

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundedReadPromptTest {
    @Test
    fun `read answers see this result not prior contact or action state`() {
        val input = ModelInput.User("데이터 관련 직업 가진 사람", promptContext = ModelPromptContext(
            listOf(ModelPromptSection("session_state", "문지영에게 메일 작성 / 이전 검색은 추시우")),
        ))
        val result = ModelToolResponse("r", "search_contacts", buildJsonObject { put("name", "설다은") })
        val prompt = GroundedReadPrompt.render(input, result)
        assertTrue(prompt.contains("설다은"))
        assertTrue(prompt.contains(input.text))
        assertFalse(prompt.contains("문지영"))
        assertFalse(prompt.contains("추시우"))
    }
}
