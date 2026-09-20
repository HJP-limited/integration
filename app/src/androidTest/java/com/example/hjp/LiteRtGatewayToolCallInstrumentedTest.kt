package com.example.hjp

import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hjp.agent.contract.AgentEvent
import com.hjp.agent.core.ArtifactIdentification
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 실기기에서 실제 Gemma 생성, 게이트웨이 결정, 날짜·시각 도구 호출을 한 턴으로 검증한다. */
@RunWith(AndroidJUnit4::class)
class LiteRtGatewayToolCallInstrumentedTest {
    @Test
    fun actualGatewayGeneratesAndCallsCurrentDateTimeTool() = runBlocking<Unit> {
        assertTrue("arm64 실기기가 아니면 생성 모델 검증을 통과시킬 수 없습니다",
            Build.SUPPORTED_ABIS.any { it == "arm64-v8a" })
        assertFalse("에뮬레이터에서는 실제 Gemma 생성 검증을 통과시킬 수 없습니다",
            AppContainer.isAndroidEmulator())

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext
            .applicationContext as HjpApplication
        val container = application.container
        assertEquals(ArtifactIdentification.KNOWN_ARTIFACT_VERIFIED, container.deployment.identifiedBy)
        assertTrue(container.modelReady)

        val before = container.runtimeCounters.snapshot()
        val events = mutableListOf<AgentEvent>()
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "TURN_START pid=${Process.myPid()} artifact=${container.deployment.artifactId}")
        withTimeout(TURN_TIMEOUT_MILLIS) {
            container.engine.runTurn("현재 날짜와 시간을 도구로 확인해서 알려줘.")
                .collect(events::add)
        }

        val after = container.runtimeCounters.snapshot()
        val delta = after - before
        val action = container.sessionSnapshot().conversationMemory.actions.lastOrNull()
        val finalText = events.filterIsInstance<AgentEvent.FinalMessage>().lastOrNull()?.text.orEmpty()
        val streamedText = events.filterIsInstance<AgentEvent.Token>().joinToString("") { it.text }
        assertTrue("actual gateway produced no decision: $delta", delta.modelInvocationSuccesses >= 1)
        assertEquals("model invocation failed: $delta", 0, delta.modelInvocationFailures)
        assertEquals("model invocation timed out: $delta", 0, delta.modelInvocationTimeouts)
        if (before.modelLoadSuccesses == 0L) {
            assertEquals("GPU-first engine should initialize once: $delta", 1L, delta.modelLoadAttempts)
            assertEquals("GPU-first engine did not initialize: $delta", 1L, delta.modelLoadSuccesses)
        }
        assertTrue("no LiteRT-LM backend initialized: $after", after.modelLoadSuccesses >= 1L)
        assertEquals("GPU initialization fell back to CPU: $after", 0L, after.modelBackendFallbacks)
        assertTrue("actual model counter is false: $delta", delta.actualModelExecuted)
        assertTrue(events.filterIsInstance<AgentEvent.ToolStarted>().any {
            it.messageKo == DATETIME_TOOL_STARTED
        })
        assertTrue(events.filterIsInstance<AgentEvent.ToolFinished>().any {
            it.messageKo == DATETIME_TOOL_FINISHED
        })
        assertTrue(action?.executedTools?.contains(DATETIME_TOOL_NAME) == true)
        assertTrue(finalText.isNotBlank() || streamedText.isNotBlank())
        assertEquals(0, delta.semanticInvocations)
        Log.i(TAG, "TURN_SUCCESS elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}")
        // AppContainer는 Application 소유다. 여기서 닫으면 뒤 계측 테스트와 실제 앱이 깨진다.
    }

    @Test
    fun pastMeetingThanksEmailStaysComposeOnActualGemma() = runBlocking<Unit> {
        assertTrue(
            "arm64 실기기가 아니면 생성 모델 검증을 통과시킬 수 없습니다",
            Build.SUPPORTED_ABIS.any { it == "arm64-v8a" },
        )
        assertFalse(
            "에뮬레이터에서는 실제 Gemma 생성 검증을 통과시킬 수 없습니다",
            AppContainer.isAndroidEmulator(),
        )

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext
            .applicationContext as HjpApplication
        val container = application.container
        assertEquals(ArtifactIdentification.KNOWN_ARTIFACT_VERIFIED, container.deployment.identifiedBy)
        assertTrue(container.modelReady)

        container.resetSession()
        container.contactBackend.clear()
        val before = container.runtimeCounters.snapshot()
        val events = mutableListOf<AgentEvent>()
        val startedAt = SystemClock.elapsedRealtime()
        try {
            Log.i(TAG, "COMPOSE_REGRESSION_START pid=${Process.myPid()}")
            withTimeout(TURN_TIMEOUT_MILLIS) {
                container.engine.runTurn(PAST_MEETING_EMAIL_PROMPT).collect(events::add)
            }

            val delta = container.runtimeCounters.snapshot() - before
            val diagnostics = container.diagnosticsSnapshot()
            val action = container.sessionSnapshot().conversationMemory.actions.lastOrNull()
            assertTrue("actual Gemma was not invoked: $delta", delta.actualModelExecuted)
            assertEquals("ACTION_COMPOSE", diagnostics["dialogue_act"])
            assertEquals("true", diagnostics["route_search_required"])
            assertTrue("contact search did not run", container.contactBackend.searchPerformed)
            assertTrue(
                "expected contact was not retrieved: ${container.contactBackend.lastHits.map { it.id }}",
                container.contactBackend.lastHits.any { it.id == EXPECTED_CONTACT_ID },
            )
            assertTrue(
                "expected contact was not grounded: ${container.contactBackend.lastReadCardIds}",
                container.contactBackend.lastReadCardIds == listOf(EXPECTED_CONTACT_ID),
            )
            assertEquals(
                "unexpected tool order or count",
                listOf(SEARCH_CONTACTS, GET_CONTACT, OPEN_COMPOSE),
                action?.executedTools,
            )
            assertFalse(
                "past meeting text escaped into calendar execution: ${action?.executedTools}",
                action?.executedTools?.contains(CREATE_CALENDAR_EVENT) == true,
            )
            Log.i(
                TAG,
                "COMPOSE_REGRESSION_SUCCESS elapsed_ms=${SystemClock.elapsedRealtime() - startedAt} " +
                    "tools=${action?.executedTools?.joinToString(",")} " +
                    "selected=${container.sessionSnapshot().conversationMemory.selectedContact?.cardId}",
            )
        } finally {
            // EXTERNAL_APP_CONFIRMATION means the platform composer/chooser is the confirmation
            // boundary. Close that surface without selecting an app or sending anything.
            instrumentation.uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
            )
            container.resetSession()
            container.contactBackend.clear()
        }
    }

    private companion object {
        const val TAG = "HjpLiteRtGatewayVerify"
        const val TURN_TIMEOUT_MILLIS = 180_000L
        const val DATETIME_TOOL_NAME = "get_current_datetime"
        const val DATETIME_TOOL_STARTED = "현재 날짜와 시각을 확인하고 있어요."
        const val DATETIME_TOOL_FINISHED = "현재 날짜와 시각을 확인했어요."
        const val PAST_MEETING_EMAIL_PROMPT =
            "현은영님께 지난 미팅 건으로 감사 인사 메일 작성해줘"
        const val EXPECTED_CONTACT_ID = "S02878"
        const val SEARCH_CONTACTS = "search_contacts"
        const val GET_CONTACT = "get_contact"
        const val OPEN_COMPOSE = "open_compose"
        const val CREATE_CALENDAR_EVENT = "create_calendar_event"
    }
}
