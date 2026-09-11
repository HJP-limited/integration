package com.hjp.searchlookup.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.hjp.searchlookup.BusinessCard;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Checks the evaluator before it is trusted to evaluate anything.
 *
 * A runner is a measuring instrument, and an instrument that has never been checked against a known
 * quantity produces numbers nobody can act on. The previous two runs are the argument: run_1
 * asserted before writing and so reported a failure with no numbers at all, and run_2 wrote its
 * results into a frozen evidence directory with a call that truncates. Neither defect was about
 * search.
 *
 * <h2>No evaluation happens here</h2>
 *
 * The gate is exercised against synthetic outcomes — numbers written by hand to be exactly at, or
 * exactly one short of, each bar. The frozen input is read and counted, which is what makes the
 * count assertions mean anything, but no query is ever run against the search path. The official
 * evaluation is a separate, explicit task.
 */
public class RyeongProductionSearchEvalV3RunnerSelfTest {

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    /** The frozen input, relative to this module's directory. */
    private static final Path INPUT =
            Paths.get("..", "integration_evidence", "production_eval", "run_3", "input");
    private static final Path GATE = Paths.get("..", "integration_evidence", "production_eval",
            "run_3", "gate", "ryeong_search_gate_v3.json");

    private RyeongProductionSearchEvalV3Gate gate() throws IOException {
        return RyeongProductionSearchEvalV3Gate.parse(
                new String(Files.readAllBytes(GATE), StandardCharsets.UTF_8));
    }

    // ---- 1-4: the frozen input is the population the gate describes ------------------------------

    @Test
    public void theFrozenInputHoldsTwoHundredAndThreeQueriesOverOneThousandCards() throws IOException {
        RyeongProductionSearchEvalV3Runner.Input input =
                RyeongProductionSearchEvalV3Runner.loadInput(INPUT);

        assertEquals("cards", 1000, input.cards.size());
        assertEquals("queries", 203, input.queries.size());
    }

    @Test
    public void theRankingDenominatorIsOneHundredAndEightyThree() throws IOException {
        RyeongProductionSearchEvalV3Runner.Input input =
                RyeongProductionSearchEvalV3Runner.loadInput(INPUT);

        assertEquals("ranking queries — not 203, and not the 143 the earlier report reached",
                183, input.rankingCount());
        assertEquals("183 + 20 = 203", 203, input.rankingCount() + input.abstentionCount());
    }

    @Test
    public void theAbstentionDenominatorIsTwenty() throws IOException {
        RyeongProductionSearchEvalV3Runner.Input input =
                RyeongProductionSearchEvalV3Runner.loadInput(INPUT);

        assertEquals("hard negatives", 20, input.abstentionCount());
        Map<String, Integer> categories = input.categoryCounts();
        assertEquals("all of them in the abstention category",
                Integer.valueOf(20), categories.get(RyeongProductionSearchEvalV3Runner.ABSTENTION_CATEGORY));
    }

    @Test
    public void nothingIsExcludedFromTheFrozenInput() throws IOException {
        RyeongProductionSearchEvalV3Runner.Input input =
                RyeongProductionSearchEvalV3Runner.loadInput(INPUT);
        // The validation is the assertion: it throws if any count, category or relevant id is off.
        input.validate(1000, 203, 183, 20);

        assertEquals("the gate expects no exclusions", 0, gate().excludedQueries);
        assertEquals("and the twenty are all still there", 20, input.abstentionCount());
    }

    // ---- 5-10: the gate actually fails when it should --------------------------------------------

    @Test
    public void oneHardNegativeThatAnswersFailsTheGate() throws IOException {
        RyeongProductionSearchEvalV3Runner.AbstentionFailure failure =
                new RyeongProductionSearchEvalV3Runner.AbstentionFailure(
                        "a hard negative", Arrays.asList("S00001"),
                        Arrays.asList("S00001 | location=어딘가 | title=무엇"));
        RyeongProductionSearchEvalV3Runner.Outcome outcome = outcome(builder ->
                builder.abstentionPassed(19).abstentionFailed(1)
                        .abstentionFailures(Collections.singletonList(failure)));

        RyeongProductionSearchEvalV3Gate.Verdict verdict = gate().evaluate(outcome);
        assertFalse("19 of 20 is what run_1 scored, and it is a failure", verdict.passed);
        assertTrue("the failure names the abstention counts: " + names(verdict),
                names(verdict).contains("abstention_passed"));
    }

    @Test
    public void twentyOfTwentyPassesTheAbstentionRequirement() throws IOException {
        RyeongProductionSearchEvalV3Gate.Verdict verdict = gate().evaluate(outcome(builder -> builder));

        assertTrue("a clean synthetic outcome must clear every bar: " + names(verdict), verdict.passed);
        assertEquals("no failures", 0, verdict.failures().size());
    }

    @Test
    public void oneStaleIdResolvingFailsTheGate() throws IOException {
        RyeongProductionSearchEvalV3Gate.Verdict verdict =
                gate().evaluate(outcome(builder -> builder.staleIdResolved(1)));

        assertFalse(verdict.passed);
        assertEquals(Collections.singletonList("stale_id_resolved"), names(verdict));
    }

    @Test
    public void oneUnstableRankingFailsTheGate() throws IOException {
        RyeongProductionSearchEvalV3Gate.Verdict verdict =
                gate().evaluate(outcome(builder -> builder.unstableRankings(1)));

        assertFalse(verdict.passed);
        assertEquals(Collections.singletonList("unstable_rankings"), names(verdict));
    }

    @Test
    public void anIdentifierCategoryFallingShortFailsTheGate() throws IOException {
        String[][] shortfalls = {{"회사명", "39"}, {"이름", "39"}, {"전화(하이픈X)", "29"}};
        for (String[] shortfall : shortfalls) {
            String category = shortfall[0];
            int reduced = Integer.parseInt(shortfall[1]);
            RyeongProductionSearchEvalV3Gate.Verdict verdict =
                    gate().evaluate(outcome(builder -> builder.categoryTop1(category, reduced)));

            assertFalse(category + " short by one must fail", verdict.passed);
            assertTrue("the failing check names the category: " + names(verdict),
                    names(verdict).contains("category_top1[" + category + "]"));
        }
    }

    @Test
    public void regionAndTitleAtThirtyEightOfFortyFailsTheGate() throws IOException {
        RyeongProductionSearchEvalV3Gate.Verdict verdict =
                gate().evaluate(outcome(builder -> builder.categoryTop1("지역+직함(문장)", 38)));

        assertFalse("the floor is 39", verdict.passed);
        assertTrue(names(verdict).contains("category_top1[지역+직함(문장)]"));

        RyeongProductionSearchEvalV3Gate.Verdict atTheFloor =
                gate().evaluate(outcome(builder -> builder.categoryTop1("지역+직함(문장)", 39)));
        assertTrue("39 is the floor itself and must pass: " + names(atTheFloor), atTheFloor.passed);
    }

    @Test
    public void losingTheOverallFloorFailsEvenWhenEveryCategoryFloorHolds() throws IOException {
        // The concept categories carry no floor of their own, so this is where a regression in them
        // would show up.
        RyeongProductionSearchEvalV3Gate.Verdict verdict =
                gate().evaluate(outcome(builder -> builder.categoryTop1("개념형(easy)", 4)));

        assertFalse("154 is the overall floor and 153 is below it", verdict.passed);
        assertEquals(Collections.singletonList("overall_top1"), names(verdict));
    }

    // ---- 11: the run identifies itself -------------------------------------------------------------

    @Test
    public void theRunIdentifiesItselfAsRunThree() throws IOException {
        assertEquals("ryeong_production_search_run_3", RyeongProductionSearchEvalV3Runner.RUN_ID);

        String results = RyeongProductionSearchEvalV3Runner.resultsJson(
                outcome(builder -> builder), "dataset-sha", "query-sha", "gate-sha", 20);
        Map<String, Object> parsed = EvalJson.parseObject(results);
        assertEquals("the result file says which run it is — run_2's said run_1",
                "ryeong_production_search_run_3", parsed.get("run_id"));
        assertEquals("ryeong_production_search_run_3",
                EvalJson.parseObject(RyeongProductionSearchEvalV3Runner.runStatusJson(true)).get("run_id"));
    }

    // ---- 12-14: where results may go ----------------------------------------------------------------

    @Test
    public void anOutputDirectoryThatAlreadyExistsIsRefused() throws IOException {
        Path existing = temporary.newFolder("already-there").toPath();

        try {
            RyeongProductionSearchEvalV3Runner.stageOutput(existing);
            fail("an existing output path must be refused, not reused");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("already exists"));
        }
    }

    @Test
    public void theFrozenRunDirectoriesAreRefusedAsOutput() throws IOException {
        // Both the real ones and a path that only resolves to them, so the check cannot be walked past.
        Path root = temporary.getRoot().toPath();
        List<Path> forbidden = Arrays.asList(
                root.resolve("integration_evidence/production_eval/run_1/results"),
                root.resolve("integration_evidence/production_eval/run_2/results"),
                root.resolve("integration_evidence/final/results"),
                root.resolve("integration_evidence/evaluator_archive/results"),
                root.resolve("integration_evidence/evaluator_isolation/results"),
                root.resolve("run_3/../../integration_evidence/production_eval/run_2/sneaky"));

        for (Path path : forbidden) {
            try {
                RyeongProductionSearchEvalV3Runner.stageOutput(path);
                fail("must refuse to write into " + path);
            } catch (IOException expected) {
                assertTrue(path + " -> " + expected.getMessage(),
                        expected.getMessage().contains("protected evidence"));
            }
        }
    }

    @Test
    public void aRunThatDiesHalfwayLeavesNoOfficialResult() throws IOException {
        Path official = temporary.getRoot().toPath().resolve("run_output");
        Path staging = RyeongProductionSearchEvalV3Runner.stageOutput(official);
        RyeongProductionSearchEvalV3Runner.writeNew(staging.resolve("partial.json"), "{}\n");

        assertTrue("the staging directory holds the half-written run", Files.isDirectory(staging));
        assertTrue(Files.exists(staging.resolve("partial.json")));
        assertFalse("and the official path does not exist until the run finishes",
                Files.exists(official));

        RyeongProductionSearchEvalV3Runner.promote(staging, official);
        assertTrue("promotion is what makes it official", Files.isDirectory(official));
        assertFalse("and the staging directory is gone", Files.exists(staging));
    }

    @Test
    public void aResultFileIsNeverReplaced() throws IOException {
        Path file = temporary.getRoot().toPath().resolve("results.json");
        RyeongProductionSearchEvalV3Runner.writeNew(file, "{\"first\": true}\n");

        try {
            RyeongProductionSearchEvalV3Runner.writeNew(file, "{\"second\": true}\n");
            fail("writing over an existing result must fail rather than truncate it");
        } catch (IOException expected) {
            assertEquals("the first write survives", "{\"first\": true}\n",
                    new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
    }

    // ---- 15: the report keeps its types ---------------------------------------------------------------

    @Test
    public void numbersBooleansAndArraysStayThemselvesInTheReport() throws IOException {
        RyeongProductionSearchEvalV3Runner.AbstentionFailure failure =
                new RyeongProductionSearchEvalV3Runner.AbstentionFailure(
                        "무엇이든", Arrays.asList("S00001", "S00002"),
                        Arrays.asList("S00001 | location=A | title=B", "S00002 | location=C | title=D"));
        RyeongProductionSearchEvalV3Runner.Outcome outcome = outcome(builder ->
                builder.abstentionPassed(18).abstentionFailed(2)
                        .abstentionFailures(Collections.singletonList(failure))
                        .unstable(Arrays.asList("어떤 질의")));

        Map<String, Object> parsed = EvalJson.parseObject(
                RyeongProductionSearchEvalV3Runner.resultsJson(outcome, "d", "q", "g", 20));

        assertTrue("cards is a number, not a string", parsed.get("cards") instanceof Double);
        assertEquals(1000.0, (Double) parsed.get("cards"), 0.0);
        assertTrue("queries_scored is a number", parsed.get("queries_scored") instanceof Double);
        assertTrue("semantic_axis_executed is a boolean",
                parsed.get("semantic_axis_executed") instanceof Boolean);
        assertEquals(Boolean.FALSE, parsed.get("semantic_axis_executed"));

        Map<String, Object> abstention = EvalJson.object(parsed, "abstention");
        assertTrue("failures is an array", abstention.get("failures") instanceof List);
        assertEquals(1, EvalJson.array(abstention, "failures").size());
        @SuppressWarnings("unchecked")
        Map<String, Object> firstFailure = (Map<String, Object>) EvalJson.array(abstention, "failures").get(0);
        assertTrue("returned ids are an array", firstFailure.get("returned_card_ids") instanceof List);
        assertEquals(2, EvalJson.array(firstFailure, "returned_card_ids").size());

        Map<String, Object> safety = EvalJson.object(parsed, "safety");
        assertTrue("unstable queries are an array", safety.get("unstable_queries") instanceof List);

        Map<String, Object> byCategory = EvalJson.object(parsed, "by_category");
        Map<String, Object> company = EvalJson.object(byCategory, "회사명");
        assertTrue("a category count is a number", company.get("top1_count") instanceof Double);
        assertEquals(40, EvalJson.integer(company, "top1_count"));

        Map<String, Object> verdict = EvalJson.parseObject(
                RyeongProductionSearchEvalV3Runner.gateVerdictJson(gate().evaluate(outcome), "g"));
        assertTrue("the verdict flag is a boolean", verdict.get("passed") instanceof Boolean);
        assertTrue("the checks are an array", verdict.get("checks") instanceof List);
    }

    // ---- the runner is not part of the ordinary suite ---------------------------------------------------

    @Test
    public void theRunnerIsNotSomethingAnOrdinaryTestRunWouldTrigger() {
        // JUnit finds tests by annotation; the runner has none and is not named like one. This is the
        // guard against an official evaluation happening as a side effect of `gradle test`.
        for (java.lang.reflect.Method method : RyeongProductionSearchEvalV3Runner.class.getDeclaredMethods()) {
            assertFalse(method.getName() + " must not be a test",
                    method.isAnnotationPresent(Test.class));
        }
        assertFalse("the class name must not look like a test class",
                RyeongProductionSearchEvalV3Runner.class.getSimpleName().endsWith("Test"));
    }

    @Test
    public void theCardParserKeepsTheFieldsTheConstraintsDependOn() {
        // tags arrive as one comma-separated string in this dataset and have to become a list, and
        // location and address have to survive intact — the location condition reads only those two.
        List<BusinessCard> cards = RyeongProductionSearchEvalV3Runner.parseCards(
                "[{\"id\":\"T001\",\"name\":\"손다은\",\"nameEn\":\"Daeun Son\",\"company\":\"(주) 블루오션\","
                        + "\"title\":\"AI 개발자\",\"department\":\"데이터팀\",\"industry\":\"it\","
                        + "\"location\":\"전라남도 순천시\",\"phone\":\"010-3000-6000\","
                        + "\"email\":\"a@b.co.kr\",\"address\":\"전라남도 순천시 월드컵로 11\","
                        + "\"memo\":\"메모\",\"tags\":\"인공지능, 머신러닝, 모델학습\"}]");

        assertEquals(1, cards.size());
        BusinessCard card = cards.get(0);
        assertEquals("전라남도 순천시", card.location);
        assertEquals("전라남도 순천시 월드컵로 11", card.address);
        assertEquals("AI 개발자", card.title);
        assertEquals(Arrays.asList("인공지능", "머신러닝", "모델학습"), card.tags);
    }

    // ---- synthetic outcomes ---------------------------------------------------------------------------

    private interface Mutation {
        Builder apply(Builder builder);
    }

    private RyeongProductionSearchEvalV3Runner.Outcome outcome(Mutation mutation) {
        return mutation.apply(new Builder()).build();
    }

    /**
     * An outcome that sits exactly on every bar, and a way to move one number off it.
     *
     * Written by hand rather than measured: a gate test has to know the answer independently of the
     * thing being gated.
     */
    private static final class Builder {
        private final Map<String, Integer> top1 = new LinkedHashMap<>();
        private final Map<String, Integer> counts = new LinkedHashMap<>();
        private final Map<String, Integer> recall5 = new LinkedHashMap<>();
        private int abstentionPassed = 20;
        private int abstentionFailed = 0;
        private List<RyeongProductionSearchEvalV3Runner.AbstentionFailure> failures = new ArrayList<>();
        private int staleIdResolved = 0;
        private int unstableRankings = 0;
        private List<String> unstableQueries = new ArrayList<>();

        Builder() {
            put("회사명", 40, 40, 40);
            put("이름", 40, 40, 40);
            put("전화(하이픈X)", 30, 30, 30);
            put("지역+직함(문장)", 40, 39, 40);
            put("개념형(easy)", 14, 5, 1);
            put("개념형(hard)", 19, 0, 0);
        }

        private void put(String category, int n, int top1Count, int recall5Complete) {
            counts.put(category, n);
            top1.put(category, top1Count);
            recall5.put(category, recall5Complete);
        }

        Builder categoryTop1(String category, int value) { top1.put(category, value); return this; }
        Builder abstentionPassed(int value) { abstentionPassed = value; return this; }
        Builder abstentionFailed(int value) { abstentionFailed = value; return this; }
        Builder abstentionFailures(List<RyeongProductionSearchEvalV3Runner.AbstentionFailure> value) {
            failures = value; return this;
        }
        Builder staleIdResolved(int value) { staleIdResolved = value; return this; }
        Builder unstableRankings(int value) { unstableRankings = value; return this; }
        Builder unstable(List<String> queries) {
            unstableQueries = queries; unstableRankings = queries.size(); return this;
        }

        RyeongProductionSearchEvalV3Runner.Outcome build() {
            Map<String, RyeongProductionSearchEvalV3Runner.CategoryCounts> byCategory = new LinkedHashMap<>();
            int scored = 0;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                byCategory.put(entry.getKey(), RyeongProductionSearchEvalV3Runner.CategoryCounts.of(
                        entry.getValue(), top1.get(entry.getKey()), recall5.get(entry.getKey())));
                scored += entry.getValue();
            }
            return new RyeongProductionSearchEvalV3Runner.Outcome(
                    1000, 203, scored, 20, 0, abstentionPassed, abstentionFailed, failures,
                    staleIdResolved, unstableRankings, unstableQueries, 1L, 1L, 2L, 1.0, false,
                    byCategory);
        }
    }

    private static List<String> names(RyeongProductionSearchEvalV3Gate.Verdict verdict) {
        List<String> out = new ArrayList<>();
        for (RyeongProductionSearchEvalV3Gate.Check check : verdict.failures()) out.add(check.name);
        return out;
    }
}
