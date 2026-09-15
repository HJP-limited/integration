package com.hjp.searchlookup;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;
import static org.junit.Assert.*;

/** Candidate selection and observation must honor the same organizational constraint. */
public class DepartmentConstraintIntegrationTest {
    private BusinessCard card(String id, String department, String title, String memo) {
        return new BusinessCard(id, "직원" + id, "", "검증기업", title, department, "", "서울",
                "", "", "서울", memo, Collections.emptyList());
    }
    private SearchLookupService service() {
        return new SearchLookupService(Arrays.asList(
                card("1", "선행연구팀", "개발자", ""),
                card("2", "영업팀", "개발자", "선행연구팀과 협업"),
                card("3", "선행연구팀", "고문개발자", "")), new LocalEmbeddingEngine());
    }
    private List<String> ids(List<SearchResult> results) {
        return results.stream().map(r -> r.card.id).collect(Collectors.toList());
    }
    @Test public void departmentWithoutLocationExcludesMemoOnlyMatches() {
        assertEquals(Arrays.asList("1", "3"), ids(service().retrieve("선행연구팀", 10, RetrievalMode.KEYWORD_ONLY).results));
    }
    @Test public void departmentFilterPreservesExistingSoftTitlePolicyWithinTheDepartment() {
        assertEquals(Arrays.asList("1", "3"), ids(service().retrieve("선행연구팀 개발자", 10, RetrievalMode.KEYWORD_ONLY).results));
    }
    @Test public void observerReportsTheDepartmentActuallyApplied() {
        final SearchPlanSnapshot[] observed = new SearchPlanSnapshot[1];
        service().retrieve("선행연구팀", 10, RetrievalMode.KEYWORD_ONLY, null, snapshot -> observed[0] = snapshot);
        assertNotNull(observed[0]);
        assertEquals(Collections.singletonList("선행연구팀"), observed[0].departments());
        assertTrue(observed[0].strictFilterApplied());
        assertEquals(Arrays.asList("1", "3"), observed[0].rankedCardIds());
    }
}
