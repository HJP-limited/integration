package com.hjp.agent.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

sealed interface DateTimeParseResult {
    data class Success(val value: LocalDateTime) : DateTimeParseResult
    data class Failure(val reasonKo: String) : DateTimeParseResult
}

class KoreanDateTimeParser {
    fun requiresCurrentDate(dateExpression: String): Boolean =
        RELATIVE_MARKERS.any(dateExpression::contains)

    fun parse(
        dateExpression: String?,
        timeExpression: String?,
        now: ZonedDateTime,
    ): DateTimeParseResult {
        val dateText = dateExpression?.trim().orEmpty()
        val timeText = timeExpression?.trim().orEmpty()
        if (dateText.isEmpty()) return DateTimeParseResult.Failure("일정 날짜가 필요합니다.")
        if (timeText.isEmpty()) return DateTimeParseResult.Failure("일정 시작 시각이 필요합니다.")
        val date = parseDate(dateText, now.toLocalDate())
            ?: return DateTimeParseResult.Failure("날짜 표현을 해석할 수 없습니다: $dateText")
        val time = parseTime(timeText)
            ?: return DateTimeParseResult.Failure("시간 표현을 해석할 수 없습니다: $timeText")
        return DateTimeParseResult.Success(LocalDateTime.of(date, time))
    }

    private fun parseDate(text: String, base: LocalDate): LocalDate? {
        ABSOLUTE_DATE.find(text)?.destructured?.let { (year, month, day) ->
            return runCatching {
                LocalDate.of(year.toInt(), month.toInt(), day.toInt())
            }.getOrNull()
        }
        if (text.contains("오늘")) return base
        if (text.contains("내일")) return base.plusDays(1)
        if (text.contains("모레")) return base.plusDays(2)
        WEEKDAY.find(text)?.let { match ->
            val scope = match.groupValues[1]
            val target = WEEKDAYS[match.groupValues[2]] ?: return null
            val monday = base.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            return when (scope) {
                "이번 주", "이번주" -> monday.plusDays(target.value.toLong() - 1)
                "다음 주", "다음주" -> monday.plusWeeks(1).plusDays(target.value.toLong() - 1)
                else -> null
            }
        }
        return null
    }

    private fun parseTime(text: String): LocalTime? {
        val match = KOREAN_TIME.find(text) ?: return null
        val marker = match.groupValues[1]
        var hour = match.groupValues[2].toIntOrNull() ?: return null
        val minute = match.groupValues[3].takeIf(String::isNotBlank)?.toIntOrNull() ?: 0
        if (marker == "오후" && hour < 12) hour += 12
        if (marker == "오전" && hour == 12) hour = 0
        return runCatching { LocalTime.of(hour, minute) }.getOrNull()
    }

    private companion object {
        val RELATIVE_MARKERS = listOf("오늘", "내일", "모레", "이번 주", "이번주", "다음 주", "다음주")
        val ABSOLUTE_DATE = Regex("""(\d{4})년\s*(\d{1,2})월\s*(\d{1,2})일""")
        val WEEKDAY = Regex("""(이번\s*주|다음\s*주)\s*(월|화|수|목|금|토|일)요일""")
        val KOREAN_TIME = Regex("""(오전|오후)\s*(\d{1,2})시(?:\s*(\d{1,2})분)?""")
        val WEEKDAYS = mapOf(
            "월" to DayOfWeek.MONDAY,
            "화" to DayOfWeek.TUESDAY,
            "수" to DayOfWeek.WEDNESDAY,
            "목" to DayOfWeek.THURSDAY,
            "금" to DayOfWeek.FRIDAY,
            "토" to DayOfWeek.SATURDAY,
            "일" to DayOfWeek.SUNDAY,
        )
    }
}
