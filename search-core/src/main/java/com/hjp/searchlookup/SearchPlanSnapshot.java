package com.hjp.searchlookup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What one {@code retrieve()} call actually did, as an immutable record taken after the fact.
 *
 * This exists because a metric computed by re-parsing the query measures the re-parser, not the
 * search. The evaluator needs to know which constraints the search *applied* — not which ones a
 * second copy of the rules thinks it would have applied — and until now the only thing that knew was
 * a package-private plan with no way out of the package.
 *
 * Everything here is read after the decision it describes has already been made. Nothing on this
 * object is consulted while ranking, filtering or limiting, so an observer cannot change a result.
 *
 * <h2>What is and is not reported</h2>
 *
 * The location and title constraints are reported because the plan really holds them. Company and
 * department are reported as <em>not resolved</em>, with {@link #constraintSource} saying so,
 * because this search has no company or department constraint axis — inventing one here so that a
 * metric had something to score would be reporting a capability that does not exist. The query
 * tokens are reported as the name/target constraint, because tokens are literally what the keyword
 * retriever matched against.
 */
public final class SearchPlanSnapshot {

    /** Where the constraints in this snapshot came from. Reported verbatim by the evaluator. */
    public static final String CONSTRAINT_SOURCE =
            "SearchFieldConstraintResolver.resolve(QueryAnalysis) as applied by "
                    + "SearchLookupService.retrieve; company and department are not constraint axes "
                    + "of this search and are reported as unresolved rather than inferred";

    private final String query;
    private final List<String> queryTokens;
    private final List<String> locations;
    private final List<String> titles;
    private final boolean locationRequested;
    private final boolean locationKnownToRepository;
    private final boolean strictFilterApplied;
    private final boolean abstained;
    private final String abstainReason;
    private final String retrievalMode;
    private final boolean fallbackUsed;
    private final int keywordCandidateCount;
    private final int semanticCandidateCount;
    private final List<String> rankedCardIds;

    SearchPlanSnapshot(String query, List<String> queryTokens, SearchFieldConstraintPlan plan,
            RetrievalMode retrievalMode, boolean fallbackUsed, int keywordCandidateCount,
            int semanticCandidateCount, List<String> rankedCardIds) {
        this.query = query == null ? "" : query;
        this.queryTokens = copy(queryTokens);
        this.locations = plan == null ? Collections.<String>emptyList() : copy(plan.locations);
        this.titles = plan == null ? Collections.<String>emptyList() : copy(plan.titles);
        this.locationRequested = plan != null && plan.locationRequested;
        this.locationKnownToRepository = plan != null && plan.locationKnownToRepository;
        this.strictFilterApplied = plan != null && plan.isStrict();
        this.abstained = plan != null && plan.abstains();
        this.abstainReason = plan == null ? "" : plan.abstainReason;
        this.retrievalMode = retrievalMode == null ? "" : retrievalMode.name();
        this.fallbackUsed = fallbackUsed;
        this.keywordCandidateCount = keywordCandidateCount;
        this.semanticCandidateCount = semanticCandidateCount;
        this.rankedCardIds = copy(rankedCardIds);
    }

    /** The normalised query the retrievers were given. */
    public String query() { return query; }

    /**
     * The analysed tokens the keyword retriever matched against.
     *
     * This is the name/target constraint as the search actually applied it. It is a token list, not
     * a list of names: deciding which of these tokens is a person's name is an inference, and the
     * search does not make it.
     */
    public List<String> queryTokens() { return queryTokens; }

    /** Locations the query constrained. Alternatives, not requirements. */
    public List<String> locations() { return locations; }

    /** Job titles the query constrained. Alternatives, not requirements. */
    public List<String> titles() { return titles; }

    /** Companies the query constrained. Always empty: this search has no company constraint axis. */
    public List<String> companies() { return Collections.emptyList(); }

    /** Departments constrained. Always empty: this search has no department constraint axis. */
    public List<String> departments() { return Collections.emptyList(); }

    /** Whether the query named a place at all. */
    public boolean locationRequested() { return locationRequested; }

    /** Whether any card in the store works in one of {@link #locations}. */
    public boolean locationKnownToRepository() { return locationKnownToRepository; }

    /** Whether candidates failing the plan were removed rather than merely ranked lower. */
    public boolean strictFilterApplied() { return strictFilterApplied; }

    /** Whether the plan already knew the answer was nobody. */
    public boolean abstained() { return abstained; }

    /** Why it abstained, or empty. */
    public String abstainReason() { return abstainReason; }

    /** The retrieval mode actually used, after any fallback. */
    public String retrievalMode() { return retrievalMode; }

    /** Whether the requested mode was not the one used. */
    public boolean fallbackUsed() { return fallbackUsed; }

    /** Keyword candidates found, before the constraint filter. */
    public int keywordCandidateCount() { return keywordCandidateCount; }

    /** Semantic candidates found, before the constraint filter. */
    public int semanticCandidateCount() { return semanticCandidateCount; }

    /** The card IDs returned, in the order they were returned. */
    public List<String> rankedCardIds() { return rankedCardIds; }

    /** How to describe where these constraints came from, for a report that has to say. */
    public String constraintSource() { return CONSTRAINT_SOURCE; }

    private static List<String> copy(List<String> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<String>(values));
    }

    @Override public String toString() {
        return "SearchPlanSnapshot{query='" + query + "', tokens=" + queryTokens
                + ", locations=" + locations + ", titles=" + titles
                + ", strict=" + strictFilterApplied + ", abstained=" + abstained
                + ", mode=" + retrievalMode + ", results=" + rankedCardIds.size() + "}";
    }
}
