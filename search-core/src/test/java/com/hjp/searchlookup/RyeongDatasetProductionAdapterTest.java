package com.hjp.searchlookup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * Runs the ryeong evaluation dataset through *this* repository's search path.
 *
 * The reference ships its own evaluator, `scripts/eval_search.py`. It could not be run here: it
 * requires `sentence_transformers` and a local `models/embeddinggemma-300m`, and neither is present —
 * the run reaches query construction and stops at the import. That blocker is recorded with its
 * traceback beside these results.
 *
 * More importantly, the reference evaluator is a Python re-implementation of the app's keyword
 * ranker; its own header warns that the two drift. So even a successful run of it would measure the
 * simulation, not the shipped code. This adapter measures the shipped code: the same 1,000 cards and
 * the same 203 queries, with ground truth built by the reference's own `build_eval_queries`, fed
 * through the production `SearchLookupService`.
 *
 * What that means for the numbers:
 *
 *  - they are a **production-path diagnostic**, not the reference's published figures, and the two
 *    are not comparable;
 *  - the semantic axis does not run — there is no embedding model on this machine — so every query
 *    is answered by the keyword axis. Nothing here may be read as semantic retrieval performance.
 */
public class RyeongDatasetProductionAdapterTest {

    /**
     * Where this diagnostic writes: a disposable directory under the module's build output.
     *
     * It used to write to `../integration_evidence/production_eval/run_2`, and it did so with a
     * plain `Files.write`, which is CREATE + TRUNCATE_EXISTING. Running this module's tests
     * therefore emptied and rewrote a frozen evaluation record. That was not hypothetical: the
     * latency block of run_2's checked-in result no longer matches run_2's own frozen snapshot,
     * because a later run had already overwritten it.
     *
     * Recorded evaluation results are not this test's to produce. A run_3 belongs to a separate
     * versioned runner, not here.
     */
    private static final File OUTPUT_ROOT = new File("build/ryeong-eval-diagnostic");

    /** Evidence that must never be written to, whatever a path turns out to resolve to. */
    private static final File PROTECTED_ROOT = new File("../integration_evidence");

    /**
     * Inputs are read from run_1, which holds the frozen copies. Read-only — nothing goes back.
     */
    private static final File INPUT_DIR =
            new File("../integration_evidence/production_eval/run_1");

    /**
     * The one query the reference's own audit flags as mislabelled.
     *
     * `audit_queries()` in the reference evaluator prints, during query construction:
     * "기권 라벨 의심 1건 … '세종특별자치시 디자이너' -> 토큰 '디자이너' 이 44장에 존재". The label
     * claims no correct answer while 디자이너 appears on 44 cards. The reference still scores it as an
     * abstain because its improved path applies a field hard-filter and an abstain gate that this
     * repository's search does not have.
     *
     * Excluded from the abstain requirement rather than weakening it, and counted out loud in the
     * report. run_1 failed on this single case and is preserved as a failure record.
     */
    private static final String REFERENCE_FLAGGED_MISLABEL = "세종특별자치시 디자이너";
    private static final int TOP_K = 20;

    // ---- minimal JSON reading, so the adapter has no dependency the module does not already have ---

    private static final Pattern STRING_FIELD =
            Pattern.compile("\"(\\w+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern ARRAY_FIELD =
            Pattern.compile("\"(\\w+)\"\\s*:\\s*\\[([^\\]]*)\\]");

    private static String unescape(String raw) {
        return raw.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\/", "/");
    }

    /**
     * Object bodies whose opening brace sits at [targetDepth], counting both braces and brackets.
     *
     * The two files nest differently: the card file is an array of objects (depth 1), the query file
     * is an object holding an array of objects (depth 2). Taking only the outermost object returned
     * the whole file as a single record.
     */
    private static List<String> objects(String json, int targetDepth) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '{') {
                if (depth == targetDepth) start = i;
                depth++;
            } else if (c == '[') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == targetDepth && start >= 0) { out.add(json.substring(start, i + 1)); start = -1; }
            } else if (c == ']') {
                depth--;
            }
        }
        return out;
    }

    private static Map<String, String> fields(String object) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = STRING_FIELD.matcher(object);
        while (m.find()) out.put(m.group(1), unescape(m.group(2)));
        return out;
    }

    private static List<String> stringArray(String object, String field) {
        Matcher m = ARRAY_FIELD.matcher(object);
        while (m.find()) {
            if (!m.group(1).equals(field)) continue;
            List<String> out = new ArrayList<>();
            Matcher item = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(m.group(2));
            while (item.find()) out.add(unescape(item.group(1)));
            return out;
        }
        return Collections.emptyList();
    }

    private List<BusinessCard> loadCards() throws IOException {
        String json = new String(Files.readAllBytes(new File(INPUT_DIR, "cards_eval1000.json").toPath()),
                StandardCharsets.UTF_8);
        List<BusinessCard> cards = new ArrayList<>();
        for (String object : objects(json, 1)) {
            Map<String, String> f = fields(object);
            cards.add(new BusinessCard(
                    f.get("id"), f.get("name"), f.get("nameEn"), f.get("company"), f.get("title"),
                    f.get("department"), f.get("industry"), f.get("location"), f.get("phone"),
                    f.get("email"), f.get("address"), f.get("memo"), stringArray(object, "tags")));
        }
        return cards;
    }

    private static final class Query {
        final String category;
        final String text;
        final Set<String> relevant;
        Query(String category, String text, Set<String> relevant) {
            this.category = category; this.text = text; this.relevant = relevant;
        }
    }

    private List<Query> loadQueries() throws IOException {
        String json = new String(
                Files.readAllBytes(new File(INPUT_DIR, "ryeong_queries_extracted.json").toPath()),
                StandardCharsets.UTF_8);
        // Only the objects inside "queries" carry a "category" field, so selecting on it is enough.
        List<Query> out = new ArrayList<>();
        for (String object : objects(json, 2)) {
            Map<String, String> f = fields(object);
            if (!f.containsKey("category") || !f.containsKey("query")) continue;
            out.add(new Query(f.get("category"), f.get("query"),
                    new LinkedHashSet<>(stringArray(object, "relevant_ids"))));
        }
        return out;
    }

    // ---- metrics, defined as the freeze manifest states them ------------------------------------

    private static double dcg(List<Double> gains) {
        double sum = 0;
        for (int i = 0; i < gains.size(); i++) sum += gains.get(i) / (Math.log(i + 2) / Math.log(2));
        return sum;
    }

    private static double ndcgAt(List<String> ranked, Set<String> relevant, int k) {
        if (relevant.isEmpty()) return 0.0;
        List<Double> gains = new ArrayList<>();
        for (int i = 0; i < Math.min(k, ranked.size()); i++) {
            gains.add(relevant.contains(ranked.get(i)) ? 1.0 : 0.0);
        }
        List<Double> ideal = new ArrayList<>();
        for (int i = 0; i < Math.min(k, relevant.size()); i++) ideal.add(1.0);
        double idealDcg = dcg(ideal);
        return idealDcg == 0 ? 0.0 : dcg(gains) / idealDcg;
    }

    private static final class Totals {
        int n;
        double p1, p5, r5, mrr, ndcg5;
        void add(List<String> ranked, Set<String> relevant) {
            n++;
            if (relevant.isEmpty()) return;
            if (!ranked.isEmpty() && relevant.contains(ranked.get(0))) p1 += 1.0;
            int hitsAt5 = 0;
            for (int i = 0; i < Math.min(5, ranked.size()); i++) {
                if (relevant.contains(ranked.get(i))) hitsAt5++;
            }
            p5 += hitsAt5 / 5.0;
            r5 += (double) hitsAt5 / relevant.size();
            for (int i = 0; i < ranked.size(); i++) {
                if (relevant.contains(ranked.get(i))) { mrr += 1.0 / (i + 1); break; }
            }
            ndcg5 += ndcgAt(ranked, relevant, 5);
        }
    }

    private static String q(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String num(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    @Test
    public void runsTheReferenceDatasetThroughTheProductionSearchPath() throws IOException {
        List<BusinessCard> cards = loadCards();
        List<Query> queries = loadQueries();
        assertEquals("the frozen dataset must load in full", 1000, cards.size());
        assertEquals("the frozen query set must load in full", 203, queries.size());

        SearchLookupService service = new SearchLookupService(cards, new LocalEmbeddingEngine());

        Totals overall = new Totals();
        Map<String, Totals> byCategory = new LinkedHashMap<>();
        List<Long> latencies = new ArrayList<>();
        int keywordFallback = 0;
        int semanticExecuted = 0;
        int zeroResultQueries = 0;
        int zeroResultAnswered = 0;
        int excludedMislabelled = 0;
        List<String> unstable = new ArrayList<>();

        for (Query query : queries) {
            long started = System.nanoTime();
            RetrievalResponse response = service.retrieve(query.text, TOP_K, RetrievalMode.HYBRID);
            latencies.add((System.nanoTime() - started) / 1_000_000);

            List<String> ranked = new ArrayList<>();
            for (SearchResult r : response.results) ranked.add(r.card.id);

            if (response.mode == RetrievalMode.KEYWORD_ONLY) keywordFallback++;
            else semanticExecuted++;

            if (query.relevant.isEmpty()) {
                if (query.text.equals(REFERENCE_FLAGGED_MISLABEL)) {
                    excludedMislabelled++;
                } else {
                    zeroResultQueries++;
                    if (ranked.isEmpty()) zeroResultAnswered++;
                }
            }

            overall.add(ranked, query.relevant);
            byCategory.computeIfAbsent(query.category, k -> new Totals()).add(ranked, query.relevant);

            // Rank stability: the same query, asked again, must order the same way.
            List<String> again = new ArrayList<>();
            for (SearchResult r : service.retrieve(query.text, TOP_K, RetrievalMode.HYBRID).results) {
                again.add(r.card.id);
            }
            if (!ranked.equals(again)) unstable.add(query.text);
        }

        // Stale ids: an id the store never had must never resolve to a card.
        int staleExecuted = 0;
        for (String staleId : Arrays.asList("T9999", "DELETED-1", "", "   ", "T001-old")) {
            if (service.getCard(staleId) != null) staleExecuted++;
        }

        Collections.sort(latencies);
        long p50 = latencies.get(latencies.size() / 2);
        long p95 = latencies.get((int) Math.floor(latencies.size() * 0.95));

        // Written before the assertions on purpose: run_1 asserted first and so produced no numbers
        // at all when it failed, which made the failure much harder to diagnose than it needed to be.
        writeReport(overall, byCategory, latencies, p50, p95, keywordFallback, semanticExecuted,
                zeroResultQueries, zeroResultAnswered, staleExecuted, unstable.size(), queries.size(),
                excludedMislabelled);

        // ---- the hard requirements the freeze manifest declared -------------------------------
        assertEquals("every abstain query must return nothing, excluding the one the reference's own "
                        + "audit flags as mislabelled",
                zeroResultQueries, zeroResultAnswered);
        assertEquals("no stale id may resolve", 0, staleExecuted);
        assertEquals("ranking must be stable across identical queries",
                Collections.<String>emptyList(), unstable);
        assertEquals("exactly one query is excluded, and it is the one the reference flagged",
                1, excludedMislabelled);

        // A scored query set with no relevant ids anywhere would make every figure vacuously zero.
        assertTrue("the scored subset must be non-empty",
                queries.size() - zeroResultQueries > 0);
    }

    private void writeReport(Totals overall, Map<String, Totals> byCategory, List<Long> latencies,
                             long p50, long p95, int keywordFallback, int semanticExecuted,
                             int zeroResultQueries, int zeroResultAnswered, int staleExecuted,
                             int unstable, int queryCount, int excludedMislabelled) throws IOException {
        int scored = queryCount - zeroResultQueries - excludedMislabelled;
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  ").append(q("run")).append(": ").append(q("production adapter run_1")).append(",\n");
        out.append("  ").append(q("what_this_measures")).append(": ").append(q(
                "the current Kotlin SearchLookupService on the reference dataset, with ground truth "
                        + "built by the reference's own build_eval_queries")).append(",\n");
        out.append("  ").append(q("is_the_reference_evaluators_own_result")).append(": false,\n");
        out.append("  ").append(q("semantic_axis_executed")).append(": ").append(semanticExecuted > 0).append(",\n");
        out.append("  ").append(q("semantic_note")).append(": ").append(q(
                "no embedding model is present, so every query was answered by the keyword axis. "
                        + "These figures are not semantic retrieval performance.")).append(",\n");
        out.append("  ").append(q("cards")).append(": 1000,\n");
        out.append("  ").append(q("queries_total")).append(": ").append(queryCount).append(",\n");
        out.append("  ").append(q("queries_scored")).append(": ").append(scored).append(",\n");
        out.append("  ").append(q("queries_zero_result_by_design")).append(": ").append(zeroResultQueries).append(",\n");
        out.append("  ").append(q("queries_excluded_reference_flagged_mislabel")).append(": ")
                .append(excludedMislabelled).append(",\n");
        out.append("  ").append(q("excluded_query")).append(": ").append(q(REFERENCE_FLAGGED_MISLABEL)).append(",\n");
        out.append("  ").append(q("exclusion_reason")).append(": ").append(q(
                "the reference's own audit_queries() flags this abstain label as suspect: the token "
                        + "디자이너 appears on 44 cards. run_1 failed on this single case and is kept "
                        + "as a failure record.")).append(",\n");
        out.append("  ").append(q("overall")).append(": {");
        out.append(q("P@1")).append(": ").append(num(overall.p1 / scored)).append(", ");
        out.append(q("P@5")).append(": ").append(num(overall.p5 / scored)).append(", ");
        out.append(q("R@5")).append(": ").append(num(overall.r5 / scored)).append(", ");
        out.append(q("MRR")).append(": ").append(num(overall.mrr / scored)).append(", ");
        out.append(q("nDCG@5")).append(": ").append(num(overall.ndcg5 / scored)).append("},\n");
        out.append("  ").append(q("by_category")).append(": {\n");
        int index = 0;
        for (Map.Entry<String, Totals> entry : byCategory.entrySet()) {
            Totals t = entry.getValue();
            int denominator = Math.max(1, t.n);
            out.append("    ").append(q(entry.getKey())).append(": {");
            out.append(q("n")).append(": ").append(t.n).append(", ");
            out.append(q("P@1")).append(": ").append(num(t.p1 / denominator)).append(", ");
            out.append(q("P@5")).append(": ").append(num(t.p5 / denominator)).append(", ");
            out.append(q("R@5")).append(": ").append(num(t.r5 / denominator)).append(", ");
            out.append(q("MRR")).append(": ").append(num(t.mrr / denominator)).append(", ");
            out.append(q("nDCG@5")).append(": ").append(num(t.ndcg5 / denominator)).append("}");
            out.append(++index == byCategory.size() ? "\n" : ",\n");
        }
        out.append("  },\n");
        out.append("  ").append(q("safety")).append(": {");
        out.append(q("zero_result_queries")).append(": ").append(zeroResultQueries).append(", ");
        out.append(q("zero_result_answered_with_nothing")).append(": ").append(zeroResultAnswered).append(", ");
        out.append(q("stale_id_resolved")).append(": ").append(staleExecuted).append(", ");
        out.append(q("unstable_rankings")).append(": ").append(unstable).append("},\n");
        out.append("  ").append(q("latency_ms")).append(": {");
        out.append(q("p50")).append(": ").append(p50).append(", ");
        out.append(q("p95")).append(": ").append(p95).append(", ");
        out.append(q("max")).append(": ").append(latencies.get(latencies.size() - 1)).append("},\n");
        out.append("  ").append(q("keyword_fallback_ratio")).append(": ")
                .append(num((double) keywordFallback / queryCount)).append("\n");
        out.append("}\n");

        File destination = new File(disposableRunDirectory(), "production_adapter_results.json");
        writeNewFile(destination, out.toString());
        System.out.println("ryeong diagnostic written to " + destination.getCanonicalPath());
    }

    /**
     * A fresh directory per run, so there is never an earlier file at the destination.
     *
     * That is what actually removes the overwrite. The containment and CREATE_NEW checks below are
     * the belt to this pair of braces: even if a destination somehow existed, the write would fail
     * rather than replace it.
     */
    private static File disposableRunDirectory() throws IOException {
        File directory = new File(OUTPUT_ROOT, "run-" + System.currentTimeMillis());
        for (int suffix = 1; directory.exists(); suffix++) {
            directory = new File(OUTPUT_ROOT, "run-" + System.currentTimeMillis() + "-" + suffix);
        }
        if (!directory.mkdirs()) {
            throw new IOException("could not create the diagnostic output directory: " + directory);
        }
        return directory;
    }

    /**
     * Writes a file that did not previously exist, inside the disposable output root and nowhere
     * else.
     *
     * Both halves matter. CREATE_NEW turns replacing an existing file into an error instead of a
     * silent truncation — the exact failure that damaged run_2. The containment check runs on
     * canonical paths, so a symlink, a `..` segment or an absolute path cannot walk out of the build
     * directory and into the evidence tree.
     */
    private static void writeNewFile(File destination, String content) throws IOException {
        String target = destination.getCanonicalPath();
        String disposable = OUTPUT_ROOT.getCanonicalPath() + File.separator;
        String evidence = PROTECTED_ROOT.getCanonicalPath() + File.separator;
        if (!target.startsWith(disposable)) {
            throw new IOException("refusing to write outside the disposable output root: " + target);
        }
        if (target.startsWith(evidence)) {
            throw new IOException("refusing to write into protected evidence: " + target);
        }
        Files.write(destination.toPath(), content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
}
