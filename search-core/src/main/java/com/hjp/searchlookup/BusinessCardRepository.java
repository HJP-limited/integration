package com.hjp.searchlookup;

import java.util.List;

public interface BusinessCardRepository {
    List<BusinessCard> getAllCards();
    BusinessCard getCard(String cardId);
    void upsertCard(BusinessCard card);
    CardEmbedding getEmbedding(String cardId, String modelName);
    List<CardEmbedding> getEmbeddings(String modelName);
    void upsertEmbedding(CardEmbedding embedding);
}
