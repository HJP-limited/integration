package com.example.hjp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactSearchPromptParserTest {
    @Test
    fun `extracts name from Korean business card request`() {
        assertEquals("김민수", ContactSearchPromptParser.parse("김민수 명함 찾아줘."))
    }

    @Test
    fun `keeps descriptive contact search terms`() {
        assertEquals(
            "판교에서 만난 AI 개발자",
            ContactSearchPromptParser.parse("판교에서 만난 AI 개발자 찾아줘"),
        )
    }

    @Test
    fun `does not route unrelated conversation`() {
        assertNull(ContactSearchPromptParser.parse("안녕하세요"))
    }

    @Test
    fun `does not route conversation references as contact search`() {
        assertNull(ContactSearchPromptParser.parse("방금 찾은 사람 누구야?"))
        assertNull(ContactSearchPromptParser.parse("지금까지 찾은 사람들 정리해줘"))
        assertTrue(ContactSearchPromptParser.isConversationReference("아까 검색한 명함 목록 알려줘"))
    }
}
