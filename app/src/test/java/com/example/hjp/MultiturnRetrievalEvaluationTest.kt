package com.example.hjp

import org.junit.Assert.*
import org.junit.Test

class MultiturnRetrievalEvaluationTest {
    private fun score(gold: List<String>, ranked: List<String>, reads: List<String> = emptyList(), searched: Boolean = true) =
        MultiturnRetrievalEvaluation.evaluate(gold, searched, ranked, reads)

    @Test fun `a plausible answer cannot compensate for the wrong retrieved card`() {
        assertEquals(listOf("gold_missing_from_top5"), score(listOf("right"), listOf("wrong")).failures)
    }
    @Test fun `a gold card at rank six does not pass hit at five`() {
        val result = score(listOf("right"), listOf("1", "2", "3", "4", "5", "right"))
        assertEquals(false, result.hitAt5)
        assertEquals(0.0, result.reciprocalRank!!, 0.0)
    }
    @Test fun `multi gold recall and first relevant rank are not the same as hit`() {
        val result = score(listOf("a", "b", "c"), listOf("wrong", "b", "c"))
        assertEquals(true, result.hitAt5)
        assertEquals(2.0 / 3, result.recallAt5!!, 0.00001)
        assertEquals(0.5, result.reciprocalRank!!, 0.0)
    }
    @Test fun `duplicate cards cannot inflate recall`() {
        assertEquals(0.5, score(listOf("a", "b"), listOf("a", "a", "a")).recallAt5!!, 0.0)
    }
    @Test fun `a direct read is evaluated without inventing a search rank`() {
        val result = score(listOf("a"), emptyList(), listOf("a"), false)
        assertEquals("direct_read", result.basis)
        assertNull(result.hitAt5)
        assertTrue(result.failures.isEmpty())
        assertTrue(score(listOf("a"), emptyList(), listOf("wrong"), false).failures.isNotEmpty())
    }
    @Test fun `an empty search cannot be rescued by an unrelated later read`() {
        assertFalse(score(listOf("a"), emptyList(), listOf("a")).failures.isEmpty())
    }
    @Test fun `no current retrieval remains explicitly unscored`() {
        val result = score(listOf("a"), emptyList(), searched = false)
        assertNotNull(result.coverageGap)
        assertNull(result.hitAt5)
    }
    @Test fun `turns without gold have no retrieval metric`() {
        assertEquals("not_applicable", score(emptyList(), emptyList()).basis)
    }
}
