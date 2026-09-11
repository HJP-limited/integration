package com.hjp.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What "the same instant, written differently" means, and where it stops.
 *
 * The rule has to be sharp in both directions. Too strict and a correct request dies on a notation
 * difference the model reliably produces — that is what happened to three calendar turns in the
 * desktop Gemma run. Too loose and truncation silently moves an appointment, which is worse than a
 * refusal because the user is never told.
 */
class LocalDateTimeCanonicalizerTest {

    @Test
    fun `minute precision is already canonical`() {
        assertEquals("2027-05-06T16:00", LocalDateTimeCanonicalizer.canonicalOrNull("2027-05-06T16:00"))
        assertEquals("2026-01-01T00:00", LocalDateTimeCanonicalizer.canonicalOrNull("2026-01-01T00:00"))
        assertEquals("2026-12-31T23:59", LocalDateTimeCanonicalizer.canonicalOrNull("2026-12-31T23:59"))
    }

    @Test
    fun `a zero seconds component is a notation difference and is canonicalised`() {
        listOf(
            "2027-05-06T16:00:00",
            "2027-05-06T16:00:00.0",
            "2027-05-06T16:00:00.000",
        ).forEach {
            assertEquals(it, "2027-05-06T16:00", LocalDateTimeCanonicalizer.canonicalOrNull(it))
        }
    }

    @Test
    fun `whitespace around the value does not change it`() {
        assertEquals("2027-05-06T16:00", LocalDateTimeCanonicalizer.canonicalOrNull("  2027-05-06T16:00:00 "))
    }

    @Test
    fun `a non-zero seconds component is refused rather than truncated`() {
        listOf("2027-05-06T16:00:30", "2027-05-06T16:00:01", "2027-05-06T16:00:00.500").forEach { value ->
            val result = LocalDateTimeCanonicalizer.canonicalize(value)
            assertTrue("$value should be refused", result is LocalDateTimeCanonicalizer.Result.Rejected)
            assertTrue(
                value,
                (result as LocalDateTimeCanonicalizer.Result.Rejected).reasonKo.contains("초"),
            )
        }
    }

    @Test
    fun `a zone or offset is refused, because reinterpreting an instant is not formatting`() {
        listOf("2027-05-06T16:00Z", "2027-05-06T16:00:00Z", "2027-05-06T16:00+09:00", "2027-05-06T16:00:00-0500")
            .forEach { value ->
                val result = LocalDateTimeCanonicalizer.canonicalize(value)
                assertTrue("$value should be refused", result is LocalDateTimeCanonicalizer.Result.Rejected)
                assertTrue(
                    value,
                    (result as LocalDateTimeCanonicalizer.Result.Rejected).reasonKo.contains("시간대"),
                )
            }
    }

    @Test
    fun `a date that does not exist is refused`() {
        listOf("2026-02-30T10:00", "2027-13-01T10:00", "2027-05-06T25:00", "2027-05-06T10:61").forEach {
            assertNull(it, LocalDateTimeCanonicalizer.canonicalOrNull(it))
        }
    }

    @Test
    fun `everything that is not this format is refused with a format reason`() {
        listOf("", "   ", "2027-05-06 16:00", "2027/05/06T16:00", "내일 오후 4시", "16:00", "2027-05-06").forEach { value ->
            val result = LocalDateTimeCanonicalizer.canonicalize(value)
            assertTrue("$value should be refused", result is LocalDateTimeCanonicalizer.Result.Rejected)
        }
    }

    /** Canonicalising twice must not differ from canonicalising once. */
    @Test
    fun `canonicalisation is idempotent`() {
        listOf("2027-05-06T16:00", "2027-05-06T16:00:00").forEach { value ->
            val once = LocalDateTimeCanonicalizer.canonicalOrNull(value)!!
            assertEquals(once, LocalDateTimeCanonicalizer.canonicalOrNull(once))
        }
    }

    /** The pattern the tool contract advertises is the pattern this produces. */
    @Test
    fun `every canonical value matches the advertised pattern`() {
        val pattern = Regex(LocalDateTimeCanonicalizer.CANONICAL_PATTERN)
        listOf("2027-05-06T16:00", "2027-05-06T16:00:00", "2026-01-01T00:00:00.000").forEach {
            assertTrue(it, pattern.matches(LocalDateTimeCanonicalizer.canonicalOrNull(it)!!))
        }
    }
}
