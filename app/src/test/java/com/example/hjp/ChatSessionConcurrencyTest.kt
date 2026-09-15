package com.example.hjp

import com.hjp.agent.contract.AgentEvent
import com.hjp.agent.core.AgentKernelMode
import com.hjp.agent.core.AgentTurnEngine
import com.hjp.tool.contact.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** UI/session orchestration only: these controlled flows do not claim model inference coverage. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionConcurrencyTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var host: Host
    private lateinit var chat: ChatSession

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        host = Host()
        chat = ChatSession(host)
    }
    @After fun cleanup() {
        chat.close()
        Dispatchers.resetMain()
    }

    @Test fun `final answer replaces provisional streaming text`() = runTest(dispatcher) {
        host.turn = { flow { emit(AgentEvent.Token("잠정 답변")); emit(AgentEvent.FinalMessage("검증된 최종 답변")) } }
        chat.send("질문")
        advanceUntilIdle()
        assertEquals("검증된 최종 답변", chat.messages.last().text)
    }

    @Test fun `engine cancellation releases busy and confirmation state`() = runTest(dispatcher) {
        host.turn = { flow {
            emit(AgentEvent.ConfirmationRequested("수정할까요?"))
            throw CancellationException("engine session replaced")
        } }
        chat.send("질문")
        advanceUntilIdle()
        assertFalse(chat.busy)
        assertNull(chat.confirmation)
        assertNull(chat.status)
    }

    @Test fun `reset blocks sends until the engine session is replaced`() = runTest(dispatcher) {
        val resetGate = CompletableDeferred<Unit>()
        host.reset = { resetGate.await() }
        chat.reset()
        runCurrent()
        assertTrue(chat.busy)
        chat.cancelTurn() // A stop click must not reopen submission during reset.
        chat.send("너무 이른 질문")
        runCurrent()
        assertTrue(host.questions.isEmpty())
        resetGate.complete(Unit)
        advanceUntilIdle()
        assertFalse(chat.busy)
        chat.send("새 대화 질문")
        advanceUntilIdle()
        assertEquals(listOf("새 대화 질문"), host.questions)
    }

    @Test fun `clearing the transcript cancels and retires an in flight response`() = runTest(dispatcher) {
        val replyGate = CompletableDeferred<Unit>()
        host.turn = { flow { replyGate.await(); emit(AgentEvent.FinalMessage("옛 답변")) } }
        chat.send("옛 질문")
        runCurrent()
        chat.clearTranscript()
        runCurrent()
        replyGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, chat.messages.size)
        assertFalse(chat.messages.any { it.text == "옛 답변" })
    }

    @Test fun `failed reset blocks old session reuse until a successful retry`() = runTest(dispatcher) {
        host.reset = { error("reset failed") }
        chat.reset()
        advanceUntilIdle()
        assertNotNull(chat.messages.last().error)
        chat.send("실패 후 질문")
        advanceUntilIdle()
        assertTrue(host.questions.isEmpty())
        host.reset = {}
        chat.reset()
        advanceUntilIdle()
        chat.send("복구 후 질문")
        advanceUntilIdle()
        assertEquals(listOf("복구 후 질문"), host.questions)
    }

    @Test fun `late search from an old turn cannot overwrite the new turn cards`() = runTest(dispatcher) {
        val oldSearchGate = CompletableDeferred<Unit>()
        val recording = RecordingContactSearchBackend(object : EmptyBackend() {
            override suspend fun search(query: String, limit: Int): ContactSearchResponse {
                if (query == "old") oldSearchGate.await()
                return response(query)
            }
        })
        val oldSearch = launch { recording.search("old", 5) }
        runCurrent()
        recording.clear()
        recording.search("new", 5)
        oldSearchGate.complete(Unit)
        oldSearch.join()
        assertEquals(listOf("new"), recording.lastHits.map { it.id })
    }

    @Test fun `directory searches on the shared engine do not overwrite chat evidence`() = runTest(dispatcher) {
        val sharedEngine = EmptyBackend()
        val chatRecorder = RecordingContactSearchBackend(sharedEngine)
        chatRecorder.search("chat-result", 5)
        sharedEngine.search("directory-result", 20)
        assertEquals(listOf("chat-result"), chatRecorder.lastHits.map { it.id })
    }

    private class Host : ChatSessionHost {
        val questions = mutableListOf<String>()
        var turn: (String) -> Flow<AgentEvent> = { flowOf(AgentEvent.FinalMessage("답변")) }
        var reset: suspend () -> Unit = {}
        override val engine = object : AgentTurnEngine {
            override val mode = AgentKernelMode.REACT
            override fun runTurn(userText: String): Flow<AgentEvent> {
                questions += userText
                return turn(userText)
            }
            override suspend fun resetSession(): Long { reset(); return 1L }
            override fun close() = Unit
        }
        override val contactBackend = RecordingContactSearchBackend(EmptyBackend())
        override val modelLabel = "test-orchestration-only"
        override fun onSessionUsed() = Unit
        override fun answerConfirmation(accepted: Boolean) = Unit
        override fun log(message: String) = Unit
    }

    private open class EmptyBackend : ContactSearchBackend {
        override suspend fun search(query: String, limit: Int) = response(query)
        override suspend fun get(cardId: String): BusinessCardRecord? = null
        override fun engineName() = "controlled-test-backend"
        override fun configurationAvailable() = true
    }
    private companion object {
        fun response(id: String) = ContactSearchResponse(
            listOf(ContactSearchHit(BusinessCardRecord(id, id), 1.0, "", 1, emptyList())),
            "TEST", false, "", "controlled-test-backend", 0,
        )
    }
}
