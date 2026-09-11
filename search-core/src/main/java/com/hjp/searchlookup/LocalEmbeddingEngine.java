package com.hjp.searchlookup;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LocalEmbeddingEngine implements EmbeddingEngine {
    private static final int DIMENSION = 192;

    private final Map<String, List<String>> conceptMap = new HashMap<>();

    public LocalEmbeddingEngine() {
        conceptMap.put("투자", Arrays.asList("투자", "금융", "vc", "ir", "스타트업", "핀테크", "대표"));
        conceptMap.put("제조", Arrays.asList("제조", "생산", "공장", "라인", "검사", "품질"));
        conceptMap.put("개발", Arrays.asList("개발", "엔지니어", "ai", "임베딩", "검색", "플랫폼", "백엔드"));
        conceptMap.put("행사", Arrays.asList("행사", "세미나", "컨퍼런스", "네트워킹", "미팅"));
        conceptMap.put("보안", Arrays.asList("보안", "개인정보", "로컬", "온디바이스", "법무"));
        conceptMap.put("연동", Arrays.asList("연동", "캘린더", "메일", "문자", "예약", "crm"));
        conceptMap.put("디자인", Arrays.asList("디자인", "브랜드", "시각", "ui", "카드", "캠페인"));
    }

    @Override
    public float[] embed(String input) {
        float[] vector = new float[DIMENSION];
        if (input == null) return vector;

        String normalized = input.toLowerCase(Locale.KOREAN)
                .replaceAll("[^0-9a-zA-Z가-힣\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isEmpty()) return vector;

        for (String word : normalized.split("\\s+")) {
            addFeature(vector, "w:" + word, 1.0f);
            addCharNgrams(vector, word);
        }

        for (Map.Entry<String, List<String>> entry : conceptMap.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (normalized.contains(keyword.toLowerCase(Locale.KOREAN))) {
                    addFeature(vector, "concept:" + entry.getKey(), 3.0f);
                }
            }
        }

        normalize(vector);
        return vector;
    }

    @Override
    public String name() {
        return "LocalEmbeddingEngine";
    }

    @Override
    public boolean isModelBacked() {
        return false;
    }

    private void addCharNgrams(float[] vector, String word) {
        if (word.length() < 2) return;
        for (int n = 2; n <= 3; n++) {
            for (int i = 0; i <= word.length() - n; i++) {
                addFeature(vector, "g:" + word.substring(i, i + n), 0.35f);
            }
        }
    }

    private void addFeature(float[] vector, String feature, float weight) {
        int hash = feature.hashCode();
        int index = Math.abs(hash % DIMENSION);
        float sign = (hash & 1) == 0 ? 1f : -1f;
        vector[index] += sign * weight;
    }

    private void normalize(float[] vector) {
        float sum = 0f;
        for (float value : vector) {
            sum += value * value;
        }
        if (sum == 0f) return;
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
    }
}
