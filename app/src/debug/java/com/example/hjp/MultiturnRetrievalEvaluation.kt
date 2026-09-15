package com.example.hjp

/** Scores observed current-turn retrieval, never a stale selected contact or an inferred route. */
internal object MultiturnRetrievalEvaluation {
    data class Result(
        val basis: String,
        val hitAt5: Boolean? = null,
        val recallAt5: Double? = null,
        val reciprocalRank: Double? = null,
        val failures: List<String> = emptyList(),
        val coverageGap: String? = null,
    )

    fun evaluate(goldIds: List<String>, searched: Boolean, rankedIds: List<String>, readIds: List<String>): Result {
        val gold = goldIds.toSet()
        if (gold.isEmpty()) return Result("not_applicable")
        if (searched) {
            val top5 = rankedIds.take(5)
            val found = top5.toSet().intersect(gold)
            val first = top5.indexOfFirst { it in gold }
            return Result(
                "search", found.isNotEmpty(), found.size.toDouble() / minOf(gold.size, 5),
                if (first < 0) 0.0 else 1.0 / (first + 1),
                if (found.isEmpty()) listOf("gold_missing_from_top5") else emptyList(),
            )
        }
        if (readIds.isNotEmpty()) return Result(
            "direct_read",
            failures = if (readIds.none { it in gold }) listOf("gold_missing_from_direct_read") else emptyList(),
        )
        // History/count/clarification may legitimately use no search/read. They are not Hit@5
        // successes and need a separate grounded-answer/slot check, not a guessed contact ID.
        return Result("unobserved", coverageGap = "gold_without_current_turn_search_or_read")
    }
}
