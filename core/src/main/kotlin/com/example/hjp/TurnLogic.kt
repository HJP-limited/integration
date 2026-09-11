package com.example.hjp

import com.example.hjp.agent.AgentSession
import com.example.hjp.agent.ChatEngineProvider
import com.example.hjp.agent.ConversationalFollowup
import com.example.hjp.agent.EMPTY_LLM_RESPONSE
import com.example.hjp.agent.LlmRole
import com.example.hjp.agent.tools.ToolRegistry
import com.example.hjp.data.BusinessCardEntity
import com.example.hjp.search.CardSearchHit
import com.example.hjp.search.CardSearchResponse
import com.example.hjp.search.CardSearchService

data class ChatResult(
    val answer: String,
    val search: CardSearchResponse?,
    /**
     * 어느 처리 경로로 갔는지 — 진단 로그와 실기기/노트북 대조에 쓴다.
     * hybrid_server.py 의 "route" 필드와 같은 이름을 쓴다(양쪽 비교가 목적).
     */
    val route: String? = null,
    val error: String? = null,
    val modelLabel: String? = null,
    /** 재검색 없이 직전 결과를 근거로 답한 턴인가(정정/확인/복수지시 발화). */
    val conversationalFollowup: Boolean = false,
    /** LLM 이 무관하다고 판단해 카드 목록에서 뺀 사람들. */
    val filteredOut: List<String> = emptyList(),
)

/** 조건에 맞는 사람이 하나도 없을 때 쓰는 고정 문구. LLM 도 이 문구로 답하도록 지시한다. */
private const val NO_MATCH_PHRASE = "조건에 해당하는 명함을 찾지 못했습니다."

/**
 * 위 고정 문구 외에, LLM이 다른 말투로 "못 찾았다"고 답하는 경우도 거절로 인정한다.
 * 실측(스트레스 테스트): "김철수 전화번호 알려줘"에 "김철수 전화번호는 찾지 못했습니다."
 * 라고 답했는데 NO_MATCH_PHRASE 와 정확히 안 겹쳐서 거절로 인식을 못 했고, 그 결과
 * "못 찾았다"는 답변 밑에 관계없는 카드가 그대로 남아있었다.
 * "없습니다"는 넣지 않는다 — "안정우는 나이가 없습니다"처럼 '그 사람은 있는데 그
 * 필드가 없다'는 정상 답변까지 거절로 오인해서 존재하는 카드를 지워버리기 때문이다.
 */
internal val REJECTION_MARKERS = listOf(NO_MATCH_PHRASE, "찾지 못했", "찾을 수 없", "찾지못했", "찾을수없")

/**
 * 명함 검색과 무관한 자기참조 질문("너는 누구야?") — 결정적으로 우회한다.
 * 실측: 프롬프트 규칙에만 맡기면 "너"를 명함 속 인물로 오인해서 관계없는 사람 이름을
 * 그대로 답했다(예: "너는 누구야?" -> "유유진").
 */
private val SELF_REFERENCE_PATTERNS = listOf(
    "너는 누구", "너 누구", "너는 뭐", "너 뭐야", "너 뭐하는", "너 몇 살", "너는 몇 살",
    "너는 ai", "너 ai", "너는 사람이야", "너는 로봇", "당신은 누구", "니 정체", "네 정체",
)
private const val SELF_REFERENCE_ANSWER = "저는 명함 검색을 도와드리는 온디바이스 AI 어시스턴트입니다."

internal fun isSelfReferenceQuestion(question: String): Boolean {
    val q = question.trim().lowercase()
    if (q.isEmpty()) return false
    return SELF_REFERENCE_PATTERNS.any { it in q }
}
/**
 * "너 뭐 할 줄 알아?" 같은 기능 질문 — 자기참조와 같은 이유로 결정적으로 우회한다.
 * 실측(전량 채점): 검색에 태우면 컨텍스트의 1등을 답으로 뱉었다("너 뭐 할 줄 알아?" -> "이현",
 * 카드 1장). 무관 요청 카드 억제 실패 1건이 이 계열이었다.
 *
 * **자기참조보다 먼저 판정해야 한다** — "너는 뭐 할 줄 알아?" 는 SELF_REFERENCE_PATTERNS 의
 * "너는 뭐" 에도 걸리는데, 그쪽이 먼저 잡으면 정체만 답하고 기능은 말하지 않는다.
 */
private val CAPABILITY_PATTERNS = listOf(
    "뭐 할 줄", "뭘 할 줄", "무엇을 할 줄", "뭐할 줄", "뭘할 줄",
    "뭐 할 수", "뭘 할 수", "무엇을 할 수", "할 수 있는 게", "할 수 있는게",
    "어떤 기능", "기능이 뭐", "기능 뭐", "뭐 도와", "뭘 도와", "어떻게 쓰는",
)

internal fun isCapabilityQuestion(question: String): Boolean {
    val q = question.trim().lowercase()
    if (q.isEmpty()) return false
    return CAPABILITY_PATTERNS.any { it in q }
}

/**
 * 기능 안내 문구. 검색은 이 화면 자체의 기능이라 항상 맨 앞에 두고, 나머지는 등록된
 * 도구에서 파생한다([ToolRegistry.capabilityLabels]). 도구가 늘면 여기를 고칠 필요가 없다.
 */
internal fun buildCapabilityAnswer(toolLabels: List<String>): String {
    val lines = mutableListOf("이름·회사·지역·직함으로 명함을 찾습니다.")
    lines += toolLabels
    return "저는 명함 검색을 도와드리는 온디바이스 AI 어시스턴트입니다. 이런 걸 할 수 있어요:\n" +
        lines.joinToString("\n") { "- $it" }
}


/**
 * "전체 몇 장/명" 같이 조건 없이 전체를 묻는 질문 — 결정적으로 우회한다. 검색은 항상
 * top-N(5명)까지만 후보를 채우므로, 이런 질문을 그냥 검색에 태우면 "총 5명"이라고
 * 답해버린다(실측: 60장인데 5명이라고 답함) — top-5를 전체로 착각하게 만드는 잘못된
 * 답이라 아예 검색 전에 걸러서 진짜 전체 개수로 답한다.
 *
 * 긴 신호 단어부터 원문에서 직접 걷어내고, 아무것도 안 남으면 "전체를 요구하는 것"으로
 * 판정한다. 토큰화(KeywordSearchRanker.analyze) 기반으로 먼저 시도했다가 실측으로
 * 버그를 발견해서 원문 문자열 직접 치환 방식으로 바꿨다: 조사 제거 로직이 "명함"을
 * 조사 뗀 형태에서만 걸러내고 원본 "명함이"는 안 걸러내는 불일치가 있었다.
 *
 * "등록된 사람 총 몇 명이야?"처럼 "전체/모두/전부" 없이 "총"+"몇"만으로 전체를 묻는
 * 문구도 신호에 추가했다(실측: 이 문구가 우회를 못 타서 "총 5명"으로 잘못 답함 —
 * top-5를 전체로 착각하는 원래 버그가 그대로 재현됨). "총"만으로는 안 걸고 "몇"과
 * 같이 나올 때만 건다 — "판교에 총 몇명이야?"처럼 조건이 있는 질의는 strip 단계에서
 * "판교"가 안 걷어지고 남으므로 어차피 여기서 걸러진다(회귀 없음).
 */
private val GENERIC_LIST_STRIP_WORDS = listOf(
    // 긴 것부터 — "내가 가진"이 "내"보다 먼저 걷혀야 한다.
    "가지고 있어", "가지고 있는", "가지고있는", "내가 가진", "가진", "가지고",
    "지금", "현재",
    "저장된", "등록된", "있는", "있어", "있나", "있지",
    "보여줘", "알려줘", "찾아줘", "리스트", "목록", "전체", "명함", "이름",
    "카드", "사람", "모두", "전부", "얼마나", "몇", "장수", "개수", "장", "명", "총", "개",
    "내", "제", "다", "이", "야", "어", "지", "나",
    "은", "는", "이야", "인가", "될까",
    "?", "!", ".", ",", " ",
)

internal fun isUnfilteredListAllQuestion(question: String): Boolean {
    // 개수/목록을 묻는 말이 있어야 한다. 없으면 그냥 검색 질의다.
    //
    // 예전에는 "전체/모두/전부/총" 신호가 있어야만 통과시켰는데, 가장 자연스러운
    // 표현들이 그 신호를 안 쓴다 — "내가 가진 명함 개수 몇개야?", "명함 몇 개 있어?"
    // 가 전부 검색으로 빠져서 top-5 를 보고 "총 5명"이라 답했다(실기기 실측, 실제 1000).
    // 신호 게이트를 없애고 **일반 단어를 다 걷어냈을 때 아무것도 안 남으면 전체**로 본다.
    // 조건이 있으면("판교에 몇 명") 그 말이 안 걷혀서 남으므로 여기서 걸러진다.
    if (!Regex("몇|목록|리스트|다 보여|얼마나|개수|장수|전체|전부|모두").containsMatchIn(question)) return false
    var stripped = question
    for (w in GENERIC_LIST_STRIP_WORDS) stripped = stripped.replace(w, "")
    return stripped.trim().isEmpty()
}

private val FILTERED_COUNT_SIGNAL_RE = Regex("몇\\s*(명|장|개)")

/**
 * 조건이 있는 카운트 질문("판교에 몇 명 있어?", "이사 직급 몇 명이야?")인지 본다.
 * 조건 없는 전체질문(isUnfilteredListAllQuestion)은 이미 다른 우회가 처리하므로
 * 거기서 걸리면 여기서는 제외한다.
 */
internal fun isFilteredCountQuestion(question: String): Boolean {
    if (isUnfilteredListAllQuestion(question)) return false
    return FILTERED_COUNT_SIGNAL_RE.containsMatchIn(question)
}

/**
 * 멀티턴 RAG 채팅.
 *
 * 대화 상태는 AgentSession 이 갖는다(최근 메시지 8개 window + rolling summary +
 * tool_session_context). 그 위에 두 가지 결정적 처리가 얹혀 있다.
 *
 *  1) 지칭 치환: 후속 질문("그 사람 직급이 뭐야?")의 대명사를 직전 대상 인물로 치환한다.
 *     소형 모델의 LLM 재작성은 의도를 왜곡해(예: "어디 살아"→"회사") 불안정했고(실기기 확인),
 *     대화 기록에서 이름을 재추출하면 "전화번호는" 같은 명사를 인물로 오인했다.
 *     그래서 직전 검색 결과의 '실제 명함 이름'만 지칭 대상으로 쓴다.
 *  2) 정정/확인 발화 처리: "5명인데?" 같이 검색할 내용이 없는 발화는 재검색하지 않고
 *     직전 카드를 그대로 근거로 쓴다. 이게 없으면 엉뚱한 카드가 근거가 돼 대화가 끊긴다.
 */
fun runChat(
    searchService: CardSearchService,
    engines: ChatEngineProvider,
    tools: ToolRegistry,
    question: String,
    session: AgentSession,
): ChatResult {
    if (question.isBlank()) return ChatResult("질문을 입력하세요.", null, route = "blank")

    // 검색/LLM 엔진 로드보다 먼저 결정적으로 우회할 질문인지 본다 — 불필요한 엔진 로드를
    // 막기도 하고, 프롬프트 규칙에만 맡기면 신뢰할 수 없는 질문 유형이기도 하다.
    // conversationalFollowup=true로 반환한다 — 검색을 안 했으니 도구 실행 기록을 남기지
    // 않고, focus/직전 카드 id도 건드리지 않는 게 이 플래그의 기존 의미와 정확히 같다.
    // 기능 질문은 자기참조보다 먼저 본다("너는 뭐 할 줄 알아?" 가 양쪽에 걸린다).
    if (isCapabilityQuestion(question)) {
        return ChatResult(
            buildCapabilityAnswer(tools.capabilityLabels()),
            null,
            route = "capability",
            conversationalFollowup = true,
        )
    }
    if (isSelfReferenceQuestion(question)) {
        return ChatResult(SELF_REFERENCE_ANSWER, null, route = "self_reference", conversationalFollowup = true)
    }
    if (isUnfilteredListAllQuestion(question)) {
        val total = searchService.totalCardCount()
        return ChatResult(
            "현재 총 ${total}명의 명함이 등록되어 있습니다. 이름·회사·지역 등 구체적인 조건으로 검색해 보세요.",
            null,
            route = "total_count",
            conversationalFollowup = true,
        )
    }
    // 조건이 있는 카운트 질문은 검색(top-5 컷)을 안 태우고 전체 카드를 직접 세서 정확한
    // 개수로 답한다(실측: "AI 다루는 사람 몇 명이야?" 가 실제 42명인데 검색은 5명까지만
    // 봐서 캡됨). 가제티어가 모르는 조건(개념형 질의)이면 null이 와서 기존 검색+LLM
    // 경로로 그대로 떨어진다.
    if (isFilteredCountQuestion(question)) {
        val matched = searchService.countByCondition(question)
        if (matched != null) {
            val sample = matched.take(5).map { card ->
                CardSearchHit(card = card, score = 0.0, keywordRank = null, vectorRank = null, similarity = 0f)
            }
            val response = CardSearchResponse(
                query = question,
                engine = "count",
                retrieval = "gazetteer-count",
                keywordQuery = question,
                semanticQuery = question,
                results = sample,
            )
            return ChatResult(
                "총 ${matched.size}명",
                response,
                route = "filtered_count",
                conversationalFollowup = true,
            )
        }
    }

    val focusPerson = session.toolContextValue(AgentSession.KEY_FOCUS_PERSON)
    val focusCompany = session.toolContextValue(AgentSession.KEY_FOCUS_COMPANY)
    val prevCardIds = session.toolContextValue(AgentSession.KEY_LAST_CARD_IDS)
        ?.split(",")?.filter { it.isNotBlank() }.orEmpty()

    // 속성을 생략한 후속("음가영씨는?")이면 직전 턴이 물어본 속성을 이어 붙인다.
    // 아래 로직은 전부 이 보충된 질문을 쓴다 — 검색어와 LLM 에 넘기는 질문이 갈리면
    // 카드는 맞는데 답변만 엉뚱해진다.
    @Suppress("NAME_SHADOWING")
    val question = applyNarrowing(
        carryOverAttribute(
            // 담화 순서 지시는 **가장 먼저** 푼다. 뒤로 밀면 "첫 번째 사람"이 아래
            // ordinalIdx 블록으로 새서 직전 카드 목록의 1번을 고른다.
            // 대상 정정은 대명사 치환(resolveSearchQuery)보다 **먼저** 푼다 —
            // 뒤에 두면 '그분'이 이미 옛 focus 로 바뀐 뒤라 정정이 무시된다.
            // 필드 지시어를 정본 낱말로 — 모델이 100% 맞히는 말투로 바꿔 보낸다.
            normalizeAttributeWords(resolveCorrection(
                resolveDiscourseReference(
                    question,
                    session.toolContextValue(AgentSession.KEY_SUBJECT_HISTORY),
                    prevCardIds.size,
                ),
                runCatching { searchService.knownNamesIn(question) }.getOrNull().orEmpty(),
            )),
            session.toolContextValue(AgentSession.KEY_LAST_ATTRIBUTE),
        ),
        session.toolContextValue(AgentSession.KEY_LAST_FILTER_TERMS),
    )

    // Chat 모델(Gemma 4 E2B 등)이 없거나 메모리가 부족한 폰에서는 FunctionGemma로 대신 답변한다.
    val role = when {
        !engines.modelStatus(LlmRole.Chat).startsWith("missing:") -> LlmRole.Chat
        !engines.modelStatus(LlmRole.ToolCalling).startsWith("missing:") -> LlmRole.ToolCalling
        else -> null
    }

    // LLM이 아예 없으면 재작성도 불가 — 원문 질문으로 검색만 해서 결과를 보여준다.
    if (role == null) {
        val search = runCatching { searchService.searchHybrid(question, 5) }.getOrNull()
        return ChatResult(
            answer = if (search?.abstained == true) NO_MATCH_PHRASE
            else "LLM 모델이 없어 검색 결과만 보여드려요. 모델 탭에서 LLM 파일을 가져오면 답변도 생성됩니다.",
            search = search,
        )
    }

    val engine = try {
        engines.openShared(role) // 공유 엔진 — 닫지 않는다(반복 로드 스파이크 방지)
    } catch (e: Throwable) {
        val search = runCatching { searchService.searchHybrid(question, 5) }.getOrNull()
        return ChatResult("LLM 로드에 실패했어요. 검색 결과만 보여드려요.", search, error = e.message ?: e.javaClass.simpleName)
    }
    val loadedModel = engine.loadedFileName.removeSuffix(".litertlm")
    val modelLabel = if (role == LlmRole.ToolCalling) "$loadedModel (임시 대체 — 품질 낮음)" else loadedModel

    // 순서 지시("두 번째 사람")면 직전 집합에서 그 하나만 남긴다. 새 검색이 아니라
    // 앞 결과를 가리키는 발화이므로 아래 후속 경로로 보낸다.
    val ordinalIdx = ConversationalFollowup.ordinalIndex(question)
    val selectedIds = if (ordinalIdx != null && prevCardIds.isNotEmpty()) {
        val i = if (ordinalIdx < 0) prevCardIds.size - 1 else ordinalIdx
        prevCardIds.getOrNull(i)?.let { listOf(it) } ?: prevCardIds
    } else {
        prevCardIds
    }

    // 정정/확인/복수지시 발화 — 새로 검색하지 않고 직전 턴의 카드를 그대로 근거로 쓴다.
    if ((ConversationalFollowup.isFollowup(question) || ordinalIdx != null) &&
        selectedIds.isNotEmpty()
    ) {
        val search = runCatching { searchService.searchByIds(selectedIds) }.getOrNull()
        val answer = fieldListAnswer(question, search?.results?.map { it.card }.orEmpty())
            ?: emptyFieldAnswer(question, search?.results?.map { it.card }.orEmpty())
            ?: runCatching {
                engine.generate(
                    buildAnswerPrompt(session, question, search?.ragContext(5).orEmpty(), followup = true)
                )
            }.getOrNull().orEmpty()
        val finalAnswer = answer.ifBlank { "직전 결과 기준으로 답변을 만들지 못했어요." }
        // 후속 발화에서도 LLM이 "못 찾았다"고 답할 수 있다 — 메인 경로와 동일하게 그
        // 경우 카드를 비운다(일관성 문제였다. 메인 경로는 이미 narrowByAnswer로 처리함).
        var narrowedSearch = search
        var dropped: List<String> = emptyList()
        if (search != null) {
            val (n, d) = narrowByAnswer(search, finalAnswer)
            narrowedSearch = n
            dropped = d
        }
        return ChatResult(
            answer = finalAnswer,
            search = narrowedSearch,
            route = "followup",
            modelLabel = modelLabel,
            conversationalFollowup = true,
            filteredOut = dropped,
        )
    }

    // 문맥 참조 발화("아까 말한 …")인데 새로 검색하라는 말이 없으면, 검색도 LLM 호출도
    // 없이 이미 아는 것으로 답한다(sojung contextAnswerForSearch).
    //
    // **대화형 후속 검사보다 뒤에 둔다.** 앞에 두었더니 "그 사람들 회사 알려줘"가
    // CONTEXT_REFERENCES 의 "그 사람"에 걸려서 직전 답변을 그대로 재생했다 — 사용자는
    // 회사를 물었는데 이름 목록만 다시 받는다(멀티턴 평가에서 21건 잡힘).
    // 직전 결과 집합에 대해 **새로 묻는** 발화는 그 카드로 다시 답을 만들어야 하고,
    // context_answer 는 "아까 뭐였지"처럼 **되짚는** 발화에만 쓴다.
    ConversationalFollowup.contextAnswer(session, question)?.let { contextual ->
        val prior = if (prevCardIds.isNotEmpty()) {
            runCatching { searchService.searchByIds(prevCardIds) }.getOrNull()
        } else {
            null
        }
        return ChatResult(
            answer = contextual,
            search = prior,
            route = "context_answer",
            modelLabel = modelLabel,
            conversationalFollowup = true,
        )
    }

    val searchQuery = resolveSearchQuery(question, focusPerson, focusCompany)

    val search = try {
        pinAmbiguousTwin(
            searchService.searchHybrid(searchQuery, 5),
            session.toolContextValue(AgentSession.KEY_SUBJECT_CARDS),
        )
    } catch (e: Throwable) {
        return ChatResult("검색 중 문제가 있었습니다.", null, error = e.message ?: e.javaClass.simpleName)
    }

    // 근거가 없으면 LLM 을 호출하지 않는다 — 부르면 무관한 카드로 답을 지어낸다
    // (실측: 없는 사람 "정하은"에 대해 "채용설명회에서 만났습니다"라고 답했다).
    //
    // 기권뿐 아니라 **후보가 0장인 경우**도 포함한다. "부산에 있는 디자인"처럼 지역·직함이
    // 둘 다 데이터에 있는데 교집합만 비는 경우인데, 빈 컨텍스트를 넣었더니 2B 모델이
    // 컨텍스트 문자열("검색 후보 없음")을 그대로 답변으로 뱉었다(pass^3 3회 모두 재현).
    if (search.abstained || search.results.isEmpty()) {
        return ChatResult(
            answer = NO_MATCH_PHRASE,
            search = search,
            route = if (search.abstained) "abstain" else "empty_result",
            modelLabel = modelLabel,
        )
    }

    // 조건이 명확해서 전체를 셀 수 있으면 그 진짜 수를 컨텍스트에 넣는다. 못 세는
    // 개념형 질의면 null 이라 헤더에 숫자가 안 들어간다 — 모델이 후보 개수를 답으로
    // 옮겨 적는 것을 막기 위해서다(실측: "디자인하는 사람" -> 이름 대신 "총 3명").
    val totalMatches = runCatching { searchService.countByCondition(question)?.size }.getOrNull()

    return try {
        val llmAnswer = fieldListAnswer(question, search.results.map { it.card })
            ?: emptyFieldAnswer(question, search.results.map { it.card })
            ?: engine.generate(
                buildAnswerPrompt(session, question, search.ragContext(5, totalMatches)))
        if (llmAnswer == EMPTY_LLM_RESPONSE) {
            ChatResult(
                answer = "LLM이 유효한 답변을 만들지 못했어요. 검색 결과를 참고해 주세요." +
                    if (role == LlmRole.ToolCalling) "\n(FunctionGemma는 대화용 모델이 아니라 자주 이렇습니다)" else "",
                search = search,
                modelLabel = loadedModel,
            )
        } else {
            // LLM 판정을 카드 목록에도 반영한다. 검색은 top-N 을 채우느라 무관한 후보를 함께
            // 담는데(점수 컷오프로는 못 자른다는 것을 오프라인 측정으로 확인), LLM 은 그중
            // 관련 있는 사람만 골라 답한다. 그 판단을 화면 카드에도 적용해 답변과 카드가
            // 어긋나지 않게 한다.
            val (narrowed, dropped) = narrowByAnswer(search, llmAnswer)
            ChatResult(
                answer = llmAnswer,
                search = narrowed,
                route = "search",
                modelLabel = modelLabel,
                filteredOut = dropped,
            )
        }
    } catch (e: Throwable) {
        ChatResult(
            answer = "LLM 답변 생성에 실패했어요. 검색 결과는 아래에서 확인할 수 있습니다.",
            search = search,
            error = e.message ?: e.javaClass.simpleName,
        )
    }
}

// buildAnswerPrompt 규칙 3번("맨 위 '총 N명'을 그대로 쓰고")이 요구하는 집계형 답변
// 형식과 정확히 맞춘 패턴. 답변에 후보 이름도 없고 이 패턴도 없으면 명함과 무관한
// 답변이라는 뜻이다(실측: "오늘 날씨 어때?" -> "날씨 정보는 명함 컨텍스트에 포함되어
// 있지 않습니다." 인데 top-5 후보가 그대로 카드로 남음. "홍길동 명함 삭제해 줘"
// (존재 안 하는 이름) -> 의도 확인 답변인데 엉뚱한 홍씨 5명이 카드로 남음).
private val AGGREGATE_COUNT_RE = Regex("총\\s*(\\d+)\\s*명")

/**
 * 답변이 이 카드를 근거로 삼았는가.
 *
 * 이름만 보면 안 된다(실측 회귀): "문선영씨 회사가 어디야?" -> "주식회사 노블어패럴입니다."
 * 처럼 필드 값으로만 답하는 게 정상인 질문이 많은데, 이름이 없다는 이유로 카드를 통째로
 * 지워버렸다. 회사/주소/이메일/전화 같은 '그 카드에서 온 값'도 근거로 인정한다.
 * 직함은 쓰지 않는다 — 여러 사람이 공유해서 변별력이 없다.
 */
private fun cardReferencedIn(card: BusinessCardEntity, answer: String): Boolean {
    if (answer.isBlank()) return false
    val name = card.name.orEmpty().trim()
    if (name.isNotBlank() && name in answer) return true
    for (raw in listOf(card.company, card.address, card.email)) {
        // 라벨 접두어("A.  ", "E.  ")를 떼고 비교한다.
        val v = raw.orEmpty().trim().substringAfterLast("  ").trim()
        if (v.length < 4) continue
        if (v in answer) return true
        // LLM 이 값의 일부만 말하는 경우도 인정한다 — 주소가 대표적이다.
        // 실측: 카드가 "강원도 당진시 반포대2로 67 (승현이김리)" 인데 답변은 괄호를 뺀
        // "강원도 당진시 반포대2로 67" 이라 v in answer 가 False 였고 카드가 지워졌다.
        val head = v.substringBefore(" (").trim()
        if (head.length >= 6 && head in answer) return true
    }
    val digits = card.phone.orEmpty().filter { it.isDigit() }
    val answerDigits = answer.filter { it.isDigit() }
    if (digits.length >= 4 && answerDigits.length >= 4 && digits.takeLast(4) in answerDigits) return true
    return false
}

/**
 * LLM 답변에 언급된 사람만 카드로 남긴다.
 *
 * 답변이 전원 거절(NO_MATCH_PHRASE)이면 카드도 전부 비운다 — 안 그러면 "없습니다" 라는
 * 답변 밑에 명함이 그대로 뜬다. 아무 이름도 언급되지 않은 집계형 답변("총 5명")이면
 * 원본을 유지한다(이름이 없다고 해서 후보가 무관한 건 아니므로). 이름도 없고 집계형
 * 형식도 아니면 명함과 무관한 답변이므로 카드를 비운다.
 */
/**
 * "이름=id" 목록에 한 줄을 넣거나 갱신한다. 같은 이름이 다시 확정되면 최신 것으로 덮는다 —
 * 사용자가 대화 중에 다른 쪽으로 옮겨갈 수 있고, 그때는 최근 확정이 맞다.
 */
fun appendSubjectCard(existing: String?, name: String, cardId: String): String {
    if (name.isBlank() || cardId.isBlank()) return existing.orEmpty()
    val kept = existing.orEmpty().split(",")
        .filter { it.isNotBlank() && it.substringBefore("=") != name }
    return (kept + "$name=$cardId").joinToString(",")
}

/** "이름=id" 목록에서 그 이름에 대해 이 대화가 정한 카드 id 를 찾는다. */
internal fun subjectCardFor(existing: String?, name: String): String? =
    existing.orEmpty().split(",")
        .firstOrNull { it.substringBefore("=") == name && "=" in it }
        ?.substringAfter("=")
        ?.takeIf { it.isNotBlank() }

/**
 * **동명이인 되부르기.** 이름 조건이 하나인데 그 이름 카드가 여럿 남았고, 이 대화가
 * 앞에서 한 명으로 정해 둔 적이 있으면 그쪽만 남긴다. 사람은 한 번 정하면 다음부터
 * 이름만 댄다("샤인기계 백다인씨 직급" ... 세 턴 뒤 "백다인씨 전화번호는?").
 *
 * 규칙을 프롬프트에 적지 않고 여기서 결정적으로 거른다 — 이 저장소에서 프롬프트 규칙
 * 추가는 4전 4패, 결정적 우회는 9전 9승이다.
 */
internal fun pinAmbiguousTwin(
    search: CardSearchResponse,
    subjectCards: String?,
): CardSearchResponse {
    if (search.fieldFilters.names.size != 1) return search
    val pinnedId = subjectCardFor(subjectCards, search.fieldFilters.names.first()) ?: return search
    val pinned = search.results.firstOrNull { it.card.id == pinnedId } ?: return search
    val sameName = search.results.filter { it.card.name == pinned.card.name }
    if (sameName.size <= 1) return search
    return search.copy(results = search.results.filter { it.card.id == pinnedId || it !in sameName })
}

internal fun narrowByAnswer(
    search: CardSearchResponse,
    answer: String,
): Pair<CardSearchResponse, List<String>> {
    if (REJECTION_MARKERS.any { it in answer }) {
        return search.copy(results = emptyList()) to search.results.map { it.card.name }
    }
    val agg = AGGREGATE_COUNT_RE.find(answer)
    // "총 0명" — 숫자로 표현된 거절이다. 집계형이라고 카드를 살려두면 "총 0명"이라
    // 답하면서 명함 5장이 뜨는 모순이 된다(실측: "울릉도 근무자" -> '총 0명', 카드 5장).
    if (agg != null && agg.groupValues[1].toIntOrNull() == 0) {
        return search.copy(results = emptyList()) to search.results.map { it.card.name }
    }
    // **하드 필터를 통과한 카드는 답변이 뭐라 하든 지우지 않는다.**
    //
    // 필드 조건(이름/직함/지역)이 걸렸다는 건 검색이 이미 '조건을 만족하는 사람'만
    // 남겼다는 뜻이라, 그 카드들은 정의상 답이다. LLM 이 그중 일부만 말했다고 나머지를
    // 지우면 사용자가 답의 일부만 보게 된다.
    // 실기기 실측: "대전에 있는 변호사 찾아줘" -> 검색은 탁예린·방우성 2명을 맞게 찾았는데
    // 답변이 "탁예린" 한 명만 말해서 방우성이 잘렸다. 그 상태로 "두 번째 사람 연락처"를
    // 물으면 두 번째가 아예 없다. 같은 질문에도 매번 달라져서 재현이 들쭉날쭉했다.
    //
    // 이 함수의 원래 목적(무관한 카드가 답변과 어긋나게 뜨는 것 방지)은 **조건이 없는**
    // 질의에서만 필요하다 — "오늘 날씨 어때?" 는 필드 조건이 안 잡히므로 아래로 내려가
    // 예전처럼 비워진다. 거절 답변은 위 두 분기에서 이미 걸러진다.
    if (!search.fieldFilters.isEmpty) {
        return search to emptyList()
    }
    val mentioned = search.results.filter { cardReferencedIn(it.card, answer) }
    if (mentioned.isNotEmpty()) {
        val dropped = search.results.filterNot { it in mentioned }.map { it.card.name }
        return search.copy(results = mentioned) to dropped
    }
    if (agg == null) {
        return search.copy(results = emptyList()) to search.results.map { it.card.name }
    }
    // 집계형인데 답변이 말한 수가 후보 수보다 적으면 그 수만큼만 보여준다.
    // 안 그러면 "총 2명"이라 답하고 카드는 5장 뜨는 모순이 된다
    // (실측: "AI 개발하는 사람 찾아줘" -> '총 2명' + 카드 5장. 뒤 3장은 AI개발팀이지만
    //  직함이 대표이사·디자인 디렉터라 실제 답이 아니었다).
    // 정렬이 정확 일치를 앞에 두므로 상위 N개가 그 N명이다.
    val want = agg.groupValues[1].toIntOrNull() ?: return search to emptyList()
    if (want in 1 until search.results.size) {
        val kept = search.results.take(want)
        return search.copy(results = kept) to search.results.drop(want).map { it.card.name }
    }
    return search to emptyList()
}

// 후속 질문임을 나타내는 대명사/지시 표현들. 이게 있을 때 직전 인물로 치환한다.
private val FOLLOWUP_PRONOUNS = listOf(
    "그 사람", "그사람", "그 분", "그분", "이 사람", "이사람", "저 사람", "저사람",
    "그 사람의", "걔", "그 회사", "그회사", "방금 그", "그 명함", "이 분", "이분",
)

/** 회사 자체가 다음 검색의 대상인 표현. "그 회사 주소" 같은 속성 질문은 제외한다. */
private val COMPANY_REFERENCE = Regex("그\\s*회사")
private val COMPANY_ENTITY_WORDS = listOf("다니", "근무", "재직", "일하", "사람", "직원", "동료")

// 속성 명사로 시작하는 생략형 후속("메일은?", "직급은?")도 직전 인물에 대한 질문으로 본다.
// 반대로 새 이름으로 시작하면("옹현은…", "홍길동은…") 새 인물로 보고 focus를 붙이지 않는다.
private val ATTRIBUTE_NOUNS = listOf(
    "전화번호", "전화", "번호", "연락처", "핸드폰", "휴대폰", "메일", "이메일",
    "직급", "직함", "직책", "회사", "소속", "주소", "위치", "지역", "부서", "이름",
)

/**
 * 후속 질문의 검색 쿼리를 만든다. 대명사가 있고 직전 대화의 대상 인물(focusPerson)이 있으면
 * 그 이름으로 치환한다. focusPerson은 직전 검색 결과 최상위 카드의 '실제 명함 이름'이라,
 * 기록 텍스트에서 정규식으로 이름을 재추출할 때 생기던 오인("전화번호는"→인물)이 없다.
 * 이름이 이미 있는 질문(대명사 없음)은 그대로 둔다 — 의도 왜곡 없이 정확히 검색되게.
 */
/**
 * 생략된 **속성**을 직전 턴에서 이어받는다.
 *
 * 생략형 후속은 두 방향이 있는데 그동안 한쪽만 처리하고 있었다:
 *   "주소는?"      주어 생략 + 속성 명시  -> focus 인물을 앞에 붙임 (resolveSearchQuery)
 *   "음가영씨는?"  주어 명시 + 속성 생략  -> **처리 없음**
 * 뒤엣것은 검색은 맞게 되는데(그 사람 카드를 찾음) 답변이 "음가영"처럼 이름만 되돌아왔다.
 * 앞 턴이 "회사가 어디야?"였으면 이번에도 회사를 묻는 것이므로 그 속성을 붙여 준다.
 *
 * 별도 규칙을 더한 게 아니라 이미 있던 생략 처리의 나머지 절반이다.
 */

/**
 * 담화 순서 지시("처음에 물어본 사람 전화번호는?")를 그 사람 이름으로 바꿔 다시 쓴다.
 *
 * [ConversationalFollowup.ordinalIndex] 와 **다른 것**이다. 저쪽은 직전 결과 목록의
 * N번째 카드를 고르고, 이쪽은 **대화에서 N번째로 화제가 된 사람**을 가리킨다. 지시 대상이
 * 카드 목록이 아니라 지난 발화라 focus 치환으로도 닿지 않는다(그 인물은 이미 최근 창 밖이다).
 *
 * 실측(HJP-limited/ymj Final50 v3): 이 처리가 없으면 "처음에 물어본 사람 전화번호는?" 이
 * 새 검색으로 빠지고 "처음/물어본/사람"이 검색어가 돼 대화에 없던 사람을 데려왔다
 * (long_range_reactivation 0/10, discourse_coreference 0/10).
 *
 * 이름을 앞에 붙여 **질의를 다시 쓰는** 방식이라(resolveSearchQuery·applyNarrowing 과 동일)
 * 그 뒤 검색·필터·focus 는 평소 경로를 그대로 탄다.
 */
private val DISCOURSE_VERBS = listOf(
    "물어본", "물어봤", "질문한", "질문했", "언급한", "언급했",
    "확인한", "확인했", "말한", "말했", "등장한", "나온",
)
private val DISCOURSE_FIRST = listOf("맨 처음", "처음에", "처음", "가장 먼저", "먼저")
private val DISCOURSE_HINTS = DISCOURSE_VERBS + DISCOURSE_FIRST + listOf("대화")

/** 이름을 앞에 붙인 뒤 남으면 검색어를 오염시키는 말들. 긴 것부터 지운다. */
private val DISCOURSE_STRIP = (DISCOURSE_HINTS + listOf(
    // 시간 부사를 남기면 contextAnswer 가 "아까"를 보고 되짚기로 오인해 직전 답변을
    // 재생한다(실측: "아까 처음에 물어본 분 전화번호는?" -> 엉뚱한 번호).
    "아까", "방금", "앞서",
    "그분", "그 분", "사람", "분", "대화에서", "두 사람", "중", "맨", "가장",
    "첫 번째로", "첫 번째", "첫번째", "번째로", "번째", "그", "했던", "던",
)).sortedByDescending { it.length }

/** 쉼표로 이어 둔 화제 인물 목록에 새 인물을 더한다(이미 있으면 순서를 유지한다). */
fun appendSubject(previous: String?, name: String): String {
    val list = previous?.split(",")?.filter { it.isNotBlank() }.orEmpty()
    return if (name in list) list.joinToString(",") else (list + name).joinToString(",")
}

/**
 * @param subjectHistory 대화에 등장한 인물(처음 나온 순서). [AgentSession.KEY_SUBJECT_HISTORY].
 * @param prevCardCount  직전 턴이 남긴 카드 수. 담화 단서 없는 순서 지시를 가를 때 쓴다.
 */
internal fun resolveDiscourseReference(
    question: String,
    subjectHistory: String?,
    prevCardCount: Int,
): String {
    val subjects = subjectHistory?.split(",")?.filter { it.isNotBlank() }.orEmpty()
    if (subjects.isEmpty()) return question
    // 질문이 이미 사람을 지목하고 있으면 지시가 아니다.
    if (subjects.any { it in question }) return question

    val idx = when {
        DISCOURSE_HINTS.any { it in question } ->
            if (DISCOURSE_FIRST.any { it in question }) 0
            else ConversationalFollowup.ordinalIndex(question)
        // "첫 번째 사람" 처럼 담화 단서가 없는 순서 지시. 직전 결과가 0~1장이면 거기서
        // 고를 게 없으므로 대화 순서를 가리키는 말로 읽는다(narrowByAnswer 4단계와 같은 원리).
        prevCardCount < 2 -> ConversationalFollowup.ordinalIndex(question)
        else -> null
    } ?: return question

    val name = (if (idx < 0) subjects.lastOrNull() else subjects.getOrNull(idx)) ?: return question
    var rest = question
    for (w in DISCOURSE_STRIP) rest = rest.replace(w, " ")
    rest = rest.replace(Regex("\\s+"), " ").trim()
    return "$name $rest".trim()
}



/**
 * 필드 지시어 정규화 — "직장/어디 다녀" 를 정본 낱말 "회사" 로 바꿔 질의를 다시 쓴다.
 *
 * **왜 사전인가(측정으로 고른 것이다).** [CardGazetteer] 는 '카드에 실재하는 값'(대전·이사·
 * 성다인)을 데이터에서 자동으로 배운다. 그런데 '회사·직장·부서' 는 **값이 아니라 스키마를
 * 가리키는 말**이라 카드 안에 없고, 그래서 배울 수가 없다. 대안 둘을 재보고 뺐다:
 *  - 필드 이름을 임베딩해 최근접 필드로 라우팅 -> 17개 발화에서 82.4%. 하필 우리가 틀리는
 *    말투에서 같이 틀렸다("직장이 어디지?" -> title, 글자 '직' 공유 / "어디 다녀?" -> location).
 *    사전은 모르면 LLM 에 넘기지만 임베딩은 **자신 있게** 틀린 칸을 읽어 더 위험하다.
 *  - 질의를 그대로 임베딩해 회사값과 맞추기 -> 회사값('네트웍스솔루션즈')은 고유명사라
 *    '회사'·'직장' 어느 쪽과도 가깝지 않다(0.111 / 0.168 — 주소보다 낮다).
 *
 * **왜 등록이 아니라 정규화인가.** [ATTRIBUTE_FIELD] 에 넣기만 하면 답이 안 바뀐다 —
 * "마민씨 직장이 어디지?" 는 이름도 있고 한 칸이고 값도 차 있어서 [fieldListAnswer](2칸 이상)·
 * [emptyFieldAnswer](빈 칸) 어느 우회도 안 걸린다. 그래서 **모델이 100% 맞히는 말투로 바꿔서**
 * 보낸다(실측: "회사가 어디야?" 41/41 · "회사 알려줘" 15/15 vs "직장이 어디지?" 5/6 ·
 * "어디 다녀?" 3/7).
 *
 * 카드 값과 충돌하지 않는 낱말만 넣는다(실측: 직장·다녀·다니·근무·일해 전부 카드 등장 0건).
 * '소속' 은 넣지 않는다 — 회사·부서 양쪽으로 읽힌다(임베딩 격차도 0.016 으로 모호했다).
 */
private val ATTRIBUTE_PHRASE_REWRITE =
    Regex("""어디\s*(?:에?서\s*)?(?:다니|다녀|근무|일하|일해)\S*""")

/**
 * 낱말 치환은 **뒤에 조사만 올 때**만 한다 — "직장인"(직장+인)처럼 다른 낱말의 일부면
 * 건드리지 않는다. 조사까지 함께 삼켜 정본 낱말 + 자연스러운 조사로 다시 붙인다.
 * 그냥 "직장"->"회사" 로 바꾸면 "직장이" 가 "회사이"(비문)가 된다.
 */
private val ATTRIBUTE_WORD_REWRITE = Regex("""직장(이|은|는|을|를|가|도|의)?(?=\s|[?!.,]|$)""")

/** 받침 있는 '직장' -> 받침 없는 '회사' 로 바뀌므로 주격·주제 조사를 맞춘다. */
private val JOSA_AFTER_VOWEL = mapOf("이" to "가", "은" to "는")

internal fun normalizeAttributeWords(question: String): String {
    var q = ATTRIBUTE_PHRASE_REWRITE.replace(question, "회사가 어디야")
    q = ATTRIBUTE_WORD_REWRITE.replace(q) { m ->
        val josa = m.groupValues[1]
        "회사" + (JOSA_AFTER_VOWEL[josa] ?: josa)
    }
    return q
}

/** 대상 정정("A가 아니라 B야") 표지. */
private val CORRECTION_MARKERS = listOf("아니라", "말고", "정정", "아니고")

/**
 * "손서윤씨가 아니라 남다은씨야. 그분 회사는?" 처럼 **대상을 바꾸는** 발화를
 * 정정된 사람에 대한 질의로 다시 쓴다.
 *
 * 실측(Final50 v3): 이게 없으면 두 이름이 **둘 다** 이름 조건으로 잡히고, 대명사('그분')는
 * [resolveSearchQuery] 가 **옛 focus** 로 치환해 버린다("… 남다은씨야. 손서윤 회사는 어디야?").
 * 그러면 focus 가 옛 대상에 머물러 **그 뒤 모든 턴이 틀린 사람**을 답한다(4턴 시나리오가 통째로
 * 무너진다). 그래서 대명사 치환보다 **먼저** 돌아야 한다. 실측 4/10 -> 7/10.
 *
 * 정정 표지가 있고 아는 이름이 **둘 이상**일 때만 건다 — 마지막에 말한 이름이 정정된 대상이다.
 *
 * @param knownNames 이 발화에서 뽑힌, 데이터에 실재하는 이름들.
 */
internal fun resolveCorrection(question: String, knownNames: List<String>): String {
    if (CORRECTION_MARKERS.none { it in question }) return question
    if (knownNames.size < 2) return question
    // 추출 순서가 아니라 **발화에 나타난 위치** 순으로 본다.
    val ordered = knownNames.sortedBy { question.indexOf(it) }
    // 거절 표지 바로 앞의 이름이 **버릴** 이름이다. "X씨 직급 말한 거야. Y씨 말고" 에서
    // 마지막 이름(Y)을 대상으로 잡으면 뒤집힌다(통합 벤치 v1 기준선 2/2 실패).
    val rejected = knownNames.filter { n ->
        Regex(Regex.escape(n) + "(?:씨|님)?(?:가|이|은|는)?\\s*(?:말고|아니라|아니고)").containsMatchIn(question)
    }.toSet()
    val kept = ordered.filterNot { it in rejected }
    val target = if (kept.isNotEmpty() && rejected.isNotEmpty()) kept.last() else ordered.last()
    // 정정 뒤의 실제 요청만 남긴다. **요청이 담긴 문장**을 고른다 — 물어본 칸(속성 명사)이
    // 있는 문장이 요청이다. 무조건 마지막 문장을 쓰면 "X씨 회사 말한 거야. Y씨 말고" 에서
    // 'Y씨 말고' 만 남아 물어본 칸이 사라지고(벤치 v1.1 실패 6건), 반대로 대상 이름이 있는
    // 문장을 먼저 고르면 "…남다은씨야. 그분 회사는?" 에서 요청이 사라진다.
    // **속성이 먼저, 이름은 그다음**이다.
    val parts = question.split(Regex("[.!?]")).filter { it.isNotBlank() }
    var tail = if (parts.size > 1) {
        val cand = parts.filter { attributeOf(it) != null }.ifEmpty { parts.filter { target in it } }
        cand.ifEmpty { parts }.last()
    } else {
        question
    }
    // 이름과 대명사를 지운다 — 남으면 다시 이름 조건으로 잡히거나 focus 로 치환된다.
    // 이름은 **전부** 지운다(대상은 어차피 앞에 다시 붙인다). 조사까지 함께 걷어야
    // "손도윤씨가 아니라" 의 '가' 같은 조각이 안 남는다.
    for (n in ordered) {
        tail = tail.replace(Regex(Regex.escape(n) + "(?:씨|님)?(?:가|이|은|는|을|를|도|의)?"), " ")
    }
    for (p in FOLLOWUP_PRONOUNS) tail = tail.replace(p, " ")
    // 정정 표지 자체도 요청이 아니다.
    for (marker in listOf("말한 거야", "말한거야", "말고", "아니라", "아니고", "아니")) {
        tail = tail.replace(marker, " ")
    }
    tail = tail.replace(Regex("\\s+"), " ").trim()
    return "$target $tail".trim()
}

internal fun carryOverAttribute(question: String, lastAttribute: String?): String {
    if (lastAttribute.isNullOrBlank()) return question
    val q = question.trim()
    // "이름 + 조사 + ?" 형태만 대상으로 한다("음가영씨는?", "그 사람은?").
    // 이미 속성 명사가 들어 있으면 손대지 않는다.
    if (ATTRIBUTE_NOUNS.any { it in q }) return question
    val m = Regex("^(.{2,10}?)(씨|님)?(는|은|이|가)\\s*\\??$").find(q) ?: return question
    val subject = m.groupValues[1] + m.groupValues[2]
    return "$subject $lastAttribute"
}

/** 질문에서 어떤 속성을 물었는지 뽑아 둔다(다음 턴이 속성을 생략했을 때 이어받으려고). */
fun attributeOf(question: String): String? =
    ATTRIBUTE_NOUNS.firstOrNull { it in question }
/**
 * 속성 명사 -> 카드 필드. 물어본 칸이 실제로 비어 있는지 보려고 둔다.
 * ('이름'은 비는 일이 없어 뺀다.)
 */
private val ATTRIBUTE_FIELD = mapOf(
    // 복합어를 **먼저** 등록한다. "메일 주소는?" 은 이메일 하나를 묻는 말이지
    // 이메일과 주소를 함께 묻는 말이 아니다(실측: 두 칸으로 읽혀 주소까지 답했다).
    "이메일 주소" to "email", "메일 주소" to "email",
    "이메일주소" to "email", "메일주소" to "email",
    "회사 주소" to "address", "회사주소" to "address",
    "전화번호" to "phone", "전화" to "phone", "번호" to "phone", "연락처" to "phone",
    "핸드폰" to "phone", "휴대폰" to "phone",
    "메일" to "email", "이메일" to "email",
    "직급" to "title", "직함" to "title", "직책" to "title",
    "회사" to "company", "소속" to "company",
    "주소" to "address", "위치" to "address", "지역" to "location",
    "부서" to "department",
)

/**
 * 대상이 하나로 정해졌는데 물어본 칸이 비어 있으면 결정적으로 '없다'고 답한다.
 *
 * 실측(Final50 v3 unanswerable): 빈 칸을 그냥 물으면 2B 모델이 **옆 칸 값으로 대체**했다 —
 * 회사를 물었는데 "국내영업팀입니다"(부서), "제주특별자치도 제주시"(주소), 직급을 물었는데
 * "생산관리팀입니다"(부서). 컨텍스트에 그 칸만 없을 뿐 나머지 값이 다 들어 있으니 모델이
 * 가장 그럴듯한 걸 골라 채운다. **값이 없다는 건 코드가 이미 아는 사실**이라 모델에
 * 맡길 이유가 없다(프롬프트 규칙 추가는 4전 4패다).
 *
 * 후보가 정확히 1장일 때만 건다 — 여러 명이면 '그중 누구의 칸'인지 정해지지 않는다
 * (narrowByAnswer 4단계와 같은 원리).
 */

/**
 * 지시 관형사 뒤의 속성 명사는 **요청이 아니라 가리키는 말**이다.
 * "그 회사 주소는?" 은 주소 하나만 묻는 것이지 회사를 함께 묻는 게 아니다
 * (실측: 이 구분이 없으면 우리 시나리오 '대명사 체인' 7턴이 통째로 오탐된다).
 */
private val DEMONSTRATIVES = listOf("그", "이", "저")

/** 질의에서 그 명사가 **요청으로** 쓰인 첫 위치. 없으면 -1. */
private fun requestedPos(question: String, noun: String): Int {
    var start = 0
    while (true) {
        val pos = question.indexOf(noun, start)
        if (pos < 0) return -1
        val before = question.substring(0, pos).trimEnd()
        if (DEMONSTRATIVES.none { before.endsWith(it) }) return pos
        start = pos + 1
    }
}

/**
 * 질의가 물은 속성들을 **말한 순서대로**, 필드 기준 중복 없이 돌려준다.
 * '전화번호'가 '전화'·'번호'를 품는 식으로 명사가 겹치므로 **긴 명사부터** 본다 —
 * 짧은 쪽이 먼저 잡히면 라벨이 잘려 나온다("메일: …").
 */
internal fun requestedFields(question: String): List<Pair<String, String>> {
    val found = mutableListOf<Triple<Int, String, String>>()
    val taken = mutableListOf<IntRange>()   // 이미 어떤 명사가 차지한 글자 구간
    for (noun in ATTRIBUTE_FIELD.keys.sortedByDescending { it.length }) {
        val field = ATTRIBUTE_FIELD.getValue(noun)
        if (found.any { it.second == field }) continue
        val pos = requestedPos(question, noun)
        if (pos < 0) continue
        // 앞서 잡힌 명사 안에 들어 있으면 같은 말을 두 번 세는 것이다.
        if (taken.any { pos in it }) continue
        taken.add(pos until pos + noun.length)
        found.add(Triple(pos, field, noun))
    }
    return found.sortedBy { it.first }.map { it.second to it.third }
}

/**
 * 한 사람에게 **여러 칸**을 물으면 코드가 직접 조합해 답한다.
 *
 * 실측(Final50 v3): "회사와 이메일도 알려줘" 에 2B 모델이 이메일만 답했다(4건).
 * 값은 컨텍스트에 다 있는데 모델이 하나를 빠뜨리는 것이라 **검색·문맥 문제가 아니다.**
 * 어느 칸을 물었는지도, 그 값이 무엇인지도 코드가 이미 안다
 * (프롬프트 규칙 추가는 4전 4패다). 담화 지시 6/10 -> 10/10.
 *
 * 두 칸 이상일 때만 건다 — 한 칸짜리는 LLM 이 문장으로 답하게 둔다(자연스러움 유지).
 * 후보가 정확히 1장일 때만 건다 — 여러 명이면 '누구의 칸'인지 안 정해진다.
 */
internal fun fieldListAnswer(question: String, cards: List<BusinessCardEntity>): String? {
    if (cards.size != 1) return null
    val fields = requestedFields(question)
    if (fields.size < 2) return null
    val card = cards[0]
    return fields.joinToString(", ") { (field, noun) ->
        val value = cardField(card, field)
        if (value.isNullOrBlank()) "$noun: 정보 없음" else "$noun: $value"
    }
}

private fun cardField(card: BusinessCardEntity, field: String): String? = when (field) {
    "phone" -> card.phone
    "email" -> card.email
    "title" -> card.title
    "company" -> card.company
    "address" -> card.address
    "location" -> card.location
    "department" -> card.department
    else -> null
}

internal fun emptyFieldAnswer(question: String, cards: List<BusinessCardEntity>): String? {
    if (cards.size != 1) return null
    val attr = attributeOf(question) ?: return null
    val field = ATTRIBUTE_FIELD[attr] ?: return null
    val card = cards[0]
    val value = cardField(card, field)
    if (!value.isNullOrBlank()) return null
    return "$attr 정보가 없습니다."
}


/** "그중에", "거기서" — 앞 턴 결과 안에서 더 좁히자는 표현. */
private val NARROWING_MARKERS = listOf("그중", "그 중", "거기서", "그 안에서", "그것들 중")

/**
 * 점진적 좁히기 — 앞 턴의 조건을 이어받는다.
 *
 * "대전에 있는 사람 찾아줘" -> "그중에 변호사만" 에서 앞 턴의 지역 조건이 사라져
 * **전국 변호사**가 나왔다. 매 턴 질문에서 조건을 새로 뽑기 때문이다.
 *
 * 조건을 따로 병합하지 않고 **앞 턴의 조건어를 질의 앞에 붙인다** — focus 인물을 앞에
 * 붙이는 resolveSearchQuery 와 같은 방식이다. 그러면 필터 추출이 알아서 둘을 합치고,
 * 검색어에도 그 말이 들어가서 후보 풀에 해당 지역 사람이 실제로 담긴다
 * (필터만 합치면 풀에 대전 사람이 없어 걸러낼 대상 자체가 없을 수 있다).
 */
internal fun applyNarrowing(question: String, previousTerms: String?): String {
    if (previousTerms.isNullOrBlank()) return question
    if (NARROWING_MARKERS.none { it in question }) return question
    return "$previousTerms $question"
}

/**
 * 속성 명사 앞에 흔히 붙는 군말. 이걸 떼고도 속성으로 시작하면 생략형 후속이다
 * ("어느 부서야?", "그럼 주소는?"). 지시 관형사(그/이/저)는 넣지 않는다 —
 * 그건 [FOLLOWUP_PRONOUNS] 가 이미 담당하므로 중복이다.
 */
private val ELLIPSIS_LEAD_FILLERS =
    listOf("어느", "어떤", "그럼", "그러면", "근데", "그리고", "혹시", "이제", "또")

internal fun resolveSearchQuery(
    question: String,
    focusPerson: String?,
    focusCompany: String? = null,
): String {
    if (focusPerson == null) return question
    val hasPronoun = FOLLOWUP_PRONOUNS.any { question.contains(it) }
    // 질문에 '새 검색값'(전화번호 뒷자리 등)이 있으면 생략형 후속으로 보지 않는다 —
    // "번호 뒷자리 4312인 분"처럼 새 값을 주는 질문을 이전 focus에 억지로 묶으면 안 됨.
    //
    // 숫자가 하나라도 있으면 새 값으로 봤더니 실측으로 버그가 났다: "전화번호 뒤 4자리는
    // 뭐야"의 '4'가 새 값으로 잡혀서 직전 인물이 안 붙고 엉뚱한 검색이 됐다(답변은
    // '조건에 해당하는 명함을 찾지 못했습니다', focus 도 딴 사람으로 튐).
    // '4자리' 같은 자릿수 표현과 '4312' 같은 검색값은 자릿수 길이로 가른다.
    val hasNewValue = Regex("\\d{3,}").containsMatchIn(question)
    // 속성 명사 **앞에 붙는 군말**을 떼고도 본다. startsWith 만 보면 "부서는?"은 되는데
    // "어느 부서야?"는 새 검색으로 빠져 focus 가 엉뚱한 사람으로 튄다 — 그 한 턴이
    // 오염시키면 **뒤 턴이 연쇄로 무너진다**(실측: 평가 발화를 다양화하자 실패 2건 -> 32건,
    // 대부분이 이 한 가지 말투에서 시작된 연쇄였다. 고친 뒤 다시 2건).
    val trimmed = question.trimStart()
    val withoutFiller = ELLIPSIS_LEAD_FILLERS
        .firstOrNull { trimmed.startsWith(it) }
        ?.let { trimmed.removePrefix(it).trimStart() }
        ?: trimmed
    val isElliptical = !hasNewValue &&
        ATTRIBUTE_NOUNS.any { trimmed.startsWith(it) || withoutFiller.startsWith(it) }
    // 대명사도 없고 속성 명사로 시작하지도 않으면 새 인물/독립 질문 — 그대로 둔다.
    if (!hasPronoun && !isElliptical) return question
    var q = question
    // "그 회사 다니는 사람" — 회사 자체가 다음 검색의 대상일 때만 회사명으로 바꾼다.
    // 사람 대명사 치환보다 **먼저** 해야 한다: FOLLOWUP_PRONOUNS 에 "그 회사"가 들어
    // 있어서, 뒤로 밀면 회사 표현이 인물 이름으로 바뀌어 버린다.
    if (focusCompany != null && COMPANY_REFERENCE.containsMatchIn(question) &&
        COMPANY_ENTITY_WORDS.any { it in question }
    ) {
        q = q.replace(COMPANY_REFERENCE, focusCompany)
    }
    // 긴 표현부터 치환한다 — "그 사람"이 "그"보다 먼저 걸려야 한다.
    for (p in FOLLOWUP_PRONOUNS.sortedByDescending { it.length }) q = q.replace(p, focusPerson)
    // 대명사 치환이 없었으면(생략형 후속) 이름을 앞에 붙여 focus 인물로 검색되게 한다.
    return if (q != question) q else "$focusPerson $question"
}

/**
 * 검색 컨텍스트 + 세션 컨텍스트로 최종 답변 프롬프트를 만든다.
 *
 * 규칙 수를 늘릴수록 2B 모델의 준수율이 떨어진다(실측: 규칙 6개일 때 "판교에 있는 디자이너"에
 * 이름 대신 "총 2명"이라고 답했다). 꼭 필요한 것만 두고, 실제로 결과를 좌우하는 규칙을
 * 뒤에 배치한다 — 최근 규칙일수록 더 잘 따른다.
 */
private fun buildAnswerPrompt(
    session: AgentSession,
    question: String,
    ragContext: String,
    followup: Boolean = false,
): String {
    val rules = buildString {
        append("You are an on-device assistant for a business card app.\n")
        append("Answer in Korean using only the business card context below.\n")
        append("- \"그 사람\" 같은 표현은 이전 대화에서 다룬 인물을 가리킨다. 그 인물 기준으로 답하라.\n")
        append("- 되묻지 말고 바로 답하라. 이름을 물으면 이름을 답하라.\n")
        // 2B 모델은 목록을 세다 틀린다(실측: 판교 5명을 맞게 검색했는데 "4명입니다").
        // 그래서 셀 일이 없게 컨텍스트 맨 위에 개수를 박아 두고 그대로 쓰게 한다.
        append("- 인원수를 물었을 때만 숫자를 써라. 후보 전원이 조건에 맞으면 맨 위 '총 N명'을\n")
        append("  그대로 쓰고 직접 세지 마라.\n")
        // 이 규칙이 무관 카드를 실제로 잘라낸다. '전원 거절'은 같은 판단의 극단이라 붙여 둔다 —
        // 따로 떼면 모델이 후보 중 하나를 억지로 고른다(실측: "우주비행사 찾아줘" -> '장우주').
        append("- 가장 중요: 컨텍스트에 있다고 질문과 관련 있는 건 아니다. 직함·부서·업무로 판단해\n")
        append("  질문 조건에 맞는 사람만 답하고, 무관한 사람은 이름조차 언급하지 마라.\n")
        append("  이름 글자가 우연히 겹치는 것은 근거가 아니다.\n")
        append("  맞는 사람이 하나도 없으면 다른 말 없이 '$NO_MATCH_PHRASE' 라고만 답하라.\n")
        if (followup) {
            append("- 지금 사용자 발화는 새 검색이 아니라 직전 답변에 대한 정정/확인/추가질문이다.\n")
            append("  아래 컨텍스트는 '직전 검색 결과'다. 이 사람들만 대상으로 짧게 답하라.\n")
            append("  사용자가 숫자나 사실을 정정했고 컨텍스트가 사용자 말과 맞으면 정정을 인정하라.\n")
            // 무엇을 물었는지 **코드로 뽑아서** 알려준다. 규칙을 하나 더 얹는 게 아니라
            // 이미 해석해 둔 의도를 전달하는 것이다 — 이게 없으면 "두 번째 사람 연락처"
            // 처럼 속성을 명시해도 모델이 이름만 돌려줬다(실기기 실측). followup 분기가
            // "짧게 답하라"로만 유도해서 무엇을 답할지가 비어 있었다.
            attributeOf(question)?.let {
                append("- 사용자가 물은 것은 '$it' 다. 그 값을 답하라(이름만 답하지 마라).\n")
            }
        }
    }
    return "$rules\n${session.buildContextBlocks(question)}\n\n명함 컨텍스트:\n$ragContext"
}
