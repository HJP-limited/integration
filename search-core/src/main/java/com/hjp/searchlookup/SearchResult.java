package com.hjp.searchlookup;

import java.util.*;

public final class SearchResult {
 public final BusinessCard card; public final double score; public final String cardId,name,company,title; public final int rank,keywordRank,semanticRank; public final List<String> retrievalSources; public final double similarity, rankFusionScore; public final ScoreBreakdown breakdown; public final List<String> matchedFields;
 public SearchResult(BusinessCard card,double score){ this(card,score,new ScoreBreakdown(score,0,0,0,score),Collections.emptyList(),0,0.0,score,0,0); }
 public SearchResult(BusinessCard card,double score,ScoreBreakdown breakdown,List<String> sources){ this(card,score,breakdown,sources,0,breakdown==null?0:breakdown.semanticScore,score,0,0); }
 public SearchResult(BusinessCard card,double score,ScoreBreakdown breakdown,List<String> sources,int rank,double similarity,double rankFusionScore){ this(card,score,breakdown,sources,rank,similarity,rankFusionScore,0,0); }
 public SearchResult(BusinessCard card,double score,ScoreBreakdown breakdown,List<String> sources,int rank,double similarity,double rankFusionScore,int keywordRank,int semanticRank){ this.card=card; this.score=score; this.cardId=card==null?"":card.id; this.name=card==null?"":card.name; this.company=card==null?"":card.company; this.title=card==null?"":card.title; this.rank=rank; this.retrievalSources=Collections.unmodifiableList(new ArrayList<>(sources==null?Collections.emptyList():sources)); this.matchedFields=this.retrievalSources; this.similarity=similarity; this.rankFusionScore=rankFusionScore; this.breakdown=breakdown==null?new ScoreBreakdown(0,similarity,0,0,score):breakdown; this.keywordRank=keywordRank; this.semanticRank=semanticRank; }
 public SearchResult withRank(int newRank){ boolean keywordOnly=containsSource("keyword")&&!containsSource("semantic"); boolean semanticOnly=containsSource("semantic")&&!containsSource("keyword"); return new SearchResult(card,score,breakdown,retrievalSources,newRank,similarity,rankFusionScore,keywordRank>0?keywordRank:(keywordOnly?newRank:0),semanticRank>0?semanticRank:(semanticOnly?newRank:0)); }
 private boolean containsSource(String expected){ for(String source:retrievalSources) if(source!=null&&source.contains(expected)) return true; return false; }
}
