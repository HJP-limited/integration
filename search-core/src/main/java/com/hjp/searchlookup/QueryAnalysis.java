package com.hjp.searchlookup;

import java.util.*;

public final class QueryAnalysis {
    public final String rawQuery;
    public final String normalizedQuery;
    public final String keywordQuery;
    public final String semanticQuery;
    public final List<String> tokens;

    public QueryAnalysis(String rawQuery, String normalizedQuery, String keywordQuery, String semanticQuery, List<String> tokens) {
        this.rawQuery = rawQuery == null ? "" : rawQuery;
        this.normalizedQuery = normalizedQuery == null ? "" : normalizedQuery;
        this.keywordQuery = keywordQuery == null ? "" : keywordQuery;
        this.semanticQuery = semanticQuery == null ? "" : semanticQuery;
        this.tokens = Collections.unmodifiableList(new ArrayList<>(tokens == null ? Collections.emptyList() : tokens));
    }
}
