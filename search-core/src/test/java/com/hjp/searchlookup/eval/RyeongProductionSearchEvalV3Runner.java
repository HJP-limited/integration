package com.hjp.searchlookup.eval;

import com.hjp.searchlookup.BusinessCard;
import com.hjp.searchlookup.LocalEmbeddingEngine;
import com.hjp.searchlookup.RetrievalMode;
import com.hjp.searchlookup.RetrievalResponse;
import com.hjp.searchlookup.SearchLookupService;
import com.hjp.searchlookup.SearchResult;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Version 3 of the production search evaluation: the current Kotlin/Java search path, measured on
 * the frozen ryeong dataset, against a bar written down before the run.
 *
 * <h2>Why a new version rather than a fix to the old one</h2>
 *
 * run_2's adapter excluded one hard negative by comparing the query to a stored string, after that
 * query had failed run_1. Every figure it produced is unusable as a pass for that reason, and
 * editing it now would put the correction in the same file as the mistake. run_2's source is
 * archived byte-exact; this is a separate runner with its own gate, and it evaluates all twenty
 * hard negatives with no exclusion mechanism at all — there is nowhere in this file to name a
 * query.
 *
 * <h2>Not part of the ordinary test suite</h2>
 *
 * This class has no test annotations and does not end in {@code Test}, so {@code gradle test} does
 * not find it. It runs only when somebody asks for it by name, through
 * {@code :search-core:runRyeongSearchEvalV3}. An official evaluation should be an act, not a
 * side effect of running unit tests.
 *
 * <h2>Output</h2>
 *
 * Everything is computed first and written into a staging directory; the official path only comes
 * into existence when the whole run has finished. A run that dies halfway leaves
 * {@code <output>.partial} behind and no result anybody could mistake for a finished one. Existing
 * evidence directories are refused outright.
 *
 * <h2>What this measures, and what it does not</h2>
 *
 * The shipping search path on the reference's cards and the reference's own ground truth. Not the
 * reference evaluator's published numbers, which were never reproduced here. With no embedding
 * model present the semantic axis does not run, every query is answered by the keyword axis, and
 * the concept categories describe keyword search attempting questions built to need meaning.
 */
public final class RyeongProductionSearchEvalV3Runner {

    public static final String RUN_ID = "ryeong_production_search_run_3";
    static final String SCHEMA_VERSION = "ryeong-production-search-eval/v3";
    static final int DEFAULT_TOP_K = 20;
    static final String ABSTENTION_CATEGORY = "기권(정답없음)";

    /** Ids no card in the frozen dataset carries, used to check that a stale lookup resolves to nothing. */
    static final List<String> STALE_ID_PROBES =
            Collections.unmodifiableList(Arrays.asList("T9999", "S99999", "DELETED-1", "", "   ", "T001-old"));

    private RyeongProductionSearchEvalV3Runner() {}

    // ---- input ---------------------------------------------------------------------------------

    static final class Query {
        final String category;
        final String text;
        final Set<String> relevantIds;

        Query(String category, String text, Set<String> relevantIds) {
            this.category = category;
            this.text = text;
            this.relevantIds = Collections.unmodifiableSet(new LinkedHashSet<>(relevantIds));
        }

        boolean isAbstention() { return relevantIds.isEmpty(); }
    }

    /**
     * The frozen cards and queries, checked for the properties every downstream number depends on.
     *
     * The checks are here rather than in a report because a dataset that quietly lost a query would
     * otherwise produce a plausible-looking evaluation over the wrong population.
     */
    static final class Input {
        final List<BusinessCard> cards;
        final List<Query> queries;

        Input(List<BusinessCard> cards, List<Query> queries) {
            this.cards = Collections.unmodifiableList(new ArrayList<>(cards));
            this.queries = Collections.unmodifiableList(new ArrayList<>(queries));
        }

        int rankingCount() {
            int n = 0;
            for (Query query : queries) if (!query.isAbstention()) n++;
            return n;
        }

        int abstentionCount() { return queries.size() - rankingCount(); }

        Map<String, Integer> categoryCounts() {
            Map<String, Integer> out = new LinkedHashMap<>();
            for (Query query : queries) {
                out.put(query.category, out.getOrDefault(query.category, 0) + 1);
            }
            return out;
        }

        /** Throws unless the input is exactly what the freeze manifest says it is. */
        void validate(int expectedCards, int expectedQueries, int expectedRanking, int expectedAbstention) {
            require(cards.size() == expectedCards, "card count " + cards.size() + " != " + expectedCards);
            require(queries.size() == expectedQueries, "query count " + queries.size() + " != " + expectedQueries);
            require(rankingCount() == expectedRanking, "ranking query count " + rankingCount() + " != " + expectedRanking);
            require(abstentionCount() == expectedAbstention, "abstention query count " + abstentionCount() + " != " + expectedAbstention);

            Set<String> cardIds = new LinkedHashSet<>();
            for (BusinessCard card : cards) {
                require(!card.id.isEmpty(), "a card has no id");
                require(cardIds.add(card.id), "duplicate card id " + card.id);
            }
            // Keyed on the pair itself rather than on the two strings joined by a separator:
            // any separator either collides with something a query could contain, or is a
            // control character that turns this source file into a binary blob for grep and diff.
            Set<List<String>> seenQueries = new LinkedHashSet<>();
            for (Query query : queries) {
                require(!query.category.isEmpty(), "query \"" + query.text + "\" has no category");
                require(!query.text.isEmpty(), "a query has no text");
                require(seenQueries.add(Arrays.asList(query.category, query.text)),
                        "duplicate query \"" + query.text + "\" in " + query.category);
                if (query.isAbstention()) {
                    require(ABSTENTION_CATEGORY.equals(query.category),
                            "query \"" + query.text + "\" has no relevant ids but is not in " + ABSTENTION_CATEGORY);
                } else {
                    require(!ABSTENTION_CATEGORY.equals(query.category),
                            "query \"" + query.text + "\" is an abstention query with relevant ids");
                    for (String id : query.relevantIds) {
                        require(cardIds.contains(id),
                                "query \"" + query.text + "\" expects card " + id + ", which is not in the dataset");
                    }
                }
            }
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new IllegalStateException("frozen input is not what was declared: " + message);
        }
    }

    static Input loadInput(Path inputDirectory) throws IOException {
        String cardsJson = read(inputDirectory.resolve("cards_eval1000.json"));
        String queriesJson = read(inputDirectory.resolve("ryeong_queries_extracted.json"));
        return new Input(parseCards(cardsJson), parseQueries(queriesJson));
    }

    static List<BusinessCard> parseCards(String json) {
        List<BusinessCard> out = new ArrayList<>();
        for (Object element : EvalJson.parseArray(json)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> card = (Map<String, Object>) element;
            out.add(new BusinessCard(
                    EvalJson.string(card, "id"), EvalJson.string(card, "name"),
                    EvalJson.string(card, "nameEn"), EvalJson.string(card, "company"),
                    EvalJson.string(card, "title"), EvalJson.string(card, "department"),
                    EvalJson.string(card, "industry"), EvalJson.string(card, "location"),
                    EvalJson.string(card, "phone"), EvalJson.string(card, "email"),
                    EvalJson.string(card, "address"), EvalJson.string(card, "memo"),
                    tags(EvalJson.string(card, "tags"))));
        }
        return out;
    }

    /** The dataset stores tags as one comma-separated string; the card model wants a list. */
    private static List<String> tags(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String tag : raw.split(",")) {
            String trimmed = tag.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    static List<Query> parseQueries(String json) {
        Map<String, Object> root = EvalJson.parseObject(json);
        List<Query> out = new ArrayList<>();
        for (Object element : EvalJson.array(root, "queries")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) element;
            Set<String> relevant = new LinkedHashSet<>();
            for (Object id : EvalJson.array(entry, "relevant_ids")) relevant.add(String.valueOf(id));
            out.add(new Query(EvalJson.string(entry, "category"), EvalJson.string(entry, "query"), relevant));
        }
        return out;
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    // ---- what a run measured --------------------------------------------------------------------

    /** Per category: the counts a gate can compare, and the sums the rates are printed from. */
    static final class CategoryCounts {
        int n;
        int top1;
        int recall5Complete;
        double p1Sum;
        double p5Sum;
        double r5Sum;
        double mrrSum;
        double ndcgSum;

        static CategoryCounts of(int n, int top1, int recall5Complete) {
            CategoryCounts counts = new CategoryCounts();
            counts.n = n;
            counts.top1 = top1;
            counts.recall5Complete = recall5Complete;
            counts.p1Sum = top1;
            counts.r5Sum = recall5Complete;
            return counts;
        }

        double rate(double sum) { return n == 0 ? 0.0 : sum / n; }
    }

    /** A hard negative that came back with somebody. */
    static final class AbstentionFailure {
        final String query;
        final List<String> returnedCardIds;
        final List<String> returnedCards;

        AbstentionFailure(String query, List<String> returnedCardIds, List<String> returnedCards) {
            this.query = query;
            this.returnedCardIds = Collections.unmodifiableList(new ArrayList<>(returnedCardIds));
            this.returnedCards = Collections.unmodifiableList(new ArrayList<>(returnedCards));
        }
    }

    static final class Outcome {
        final int cards;
        final int queriesTotal;
        final int queriesScored;
        final int abstentionQueries;
        final int excludedQueries;
        final int abstentionPassed;
        final int abstentionFailed;
        final List<AbstentionFailure> abstentionFailures;
        final int staleIdResolved;
        final int unstableRankings;
        final List<String> unstableQueries;
        final long latencyP50;
        final long latencyP95;
        final long latencyMax;
        final double keywordFallbackRatio;
        final boolean semanticAxisExecuted;
        final Map<String, CategoryCounts> byCategory;

        Outcome(int cards, int queriesTotal, int queriesScored, int abstentionQueries,
                int excludedQueries, int abstentionPassed, int abstentionFailed,
                List<AbstentionFailure> abstentionFailures, int staleIdResolved,
                int unstableRankings, List<String> unstableQueries, long latencyP50, long latencyP95,
                long latencyMax, double keywordFallbackRatio, boolean semanticAxisExecuted,
                Map<String, CategoryCounts> byCategory) {
            this.cards = cards;
            this.queriesTotal = queriesTotal;
            this.queriesScored = queriesScored;
            this.abstentionQueries = abstentionQueries;
            this.excludedQueries = excludedQueries;
            this.abstentionPassed = abstentionPassed;
            this.abstentionFailed = abstentionFailed;
            this.abstentionFailures = Collections.unmodifiableList(new ArrayList<>(abstentionFailures));
            this.staleIdResolved = staleIdResolved;
            this.unstableRankings = unstableRankings;
            this.unstableQueries = Collections.unmodifiableList(new ArrayList<>(unstableQueries));
            this.latencyP50 = latencyP50;
            this.latencyP95 = latencyP95;
            this.latencyMax = latencyMax;
            this.keywordFallbackRatio = keywordFallbackRatio;
            this.semanticAxisExecuted = semanticAxisExecuted;
            this.byCategory = Collections.unmodifiableMap(new LinkedHashMap<>(byCategory));
        }

        int categoryTop1(String category) {
            CategoryCounts counts = byCategory.get(category);
            return counts == null ? 0 : counts.top1;
        }

        int categoryRecall5Complete(String category) {
            CategoryCounts counts = byCategory.get(category);
            return counts == null ? 0 : counts.recall5Complete;
        }

        int overallTop1() {
            int total = 0;
            for (CategoryCounts counts : byCategory.values()) total += counts.top1;
            return total;
        }

        double overall(String metric) {
            double sum = 0.0;
            for (CategoryCounts counts : byCategory.values()) {
                switch (metric) {
                    case "P@1": sum += counts.p1Sum; break;
                    case "P@5": sum += counts.p5Sum; break;
                    case "R@5": sum += counts.r5Sum; break;
                    case "MRR": sum += counts.mrrSum; break;
                    default: sum += counts.ndcgSum; break;
                }
            }
            return queriesScored == 0 ? 0.0 : sum / queriesScored;
        }
    }

    // ---- measuring -------------------------------------------------------------------------------

    static Outcome evaluate(Input input, SearchLookupService service, int topK) {
        Map<String, CategoryCounts> byCategory = new LinkedHashMap<>();
        List<AbstentionFailure> abstentionFailures = new ArrayList<>();
        List<String> unstable = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();
        int abstentionPassed = 0;
        int keywordFallback = 0;
        int semanticExecuted = 0;

        for (Query query : input.queries) {
            long started = System.nanoTime();
            RetrievalResponse response = service.retrieve(query.text, topK, RetrievalMode.HYBRID);
            latencies.add((System.nanoTime() - started) / 1_000_000L);

            List<String> ranked = ids(response.results);
            if (response.mode == RetrievalMode.KEYWORD_ONLY) keywordFallback++; else semanticExecuted++;

            if (query.isAbstention()) {
                // Every one of them. There is no exclusion path in this runner.
                if (ranked.isEmpty()) {
                    abstentionPassed++;
                } else {
                    abstentionFailures.add(new AbstentionFailure(query.text, ranked, describe(response.results)));
                }
            } else {
                CategoryCounts counts = byCategory.computeIfAbsent(query.category, key -> new CategoryCounts());
                score(counts, ranked, query.relevantIds);
            }

            List<String> again = ids(service.retrieve(query.text, topK, RetrievalMode.HYBRID).results);
            if (!ranked.equals(again)) unstable.add(query.text);
        }

        int staleResolved = 0;
        for (String probe : STALE_ID_PROBES) {
            if (service.getCard(probe) != null) staleResolved++;
        }

        Collections.sort(latencies);
        long p50 = latencies.isEmpty() ? 0 : latencies.get(latencies.size() / 2);
        long p95 = latencies.isEmpty() ? 0
                : latencies.get(Math.min(latencies.size() - 1, (int) Math.floor(latencies.size() * 0.95)));
        long max = latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1);

        return new Outcome(input.cards.size(), input.queries.size(), input.rankingCount(),
                input.abstentionCount(), 0, abstentionPassed, abstentionFailures.size(),
                abstentionFailures, staleResolved, unstable.size(), unstable, p50, p95, max,
                input.queries.isEmpty() ? 0.0 : (double) keywordFallback / input.queries.size(),
                semanticExecuted > 0, byCategory);
    }

    private static void score(CategoryCounts counts, List<String> ranked, Set<String> relevant) {
        counts.n++;
        if (!ranked.isEmpty() && relevant.contains(ranked.get(0))) {
            counts.top1++;
            counts.p1Sum += 1.0;
        }
        int hitsAt5 = 0;
        for (int i = 0; i < Math.min(5, ranked.size()); i++) {
            if (relevant.contains(ranked.get(i))) hitsAt5++;
        }
        counts.p5Sum += hitsAt5 / 5.0;
        counts.r5Sum += (double) hitsAt5 / relevant.size();
        if (hitsAt5 == relevant.size()) counts.recall5Complete++;
        for (int i = 0; i < ranked.size(); i++) {
            if (relevant.contains(ranked.get(i))) { counts.mrrSum += 1.0 / (i + 1); break; }
        }
        counts.ndcgSum += ndcgAt(ranked, relevant, 5);
    }

    private static double ndcgAt(List<String> ranked, Set<String> relevant, int k) {
        double dcg = 0.0;
        for (int i = 0; i < Math.min(k, ranked.size()); i++) {
            if (relevant.contains(ranked.get(i))) dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        double ideal = 0.0;
        for (int i = 0; i < Math.min(k, relevant.size()); i++) {
            ideal += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return ideal == 0.0 ? 0.0 : dcg / ideal;
    }

    private static List<String> ids(List<SearchResult> results) {
        List<String> out = new ArrayList<>();
        for (SearchResult result : results) out.add(result.card.id);
        return out;
    }

    /** A returned card id alone does not explain a wrong answer; where it is and what it does might. */
    private static List<String> describe(List<SearchResult> results) {
        List<String> out = new ArrayList<>();
        for (SearchResult result : results) {
            BusinessCard card = result.card;
            out.add(card.id + " | location=" + card.location + " | address=" + card.address
                    + " | title=" + card.title + " | company=" + card.company);
        }
        return out;
    }

    // ---- where results may go ---------------------------------------------------------------------

    /**
     * Directories this runner refuses to write into, whatever path it is handed.
     *
     * The last runner wrote its report straight into a frozen evidence directory with a plain
     * {@code Files.write}, which truncates. It overwrote a frozen result before anyone noticed.
     */
    static final String[] FORBIDDEN_PATH_SEGMENTS = {
        "production_eval/run_1", "production_eval/run_2", "integration_evidence/final",
        "evaluator_archive", "evaluator_isolation",
        "search_field_constraints_v1/red", "search_field_constraints_v1/green",
    };

    /**
     * Validates the requested output path and returns a staging directory beside it.
     *
     * Canonicalising first is what makes the refusals mean something: a symlink, a {@code ..}
     * segment or an absolute path pointing at frozen evidence all resolve to the same real place
     * before the check runs.
     */
    static Path stageOutput(Path requested) throws IOException {
        Path canonical = requested.toFile().getCanonicalFile().toPath();
        if (Files.exists(canonical)) {
            throw new IOException("output path already exists, refusing to reuse it: " + canonical);
        }
        String normalized = canonical.toString().replace(File.separatorChar, '/');
        for (String segment : FORBIDDEN_PATH_SEGMENTS) {
            if (normalized.contains(segment)) {
                throw new IOException("refusing to write into protected evidence (" + segment + "): " + canonical);
            }
        }
        Path staging = canonical.resolveSibling(canonical.getFileName().toString() + ".partial");
        if (Files.exists(staging)) {
            throw new IOException("a previous partial run is still there, refusing to reuse it: " + staging);
        }
        Files.createDirectories(staging);
        return staging;
    }

    /** Creates a file that did not exist. Never replaces one. */
    static void writeNew(Path file, String content) throws IOException {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    /**
     * Makes the run official.
     *
     * Until this returns, the output path does not exist, so there is no half-written run for
     * anybody to read as a finished one.
     */
    static Path promote(Path staging, Path requested) throws IOException {
        Path canonical = requested.toFile().getCanonicalFile().toPath();
        if (Files.exists(canonical)) {
            throw new IOException("output path appeared during the run, refusing to overwrite: " + canonical);
        }
        try {
            return Files.move(staging, canonical, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException retry) {
            return Files.move(staging, canonical);
        }
    }

    // ---- reporting ---------------------------------------------------------------------------------

    static String resultsJson(Outcome outcome, String datasetSha, String querySha, String gateSha, int topK) {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"schema_version\": ").append(EvalJson.quote(SCHEMA_VERSION)).append(",\n");
        out.append("  \"run_id\": ").append(EvalJson.quote(RUN_ID)).append(",\n");
        out.append("  \"what_this_measures\": ").append(EvalJson.quote(
                "the current production SearchLookupService on the frozen ryeong dataset, with ground "
                        + "truth built by the reference's own build_eval_queries")).append(",\n");
        out.append("  \"is_the_reference_evaluators_own_result\": false,\n");
        out.append("  \"top_k\": ").append(topK).append(",\n");
        out.append("  \"dataset_sha256\": ").append(EvalJson.quote(datasetSha)).append(",\n");
        out.append("  \"query_set_sha256\": ").append(EvalJson.quote(querySha)).append(",\n");
        out.append("  \"gate_sha256\": ").append(EvalJson.quote(gateSha)).append(",\n");
        out.append("  \"cards\": ").append(outcome.cards).append(",\n");
        out.append("  \"queries_total\": ").append(outcome.queriesTotal).append(",\n");
        out.append("  \"queries_scored\": ").append(outcome.queriesScored).append(",\n");
        out.append("  \"abstention_queries\": ").append(outcome.abstentionQueries).append(",\n");
        out.append("  \"excluded_queries\": ").append(outcome.excludedQueries).append(",\n");
        out.append("  \"exclusion_mechanism\": ").append(EvalJson.quote("none — this runner has no way to exclude a query")).append(",\n");
        out.append("  \"semantic_axis_executed\": ").append(outcome.semanticAxisExecuted).append(",\n");
        out.append("  \"keyword_fallback_ratio\": ").append(EvalJson.rate(outcome.keywordFallbackRatio)).append(",\n");
        out.append("  \"semantic_note\": ").append(EvalJson.quote(
                "with no embedding model present every query is answered by the keyword axis. No figure "
                        + "here is semantic retrieval performance, and the concept categories describe "
                        + "keyword search attempting questions built to need meaning.")).append(",\n");

        out.append("  \"overall\": {");
        out.append("\"top1_count\": ").append(outcome.overallTop1()).append(", ");
        out.append("\"denominator\": ").append(outcome.queriesScored).append(", ");
        out.append("\"P@1\": ").append(EvalJson.rate(outcome.overall("P@1"))).append(", ");
        out.append("\"P@5\": ").append(EvalJson.rate(outcome.overall("P@5"))).append(", ");
        out.append("\"R@5\": ").append(EvalJson.rate(outcome.overall("R@5"))).append(", ");
        out.append("\"MRR\": ").append(EvalJson.rate(outcome.overall("MRR"))).append(", ");
        out.append("\"nDCG@5\": ").append(EvalJson.rate(outcome.overall("nDCG@5"))).append("},\n");

        out.append("  \"by_category\": {\n");
        int index = 0;
        for (Map.Entry<String, CategoryCounts> entry : outcome.byCategory.entrySet()) {
            CategoryCounts counts = entry.getValue();
            out.append("    ").append(EvalJson.quote(entry.getKey())).append(": {");
            out.append("\"n\": ").append(counts.n).append(", ");
            out.append("\"top1_count\": ").append(counts.top1).append(", ");
            out.append("\"recall5_complete_count\": ").append(counts.recall5Complete).append(", ");
            out.append("\"P@1\": ").append(EvalJson.rate(counts.rate(counts.p1Sum))).append(", ");
            out.append("\"P@5\": ").append(EvalJson.rate(counts.rate(counts.p5Sum))).append(", ");
            out.append("\"R@5\": ").append(EvalJson.rate(counts.rate(counts.r5Sum))).append(", ");
            out.append("\"MRR\": ").append(EvalJson.rate(counts.rate(counts.mrrSum))).append(", ");
            out.append("\"nDCG@5\": ").append(EvalJson.rate(counts.rate(counts.ndcgSum))).append("}");
            out.append(++index == outcome.byCategory.size() ? "\n" : ",\n");
        }
        out.append("  },\n");

        out.append("  \"abstention\": {");
        out.append("\"total\": ").append(outcome.abstentionQueries).append(", ");
        out.append("\"passed\": ").append(outcome.abstentionPassed).append(", ");
        out.append("\"failed\": ").append(outcome.abstentionFailed).append(", ");
        out.append("\"accuracy\": ").append(EvalJson.rate(outcome.abstentionQueries == 0 ? 0.0
                : (double) outcome.abstentionPassed / outcome.abstentionQueries)).append(", ");
        out.append("\"failures\": [");
        for (int i = 0; i < outcome.abstentionFailures.size(); i++) {
            AbstentionFailure failure = outcome.abstentionFailures.get(i);
            if (i > 0) out.append(",");
            out.append("\n      {\"query\": ").append(EvalJson.quote(failure.query));
            out.append(", \"returned_card_ids\": ").append(EvalJson.stringArray(failure.returnedCardIds));
            out.append(", \"returned_cards\": ").append(EvalJson.stringArray(failure.returnedCards)).append("}");
        }
        out.append(outcome.abstentionFailures.isEmpty() ? "]},\n" : "\n    ]},\n");

        out.append("  \"safety\": {");
        out.append("\"stale_id_resolved\": ").append(outcome.staleIdResolved).append(", ");
        out.append("\"unstable_rankings\": ").append(outcome.unstableRankings).append(", ");
        out.append("\"unstable_queries\": ").append(EvalJson.stringArray(outcome.unstableQueries)).append("},\n");
        out.append("  \"latency_ms\": {\"p50\": ").append(outcome.latencyP50)
                .append(", \"p95\": ").append(outcome.latencyP95)
                .append(", \"max\": ").append(outcome.latencyMax).append("}\n");
        out.append("}\n");
        return out.toString();
    }

    static String gateVerdictJson(RyeongProductionSearchEvalV3Gate.Verdict verdict, String gateSha) {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"run_id\": ").append(EvalJson.quote(RUN_ID)).append(",\n");
        out.append("  \"gate_sha256\": ").append(EvalJson.quote(gateSha)).append(",\n");
        out.append("  \"gate_frozen_before_the_run\": true,\n");
        out.append("  \"passed\": ").append(verdict.passed).append(",\n");
        out.append("  \"failed_check_count\": ").append(verdict.failures().size()).append(",\n");
        out.append("  \"checks\": [\n");
        for (int i = 0; i < verdict.checks.size(); i++) {
            RyeongProductionSearchEvalV3Gate.Check check = verdict.checks.get(i);
            out.append("    {\"name\": ").append(EvalJson.quote(check.name));
            out.append(", \"requirement\": ").append(EvalJson.quote(check.requirement));
            out.append(", \"expected\": ").append(check.expected);
            out.append(", \"actual\": ").append(check.actual);
            out.append(", \"passed\": ").append(check.passed).append("}");
            out.append(i == verdict.checks.size() - 1 ? "\n" : ",\n");
        }
        out.append("  ]\n}\n");
        return out.toString();
    }

    static String runStatusJson(boolean gatePassed) {
        return "{\n  \"run_id\": " + EvalJson.quote(RUN_ID) + ",\n"
                + "  \"status\": " + EvalJson.quote("EXECUTED") + ",\n"
                + "  \"evaluation_executed\": true,\n"
                + "  \"gate_passed\": " + gatePassed + ",\n"
                + "  \"means\": " + EvalJson.quote(
                        "a production-path diagnostic against a pre-registered gate. Not the reference "
                                + "evaluator's numbers, not semantic performance, not Android verification.")
                + "\n}\n";
    }

    static String sha256(Path file) throws IOException {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    // ---- entry point ---------------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        Path inputDirectory = Paths.get(require(options, "input"));
        Path gateFile = Paths.get(require(options, "gate"));
        Path outputDirectory = Paths.get(require(options, "output"));
        int topK = options.containsKey("top-k") ? Integer.parseInt(options.get("top-k")) : DEFAULT_TOP_K;

        RyeongProductionSearchEvalV3Gate gate =
                RyeongProductionSearchEvalV3Gate.parse(read(gateFile));
        Input input = loadInput(inputDirectory);
        input.validate(gate.cards, gate.queriesTotal, gate.queriesScored, gate.abstentionQueries);

        // Everything is computed before anything is written, and the official path is created last.
        SearchLookupService service = new SearchLookupService(input.cards, new LocalEmbeddingEngine());
        Outcome outcome = evaluate(input, service, topK);
        RyeongProductionSearchEvalV3Gate.Verdict verdict = gate.evaluate(outcome);

        String datasetSha = sha256(inputDirectory.resolve("cards_eval1000.json"));
        String querySha = sha256(inputDirectory.resolve("ryeong_queries_extracted.json"));
        String gateSha = sha256(gateFile);

        Path staging = stageOutput(outputDirectory);
        writeNew(staging.resolve("production_search_eval_v3_results.json"),
                resultsJson(outcome, datasetSha, querySha, gateSha, topK));
        writeNew(staging.resolve("gate_verdict.json"), gateVerdictJson(verdict, gateSha));
        writeNew(staging.resolve("RUN_STATUS.json"), runStatusJson(verdict.passed));
        Path published = promote(staging, outputDirectory);

        System.out.println("run_id=" + RUN_ID);
        System.out.println("output=" + published);
        System.out.println("gate_passed=" + verdict.passed);
        for (RyeongProductionSearchEvalV3Gate.Check failure : verdict.failures()) {
            System.out.println("  FAILED " + failure.name + " requirement " + failure.requirement
                    + " actual " + failure.actual);
        }
        if (!verdict.passed) {
            // A failing run keeps its numbers and reports the failure. It is not promoted to a pass.
            System.exit(1);
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if (!argument.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + argument);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("missing value for " + argument);
            }
            out.put(argument.substring(2), args[++i]);
        }
        return out;
    }

    private static String require(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("--" + name + " is required");
        }
        return value;
    }
}
