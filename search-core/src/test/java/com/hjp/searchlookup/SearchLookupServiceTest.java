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
    /**
     * A family name is a question about who somebody is called, so the answer is every card with
     * that name and nothing else. Before this, "오씨 성을 가진 사람" left the keyword side with no
     * usable token at all (오씨 is two syllables, 오 is one) and the semantic axis answered with
     * whatever sounded close — 옥지수, and a 강성민 whose employer happened to be (주)오션통신.
     */
    @Test
    public void surnameQueryKeepsOnlyThatFamilyNameAndIgnoresLookalikes() {
        List<BusinessCard> cards = new ArrayList<>();
        cards.add(card("A1", "오수아", "주식회사 백제"));
        cards.add(card("A2", "오슬기", "주식회사 네트웍스상사"));
        cards.add(card("A3", "오하늘", "유한회사 한솔건설"));
        cards.add(card("B1", "옥지수", "(주) 프라임푸드"));
        cards.add(card("B2", "옥우진", "유한회사 충청스튜디오"));
        cards.add(card("C1", "강성민", "(주)오션통신"));
        SearchLookupService service = new SearchLookupService(cards, new DeterministicModelEngine());

        for (String query : Arrays.asList("오씨 성을 가진 사람 찾아줘", "오씨 성 가진 사람 찾아줘",
                "오씨성 가진 사람 찾아줘", "성이 오씨인 사람",
                // What the agent actually hands this layer once the router has shortened it.
                "오씨")) {
            RetrievalResponse result = service.retrieve(query, 5, RetrievalMode.HYBRID);
            assertEquals(query, 3, result.results.size());
            assertTrue(query, result.cardIds.containsAll(Arrays.asList("A1", "A2", "A3")));
        }
    }

    /**
     * "X씨" without the word 성 is how people are addressed, not a question about family names.
     * 마이클 첸씨 and 안나 리씨 have the same shape as 오씨 and must still reach their card.
     */
    @Test
    public void honorificNameIsNotReadAsAFamilyNameQuestion() {
        List<BusinessCard> cards = new ArrayList<>();
        cards.add(card("D008", "마이클 첸", "주식회사 백제"));
        cards.add(card("A1", "오수아", "주식회사 백제"));
        SearchLookupService service = new SearchLookupService(cards, new DeterministicModelEngine());

        RetrievalResponse result = service.retrieve("마이클 첸씨 찾아줘", 5, RetrievalMode.HYBRID);
        assertTrue(result.results.toString(), result.cardIds.contains("D008"));
    }

    /**
     * A family name filters; a job title still only orders. Asking for 변호사 among the 오씨 must
     * not start hiding the 고문변호사 sitting right there — that looseness is deliberate, and the
     * surname constraint has no business changing it.
     */
    @Test
    public void surnameFiltersWithoutMakingTitleMatchingStrict() {
        List<BusinessCard> cards = new ArrayList<>();
        cards.add(new BusinessCard("O1", "오수아", "", "주식회사 백제", "변호사", "법무팀", "법률",
                "서울", "", "", "", "", Collections.<String>emptyList()));
        cards.add(new BusinessCard("O2", "오슬기", "", "주식회사 백제", "고문변호사", "법무팀", "법률",
                "서울", "", "", "", "", Collections.<String>emptyList()));
        cards.add(new BusinessCard("K1", "김지수", "", "주식회사 백제", "변호사", "법무팀", "법률",
                "서울", "", "", "", "", Collections.<String>emptyList()));
        SearchLookupService service = new SearchLookupService(cards, new DeterministicModelEngine());

        RetrievalResponse result = service.retrieve("오씨 성 가진 변호사", 5, RetrievalMode.HYBRID);
        assertTrue(result.cardIds.toString(), result.cardIds.containsAll(Arrays.asList("O1", "O2")));
        assertFalse(result.cardIds.toString(), result.cardIds.contains("K1"));
    }

    /** A surname nobody carries is an answer of nobody, not a ranked list of near-spellings. */
    @Test
    public void surnameAbsentFromTheRollAbstainsInsteadOfOfferingLookalikes() {
        List<BusinessCard> cards = new ArrayList<>();
        cards.add(card("A1", "오수아", "주식회사 백제"));
        cards.add(card("B1", "옥지수", "(주) 프라임푸드"));
        SearchLookupService service = new SearchLookupService(cards, new DeterministicModelEngine());

        RetrievalResponse result = service.retrieve("남궁씨 성을 가진 사람", 5, RetrievalMode.HYBRID);
        assertTrue(result.results.toString(), result.results.isEmpty());
    }

    private static BusinessCard card(String id, String name, String company) {
        return new BusinessCard(id, name, "", company, "개발자", "개발팀", "it", "서울",
                "", "", "", "", Collections.<String>emptyList());
    }

    @Test
    public void exactStoredNameKeepsHomonymsButExcludesSemanticNeighbours() {
        List<BusinessCard> cards = new ArrayList<>();
        String[] names = {"손다은", "손다은", "전다은", "손다인"};
        for (int i = 0; i < names.length; i++) {
            cards.add(new BusinessCard("N" + i, names[i], "", "회사", "개발자", "팀", "it", "서울",
                    "", "", "", "", Collections.emptyList()));
        }
        SearchLookupService service = new SearchLookupService(cards, new DeterministicModelEngine());
        RetrievalResponse result = service.retrieve("손다은", 5, RetrievalMode.HYBRID);
        assertEquals(2, result.results.size());
        assertTrue(result.cardIds.containsAll(Arrays.asList("N0", "N1")));
        assertFalse(result.fallbackUsed);
    }
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
    public void phoneAndEmailAreBothSearchableByKeyword() {
        // 이메일은 예전에 일부러 빠져 있었고 이 시험이 그걸 고정하고 있었다. 그런데 실기기의
        // Room 인덱스는 이메일을 넣고 있어서 두 경로가 서로 다른 것을 뒤졌다 — 노트북에서 잰
        // 값이 폰을 대변해야 한다는 이 프로젝트의 제약을 깨는 어긋남이다.
        //
        // 명함에 적힌 것은 전부 찾을 수 있어야 한다. 사람은 기억나는 조각으로 찾고, 그게 어느
        // 칸이었는지는 기억하지 못한다.
        SearchLookupService service = serviceWithCards(new LocalEmbeddingEngine());
        QueryAnalysis analysis = service.analyzeQuery("test+vip@example.com / 010-1111-2222");

        assertTrue(analysis.normalizedQuery.contains("test+vip@example.com"));
        assertEquals("C001", service.search("test+vip@example.com", 5).get(0).cardId);
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
