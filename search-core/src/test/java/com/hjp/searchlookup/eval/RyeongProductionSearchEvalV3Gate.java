package com.hjp.searchlookup.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bar run_3 has to clear, fixed in a file before the run and read back from it.
 *
 * <h2>Counts, not rounded rates</h2>
 *
 * Every hard check compares whole numbers. run_2 reported {@code P@1: 0.9750} for the region+title
 * category, and a threshold written as {@code >= 0.975} would have been decided by the fourth
 * decimal of a division. The same fact stated as 39 of 40 cannot drift.
 *
 * <h2>What is deliberately not a gate</h2>
 *
 * The concept categories are measured with no embedding model, so their numbers describe keyword
 * search answering questions built to need meaning. Freezing them as a bar would be freezing the
 * absence of the model. They are reported and not gated.
 *
 * MRR and nDCG are reported for the same reason a thermometer is read even when nothing is being
 * decided by it: run_2 only preserved them rounded to four places, so a threshold taken from those
 * digits would be a threshold on rounding. They are reported and not gated.
 */
final class RyeongProductionSearchEvalV3Gate {

    /** One pass/fail line of the verdict. */
    static final class Check {
        final String name;
        final String requirement;
        final long expected;
        final long actual;
        final boolean passed;

        Check(String name, String requirement, long expected, long actual, boolean passed) {
            this.name = name;
            this.requirement = requirement;
            this.expected = expected;
            this.actual = actual;
            this.passed = passed;
        }
    }

    static final class Verdict {
        final List<Check> checks;
        final boolean passed;

        Verdict(List<Check> checks) {
            this.checks = Collections.unmodifiableList(new ArrayList<>(checks));
            boolean all = true;
            for (Check check : this.checks) all = all && check.passed;
            this.passed = all;
        }

        List<Check> failures() {
            List<Check> out = new ArrayList<>();
            for (Check check : checks) if (!check.passed) out.add(check);
            return out;
        }
    }

    final int cards;
    final int queriesTotal;
    final int queriesScored;
    final int abstentionQueries;
    final int excludedQueries;
    final int abstentionPassed;
    final int abstentionFailed;
    final int staleIdResolved;
    final int unstableRankings;
    final int overallTop1Min;
    final Map<String, Integer> categoryTop1Min;
    final Map<String, Integer> categoryRecall5CompleteMin;

    private RyeongProductionSearchEvalV3Gate(Map<String, Object> json) {
        Map<String, Object> exact = EvalJson.object(json, "exact_counts");
        this.cards = EvalJson.integer(exact, "cards");
        this.queriesTotal = EvalJson.integer(exact, "queries_total");
        this.queriesScored = EvalJson.integer(exact, "queries_scored");
        this.abstentionQueries = EvalJson.integer(exact, "abstention_queries");
        this.excludedQueries = EvalJson.integer(exact, "excluded_queries");
        this.abstentionPassed = EvalJson.integer(exact, "abstention_passed");
        this.abstentionFailed = EvalJson.integer(exact, "abstention_failed");
        this.staleIdResolved = EvalJson.integer(exact, "stale_id_resolved");
        this.unstableRankings = EvalJson.integer(exact, "unstable_rankings");

        Map<String, Object> floors = EvalJson.object(json, "minimum_counts");
        this.overallTop1Min = EvalJson.integer(floors, "overall_top1");
        this.categoryTop1Min = readCounts(EvalJson.object(floors, "category_top1"));
        this.categoryRecall5CompleteMin =
                readCounts(EvalJson.object(floors, "category_recall5_complete"));
    }

    private static Map<String, Integer> readCounts(Map<String, Object> raw) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            out.put(entry.getKey(), EvalJson.integer(raw, entry.getKey()));
        }
        return Collections.unmodifiableMap(out);
    }

    static RyeongProductionSearchEvalV3Gate parse(String json) {
        return new RyeongProductionSearchEvalV3Gate(EvalJson.parseObject(json));
    }

    Verdict evaluate(RyeongProductionSearchEvalV3Runner.Outcome outcome) {
        List<Check> checks = new ArrayList<>();
        equalTo(checks, "cards", cards, outcome.cards);
        equalTo(checks, "queries_total", queriesTotal, outcome.queriesTotal);
        equalTo(checks, "queries_scored", queriesScored, outcome.queriesScored);
        equalTo(checks, "abstention_queries", abstentionQueries, outcome.abstentionQueries);
        equalTo(checks, "excluded_queries", excludedQueries, outcome.excludedQueries);
        equalTo(checks, "abstention_passed", abstentionPassed, outcome.abstentionPassed);
        equalTo(checks, "abstention_failed", abstentionFailed, outcome.abstentionFailed);
        equalTo(checks, "stale_id_resolved", staleIdResolved, outcome.staleIdResolved);
        equalTo(checks, "unstable_rankings", unstableRankings, outcome.unstableRankings);

        atLeast(checks, "overall_top1", overallTop1Min, outcome.overallTop1());
        for (Map.Entry<String, Integer> entry : categoryTop1Min.entrySet()) {
            atLeast(checks, "category_top1[" + entry.getKey() + "]", entry.getValue(),
                    outcome.categoryTop1(entry.getKey()));
        }
        for (Map.Entry<String, Integer> entry : categoryRecall5CompleteMin.entrySet()) {
            atLeast(checks, "category_recall5_complete[" + entry.getKey() + "]", entry.getValue(),
                    outcome.categoryRecall5Complete(entry.getKey()));
        }
        return new Verdict(checks);
    }

    private static void equalTo(List<Check> checks, String name, long expected, long actual) {
        checks.add(new Check(name, "== " + expected, expected, actual, expected == actual));
    }

    private static void atLeast(List<Check> checks, String name, long floor, long actual) {
        checks.add(new Check(name, ">= " + floor, floor, actual, actual >= floor));
    }
}
