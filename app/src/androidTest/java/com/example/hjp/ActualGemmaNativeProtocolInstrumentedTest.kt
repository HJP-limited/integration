package com.example.hjp

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hjp.agent.contract.AgentEvent
import com.hjp.agent.contract.AgentRuntimeCounters
import com.hjp.agent.contract.CountingAgentModelGateway
import com.hjp.agent.core.AgentKernel
import com.hjp.agent.core.AgentRuntimeEnvironment
import com.hjp.agent.core.AgentSessionManager
import com.hjp.agent.core.AgentTurnPolicy
import com.hjp.agent.core.ContextBudget
import com.hjp.agent.core.ConversationHistoryStrategy
import com.hjp.agent.core.DefaultToolExecutor
import com.hjp.agent.core.DefaultToolObservationMapper
import com.hjp.agent.core.DefaultToolPolicyEngine
import com.hjp.agent.core.DefaultToolRegistry
import com.hjp.agent.core.InMemoryAgentSessionStore
import com.hjp.agent.core.ModelContextSelector
import com.hjp.agent.core.ToolExecutor
import com.hjp.agent.core.ToolImplementationCandidate
import com.hjp.agent.litert.LiteRtAgentModelGateway
import com.hjp.agent.litert.LiteRtBackendPreference
import com.hjp.agent.litert.NativeProtocolEvent
import com.hjp.agent.litert.NativeProtocolObserver
import com.hjp.tool.android.CalendarComposerBackend
import com.hjp.tool.android.CalendarDraft
import com.hjp.tool.android.CreateCalendarEventPlugin
import com.hjp.tool.android.MessageChannel
import com.hjp.tool.android.MessageComposerBackend
import com.hjp.tool.android.MessageDraft
import com.hjp.tool.android.OpenComposePlugin
import com.hjp.tool.contact.BusinessCardRecord
import com.hjp.tool.contact.BusinessCardUpdateResult
import com.hjp.tool.contact.ContactSearchBackend
import com.hjp.tool.contact.ContactSearchHit
import com.hjp.tool.contact.ContactSearchResponse
import com.hjp.tool.contact.CountContactsPlugin
import com.hjp.tool.contact.GetContactPlugin
import com.hjp.tool.contact.MutableBusinessCardRepository
import com.hjp.tool.contact.SearchContactsPlugin
import com.hjp.tool.contact.UpdateBusinessCardPlugin
import com.hjp.tool.contract.CatalogContext
import com.hjp.tool.contract.ConfirmationGateway
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolExecutionResult
import com.hjp.tool.datetime.GetCurrentDateTimePlugin
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActualGemmaNativeProtocolInstrumentedTest {
    @Test
    fun actualGemmaPromotesUniqueCandidateAndCreatesOnlyTheSelectedCalendarDraft() = runBlocking {
        assertTrue(Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = File(context.getExternalFilesDir("models"), "hjp-agent.litertlm")
        assertTrue("isolated test model is missing: ${model.absolutePath}", model.isFile)

        val h = Harness(context, model)
        try {
            val search = h.turn("AI 개발자 찾아줘")
            assertEquals(listOf("search_contacts"), search.tools)
            assertTrue(h.session().conversationMemory.candidateContacts.size >= 4)
            assertEquals(null, h.session().conversationMemory.selectedContact)

            val calendar = h.turn("서수씨랑 내일 3시에 일정 잡아줘")
            assertEquals(
                "events=${calendar.events}; protocol=${calendar.protocol}; failures=${calendar.modelFailures}",
                setOf("get_current_datetime", "get_contact", "create_calendar_event"),
                calendar.tools.toSet(),
            )
            assertEquals("SYN-AI-2", h.session().conversationMemory.selectedContact?.cardId)
            assertEquals(1, h.calendar.drafts.size)
            assertEquals(listOf("seosu@example.invalid"), h.calendar.drafts.single().attendeeEmails)
            assertEquals(1, calendar.tools.count { it == "get_current_datetime" })
            assertEquals(1, calendar.tools.count { it == "get_contact" })
            assertEquals(1, calendar.tools.count { it == "create_calendar_event" })
            val sideEffectIndex = calendar.tools.indexOf("create_calendar_event")
            assertTrue(calendar.tools.indexOf("get_current_datetime") in 0 until sideEffectIndex)
            assertTrue(calendar.tools.indexOf("get_contact") in 0 until sideEffectIndex)
            assertTrue(calendar.arguments.none { (_, args) ->
                args.toString().contains("SYN-AI-1") ||
                    args.toString().contains("SYN-AI-3") ||
                    args.toString().contains("SYN-AI-4")
            })
            h.assertModelToolRoundTrip("get_current_datetime", calendar.protocol)
            h.assertModelToolRoundTrip("get_contact", calendar.protocol)
            assertTrue(h.counters.snapshot().actualModelExecuted)
        } finally {
            h.close()
        }
    }

    @Test
    fun actualGemmaExercisesNativeRolesGroundedBootstrapA52AndReadOnlySafety() = runBlocking {
        assertTrue(Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = File(context.getExternalFilesDir("models"), "hjp-agent.litertlm")
        assertTrue("isolated test model is missing: ${model.absolutePath}", model.isFile)

        val h = Harness(context, model)
        try {
            val datetime = h.turn("현재 날짜와 시간을 도구로 확인해서 알려줘.")
            assertEquals("events=${datetime.events}; protocol=${datetime.protocol}; failures=${datetime.modelFailures}",
                listOf("get_current_datetime"), datetime.tools)
            h.assertModelToolRoundTrip("get_current_datetime", datetime.protocol)

            h.reset()
            val grounded = h.turn("김가온 명함 찾아줘")
            assertEquals(listOf("search_contacts"), grounded.tools)
            assertTrue(grounded.protocol.any {
                it.conversation == "grounded-read" && it.kind == "created"
            })
            assertTrue(grounded.protocol.any {
                it.conversation == "grounded-read" && it.kind == "grounded-read-result" &&
                    it.toolNames == listOf("search_contacts")
            })
            assertEquals("SYN-001", h.session().conversationMemory.candidateContacts.single().cardId)

            val compose = h.turn("그 사람에게 제목은 검증, 내용은 확인 부탁드립니다 라고 메일 작성해줘")
            assertEquals(listOf("get_contact", "open_compose"), compose.tools)
            assertEquals("synthetic1@example.invalid", h.messages.drafts.single().to)
            assertTrue(compose.protocol.any { it.conversation == "main" && it.kind == "reset" })
            h.assertModelToolRoundTrip("get_contact", compose.protocol)
            assertTrue(compose.protocol.any {
                it.role == "assistant" && it.kind == "tool-call" && "open_compose" in it.toolNames
            })

            val draftsBeforeRead = h.messages.drafts.size
            val readOnly = h.turn("그 사람 회사 알려줘")
            assertEquals(listOf("get_contact"), readOnly.tools)
            assertEquals(draftsBeforeRead, h.messages.drafts.size)

            h.reset()
            h.turn("김가온 명함 찾아줘")
            val a52 = h.turn("박도윤씨에게 제목은 새 대상, 내용은 확인 요청입니다 라고 메일 작성해줘")
            assertEquals(listOf("search_contacts", "get_contact", "open_compose"), a52.tools)
            assertEquals("synthetic2@example.invalid", h.messages.drafts.last().to)
            assertEquals("SYN-002", h.session().conversationMemory.selectedContact?.cardId)
            assertTrue(a52.arguments.none { (_, args) -> args.toString().contains("SYN-001") })
            h.assertModelToolRoundTrip("search_contacts", a52.protocol)
            h.assertModelToolRoundTrip("get_contact", a52.protocol)

            val draftsBeforeA52Read = h.messages.drafts.size
            val a52Read = h.turn("그 사람 회사 알려줘")
            assertEquals(listOf("get_contact"), a52Read.tools)
            assertEquals(draftsBeforeA52Read, h.messages.drafts.size)

            assertTrue(h.counters.snapshot().actualModelExecuted)
        } finally {
            h.close()
        }
    }

    private class Harness(context: Context, model: File) {
        private val cards = mutableListOf(
            BusinessCardRecord(
                "SYN-001", "김가온", company = "합성알파", title = "검증자",
                email = "synthetic1@example.invalid", memo = "synthetic-only",
            ),
            BusinessCardRecord(
                "SYN-002", "박도윤", company = "합성베타", title = "검증자",
                email = "synthetic2@example.invalid", memo = "synthetic-only",
            ),
            BusinessCardRecord(
                "SYN-AI-1", "고하늘", company = "합성에이아이", title = "AI 개발자",
                email = "gohaneul@example.invalid", memo = "synthetic-only",
            ),
            BusinessCardRecord(
                "SYN-AI-2", "서수", company = "합성에이아이", title = "AI 개발자",
                email = "seosu@example.invalid", memo = "synthetic-only",
            ),
            BusinessCardRecord(
                "SYN-AI-3", "윤다온", company = "합성에이아이", title = "AI 개발자",
                email = "yundaon@example.invalid", memo = "synthetic-only",
            ),
            BusinessCardRecord(
                "SYN-AI-4", "한가람", company = "합성에이아이", title = "AI 개발자",
                email = "hangaram@example.invalid", memo = "synthetic-only",
            ),
        )
        private val repository = SyntheticRepository(cards)
        private val backend = SyntheticSearchBackend(repository)
        val messages = RecordingMessages()
        val calendar = RecordingCalendar()
        private val plugins = listOf(
            SearchContactsPlugin(backend),
            CountContactsPlugin(backend),
            GetContactPlugin(backend),
            UpdateBusinessCardPlugin(repository),
            CreateCalendarEventPlugin(calendar),
            OpenComposePlugin(messages),
            GetCurrentDateTimePlugin { 1_758_240_000_000L },
        )
        private val registry = DefaultToolRegistry(plugins.map { ToolImplementationCandidate(it) })
        private val store = InMemoryAgentSessionStore { 1_758_240_000_000L }
        val counters = AgentRuntimeCounters()
        private val allProtocol = mutableListOf<NativeProtocolEvent>()
        private val gateway = LiteRtAgentModelGateway(
            model,
            File(context.cacheDir, "actual-gemma-native-protocol"),
            LiteRtBackendPreference.CPU_ONLY,
            counters,
            NativeProtocolObserver { event -> synchronized(allProtocol) { allProtocol += event } },
        )
        private val sessions = AgentSessionManager(
            store,
            CountingAgentModelGateway(gateway, counters),
            HjpSystemInstruction.TEXT,
            "ko-KR",
            clockMillis = { 1_758_240_000_000L },
        )
        private val tools = mutableListOf<String>()
        private val arguments = mutableListOf<Pair<String, kotlinx.serialization.json.JsonObject>>()
        private val modelFailures = mutableListOf<String>()
        private val executor = object : ToolExecutor {
            private val delegate = DefaultToolExecutor(registry)
            override suspend fun execute(
                call: com.hjp.agent.contract.ModelToolCall,
                snapshot: com.hjp.tool.contract.ToolCatalogSnapshot,
                context: ToolExecutionContext,
            ): ToolExecutionResult {
                tools += call.modelToolName
                arguments += call.modelToolName to call.arguments
                return delegate.execute(call, snapshot, context)
            }
        }
        private val kernel = AgentKernel(
            registry,
            executor,
            DefaultToolPolicyEngine(),
            sessions,
            DefaultToolObservationMapper(),
            SafeEnvironment(),
            AgentTurnPolicy(
                maxToolCalls = 6,
                maxProtocolCorrections = 1,
                historyStrategy = ConversationHistoryStrategy.APP_CANONICAL_BOOTSTRAP,
            ),
            contextSelector = ModelContextSelector(ContextBudget(maxPromptTokens = 3_072)),
            contactDirectory = RepositoryContactDirectory(repository),
            runtimeCounters = counters,
            modelSessionFailureObserver = { error ->
                modelFailures += generateSequence(error) { it.cause }
                    .joinToString(" <- ") { "${it::class.java.name}: ${it.message}" }
            },
        )

        suspend fun turn(text: String): Record {
            tools.clear()
            arguments.clear()
            val protocolStart = synchronized(allProtocol) { allProtocol.size }
            val events = withTimeout(180_000L) { kernel.runTurn(text).toList() }
            val protocol = synchronized(allProtocol) { allProtocol.drop(protocolStart) }
            val record = Record(tools.toList(), arguments.toList(), protocol, events, modelFailures.toList())
            Log.i(TAG, "turn tools=${record.tools} roles=${record.protocol}")
            return record
        }

        suspend fun reset() = kernel.resetSession()
        suspend fun session() = store.getOrCreate()
        fun close() = sessions.close()

        fun assertModelToolRoundTrip(tool: String, events: List<NativeProtocolEvent>) {
            val call = events.indexOfFirst {
                it.role == "assistant" && it.kind == "tool-call" && tool in it.toolNames
            }
            val result = events.indexOfFirst {
                it.role == "tool" && it.kind == "tool-result" && it.toolNames == listOf(tool)
            }
            assertTrue("missing assistant call for $tool: $events", call >= 0)
            assertTrue("missing or unordered tool result for $tool: $events", result > call)
        }
    }

    private data class Record(
        val tools: List<String>,
        val arguments: List<Pair<String, kotlinx.serialization.json.JsonObject>>,
        val protocol: List<NativeProtocolEvent>,
        val events: List<AgentEvent>,
        val modelFailures: List<String>,
    )

    private class SyntheticSearchBackend(
        private val repository: SyntheticRepository,
    ) : ContactSearchBackend {
        override suspend fun search(query: String, limit: Int): ContactSearchResponse {
            val compact = query.replace(" ", "").lowercase()
            val trimmed = query.trim().trimEnd('씨', '님').lowercase()
            val hits = repository.loadAll().filter { card ->
                compact.contains(card.name.lowercase()) ||
                    card.name.lowercase().contains(trimmed) ||
                    card.title?.replace(" ", "")?.lowercase()?.let { compact.contains(it) || it.contains(compact) } == true ||
                    card.company?.replace(" ", "")?.lowercase()?.let { compact.contains(it) || it.contains(compact) } == true ||
                    card.memo?.replace(" ", "")?.lowercase()?.let { compact.contains(it) || it.contains(compact) } == true
            }.take(limit).mapIndexed { index, card ->
                ContactSearchHit(card, 1.0 - index * 0.01, "synthetic exact name", index + 1, listOf("test-exact"))
            }
            return ContactSearchResponse(hits, "HYBRID", false, "", "synthetic-model-backed", 0)
        }
        override suspend fun get(cardId: String) = repository.getById(cardId)
        override suspend fun countMatching(query: String): Int = repository.loadAll().size
        override fun engineName() = "synthetic-model-backed"
        override fun configurationAvailable() = true
    }

    private class SyntheticRepository(initial: List<BusinessCardRecord>) : MutableBusinessCardRepository {
        private val cards = initial.toMutableList()
        override suspend fun loadAll(): List<BusinessCardRecord> = cards.toList()
        override suspend fun getById(cardId: String): BusinessCardRecord? = cards.firstOrNull { it.id == cardId }
        override suspend fun update(
            cardId: String,
            updates: Map<String, String>,
            clearFields: Set<String>,
            updatedAt: String,
        ): BusinessCardUpdateResult? {
            val index = cards.indexOfFirst { it.id == cardId }
            if (index < 0) return null
            val before = cards[index]
            val after = before.copy(
                name = updates["name"] ?: before.name,
                company = updates["company"] ?: before.company,
                memo = updates["memo"] ?: before.memo,
                updatedAt = updatedAt,
            )
            cards[index] = after
            return BusinessCardUpdateResult(before, after)
        }
        override suspend fun restore(snapshot: BusinessCardRecord): Boolean {
            val index = cards.indexOfFirst { it.id == snapshot.id }
            if (index < 0) return false
            cards[index] = snapshot
            return true
        }
    }

    private class RecordingMessages : MessageComposerBackend {
        val drafts = mutableListOf<MessageDraft>()
        override fun isAvailable(channel: MessageChannel?) = true
        override suspend fun open(draft: MessageDraft): Boolean { drafts += draft; return true }
    }

    private class RecordingCalendar : CalendarComposerBackend {
        val drafts = mutableListOf<CalendarDraft>()
        override fun isAvailable() = true
        override suspend fun open(draft: CalendarDraft): Boolean { drafts += draft; return true }
    }

    private class SafeEnvironment : AgentRuntimeEnvironment {
        override val localeTag = "ko-KR"
        override val timeZoneId = "Asia/Seoul"
        override suspend fun grantedPermissions() = emptySet<String>()
        override suspend fun deviceCapabilities() = setOf(
            "android.external_ui", "contact.local_search", "contact.local_update", "datetime.current",
        )
        override suspend fun toolContext(sessionId: String, turnId: String) = ToolExecutionContext(
            sessionId,
            turnId,
            localeTag,
            timeZoneId,
            confirmationGateway = ConfirmationGateway { true },
        )
    }

    private companion object { const val TAG = "HjpNativeProtocolAudit" }
}
