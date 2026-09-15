package com.hjp.searchlookup;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;
import static org.junit.Assert.*;

public class CompanyConstraintIntegrationTest {
    private BusinessCard card(String id, String company, String department, String memo) {
        return new BusinessCard(id, "직원" + id, "", company, "개발자", department, "", "서울",
                "", "", "서울", memo, Collections.emptyList());
    }
    private SearchLookupService service() {
        return new SearchLookupService(Arrays.asList(
                card("1", "(주)한빛테크", "연구팀", ""),
                card("2", "다른테크", "연구팀", "한빛테크와 협업"),
                card("3", "한빛테크", "영업팀", ""),
                card("4", "한빛테크 연구소", "연구팀", "")), new LocalEmbeddingEngine());
    }
    private List<String> ids(String query) {
        return service().retrieve(query, 10, RetrievalMode.KEYWORD_ONLY).results.stream()
                .map(r -> r.card.id).collect(Collectors.toList());
    }
    @Test public void employerExcludesMemoAndLongerCompanyMatches() {
        assertEquals(Arrays.asList("1", "3"), ids("한빛테크 개발자"));
    }
    @Test public void employerAndDepartmentAreBothRequired() {
        assertEquals(Collections.singletonList("1"), ids("한빛테크 연구팀"));
    }
    @Test public void multiwordEmployerWinsOverItsShorterPrefix() {
        assertEquals(Collections.singletonList("4"), ids("한빛테크 연구소 개발자"));
    }
    @Test public void companyCountsDoNotIncludePartnersMentionedInMemos() {
        assertEquals(2, service().countMatching("한빛테크 몇 명"));
    }
    @Test public void companyWithParticleStillConstrainsTheEmployer() {
        assertEquals(Arrays.asList("1", "3"), ids("한빛테크에서 일하는 사람"));
    }
    @Test public void observedCompanyComesFromTheAppliedPlan() {
        final SearchPlanSnapshot[] observed = new SearchPlanSnapshot[1];
        service().retrieve("한빛테크 연구팀", 10, RetrievalMode.KEYWORD_ONLY, null, plan -> observed[0] = plan);
        assertEquals(Collections.singletonList("한빛테크"), observed[0].companies());
        assertEquals(Collections.singletonList("연구팀"), observed[0].departments());
        assertTrue(observed[0].strictFilterApplied());
    }
    @Test public void aCompanySubstringInsideAnotherWordDoesNotBecomeAConstraint() {
        assertTrue(service().fieldConstraintPlan("한빛테크놀로지").companies.isEmpty());
    }
    @Test public void aCompanyNamedLikeACityDoesNotStealTheLocationConstraint() {
        SearchLookupService service = new SearchLookupService(Arrays.asList(
                card("1", "서울", "연구팀", ""), card("2", "다른회사", "연구팀", "")), new LocalEmbeddingEngine());
        SearchFieldConstraintPlan plan = service.fieldConstraintPlan("서울 개발자");
        assertTrue(plan.companies.isEmpty());
        assertFalse(plan.locations.isEmpty());
    }
}
