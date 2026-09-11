package com.hjp.searchlookup;

import java.util.*;

public final class QueryAnalyzer {
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "찾아줘", "찾아", "알려줘", "있는", "사람", "명함", "연락처", "누구",
            "please", "find", "show", "me", "who", "is", "are", "the", "a", "an"));
    private static final List<String> KOREAN_SUFFIXES = Arrays.asList(
            "에서는", "에서", "에게", "한테", "으로", "이랑", "부터", "까지", "처럼", "밖에",
            // Copula forms. Longest first, because the first match wins: "이야" has to be tried
            // before "야" and before "이", or "남다은씨야" reduces to "남다은씨이" and never matches.
            //
            // "-(이)야" ends a correction — "손서윤씨가 아니라 남다은씨야". Without it the new name is
            // never extracted and the correction is dropped, leaving focus on the old person.
            // "-인" is the adnominal form used to state a condition — "주소가 대전인 사람",
            // "직급이 상무인 사람". Without it "대전인" never matches 대전 and the condition is lost.
            "이야", "야", "인",
            "은", "는", "이", "가", "을", "를", "에", "의", "와", "과", "도", "만", "랑", "로",
            "씨", "님");

    /**
     * Punctuation that may sit at the end of a word but never inside a stem.
     *
     * {@link #normalize} keeps '.', '-' and '_' because e-mail addresses and phone numbers need
     * them. That leaves a sentence-final period stuck to the last word, and a token like
     * "남다은씨야." matches no suffix at all. Only the trailing run is removed, so
     * "pc001@example.invalid" and "010-0000-0000" are untouched.
     */
    private static final String TRAILING_PUNCTUATION = ".!?,;:";

    /** Honorifics that attach to a person's name. Stripped separately from grammatical particles. */
    private static final List<String> NAME_HONORIFICS = Arrays.asList("씨", "님");
    public QueryAnalysis analyze(String rawQuery) {
        String raw = rawQuery == null ? "" : rawQuery;
        String normalized = normalize(raw);
        List<String> tokens = tokenize(normalized);
        return new QueryAnalysis(raw, normalized, String.join(" ", tokens), normalized, tokens);
    }

    public String normalize(String raw) {
        if (raw == null) return "";
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        // Preserve Korean/English/numbers plus email/phone-friendly characters: @ . + - _ #
        String cleaned = lower.replaceAll("[^\\p{IsHangul}\\p{L}\\p{N}@._+\\-#\\s]", " ");
        cleaned = cleaned.replaceAll("(?<![\\p{L}\\p{N}])#", " ");
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    private List<String> tokenize(String normalized) {
        if (normalized == null || normalized.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String token : normalized.split("\\s+")) {
            String t = trimTrailingPunctuation(token.trim());
            if (t.isEmpty() || STOP_WORDS.contains(t)) continue;
            String stem = stripKoreanSuffixRepeatedly(t);
            if (!STOP_WORDS.contains(stem)) addDistinct(out, stem);
            if (stem.equals(t)) addDistinct(out, t);
            String digits = t.replaceAll("[^0-9]", "");
            if (digits.length() >= 3) addDistinct(out, digits);
        }
        return out;
    }

    /** Drops a trailing run of sentence punctuation, keeping anything that is part of the word. */
    private String trimTrailingPunctuation(String token) {
        int end = token.length();
        while (end > 0 && TRAILING_PUNCTUATION.indexOf(token.charAt(end - 1)) >= 0) end--;
        // An all-punctuation token is left alone; the caller drops it as empty either way.
        return end == 0 ? token : token.substring(0, end);
    }

    /**
     * One grammatical suffix, then at most one honorific.
     *
     * "남다은씨야" stacks an honorific under a copula ending, and a single pass leaves "남다은씨".
     * Looping the whole list instead would eat the name: 남다은 ends in 은, which is also a
     * particle, so a second unrestricted pass yields "남다". Upstream avoids that by keeping the
     * two jobs apart — the ranker strips particles, the gazetteer strips honorifics — and this
     * does the same in one place: each stage runs once, and the second stage only knows honorifics.
     */
    private String stripKoreanSuffixRepeatedly(String token) {
        return stripHonorific(stripKoreanSuffix(token));
    }

    private String stripHonorific(String token) {
        for (String honorific : NAME_HONORIFICS) {
            if (token.endsWith(honorific)) {
                String stem = token.substring(0, token.length() - honorific.length());
                if (stem.length() >= 2) return stem;
            }
        }
        return token;
    }

    private String stripKoreanSuffix(String token) {
        boolean hasHangul = token.codePoints().anyMatch(cp ->
                Character.UnicodeScript.of(cp) == Character.UnicodeScript.HANGUL);
        if (!hasHangul) return token;
        for (String suffix : KOREAN_SUFFIXES) {
            if (token.endsWith(suffix)) {
                String stem = token.substring(0, token.length() - suffix.length());
                if (stem.length() >= 2) return stem;
            }
        }
        return token;
    }

    private void addDistinct(List<String> values, String value) {
        if (value != null && !value.isEmpty() && !values.contains(value)) values.add(value);
    }
}
