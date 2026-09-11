package com.hjp.searchlookup;

import java.util.*;

public final class ReciprocalRankFusion {
    public static final int DEFAULT_RRF_K = 60;
    private final int k;
    public ReciprocalRankFusion() { this(DEFAULT_RRF_K); }
    public ReciprocalRankFusion(int k) { this.k = k <= 0 ? DEFAULT_RRF_K : k; }
    public List<SearchResult> fuse(List<SearchResult> keywordResults, List<SearchResult> semanticResults, int topK) {
        Map<String,Double> scores=new LinkedHashMap<>(); Map<String,BusinessCard> cards=new HashMap<>(); Map<String,Double> sims=new HashMap<>(); Map<String,Set<String>> src=new HashMap<>(); Map<String,Integer> keywordRanks=new HashMap<>(),semanticRanks=new HashMap<>();
        add(scores,cards,sims,src,keywordRanks,keywordResults,"keyword"); add(scores,cards,sims,src,semanticRanks,semanticResults,"semantic");
        List<SearchResult> out=new ArrayList<>();
        for(String id:scores.keySet()){ double s=scores.get(id); out.add(new SearchResult(cards.get(id),s,new ScoreBreakdown(0,sims.getOrDefault(id,0.0),0,0,s),new ArrayList<>(src.get(id)),0,sims.getOrDefault(id,0.0),s,keywordRanks.getOrDefault(id,0),semanticRanks.getOrDefault(id,0))); }
        out.sort(Comparator.comparingDouble((SearchResult r)->r.rankFusionScore).reversed());
        List<SearchResult> ranked=new ArrayList<>(); for(int i=0;i<out.size();i++) ranked.add(out.get(i).withRank(i+1));
        if(topK>0 && ranked.size()>topK) ranked = new ArrayList<>(ranked.subList(0, topK));
        return Collections.unmodifiableList(ranked);
    }
    private void add(Map<String,Double> scores,Map<String,BusinessCard> cards,Map<String,Double> sims,Map<String,Set<String>> src,Map<String,Integer> ranks,List<SearchResult> list,String source){ if(list==null)return; for(int i=0;i<list.size();i++){ SearchResult r=list.get(i); int rank=r.rank>0?r.rank:i+1; scores.put(r.cardId,scores.getOrDefault(r.cardId,0.0)+1.0/(k+rank)); cards.put(r.cardId,r.card); sims.put(r.cardId,Math.max(sims.getOrDefault(r.cardId,0.0),r.similarity)); src.computeIfAbsent(r.cardId,x->new LinkedHashSet<>()).add(source); ranks.put(r.cardId,rank); } }
}
