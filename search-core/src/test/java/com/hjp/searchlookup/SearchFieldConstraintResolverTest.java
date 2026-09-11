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
import java.util.List;
import org.junit.AfterClass;
import org.junit.Test;

/**
 * The rules behind the field constraints, tested as rules rather than through whole searches.
 *
 * {@link SearchFieldConstraintCharacterizationTest} pins the behaviour a user would notice. This
 * file pins the reasoning underneath it: which words are read as places, which as jobs, when the
 * answer is already known to be nobody, and what happens when the address book changes. Those are
 * the parts most likely to be quietly broken by a later edit, because nothing about them is visible
 * from a search result alone.
 */
public class SearchFieldConstraintResolverTest {

    private static BusinessCard card(String id, String name, String company, String title,
            String department, String industry, String location, String address, String memo,
            List<String> tags) {
        return new BusinessCard(id, name, "", company, title, department, industry, location,
                "010-8000-" + id.substring(1), id.toLowerCase() + "@example.net", address, memo, tags);
    }

    /** A small address book with the ambiguities that make this hard. */
    private static List<BusinessCard> roster() {
        return Arrays.asList(
                card("R001", "한도윤", "누리디자인", "디자이너", "디자인팀", "디자인",
                        "부산광역시", "부산광역시 해운대구 센텀로 1", "", Collections.<String>emptyList()),
                card("R002", "서가람", "밝은법률", "변호사", "송무팀", "법률",
                        "대전광역시", "대전광역시 서구 둔산로 2", "", Collections.<String>emptyList()),
                card("R003", "노윤재", "큰숲파트너스", "대표이사", "경영지원팀", "컨설팅",
                        "서울특별시", "서울특별시 강남구 테헤란로 3", "", Collections.<String>emptyList()),
                card("R004", "임세아", "판교로보틱스", "로봇엔지니어", "연구팀", "제조",
                        "판교", "경기도 성남시 분당구 판교역로 4", "", Collections.<String>emptyList()),
                // The region this card names is not the region it works in.
                card("R005", "구하람", "세종특별자치시개발원", "디자이너", "디자인팀", "디자인",
                        "인천광역시", "인천광역시 남동구 예술로 5", "세종특별자치시 본원 협업",
                        Arrays.asList("세종특별자치시")));
    }

    private SearchLookupService service() {
        return new SearchLookupService(roster(), new LocalEmbeddingEngine());
    }

    private List<String> ids(List<SearchResult> results) {
        List<String> out = new ArrayList<>();
        for (SearchResult result : results) out.add(result.card.id);
        return out;
    }

    private List<String> lookup(SearchLookupService service, String query) {
        return ids(service.retrieve(query, 10, RetrievalMode.KEYWORD_ONLY).results);
    }

    // ---- what counts as naming a place ----------------------------------------------------------

    @Test
    public void aPlaceIsRecognisedByItsShapeNotByAListOfKnownPlaces() {
        // The place has to be understood as a place before the search can say nobody works there,
        // and it is absent from the data by definition — so no list built from the data would hold
        // it. 특별자치시 is not a suffix ordinary words carry.
        SearchFieldConstraintPlan plan = service().fieldConstraintPlan("세종특별자치시 디자이너");

        assertEquals(Collections.singletonList("세종특별자치시"), plan.locations);
        assertEquals(Collections.singletonList("디자이너"), plan.titles);
        assertTrue("a place was named", plan.locationRequested);
        assertFalse("but nobody works there", plan.locationKnownToRepository);
        assertTrue("so the answer is already known", plan.abstains());
    }

    @Test
    public void theShorterAdministrativeSuffixesAreRecognisedTooWhenTheWordCanCarryAStem() {
        for (String query : new String[] {"춘천시 디자이너", "가평군 디자이너", "일산동구 디자이너"}) {
            SearchFieldConstraintPlan plan = service().fieldConstraintPlan(query);
            assertTrue(query + " names a place: " + plan, plan.locationRequested);
            assertTrue(query + " names a place nobody works in: " + plan, plan.abstains());
        }
    }

    @Test
    public void aColloquialPlaceNameTheCardsUseIsRecognised() {
        // 판교 carries no administrative suffix. The cards use it, which is what makes it a place.
        SearchFieldConstraintPlan plan = service().fieldConstraintPlan("판교 로봇엔지니어");

        assertEquals(Collections.singletonList("판교"), plan.locations);
        assertTrue("somebody does work there", plan.locationKnownToRepository);
        assertFalse(plan.abstains());
        assertEquals(Collections.singletonList("R004"), lookup(service(), "판교 로봇엔지니어"));
    }

    @Test
    public void aWordTheCardsUseForAPersonOrAnEmployerIsNotAPlace() {
        // Short suffixes are shared with ordinary words. A word this address book already spends on
        // somebody's name or employer is not reinterpreted as a region.
        List<BusinessCard> cards = new ArrayList<>(roster());
        cards.add(card("R006", "장미구", "미구상사", "영업이사", "영업팀", "유통",
                "부산광역시", "부산광역시 중구 중앙대로 6", "", Collections.<String>emptyList()));
        SearchLookupService service = new SearchLookupService(cards, new LocalEmbeddingEngine());

        SearchFieldConstraintPlan plan = service.fieldConstraintPlan("장미구 찾아줘");
        assertEquals("a person's name ending in 구 is not a district: " + plan,
                Collections.<String>emptyList(), plan.locations);
        assertFalse("and naming them must not abstain: " + plan, plan.abstains());
        assertTrue("they are still findable: " + lookup(service, "장미구 찾아줘"),
                lookup(service, "장미구 찾아줘").contains("R006"));
    }

    @Test
    public void aJobWinsOverAPlaceWhenTheWordCouldBeEither() {
        // 상무 is a rank and a district in 광주. Reading it as the district would hide every 상무.
        List<BusinessCard> cards = new ArrayList<>(roster());
        cards.add(card("R007", "천지호", "한별상사", "상무", "전략팀", "유통",
                "광주광역시", "광주광역시 서구 상무중앙로 7", "", Collections.<String>emptyList()));
        SearchLookupService service = new SearchLookupService(cards, new LocalEmbeddingEngine());

        SearchFieldConstraintPlan plan = service.fieldConstraintPlan("상무 찾아줘");
        assertEquals("상무 is the job here: " + plan, Collections.singletonList("상무"), plan.titles);
        assertEquals("and not a place: " + plan, Collections.<String>emptyList(), plan.locations);
        assertTrue(lookup(service, "상무 찾아줘").contains("R007"));
    }

    // ---- which field carries the meaning --------------------------------------------------------

    @Test
    public void aPlaceNamedInACompanyMemoOrTagIsNotWhereSomebodyWorks() {
        assertEquals("only location and address say where somebody works",
                Collections.<String>emptyList(), lookup(service(), "세종특별자치시 디자이너"));
        assertTrue("and the same card is reachable where it really is",
                lookup(service(), "인천광역시 디자이너").contains("R005"));
    }

    @Test
    public void aTitleIsMatchedWordByWordRatherThanBySubstring() {
        // 이사 and 대표이사 are different jobs. Once 이사 is a title this address book uses, asking
        // for an 이사 must not be answered with the 대표이사 whose title merely contains those two
        // characters.
        List<BusinessCard> cards = new ArrayList<>(roster());
        cards.add(card("R008", "명하진", "돌담무역", "이사", "경영지원팀", "유통",
                "대전광역시", "대전광역시 중구 계룡로 8", "", Collections.<String>emptyList()));
        SearchLookupService service = new SearchLookupService(cards, new LocalEmbeddingEngine());

        SearchFieldConstraintPlan plan = service.fieldConstraintPlan("서울특별시 이사");
        assertEquals("이사 is now a job the cards use: " + plan,
                Collections.singletonList("이사"), plan.titles);
        assertEquals("and the 서울 대표이사 does not hold it",
                Collections.<String>emptyList(), lookup(service, "서울특별시 이사"));
        assertEquals("the person whose title really is 이사 is found",
                Collections.singletonList("R008"), lookup(service, "대전광역시 이사"));
        assertTrue("and the 대표이사 is still reachable by their own title",
                lookup(service, "서울특별시 대표이사").contains("R003"));
    }

    // ---- how conditions combine ------------------------------------------------------------------

    @Test
    public void conditionsInDifferentFieldsAreAllRequired() {
        // 대전 has somebody and 디자이너 exists; nobody is both.
        assertFalse("대전 has people", lookup(service(), "대전광역시 변호사").isEmpty());
        assertFalse("designers exist", lookup(service(), "부산광역시 디자이너").isEmpty());
        assertEquals("but not one person who is both",
                Collections.<String>emptyList(), lookup(service(), "대전광역시 디자이너"));
    }

    @Test
    public void alternativesWithinOneFieldAreEitherOr() {
        SearchFieldConstraintPlan plan = service().fieldConstraintPlan("부산광역시 또는 대전광역시 변호사");
        assertEquals("both places are on the plan: " + plan,
                Arrays.asList("부산광역시", "대전광역시"), plan.locations);
        assertEquals(Collections.singletonList("변호사"), plan.titles);
        assertEquals("either place, and the job: " + plan,
                Collections.singletonList("R002"),
                lookup(service(), "부산광역시 또는 대전광역시 변호사"));
    }

    // ---- what must not be constrained --------------------------------------------------------------

    @Test
    public void aQuestionThatNamesNeitherAPlaceNorAJobConstrainsNothing() {
        for (String query : new String[] {"AI 전문가", "투자 담당자", "디자인 관련 담당자", "명함 찾아줘"}) {
            SearchFieldConstraintPlan plan = service().fieldConstraintPlan(query);
            assertTrue("\"" + query + "\" must not be read as a field constraint: " + plan,
                    plan.locations.isEmpty());
            assertFalse("and must not abstain: " + plan, plan.abstains());
        }
    }

    @Test
    public void aJobNamedOnItsOwnOrdersRatherThanFilters() {
        // "디자이너" alone is a broader question than "부산의 디자이너". Answering it by deleting
        // everyone whose title is not exactly 디자이너 would throw away the near answers.
        List<String> found = lookup(service(), "디자이너");
        assertTrue("the exact titles come first: " + found,
                found.indexOf("R001") == 0 || found.indexOf("R005") == 0);
        SearchFieldConstraintPlan plan = service().fieldConstraintPlan("디자이너");
        assertFalse("a job on its own does not filter: " + plan, plan.isStrict());
        assertTrue(plan.isTitleOnly());
    }

    // ---- the address book changes underneath ---------------------------------------------------

    @Test
    public void hiringSomebodyInThatRegionChangesTheAnswer() {
        // The vocabulary is derived from the cards, so it has to follow them. A cached answer of
        // "nobody works there" would outlive the person who just started working there.
        InMemoryBusinessCardRepository repository = new InMemoryBusinessCardRepository(roster());
        SearchLookupService service = new SearchLookupService(repository, new LocalEmbeddingEngine());

        assertEquals("nobody there yet", Collections.<String>emptyList(),
                lookup(service, "세종특별자치시 디자이너"));

        repository.upsertCard(card("R100", "윤보라", "새길디자인", "디자이너", "디자인팀", "디자인",
                "세종특별자치시", "세종특별자치시 한누리대로 100", "", Collections.<String>emptyList()));

        assertEquals("and now there is, on the same live service",
                Collections.singletonList("R100"), lookup(service, "세종특별자치시 디자이너"));
    }

    @Test
    public void movingSomebodyAwayChangesItBack() {
        InMemoryBusinessCardRepository repository = new InMemoryBusinessCardRepository(roster());
        SearchLookupService service = new SearchLookupService(repository, new LocalEmbeddingEngine());
        repository.upsertCard(card("R100", "윤보라", "새길디자인", "디자이너", "디자인팀", "디자인",
                "세종특별자치시", "세종특별자치시 한누리대로 100", "", Collections.<String>emptyList()));
        assertFalse(lookup(service, "세종특별자치시 디자이너").isEmpty());

        repository.upsertCard(card("R100", "윤보라", "새길디자인", "디자이너", "디자인팀", "디자인",
                "부산광역시", "부산광역시 해운대구 센텀로 100", "", Collections.<String>emptyList()));

        assertEquals("the same id, edited in place, is no longer there",
                Collections.<String>emptyList(), lookup(service, "세종특별자치시 디자이너"));
    }

    // ---- the plan dump used by the green evidence -------------------------------------------------

    /**
     * The nine cards {@link SearchFieldConstraintCharacterizationTest} searches.
     *
     * Repeated here rather than shared, because that file is frozen characterization evidence and is
     * not to be edited — including to extract a helper. The dump below has to resolve plans against
     * the same address book for the recorded constraints to describe the same searches.
     */
    private static List<BusinessCard> characterizationRoster() {
        return Arrays.asList(
                card("K001", "김도윤", "부산디자인랩", "디자이너", "디자인팀", "디자인",
                        "부산광역시", "부산광역시 해운대구 센텀로 12", "", Collections.<String>emptyList()),
                card("K002", "박서진", "해운대크리에이티브", "디자이너", "디자인팀", "디자인",
                        "부산광역시", "부산광역시 수영구 광안로 8", "", Collections.<String>emptyList()),
                card("K003", "이하람", "한밭법률사무소", "변호사", "송무팀", "법률",
                        "대전광역시", "대전광역시 서구 둔산로 45", "", Collections.<String>emptyList()),
                card("K004", "최윤슬", "빛고을스튜디오", "디자이너", "디자인팀", "디자인",
                        "광주광역시", "광주광역시 서구 상무대로 77", "", Collections.<String>emptyList()),
                card("K005", "정한별", "세종특별자치시개발원", "디자이너", "디자인팀", "디자인",
                        "인천광역시", "인천광역시 남동구 예술로 21", "세종특별자치시 본원 출장 예정",
                        Arrays.asList("세종특별자치시", "협력사")),
                card("K006", "오세라", "한밭회계법인", "회계사", "감사팀", "회계",
                        "대전광역시", "대전광역시 유성구 대학로 99", "", Collections.<String>emptyList()),
                card("K007", "강태오", "섬돌법률", "변호사", "송무팀", "법률",
                        "제주특별자치도", "제주특별자치도 제주시 중앙로 3", "", Collections.<String>emptyList()),
                card("K008", "문가온", "달빛에이아이", "AI 리서치 전문가", "AI연구팀", "AI",
                        "대구광역시", "대구광역시 수성구 동대구로 55", "", Collections.<String>emptyList()),
                card("K009", "신유하", "한강벤처스", "투자심사역", "투자팀", "금융",
                        "서울특별시", "서울특별시 강남구 테헤란로 4", "초기기업 투자 담당",
                        Collections.<String>emptyList()));
    }

    /** Every distinct query the characterization searches, in the order it first asks them. */
    private static final String[] CHARACTERIZATION_QUERIES = {
        "부산광역시 디자이너",
        "디자이너 중 부산광역시 근무자",
        "부산광역시에서 일하는 디자이너",
        "대전광역시 변호사",
        "세종특별자치시 디자이너",
        "대전광역시 디자이너",
        "광주광역시 변호사",
        "울산광역시 변호사",
        "춘천시 회계사",
        "AI 전문가",
        "투자 담당자",
        "디자인 관련 담당자",
        "인천광역시 디자이너",
        "세종특별자치시에서 일하는 디자이너 찾아줘",
        "디자이너 중에 세종특별자치시 근무자 알려주세요",
        "세종특별자치시에 있는 디자이너 연락처 부탁드립니다",
        "울산광역시에서 근무하는 변호사님 찾아주세요",
        "회계사 중 춘천시 계신 분",
    };

    @Test
    public void everyCharacterizationQueryResolvesToAPlanThatCanBeRecorded() {
        // Also the guard on the dump: a query that resolves to nothing recordable would silently
        // become a blank row in the evidence.
        for (String query : CHARACTERIZATION_QUERIES) {
            SearchFieldConstraintPlan plan = plan(query);
            assertTrue("a plan is always produced for \"" + query + "\"", plan != null);
            if (plan.abstains()) {
                assertTrue("an abstention names a place: " + plan, plan.locationRequested);
                assertFalse("and says why: " + plan, plan.abstainReason.isEmpty());
            }
        }
    }

    private static SearchFieldConstraintPlan plan(String query) {
        return new SearchLookupService(characterizationRoster(), new LocalEmbeddingEngine())
                .fieldConstraintPlan(query);
    }

    /**
     * Writes the resolved constraints per query, into the module's disposable build directory.
     *
     * The green evidence joins this to what the searches actually returned; without it the result
     * matrix could only say a search came back empty, not that a place was named and nobody in the
     * address book works there.
     */
    @AfterClass
    public static void writeConstraintPlans() {
        try {
            File directory = new File("build/search-field-constraints");
            if (!directory.isDirectory() && !directory.mkdirs()) return;
            StringBuilder out = new StringBuilder("{\n  \"plans\": [\n");
            for (int i = 0; i < CHARACTERIZATION_QUERIES.length; i++) {
                String query = CHARACTERIZATION_QUERIES[i];
                SearchFieldConstraintPlan plan = plan(query);
                out.append("    {\"query\": ").append(quote(query));
                out.append(", \"constraint_locations\": ").append(array(plan.locations));
                out.append(", \"constraint_titles\": ").append(array(plan.titles));
                out.append(", \"location_requested\": ").append(plan.locationRequested);
                out.append(", \"location_known_to_repository\": ").append(plan.locationKnownToRepository);
                out.append(", \"strict\": ").append(plan.isStrict());
                out.append(", \"abstained\": ").append(plan.abstains());
                out.append(", \"abstention_reason\": ").append(quote(plan.abstainReason));
                out.append("}").append(i == CHARACTERIZATION_QUERIES.length - 1 ? "\n" : ",\n");
            }
            out.append("  ]\n}\n");
            Files.write(new File(directory, "constraint_plans.json").toPath(),
                    out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Bookkeeping must never mask a contract failure.
        }
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
        return "\"" + safe.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
