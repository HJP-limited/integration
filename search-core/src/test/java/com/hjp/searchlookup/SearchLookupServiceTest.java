package com.hjp.searchlookup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class SearchLookupServiceTest {
    @Test
    public void localEmbeddingIsNotUsedAsProductionSemanticAndFallsBackToKeyword() {
        BusinessCard ai = new BusinessCard("C002", "오성령", "Sungryung Oh", "코어AI",
                "AI 엔지니어", "플랫폼팀", "it", "판교", "010", "ai@example.com",
                "성남", "로컬 임베딩 검색", Arrays.asList("AI", "개발자"));
        BusinessCard finance = new BusinessCard("C001", "김지원", "Jiwon Kim", "비전글로벌",
                "대표이사", "전략팀", "finance", "서울", "011", "ceo@example.com",
                "서울", "투자 파트너십", Arrays.asList("투자"));
        SearchLookupService service = new SearchLookupService(Arrays.asList(ai, finance), new LocalEmbeddingEngine());

        RetrievalResponse response = service.retrieve("판교 AI 개발자", 5, RetrievalMode.HYBRID);
        List<SearchResult> results = response.results;

        assertFalse(results.isEmpty());
        assertEquals("C002", results.get(0).card.id);
        assertEquals("ai@example.com", service.getCard("C002").email);
        assertEquals(RetrievalMode.KEYWORD_ONLY, response.mode);
        assertTrue(response.fallbackUsed);
        assertTrue(response.cardIds.contains("C002"));
        assertTrue(response.ragContext.isEmpty());
    }

    @Test
    public void unknownKoreanNameDoesNotReturnSemanticFalsePositive() {
        BusinessCard existing = new BusinessCard("C001", "김지원", "Jiwon Kim", "비전글로벌",
                "대표이사", "전략팀", "finance", "서울", "011", "ceo@example.com",
                "서울", "투자 파트너십", Arrays.asList("투자"));
        SearchLookupService service = new SearchLookupService(
                Arrays.asList(existing), new LocalEmbeddingEngine());

        assertTrue(service.search("김민수", 5).isEmpty());
    }

    @Test
    public void keywordSearchCoversSupportedFieldsAndEnglishPartialName() {
        SearchLookupService service = serviceWithCards(new LocalEmbeddingEngine());

        assertTop(service, "지원", "C001");
        assertTop(service, "Jiwon", "C001");
        assertTop(service, "비전글로벌", "C001");
        assertTop(service, "대표이사 전략팀", "C001");
        assertTop(service, "금융 서울", "C001");
        assertTop(service, "투자 파트너십", "C001");
        assertTop(service, "AI 개발자", "C002");
    }

    @Test
    public void hybridUsesRrfAndPreservesRankProvenance() {
        SearchLookupService service = serviceWithCards(new DeterministicModelEngine());
        RetrievalResponse response = service.retrieve("인공지능 전문가", 5, RetrievalMode.HYBRID);

        assertEquals(RetrievalMode.HYBRID, response.mode);
        assertFalse(response.fallbackUsed);
        assertEquals("C002", response.results.get(0).cardId);
        assertTrue(response.results.get(0).retrievalSources.contains("semantic"));
        assertTrue(response.results.get(0).rankFusionScore > 0.0);
        assertTrue(response.ragContext.contains("[cardId=C002]"));
        assertFalse(response.ragContext.contains("ai@example.com"));
        assertFalse(response.ragContext.contains("010-2222"));
        assertFalse(response.ragContext.contains("성남시"));
    }

    @Test
    public void duplicateNamesRemainDistinctAndTopKIsEnforced() {
        List<BusinessCard> cards = new ArrayList<>(fixtureCards());
        cards.add(new BusinessCard("C004", "김지원", "Jiwon Kim", "다른회사",
                "팀장", "영업", "sales", "부산", "", "", "", "", Collections.emptyList()));
        SearchLookupService service = new SearchLookupService(cards, new LocalEmbeddingEngine());

        List<SearchResult> results = service.search("김지원", 10);
        assertEquals(2, results.size());
        assertFalse(results.get(0).cardId.equals(results.get(1).cardId));
        assertEquals(1, service.search("김지원", 1).size());
    }

    @Test
    public void normalizedPhoneIsSearchableButEmailRemainsOutsideRagKeywordFields() {
        SearchLookupService service = serviceWithCards(new LocalEmbeddingEngine());
        QueryAnalysis analysis = service.analyzeQuery("test+vip@example.com / 010-1111-2222");

        assertTrue(analysis.normalizedQuery.contains("test+vip@example.com"));
        assertTrue(service.search("test+vip@example.com", 5).isEmpty());
        assertEquals("C001", service.search("010-1111-2222", 5).get(0).cardId);
    }

    @Test
    public void missingAssetsInferenceFailureAndDimensionMismatchSafelyUseKeyword() {
        List<EmbeddingEngine> engines = Arrays.asList(
                new OnDeviceEmbeddingEngine((EmbeddingEngine) null, new LocalEmbeddingEngine()),
                OnDeviceEmbeddingEngine.production(new ThrowingModelEngine()),
                OnDeviceEmbeddingEngine.production(new WrongDimensionModelEngine())
        );
        for (EmbeddingEngine engine : engines) {
            SearchLookupService service = serviceWithCards(engine);
            RetrievalResponse response = service.retrieve("김지원", 5, RetrievalMode.HYBRID);
            assertEquals(RetrievalMode.KEYWORD_ONLY, response.mode);
            assertTrue(response.fallbackUsed);
            assertEquals("C001", response.results.get(0).cardId);
            assertFalse(response.fallbackReason.isEmpty());
        }
    }

    @Test
    public void stableCardIdRejectsUnknownLookup() {
        SearchLookupService service = serviceWithCards(new LocalEmbeddingEngine());
        assertEquals("김지원", service.getCard(" C001 ").name);
        assertNull(service.getCard("not-a-room-id"));
    }

    @Test
    public void rrfCombinesRanksInsteadOfRawScores() {
        BusinessCard a = fixtureCards().get(0);
        BusinessCard b = fixtureCards().get(1);
        List<SearchResult> keyword = Arrays.asList(
                new SearchResult(a, 1000.0).withRank(1),
                new SearchResult(b, 999.0).withRank(2));
        List<SearchResult> semantic = Arrays.asList(
                new SearchResult(b, 0.99).withRank(1),
                new SearchResult(a, 0.01).withRank(2));

        List<SearchResult> fused = new ReciprocalRankFusion().fuse(keyword, semantic, 5);
        assertEquals(2, fused.size());
        assertEquals(fused.get(0).rankFusionScore, fused.get(1).rankFusionScore, 0.000001);
    }

    private static SearchLookupService serviceWithCards(EmbeddingEngine engine) {
        return new SearchLookupService(fixtureCards(), engine);
    }

    private static List<BusinessCard> fixtureCards() {
        return Arrays.asList(
                new BusinessCard("C001", "김지원", "Jiwon Kim", "비전글로벌",
                        "대표이사", "전략팀", "금융", "서울", "010-1111-2222",
                        "test+vip@example.com", "서울시", "투자 파트너십",
                        Arrays.asList("투자", "VIP")),
                new BusinessCard("C002", "오성령", "Sungryung Oh", "코어AI",
                        "AI 엔지니어", "플랫폼팀", "IT", "판교", "010-2222",
                        "ai@example.com", "성남시", "온디바이스 머신러닝 검색",
                        Arrays.asList("AI", "개발자")),
                new BusinessCard("C003", "박하늘", "Haneul Park", "네오팩토리",
                        "품질팀장", "제조혁신팀", "제조", "부산", "", "",
                        "부산시", "스마트공장 품질 검사", Arrays.asList("공장", "품질"))
        );
    }

    private static void assertTop(SearchLookupService service, String query, String cardId) {
        List<SearchResult> results = service.search(query, 5);
        assertFalse("No result for " + query, results.isEmpty());
        assertEquals(cardId, results.get(0).cardId);
    }

    private static class DeterministicModelEngine implements EmbeddingEngine {
        @Override public float[] embed(String input) { return embedQuery(input); }
        @Override public float[] embedQuery(String input) { return vector(input); }
        @Override public float[] embedDocument(String input) { return vector(input); }
        @Override public String name() { return "deterministic-embeddinggemma"; }
        @Override public boolean isModelBacked() { return true; }
        private float[] vector(String raw) {
            String text = raw == null ? "" : raw.toLowerCase();
            float[] out = new float[768];
            if (text.contains("ai") || text.contains("인공지능") || text.contains("머신러닝")) out[0] = 1f;
            if (text.contains("투자") || text.contains("금융")) out[1] = 1f;
            if (text.contains("제조") || text.contains("공장")) out[2] = 1f;
            if (out[0] == 0f && out[1] == 0f && out[2] == 0f) out[767] = 1f;
            return out;
        }
    }

    private static final class ThrowingModelEngine extends DeterministicModelEngine {
        @Override public float[] embedDocument(String input) {
            throw new IllegalStateException("delegate inference failed");
        }
    }

    private static final class WrongDimensionModelEngine extends DeterministicModelEngine {
        @Override public float[] embedDocument(String input) {
            return new float[] {1f, 0f, 0f, 0f};
        }
    }
}
