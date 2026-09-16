package com.hjp.searchlookup;

import java.util.*;

public final class SearchLookupService implements RetrievalService {
    private static final int RAG_CARD_LIMIT = 5;
    private final BusinessCardRepository repository;
    private final EmbeddingEngine embeddingEngine;
    private final QueryAnalyzer queryAnalyzer = new QueryAnalyzer();
    private final KeywordRetriever cardTabKeywordRetriever;
    private final KeywordRetriever agentKeywordRetriever;
    private final SemanticRetriever semanticRetriever;
    private final ReciprocalRankFusion rankFusion = new ReciprocalRankFusion();
    private final RagContextBuilder ragContextBuilder = new RagContextBuilder();
    private final SearchFieldConstraintResolver fieldConstraints;

    public SearchLookupService(List<BusinessCard> cards, EmbeddingEngine embeddingEngine) { this(new InMemoryBusinessCardRepository(cards), embeddingEngine); }
    public SearchLookupService(BusinessCardRepository repository, EmbeddingEngine embeddingEngine) {
        this.repository=repository; this.embeddingEngine=embeddingEngine==null?OnDeviceEmbeddingEngine.production():embeddingEngine;
        // Browsing with an empty box shows everyone; that is what the card list is for.
        this.cardTabKeywordRetriever = new LikeFallbackKeywordRetriever(
                repository, LikeFallbackKeywordRetriever.EmptyQueryPolicy.MATCH_ALL);
        // An agent lookup that analysed to no terms found nobody. Returning the roster here let a
        // query that matched nothing put a contact into session state.
        this.agentKeywordRetriever = new LikeFallbackKeywordRetriever(
                repository, LikeFallbackKeywordRetriever.EmptyQueryPolicy.MATCH_NONE);
        this.semanticRetriever = new SemanticRetriever(repository, this.embeddingEngine);
        // Agent lookups only. Browsing the card list is a different question and keeps its own path.
        this.fieldConstraints = new SearchFieldConstraintResolver(repository);
        if(this.embeddingEngine.isModelBacked()) {
            EmbeddingUpdater updater=new EmbeddingUpdater(repository,this.embeddingEngine);
            for(BusinessCard c:repository.getAllCards()) {
                updater.refreshIfNeeded(c);
                if(!this.embeddingEngine.isModelBacked()) break;
            }
        }
    }

    public List<SearchResult> search(String rawQuery, int limit) { return searchCardTab(rawQuery, SortOption.RELEVANCE, limit); }
    public List<SearchResult> searchCardTab(String rawQuery, SortOption sortOption, int limit) { return cardTabKeywordRetriever.retrieve(queryAnalyzer.analyze(rawQuery), limit); }
    public RetrievalResponse retrieveForAgent(String rawQuery, AgentSessionState session, int limit) { RetrievalResponse r=retrieve(rawQuery,limit); (session==null?new AgentSessionState():session).updateLastSearch(r.query,r.results); return r; }
    @Override public RetrievalResponse retrieve(String rawQuery, int topK) { return retrieve(rawQuery, topK, RetrievalMode.HYBRID); }
    public RetrievalResponse retrieve(String rawQuery, int topK, RetrievalMode mode) {
        return retrieve(rawQuery, topK, mode, null);
    }
    /**
     * Android production supplies Room FTS candidates here. A null list intentionally means
     * "use the core in-memory fallback" for desktop/JVM fixtures; an empty list means the real
     * index found no keyword match.
     */
    public RetrievalResponse retrieve(String rawQuery, int topK, RetrievalMode mode, List<SearchResult> indexedKeywordResults) {
        return retrieve(rawQuery, topK, mode, indexedKeywordResults, SearchPlanObserver.NONE);
    }
    /**
     * As above, and additionally tells {@code observer} what the search applied.
     *
     * The observer is a parameter rather than a field on purpose: per-call state cannot leak between
     * threads or sessions, and there is nothing to reset between runs. It is called last, from
     * values already computed, and anything it throws is swallowed — a measurement must never be
     * able to change or break the thing it measures.
     */
    public RetrievalResponse retrieve(String rawQuery, int topK, RetrievalMode mode,
            List<SearchResult> indexedKeywordResults, SearchPlanObserver observer) {
        long started=System.nanoTime(); int safe=Math.max(1,topK); RetrievalMode requested=mode==null?RetrievalMode.HYBRID:mode; RetrievalMode actual=requested; QueryAnalysis analysis=queryAnalyzer.analyze(rawQuery);
        String fallbackReason="";
        // A phone number or an e-mail address is looked up, not understood. Meaning has nothing to
        // say about 010-3000-6000, and fusing a semantic axis that scores R@5=0.000 on equal terms
        // pushes the good keyword hits down: P@5 measured 1.000 -> 0.233 with it mixed in. So the
        // semantic axis is dropped for these queries rather than merely down-weighted.
        //
        // The threshold is four digits because people ask that way — "번호 뒷자리 4312인 분".
        if(actual==RetrievalMode.HYBRID&&isIdentifierQuery(rawQuery)){ actual=RetrievalMode.KEYWORD_ONLY; fallbackReason="IDENTIFIER_QUERY_SEMANTIC_EXCLUDED"; }
        if(actual!=RetrievalMode.KEYWORD_ONLY&&!embeddingEngine.isModelBacked()){ actual=RetrievalMode.KEYWORD_ONLY; fallbackReason=embeddingEngine.diagnosticStatus(); }
        List<SearchResult> keyword = actual==RetrievalMode.SEMANTIC_ONLY ? Collections.emptyList() : keywordResults(analysis,indexedKeywordResults);
        List<SearchResult> semantic=Collections.emptyList();
        if(actual!=RetrievalMode.KEYWORD_ONLY){
            try {
                semantic=semanticRetriever.retrieve(analysis,Integer.MAX_VALUE);
                if(!embeddingEngine.isModelBacked()||semantic.isEmpty()){
                    actual=RetrievalMode.KEYWORD_ONLY;
                    keyword=keywordResults(analysis,indexedKeywordResults);
                    semantic=Collections.emptyList();
                    fallbackReason=embeddingEngine.diagnosticStatus();
                    if(fallbackReason==null||fallbackReason.isEmpty()||"ready".equals(fallbackReason)) fallbackReason="SEMANTIC_RESULTS_UNAVAILABLE";
                }
            } catch(Throwable error){
                actual=RetrievalMode.KEYWORD_ONLY;
                keyword=keywordResults(analysis,indexedKeywordResults);
                semantic=Collections.emptyList();
                fallbackReason="SEMANTIC_RETRIEVAL_FAILED: "+error.getClass().getSimpleName();
            }
        }
        // What the query asked for, per field. Applied to every candidate list before anything is
        // ranked or cut: a card that satisfies the question but sits outside topK cannot be
        // recovered afterwards, and a card that fails it must not occupy a slot in the first place.
        // The two reported candidate counts stay pre-constraint on purpose — they say what the
        // retrievers found, and the result list says what survived.
        SearchFieldConstraintPlan plan = fieldConstraints.resolve(analysis);
        // A bare name the roll does not carry becomes an abstention only when the keyword side also
        // found nothing. The resolver cannot decide this: it sees the word, not what the index did
        // with it. A three-syllable word starting with a known surname is often an ordinary word,
        // and if the index matched something then it is one — the name reading was wrong, and
        // abstaining would hide real answers.
        if (plan.bareNameAbsent && keyword.isEmpty()) {
            plan = plan.abstaining("NO_CARD_WITH_REQUESTED_NAME");
        }
        int keywordCandidates = keyword.size();
        int semanticCandidates = semantic.size();
        keyword = SearchFieldConstraintMatcher.apply(keyword, plan);
        semantic = SearchFieldConstraintMatcher.apply(semantic, plan);
        // A bare exact stored name is an identity constraint, not a request for similar people.
        // Keep all exact homonyms; never turn an ambiguous name into an arbitrary single person.
        java.util.Set<String> exactNameIds = new java.util.HashSet<>();
        String nameQuery = compactName(rawQuery);
        if (!nameQuery.isEmpty()) {
            for (BusinessCard card : repository.getAllCards()) {
                if (nameQuery.equals(compactName(card.name)) || nameQuery.equals(compactName(card.nameEn))) {
                    exactNameIds.add(card.id);
                }
            }
        }
        if (!exactNameIds.isEmpty()) {
            keyword = new java.util.ArrayList<>(keyword);
            semantic = new java.util.ArrayList<>(semantic);
            keyword.removeIf(result -> !exactNameIds.contains(result.cardId));
            semantic.removeIf(result -> !exactNameIds.contains(result.cardId));
        }
        List<SearchResult> results = actual==RetrievalMode.KEYWORD_ONLY ? limit(keyword,safe) : actual==RetrievalMode.SEMANTIC_ONLY ? limit(semantic,safe) : rankFusion.fuse(keyword, semantic, safe);
        String rag= actual==RetrievalMode.KEYWORD_ONLY ? "" : ragContextBuilder.build(analysis.normalizedQuery,results,Math.min(RAG_CARD_LIMIT,safe));
        boolean fb=requested!=actual||(embeddingEngine instanceof OnDeviceEmbeddingEngine&&((OnDeviceEmbeddingEngine)embeddingEngine).isFallbackUsed());
        long elapsed=(System.nanoTime()-started)/1_000_000L;
        long queryEmbeddingMillis=actual==RetrievalMode.KEYWORD_ONLY?0L:semanticRetriever.lastQueryEmbeddingMillis();
        RetrievalResponse response = new RetrievalResponse(analysis.normalizedQuery,results,rag,actual,embeddingEngine.name(),keywordCandidates,semanticCandidates,fb,analysis,fallbackReason,elapsed,queryEmbeddingMillis);
        // Last, from values that are already decided, and never allowed to matter.
        if (observer != null && observer != SearchPlanObserver.NONE) {
            try {
                observer.onSearchPlan(new SearchPlanSnapshot(analysis.normalizedQuery, analysis.tokens,
                        plan, actual, fb, keywordCandidates, semanticCandidates, response.cardIds));
            } catch (Throwable ignored) {
                // A measurement that fails is a measurement that is missing, not a search that failed.
            }
        }
        return response;
    }
    /**
     * Is this query an identifier lookup rather than a description?
     *
     * Four digits or an "@". Deliberately shape-based: the question is whether the words carry
     * meaning a vector can use, and a run of digits does not, wherever it came from.
     */
    static boolean isIdentifierQuery(String rawQuery) {
        if (rawQuery == null) return false;
        String q = rawQuery.trim();
        if (q.indexOf('@') >= 0) return true;
        int digits = 0;
        for (int i = 0; i < q.length(); i++) {
            if (Character.isDigit(q.charAt(i))) digits++;
        }
        return digits >= 4;
    }

    private static String compactName(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 조건에 맞는 명함이 **모두 몇 장인지**. 검색이 아니라 세기다.
     *
     * 검색을 태워서 세면 안 된다. 검색은 top-N 까지만 후보를 채우므로 "판교에 몇 명 있어?"에
     * 5라고 답하게 된다(실측: 실제 42명인데 5명이라고 답함). 그래서 순위와 무관하게 조건을
     * 모든 카드에 대 본다 — 조건을 읽는 기계는 검색이 쓰는 것과 같은 것이다.
     *
     * @return 조건을 하나도 못 읽었으면 {@link #COUNT_NOT_COUNTABLE}. 개념형 질의("AI 잘하는
     *     사람 몇 명이야")가 여기 해당하고, 그때는 부르는 쪽이 보통의 검색 경로로 가야 한다 —
     *     사람마다 답이 다른 질문을 숫자 하나로 답하면 틀린 확신을 준다.
     */
    public int countMatching(String rawQuery) {
        List<BusinessCard> all = repository.getAllCards();
        if (rawQuery == null || rawQuery.trim().isEmpty()) return all.size();
        SearchFieldConstraintPlan plan = fieldConstraints.resolve(queryAnalyzer.analyze(rawQuery));
        if (plan.abstains()) return 0;
        if (plan.constrainsNothing()) return COUNT_NOT_COUNTABLE;
        int matched = 0;
        for (BusinessCard card : all) {
            if (SearchFieldConstraintMatcher.matchesForCount(card, plan)) matched++;
        }
        return matched;
    }

    /** 조건을 읽지 못해 셀 수 없다는 뜻. 음수라 개수와 혼동되지 않는다. */
    public static final int COUNT_NOT_COUNTABLE = -1;

    /** The field constraints a query resolves to. Package-private: for tests in this package. */
    SearchFieldConstraintPlan fieldConstraintPlan(String rawQuery){ return fieldConstraints.resolve(queryAnalyzer.analyze(rawQuery)); }
    @Override public BusinessCard getCard(String cardId){ return repository.getCard(cardId==null?null:cardId.trim()); }
    public String engineName(){ return embeddingEngine.name(); }
    public QueryAnalysis analyzeQuery(String rawQuery){ return queryAnalyzer.analyze(rawQuery); }
    public List<SearchResult> retrieveKeywordCandidates(String rawQuery, int topK){ return agentKeywordRetriever.retrieve(queryAnalyzer.analyze(rawQuery), topK); }
    public List<SearchResult> retrieveSemanticCandidates(String rawQuery, int topK){ return semanticRetriever.retrieve(queryAnalyzer.analyze(rawQuery), topK); }
    private List<SearchResult> keywordResults(QueryAnalysis analysis,List<SearchResult> indexed){ return indexed==null?agentKeywordRetriever.retrieve(analysis,Integer.MAX_VALUE):Collections.unmodifiableList(new ArrayList<>(indexed)); }
    private List<SearchResult> limit(List<SearchResult> r,int l){ if(r==null||l<=0)return Collections.emptyList(); return Collections.unmodifiableList(new ArrayList<>(r.subList(0,Math.min(l,r.size())))); }
}
