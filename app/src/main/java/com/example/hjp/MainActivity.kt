package com.example.hjp

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
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.runtime.collectAsState
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
import com.hjp.agent.contract.AgentEvent
import com.hjp.tool.contact.BusinessCardRecord
import com.example.hjp.ui.CardDetailScreen
import com.example.hjp.ui.CardListScreen
import com.example.hjp.ui.CaptureScreen
import com.example.hjp.ui.HjpIcons
import com.example.hjp.ui.HomeScreen
import com.example.hjp.ui.OcrDraft
import com.example.hjp.ocr.OcrCardMapper
import com.example.hjp.ui.OcrResultScreen
import com.example.hjp.ui.SettingsScreen
import com.example.hjp.ui.theme.HJPTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent?.takeIf { BuildConfig.DEBUG }?.getStringExtra("q")?.let { q ->
            android.util.Log.i(DIAG_TAG, "onCreate q=$q")
            DebugQuestion.offer(q)
            intent.removeExtra("q")
        }
        intent?.takeIf { BuildConfig.DEBUG }?.getStringExtra("test_image_path")?.let { path ->
            android.util.Log.i(DIAG_TAG, "onCreate image=$path")
            DebugImage.offer(path)
            intent.removeExtra("test_image_path")
        }

        // 에이전트 배선은 프로세스 하나에 하나뿐이다(HjpApplication 이 들고 있다).
        // 액티비티가 다시 만들어져도 같은 세션이 이어지도록 여기서 새로 만들지 않는다.
        setContent {
            HJPTheme {
                ServiceEntry(application as HjpApplication)
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
        if (!BuildConfig.DEBUG) return
        val q = intent.getStringExtra("q")
        android.util.Log.i(DIAG_TAG, "onNewIntent q=$q")
        DebugQuestion.offer(q)
        intent.getStringExtra("test_image_path")?.let { path ->
            android.util.Log.i(DIAG_TAG, "onNewIntent image=$path")
            DebugImage.offer(path)
            intent.removeExtra("test_image_path")
        }
    }

    /**
     * onNewIntent 가 안 오는 경우(런처 플래그·태스크 상태에 따라 다르다)를 대비한 폴백.
     * 같은 인텐트를 두 번 처리하지 않도록 소비한 extra 는 지운다.
     */
    override fun onResume() {
        super.onResume()
        if (!BuildConfig.DEBUG) return
        intent?.getStringExtra("q")?.let { q ->
            android.util.Log.i(DIAG_TAG, "onResume q=$q")
            intent.removeExtra("q")
            DebugQuestion.offer(q)
        }
        intent?.getStringExtra("test_image_path")?.let { path ->
            android.util.Log.i(DIAG_TAG, "onResume image=$path")
            intent.removeExtra("test_image_path")
            DebugImage.offer(path)
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
    data class CardDetail(val card: BusinessCardRecord) : Overlay
    data class OcrResult(val draft: OcrDraft) : Overlay
    data object Models : Overlay
}

/** 실기기 진단 로그 태그. `adb logcat -s HJP` 로 본다. */
internal const val DIAG_TAG = "HJP"

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


/**
 * 디버그용 이미지 주입구. [DebugQuestion] 의 촬영판이다.
 *
 * 갤러리 선택기는 시스템 화면이라 adb 로 눌러 태울 수가 없다. 그래서 실기기·에뮬레이터에서
 * 인식 경로를 확인하려면 사람이 손으로 고르는 수밖에 없었다. 경로를 인텐트로 받으면 갤러리로
 * 고른 것과 **똑같은 경로**(decodeBitmap -> EXIF 보정 -> OCR)를 탄다:
 *
 *     adb shell am start -n com.example.hjp/.MainActivity --es test_image_path /sdcard/card.jpg
 *
 * 원본 App 트랙에도 같은 것이 있다. 운영 동작에는 영향이 없다 — 인텐트가 없으면 아무 일도
 * 안 한다.
 */
internal object DebugImage {
    var pending by mutableStateOf<String?>(null)
        private set

    fun offer(path: String?) {
        if (!path.isNullOrBlank()) pending = path.trim()
    }

    fun consume() {
        pending = null
    }
}

/**
 * 말풍선 하나. [cards] 는 **그 턴에 도구가 실제로 찾은** 명함이다 — 화면이 같은 질문으로
 * 다시 검색해서 채우지 않는다(재작성된 질의를 모르는 채 검색하면 답과 카드가 어긋난다).
 */
internal data class ChatMessage(
    val isUser: Boolean,
    val text: String,
    val modelLabel: String? = null,
    val cards: List<BusinessCardRecord> = emptyList(),
    val error: String? = null,
)

@Composable
fun HjpApp(
    container: AppContainer,
    directory: CardDirectory,
) {
    // Local-only service: no simulated account/login screen. Model setup precedes entry.
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.Home) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    // 디버그 인텐트로 질문이 들어오면 Agent 화면으로 옮긴다 — 그 화면이 떠 있어야
    // 질문이 처리된다(adb 로 탭을 누르는 건 기기에서 잘 안 먹혔다).
    LaunchedEffect(DebugQuestion.pending) {
        if (DebugQuestion.pending != null) {
            selectedTab = AppTab.Agent
            overlay = null
        }
    }
    // 디버그 인텐트로 이미지가 들어오면 촬영 화면으로 옮긴다 — 그 화면이 떠야 인식이 돈다.
    LaunchedEffect(DebugImage.pending) {
        if (DebugImage.pending != null) {
            selectedTab = AppTab.Capture
            overlay = null
        }
    }

    // 모델 상태는 커널이 실제로 읽은 아티팩트에서 온다 — 파일 이름이 아니라 바이트를
    // 보고 정한 값이라, 파일만 바꿔치기해도 여기 표시가 따라간다.
    val deploymentStatus = if (container.modelReady) {
        container.deployment.artifactId
    } else {
        "missing: " + container.modelFile.absolutePath
    }
    var toolLlmStatus by remember { mutableStateOf(deploymentStatus) }
    var chatLlmStatus by remember { mutableStateOf(deploymentStatus) }

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
                var saveError by remember(current) { mutableStateOf<String?>(null) }
                var pendingCard by remember(current) { mutableStateOf<BusinessCardRecord?>(null) }
                val scope = rememberCoroutineScope()
                OcrResultScreen(
                    draft = current.draft,
                    saving = saving,
                    saveError = saveError,
                    onCancel = { overlay = null },
                    onSave = {
                        saving = true
                        saveError = null
                        scope.launch {
                            // id 발급은 전체 카드를 읽고, 저장은 FTS 를 다시 만든다. 메인
                            // 스레드에서 하면 Room 이 IllegalStateException 으로 앱을 죽인다
                            // (에뮬레이터에서 실제로 크래시).
                            try {
                                // Reuse the same row when a failed model refresh is retried.
                                // Allocating another ID here would duplicate the OCR card.
                                val card = pendingCard ?: withContext(Dispatchers.IO) {
                                    OcrCardMapper.toCard(
                                        current.draft.fields,
                                        directory.nextOcrCardId(),
                                        System.currentTimeMillis(),
                                    )
                                }.also { pendingCard = it }
                                withContext(Dispatchers.IO) { directory.addCard(card) }
                                overlay = Overlay.CardDetail(card)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                saveError = error.message ?: error.javaClass.simpleName
                            } finally {
                                saving = false
                            }
                        }
                    },
                    modifier = content,
                )
            }

            Overlay.Models -> ModelsScreen(
                container = container,
                modifier = content,
            )

            null -> when (selectedTab) {
                AppTab.Home -> HomeScreen(
                    directory = directory,
                    onCapture = { selectedTab = AppTab.Capture },
                    onAgent = { selectedTab = AppTab.Agent },
                    onSeeAll = { selectedTab = AppTab.Cards },
                    onCardClick = { overlay = Overlay.CardDetail(it) },
                    modifier = content,
                )

                AppTab.Cards -> CardListScreen(
                    directory = directory,
                    onCardClick = { overlay = Overlay.CardDetail(it) },
                    modifier = content,
                )

                AppTab.Capture -> CaptureScreen(
                    onRecognized = { overlay = Overlay.OcrResult(it) },
                    modifier = content,
                )

                AppTab.Agent -> ChatScreen(
                    container = container,
                    modifier = content,
                )

                AppTab.Settings -> {
                    var cardCount by remember { mutableStateOf<Int?>(null) }
                    LaunchedEffect(Unit) {
                        cardCount = withContext(Dispatchers.IO) { directory.totalCardCount() }
                    }
                    SettingsScreen(
                        engineStatus = directory.engineStatus(),
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
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    // 대화도, 그 대화를 굴리는 코루틴도 화면이 아니라 [AppContainer] 가 들고 있다.
    // 화면은 보여 주고 입력만 받는다 — 이유는 [ChatSession] 에 적어 뒀다(요약하면:
    // 메일·일정 도구가 다른 앱을 열면 이 화면이 물러나는데, 턴이 화면에 매여 있으면
    // 그 순간 취소된다).
    val session = container.chat
    val messages = session.messages
    val asking = session.busy

    var input by remember { mutableStateOf("") }
    var selectedCard by remember { mutableStateOf<BusinessCardRecord?>(null) }
    val listState = rememberLazyListState()

    // 디버그 인텐트로 들어온 질문을 태운다(앱을 껐다 켜지 않으므로 세션이 유지된다).
    //
    // 인텐트 진입점을 둔 이유: `adb shell input text` 가 한글을 못 친다
    // (NullPointerException). 그래서 실기기에서 무엇이 일어나는지 확인하려면 사람이
    // 손으로 타이핑하는 수밖에 없었다. 여기로 밀어 넣으면 노트북에서 시나리오를 그대로
    // 태울 수 있고, 세션이 유지되므로 멀티턴도 된다.
    LaunchedEffect(DebugQuestion.pending, asking) {
        val q = DebugQuestion.pending
        if (q != null && !asking) {
            DebugQuestion.consume()
            session.send(q)
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
            // 멀티턴 기억을 초기화하고 대화를 처음부터 다시 시작한다. 화면의 말풍선과
            // 커널의 기억을 [ChatSession] 이 한 번에 비운다 — 한쪽만 비우면 사람이 보는
            // 대화와 모델이 기억하는 대화가 어긋난다.
            TextButton(
                enabled = !asking,
                onClick = { session.reset() },
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
                // 진행 상황은 커널이 흘려보내는 사건 그대로다(어느 도구가 도는 중인지).
                // 19초 걸리는 턴을 "답변 생성 중..." 한 줄로만 두면 멈춘 것과 구별되지 않는다.
                item { TypingBubble(session.status) }
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
            // 도는 중에는 같은 자리가 중단 버튼이 된다. 온디바이스 모델은 한 턴이 20초쯤
            // 걸리는데, 잘못 보낸 질문을 끝까지 기다릴 수밖에 없는 것은 막다른 길이다.
            if (asking) {
                OutlinedButton(onClick = { session.cancelTurn() }) {
                    Text("중단")
                }
            } else {
                Button(
                    enabled = input.isNotBlank(),
                    onClick = {
                        val q = input.trim()
                        input = ""
                        session.send(q)
                    },
                ) {
                    Text("전송")
                }
            }
        }
    }
    selectedCard?.let { card ->
        CardDetailDialog(card, onDismiss = { selectedCard = null })
    }
    // 실행 전 승인. 이게 뜨는 동안 커널은 대답을 기다리며 멈춰 있다 — 닫기만 하고
    // 답하지 않는 길을 두지 않는 이유다(dismiss 도 거절로 답한다).
    session.confirmation?.let { prompt ->
        ConfirmationDialog(
            promptKo = prompt,
            onAnswer = { accepted -> session.answerConfirmation(accepted) },
        )
    }
}

@Composable
private fun ConfirmationDialog(promptKo: String, onAnswer: (Boolean) -> Unit) {
    Dialog(onDismissRequest = { onAnswer(false) }) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("확인이 필요해요", style = MaterialTheme.typography.titleMedium)
                Text(promptKo, style = MaterialTheme.typography.bodyMedium)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { onAnswer(false) }, modifier = Modifier.weight(1f)) {
                        Text("취소")
                    }
                    Button(onClick = { onAnswer(true) }, modifier = Modifier.weight(1f)) {
                        Text("실행")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, onCardClick: (BusinessCardRecord) -> Unit = {}) {
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
        // 답변의 근거가 된 명함. 도구가 찾아온 것을 그대로 쓴다(화면이 다시 검색하지 않는다).
        if (message.cards.isNotEmpty()) {
            Text(
                "명함 " + message.cards.size + "장",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            message.cards.take(5).forEach { card ->
                BusinessCardResultCard(card, onClick = { onCardClick(card) })
            }
        }
    }
}

@Composable
private fun TypingBubble(status: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                status ?: "답변 생성 중...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
internal fun ModelsScreen(container: AppContainer, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        com.example.hjp.ui.ModelDownloadPanel()
        Text(if (container.modelReady) "대화 모델 사용 준비 완료" else "대화 모델 준비가 필요합니다.")
    }
}

@Composable
private fun BusinessCardResultCard(card: BusinessCardRecord, onClick: (() -> Unit)? = null) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(card.name.ifBlank { "(이름 없음)" }, style = MaterialTheme.typography.titleMedium)
            Text(card.company + " · " + card.title, style = MaterialTheme.typography.bodyMedium)
            if (card.department.isNotBlank() || card.location.isNotBlank()) {
                Text(card.department + " · " + card.location, style = MaterialTheme.typography.bodySmall)
            }
            Text(card.phone + "  " + card.email, style = MaterialTheme.typography.bodySmall)
            if (card.address.isNotBlank()) {
                Text(card.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 명함 상세: 명함 이미지(있으면) + 전체 필드 표 */
@Composable
private fun CardDetailDialog(card: BusinessCardRecord, onDismiss: () -> Unit) {
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
                    "태그" to card.tags.joinToString(", "),
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
    canCheckWithoutReady: Boolean = false,
    checkLabel: String = "상태 확인",
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
                    enabled = !checking && (canCheckWithoutReady || state != ModelState.Missing),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (checking) "확인 중..." else checkLabel)
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




private suspend fun copyModelToAppStorage(context: Context, uri: Uri, fileName: String): File {
    val dir = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
    val model = com.example.hjp.models.ModelDownloads.required.single { it.fileName == fileName }
    return com.example.hjp.models.ModelInstaller(dir).installFrom(model) {
        requireNotNull(context.contentResolver.openInputStream(uri)) { "Could not open selected file." }
    }
}

@Composable
private fun ServiceEntry(application: HjpApplication) {
    val setup = application.modelSetup
    val state by setup.state.collectAsState()
    var container by remember { mutableStateOf<AppContainer?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.ready, setup.termsAccepted) {
        if (state.ready && setup.termsAccepted) {
            try { container = withContext(Dispatchers.IO) { application.container } }
            catch (e: Exception) { error = "AI 초기화에 실패했습니다. 앱을 다시 열어 주세요." }
        }
    }
    val active = container
    if (active != null) {
        val directory = remember(active) { CardDirectory(active.contactRepository, active.directorySearchBackend, active::refreshAfterCardAdded) }
        HjpApp(active, directory)
    } else Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
            com.example.hjp.ui.ModelDownloadPanel()
            if (state.ready && setup.termsAccepted) Text(error ?: "AI 엔진을 시작하고 있습니다…")
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024.0
    return "%.1fMB".format(mb)
}
