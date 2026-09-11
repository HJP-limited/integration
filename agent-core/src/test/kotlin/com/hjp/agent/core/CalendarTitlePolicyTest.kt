package com.hjp.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarTitlePolicyTest {
    @Test
    fun `removes only grounded generic suffixes`() {
        val prompt = "내일 오후 2시에 계약 검토 일정을 만들어 주세요."
        assertEquals("계약 검토", CalendarTitlePolicy.canonicalize("계약 검토 일정", prompt))
        assertEquals("고객 일정", CalendarTitlePolicy.canonicalize("고객 일정", prompt))
        assertEquals("일정", CalendarTitlePolicy.canonicalize("일정", prompt))
        val stagedPrompt = "내일 오후 2시에 분기 검토 캘린더 작성 단계까지 준비해 주세요."
        assertEquals(
            "분기 검토",
            CalendarTitlePolicy.canonicalize("분기 검토 캘린더 작성 단계까지 준비", stagedPrompt),
        )
    }

    @Test
    fun `empty title remains unavailable`() {
        assertNull(CalendarTitlePolicy.canonicalize("  ", "일정을 만들어 주세요"))
    }
}
