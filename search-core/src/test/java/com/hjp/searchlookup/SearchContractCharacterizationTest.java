package com.hjp.searchlookup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * The search contract, written from what a lookup has to guarantee rather than from what the code
 * currently returns.
 *
 * The ryeong reference and this repository share the same package and most of the same files — this
 * search library was ported from there once already. What differs is where each side went afterwards:
 * the reference grew a keyword-candidate abstraction, FTS retrievers, tokenizers and a reranker seam;
 * this side grew suffix and honorific stripping, stop words, and an operator-safe FTS path. Porting
 * either wholesale would trade one set of hardening for another.
 *
 * So this is the contract both sides are supposed to satisfy, checked against the current
 * implementation: which card a query should reach first, what happens when nothing matches, what
 * happens when two people share a name, and what a query full of characters the query engine treats
 * as syntax does.
 */
public class SearchContractCharacterizationTest {

    private BusinessCard card(String id, String name, String company, String title,
                              String department, String phone, String email) {
        return new BusinessCard(id, name, "", company, title, department, "it", "서울",
                phone, email, "서울", "", Collections.<String>emptyList());
    }

    /** A roster whose fields deliberately collide across cards, so tier order is observable. */
    private List<BusinessCard> roster() {
        return Arrays.asList(
                card("C001", "김서준", "비전글로벌", "대표이사", "전략팀", "010-1111-0001", "seojun@vision.example.net"),
                card("C002", "김서준", "새길테크", "책임연구원", "연구팀", "010-1111-0002", "seojun@saegil.example.net"),
                card("C003", "박서준", "비전글로벌", "선임연구원", "전략팀", "010-1111-0003", "parkseojun@vision.example.net"),
                card("C004", "이하늘", "김서준컴퍼니", "팀장", "영업팀", "010-1111-0004", "haneul@ksj.example.net"),
                card("C005", "최다온", "온빛교육", "김서준연구소장", "교육팀", "010-1111-0005", "daon@onbit.example.net"));
    }

    private SearchLookupService service() {
        return new SearchLookupService(roster(), new LocalEmbeddingEngine());
    }

    private List<String> ids(List<SearchResult> results) {
        List<String> out = new ArrayList<>();
        for (SearchResult r : results) out.add(r.card.id);
        return out;
    }

    // ---- which card a query reaches first ------------------------------------------------------

    @Test
    public void anExactNameOutranksTheSameStringInsideAnotherField() {
        // 김서준 is two people's name, one company's name and one person's title. A search for the
        // name must reach the people called that before the company that contains it — otherwise a
        // lookup for a person lands on somebody else's employer.
        List<String> found = ids(service().search("김서준", 5));

        assertFalse("the name must match somebody", found.isEmpty());
        assertTrue(
                "an exact name match belongs at the top, ahead of a company or title that merely "
                        + "contains the same string: " + found,
                found.get(0).equals("C001") || found.get(0).equals("C002"));
        int firstNameHolder = Math.min(found.indexOf("C001"), found.indexOf("C002"));
        int companyHolder = found.indexOf("C004");
        if (companyHolder >= 0) {
            assertTrue("the person named 김서준 outranks the company called 김서준컴퍼니: " + found,
                    firstNameHolder < companyHolder);
        }
    }

    @Test
    public void aCompanyQueryReachesEveryoneThere() {
        List<String> found = ids(service().search("비전글로벌", 5));
        assertTrue("both 비전글로벌 people must be reachable: " + found,
                found.contains("C001") && found.contains("C003"));
    }

    @Test
    public void aTitleAndADepartmentAreSearchable() {
        assertFalse("a title must be searchable", service().search("대표이사", 5).isEmpty());
        assertFalse("a department must be searchable", service().search("연구팀", 5).isEmpty());
    }

    @Test
    public void aPhoneNumberIsSearchableWhateverItsPunctuation() {
        List<String> dashed = ids(service().search("010-1111-0003", 5));
        List<String> bare = ids(service().search("01011110003", 5));
        assertTrue("a dashed number must find its owner: " + dashed, dashed.contains("C003"));
        assertTrue(
                "and so must the same digits without punctuation, because that is how people type "
                        + "them: " + bare,
                bare.contains("C003"));
    }

    @Test
    public void anHonorificOrParticleDoesNotBreakTheLookup() {
        // "김서준씨", "김서준을", "김서준한테" are the same person as "김서준".
        for (String query : new String[] {"김서준씨", "김서준님", "김서준을", "김서준한테", "김서준 씨"}) {
            List<String> found = ids(service().search(query, 5));
            assertTrue("\"" + query + "\" must still find the person: " + found,
                    found.contains("C001") || found.contains("C002"));
        }
    }

    // ---- what happens when there is nothing, or too much ----------------------------------------

    @Test
    public void aNameNobodyHasReturnsNothingRatherThanTheNearestPerson() {
        // The dangerous failure is a confident near-miss: returning 김서준 for 김서윤 puts a real
        // address behind a name the user did not ask for.
        assertTrue("an unknown name must return nothing", service().search("고윤슬", 5).isEmpty());
        assertTrue("including one that is close to a real name",
                service().search("김서윤", 5).isEmpty());
    }

    @Test
    public void aDuplicatedNameReturnsBothPeopleRatherThanPickingOne() {
        List<String> found = ids(service().search("김서준", 5));
        assertTrue(
                "two people share this name; returning only one would make the agent choose a "
                        + "person the user never distinguished: " + found,
                found.contains("C001") && found.contains("C002"));
    }

    @Test
    public void anAgentLookupWithNoUsableTermsFindsNobody() {
        // `retrieve` is the agent path. A query that analyses to no terms found nobody, and must not
        // hand back the roster — with a one-card store that becomes a SINGLE_RESULT selection, i.e.
        // an actionable target from a query that matched nothing.
        String[] degenerate = {"", "   ", "!!!", "***", "명함 찾아줘", "연락처 알려줘"};
        List<String> leaked = new ArrayList<>();
        for (String query : degenerate) {
            RetrievalResponse response = service().retrieve(query, 5, RetrievalMode.KEYWORD_ONLY);
            if (!response.results.isEmpty()) {
                leaked.add("[" + query + "] -> " + ids(response.results));
            }
        }
        assertEquals("a lookup that matched nothing must return nothing", 
                Collections.<String>emptyList(), leaked);
    }

    @Test
    public void browsingTheCardListWithAnEmptyQueryStillShowsEveryone() {
        // The other half of the same decision: an empty box in the card list means "show me
        // everyone", and that must keep working.
        assertEquals("browsing shows the whole roster", 5, service().search("", 20).size());
    }

    @Test
    public void aLookupThatMatchesSomethingIsUnaffected() {
        assertFalse(service().retrieve("비전글로벌", 5, RetrievalMode.KEYWORD_ONLY).results.isEmpty());
    }

    // ---- input the query engine would read as syntax ---------------------------------------------

    @Test
    public void queryEngineSyntaxInTheQueryIsNotAnError() {
        // FTS treats " * ^ : - and the bare words AND/OR/NOT/NEAR as syntax. A user typing them —
        // or pasting a card line — must get a result or an empty list, never a crash.
        String[] hostile = {
            "\"김서준\"", "김서준*", "^김서준", "김서준 AND 비전글로벌", "김서준 OR", "NEAR/2 김서준",
            "김서준:대표", "-김서준", "김서준 (비전글로벌)", "김서준 -- 대표이사", "*", "\"", "AND",
        };
        List<String> crashed = new ArrayList<>();
        for (String query : hostile) {
            try {
                service().search(query, 5);
            } catch (RuntimeException failure) {
                crashed.add(query + " -> " + failure.getClass().getSimpleName());
            }
        }
        assertEquals("no query may throw; a lookup either finds something or finds nothing",
                Collections.<String>emptyList(), crashed);
    }

    @Test
    public void aQuotedNameStillFindsThePerson() {
        List<String> found = ids(service().search("\"김서준\"", 5));
        assertTrue("quoting a name is a thing people do: " + found,
                found.contains("C001") || found.contains("C002"));
    }

    // ---- stability and the embedding fallback -----------------------------------------------------

    @Test
    public void repeatingTheSameQueryGivesTheSameOrder() {
        // Rank instability makes "두 번째 사람" mean different people on consecutive turns.
        List<String> first = ids(service().search("비전글로벌", 5));
        for (int attempt = 0; attempt < 5; attempt++) {
            assertEquals("the same query must rank the same way every time",
                    first, ids(service().search("비전글로벌", 5)));
        }
    }

    @Test
    public void searchStillWorksWhenNoEmbeddingEngineIsAvailable() {
        // On a device without the embedding model the agent must degrade to keyword search rather
        // than stop finding people.
        SearchLookupService withoutEmbeddings = new SearchLookupService(roster(), null);
        List<String> found = ids(withoutEmbeddings.search("김서준", 5));
        assertTrue("keyword search must carry the lookup on its own: " + found,
                found.contains("C001") || found.contains("C002"));
    }

    @Test
    public void theHybridPathReportsWhichModeItActuallyUsed() {
        // A caller has to be able to tell a semantic result from a keyword fallback, or "semantic
        // performance" is unmeasurable.
        RetrievalResponse response = service().retrieve("비전글로벌", 5, RetrievalMode.HYBRID);
        assertTrue("the response must state its mode", response.mode != null);
    }

    // ---- a lookup by id is exact --------------------------------------------------------------------

    @Test
    public void gettingACardByIdIsExactAndMissesCleanly() {
        assertEquals("C001", service().getCard("C001").id);
        assertEquals("an id nobody has must return nothing rather than a neighbour",
                null, service().getCard("C999"));
        // Trimming is documented normalisation and cannot change *which* card is meant. What must
        // not happen is a neighbouring id resolving: a prefix or an extended id is a different card.
        assertEquals("surrounding whitespace is normalised, not a different card",
                "C001", service().getCard(" C001 ").id);
        assertEquals("a prefix of an id is not that card", null, service().getCard("C00"));
        assertEquals("nor is a longer id", null, service().getCard("C0011"));
    }
}
