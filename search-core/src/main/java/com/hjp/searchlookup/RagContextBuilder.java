package com.hjp.searchlookup;

import java.util.List;

public final class RagContextBuilder {
    public String build(String query, List<SearchResult> results, int maxCards) {
        if (results == null || results.isEmpty() || maxCards <= 0) return "";
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (SearchResult result : results) {
            if (result == null || result.card == null) continue;
            if (count >= maxCards) break;
            BusinessCard card = result.card;
            if (builder.length() > 0) builder.append('\n');
            builder.append("[cardId=").append(safe(card.id)).append("]\n");
            builder.append("name: ").append(safe(card.name)).append('\n');
            builder.append("company: ").append(safe(card.company)).append('\n');
            builder.append("title: ").append(safe(card.title)).append('\n');
            builder.append("department: ").append(safe(card.department)).append('\n');
            builder.append("industry: ").append(safe(card.industry)).append('\n');
            builder.append("location: ").append(safe(card.location)).append('\n');
            builder.append("memo: ").append(safe(card.memo)).append('\n');
            builder.append("tags: ").append(String.join(", ", card.tags)).append('\n');
            count++;
        }
        return builder.toString().trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
