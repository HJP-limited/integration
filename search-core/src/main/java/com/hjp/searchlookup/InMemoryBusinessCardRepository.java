package com.hjp.searchlookup;

import java.util.*;

public final class InMemoryBusinessCardRepository implements BusinessCardRepository {
 private final Map<String,BusinessCard> cards=new LinkedHashMap<>(); private final Map<String,CardEmbedding> embeddings=new LinkedHashMap<>();
 public InMemoryBusinessCardRepository(List<BusinessCard> initial){ if(initial!=null) for(BusinessCard c:initial) upsertCard(c); }
 public List<BusinessCard> getAllCards(){ return new ArrayList<>(cards.values()); }
 public BusinessCard getCard(String id){ return cards.get(id); }
 public void upsertCard(BusinessCard card){ if(card!=null) cards.put(card.id,card); }
 public CardEmbedding getEmbedding(String cardId,String modelName){ return embeddings.get(modelName+":"+cardId); }
 public List<CardEmbedding> getEmbeddings(String modelName){ List<CardEmbedding> out=new ArrayList<>(); for(CardEmbedding e:embeddings.values()) if(e.modelName.equals(modelName)) out.add(e); return out; }
 public void upsertEmbedding(CardEmbedding e){ if(e!=null) embeddings.put(e.modelName+":"+e.cardId,e); }
}
