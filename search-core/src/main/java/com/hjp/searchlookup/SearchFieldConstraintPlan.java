package com.hjp.searchlookup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a query asked for, per field, once the words have been read.
 *
 * A query that names a place and a job is stating two things about one person. Keeping the two
 * apart — rather than folding them into a bag of tokens — is what lets the search answer "nobody"
 * when the combination does not exist, instead of handing back whoever matched half of it.
 *
 * <p>Values inside one field are alternatives (OR): "서울 또는 부산의 디자이너" carries two
 * locations and means either. Different fields are requirements (AND): the same query still demands
 * the title as well. Only the OR half is exercised by today's callers; the AND half is the fix.
 *
 * <p>Package-private on purpose. This is how the search reasons internally, not something callers
 * configure.
 */
final class SearchFieldConstraintPlan {

    /** The plan for a query that constrained nothing — the overwhelming majority of them. */
    static final SearchFieldConstraintPlan NONE =
            new SearchFieldConstraintPlan(Collections.<String>emptyList(),
                    Collections.<String>emptyList(), false, false, "");

    /** Locations named in the query, already normalised. Alternatives, not requirements. */
    final List<String> locations;

    /** Titles named in the query, already normalised. Alternatives, not requirements. */
    final List<String> titles;

    /**
     * Whether the query named a place at all.
     *
     * This is the switch between the two regimes. A query that pins a location is precise about
     * where, so the whole combination is enforced and an impossible one returns nothing. A query
     * that only names a job is a broader question — "변호사 있나?" — and narrowing it to exact
     * title matches would throw away the neighbouring answers the person probably wanted.
     */
    final boolean locationRequested;

    /** Whether any card in the repository actually works in one of {@link #locations}. */
    final boolean locationKnownToRepository;

    /** Empty unless the plan already knows the answer is nobody. */
    final String abstainReason;

    private SearchFieldConstraintPlan(List<String> locations, List<String> titles,
            boolean locationRequested, boolean locationKnownToRepository, String abstainReason) {
        this.locations = Collections.unmodifiableList(new ArrayList<>(locations));
        this.titles = Collections.unmodifiableList(new ArrayList<>(titles));
        this.locationRequested = locationRequested;
        this.locationKnownToRepository = locationKnownToRepository;
        this.abstainReason = abstainReason == null ? "" : abstainReason;
    }

    static SearchFieldConstraintPlan of(List<String> locations, List<String> titles,
            boolean locationKnownToRepository, String abstainReason) {
        if (locations.isEmpty() && titles.isEmpty()) return NONE;
        return new SearchFieldConstraintPlan(locations, titles, !locations.isEmpty(),
                locationKnownToRepository, abstainReason);
    }

    /** True when the answer is already known to be nobody, whatever the retrievers turn up. */
    boolean abstains() {
        return !abstainReason.isEmpty();
    }

    /**
     * True when candidates that fail the plan must be removed rather than merely ranked lower.
     *
     * Tied to {@link #locationRequested} — see the note there.
     */
    boolean isStrict() {
        return locationRequested;
    }

    /** True when the query named a job and no place: ordering territory, not filtering territory. */
    boolean isTitleOnly() {
        return !titles.isEmpty() && !locationRequested;
    }

    boolean constrainsNothing() {
        return locations.isEmpty() && titles.isEmpty();
    }

    @Override public String toString() {
        return "SearchFieldConstraintPlan{locations=" + locations + ", titles=" + titles
                + ", strict=" + isStrict() + ", locationKnown=" + locationKnownToRepository
                + ", abstainReason='" + abstainReason + "'}";
    }
}
