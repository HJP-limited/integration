package com.hjp.searchlookup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Behaviour the upstream ryeong branch fixed at 9f359c7, checked against this port.
 *
 * The classes are ours — {@link QueryAnalyzer}, {@link SearchFieldVocabulary},
 * {@link SearchFieldConstraintResolver} — but they carry the same responsibilities as upstream's
 * KeywordSearchRanker / CardGazetteer / extractFieldFilters. Each case below is a defect upstream
 * measured on a real device and then fixed; the question here is whether the same defect exists on
 * this side.
 *
 * Every card is synthetic and appears in no evaluation fixture.
 */
public class UpstreamParityCharacterizationTest {

    private static final QueryAnalyzer ANALYZER = new QueryAnalyzer();

    private static BusinessCard card(String id, String name, String company, String title,
            String department, String location, String address) {
        return new BusinessCard(id, name, "", company, title, department, "", location,
                "010-0000-0000", id.toLowerCase() + "@example.invalid", address, "",
                new ArrayList<>());
    }

    private static final List<BusinessCard> CARDS = Arrays.asList(
            card("PC001", "남다은", "너울건설", "상무", "안전관리팀", "대전광역시", "대전광역시 서구 1-1"),
            card("PC002", "손서윤", "새벽테크", "연구소장", "연구소", "서울특별시", "서울특별시 강남구 2-2"),
            card("PC003", "두미영", "물결식품", "영업이사", "영업팀", "부산광역시", "부산광역시 해운대구 3-3"));

    private List<String> tokensOf(String query) {
        return ANALYZER.analyze(query).tokens;
    }

    /** Does any token this query produced name the person we meant? */
    private boolean findsName(String query, String name) {
        return tokensOf(query).stream().anyMatch(t -> t.equals(name) || name.contains(t) && t.length() >= 2);
    }

    // ---- 1. copula ending "-(이)야" on a correction --------------------------------------------

    @Test
    public void a_correction_ending_in_the_copula_still_yields_the_new_name() {
        // 손서윤씨가 아니라 남다은씨야 — upstream measured 6/10 corrections lost because
        // "남다은씨야" never reduced to "남다은", so focus stayed on the old target.
        List<String> tokens = tokensOf("손서윤씨가 아니라 남다은씨야");
        assertTrue("expected 남다은 among " + tokens, tokens.contains("남다은"));
    }

    @Test
    public void a_correction_with_a_trailing_period_still_yields_the_new_name() {
        // Upstream: "남다은씨야." extracted zero names, because normalize keeps '.' for e-mail
        // addresses and the particle stripper then never matches.
        List<String> tokens = tokensOf("손서윤씨가 아니라 남다은씨야.");
        assertTrue("expected 남다은 among " + tokens, tokens.contains("남다은"));
    }

    // ---- 2. adnominal copula "-인" in a condition ------------------------------------------------

    @Test
    public void a_condition_phrased_with_the_adnominal_copula_still_names_the_place() {
        // 주소가 대전인 사람 — upstream: '대전인' never matched the gazetteer's '대전', so no
        // condition was captured at all and a count query silently fell back to a 5-card search.
        List<String> tokens = tokensOf("주소가 대전인 사람");
        assertTrue("expected 대전 among " + tokens, tokens.contains("대전"));
    }

    @Test
    public void a_title_condition_phrased_with_the_adnominal_copula_still_names_the_title() {
        List<String> tokens = tokensOf("직급이 상무인 사람");
        assertTrue("expected 상무 among " + tokens, tokens.contains("상무"));
    }

    // ---- 3. a stray token that looks like a 도 region must not force an abstention ---------------

    @Test
    public void naming_a_real_person_survives_a_field_word_that_looks_like_a_region() {
        // 두미영 회사와 이메일도 알려줘 — "이메일도" ends in 도 and is three syllables or more, so a
        // suffix rule can read it as a province. Upstream abstained on the whole query; asking with
        // "이메일" alone worked. The fix: a query that already names a real person is not abstained
        // because of a side token's region false positive.
        SearchFieldConstraintResolver resolver =
                new SearchFieldConstraintResolver(new InMemoryBusinessCardRepository(CARDS));
        SearchFieldConstraintPlan plan = resolver.resolve(ANALYZER.analyze("두미영 회사와 이메일도 알려줘"));
        assertEquals("must not abstain when a real person is named: " + plan,
                "", plan.abstainReason);
    }

    @Test
    public void an_unknown_place_without_a_role_marker_is_not_read_as_a_location_here() {
        // A deliberate difference from upstream, recorded rather than "fixed".
        //
        // Upstream reads a 도-final token as a province on shape alone, which is why it needed the
        // named-person guard. This port only applies the 도 rule when the query also carries a
        // location role marker, so "울릉도 근무자 찾아줘" never becomes a location filter and never
        // reaches the abstention branch at all.
        //
        // The abstention contract on this side is pinned by the sealed search run_3 (20/20), so it
        // is not moved to match upstream's shape. What matters is that both sides refuse to abstain
        // on the query above — see the previous test — and they get there by different routes.
        SearchFieldConstraintResolver resolver =
                new SearchFieldConstraintResolver(new InMemoryBusinessCardRepository(CARDS));
        SearchFieldConstraintPlan plan = resolver.resolve(ANALYZER.analyze("울릉도 근무자 찾아줘"));
        assertEquals("no location was extracted, so there is nothing to abstain over",
                "", plan.abstainReason);
        assertTrue("and no location filter was applied: " + plan, plan.locations.isEmpty());
    }

    @Test
    public void an_explicit_unknown_region_with_a_role_marker_still_abstains() {
        // The guard stays narrow where it does apply: with a role marker and nobody named, an
        // unknown administrative place is still an abstention. This is the run_3 contract.
        SearchFieldConstraintResolver resolver =
                new SearchFieldConstraintResolver(new InMemoryBusinessCardRepository(CARDS));
        SearchFieldConstraintPlan plan =
                resolver.resolve(ANALYZER.analyze("세종특별자치시에서 근무하는 사람 찾아줘"));
        assertEquals("an unknown administrative place must still abstain",
                "NO_CARD_IN_REQUESTED_LOCATION", plan.abstainReason);
    }

    // ---- 4. what already works, so a fix cannot quietly break it ---------------------------------

    @Test
    public void an_ordinary_honorific_query_still_finds_the_person() {
        assertTrue(findsName("남다은씨 명함 찾아줘", "남다은"));
    }

    @Test
    public void an_email_address_survives_normalisation() {
        List<String> tokens = tokensOf("pc001@example.invalid 찾아줘");
        assertTrue("e-mail must survive: " + tokens,
                tokens.stream().anyMatch(t -> t.contains("@")));
    }

    @Test
    public void a_phone_number_still_yields_its_digits() {
        List<String> tokens = tokensOf("010-0000-0000");
        assertTrue("digits must survive: " + tokens, tokens.contains("01000000000"));
    }
}
