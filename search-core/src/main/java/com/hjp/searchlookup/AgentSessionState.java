package com.hjp.searchlookup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class AgentSessionState {
    private static final List<String> PRONOUNS = Arrays.asList(
            "그 사람", "그분", "그 분", "걔", "그 회사");
    private static final List<String> ATTRIBUTE_PREFIXES = Arrays.asList(
            "회사", "직급", "직책", "부서", "번호", "전화", "이메일", "메일", "주소", "지역");
    private String lastQuery = "";
    private List<SearchResult> lastSearchResults = Collections.emptyList();
    private String lastSelectedCardId = "";

    public void updateLastSearch(String query, List<SearchResult> results) {
        lastQuery = query == null ? "" : query;
        lastSearchResults = Collections.unmodifiableList(new ArrayList<>(results == null ? Collections.emptyList() : results));
        String compact = compact(query);
        for (SearchResult result : lastSearchResults) {
            if (result != null && result.card != null && !result.card.name.isEmpty()
                    && compact.contains(compact(result.card.name))) {
                setLastSelectedCardId(result.cardId);
                return;
            }
        }
        if (!lastSearchResults.isEmpty() && (!isFollowUp(query) || lastSelectedCardId.isEmpty())) {
            setLastSelectedCardId(lastSearchResults.get(0).cardId);
        }
    }

    public List<SearchResult> getLastSearchResults() {
        return lastSearchResults;
    }

    public void setLastSelectedCardId(String cardId) {
        lastSelectedCardId = cardId == null ? "" : cardId.trim();
    }

    public String getLastSelectedCardId() {
        return lastSelectedCardId;
    }

    public String getLastQuery() {
        return lastQuery;
    }

    public void clear() {
        lastQuery = "";
        lastSearchResults = Collections.emptyList();
        lastSelectedCardId = "";
    }

    public String resolveReferencedCardId(String query) {
        String normalized = compact(query);
        for (SearchResult result : lastSearchResults) {
            if (result != null && result.card != null && !result.card.name.isEmpty()
                    && normalized.contains(compact(result.card.name))) return result.cardId;
        }
        // A new phone suffix is a fresh search constraint, never provenance for the old focus.
        if (containsDigit(query)) return "";
        int index = -1;
        if (normalized.contains("첫번째") || normalized.contains("1번")) index = 0;
        else if (normalized.contains("두번째") || normalized.contains("2번")) index = 1;
        else if (normalized.contains("세번째") || normalized.contains("3번")) index = 2;

        if (index >= 0 && index < lastSearchResults.size()) {
            SearchResult result = lastSearchResults.get(index);
            if (result != null && result.cardId != null && !result.cardId.isEmpty()) return result.cardId;
        }
        return lastSelectedCardId == null ? "" : lastSelectedCardId;
    }

    /** Resolves only references. It never classifies intent or invents a person name. */
    public String resolveSearchQuery(String query) {
        String raw = query == null ? "" : query.trim();
        if (raw.isEmpty() || containsDigit(raw)) return raw;
        SearchResult focus = findResult(lastSelectedCardId);
        if (focus == null || focus.card == null || focus.card.name.isEmpty()) return raw;
        String compact = compact(raw);
        for (SearchResult result : lastSearchResults) {
            if (result != null && result.card != null && !result.card.name.isEmpty()
                    && compact.contains(compact(result.card.name))) return raw;
        }
        String resolved = raw;
        if (resolved.contains("그 회사")) resolved = resolved.replace("그 회사", focus.card.name + " 회사");
        if (resolved.contains("그 사람에게")) resolved = resolved.replace("그 사람에게", focus.card.name + "에게");
        if (resolved.contains("그에게")) resolved = resolved.replace("그에게", focus.card.name + "에게");
        for (String pronoun : PRONOUNS) {
            if (resolved.contains(pronoun)) resolved = resolved.replace(pronoun, focus.card.name);
        }
        if (!resolved.equals(raw)) return resolved;
        for (String attribute : ATTRIBUTE_PREFIXES) {
            if (compact.startsWith(attribute)) return focus.card.name + " " + raw;
        }
        return raw;
    }

    private SearchResult findResult(String cardId) {
        for (SearchResult result : lastSearchResults) {
            if (result != null && result.cardId.equals(cardId)) return result;
        }
        return null;
    }

    private boolean isFollowUp(String query) {
        if (containsDigit(query)) return false;
        String compact = compact(query);
        for (String pronoun : PRONOUNS) if (compact.contains(compact(pronoun))) return true;
        for (String attribute : ATTRIBUTE_PREFIXES) if (compact.startsWith(attribute)) return true;
        return false;
    }

    private boolean containsDigit(String value) {
        return value != null && value.matches(".*[0-9].*");
    }

    private String compact(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }
}
