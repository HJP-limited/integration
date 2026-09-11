package com.example.hjp

import com.example.hjp.agent.tools.OpenComposeTool
import com.example.hjp.agent.tools.CreateCalendarEventTool
import com.example.hjp.agent.tools.ToolRegistry
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.hjp.agent.AgentSession
import com.example.hjp.agent.ConversationalFollowup
import com.example.hjp.agent.LiteRtChatEngineProvider
import com.example.hjp.agent.LiteRtLmChatEngine
import com.example.hjp.agent.LlmRole
import com.example.hjp.data.BusinessCardEntity
import com.example.hjp.search.CardSearchHit
import com.example.hjp.search.CardSearchResponse
import com.example.hjp.search.CardSearchService
import com.example.hjp.search.createCardSearchService
import com.example.hjp.ui.CardDetailScreen
import com.example.hjp.ui.CardListScreen
import com.example.hjp.ui.CaptureScreen
import com.example.hjp.ui.HjpIcons
import com.example.hjp.ui.HomeScreen
import com.example.hjp.ui.LoginScreen
import com.example.hjp.ui.OcrDraft
import com.example.hjp.ocr.OcrCardMapper
import com.example.hjp.ui.OcrResultScreen
import com.example.hjp.ui.SettingsScreen
import com.example.hjp.ui.theme.HJPTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent?.getStringExtra("q")?.let { q ->
            android.util.Log.i(DIAG_TAG, "onCreate q=$q")
            DebugQuestion.offer(q)
            intent.removeExtra("q")
        }

        val searchService = createCardSearchService(applicationContext)

        setContent {
            HJPTheme {
                HjpApp(
                    searchService = searchService,
                    initialToolLlmStatus = LiteRtLmChatEngine.modelStatus(applicationContext, LlmRole.ToolCalling),
                    initialChatLlmStatus = LiteRtLmChatEngine.modelStatus(applicationContext, LlmRole.Chat),
                )
            }
        }
    }

    /**
     * 이미 떠 있는 액티비티에 질문을 더 밀어 넣는다. launchMode=singleTop 이라 액티비티가
     * 다시 만들어지지 않으므로 **멀티턴 세션이 유지된다** — 이게 없으면 질문마다 세션이
     * 초기화돼서 후속 발화("그 사람 부서는?")를 시험할 수가 없다.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val q = intent.getStringExtra("q")
        android.util.Log.i(DIAG_TAG, "onNewIntent q=$q")
        DebugQuestion.offer(q)
    }

    /**
     * onNewIntent 가 안 오는 경우(런처 플래그·태스크 상태에 따라 다르다)를 대비한 폴백.
     * 같은 인텐트를 두 번 처리하지 않도록 소비한 extra 는 지운다.
     */
    override fun onResume() {
        super.onResume()
        intent?.getStringExtra("q")?.let { q ->
            android.util.Log.i(DIAG_TAG, "onResume q=$q")
            intent.removeExtra("q")
            DebugQuestion.offer(q)
        }
    }
}

/** 화면 목업(SCR-01~09)의 하단 탭 5개. */
internal enum class AppTab(val label: String, val icon: String) {
    Home("홈", HjpIcons.HOME),
    Cards("명함", HjpIcons.CARDS),
    Capture("촬영", HjpIcons.CAMERA),
    Agent("Agent", HjpIcons.AGENT),
    Settings("설정", HjpIcons.SETTINGS),
}

/**
 * 탭 위에 겹쳐 뜨는 화면. 목업의 SCR-04(OCR 결과)·SCR-06(상세)이 여기 해당한다 —
 * 탭이 아니라 흐름의 일부라서 뒤로 가면 원래 탭으로 돌아와야 한다.
 */
internal sealed interface Overlay {
    data class CardDetail(val card: BusinessCardEntity) : Overlay
    data class OcrResult(val draft: OcrDraft) : Overlay
    data object Models : Overlay
}

/** 실기기 진단 로그 태그. `adb logcat -s HJP` 로 본다. */
private const val DIAG_TAG = "HJP"

/**
 * 디버그용 질문 주입구.
 *
 * `adb shell input text` 가 한글을 못 쳐서(NullPointerException) 실기기에서 시나리오를
 * 자동으로 태울 방법이 없었다. 인텐트로 질문을 받아 여기로 흘려보내면 화면의 전송
 * 버튼과 똑같은 경로를 타고, **세션이 유지되므로 멀티턴도 그대로 된다**:
 *
 *     adb shell am start -n com.example.hjp/.MainActivity --es q "대전에 있는 변호사 찾아줘"
 *     adb shell am start -n com.example.hjp/.MainActivity --es q "두 번째 사람 연락처"
 *
 * 결과는 DIAG_TAG 로그로 나온다. 운영 동작에는 영향이 없다(인텐트가 없으면 아무 일도
 * 안 한다). replay=0 이라 화면 회전 등으로 다시 구독해도 옛 질문이 재실행되지 않는다.
 */
internal object DebugQuestion {
    /**
     * 대기 중인 질문. **SharedFlow 가 아니라 상태로 들고 있는다** — 흘려보내는 방식은
     * 채팅 화면이 아직 안 떠 있으면 구독자가 없어 그냥 버려졌다(실측: 명함 탭에 있을 때
     * 인텐트가 조용히 무시됨). 상태로 두면 탭 전환 -> 화면 구성 -> 처리 순서가 보장된다.
     */
    var pending by mutableStateOf<String?>(null)
        private set

    fun offer(question: String?) {
        if (!question.isNullOrBlank()) pending = question.trim()
    }

    fun consume() {
        pending = null
    }
}


private data class ChatMessage(
    val isUser: Boolean,
    val text: String,
    val modelLabel: String? = null,
    val search: CardSearchResponse? = null,
    val error: String? = null,
    val conversationalFollowup: Boolean = false,
    val filteredOut: List<String> = emptyList(),
)

@Composable
fun HjpApp(
    searchService: CardSearchService,
    initialToolLlmStatus: String,
    initialChatLlmStatus: String,
) {
    // SCR-01. 인증이 없으므로 진짜 관문이 아니라 첫 화면일 뿐이다.
    //
    // 초기값을 **구성 시점에** 정한다 — 디버그 인텐트로 들어온 질문이 대기 중이면 로그인
    // 화면을 아예 거치지 않는다. 예전에는 effect 로 뒤늦게 넘겼는데, 그러면 로그인 화면이
    // 한 번 그려졌다가 교체되면서 채팅 화면이 처리한 말풍선이 사라졌다(실측).
    var signedIn by rememberSaveable { mutableStateOf(DebugQuestion.pending != null) }
    var selectedTab by remember { mutableStateOf(AppTab.Home) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    // 디버그 인텐트로 질문이 들어오면 Agent 화면으로 옮긴다 — 그 화면이 떠 있어야
    // 질문이 처리된다(adb 로 탭을 누르는 건 기기에서 잘 안 먹혔다).
    LaunchedEffect(DebugQuestion.pending) {
        if (DebugQuestion.pending != null) {
            signedIn = true
            selectedTab = AppTab.Agent
            overlay = null
        }
    }

    if (!signedIn) {
        LoginScreen(onEnter = { signedIn = true }, modifier = Modifier.fillMaxSize())
        return
    }
    var toolLlmStatus by remember { mutableStateOf(initialToolLlmStatus) }
    var chatLlmStatus by remember { mutableStateOf(initialChatLlmStatus) }

    Scaffold(
        // imePadding 은 **Scaffold 에** 건다. 화면 쪽 Column 에 걸면 Scaffold 가 이미 준
        // 하단 탭바 높이 패딩 위에 키보드 높이가 또 더해져서, 입력창이 키보드보다
        // 탭바 높이만큼 위로 떠 채팅 내용이 가려진다(실기기에서 확인).
        // 여기에 걸면 탭바까지 함께 올라가고 입력창이 키보드에 붙는다.
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab && overlay == null,
                        onClick = { selectedTab = tab; overlay = null },
                        label = { Text(tab.label, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        icon = { Text(tab.icon, fontSize = 18.sp) },
                    )
                }
            }
        },
    ) { innerPadding ->
        val content = Modifier.padding(innerPadding)
        when (val current = overlay) {
            is Overlay.CardDetail -> CardDetailScreen(
                card = current.card,
                onBack = { overlay = null },
                modifier = content,
            )

            is Overlay.OcrResult -> {
                var saving by remember(current) { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                OcrResultScreen(
                    draft = current.draft,
                    saving = saving,
                    onCancel = { overlay = null },
                    onSave = {
                        saving = true
                        scope.launch {
                            // id 발급은 전체 카드를 읽고, 저장은 FTS 를 다시 만든다. 메인
                            // 스레드에서 하면 Room 이 IllegalStateException 으로 앱을 죽인다
                            // (에뮬레이터에서 실제로 크래시).
                            val card = withContext(Dispatchers.IO) {
                                val saved = OcrCardMapper.toCard(
                                    current.draft.fields,
                                    searchService.nextOcrCardId(),
                                    System.currentTimeMillis(),
                                )
                                searchService.addCard(saved)
                                saved
                            }
                            saving = false
                            overlay = Overlay.CardDetail(card)
                        }
                    },
                    modifier = content,
                )
            }

            Overlay.Models -> ModelsScreen(
                searchService = searchService,
                toolLlmStatus = toolLlmStatus,
                chatLlmStatus = chatLlmStatus,
                onToolLlmStatusChanged = { toolLlmStatus = it },
                onChatLlmStatusChanged = { chatLlmStatus = it },
                modifier = content,
            )

            null -> when (selectedTab) {
                AppTab.Home -> HomeScreen(
                    searchService = searchService,
                    onCapture = { selectedTab = AppTab.Capture },
                    onAgent = { selectedTab = AppTab.Agent },
                    onSeeAll = { selectedTab = AppTab.Cards },
                    onCardClick = { overlay = Overlay.CardDetail(it) },
                    modifier = content,
                )

                AppTab.Cards -> CardListScreen(
                    searchService = searchService,
                    onCardClick = { overlay = Overlay.CardDetail(it) },
                    modifier = content,
                )

                AppTab.Capture -> CaptureScreen(
                    onRecognized = { overlay = Overlay.OcrResult(it) },
                    modifier = content,
                )

                AppTab.Agent -> ChatScreen(
                    searchService = searchService,
                    modifier = content,
                )

                AppTab.Settings -> {
                    var cardCount by remember { mutableStateOf<Int?>(null) }
                    LaunchedEffect(Unit) {
                        cardCount = withContext(Dispatchers.IO) { searchService.totalCardCount() }
                    }
                    SettingsScreen(
                        engineStatus = searchService.engineStatus,
                        toolLlmStatus = toolLlmStatus,
                        chatLlmStatus = chatLlmStatus,
                        cardCount = cardCount,
                        onOpenModels = { overlay = Overlay.Models },
                        modifier = content,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ChatScreen(
    searchService: CardSearchService,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }
    var selectedCard by remember { mutableStateOf<BusinessCardEntity?>(null) }
    // 멀티턴 세션 — 앱 프로세스가 살아있는 동안 하나를 유지한다(운영 아키텍처 규격).
    // 지칭 대상 인물과 직전 결과 카드는 tool_session_context 에 저장된다.
    val session = remember { AgentSession() }
    // 턴 파이프라인(:core)에 꽂아 줄 안드로이드 구현. 캘린더/메일은 인텐트를 여는 것까지만 한다.
    val engines = remember(context) { LiteRtChatEngineProvider(context) }
    val tools = remember(context) {
        ToolRegistry(CreateCalendarEventTool(context), OpenComposeTool(context))
    }
    val messages = remember {
        mutableStateListOf(
            ChatMessage(isUser = false, text = "명함에 대해 문장으로 물어보세요.\n예) \"판교에 있는 AI 개발자 찾아줘\"")
        )
    }
    val listState = rememberLazyListState()

    // 질문 하나를 처리한다. 화면의 전송 버튼과 **디버그 인텐트**가 같이 쓴다.
    //
    // 인텐트 진입점을 둔 이유: `adb shell input text` 가 한글을 못 친다
    // (NullPointerException). 그래서 실기기에서 무엇이 일어나는지 확인하려면 사람이
    // 손으로 타이핑하는 수밖에 없었다. 아래 DebugQuestion 으로 밀어 넣으면
    // 노트북에서 시나리오를 그대로 태울 수 있고, 세션이 유지되므로 멀티턴도 된다.
    fun send(question: String) {
        if (question.isBlank()) return
            messages.add(ChatMessage(isUser = true, text = question))
            // 턴 시작을 구조화 메모리에 걸어둔다 — 이 턴이 끝까지 완료되지 못해도
            // (예외 등) 다음 턴에서 "아직 처리 못한 요청"으로 남는다.
            val turnId = java.util.UUID.randomUUID().toString()
            session.beginTurn(turnId, question)
            scope.launch {
                asking = true
                val startedAt = System.currentTimeMillis()
                val result = withContext(Dispatchers.IO) {
                    runChat(searchService, engines, tools, question, session)
                }
                asking = false
                // 실기기 진단 로그. `adb logcat -s HJP` 로 본다.
                //
                // 이게 없을 때는 폰에서 무슨 일이 일어나는지 전혀 볼 수 없었다 —
                // 크래시만 보이고 어느 경로로 갔는지, 어떤 조건이 잡혔는지, 얼마나
                // 걸렸는지가 안 보여서 화면을 눈으로 읽는 수밖에 없었다.
                // 노트북 서버(hybrid_server.py)가 내는 항목과 **같은 이름**을 쓴다 —
                // 양자화 임베딩 때문에 폰과 노트북의 검색 순위가 갈릴 수 있어서
                // 둘을 나란히 놓고 대조하는 게 목적이다.
                android.util.Log.i(
                    DIAG_TAG,
                    buildString {
                        append("q=").append(question)
                        append(" | route=").append(result.route ?: "-")
                        append(" | ms=").append(System.currentTimeMillis() - startedAt)
                        result.search?.let { s ->
                            append(" | filters=").append(s.fieldFilters)
                            append(" | abstained=").append(s.abstained)
                            append(" | cards=")
                                .append(s.results.joinToString(",") { it.card.name })
                        }
                        if (result.filteredOut.isNotEmpty()) {
                            append(" | dropped=").append(result.filteredOut.joinToString(","))
                        }
                        append(" | answer=").append(result.answer.replace('\n', ' ').take(120))
                        result.error?.let { append(" | error=").append(it) }
                    },
                )
                // 대화 내역은 세션이 관리한다(최근 8개 window + 구조화 메모리).
                // 후속 발화 재사용 턴은 새로 검색하지 않았으니 도구 실행 기록도 없다.
                val executedTools = if (result.conversationalFollowup) {
                    emptyList()
                } else {
                    listOf("search_business_cards")
                }
                session.recordTurn(turnId, question, result.answer, executedTools)
                // 질문이 실제로 이름을 지목했으면 그 이름을 지칭 대상으로 삼는다(우선).
                // 그런 이름이 없으면(대명사/생략형 후속) 검색 1등 카드로 대체 — 새 개념
                // 검색("판교 AI개발자 찾아줘")에서도 focus가 정상적으로 잡히게.
                // "검색 1등이면 무조건 focus"였던 예전 방식은 유사 이름 오매칭 시
                // focus가 엉뚱한 사람으로 튀는 문제가 있었다(실기기에서 발견).
                val resultNames = result.search?.results?.map { it.card.name }?.distinct().orEmpty()
                val namedInQuestion = resultNames.firstOrNull { name -> question.contains(name) }
                (namedInQuestion ?: resultNames.firstOrNull())?.let { focusName ->
                    session.putToolContext(AgentSession.KEY_FOCUS_PERSON, focusName)
                    // 그 인물의 회사도 같이 기억한다 — "그 회사 다니는 사람 또 있어?" 처럼
                    // 회사 자체를 가리키는 후속 질의를 풀려면 이름만으로는 안 된다.
                    result.search?.results?.firstOrNull { it.card.name == focusName }?.card?.company
                        ?.takeIf { it.isNotBlank() }
                        ?.let { session.putToolContext(AgentSession.KEY_FOCUS_COMPANY, it) }
                    // 담화 순서 지시("처음에 물어본 사람")를 풀려면 최근 창 밖의 인물도
                    // 알아야 한다. focus 는 매 턴 그 턴의 주인공이므로 그대로 쌓으면
                    // '대화에 등장한 순서'가 된다.
                    session.putToolContext(
                        AgentSession.KEY_SUBJECT_HISTORY,
                        appendSubject(session.toolContextValue(AgentSession.KEY_SUBJECT_HISTORY), focusName),
                    )
                }
                // 정정/확인 발화가 아니었을 때만 근거 카드를 갱신한다
                // (정정 턴은 직전 근거를 그대로 유지해야 대화가 이어진다).
                if (!result.conversationalFollowup) {
                    val ids = result.search?.results?.map { it.card.id }.orEmpty()
                    session.putToolContext(
                        AgentSession.KEY_LAST_CARD_IDS,
                        ids.joinToString(",").ifBlank { null },
                    )
                    session.putToolContext(AgentSession.KEY_LAST_QUERY, question)
                    // 이름이 한 장으로 좁혀졌으면 그 짝을 기억한다(동명이인 되부르기).
                    // 회사로 특정한 턴("샤인기계 백다인씨")이 여기 해당한다.
                    val only = result.search?.results?.singleOrNull()?.card
                    if (only != null && question.contains(only.name.orEmpty())) {
                        session.putToolContext(
                            AgentSession.KEY_SUBJECT_CARDS,
                            appendSubjectCard(
                                session.toolContextValue(AgentSession.KEY_SUBJECT_CARDS),
                                only.name.orEmpty(), only.id,
                            ),
                        )
                    }
                }
                // 이번 턴이 물어본 속성을 남겨 둔다 — 다음 턴이 "○○씨는?" 처럼
                // 속성을 생략하면 여기서 이어받는다. 속성이 없는 질문이면
                // 이전 값을 그대로 둬서 대화 흐름을 유지한다.
                attributeOf(question)?.let {
                    session.putToolContext(AgentSession.KEY_LAST_ATTRIBUTE, it)
                }
                // 이번 턴에 걸린 필드 조건어를 남긴다 — 다음 턴이 "그중에 …" 로
                // 좁히면 여기서 이어받는다. 조건이 없었으면 이전 값을 유지한다.
                result.search?.fieldFilters?.let { f ->
                    val terms = (f.names + f.locations + f.titles).joinToString(" ")
                    if (terms.isNotBlank()) {
                        session.putToolContext(AgentSession.KEY_LAST_FILTER_TERMS, terms)
                    }
                }
                messages.add(
                    ChatMessage(
                        isUser = false,
                        text = result.answer,
                        modelLabel = result.modelLabel,
                        search = result.search,
                        error = result.error,
                        conversationalFollowup = result.conversationalFollowup,
                        filteredOut = result.filteredOut,
                    )
                )
            }
    }

    // 디버그 인텐트로 들어온 질문을 태운다(앱을 껐다 켜지 않으므로 세션이 유지된다).
    LaunchedEffect(DebugQuestion.pending, asking) {
        val q = DebugQuestion.pending
        if (q != null && !asking) {
            DebugQuestion.consume()
            send(q)
        }
    }

    LaunchedEffect(messages.size, asking) {
        listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("채팅", style = MaterialTheme.typography.titleLarge)
            // 멀티턴 기억을 초기화하고 대화를 처음부터 다시 시작한다.
            TextButton(
                enabled = !asking,
                onClick = {
                    messages.clear()
                    messages.add(
                        ChatMessage(isUser = false, text = "명함에 대해 문장으로 물어보세요.\n예) \"판교에 있는 AI 개발자 찾아줘\"")
                    )
                    // 세션 초기화 시 대화 내역과 모델 conversation 을 모두 폐기한다.
                    session.reset()
                    scope.launch(Dispatchers.IO) { LiteRtLmChatEngine.resetSharedChat() }
                },
            ) {
                Text("새 대화")
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages) { message ->
                ChatBubble(message, onCardClick = { selectedCard = it })
            }
            if (asking) {
                item { TypingBubble() }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("질문을 입력하세요") },
                maxLines = 3,
            )
            Button(
                enabled = !asking && input.isNotBlank(),
                onClick = {
                    val q = input.trim()
                    input = ""
                    send(q)
                },
            ) {
                Text("전송")
            }
        }
    }
    selectedCard?.let { card ->
        CardDetailDialog(card, onDismiss = { selectedCard = null })
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, onCardClick: (BusinessCardEntity) -> Unit = {}) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start,
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (message.isUser) 18.dp else 4.dp,
                    bottomEnd = if (message.isUser) 4.dp else 18.dp,
                ),
                color = if (message.isUser) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (message.isUser) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    message.modelLabel?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    message.error?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        message.search?.let { search ->
            SearchSummary(search, message.conversationalFollowup, message.filteredOut)
            search.results.take(5).forEach { hit ->
                BusinessCardResultCard(hit, onClick = { onCardClick(hit.card) })
            }
        }
    }
}

@Composable
private fun TypingBubble() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                "답변 생성 중...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
internal fun ModelsScreen(
    searchService: CardSearchService,
    toolLlmStatus: String,
    chatLlmStatus: String,
    onToolLlmStatusChanged: (String) -> Unit,
    onChatLlmStatusChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var diagnostics by remember { mutableStateOf<JSONObject?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var embedCheckMessage by remember { mutableStateOf<String?>(null) }
    var embedChecking by remember { mutableStateOf(false) }
    var toolTestMessage by remember { mutableStateOf<String?>(null) }
    var toolTesting by remember { mutableStateOf(false) }
    var chatTestMessage by remember { mutableStateOf<String?>(null) }
    var chatTesting by remember { mutableStateOf(false) }
    var indexMessage by remember { mutableStateOf<String?>(null) }
    var indexing by remember { mutableStateOf(false) }
    var pendingModelFileName by remember { mutableStateOf("embeddinggemma-300m.tflite") }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importMessage = "${pendingModelFileName} 복사 중... (파일 크기에 따라 시간이 걸립니다)"
            val result = withContext(Dispatchers.IO) {
                runCatching { copyModelToAppStorage(context, uri, pendingModelFileName) }
            }
            result.onSuccess { copied ->
                onToolLlmStatusChanged(LiteRtLmChatEngine.modelStatus(context, LlmRole.ToolCalling))
                onChatLlmStatusChanged(LiteRtLmChatEngine.modelStatus(context, LlmRole.Chat))
                importMessage = "${pendingModelFileName} 복사 완료 (${formatBytes(copied.length())})"
                diagnostics = withContext(Dispatchers.IO) {
                    searchService.reloadEmbeddingProvider()
                    searchService.diagnostics()
                }
            }.onFailure {
                importMessage = "복사 실패: ${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    LaunchedEffect(Unit) {
        diagnostics = withContext(Dispatchers.IO) { searchService.diagnostics() }
    }

    val embedStatus = diagnostics?.optString("active_embedding_status").orEmpty()
    val embedState = when {
        diagnostics?.optBoolean("active_embedding_model_backed") == true -> ModelState.Ready
        diagnostics == null || embedStatus.startsWith("missing:") -> ModelState.Missing
        else -> ModelState.Failed
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("모델 관리", style = MaterialTheme.typography.titleLarge)
        Text(
            "모든 AI 기능은 인터넷 없이 기기 안에서 동작합니다. 각 모델 파일을 가져온 뒤 '동작 확인'을 눌러 실제로 실행되는지 검사해 보세요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        importMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("복사 실패")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ModelCard(
            title = "임베딩 모델",
            subtitle = "EmbeddingGemma 300M · 모델(.tflite) + 토크나이저(sentencepiece.model) 2개 파일 필요",
            role = "채팅 탭의 의미 검색에 사용합니다. \"판교에서 만난 AI 하는 분\"처럼 문장 뜻으로 명함을 찾아줍니다.",
            state = embedState,
            stateLabel = when {
                embedState == ModelState.Ready -> "사용 준비됨"
                embedState == ModelState.Missing && embedStatus.contains("sentencepiece") -> "토크나이저 없음 — sentencepiece.model을 가져와 주세요"
                embedState == ModelState.Missing -> "모델 파일 없음 — 파일을 가져와 주세요"
                else -> "파일은 있지만 로드 실패"
            },
            detail = if (embedState == ModelState.Failed) embedStatus else "",
            onImport = {
                pendingModelFileName = "embeddinggemma-300m.tflite"
                modelPicker.launch(arrayOf("application/octet-stream", "*/*"))
            },
            secondaryImportLabel = "토크나이저 가져오기",
            onSecondaryImport = {
                pendingModelFileName = "sentencepiece.model"
                modelPicker.launch(arrayOf("application/octet-stream", "*/*"))
            },
            checking = embedChecking,
            onCheck = {
                scope.launch {
                    embedChecking = true
                    diagnostics = withContext(Dispatchers.IO) {
                        searchService.reloadEmbeddingProvider()
                        searchService.diagnostics()
                    }
                    embedChecking = false
                    embedCheckMessage = diagnostics?.let { d ->
                        if (d.optBoolean("active_embedding_model_backed")) {
                            "정상 동작 · ${d.optInt("embedding_dimensions")}차원 · 문장 1개 ${d.optLong("sample_embedding_ms")}ms"
                        } else {
                            "실행 실패: ${d.optString("active_embedding_status")}"
                        }
                    } ?: "상태를 읽지 못했습니다."
                }
            },
            resultText = embedCheckMessage,
        )

        ModelCard(
            title = "도구 실행 LLM",
            subtitle = "FunctionGemma 270M · functiongemma_270m.litertlm",
            role = "\"김지원한테 문자 보내줘\" 같은 요청을 캘린더·문자 도구 호출로 바꾸는 에이전트용 모델입니다. (에이전트 화면은 아직 연동 전)",
            state = if (toolLlmStatus.startsWith("missing:")) ModelState.Missing else ModelState.Ready,
            stateLabel = if (toolLlmStatus.startsWith("missing:")) "모델 파일 없음 — 파일을 가져와 주세요" else "파일 있음 — 동작 확인으로 실행을 검사하세요",
            detail = "",
            onImport = {
                pendingModelFileName = "functiongemma_270m.litertlm"
                modelPicker.launch(arrayOf("application/octet-stream", "*/*"))
            },
            checking = toolTesting,
            onCheck = {
                scope.launch {
                    toolTesting = true
                    toolTestMessage = withContext(Dispatchers.IO) { runLlmSmokeTest(context, LlmRole.ToolCalling) }
                    toolTesting = false
                    onToolLlmStatusChanged(LiteRtLmChatEngine.modelStatus(context, LlmRole.ToolCalling))
                }
            },
            resultText = toolTestMessage,
        )

        ModelCard(
            title = "채팅 LLM",
            subtitle = "Gemma 4 E2B IT · gemma-4-E2B-it.litertlm",
            role = "채팅 탭에서 검색된 명함 내용을 바탕으로 답변 문장을 만드는 모델입니다.",
            state = if (chatLlmStatus.startsWith("missing:")) ModelState.Missing else ModelState.Ready,
            stateLabel = if (chatLlmStatus.startsWith("missing:")) "모델 파일 없음 — 파일을 가져와 주세요" else "파일 있음 — 동작 확인으로 실행을 검사하세요",
            detail = "",
            onImport = {
                pendingModelFileName = "gemma-4-E2B-it.litertlm"
                modelPicker.launch(arrayOf("application/octet-stream", "*/*"))
            },
            checking = chatTesting,
            onCheck = {
                scope.launch {
                    chatTesting = true
                    chatTestMessage = withContext(Dispatchers.IO) { runLlmSmokeTest(context, LlmRole.Chat) }
                    chatTesting = false
                    onChatLlmStatusChanged(LiteRtLmChatEngine.modelStatus(context, LlmRole.Chat))
                }
            },
            resultText = chatTestMessage,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("임베딩 인덱스", style = MaterialTheme.typography.titleMedium)
                Text(
                    "명함 데이터를 새로 넣었다면 여기서 인덱스를 미리 만들어 두세요. 만들지 않으면 첫 채팅 질문이 수 분씩 걸립니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    enabled = !indexing && embedState == ModelState.Ready,
                    onClick = {
                        scope.launch {
                            indexing = true
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    searchService.indexEmbeddings { done, total ->
                                        if (done % 50 == 0 || done == total) {
                                            scope.launch { indexMessage = "인덱싱 중... $done / $total" }
                                        }
                                    }
                                }
                            }
                            indexing = false
                            indexMessage = result.fold(
                                onSuccess = { "인덱스 구축 완료. 채팅 검색을 바로 쓸 수 있습니다." },
                                onFailure = { "인덱싱 실패: ${it.message ?: it.javaClass.simpleName}" },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (indexing) "인덱싱 중..." else "인덱스 만들기")
                }
                if (embedState != ModelState.Ready) {
                    Text("임베딩 모델이 준비되면 사용할 수 있습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                indexMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("인덱싱 실패")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchSummary(
    response: CardSearchResponse,
    conversationalFollowup: Boolean = false,
    filteredOut: List<String> = emptyList(),
) {
    // 검색이 왜 이렇게 동작했는지 화면에서 바로 보이게 한다 — 라우팅/필터/기권이 조용히
    // 결과를 바꾸면 "검색이 이상하다"와 "규칙이 걸렸다"를 구분할 수 없다.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {}, label = { Text("키워드: ${response.keywordQuery.ifBlank { "전체" }}") })
            AssistChip(onClick = {}, label = { Text(response.retrieval) })
        }
        val notes = buildList {
            if (conversationalFollowup) add("정정/확인 발화 → 재검색 없이 직전 결과 사용")
            if (response.identifierRouted) add("식별자 질의 → 시맨틱 제외")
            if (!response.fieldFilters.isEmpty) add("필드 필터 ${response.fieldFilters}")
            if (response.abstained) add("기권 — 없는 이름/지역/번호")
            if (filteredOut.isNotEmpty()) add("무관 판정 제외 ${filteredOut.size}명: ${filteredOut.joinToString(", ")}")
        }
        notes.forEach {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BusinessCardResultCard(hit: CardSearchHit, onClick: (() -> Unit)? = null) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(hit.card.name.ifBlank { "(이름 없음)" }, style = MaterialTheme.typography.titleMedium)
            Text("${hit.card.company} · ${hit.card.title}", style = MaterialTheme.typography.bodyMedium)
            if (hit.card.department.isNotBlank() || hit.card.location.isNotBlank()) {
                Text("${hit.card.department} · ${hit.card.location}", style = MaterialTheme.typography.bodySmall)
            }
            Text("${hit.card.phone}  ${hit.card.email}", style = MaterialTheme.typography.bodySmall)
            if (hit.card.address.isNotBlank()) {
                Text(hit.card.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 하이브리드(RRF) 점수는 0.03처럼 작아서 소수점 1자리로는 0.0으로 보인다
            val scoreText = if (hit.score < 1.0) "%.3f".format(hit.score) else "%.1f".format(hit.score)
            Text("검색 점수 $scoreText", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** 명함 상세: 명함 이미지(있으면) + 전체 필드 표 */
@Composable
private fun CardDetailDialog(card: BusinessCardEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // 합성 데이터 카드 id(S00021)는 이미지 파일명(000021.png)과 매핑된다.
    // OCR로 들어올 미래 카드는 "{id}.png" 그대로 찾는다.
    val bitmap = remember(card.id) {
        val dir = context.getExternalFilesDir("card_images")
        val candidates = listOf(
            "${card.id}.png",
            card.id.removePrefix("S").padStart(6, '0') + ".png",
        )
        candidates.firstNotNullOfOrNull { name ->
            dir?.let { d ->
                val f = java.io.File(d, name)
                if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(card.name.ifBlank { "(이름 없음)" }, style = MaterialTheme.typography.titleLarge)

                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "명함 이미지",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth,
                    )
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "명함 이미지 없음",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 28.dp, horizontal = 16.dp),
                        )
                    }
                }

                listOf(
                    "이름" to card.name,
                    "영문 이름" to card.nameEn,
                    "회사" to card.company,
                    "직함" to card.title,
                    "부서" to card.department,
                    "업종" to card.industry,
                    "지역" to card.location,
                    "전화" to card.phone,
                    "이메일" to card.email,
                    "주소" to card.address,
                    "메모" to card.memo,
                    "태그" to card.tags,
                ).filter { it.second.isNotBlank() }.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(min = 72.dp),
                        )
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("닫기")
                }
            }
        }
    }
}

private enum class ModelState { Ready, Missing, Failed }

@Composable
private fun StatusDot(state: ModelState) {
    val color = when (state) {
        ModelState.Ready -> Color(0xFF2E7D32)
        ModelState.Missing -> MaterialTheme.colorScheme.outline
        ModelState.Failed -> MaterialTheme.colorScheme.error
    }
    Box(
        Modifier
            .size(10.dp)
            .background(color, CircleShape)
    )
}

@Composable
private fun ModelCard(
    title: String,
    subtitle: String,
    role: String,
    state: ModelState,
    stateLabel: String,
    detail: String,
    onImport: () -> Unit,
    checking: Boolean,
    onCheck: () -> Unit,
    resultText: String?,
    secondaryImportLabel: String? = null,
    onSecondaryImport: (() -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusDot(state)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(role, style = MaterialTheme.typography.bodyMedium)
            Text(
                stateLabel,
                style = MaterialTheme.typography.bodySmall,
                color = when (state) {
                    ModelState.Ready -> Color(0xFF2E7D32)
                    ModelState.Missing -> MaterialTheme.colorScheme.onSurfaceVariant
                    ModelState.Failed -> MaterialTheme.colorScheme.error
                },
            )
            if (detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onImport, modifier = Modifier.weight(1f)) {
                    Text("파일 가져오기")
                }
                OutlinedButton(
                    onClick = onCheck,
                    enabled = !checking && state != ModelState.Missing,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (checking) "확인 중..." else "동작 확인")
                }
            }
            if (secondaryImportLabel != null && onSecondaryImport != null) {
                OutlinedButton(onClick = onSecondaryImport, modifier = Modifier.fillMaxWidth()) {
                    Text(secondaryImportLabel)
                }
            }
            resultText?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("실행 실패") || it.startsWith("실패")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    message: String,
    ok: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(if (ok) message else "실패: $message", style = MaterialTheme.typography.bodyMedium)
        }
    }
}




private fun runLlmSmokeTest(context: Context, role: LlmRole): String =
    try {
        val engine = LiteRtLmChatEngine.openShared(context, role)
        val answer = engine.generate("Reply with one short Korean sentence.")
        "정상 동작 (${engine.backendName}) · 생성 예시: ${answer.take(80)}"
    } catch (e: Throwable) {
        "실행 실패: ${e.message ?: e.javaClass.simpleName}"
    }

private fun copyModelToAppStorage(context: Context, uri: Uri, fileName: String): File {
    val dir = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
    dir.mkdirs()
    val out = File(dir, fileName)
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "Could not open selected file." }
        out.outputStream().use { output -> input.copyTo(output) }
    }
    return out
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024.0
    return "%.1fMB".format(mb)
}
