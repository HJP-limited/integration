package com.hjp.searchlookup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.AfterClass;
import org.junit.Test;

/**
 * What a query that names both a region and a title is supposed to mean.
 *
 * When someone asks for "a designer in 세종특별자치시" they are stating two conditions about one
 * person, not two independent hints. If nobody works in that region, the honest answer is nobody —
 * not the designers who happen to work somewhere else. The current keyword path does not read it
 * that way: {@code LikeFallbackKeywordRetriever} keeps a card when <em>any</em> single token appears
 * anywhere in its searchable text, so the title alone satisfies the query and a real stranger comes
 * back as a candidate the agent can act on.
 *
 * <h2>This test is expected to fail</h2>
 *
 * It is a characterization of the defect, written before the fix. A failure here is the point; it is
 * not a measurement of search quality, and passing it later is not evidence of anything beyond this
 * contract. Nothing in this file touches production code, and no query is special-cased.
 *
 * <h2>Why a local fixture</h2>
 *
 * The defect was first seen on the reference's 1,000-card dataset, where "세종특별자치시 디자이너" is
 * a deliberately built hard negative: 0 cards in that region, 44 with that title, 0 with both. That
 * dataset is frozen evidence and is not read here. The nine cards below rebuild the same
 * relationships — a region that exists, a region that does not, a title common enough to be found on
 * its own, and a card whose company, memo and tags name a region it does not work in — so the
 * contract is stated in terms of the relationships rather than of one sentence.
 *
 * Every expectation is computed from the fixture by {@link #expected(String, String)}. No assertion
 * compares against a hardcoded card id or a hardcoded query string, so a fix that special-cases the
 * seed sentence does not satisfy this file.
 */
public class SearchFieldConstraintCharacterizationTest {

    private static final int TOP_K = 10;

    // ---- regions and titles, named so the contracts can be read ----------------------------------

    /** Regions that some card actually works in. */
    private static final String REGION_A = "부산광역시";
    private static final String REGION_B = "대전광역시";
    private static final String REGION_OTHER = "광주광역시";
    private static final String REGION_FALSE_FRIEND_HOME = "인천광역시";

    /** Regions no card works in. The first is the seed the reference dataset uses. */
    private static final String ABSENT_REGION_SEED = "세종특별자치시";
    private static final String ABSENT_REGION_2 = "울산광역시";
    private static final String ABSENT_REGION_3 = "춘천시";

    private static final String TITLE_COMMON = "디자이너";
    private static final String TITLE_SECOND = "변호사";
    private static final String TITLE_RARE = "회계사";

    /**
     * A roster whose region and title relationships are the whole point.
     *
     * <ul>
     *   <li>K001, K002 — region A and the common title on the same card;</li>
     *   <li>K003 — region B and the second title on the same card;</li>
     *   <li>K004 — the common title in a third region, so "same title, wrong region" is observable;</li>
     *   <li>K005 — the false friend: it works in {@value #REGION_FALSE_FRIEND_HOME}, while the absent
     *       region appears in its company, its memo and its tags;</li>
     *   <li>K006 — region B with the rare title, so region B has no card with the common title;</li>
     *   <li>K007 — the second title in a fourth region;</li>
     *   <li>K008, K009 — targets for the generic concept queries that must not be blocked.</li>
     * </ul>
     */
    private static List<BusinessCard> roster() {
        return Arrays.asList(
                card("K001", "김도윤", "부산디자인랩", TITLE_COMMON, "디자인팀", "디자인",
                        REGION_A, REGION_A + " 해운대구 센텀로 12", "", Collections.<String>emptyList()),
                card("K002", "박서진", "해운대크리에이티브", TITLE_COMMON, "디자인팀", "디자인",
                        REGION_A, REGION_A + " 수영구 광안로 8", "", Collections.<String>emptyList()),
                card("K003", "이하람", "한밭법률사무소", TITLE_SECOND, "송무팀", "법률",
                        REGION_B, REGION_B + " 서구 둔산로 45", "", Collections.<String>emptyList()),
                card("K004", "최윤슬", "빛고을스튜디오", TITLE_COMMON, "디자인팀", "디자인",
                        REGION_OTHER, REGION_OTHER + " 서구 상무대로 77", "", Collections.<String>emptyList()),
                // The false friend. The absent region is in the company name, the memo and a tag —
                // and in none of the fields that say where this person works.
                card("K005", "정한별", ABSENT_REGION_SEED + "개발원", TITLE_COMMON, "디자인팀", "디자인",
                        REGION_FALSE_FRIEND_HOME, REGION_FALSE_FRIEND_HOME + " 남동구 예술로 21",
                        ABSENT_REGION_SEED + " 본원 출장 예정", Arrays.asList(ABSENT_REGION_SEED, "협력사")),
                card("K006", "오세라", "한밭회계법인", TITLE_RARE, "감사팀", "회계",
                        REGION_B, REGION_B + " 유성구 대학로 99", "", Collections.<String>emptyList()),
                card("K007", "강태오", "섬돌법률", TITLE_SECOND, "송무팀", "법률",
                        "제주특별자치도", "제주특별자치도 제주시 중앙로 3", "", Collections.<String>emptyList()),
                card("K008", "문가온", "달빛에이아이", "AI 리서치 전문가", "AI연구팀", "AI",
                        "대구광역시", "대구광역시 수성구 동대구로 55", "", Collections.<String>emptyList()),
                card("K009", "신유하", "한강벤처스", "투자심사역", "투자팀", "금융",
                        "서울특별시", "서울특별시 강남구 테헤란로 4", "초기기업 투자 담당",
                        Collections.<String>emptyList()));
    }

    private static BusinessCard card(String id, String name, String company, String title,
            String department, String industry, String location, String address, String memo,
            List<String> tags) {
        return new BusinessCard(id, name, "", company, title, department, industry, location,
                "010-7000-" + id.substring(1), id.toLowerCase() + "@example.net", address, memo, tags);
    }

    // ---- the contract, stated over the fixture rather than over sentences ------------------------

    /** Where a person works. A company name, a memo and a tag are not that. */
    private static boolean worksIn(BusinessCard card, String region) {
        return card.location.contains(region) || card.address.contains(region);
    }

    private static boolean holdsTitle(BusinessCard card, String title) {
        return card.title.contains(title);
    }

    /**
     * The cards a region+title query is asking for: both conditions, on one card. Empty is a
     * perfectly good answer and is what an impossible combination must produce.
     */
    private Set<String> expected(String region, String title) {
        Set<String> ids = new LinkedHashSet<>();
        for (BusinessCard card : roster()) {
            if (worksIn(card, region) && holdsTitle(card, title)) ids.add(card.id);
        }
        return ids;
    }

    private Set<String> idsWorkingIn(String region) {
        Set<String> ids = new LinkedHashSet<>();
        for (BusinessCard card : roster()) if (worksIn(card, region)) ids.add(card.id);
        return ids;
    }

    private Set<String> idsHolding(String title) {
        Set<String> ids = new LinkedHashSet<>();
        for (BusinessCard card : roster()) if (holdsTitle(card, title)) ids.add(card.id);
        return ids;
    }

    // ---- the three entry points --------------------------------------------------------------

    private SearchLookupService keywordService() {
        return new SearchLookupService(roster(), new LocalEmbeddingEngine());
    }

    private SearchLookupService hybridService() {
        return new SearchLookupService(roster(), new DeterministicTestEmbeddingEngine());
    }

    private List<SearchResult> keywordPath(String query) {
        return keywordService().retrieve(query, TOP_K, RetrievalMode.KEYWORD_ONLY).results;
    }

    private List<SearchResult> indexedPath(String query, List<SearchResult> indexedCandidates) {
        return keywordService()
                .retrieve(query, TOP_K, RetrievalMode.KEYWORD_ONLY, indexedCandidates).results;
    }

    private RetrievalResponse hybridPath(String query) {
        return hybridService().retrieve(query, TOP_K, RetrievalMode.HYBRID);
    }

    /**
     * Candidates as an upstream index would hand them over: matched on one field, unverified.
     *
     * The Android build passes Room/FTS hits into the four-argument {@code retrieve}. Whatever that
     * index believes, the combination still has to hold on the card itself.
     */
    private List<SearchResult> candidates(String... ids) {
        List<SearchResult> out = new ArrayList<>();
        List<String> wanted = Arrays.asList(ids);
        int rank = 1;
        for (BusinessCard card : roster()) {
            if (!wanted.contains(card.id)) continue;
            out.add(new SearchResult(card, 1.0, ScoreBreakdown.keywordOnly(1.0),
                    Arrays.asList("keyword-index")).withRank(rank++));
        }
        return out;
    }

    // ---- 6.1 an absent region with a common title -----------------------------------------------

    @Test
    public void anAbsentRegionWithACommonTitleFindsNobody() {
        // Three independent combinations, so the contract is not one sentence's behaviour.
        String[][] combinations = {
            {ABSENT_REGION_SEED, TITLE_COMMON},
            {ABSENT_REGION_2, TITLE_SECOND},
            {ABSENT_REGION_3, TITLE_RARE},
        };
        List<String> violations = new ArrayList<>();
        for (String[] combination : combinations) {
            String region = combination[0];
            String title = combination[1];
            assertTrue("this combination only tests something if the title exists on its own",
                    !idsHolding(title).isEmpty());
            assertTrue("and if the region is nobody's workplace", idsWorkingIn(region).isEmpty());
            String query = region + " " + title;
            add(violations, check("absent_region:" + region, "keyword", query,
                    expected(region, title), keywordPath(query)));
        }
        assertNoViolations("a region nobody works in, named alongside a title that does exist, "
                + "must find nobody", violations);
    }

    @Test
    public void anAbsentRegionStillFindsNobodyHoweverThePersonPhrasesIt() {
        // Politeness, particles and word order change the sentence, not the question being asked.
        String[] phrasings = {
            ABSENT_REGION_SEED + "에서 일하는 " + TITLE_COMMON + " 찾아줘",
            TITLE_COMMON + " 중에 " + ABSENT_REGION_SEED + " 근무자 알려주세요",
            ABSENT_REGION_SEED + "에 있는 " + TITLE_COMMON + " 연락처 부탁드립니다",
            ABSENT_REGION_2 + "에서 근무하는 " + TITLE_SECOND + "님 찾아주세요",
            TITLE_RARE + " 중 " + ABSENT_REGION_3 + " 계신 분",
        };
        List<String> violations = new ArrayList<>();
        for (String query : phrasings) {
            String region = query.contains(ABSENT_REGION_SEED) ? ABSENT_REGION_SEED
                    : query.contains(ABSENT_REGION_2) ? ABSENT_REGION_2 : ABSENT_REGION_3;
            String title = query.contains(TITLE_COMMON) ? TITLE_COMMON
                    : query.contains(TITLE_SECOND) ? TITLE_SECOND : TITLE_RARE;
            add(violations, check("absent_region_paraphrase", "keyword", query,
                    expected(region, title), keywordPath(query)));
        }
        assertNoViolations("phrasing does not change what was asked", violations);
    }

    // ---- 6.2 both conditions exist, never together ----------------------------------------------

    @Test
    public void twoConditionsThatNeverShareACardFindNobody() {
        // Region B has people and the common title has people. No one card has both.
        assertFalse("the fixture must have someone in " + REGION_B, idsWorkingIn(REGION_B).isEmpty());
        assertFalse("and someone with the title " + TITLE_COMMON, idsHolding(TITLE_COMMON).isEmpty());

        List<String> violations = new ArrayList<>();
        add(violations, check("split_conditions:" + REGION_B, "keyword", REGION_B + " " + TITLE_COMMON,
                expected(REGION_B, TITLE_COMMON), keywordPath(REGION_B + " " + TITLE_COMMON)));
        add(violations, check("split_conditions:" + REGION_OTHER, "keyword",
                REGION_OTHER + " " + TITLE_SECOND, expected(REGION_OTHER, TITLE_SECOND),
                keywordPath(REGION_OTHER + " " + TITLE_SECOND)));
        assertNoViolations("two conditions that exist separately, but never on one card, find nobody",
                violations);
    }

    @Test
    public void aHalfMatchIsNotOfferedAsSomeoneToChooseBetween() {
        // The dangerous shape is not the empty list — it is a list of plausible strangers that the
        // agent then asks the user to pick from.
        String query = REGION_B + " " + TITLE_COMMON;
        List<String> returned = ids(keywordPath(query));
        List<String> regionOnly = new ArrayList<>(idsWorkingIn(REGION_B));
        List<String> titleOnly = new ArrayList<>(idsHolding(TITLE_COMMON));

        List<String> leaked = new ArrayList<>();
        for (String id : returned) {
            if (regionOnly.contains(id) || titleOnly.contains(id)) leaked.add(id);
        }
        observe("half_match_not_a_candidate", "keyword", query, expected(REGION_B, TITLE_COMMON),
                keywordPath(query), true, !leaked.isEmpty(),
                leaked.isEmpty() ? "" : "partial matches offered as candidates: " + leaked);
        assertEquals("a card matching only the region, or only the title, must not come back as a "
                        + "candidate for \"" + query + "\": " + describe(keywordPath(query)),
                Collections.<String>emptyList(), leaked);
    }

    // ---- 6.3 the combination that does hold ------------------------------------------------------

    @Test
    public void theRightPeopleAreStillFoundWhenTheCombinationHolds() {
        // Positive control. If this ever fails, the contract has been "satisfied" by returning
        // nothing to everyone, which is not a fix.
        List<String> violations = new ArrayList<>();
        for (String query : wordOrderVariants()) {
            List<SearchResult> results = keywordPath(query);
            List<String> returned = ids(results);
            Set<String> wanted = expected(REGION_A, TITLE_COMMON);
            boolean missing = !returned.containsAll(wanted);
            observe("positive_reachability", "keyword", query, wanted, results, false, missing,
                    missing ? "the correct cards were not returned" : "");
            if (missing) {
                violations.add("\"" + query + "\" did not reach " + wanted + "; returned "
                        + describe(results));
            }
        }
        assertNoViolations("the people who really do hold that title in that region must be found",
                violations);
    }

    @Test
    public void theSameTitleInAnotherRegionIsNotReturned() {
        List<String> violations = new ArrayList<>();
        for (String query : wordOrderVariants()) {
            add(violations, check("positive_exclusivity", "keyword", query,
                    expected(REGION_A, TITLE_COMMON), keywordPath(query)));
        }
        assertNoViolations("someone with the same title in a different region is not an answer",
                violations);
    }

    /** The same question, asked three ways people actually ask it. */
    private String[] wordOrderVariants() {
        return new String[] {
            REGION_A + " " + TITLE_COMMON,
            TITLE_COMMON + " 중 " + REGION_A + " 근무자",
            REGION_A + "에서 일하는 " + TITLE_COMMON,
        };
    }

    @Test
    public void asecondValidCombinationBehavesTheSameWay() {
        String query = REGION_B + " " + TITLE_SECOND;
        assertFalse("the fixture must contain this combination", expected(REGION_B, TITLE_SECOND).isEmpty());
        assertNoViolations("a second valid combination is answered exactly as precisely",
                collect(check("positive_second_combination", "keyword", query,
                        expected(REGION_B, TITLE_SECOND), keywordPath(query))));
    }

    private static List<String> collect(String violation) {
        List<String> violations = new ArrayList<>();
        add(violations, violation);
        return violations;
    }

    // ---- 6.4 a region string in the wrong field ---------------------------------------------------

    @Test
    public void aRegionNamedInACompanyMemoOrTagIsNotWhereSomebodyWorks() {
        BusinessCard falseFriend = null;
        for (BusinessCard candidate : roster()) {
            if (candidate.id.equals("K005")) falseFriend = candidate;
        }
        assertTrue("the fixture must carry the false friend", falseFriend != null);
        assertTrue("its company must name the absent region",
                falseFriend.company.contains(ABSENT_REGION_SEED));
        assertTrue("so must its memo", falseFriend.memo.contains(ABSENT_REGION_SEED));
        assertTrue("and one of its tags", falseFriend.tags.contains(ABSENT_REGION_SEED));
        assertFalse("but it must not work there",
                worksIn(falseFriend, ABSENT_REGION_SEED));

        String query = ABSENT_REGION_SEED + " " + TITLE_COMMON;
        List<String> returned = ids(keywordPath(query));
        observe("false_friend_field", "keyword", query, expected(ABSENT_REGION_SEED, TITLE_COMMON),
                keywordPath(query), true, returned.contains(falseFriend.id),
                returned.contains(falseFriend.id)
                        ? "a card whose only link to the region is its company, memo and tags was returned"
                        : "");
        assertFalse("\"" + query + "\" must not reach " + falseFriend.id + ", whose company, memo and "
                        + "tags name " + ABSENT_REGION_SEED + " but who works in "
                        + falseFriend.location + ": " + describe(keywordPath(query)),
                returned.contains(falseFriend.id));
    }

    @Test
    public void theFalseFriendIsStillReachableByTheRegionItActuallyWorksIn() {
        // The rule is about which field carries the meaning, not about hiding the card.
        String query = REGION_FALSE_FRIEND_HOME + " " + TITLE_COMMON;
        Set<String> wanted = expected(REGION_FALSE_FRIEND_HOME, TITLE_COMMON);
        List<String> returned = ids(keywordPath(query));
        observe("false_friend_real_region", "keyword", query, wanted, keywordPath(query), false,
                !returned.containsAll(wanted), returned.containsAll(wanted) ? "" : "not reachable");
        assertTrue("the false friend must still be findable where it really works: "
                        + describe(keywordPath(query)), returned.containsAll(wanted));
    }

    // ---- 6.5 a constraint must not swallow ordinary questions -------------------------------------

    @Test
    public void aGeneralConceptQuestionIsNotTurnedIntoAnEmptyAnswer() {
        // No region, no exact title. Whatever field constraint arrives later, it must not fire here.
        // This says nothing about how good the ranking is — only that the question is still asked.
        List<String> violations = new ArrayList<>();
        for (String query : new String[] {"AI 전문가", "투자 담당자", "디자인 관련 담당자"}) {
            List<SearchResult> results = keywordPath(query);
            observe("generic_not_blocked", "keyword", query, null, results, false,
                    results.isEmpty(), results.isEmpty() ? "a generic concept query returned nothing" : "");
            if (results.isEmpty()) {
                violations.add("\"" + query + "\" names neither a region nor an exact title, so a "
                        + "field constraint must not empty it");
            }
        }
        assertNoViolations("a general concept question must still be asked", violations);
    }

    // ---- 7.2 candidates handed in by an index -----------------------------------------------------

    @Test
    public void candidatesFromTheIndexAreStillCheckedAgainstBothConditions() {
        // Deliberately hostile input: one card matches only the region, one only the title, and one
        // carries the region string in its company, memo and tags.
        String query = REGION_B + " " + TITLE_COMMON;
        List<SearchResult> indexed = candidates("K003", "K001", "K005");
        assertFalse("the fixture must actually hand something over", indexed.isEmpty());
        assertNoViolations("candidates arriving from an index are still checked on the card itself",
                collect(check("indexed_candidates_unverified", "indexed", query,
                        expected(REGION_B, TITLE_COMMON), indexedPath(query, indexed))));
    }

    @Test
    public void anIndexCannotMakeAnImpossibleCombinationPossible() {
        String query = ABSENT_REGION_SEED + " " + TITLE_COMMON;
        assertNoViolations("no upstream candidate list makes an impossible combination possible",
                collect(check("indexed_candidates_absent_region", "indexed", query,
                        expected(ABSENT_REGION_SEED, TITLE_COMMON),
                        indexedPath(query, candidates("K005", "K001", "K002")))));
    }

    // ---- 7.3 the hybrid path ----------------------------------------------------------------------

    @Test
    public void theHybridPathReallyRunsInThisFixture() {
        // A guard on the other two hybrid tests: if the engine quietly fell back to keywords, they
        // would be re-testing the keyword path and proving nothing about fusion.
        RetrievalResponse response = hybridPath(REGION_A + " " + TITLE_COMMON);
        assertEquals("the hybrid path must not have fallen back; fallbackReason="
                        + response.fallbackReason, RetrievalMode.HYBRID, response.mode);
        assertTrue("and the semantic axis must have produced candidates to fuse",
                response.semanticResultCount > 0);
    }

    @Test
    public void fusionDoesNotReviveAnImpossibleCombination() {
        String query = ABSENT_REGION_SEED + " " + TITLE_COMMON;
        RetrievalResponse response = hybridPath(query);
        assertEquals("this only tests fusion if fusion actually ran; fallbackReason="
                        + response.fallbackReason, RetrievalMode.HYBRID, response.mode);
        assertNoViolations("rank fusion must not revive a combination no card satisfies",
                collect(check("hybrid_absent_region", "hybrid", query,
                        expected(ABSENT_REGION_SEED, TITLE_COMMON), response.results)));
    }

    @Test
    public void theHybridPathStillFindsAValidCombination() {
        String query = REGION_A + " " + TITLE_COMMON;
        RetrievalResponse response = hybridPath(query);
        Set<String> wanted = expected(REGION_A, TITLE_COMMON);
        List<String> returned = ids(response.results);
        observe("hybrid_positive", "hybrid", query, wanted, response.results, false,
                !returned.containsAll(wanted), returned.containsAll(wanted) ? "" : "not reachable");
        assertTrue("the hybrid path must still reach the right people: " + describe(response.results),
                returned.containsAll(wanted));
    }

    // ---- the fixture itself -----------------------------------------------------------------------

    @Test
    public void theFixtureEncodesTheRelationshipsTheseContractsDependOn() {
        // If this ever fails, every other assertion in the file has stopped meaning what it says.
        assertEquals("the absent seed region must be nobody's workplace",
                Collections.<String>emptySet(), idsWorkingIn(ABSENT_REGION_SEED));
        assertEquals("and so must the other two absent regions",
                Collections.<String>emptySet(), idsWorkingIn(ABSENT_REGION_2));
        assertEquals("", Collections.<String>emptySet(), idsWorkingIn(ABSENT_REGION_3));
        assertTrue("the common title must be common enough to be found on its own",
                idsHolding(TITLE_COMMON).size() >= 3);
        assertEquals("region A with the common title is the positive control",
                new LinkedHashSet<>(Arrays.asList("K001", "K002")), expected(REGION_A, TITLE_COMMON));
        assertFalse("region B must have people", idsWorkingIn(REGION_B).isEmpty());
        assertEquals("but none of them with the common title",
                Collections.<String>emptySet(), expected(REGION_B, TITLE_COMMON));
        assertEquals("nine distinct ids, so ordering is observable", 9, roster().size());
    }

    // ---- observation, message building and the report --------------------------------------------

    private static final List<String> OBSERVATIONS = new ArrayList<>();

    /**
     * Records the case and reports whether it violated the contract, without aborting.
     *
     * Returning instead of asserting is deliberate. A method that asserts inside a loop stops at the
     * first bad case, so the remaining combinations are never run and never recorded — and a
     * characterization whose whole claim is "this is general, not one sentence" cannot afford to
     * leave the other combinations unobserved. Callers collect the violations and assert once, with
     * every one of them in the message.
     */
    private String check(String caseName, String path, String query, Set<String> wanted,
            List<SearchResult> results) {
        Set<String> actual = new LinkedHashSet<>(ids(results));
        boolean failed = !wanted.equals(actual);
        observe(caseName, path, query, wanted, results, wanted.isEmpty(), failed,
                failed ? "expected " + wanted + " but the search returned " + actual : "");
        if (!failed) return null;
        return "\"" + query + "\" [" + path + "] expected " + wanted + " but returned "
                + describe(results);
    }

    private static void add(List<String> violations, String violation) {
        if (violation != null) violations.add(violation);
    }

    private static void assertNoViolations(String contract, List<String> violations) {
        assertEquals(contract + " — " + violations.size() + " case(s) violated it:\n  "
                        + String.join("\n  ", violations),
                Collections.<String>emptyList(), violations);
    }

    private void observe(String caseName, String path, String query, Set<String> wanted,
            List<SearchResult> results, boolean expectAbstention, boolean failed, String reason) {
        StringBuilder json = new StringBuilder();
        json.append("  {\n");
        json.append("    \"case_name\": ").append(quote(caseName)).append(",\n");
        json.append("    \"query\": ").append(quote(query)).append(",\n");
        json.append("    \"path\": ").append(quote(path)).append(",\n");
        json.append("    \"expected_card_ids\": ")
                .append(wanted == null ? "null" : array(new ArrayList<>(wanted))).append(",\n");
        json.append("    \"actual_card_ids\": ").append(array(ids(results))).append(",\n");
        json.append("    \"actual_cards\": [");
        for (int i = 0; i < results.size(); i++) {
            BusinessCard card = results.get(i).card;
            if (i > 0) json.append(",");
            json.append("\n      {\"card_id\": ").append(quote(card.id));
            json.append(", \"location\": ").append(quote(card.location));
            json.append(", \"address\": ").append(quote(card.address));
            json.append(", \"title\": ").append(quote(card.title));
            json.append(", \"company\": ").append(quote(card.company)).append("}");
        }
        json.append(results.isEmpty() ? "]," : "\n    ],").append("\n");
        json.append("    \"expected_abstention\": ").append(expectAbstention).append(",\n");
        json.append("    \"assertion_failed\": ").append(failed).append(",\n");
        json.append("    \"failure_reason\": ").append(quote(reason)).append("\n");
        json.append("  }");
        OBSERVATIONS.add(json.toString());
    }

    /**
     * Writes what was actually observed, whatever the assertions did.
     *
     * {@code @AfterClass} runs after failures too, which is the only reason this file exists: a red
     * run has to leave behind the returned card ids, not just a stack trace. It writes into the
     * module's build directory — disposable, and never the evidence tree.
     */
    @AfterClass
    public static void writeObservations() {
        try {
            File directory = new File("build/search-field-constraints");
            if (!directory.isDirectory() && !directory.mkdirs()) return;
            StringBuilder out = new StringBuilder();
            out.append("{\n  \"what_this_is\": ").append(quote(
                    "observations recorded by SearchFieldConstraintCharacterizationTest against the "
                            + "current production search path. A failed assertion here is the "
                            + "characterized defect, not a search quality measurement."))
                    .append(",\n  \"cases\": [\n");
            for (int i = 0; i < OBSERVATIONS.size(); i++) {
                out.append(OBSERVATIONS.get(i)).append(i == OBSERVATIONS.size() - 1 ? "\n" : ",\n");
            }
            out.append("  ]\n}\n");
            Files.write(new File(directory, "observed_results.json").toPath(),
                    out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Never let the bookkeeping mask the contract failures this class exists to report.
        }
    }

    private static List<String> ids(List<SearchResult> results) {
        List<String> out = new ArrayList<>();
        for (SearchResult result : results) out.add(result.card.id);
        return out;
    }

    /** Card ids alone do not say why a result is wrong; the region and the title do. */
    private static String describe(List<SearchResult> results) {
        if (results.isEmpty()) return "[]";
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < results.size(); i++) {
            BusinessCard card = results.get(i).card;
            if (i > 0) text.append(", ");
            text.append(card.id).append("(location=").append(card.location)
                    .append(", title=").append(card.title)
                    .append(", company=").append(card.company).append(")");
        }
        return text.append("]").toString();
    }

    private static String array(List<String> values) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) text.append(", ");
            text.append(quote(values.get(i)));
        }
        return text.append("]").toString();
    }

    private static String quote(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    /**
     * A stand-in for the on-device model, so the hybrid path executes at all.
     *
     * It hashes characters into a small dense vector: same text in, same vector out, and texts
     * sharing characters land near each other. That is enough to exercise fusion and nothing more.
     * These vectors are not EmbeddingGemma and no number produced through them says anything about
     * semantic retrieval quality.
     */
    private static final class DeterministicTestEmbeddingEngine implements EmbeddingEngine {
        private static final int DIMENSIONS = 16;

        @Override public float[] embed(String input) {
            float[] vector = new float[DIMENSIONS];
            String text = input == null ? "" : input;
            for (int i = 0; i < text.length(); i++) {
                vector[Math.abs(text.charAt(i) % DIMENSIONS)] += 1.0f;
            }
            // A floor keeps the vector non-degenerate for very short inputs.
            for (int i = 0; i < DIMENSIONS; i++) vector[i] += 0.1f;
            return vector;
        }

        @Override public String name() { return "deterministic-test-engine"; }

        @Override public boolean isModelBacked() { return true; }
    }
}
