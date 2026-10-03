package com.hjp.searchlookup;

import java.util.Set;

/** Shared surname boundary for stored Korean names; compound surnames stay together. */
final class KoreanSurnames {
    private static final Set<String> COMPOUND = Set.of(
            "남궁", "황보", "제갈", "선우", "사공", "서문", "독고", "동방", "어금",
            "망절", "무본", "황목");

    private KoreanSurnames() {}

    static boolean isCompound(String surname) {
        return COMPOUND.contains(surname);
    }

    static String fromName(String rawName) {
        String name = SearchFieldVocabulary.normalize(rawName).replace(" ", "");
        if (name.length() < 2) return "";
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) < '가' || name.charAt(i) > '힣') return "";
        }
        String two = name.substring(0, 2);
        return name.length() > 2 && isCompound(two) ? two : name.substring(0, 1);
    }
}
