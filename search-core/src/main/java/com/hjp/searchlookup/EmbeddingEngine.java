package com.hjp.searchlookup;

public interface EmbeddingEngine {
    float[] embed(String input);

    default float[] embedQuery(String input) {
        return embed(input);
    }

    default float[] embedDocument(String input) {
        return embed(input);
    }

    default float[] embedCard(BusinessCard card) {
        return embedDocument(card.searchableText());
    }

    default Float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return null;
        return CosineSimilarity.cosine(a, b);
    }

    String name();

    boolean isModelBacked();

    default String diagnosticStatus() {
        return isModelBacked() ? "ready" : "not_model_backed";
    }
}
