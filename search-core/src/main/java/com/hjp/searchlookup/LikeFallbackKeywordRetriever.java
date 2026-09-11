package com.hjp.searchlookup;

import java.util.*;

public final class LikeFallbackKeywordRetriever implements KeywordRetriever {

    /**
     * What a query with no usable terms should match.
     *
     * The two callers want opposite things and used to share one answer. Browsing the card list with
     * an empty box means "show me everyone". An agent lookup whose query analysed to nothing —
     * "명함 찾아줘" is entirely stop words, and punctuation-only input analyses to nothing at all —
     * means the agent found no one, and returning the whole roster instead let a query that matched
     * nothing seed the session with a contact. With a single-card store it produced a
     * SINGLE_RESULT selection: an actionable target the user never named.
     */
    public enum EmptyQueryPolicy {
        /** Browse: no terms means everyone. */
        MATCH_ALL,
        /** Lookup: no terms means nothing was found. */
        MATCH_NONE,
    }

    private final BusinessCardRepository repository;
    private final EmptyQueryPolicy emptyQueryPolicy;

    public LikeFallbackKeywordRetriever(BusinessCardRepository repository) {
        this(repository, EmptyQueryPolicy.MATCH_NONE);
    }

    public LikeFallbackKeywordRetriever(BusinessCardRepository repository, EmptyQueryPolicy emptyQueryPolicy) {
        this.repository = repository;
        this.emptyQueryPolicy = emptyQueryPolicy == null ? EmptyQueryPolicy.MATCH_NONE : emptyQueryPolicy;
    }

    @Override public List<SearchResult> retrieve(QueryAnalysis analysis, int topK) {
        List<String> tokens = analysis == null ? Collections.emptyList() : analysis.tokens;
        if (tokens.isEmpty() && emptyQueryPolicy == EmptyQueryPolicy.MATCH_NONE) {
            return Collections.emptyList();
        }
        List<SearchResult> results = new ArrayList<>();
        for (BusinessCard card : repository.getAllCards()) {
            String text = card.keywordSearchableText();
            int matches = 0;
            for (String token : tokens) if (token.length() >= 2 && text.contains(token.toLowerCase(Locale.ROOT))) matches++;
            if (tokens.isEmpty() || matches > 0) {
                double score = tokens.isEmpty() ? 1.0 : (double) matches / Math.max(1, tokens.size());
                // Weak UX tie-breaker only; hybrid ranking uses list rank via RRF, not this score scale.
                if (card.verified) score += 0.001;
                results.add(new SearchResult(card, score, ScoreBreakdown.keywordOnly(score), Arrays.asList("keyword-like")));
            }
        }
        results.sort(Comparator.comparingDouble((SearchResult r) -> r.score).reversed().thenComparing(r -> r.card.name));
        return limit(rank(results), topK);
    }
    private List<SearchResult> rank(List<SearchResult> in){ List<SearchResult> out=new ArrayList<>(); for(int i=0;i<in.size();i++) out.add(in.get(i).withRank(i+1)); return out; }
    private List<SearchResult> limit(List<SearchResult> r,int l){ if(l<=0)return Collections.unmodifiableList(r); return Collections.unmodifiableList(new ArrayList<>(r.subList(0,Math.min(l,r.size())))); }
}
