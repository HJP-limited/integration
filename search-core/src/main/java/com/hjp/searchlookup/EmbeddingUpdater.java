package com.hjp.searchlookup;

import java.nio.charset.StandardCharsets; import java.security.MessageDigest;

public final class EmbeddingUpdater {
 private final BusinessCardRepository repo; private final EmbeddingEngine engine;
 public EmbeddingUpdater(BusinessCardRepository repo, EmbeddingEngine engine){ this.repo=repo; this.engine=engine; }
 public void upsertCardAndRefreshEmbedding(BusinessCard card){ repo.upsertCard(card); refreshIfNeeded(card); }
 public void refreshIfNeeded(BusinessCard card){
  if(card==null||!engine.isModelBacked()) return;
  String text=card.searchableText(); String hash=sha256(text); String engineName=engine.name();
  CardEmbedding old=repo.getEmbedding(card.id,engineName); if(old!=null&&hash.equals(old.sourceTextHash)) return;
  float[] v=engine.embedDocument(text);
  if(!engine.isModelBacked()||v==null||v.length==0) return;
  CosineSimilarity.normalizeInPlace(v); long now=System.currentTimeMillis();
  repo.upsertEmbedding(new CardEmbedding(card.id, engineName, v.length, FloatVectorCodec.toBlob(v), hash, old==null?now:old.createdAt, now));
 }
 public static String sha256(String s){ try{ MessageDigest md=MessageDigest.getInstance("SHA-256"); byte[] d=md.digest((s==null?"":s).getBytes(StandardCharsets.UTF_8)); StringBuilder b=new StringBuilder(); for(byte x:d)b.append(String.format("%02x",x)); return b.toString(); }catch(Exception e){ throw new IllegalStateException(e); } }
}
