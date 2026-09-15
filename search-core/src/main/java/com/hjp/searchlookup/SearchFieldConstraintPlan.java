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
                    Collections.<String>emptyList(), false, false, "", false);

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

    /**
     * Organisational units named in the query. A requirement, not an ordering hint: a team is an
     * exact unit, so a card outside it is not a weaker answer, it is a different question.
     */
    final List<String> departments;
    final List<String> companies;

    /** Empty unless the plan already knows the answer is nobody. */
    final String abstainReason;

    /**
     * The query named somebody with no honorific, spelled out of parts the data uses, and nobody
     * is called that.
     *
     * Not an abstention on its own. A bare three-syllable word that happens to start with a
     * surname can be an ordinary noun — 조련사, 조종사, 임원급 all do — so the decision needs one
     * more fact the resolver cannot see: whether the keyword retriever found anything at all. If
     * it did, the word is a real word in this data and the name reading was wrong.
     * {@link SearchLookupService} settles it.
     */
    final boolean bareNameAbsent;

    private SearchFieldConstraintPlan(List<String> locations, List<String> titles,
            boolean locationRequested, boolean locationKnownToRepository, String abstainReason,
            boolean bareNameAbsent) {
        this(locations, titles, Collections.<String>emptyList(), locationRequested,
                locationKnownToRepository, abstainReason, bareNameAbsent);
    }

    private SearchFieldConstraintPlan(List<String> locations, List<String> titles,
            List<String> departments, boolean locationRequested,
            boolean locationKnownToRepository, String abstainReason, boolean bareNameAbsent) {
        this(locations, titles, departments, Collections.<String>emptyList(), locationRequested,
                locationKnownToRepository, abstainReason, bareNameAbsent);
    }

    private SearchFieldConstraintPlan(List<String> locations, List<String> titles,
            List<String> departments, List<String> companies, boolean locationRequested,
            boolean locationKnownToRepository, String abstainReason, boolean bareNameAbsent) {
        this.departments = Collections.unmodifiableList(new ArrayList<>(departments));
        this.companies = Collections.unmodifiableList(new ArrayList<>(companies));
        this.locations = Collections.unmodifiableList(new ArrayList<>(locations));
        this.titles = Collections.unmodifiableList(new ArrayList<>(titles));
        this.locationRequested = locationRequested;
        this.locationKnownToRepository = locationKnownToRepository;
        this.abstainReason = abstainReason == null ? "" : abstainReason;
        this.bareNameAbsent = bareNameAbsent;
    }

    static SearchFieldConstraintPlan of(List<String> locations, List<String> titles,
            boolean locationKnownToRepository, String abstainReason) {
        return of(locations, titles, locationKnownToRepository, abstainReason, false);
    }

    static SearchFieldConstraintPlan of(List<String> locations, List<String> titles,
            boolean locationKnownToRepository, String abstainReason, boolean bareNameAbsent) {
        return of(locations, titles, Collections.<String>emptyList(), locationKnownToRepository,
                abstainReason, bareNameAbsent);
    }

    static SearchFieldConstraintPlan of(List<String> locations, List<String> titles,
            List<String> departments, boolean locationKnownToRepository, String abstainReason,
            boolean bareNameAbsent) {
        return of(locations, titles, departments, Collections.<String>emptyList(),
                locationKnownToRepository, abstainReason, bareNameAbsent);
    }

    static SearchFieldConstraintPlan of(List<String> locations, List<String> titles,
            List<String> departments, List<String> companies, boolean locationKnownToRepository,
            String abstainReason, boolean bareNameAbsent) {
        if (locations.isEmpty() && titles.isEmpty() && departments.isEmpty() && companies.isEmpty()
                && (abstainReason == null || abstainReason.isEmpty()) && !bareNameAbsent) {
            return NONE;
        }
        return new SearchFieldConstraintPlan(locations, titles, departments, companies, !locations.isEmpty(),
                locationKnownToRepository, abstainReason, bareNameAbsent);
    }

    /** The same plan, now certain the answer is nobody. */
    SearchFieldConstraintPlan abstaining(String reason) {
        if (abstains()) return this;
        return new SearchFieldConstraintPlan(locations, titles, departments, companies, locationRequested,
                locationKnownToRepository, reason, bareNameAbsent);
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
        // An abstention constrains everything, even with no field named: "정하은 명함" resolves no
        // location and no title, yet the answer is already known to be nobody.
        return locations.isEmpty() && titles.isEmpty() && departments.isEmpty() && companies.isEmpty()
                && !abstains() && !bareNameAbsent;
    }

    @Override public String toString() {
        return "SearchFieldConstraintPlan{locations=" + locations + ", titles=" + titles
                + ", strict=" + isStrict() + ", locationKnown=" + locationKnownToRepository
                + ", abstainReason='" + abstainReason + "'}";
    }
}
