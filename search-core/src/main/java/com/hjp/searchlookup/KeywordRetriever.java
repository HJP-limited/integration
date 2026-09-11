package com.hjp.searchlookup;

import java.util.List;

public interface KeywordRetriever {
    List<SearchResult> retrieve(QueryAnalysis analysis, int topK);
}
