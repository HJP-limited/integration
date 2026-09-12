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

    /** Words that mark the token before them as a person. */
    static final String[] NAME_HONORIFICS = { "씨", "님", "군", "양" };

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
        List<String> departments = new ArrayList<>();
        boolean namedRealPerson = false;
        boolean namedAbsentPersonWithHonorific = false;
        boolean namedAbsentBareName = false;

        for (String rawToken : analysis.tokens) {
            String token = SearchFieldVocabulary.normalize(rawToken);
            if (token.length() < 2) continue;
            // A word the cards use as a job is a job, even when it doubles as a district.
            if (vocabulary.titleTerms.contains(token)) {
                addDistinct(titles, token);
                continue;
            }
            // 부서는 직함 다음, 지명보다 먼저. 팀 이름이 지역 접미사로 끝나는 일이 있다
            // (…지원구, …영업소). 조직 단위로 실재하는 말이면 그쪽이 먼저다.
            if (vocabulary.departmentTerms.contains(token)) {
                addDistinct(departments, token);
                continue;
            }
            // Place before person. 군 is an honorific and also the suffix of 가평군·음성군·울주군,
            // so reading the name first turns "가평군 디자이너" into a question about somebody
            // called 가평 and abstains on a question that is about a district.
            if (namesAPlace(token, vocabulary, roleMarked)) {
                addDistinct(locations, token);
                continue;
            }
            NameReading name = readName(token, vocabulary);
            if (name != NameReading.NOT_A_NAME) {
                if (vocabulary.someoneIsNamed(personNameStem(token))) {
                    namedRealPerson = true;
                } else if (name == NameReading.HONORIFIC) {
                    namedAbsentPersonWithHonorific = true;
                } else {
                    namedAbsentBareName = true;
                }
            }
        }

        boolean known = false;
        for (String location : locations) {
            if (vocabulary.someoneWorksIn(location)) { known = true; break; }
        }
        String abstainReason = "";
        // An honorific settles it: 씨/님/군/양 point at a person, so a name nobody carries is an
        // answer of nobody — whatever else the sentence mentions.
        if (namedAbsentPersonWithHonorific) {
            abstainReason = "NO_CARD_WITH_REQUESTED_NAME";
        } else if (!locations.isEmpty() && !known && !namedRealPerson) {
            // A query that already named somebody real is not abstained on a stray token's place
            // reading. The analyzer keeps particles attached, so 이메일도 and 회사도 both end in 도
            // and read as provinces; "두미영 회사와 이메일도 알려줘" abstained because of it, while
            // the same sentence without 도 worked. A sentence that only asks about a place nobody
            // works in still abstains, because it names nobody.
            abstainReason = "NO_CARD_IN_REQUESTED_LOCATION";
        }
        // A bare name resolves to nobody only when the retrievers also found nothing — settled by
        // SearchLookupService, which is the first place that knows.
        boolean bareNameAbsent = namedAbsentBareName && !namedRealPerson && abstainReason.isEmpty();

        if (locations.isEmpty() && titles.isEmpty() && departments.isEmpty()
                && abstainReason.isEmpty() && !bareNameAbsent) {
            return SearchFieldConstraintPlan.NONE;
        }
        return SearchFieldConstraintPlan.of(
                locations, titles, departments, known, abstainReason, bareNameAbsent);
    }

    /** How confidently a token points at a person. */
    private enum NameReading { NOT_A_NAME, BARE, HONORIFIC }

    /**
     * Reads a token as somebody's name, or decides it is not one.
     *
     * Two routes, and only two:
     *
     * <ol>
     *   <li>an honorific is attached (정하은씨) — the honorific itself is the evidence;</li>
     *   <li>three bare syllables (정하은) assembled from a surname the data uses and a given name
     *       the data uses. "Built from parts we know, but absent from the roll."</li>
     * </ol>
     *
     * <p>Widening route 2 to "any three syllables starting with a known surname" was measured and
     * reverted: 서커스·조련사·조종사·임원급·공무원 all read as names and conceptual Recall@5 fell
     * 0.660 → 0.630. The name test had quietly become an unknown-word test.
     *
     * <p>A token the data already uses as a place, a job, an employer or a department is not a
     * name. That check is needed on the honorific route too: 군 is an honorific and also the suffix
     * of 음성군·평창군·울주군, so "충청북도 음성군에 있는 프로덕트매니저 찾아줘" abstained on a
     * question that had answers.
     */
    private NameReading readName(String token, SearchFieldVocabulary vocabulary) {
        if (!isAllHangul(token)) return NameReading.NOT_A_NAME;
        if (vocabulary.locationTerms.contains(token) || vocabulary.titleTerms.contains(token)) {
            return NameReading.NOT_A_NAME;
        }
        if (token.length() >= 3 && token.length() <= 5) {
            for (String honorific : NAME_HONORIFICS) {
                if (token.endsWith(honorific) && token.length() - honorific.length() >= 2) {
                    return NameReading.HONORIFIC;
                }
            }
        }
        if (token.length() == 3
                && vocabulary.surnames.contains(token.substring(0, 1))
                && vocabulary.givenNames.contains(token.substring(1))) {
            return NameReading.BARE;
        }
        return NameReading.NOT_A_NAME;
    }

    /** The name without its honorific, which is what the roll is checked against. */
    static String personNameStem(String token) {
        for (String honorific : NAME_HONORIFICS) {
            if (token.endsWith(honorific) && token.length() - honorific.length() >= 2) {
                return token.substring(0, token.length() - honorific.length());
            }
        }
        return token;
    }

    private static boolean isAllHangul(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < '가' || c > '힣') return false;
        }
        return !token.isEmpty();
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

        // 카드의 주소 안에 그 말이 들어 있으면 지명이다. 어휘집은 주소를 띄어쓰기로 쪼갠 뒤
        // 접미사를 **한 겹만** 떼므로 "판교역로"에서 "판교역"까지만 만들어지고 "판교"는 없다.
        // 사람들은 판교라고 부른다 — 어휘집 주석이 판교·강남을 예로 들며 "카드가 그렇게 쓰니까
        // 지명"이라고 적어 둔 그 경우다. 이름·회사·직함으로 쓰이는 말은 바로 위에서 이미
        // 걸러졌으므로, 여기까지 온 두 글자 이상은 주소에 있으면 지명으로 본다.
        if (token.length() >= 2 && vocabulary.someoneWorksIn(token)) return true;

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
