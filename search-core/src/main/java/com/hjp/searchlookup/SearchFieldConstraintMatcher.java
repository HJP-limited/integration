package com.hjp.searchlookup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Applies a {@link SearchFieldConstraintPlan} to candidates, whichever retriever produced them.
 *
 * Every candidate goes through here — the in-memory keyword list, the Room/FTS list Android hands
 * in, and the semantic list. An upstream index matched on whatever it indexed; that is a reason to
 * consider a card, not evidence that the card satisfies what was asked. The check is repeated on
 * the card itself.
 *
 * <p>Filtering only ever removes. Rank, provenance and fusion scores on the survivors are the
 * objects the retriever built, untouched, so a candidate that came in ranked third and survives is
 * still ranked third to the fusion stage.
 */
final class SearchFieldConstraintMatcher {

    private SearchFieldConstraintMatcher() {}

    /**
     * Does this card satisfy what the query asked for?
     *
     * Fields are requirements and values within a field are alternatives: a query naming two cities
     * and a job wants somebody in either city who holds that job.
     */
    static boolean matches(BusinessCard card, SearchFieldConstraintPlan plan) {
        if (card == null) return false;
        if (plan == null || plan.constrainsNothing()) return true;

        if (!plan.locations.isEmpty()) {
            // Where somebody works is what their location and address say. A company name, a memo
            // or a tag can mention a city for a hundred reasons — a head office, a client, a trip —
            // and none of them put the person there.
            String where = SearchFieldVocabulary.normalize(card.location + " " + card.address);
            if (!containsAny(where, plan.locations)) return false;
        }
        if (!plan.titles.isEmpty() && plan.isStrict()) {
            // Word by word, not substring: 이사 must not match 대표이사, which is a different job.
            List<String> titleWords = titleWords(card);
            boolean held = false;
            for (String title : plan.titles) {
                if (titleWords.contains(title)) { held = true; break; }
            }
            if (!held) return false;
        }
        return true;
    }

    /**
     * The candidates that survive the plan.
     *
     * When the plan already knows the answer is nobody — a place was named and this address book
     * has no one there — nothing survives, however many candidates matched the rest of the sentence.
     * That is the whole point: "세종특별자치시 디자이너" asks about a combination that does not
     * exist, and the designers in other cities are not a partial answer to it.
     */
    static List<SearchResult> apply(List<SearchResult> candidates, SearchFieldConstraintPlan plan) {
        if (candidates == null || candidates.isEmpty()) return Collections.emptyList();
        if (plan == null || plan.constrainsNothing()) return candidates;
        if (plan.abstains()) return Collections.emptyList();

        if (!plan.isStrict()) {
            // A job named on its own is a broader question than a job pinned to a place. Removing
            // everything but exact title matches would answer "변호사 있나?" by hiding the 고문변호사
            // sitting right there. Order instead: exact matches first, everything else as it was.
            return plan.titles.isEmpty() ? candidates : exactTitlesFirst(candidates, plan);
        }
        List<SearchResult> kept = new ArrayList<>();
        for (SearchResult candidate : candidates) {
            if (candidate != null && matches(candidate.card, plan)) kept.add(candidate);
        }
        return Collections.unmodifiableList(kept);
    }

    /** A stable partition: nothing is dropped and equal-standing candidates keep their order. */
    private static List<SearchResult> exactTitlesFirst(List<SearchResult> candidates,
            SearchFieldConstraintPlan plan) {
        List<SearchResult> exact = new ArrayList<>();
        List<SearchResult> rest = new ArrayList<>();
        for (SearchResult candidate : candidates) {
            if (candidate == null) continue;
            List<String> titleWords = titleWords(candidate.card);
            boolean held = false;
            for (String title : plan.titles) {
                if (titleWords.contains(title)) { held = true; break; }
            }
            (held ? exact : rest).add(candidate);
        }
        exact.addAll(rest);
        return Collections.unmodifiableList(exact);
    }

    private static List<String> titleWords(BusinessCard card) {
        String title = SearchFieldVocabulary.normalize(card == null ? "" : card.title);
        if (title.isEmpty()) return Collections.emptyList();
        return Arrays.asList(title.split(" "));
    }

    private static boolean containsAny(String haystack, List<String> needles) {
        for (String needle : needles) {
            if (!needle.isEmpty() && haystack.contains(needle)) return true;
        }
        return false;
    }
}
