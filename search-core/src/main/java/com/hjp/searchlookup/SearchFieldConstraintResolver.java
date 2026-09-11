package com.hjp.searchlookup;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a query and works out which fields it actually constrained.
 *
 * <h2>How a place is recognised</h2>
 *
 * Not from a list of Korean place names. A fixed list is wrong twice over: it goes stale, and it
 * cannot recognise a place that is real but absent from the data — which is precisely the case that
 * has to abstain. 세종특별자치시 is a place whether or not anybody in the address book works there,
 * and the search only knows to answer "nobody" if it first understands that a place was named.
 *
 * <p>So a token is read as a place when any of these holds:
 *
 * <ul>
 *   <li>the cards already use it as one — including the colloquial forms (판교, 강남);</li>
 *   <li>it ends in an unambiguous administrative suffix (특별시, 광역시, 특별자치시, 특별자치도,
 *       자치시, 자치도), which nothing else in Korean does;</li>
 *   <li>it ends in a short suffix (시, 군, 구) and is long enough to carry a stem, and this
 *       repository does not already use the word for a person, an employer, a job or a
 *       department;</li>
 *   <li>it ends in 도 — by far the most ambiguous of them, since 설계도, 제도 and 각도 all do too —
 *       and the sentence puts it in a place's role (…에서, …에 있는, 근무, 일하는, 계신).</li>
 * </ul>
 *
 * <h2>Job before place</h2>
 *
 * A word that could be either is a job. 상무 is a rank and also a district in 광주; reading it as
 * the district would silently drop every 상무 in the company. The cards' own title vocabulary wins.
 *
 * <h2>Freshness</h2>
 *
 * The vocabulary is derived from the repository, and a repository can change under a long-lived
 * service — Android rebuilds the service on {@code invalidate()}, but the in-memory repository can
 * also be written through directly. So it is cached against a fingerprint of the fields the
 * vocabulary is built from, and rebuilt when they move. A cache that could go stale would answer
 * "nobody works there" about somebody hired five minutes ago.
 */
final class SearchFieldConstraintResolver {

    /** Suffixes trimmed to register a colloquial short form: 강남구 → 강남. */
    static final String[] TRIMMABLE_LOCATION_SUFFIXES = {
        "특별자치시", "특별자치도", "특별시", "광역시", "자치시", "자치도", "시", "군", "구", "도", "로", "길",
    };

    /** Suffixes no other kind of Korean word carries. */
    private static final String[] ADMINISTRATIVE_SUFFIXES = {
        "특별자치시", "특별자치도", "특별시", "광역시", "자치시", "자치도",
    };

    /** Short administrative suffixes: real, but shared with ordinary words. */
    private static final String[] SHORT_SUFFIXES = {"시", "군", "구"};

    /** The most ambiguous suffix of all; needs the sentence to agree. */
    private static final String PROVINCE_SUFFIX = "도";

    /** Phrases that put a word in a place's role rather than a topic's. */
    private static final String[] LOCATION_ROLE_MARKERS = {
        "에서", "에 있는", "에있는", "근무", "일하는", "계신", "소재", "지사", "근처",
    };

    private final BusinessCardRepository repository;

    private volatile SearchFieldVocabulary cachedVocabulary;
    private volatile long cachedFingerprint;
    private volatile boolean cacheValid;

    SearchFieldConstraintResolver(BusinessCardRepository repository) {
        this.repository = repository;
    }

    SearchFieldConstraintPlan resolve(QueryAnalysis analysis) {
        if (analysis == null || analysis.tokens.isEmpty()) return SearchFieldConstraintPlan.NONE;
        SearchFieldVocabulary vocabulary = vocabulary();
        if (vocabulary == null) return SearchFieldConstraintPlan.NONE;

        boolean roleMarked = hasLocationRole(analysis.normalizedQuery);
        List<String> locations = new ArrayList<>();
        List<String> titles = new ArrayList<>();

        for (String rawToken : analysis.tokens) {
            String token = SearchFieldVocabulary.normalize(rawToken);
            if (token.length() < 2) continue;
            // A word the cards use as a job is a job, even when it doubles as a district.
            if (vocabulary.titleTerms.contains(token)) {
                addDistinct(titles, token);
                continue;
            }
            if (namesAPlace(token, vocabulary, roleMarked)) {
                addDistinct(locations, token);
            }
        }
        if (locations.isEmpty() && titles.isEmpty()) return SearchFieldConstraintPlan.NONE;

        boolean known = false;
        for (String location : locations) {
            if (vocabulary.someoneWorksIn(location)) { known = true; break; }
        }
        String abstainReason = "";
        if (!locations.isEmpty() && !known) {
            abstainReason = "NO_CARD_IN_REQUESTED_LOCATION";
        }
        return SearchFieldConstraintPlan.of(locations, titles, known, abstainReason);
    }

    /** Exposed for tests in this package; production callers go through {@link #resolve}. */
    SearchFieldConstraintPlan resolve(String rawQuery, QueryAnalyzer analyzer) {
        return resolve(analyzer.analyze(rawQuery));
    }

    private boolean namesAPlace(String token, SearchFieldVocabulary vocabulary, boolean roleMarked) {
        if (vocabulary.locationTerms.contains(token)) return true;

        for (String suffix : ADMINISTRATIVE_SUFFIXES) {
            // A stem plus the suffix: 특별시 on its own names nowhere.
            if (token.length() > suffix.length() + 1 && token.endsWith(suffix)) return true;
        }
        // 이름·회사·직함·부서로 이미 쓰이는 말은 지역으로 읽지 않는다.
        if (vocabulary.nonLocationTerms.contains(token)) return false;

        for (String suffix : SHORT_SUFFIXES) {
            if (token.length() >= 3 && token.endsWith(suffix)
                    && token.length() - suffix.length() >= 2) {
                return true;
            }
        }
        return roleMarked && token.length() >= 3 && token.endsWith(PROVINCE_SUFFIX)
                && token.length() - PROVINCE_SUFFIX.length() >= 2;
    }

    private static boolean hasLocationRole(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isEmpty()) return false;
        for (String marker : LOCATION_ROLE_MARKERS) {
            if (normalizedQuery.contains(marker)) return true;
        }
        return false;
    }

    private static void addDistinct(List<String> values, String value) {
        if (!values.contains(value)) values.add(value);
    }

    /**
     * The vocabulary for the repository as it stands right now.
     *
     * The fingerprint covers exactly the fields the vocabulary is derived from, so an edit that
     * could change the answer invalidates the cache and an edit that could not does not.
     */
    private SearchFieldVocabulary vocabulary() {
        if (repository == null) return null;
        List<BusinessCard> cards = repository.getAllCards();
        long fingerprint = fingerprint(cards);
        SearchFieldVocabulary cached = cachedVocabulary;
        if (cacheValid && cached != null && fingerprint == cachedFingerprint) return cached;
        SearchFieldVocabulary rebuilt = SearchFieldVocabulary.from(cards);
        cachedVocabulary = rebuilt;
        cachedFingerprint = fingerprint;
        cacheValid = true;
        return rebuilt;
    }

    private static long fingerprint(List<BusinessCard> cards) {
        long value = 1125899906842597L + cards.size();
        for (BusinessCard card : cards) {
            if (card == null) continue;
            value = 31 * value + card.id.hashCode();
            value = 31 * value + card.location.hashCode();
            value = 31 * value + card.address.hashCode();
            value = 31 * value + card.title.hashCode();
            value = 31 * value + card.name.hashCode();
            value = 31 * value + card.company.hashCode();
            value = 31 * value + card.department.hashCode();
            value = 31 * value + card.industry.hashCode();
        }
        return value;
    }
}
