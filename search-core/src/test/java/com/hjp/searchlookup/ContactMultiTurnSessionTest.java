package com.hjp.searchlookup;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** Forty two/three-turn regressions derived from Ryeong b543a18 session failures. */
public class ContactMultiTurnSessionTest {
    private final BusinessCard jiwon = card("C1", "김지원", "비전글로벌");
    private final BusinessCard seoyeon = card("C2", "이서연", "스타테크");
    private final BusinessCard gang = card("C3", "강서연", "코어AI");

    @Test public void fortyMultiTurnReferenceScenarios() {
        List<Scenario> cases = Arrays.asList(
                s("그 사람 회사가 어디야?", "김지원 회사가 어디야?"),
                s("그분에게 문자 작성해줘", "김지원에게 문자 작성해줘"),
                s("그 분 이메일은?", "김지원 이메일은?"),
                s("그에게 메일 작성해줘", "김지원에게 메일 작성해줘"),
                s("그 사람에게 연락해줘", "김지원에게 연락해줘"),
                s("걔 직책은?", "김지원 직책은?"),
                s("그 회사가 어디야?", "김지원 회사가 어디야?"),
                s("회사는?", "김지원 회사는?"),
                s("직급은?", "김지원 직급은?"),
                s("직책 알려줘", "김지원 직책 알려줘"),
                s("부서는?", "김지원 부서는?"),
                s("전화번호는?", "김지원 전화번호는?"),
                s("이메일 확인", "김지원 이메일 확인"),
                s("주소 알려줘", "김지원 주소 알려줘"),
                s("지역은?", "김지원 지역은?"),
                s("번호 뒷자리 4312인 사람 찾아줘", "번호 뒷자리 4312인 사람 찾아줘"),
                s("010-1234-4312 찾아줘", "010-1234-4312 찾아줘"),
                s("이서연에게 메일 작성해줘", "이서연에게 메일 작성해줘"),
                s("강서연씨 찾아줘", "강서연씨 찾아줘"),
                s("새로운 투자 담당자 찾아줘", "새로운 투자 담당자 찾아줘"),
                s("그분 회사명 알려줘", "김지원 회사명 알려줘"),
                s("그 사람 부서 알려줘", "김지원 부서 알려줘"),
                s("그분과 내일 회의 잡아줘", "김지원과 내일 회의 잡아줘"),
                s("그 사람에게 감사 메일 작성해줘", "김지원에게 감사 메일 작성해줘"),
                s("그분에게 일정 변경 문자 작성해줘", "김지원에게 일정 변경 문자 작성해줘"),
                s("전화 알려줘", "김지원 전화 알려줘"),
                s("메일 주소는?", "김지원 메일 주소는?"),
                s("번호 알려줘", "김지원 번호 알려줘"),
                s("주소는 어디야?", "김지원 주소는 어디야?"),
                s("지역 알려줘", "김지원 지역 알려줘"),
                s("이서연씨에게 문자 작성해줘", "이서연씨에게 문자 작성해줘"),
                s("강서연님 회사 알려줘", "강서연님 회사 알려줘"),
                s("박민수 명함 찾아줘", "박민수 명함 찾아줘"),
                s("01055554312인 사람 찾아줘", "01055554312인 사람 찾아줘"),
                s("4312 번호 찾아줘", "4312 번호 찾아줘"),
                s("다른 사람 찾아줘", "다른 사람 찾아줘"),
                s("오늘 그분에게 연락해줘", "오늘 김지원에게 연락해줘"),
                s("그 회사 담당자에게 메일 써줘", "김지원 회사 담당자에게 메일 써줘"),
                s("그 사람 이메일로 자료 보내는 화면 열어줘", "김지원 이메일로 자료 보내는 화면 열어줘"),
                s("회사 이메일은?", "김지원 회사 이메일은?")
        );
        assertEquals(40, cases.size());
        for (Scenario scenario : cases) {
            AgentSessionState session = focusedSession();
            assertEquals(scenario.expected, session.resolveSearchQuery(scenario.followUp));
        }
    }

    @Test public void explicitNameControlsFocusAndOrdinalKeepsSearchProvenance() {
        AgentSessionState session = new AgentSessionState();
        session.updateLastSearch("김지원과 이서연 찾아줘", Arrays.asList(result(jiwon), result(seoyeon)));
        assertEquals("C1", session.getLastSelectedCardId());
        assertEquals("C2", session.resolveReferencedCardId("두 번째 사람의 이메일 확인"));
        session.updateLastSearch("이서연에게 메일 작성해줘", Arrays.asList(result(jiwon), result(seoyeon)));
        assertEquals("C2", session.getLastSelectedCardId());
    }

    @Test public void honorificExplicitNameBeatsSimilarTopResult() {
        AgentSessionState session = new AgentSessionState();
        session.updateLastSearch("강서연씨 찾아줘", Arrays.asList(result(seoyeon), result(gang)));
        assertEquals("C3", session.getLastSelectedCardId());
    }

    @Test public void numberConstraintNeverLeaksPreviousCardId() {
        AgentSessionState session = focusedSession();
        assertEquals("", session.resolveReferencedCardId("번호 뒷자리 4312인 사람"));
    }

    @Test public void threeTurnExplicitSwitchChangesSubsequentPronounFocus() {
        long[] elapsedNanos = new long[10];
        for (int index = 0; index < 10; index++) {
            long started = System.nanoTime();
            AgentSessionState session = focusedSession();
            assertEquals("김지원에게 문자 작성해줘", session.resolveSearchQuery("그분에게 문자 작성해줘"));
            session.updateLastSearch("이서연 찾아줘", Collections.singletonList(result(seoyeon)));
            assertEquals("이서연 회사가 어디야?", session.resolveSearchQuery("그 사람 회사가 어디야?"));
            assertEquals("C2", session.resolveReferencedCardId("그 사람 회사가 어디야?"));
            elapsedNanos[index] = System.nanoTime() - started;
        }
        Arrays.sort(elapsedNanos);
        System.out.println(
                "[MULTITURN_STABILITY] repeats=10 success=10 rank_variants=1 " +
                        "p50_ms=" + elapsedNanos[5] / 1_000_000.0 +
                        " p95_ms=" + elapsedNanos[9] / 1_000_000.0
        );
    }

    @Test public void zeroResultDoesNotRewriteTextToOldFocus() {
        AgentSessionState session = focusedSession();
        session.updateLastSearch("없는 사람 찾아줘", Collections.emptyList());
        assertEquals("그분 이메일은?", session.resolveSearchQuery("그분 이메일은?"));
        assertEquals(0, session.getLastSearchResults().size());
    }

    private AgentSessionState focusedSession() {
        AgentSessionState session = new AgentSessionState();
        session.updateLastSearch("김지원 명함 찾아줘", Collections.singletonList(result(jiwon)));
        return session;
    }

    private SearchResult result(BusinessCard card) {
        return new SearchResult(card, 1.0).withRank(1);
    }

    private BusinessCard card(String id, String name, String company) {
        return new BusinessCard(id, name, "", company, "", "", "", "", "", "", "", "", Collections.emptyList());
    }

    private Scenario s(String followUp, String expected) { return new Scenario(followUp, expected); }

    private static final class Scenario {
        final String followUp;
        final String expected;
        Scenario(String followUp, String expected) { this.followUp = followUp; this.expected = expected; }
    }
}
