package com.hjp.agent.core

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KoreanDateTimeParserTest {
    private val parser = KoreanDateTimeParser()
    private val base = ZonedDateTime.of(
        2026, 7, 24, 9, 0, 0, 0, ZoneId.of("Asia/Seoul"),
    )

    @Test
    fun `parses relative weekdays and Korean am pm in Seoul`() {
        assertEquals(
            LocalDateTime.of(2026, 7, 25, 14, 0),
            (parser.parse("내일", "오후 2시", base) as DateTimeParseResult.Success).value,
        )
        assertEquals(
            LocalDateTime.of(2026, 7, 27, 10, 0),
            (parser.parse("다음 주 월요일", "오전 10시", base) as DateTimeParseResult.Success).value,
        )
        assertEquals(
            LocalDateTime.of(2026, 7, 24, 13, 0),
            (parser.parse("이번 주 금요일", "오후 1시", base) as DateTimeParseResult.Success).value,
        )
    }

    @Test
    fun `parses absolute date and rejects incomplete expressions`() {
        assertEquals(
            LocalDateTime.of(2026, 8, 4, 16, 0),
            (parser.parse("2026년 8월 4일", "오후 4시", base) as DateTimeParseResult.Success).value,
        )
        assertTrue(parser.parse("다음 주", null, base) is DateTimeParseResult.Failure)
        assertTrue(parser.parse(null, "오후 2시", base) is DateTimeParseResult.Failure)
    }
}
