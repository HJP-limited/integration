package com.hjp.searchlookup;

/**
 * Told what one search actually did, after it has already done it.
 *
 * Read-only by construction rather than by promise. The observer is passed in per call, so there is
 * no shared field for one thread or session to read another's trace out of; it is invoked once, at
 * the end of {@code retrieve()}, with a value object built from decisions that have already been
 * made; and the call site swallows anything it throws, so a broken observer cannot change, delay or
 * fail a search.
 *
 * {@link #NONE} is the default everywhere. A search with no observer does exactly what it did before
 * this interface existed.
 */
public interface SearchPlanObserver {

    /** Does nothing. The default for every caller that is not measuring. */
    SearchPlanObserver NONE = new SearchPlanObserver() {
        @Override public void onSearchPlan(SearchPlanSnapshot snapshot) { }
    };

    void onSearchPlan(SearchPlanSnapshot snapshot);
}
