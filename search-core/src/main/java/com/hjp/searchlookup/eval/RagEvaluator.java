package com.hjp.searchlookup.eval;

import com.hjp.searchlookup.*;
import java.io.*; import java.nio.charset.StandardCharsets; import java.util.*;

public final class RagEvaluator { private RagEvaluator() {}
 public static Metrics evaluateAt5(List<EvalCase> cases, ResultProvider provider){ if(cases==null||cases.isEmpty()) return new Metrics(0,0,0); double recall=0,mrr=0,top1=0; for(EvalCase c:cases){ List<SearchResult> r=provider.search(c.query,5); int rank=firstRelevantRank(r,c.relevantCardIds,5); if(rank>0){recall++; mrr+=1.0/rank; if(rank==1) top1++;}} double t=cases.size(); return new Metrics(recall/t,mrr/t,top1/t); }
 public static Map<RetrievalMode,Metrics> evaluateAll(SearchLookupService service,List<EvalCase> cases){ Map<RetrievalMode,Metrics> m=new LinkedHashMap<>(); for(RetrievalMode mode:RetrievalMode.values()) m.put(mode,evaluateAt5(cases,(q,l)->service.retrieve(q,l,mode).results)); return m; }
 public static List<EvalCase> loadJsonl(File file){ List<EvalCase> out=new ArrayList<>(); if(file==null||!file.exists()) return sampleCases(); try(BufferedReader br=new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))){ String line; while((line=br.readLine())!=null){ String id=value(line,"id"), q=value(line,"query"), type=value(line,"type"); List<String> ids=array(line,"relevantCardIds"); if(!q.isEmpty()) out.add(new EvalCase(id,q,ids,type)); }} catch(IOException e){ throw new IllegalStateException(e);} return out; }
 private static String value(String line,String key){ java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\\""+key+"\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").matcher(line); return m.find()?m.group(1):""; }
 private static List<String> array(String line,String key){ java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\\""+key+"\\\"\\s*:\\s*\\[(.*?)\\]").matcher(line); List<String> out=new ArrayList<>(); if(m.find()){ java.util.regex.Matcher v=java.util.regex.Pattern.compile("\\\"([^\\\"]*)\\\"").matcher(m.group(1)); while(v.find()) out.add(v.group(1)); } return out; }
 public static List<EvalCase> sampleCases(){ return Arrays.asList(new EvalCase("Q001","AI 개발팀 사람 찾아줘",Arrays.asList("C002"),"hybrid"),new EvalCase("Q002","투자 미팅에서 만난 대표",Arrays.asList("C001"),"memo"),new EvalCase("Q003","제조 품질 담당자",Arrays.asList("C003"),"keyword")); }
 private static int firstRelevantRank(List<SearchResult> results,List<String> ids,int limit){ if(results==null||ids==null) return -1; for(int i=0;i<Math.min(limit,results.size());i++) if(ids.contains(results.get(i).cardId)) return i+1; return -1; }
 public interface ResultProvider{ List<SearchResult> search(String query,int limit); }
 public static final class EvalCase{ public final String id,query,type; public final List<String> relevantCardIds; public EvalCase(String query,List<String> ids){this("",query,ids,"");} public EvalCase(String id,String query,List<String> ids,String type){this.id=id;this.query=query;this.relevantCardIds=ids;this.type=type;} }
 public static final class Metrics{ public final double recallAt5,mrrAt5,top1Accuracy; public Metrics(double r,double m,double t){recallAt5=r;mrrAt5=m;top1Accuracy=t;} public String toString(){return String.format("Top1 Accuracy=%.3f Recall@5=%.3f MRR@5=%.3f",top1Accuracy,recallAt5,mrrAt5);} }
}
