package com.hjp.agent.core

/**
 * The intent vocabularies more than one component has to agree on.
 *
 * Before this file existed the pre-router, the workflow validator and the emulator gateway each
 * carried a private list for the *same* question, and the lists had drifted apart. "지금 몇 시인지
 * 알려줘" was a clock query to two of them and an unparseable sentence to the third; "명함 검색해줘"
 * was a contact read to the validator and an unclassified sentence to the router. Whenever a
 * decision has to be identical in more than one place, its vocabulary belongs here and nowhere else.
 *
 * Every detector is a pure `String -> Boolean` (or `String -> String?`) over the user's own words. No
 * detector knows about scenarios, fixtures, card ids or evaluation case ids, and none of them matches
 * a whole sentence: they match the *form* of a request, so a phrasing that was never written down
 * still classifies the same way.
 */

/**
 * Blanks out the spans of an utterance that are somebody's name.
 *
 * Every action detector in this agent asks "does the sentence contain 문자 / 수정 / 메일 / 일정 …".
 * Asked of the raw sentence, that question cannot tell a command from a person: 문자현 is a name that
 * contains 문자, 서수정 is a name that contains 수정, and both were routed as the action their name
 * happens to spell — the lookup never ran and the following turn had no target.
 *
 * The fix has to be structural, because the set of colliding names is unbounded: 서수정, 문자현,
 * 조회연, 남일정, 김시간 and any future card all collide the same way. So instead of naming people,
 * this names the *positions* where a person appears in this domain — immediately before a
 * person-marking particle (에게/한테/께/님/씨) or immediately before a card object or card field
 * (명함/연락처/회사/이메일/…) — and blanks that span before keyword detection runs.
 *
 * Two properties keep it safe:
 *
 *  1. A span is blanked only when it is **not itself** part of the agent's own vocabulary
 *     ([DOMAIN_TOKENS]). "메일 주소 알려줘" keeps 메일, because 메일 is a domain token; "문자현 명함"
 *     loses 문자현, because that is not.
 *  2. Blanking a span that contains no keyword is a no-op, since only keyword detection reads the
 *     masked text. Everything that needs the real words — the recipient name, the body, the dates,
 *     the anaphora — keeps reading the original.
 */
object PersonNameMask {

    /** The character a blanked name is replaced with. It cannot occur inside any keyword. */
    private const val BLANK = '·'

    /**
     * The agent's own vocabulary. A span made of these is never a name, so it is never blanked.
     *
     * It contains no person's name and never will: adding one would be exactly the per-name
     * exception this type exists to avoid.
     */
    val DOMAIN_TOKENS: Set<String> = setOf(
        // people, referred to generically
        "사람", "그사람", "그분", "이분", "저분", "본인", "담당자", "고객", "대표", "상대방", "우리", "저희",
        // card objects and card fields
        "명함", "연락처", "카드", "회사", "직함", "직급", "직책", "부서", "업종", "지역", "메모",
        "이메일", "메일", "주소", "번호", "전화", "전화번호", "휴대폰", "핸드폰", "이름", "홈페이지", "웹사이트",
        // actions
        "문자", "메시지", "일정", "캘린더", "스케줄", "회의", "미팅", "약속",
        "작성", "검색", "조회", "수정", "변경", "삭제", "등록", "생성",
        // time
        "오늘", "내일", "모레", "지금", "현재", "시간", "시각", "날짜", "요일", "오전", "오후",
        // generic nouns that share a position with names
        "자료", "내용", "제목", "본문", "장소", "정보", "목록", "결과", "사항", "파일", "문서", "여기", "거기",
    )

    /** Words that only ever follow a person. */
    private const val PERSON_PARTICLES = "에게|한테|께|님|씨"

    /** Card objects and card fields. A name sits directly in front of one of these. */
    private const val OWNED_NOUNS =
        "명함|연락처|카드|회사|직함|직급|직책|부서|업종|지역|메모|이메일|메일주소|전화번호|휴대폰|핸드폰|주소|홈페이지|웹사이트|이름"

    /**
     * A whole Hangul run of two to six syllables sitting in a name position.
     *
     * The lookbehind and the length ceiling together mean only a *complete, short* run is ever
     * considered: a longer compound such as 품질관리팀장에게 is left alone rather than half-blanked.
     */
    private val NAME_BEFORE_PARTICLE =
        Regex("(?<![가-힣])([가-힣]{2,6})(?=(?:$PERSON_PARTICLES)(?:$NOUN_PARTICLES)?(?![가-힣]))")
    /**
     * Particles that attach to the owned noun. Without them "명함을 찾아줘" and "명함좀 찾아줘" stopped
     * matching, so the name in front of them stayed unmasked and the collision came straight back.
     */
    private const val NOUN_PARTICLES = "을|를|은|는|이|가|의|도|만|좀|과|와|에|으로|로"

    private val NAME_BEFORE_OWNED_NOUN =
        Regex("(?<![가-힣])([가-힣]{2,6})(?=\\s*(?:$OWNED_NOUNS)(?:$NOUN_PARTICLES)?(?![가-힣]))")

    /**
     * Noun endings that make a span a job, a team or an organisation rather than a person.
     *
     * The name-position patterns above cannot tell 품질팀장에게 from 빙현우에게: both are a short
     * Hangul run in front of 에게. Treating the first as a name is how "품질팀장에게 메일 작성해줘"
     * opened a mail addressed to whoever currently holds that job — a person the user never named.
     * A role is not an identifier: two people can share it, and next quarter somebody else has it.
     *
     * A closed list of *people* was never available — the set of colliding names is unbounded, which
     * is the whole reason [PersonNameMask] exists — but the set of Korean role and organisation
     * endings is small and genuinely closed, and it describes the shape of a common noun rather than
     * any individual. So this is a rule about nouns, not an exception list of names.
     *
     * Recognising them here does not make the words unusable: they remain ordinary search
     * constraints ("박민수 영업팀장"). What changes is that a bare one can no longer *be* the target
     * of an irreversible action; the turn asks which person instead, exactly as it already did for
     * "전략기획 부서에" and "한빛물산에".
     */
    private val ROLE_ENDINGS: List<String> = listOf(
        "팀장", "부장", "과장", "차장", "대리", "사원", "이사", "상무", "전무", "실장", "소장",
        "본부장", "센터장", "파트장", "매니저", "책임", "선임", "수석", "주임", "반장", "공장장",
        "원장", "사장", "회장", "대표", "지점장", "점장", "단장", "국장", "처장", "총괄", "리더",
        "담당자", "관리자", "책임자", "실무자",
    )

    /** Organisation endings. 은행/증권 and the rest are what a company is called, not who. */
    private val ORGANISATION_ENDINGS: List<String> = listOf(
        "회사", "물산", "전자", "산업", "기업", "그룹", "상사", "공업", "제약", "화학", "건설",
        "은행", "증권", "보험", "재단", "협회", "조합", "공사", "공단", "연구소", "연구원",
        "법인", "상회", "무역", "네트웍스", "시스템즈",
    )

    /**
     * Organisational units. Only endings of two syllables or more, plus 팀.
     *
     * A one-syllable ending such as 실 or 과 sits at the end of ordinary given names often enough
     * that excluding on it would misclassify people, and the cost of that mistake — a person who can
     * no longer be addressed — is worse than the cost this rule exists to prevent.
     */
    private val UNIT_ENDINGS: List<String> = listOf(
        "팀", "부서", "본부", "사업부", "센터", "지사", "지점", "사업소",
    )

    /**
     * True when [span] is a role, an organisational unit or an organisation.
     *
     * The prefix requirement is what keeps an address form a name: 품질팀장 is 품질 + 팀장 and names
     * a job, while 김대표 is one syllable plus 대표 and is how somebody is addressed, so it stays a
     * candidate name and the store gets to decide.
     */
    fun namesARoleOrOrganisation(span: String): Boolean {
        val value = span.trim()
        return (ROLE_ENDINGS + ORGANISATION_ENDINGS + UNIT_ENDINGS).any { ending ->
            value.length - ending.length >= 2 && value.endsWith(ending)
        }
    }

    /** A span that may stand for a person: not agent vocabulary, and not a role or an organisation. */
    private fun couldNameAPerson(span: String): Boolean =
        span !in DOMAIN_TOKENS && !namesARoleOrOrganisation(span)

    /** True when [token] is one of the agent's own words rather than anybody's name. */
    fun isAgentVocabulary(token: String): Boolean = token in DOMAIN_TOKENS

    /**
     * The person-name spans this utterance contains, in order of appearance.
     *
     * The mask already has to find them; exposing them lets the turn-target rules ask "does this
     * sentence name somebody?" without a second, differently-behaved name finder.
     */
    fun nameSpans(text: String): List<String> {
        val spans = linkedSetOf<String>()
        listOf(NAME_BEFORE_PARTICLE, NAME_BEFORE_OWNED_NOUN).forEach { pattern ->
            pattern.findAll(text).forEach { match ->
                val span = match.groupValues[1]
                if (couldNameAPerson(span)) spans += span
            }
        }
        return spans.toList()
    }

    /**
     * Particles that follow a person's name when a sentence merely brings them up.
     *
     * Kept out of [nameSpans] on purpose. Masking has to be conservative — blanking a run that was
     * not a name destroys the keyword the router needs — so it only trusts positions where a person
     * is the sole possibility. Mentions have the opposite risk: a missed one leaves the previous
     * target in focus and a later anaphor opens a message to the wrong person.
     */
    /**
     * Forms that introduce a person outright.
     *
     * 이라는/라는/이란/란 is the quotative attributive — "남지후라는 분" — and a name written before a
     * spaced honorific ("남지후 씨는") is equally unambiguous. Both are trusted without further
     * evidence.
     */
    private const val STRONG_MENTION_PARTICLES = "이라는|라는|이란|란"

    /**
     * Forms that *may* follow a person and follow almost everything else too.
     *
     * 도/은/는/랑/하고/과/와 attach to any noun in Korean, so trusting them alone made two sentences
     * in five retire the session's target: 회의록도, 일정은, 너울건설도 and 미뤄도 all parse as "a name
     * plus a particle". They are therefore only read as a person when the sentence is visibly about
     * one.
     */
    private const val WEAK_MENTION_PARTICLES = "도|은|는|이랑|랑|하고|과|와"

    /** Evidence the sentence is talking about a person at all. */
    private val PERSON_CONTEXT = Regex("분|사람|씨|님|아세요|아는|알아|알고|기억|담당|소속")

    /**
     * Personal names run two to four syllables in practice. Longer runs in the same position are
     * compounds — 영업본부장, 품질관리팀 — which separates a person from a job title without needing a
     * list of either.
     */
    private val NAME_BEFORE_STRONG_PARTICLE =
        Regex("(?<![가-힣])([가-힣]{2,4})(?=\\s*(?:$STRONG_MENTION_PARTICLES)(?![가-힣]))")

    private val NAME_BEFORE_WEAK_PARTICLE =
        Regex("(?<![가-힣])([가-힣]{2,4})(?=\\s*(?:$WEAK_MENTION_PARTICLES)(?![가-힣]))")

    /**
     * Honorifics and datives written with a space: "남지후 씨는", "남지후 님께".
     *
     * The honorific itself carries a particle more often than not, so one is allowed after it —
     * without that, 씨 followed by 는 failed the "nothing Hangul after" check and the mention was
     * missed.
     */
    private val NAME_BEFORE_SPACED_PERSON_PARTICLE =
        Regex(
            "(?<![가-힣])([가-힣]{2,4})" +
                "(?=\\s+(?:$PERSON_PARTICLES)(?:$NOUN_PARTICLES)?(?![가-힣]))",
        )

    fun mentionSpans(text: String): List<String> {
        val spans = linkedSetOf<String>()
        spans += nameSpans(text)
        val patterns = buildList {
            add(NAME_BEFORE_STRONG_PARTICLE)
            add(NAME_BEFORE_SPACED_PERSON_PARTICLE)
            if (PERSON_CONTEXT.containsMatchIn(text)) add(NAME_BEFORE_WEAK_PARTICLE)
        }
        patterns.forEach { pattern ->
            pattern.findAll(text).forEach { match ->
                val span = match.groupValues[1]
                if (couldNameAPerson(span)) spans += span
            }
        }
        return spans.toList()
    }

    /** [text] with every person-name span blanked. Intended for keyword detection only. */
    fun maskNames(text: String): String {
        var masked = text
        listOf(NAME_BEFORE_PARTICLE, NAME_BEFORE_OWNED_NOUN).forEach { pattern ->
            masked = pattern.replace(masked) { match ->
                val span = match.groupValues[1]
                // A role or an organisation is not a name, so blanking it would delete a legitimate
                // search constraint from the text every keyword rule reads.
                if (couldNameAPerson(span)) BLANK.toString().repeat(span.length) else span
            }
        }
        return masked
    }

    /** True when [keyword] occurs somewhere that is not part of a person's name. */
    fun containsOutsideNames(text: String, keyword: String): Boolean =
        maskNames(text).contains(keyword, ignoreCase = true)
}

/**
 * "지금은 언제인가", asked directly.
 *
 * The second half of [isDirectQuery] is the part that matters: a time expression inside a calendar,
 * message or card request belongs to *that* request. "김민수에게 현재 시간을 알려주는 문자 작성해줘"
 * is a compose turn that happens to mention the clock, and answering it with the time would drop the
 * action the user actually asked for.
 */
object CurrentDateTimeIntent {

    /**
     * The turn asks the clock and nothing more specific competes for it.
     *
     * Both halves read the name-masked utterance, so a person called 김시간 is a person and not a
     * clock question, and 남일정 does not turn a clock question into a calendar request.
     */
    fun isDirectQuery(text: String): Boolean {
        val masked = PersonNameMask.maskNames(text)
        return CLOCK_QUESTION.containsMatchIn(masked) && !COMPETING_ACTION.containsMatchIn(masked)
    }

    /** A more specific action owns this sentence, whatever time words it also contains. */
    fun hasCompetingAction(text: String): Boolean =
        COMPETING_ACTION.containsMatchIn(PersonNameMask.maskNames(text))

    /**
     * Ordinary Korean ways of asking for the current date or time.
     *
     * The last alternative deliberately accepts a bare "날짜 알려줘": with [COMPETING_ACTION] filtering
     * the sentence first, a bare date/time read request cannot belong to anything else.
     */
    const val CLOCK_QUESTION_PATTERN: String =
        "(?:현재|지금|오늘)\\s*(?:날짜|시간|시각|일자)" +
            "|(?:지금|현재)\\s*몇\\s*시" +
            "|몇\\s*시\\s*(?:야|인지|인가|입니까|예요|에요|죠|지)" +
            "|오늘\\s*(?:며칠|무슨\\s*요일|어떤\\s*요일|무슨\\s*날)" +
            "|(?:날짜|시각|시간)\\s*(?:은|는|을|를|좀)?\\s*(?:알려|확인|조회)"

    /** Everything this agent can do that owns a time expression of its own. */
    const val COMPETING_ACTION_PATTERN: String =
        "(?i)일정|캘린더|스케줄|회의|미팅|약속|메일|이메일|문자|메시지|sms|명함|연락처|수정|변경|바꿔|고쳐"

    private val CLOCK_QUESTION = Regex(CLOCK_QUESTION_PATTERN)
    private val COMPETING_ACTION = Regex(COMPETING_ACTION_PATTERN)
}

/**
 * Reading the contact store: a card object plus a verb that retrieves or shows it.
 *
 * Two strengths, because two different questions need them. [isCardSearch] is "go to the store and
 * look" — it is what tells a question about the conversation apart from a new lookup. [isCardRead] is
 * the wider "let me see a card", which is what a route label must recognise: "명함 보여줘",
 * "연락처 확인해줘" and "명함 검색해줘" are one intent worded three ways.
 */
object ContactReadIntent {
    val CARD_OBJECTS: List<String> = listOf("명함", "연락처", "카드")

    /** Verbs that go to the store. */
    val SEARCH_VERBS: List<String> = listOf("찾아", "찾기", "찾아봐", "검색", "조회")

    /** Verbs that ask to see a record, which may already be in focus. */
    val SHOW_VERBS: List<String> = listOf("보여", "알려", "확인")

    /**
     * Predicate stems that mean "retrieve it" or "put it in front of me".
     *
     * Stems, not sentences: the inflection, the politeness level and the sentence ending are matched
     * separately by [REQUEST_FORM], so 보여줘 / 보여주세요 / 보여줄래 / 띄워줘 / 띄워 주실래요 are one
     * rule rather than five entries. Adding a single word here — which is how "띄워" would have been
     * "fixed" — was explicitly not the answer: the failure was that only a closed list existed.
     */
    val DISPLAY_STEMS: List<String> = listOf(
        "보여", "띄워", "띄우", "열어", "열람", "확인", "알려", "조회", "검색", "찾아", "찾기", "검색해", "불러",
        "리스트업", "출력",
    )

    /**
     * The forms a Korean request takes. A read intent needs one of these, which is what keeps a
     * bare noun phrase or a quotation from being read as a command.
     */
    val REQUEST_FORM: Regex = Regex(
        "(?:줘|주세요|주시겠|주실래|줄래|줄 수 있|해줘|해 줘|해주세요|해주실|해봐|해 봐|봐줘|봐 줘|" +
            "볼 수 있|보자|하자|해라|해줄래|부탁|바랍니다|원해|싶어|싶은데|주라|줍쇼)",
    )

    fun hasCardObject(text: String): Boolean =
        PersonNameMask.maskNames(text).let { m -> CARD_OBJECTS.any(m::contains) }

    fun hasSearchVerb(text: String): Boolean =
        PersonNameMask.maskNames(text).let { m -> SEARCH_VERBS.any(m::contains) }

    fun hasShowVerb(text: String): Boolean =
        PersonNameMask.maskNames(text).let { m -> SHOW_VERBS.any(m::contains) }

    /**
     * A display predicate used as a request in this sentence.
     *
     * Structure rather than vocabulary: a stem that means "show/retrieve", inflected into a request
     * form, outside every person-name span. "봉예람 연락처 좀 띄워줘" and "봉예람 명함 열어볼 수 있어?"
     * both satisfy it; "띄워쓰기" and a quoted "'띄워줘'라고 했잖아" do not, because the quotation is
     * settled before this is consulted.
     */
    fun hasDisplayRequest(text: String): Boolean {
        val masked = PersonNameMask.maskNames(text)
        val stem = DISPLAY_STEMS.firstOrNull(masked::contains) ?: return false
        val tail = masked.substring(masked.indexOf(stem) + stem.length)
        return REQUEST_FORM.containsMatchIn(tail) || tail.trimEnd('.', '!', '?', ' ', '\u3002').isEmpty() ||
            masked.trimEnd('.', '!', '?', ' ').endsWith("?")
    }

    fun hasReadVerb(text: String): Boolean = hasSearchVerb(text) || hasShowVerb(text)

    /** "명함 검색해줘", "연락처 조회해줘" — go and look. */
    fun isCardSearch(text: String): Boolean = hasCardObject(text) && hasSearchVerb(text)

    /**
     * "명함 보여줘", "연락처 확인해줘", "연락처 좀 띄워줘", "명함 열어볼 수 있어?" — show me a card.
     *
     * The second disjunct is the general rule; the first is kept because a bare "명함 검색" with no
     * request ending is still unambiguously a lookup.
     */
    fun isCardRead(text: String): Boolean =
        hasCardObject(text) && (hasReadVerb(text) || hasDisplayRequest(text)) ||
            // A person marker is an unambiguous contact target even when the user omits the
            // literal card noun: "우성씨 찾아줘" is a contact lookup, not an arbitrary search.
            // Unlike a shape-only name heuristic this reuses the conservative name-position
            // detector, so titles and organisations do not become contact targets.
            (hasSearchVerb(text) && PersonNameMask.nameSpans(text).isNotEmpty())

    /** "연락처 목록 보여줘" — the whole store rather than one person. */
    fun isListRequest(text: String): Boolean {
        val masked = PersonNameMask.maskNames(text)
        return isCardRead(text) && LIST_MARKERS.any(masked::contains)
    }

    private val LIST_MARKERS = listOf("목록", "리스트", "전체", "다 보여", "모두", "전부")
}

/**
 * Editing a stored card.
 *
 * The verb list was previously written out three times with three different memberships, so "고쳐줘"
 * was an edit to the emulator gateway and an unclassified sentence to both the router and the
 * validator.
 */
object CardUpdateIntent {
    val UPDATE_VERBS: List<String> = listOf("수정", "변경", "바꿔", "바꾸", "고쳐", "고치", "업데이트")

    /** Verbs that empty a field rather than set it. */
    val CLEAR_VERBS: List<String> = listOf("지워", "삭제", "비워", "없애")

    /** Reads the name-masked utterance, so 서수정 is a person rather than an edit command. */
    fun hasUpdateVerb(text: String): Boolean =
        PersonNameMask.maskNames(text).let { m -> UPDATE_VERBS.any(m::contains) }

    /** True when a clear verb occurs outside every person-name span. */
    fun hasClearVerb(text: String): Boolean =
        PersonNameMask.maskNames(text).let { m -> CLEAR_VERBS.any(m::contains) }

    /** "VIP로 수정", "영업팀장으로 바꿔" — a concrete new value, not just a field name. */
    const val UPDATE_VALUE_PATTERN: String =
        """\S+\s*(?:으로|로|라고|이라고)\s*(?:수정|변경|바꿔|바꾸|고쳐|고치|업데이트)"""
}

/**
 * Words that point at the contact the conversation already has in focus.
 *
 * This is the *only* list that may promote a remembered contact into the target of the current turn,
 * so it is deliberately restricted to expressions that can mean nothing else.
 */
object ContactAnaphora {
    val MARKERS: List<String> = listOf(
        "그 사람", "그사람", "그분", "그 분", "이 사람", "이사람", "이분", "이 분",
        "저분", "저 분", "걔", "그에게", "그 회사", "그 연락처", "그 명함", "해당 연락처", "해당 명함",
    )

    fun isPresent(text: String): Boolean = MARKERS.any(text::contains)
}

/**
 * A third-party destination this agent has no integration with.
 *
 * Recognising the *destination* is what stops an ordinary noun inside the request from deciding the
 * route: "회의록 요약해서 슬랙에 올려줘" contains 회의, but the thing that makes it undoable is Slack,
 * not the word 회의. Returning the app's name lets the answer say plainly which integration is
 * missing instead of offering a generic capability blurb.
 */
/**
 * Whether an utterance carries the slots the action tools cannot run without.
 *
 * A request that names an action but leaves out what the action needs — a mail with no recipient, a
 * meeting with no time, an edit with no card — has to become a question. The alternative is what the
 * agent used to do: fall through to a general answer that sounds helpful, runs no tool, and is
 * indistinguishable from success to any metric that counts side effects.
 */
/**
 * The nouns that make a request an action of a given kind.
 *
 * These lived in two places with different contents: the pre-router treated 회의/미팅/약속 as calendar
 * requests, the workflow validator recognised only 일정/캘린더. So "다음 주에 회의 잡아줘" was routed as
 * ACTION_CALENDAR and simultaneously judged by the workflow not to be a calendar request at all,
 * which is how a meeting with no stated time passed as a complete request. A decision made in two
 * places needs one vocabulary.
 */
/**
 * Whether an utterance states a calendar date, a time of day, or both.
 *
 * Two separate facts, because they are two separate slots. The workflow used to decide "this states a
 * time" with one pattern — 오전/오후, digits, 시 — so `14시`, `2시`, `정오`, `자정`, `새벽 3시`,
 * `저녁 7시` and `14:30` were all invisible to it. Once a calendar request without a time had to ask
 * for one, that turned a stream of complete requests into questions. The mirror mistake is reading a
 * *date* as a time: "내일 회의 잡아줘" names a day and nothing else.
 *
 * Read on the name-masked utterance by callers that need it, so a contact called 정오영 does not state
 * a time.
 */
object TemporalSlotIntent {

    /** Hours that exist. 25시 and 오후 13시 are not times the tool could be given. */
    private const val HOUR = "(?:[01]?\\d|2[0-3])"

    /**
     * 오후 2시, 오전 9시 30분, 오후 2시 반.
     *
     * The hour is 1-12: with a meridiem, 13시 is not a time anyone can act on, and accepting it would
     * report a slot the tool cannot use.
     */
    private val MERIDIEM_CLOCK =
        Regex("(?:오전|오후)\\s*(?:1[0-2]|[1-9])\\s*시(?:\\s*(?:[0-5]?\\d\\s*분|반))?")

    /**
     * 14시, 2시, 9시 30분, 9시 반 — with no meridiem.
     *
     * The meridiem lookbehinds matter: without them "오후 13시" was rejected by [MERIDIEM_CLOCK] and
     * then accepted here as a bare 13시, so an hour that cannot exist with a meridiem still counted
     * as a stated time.
     */
    private val BARE_CLOCK = Regex(
        "(?<![\\d:])(?<!오전)(?<!오후)(?<!오전\\s)(?<!오후\\s)" +
            "$HOUR\\s*시(?:\\s*(?:[0-5]?\\d\\s*분|반))?",
    )

    /** 14:30, 09:05. */
    private val COLON_CLOCK = Regex("(?<!\\d)$HOUR:[0-5]\\d(?!\\d)")

    /** 정오, 자정 — a time of day with no number at all. */
    private val NAMED_CLOCK = Regex("(?:정오|자정)")

    /** 새벽 3시, 저녁 7시, 아침 8시, 밤 11시 — a part of the day qualifying an hour. */
    private val DAYPART_CLOCK = Regex("(?:새벽|아침|점심|저녁|밤)\\s*$HOUR\\s*시(?:\\s*(?:[0-5]?\\d\\s*분|반))?")

    /** 2027년 3월 4일, 3월 4일, 9월 1일. */
    private val ABSOLUTE_DATE = Regex("(?:\\d{4}\\s*년\\s*)?\\d{1,2}\\s*월\\s*\\d{1,2}\\s*일")

    /** 내일, 다음 주, 금요일 — a day named without digits. */
    private val RELATIVE_DATE = Regex(
        "(?:오늘|내일|모레|글피|이번\\s*주|다음\\s*주|담주|이번\\s*달|다음\\s*달|" +
            "월요일|화요일|수요일|목요일|금요일|토요일|일요일|주말)",
    )

    /** True when the utterance says *what time*. */
    fun hasTime(text: String): Boolean {
        val masked = PersonNameMask.maskNames(text)
        return NAMED_CLOCK.containsMatchIn(masked) ||
            DAYPART_CLOCK.containsMatchIn(masked) ||
            MERIDIEM_CLOCK.containsMatchIn(masked) ||
            COLON_CLOCK.containsMatchIn(masked) ||
            BARE_CLOCK.containsMatchIn(masked)
    }

    /** True when the utterance says *which day*. */
    fun hasDate(text: String): Boolean {
        val masked = PersonNameMask.maskNames(text)
        return ABSOLUTE_DATE.containsMatchIn(masked) || RELATIVE_DATE.containsMatchIn(masked)
    }
}

object ActionVocabulary {
    val CALENDAR: List<String> = listOf("일정", "캘린더", "스케줄", "미팅", "회의", "약속")
    val COMPOSE: List<String> = listOf("메일", "이메일", "문자", "sms")
}

object RequiredSlotIntent {

    /** Digits with a calendar unit: "2027년", "3월 4일", "오후 2시", "14시 30분". */
    private val EXPLICIT_TIME = Regex("\\d{1,4}\\s*(?:년|월|일|시|분)")

    /** Day words that name a day without digits. */
    private val RELATIVE_DAY = Regex(
        "(?:오늘|내일|모레|글피|어제|이번\\s*주|다음\\s*주|담주|이번\\s*달|다음\\s*달|" +
            "월요일|화요일|수요일|목요일|금요일|토요일|일요일|주말)",
    )

    /** Times of day that pin an hour without digits. */
    private val NAMED_TIME = Regex("(?:정오|자정|새벽|아침|점심|저녁|밤)")

    /** True when the sentence says *when*, in any of the forms the schedule tool can be given. */
    fun hasTimeSlot(text: String): Boolean =
        EXPLICIT_TIME.containsMatchIn(text) ||
            RELATIVE_DAY.containsMatchIn(text) ||
            NAMED_TIME.containsMatchIn(text)

    /**
     * True when the sentence says *who*.
     *
     * Uses the broad mention detector rather than the masking one: a recipient this misses becomes a
     * turn that acts on whoever was last in focus.
     */
    fun hasPersonSlot(text: String): Boolean = PersonNameMask.mentionSpans(text).isNotEmpty()
}

object ExternalIntegrationIntent {
    private val APPS: List<Pair<String, String>> = listOf(
        "슬랙" to "슬랙", "slack" to "슬랙",
        "노션" to "노션", "notion" to "노션",
        "카카오톡" to "카카오톡", "카톡" to "카카오톡",
        "텔레그램" to "텔레그램", "telegram" to "텔레그램",
        "디스코드" to "디스코드", "discord" to "디스코드",
        "팀즈" to "팀즈", "teams" to "팀즈",
        "잔디" to "잔디",
        "인스타그램" to "인스타그램", "instagram" to "인스타그램",
        "페이스북" to "페이스북", "facebook" to "페이스북",
        "드롭박스" to "드롭박스", "dropbox" to "드롭박스",
        "구글 드라이브" to "구글 드라이브", "구글드라이브" to "구글 드라이브",
        "에어테이블" to "에어테이블", "트렐로" to "트렐로", "지라" to "지라", "jira" to "지라",
    )

    /** The unsupported destination this sentence names, or null when it names none. */
    fun namedApp(text: String): String? {
        val lower = text.lowercase()
        return APPS.firstOrNull { (needle, _) -> lower.contains(needle) }?.second
    }
}

/**
 * Whether the sentence *performs* a request or merely talks about one.
 *
 * The agent's capability rules are about what the user is asking it to do. Applied before this
 * question is settled they fire on any sentence that merely contains the vocabulary, so
 * "내가 좀 전에 명함 지워달라고 했나?" — a question about a past request — came back as "명함 삭제는
 * 지원하지 않습니다". Held-out v3 caught it; eleven ordinary paraphrases reproduce it.
 *
 * Five ways a sentence can name an action without asking for it, each matched by shape rather than
 * by a list of sentences.
 */
object ReportedSpeechIntent {

    /** Direct quotation: the action word belongs to the quoted span, not to this turn. */
    val QUOTATION = Regex("[‘“\"']([^’”\"']{2,120})[’”\"']")

    /** "…한 적 없어", "…라는 뜻은 아니야" — the user is denying the request, not making it. */
    val NEGATION = Regex(
        "(?:한\\s*적\\s*없|하지\\s*않았|안\\s*했|아니야|아니에요|아닙니다|" +
            "뜻은\\s*아니|말은\\s*아니|요청은\\s*아니|얘기는\\s*아니)",
    )

    /** "만약 …하면", "…한다면" — a condition, not an instruction. */
    val HYPOTHETICAL = Regex("(?:만약|만일|가정|혹시).{0,20}(?:면|다면|경우)|(?:하면|한다면|된다면)\\s*(?:어떻|어찌|무슨|뭐)")

    /** "…할 수 있어?", "…기능이 있나요?" — asking what the agent can do. */
    val CAPABILITY_QUESTION = Regex(
        "(?:할\\s*수\\s*있|가능해|가능한가|기능(?:이|은)?\\s*(?:있|되|지원)|지원(?:하나|해|되나))" +
            "(?:\\?|나요|어\\?|니|습니까|을까|는지)?",
    )

    /**
     * The agent, named as the subject of the sentence.
     *
     * This is what separates "너는 명함을 띄워줄 수 있어?" — a question about what the agent can do —
     * from "명함 열어볼 수 있어?", which is an ordinary polite request to be shown a card. Korean
     * marks the difference with the subject, not with the verb: both end in 수 있어, so a rule that
     * looked only at the ability form would turn every polite request into a capability question and
     * the agent would stop doing anything it was asked politely.
     */
    val AGENT_SUBJECT = Regex(
        "(?:^|[\\s,\\u3002])(?:너는|넌|너가|네가|니가|당신은|당신이|자네는|" +
            "에이전트는|에이전트가|어시스턴트는|어시스턴트가|비서는|비서가|봇은|봇이|챗봇은|챗봇이|" +
            "(?i:ai)는|(?i:ai)가)",
    )

    /** "…할 수 있", "…가능" — the ability form, in any inflection. */
    val ABILITY_FORM = Regex("(?:수\\s*있|가능)")

    /** The sentence is a question. Either the mark, or an interrogative ending. */
    val QUESTION_FORM = Regex(
        "[?\\uFF1F]\\s*$|(?:나요|까요|습니까|는지|은가요|인가요|어\\?|니\\?)\\s*[?\\uFF1F]?\\s*$",
    )

    /**
     * "너는 …할 수 있어?" — a question about the agent's abilities, not a request to use them.
     *
     * All three parts are required, so an ordinary request that happens to contain one of them keeps
     * its own route.
     */
    fun asksWhetherTheAgentCan(text: String): Boolean {
        val trimmed = text.trim()
        return AGENT_SUBJECT.containsMatchIn(trimmed) &&
            ABILITY_FORM.containsMatchIn(trimmed) &&
            QUESTION_FORM.containsMatchIn(trimmed)
    }

    /** Any of the six, including the recall form defined by [QuotedRecallIntent]. */
    fun isAboutAnActionRatherThanARequest(text: String): Boolean =
        QuotedRecallIntent.isPresent(text) ||
            QUOTATION.containsMatchIn(text) ||
            NEGATION.containsMatchIn(text) ||
            HYPOTHETICAL.containsMatchIn(text) ||
            CAPABILITY_QUESTION.containsMatchIn(text) ||
            asksWhetherTheAgentCan(text)
}

/**
 * Recalling a past instruction instead of issuing one.
 *
 * A fixed list of phrasings could not keep up with Korean's past-tense interrogative endings — it
 * covered "했었지" but not "했던가" — so the shape is matched instead: a speech or request verb in the
 * past tense followed by a confirmation-seeking ending.
 */
object QuotedRecallIntent {
    const val PATTERN: String =
        "(?:말했|얘기했|이야기했|요청했|부탁했|시켰|물어봤|물었|달라고\\s*했|라고\\s*했|하라고\\s*했|해달라고\\s*했)" +
            "(?:었)?(?:지|나|니|냐|던가|던데|잖아)" +
            "|(?:말한|얘기한|요청한|부탁한|시킨|물어본|물은)\\s*적" +
            // The experiential frame: a quotative complementiser followed by "한 적".
            //
            // "일정 만들어달라고 한 적 있었나?" is the same question as "…해달라고 했나?", and the
            // ending-based half above cannot see it: the past tense sits on 있었나, not on the speech
            // verb, which stays in the adnominal 한. Without this the sentence was classified
            // ACTION_CALENDAR and the agent created the event the user was asking whether they had
            // ever requested.
            "|(?:달라고|해달라고|하라고|라고|자고|냐고)\\s*(?:한|했던)\\s*적"

    private val REGEX = Regex(PATTERN)

    fun isPresent(text: String): Boolean = REGEX.containsMatchIn(text)
}
