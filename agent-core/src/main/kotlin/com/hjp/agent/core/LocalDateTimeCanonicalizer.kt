package com.hjp.agent.core

import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/**
 * The one definition of what a calendar `start_time` looks like.
 *
 * The tool contract has always said `yyyy-MM-dd'T'HH:mm`, and the validator matched exactly that.
 * The real Gemma artifact reliably writes the seconds anyway — `2027-05-06T16:00:00` — so a request
 * the model got completely right in every other respect was rejected as an invalid datetime, and the
 * turn ended with nothing created. Three of the fifteen scenarios in the actual-Gemma run died this
 * way.
 *
 * Rejecting a value that means exactly what the contract asks for helps nobody, so a zero seconds
 * component (and a zero fractional part) is *canonicalised* down to minute precision. Everything that
 * would change the meaning is still refused, loudly:
 *
 *  - a non-zero seconds component, because silently dropping `:30` moves the appointment;
 *  - any zone or offset suffix, because the tool writes device-local time and reinterpreting an
 *    instant is not a formatting question;
 *  - a date that does not exist, which `LocalDateTime` catches for us.
 *
 * Every consumer — normalizer, validator, evaluator and the desktop Gemma harness — goes through
 * this type, so "canonical" cannot come to mean two different things in two different places.
 */
object LocalDateTimeCanonicalizer {

    /** The canonical wire form. */
    const val CANONICAL_PATTERN: String = """\d{4}-\d{2}-\d{2}T\d{2}:\d{2}"""

    /** The forms accepted before canonicalisation: minute precision, optionally with zero seconds. */
    private val ACCEPTED = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2})(?::(\d{2})(?:\.(\d+))?)?$""")

    /** A zone or offset suffix. Recognised only so it can be refused with a reason. */
    private val ZONED = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?:Z|[+-]\d{2}:?\d{2})$""")

    sealed interface Result {
        /** [value] is exactly [CANONICAL_PATTERN] and names a date that exists. */
        data class Canonical(val value: String) : Result

        /** Not usable. [reasonKo] is safe to show a user and never contains a raw exception. */
        data class Rejected(val reasonKo: String) : Result
    }

    fun canonicalize(raw: String): Result {
        val value = raw.trim()
        if (value.isEmpty()) {
            return Result.Rejected("start_time이 비어 있습니다.")
        }
        if (ZONED.matches(value)) {
            return Result.Rejected("start_time에는 시간대를 포함할 수 없습니다. 기기 현지 시각으로 알려 주세요.")
        }
        val match = ACCEPTED.matchEntire(value)
            ?: return Result.Rejected("start_time은 yyyy-MM-dd'T'HH:mm 형식이어야 합니다.")
        val minutePart = match.groupValues[1]
        val seconds = match.groupValues[2]
        val fraction = match.groupValues[3]
        if (seconds.isNotEmpty() && seconds.toInt() != 0) {
            return Result.Rejected("start_time은 분 단위까지만 지원합니다. 초는 00이어야 합니다.")
        }
        if (fraction.isNotEmpty() && fraction.any { it != '0' }) {
            return Result.Rejected("start_time은 분 단위까지만 지원합니다. 초는 00이어야 합니다.")
        }
        return try {
            // Parsing is what rejects 2027-02-30 and 25:00; the string is already shaped correctly.
            LocalDateTime.parse(minutePart)
            Result.Canonical(minutePart)
        } catch (_: DateTimeParseException) {
            Result.Rejected("존재하지 않는 날짜 또는 시각입니다.")
        }
    }

    /** The canonical string, or null when [raw] is not usable. */
    fun canonicalOrNull(raw: String): String? =
        (canonicalize(raw) as? Result.Canonical)?.value

    /** Parsed form, or null. Callers that need the value rather than the string use this. */
    fun parseOrNull(raw: String): LocalDateTime? =
        canonicalOrNull(raw)?.let(LocalDateTime::parse)
}
