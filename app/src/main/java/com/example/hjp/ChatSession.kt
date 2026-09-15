package com.example.hjp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hjp.agent.contract.AgentEvent
import com.hjp.agent.core.AgentTurnEngine
import com.hjp.tool.contact.BusinessCardRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 화면에 보이는 대화와, 그 대화를 굴리는 코루틴.
 *
 * Agent_0910 의 `AgentViewModel` 이 하던 일을 그대로 한다. 그쪽은 `viewModelScope` 를 썼고
 * 여기서는 [AppContainer] 가 스코프를 들고 있는데, 요점은 같다 — **화면보다 오래 산다.**
 *
 * 한때 둘 다 ChatScreen 안에 있었다. 대화 내역은 `remember`, 턴은 `rememberCoroutineScope()`.
 * 화면이 구성에서 빠지는 순간 같이 사라졌고, 두 가지가 실제로 깨졌다:
 *
 *  - **메일·일정 도구가 턴을 죽였다.** 그 도구들은 다른 앱의 작성 화면을 연다. 우리 화면이
 *    물러나면 컴포지션이 정리되고, 진행 중이던 턴이 "rememberCoroutineScope left the
 *    composition" 으로 끊겼다(실기기 실측: "정재민씨한테 메일 보내줘" 가 19초 돌다가 그렇게
 *    끝났다). 사용자에게는 멀티턴이 고장난 것으로 보인다.
 *  - **탭을 옮기면 대화가 날아갔다.** 명함 탭을 보고 돌아오면 빈 화면이었다.
 *
 * 커널 세션도 [AppContainer] 에 있으므로 화면에 보이는 대화와 모델이 기억하는 대화가 같은
 * 수명을 갖는다 — 하나만 살아남으면 둘이 어긋난다.
 */
internal interface ChatSessionHost {
    val engine: AgentTurnEngine
    val contactBackend: RecordingContactSearchBackend
    val modelLabel: String
    fun onSessionUsed()
    fun answerConfirmation(accepted: Boolean)
    fun log(message: String)
}

internal class ChatSession(private val container: ChatSessionHost) {
    constructor(container: AppContainer) : this(object : ChatSessionHost {
        override val engine get() = container.engine
        override val contactBackend get() = container.contactBackend
        override val modelLabel get() = container.deployment.artifactId
        override fun onSessionUsed() = container.onSessionUsed()
        override fun answerConfirmation(accepted: Boolean) { container.answerConfirmation(accepted) }
        override fun log(message: String) { android.util.Log.i(DIAG_TAG, message) }
    })

    /**
     * 턴을 굴리는 자리. 앱이 살아 있는 동안 유지된다.
     *
     * `Dispatchers.Main.immediate` 인 이유: 여기서 바꾸는 것이 전부 Compose 상태라
     * 메인 스레드에서 만져야 하고, immediate 라 이미 메인이면 한 프레임을 더 기다리지 않는다.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val messages = mutableStateListOf(ChatMessage(isUser = false, text = GREETING))

    var busy by mutableStateOf(false)
        private set

    var status by mutableStateOf<String?>(null)
        private set

    /**
     * 커널이 실행 전 승인을 기다리는 중이면 그 물음. 아니면 null.
     *
     * [status] 와 따로 두는 이유: 상태 줄은 흘러가는 진행 표시라 사람이 안 봐도 되지만,
     * 이건 **답을 주기 전에는 턴이 멈춰 있다.** `update_business_card` 가
     * `ConfirmationPolicy.BEFORE_EXECUTION` 이라 커널이 `confirmationGateway.confirm()`
     * 에서 매달린다. 예전에는 이 물음이 상태 줄로만 나가고 답할 방법이 없어서 — 컨테이너에
     * `answerConfirmation` 이 있는데 부르는 곳이 하나도 없었다 — 명함 수정 턴이 영원히
     * 끝나지 않았다.
     */
    var confirmation by mutableStateOf<String?>(null)
        private set

    private var turnJob: Job? = null
    private var resetting = false
    private var resetRequired = false

    /**
     * 세션이 갈릴 때마다 올린다. 진행 중이던 턴이 **새 대화에 끼어드는 것**을 막는 표식이다.
     *
     * 취소는 즉시 듣지 않는다 — 온디바이스 모델은 한 번 생성에 들어가면 중간에 협조할 지점이
     * 거의 없어서, `새 대화` 를 눌러도 옛 턴이 조금 더 살아 있다가 답을 내놓는다. 세대가
     * 다르면 그 답을 버린다. Agent_0910 의 `AgentViewModel.uiGeneration` 과 같은 장치다.
     */
    private var generation = 0L

    /** 승인 물음에 답한다. 커널이 여기서 깨어나 실행하거나 취소한다. */
    fun answerConfirmation(accepted: Boolean) {
        confirmation = null
        status = if (accepted) "작업을 실행하고 있어요." else "작업을 취소하고 있어요."
        container.answerConfirmation(accepted)
    }

    /**
     * 질문 하나를 처리한다. 화면의 전송 버튼과 디버그 인텐트가 같이 쓴다.
     *
     * 한 번에 한 턴만 돈다 — 진행 중에 들어온 요청은 큐에 쌓지 않고 무시한다. 모델이 도는
     * 동안 또 부르면 같은 세션에 두 턴이 겹쳐 기억이 섞인다.
     */
    fun send(question: String) {
        val text = question.trim()
        if (text.isEmpty() || busy || resetting || resetRequired) return
        // 대화가 시작됐음을 앱에 알린다. 작업 목록에서 앱을 지웠다가 다시 열면 빈 대화로
        // 시작한다는 보장이 이 표식에 달려 있다([HjpApplication]).
        container.onSessionUsed()
        val turn = generation
        messages += ChatMessage(isUser = true, text = text)
        // 이 턴에 도구가 찾은 명함만 답변 아래 붙인다. 안 비우면 검색을 안 한 턴이 앞 턴의
        // 카드를 물려받아, 답과 카드가 어긋난 채로 남는다.
        container.contactBackend.clear()
        busy = true
        status = "요청을 해석하고 있어요."
        turnJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            val answer = StringBuilder()
            var failure: String? = null
            // 토큰을 이어 붙이고 있는 말풍선의 자리. -1 이면 아직 안 만들었다.
            var streaming = -1

            try {
                container.engine.runTurn(text).collect { event ->
                    // 세대가 갈렸으면 이 턴의 결과는 이미 남의 대화다. 화면을 건드리지 않는다.
                    if (turn != generation) return@collect
                    when (event) {
                        is AgentEvent.TurnStarted -> Unit
                        is AgentEvent.ToolStarted -> status = event.messageKo
                        is AgentEvent.ToolFinished -> status = event.messageKo
                        is AgentEvent.ConfirmationRequested -> {
                            status = event.promptKo
                            confirmation = event.promptKo
                        }
                        is AgentEvent.PermissionRequested ->
                            status = "필요한 권한: " + event.permissions.joinToString()
                        // 도착하는 대로 말풍선에 이어 붙인다. 모아 뒀다가 끝에 한 번에
                        // 보여 주면 20초 가까이 화면이 멈춘 것처럼 보인다(실기기 18.9초).
                        is AgentEvent.Token -> {
                            answer.append(event.text)
                            if (streaming < 0) {
                                messages += ChatMessage(isUser = false, text = answer.toString())
                                streaming = messages.lastIndex
                            } else {
                                messages[streaming] = messages[streaming].copy(text = answer.toString())
                            }
                            status = "답변을 작성하고 있어요."
                        }
                        is AgentEvent.FinalMessage -> {
                            answer.setLength(0)
                            answer.append(event.text)
                        }
                        is AgentEvent.UserError -> failure = event.messageKo
                    }
                }
            } catch (cancelled: CancellationException) {
                // 취소는 실패가 아니다. 말풍선을 남기지 않고 조용히 물러난다.
                if (turn == generation) {
                    busy = false
                    status = null
                    confirmation = null
                    container.contactBackend.clear()
                }
                throw cancelled
            } catch (error: Throwable) {
                failure = error.message ?: error.javaClass.simpleName
            }

            if (turn != generation) return@launch
            busy = false
            status = null
            // 턴이 끝났으면 승인 물음도 의미가 없다. 답하지 않은 채 턴이 다른 이유로
            // 끝나는 경우(오류·세션 교체)에 물음만 화면에 남는 것을 막는다.
            confirmation = null

            val cards: List<BusinessCardRecord> = container.contactBackend.lastHits
            val answerText = failure ?: answer.toString().trim().ifBlank { "답변을 만들지 못했어요." }
            // 실기기 진단 로그. `adb logcat -s HJP` 로 본다. 폰에서 무슨 일이 일어났는지
            // 이게 없으면 화면을 눈으로 읽는 수밖에 없다.
            container.log(
                buildString {
                    append("q=").append(text)
                    append(" | ms=").append(System.currentTimeMillis() - startedAt)
                    append(" | cards=").append(cards.joinToString(",") { it.name })
                    append(" | answer=").append(answerText.replace('\n', ' ').take(120))
                    failure?.let { append(" | error=").append(it) }
                },
            )
            // 흘려보내던 말풍선을 최종본으로 바꾼다 — 근거 명함과 모델 이름이 여기서 붙는다.
            val finished = ChatMessage(
                isUser = false,
                text = answerText,
                modelLabel = container.modelLabel,
                cards = cards,
                error = failure,
            )
            if (streaming >= 0) messages[streaming] = finished else messages += finished
        }
    }

    /**
     * 진행 중인 턴을 버린다. 모델이 멈추기를 기다리지 않고 화면을 먼저 돌려준다 —
     * 세대를 올렸으므로 늦게 도착하는 답은 어차피 버려진다.
     */
    fun cancelTurn() {
        if (!busy || resetting) return
        // 승인을 기다리며 멈춰 있으면 거절로 풀어 준다. 안 그러면 커널이 계속 매달린다.
        if (confirmation != null) container.answerConfirmation(false)
        generation += 1
        turnJob?.cancel()
        turnJob = null
        busy = false
        status = null
        confirmation = null
        container.contactBackend.clear()
    }

    /** 커널 세션을 갈아끼우고 화면의 대화도 비운다. 둘은 같이 움직여야 한다. */
    fun reset() {
        if (resetting) return
        // 진행 중이던 턴을 먼저 끊는다(승인 대기도 같이 풀린다). 세대가 올라가므로
        // 늦게 끝난 옛 턴이 새 대화에 말풍선을 남기지 못한다.
        cancelTurn()
        generation += 1
        resetting = true
        resetRequired = true
        busy = true
        status = "새 대화를 준비하고 있어요."
        clearMessages()
        scope.launch {
            try {
                container.engine.resetSession()
                resetRequired = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = "대화 초기화에 실패했어요. 다시 새 대화를 눌러 주세요."
                messages += ChatMessage(isUser = false, text = message, error = message)
            } finally {
                resetting = false
                busy = false
                status = if (resetRequired) "대화 초기화가 필요해요. 새 대화를 다시 눌러 주세요." else null
            }
        }
    }

    /**
     * 화면의 말풍선만 비운다. 커널 세션 초기화는 부르는 쪽이 한다.
     *
     * [HjpApplication] 의 수명 훅이 커널 세션을 버릴 때 같이 부른다. 대화가 화면보다
     * 오래 살게 되면서 생긴 요구다 — 예전에는 화면이 사라질 때 말풍선도 같이 사라져서
     * 저절로 맞았지만, 이제는 커널 기억만 비우면 **사람이 보는 대화와 모델이 기억하는
     * 대화가 어긋난 채** 남는다.
     *
     * 메인 스레드에서 부르면 `Main.immediate` 라 그 자리에서 끝난다 — 첫 프레임 전에
     * 비우는 것이 보장돼야 하는 자리(콜드 진입)가 있기 때문이다.
     */
    fun clearTranscript() {
        scope.launch {
            cancelTurn()
            generation += 1
            clearMessages()
            status = if (resetting) "새 대화를 준비하고 있어요." else null
            confirmation = null
            busy = resetting
        }
    }

    private fun clearMessages() {
        messages.clear()
        messages += ChatMessage(isUser = false, text = GREETING)
        container.contactBackend.clear()
    }

    fun close() = scope.cancel()

    private companion object {
        const val GREETING =
            "명함에 대해 문장으로 물어보세요.\n예) \"판교에 있는 AI 개발자 찾아줘\""
    }
}
