package com.hjp.searchlookup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Canonical document text shared by live and precomputed EmbeddingGemma vectors. */
public final class EmbeddingInput {
    private EmbeddingInput() {}

    public static String forCard(String name, String nameEn, String company, String title,
            String department, String industry, String location, String memo, List<String> tags) {
        List<String> parts = new ArrayList<>();
        add(parts, name);
        add(parts, nameEn);
        add(parts, company);
        add(parts, title);
        add(parts, department);
        add(parts, industry);
        add(parts, location);
        add(parts, memo);
        String tagText = String.join(", ", tags == null ? Collections.emptyList() : tags);
        add(parts, tagText);
        return String.join(", ", parts);
    }

    private static void add(List<String> parts, String value) {
        if (value != null && !value.trim().isEmpty()) parts.add(value);
    }
}
