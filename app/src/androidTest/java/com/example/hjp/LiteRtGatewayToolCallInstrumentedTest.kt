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
    fun actualGatewayGeneratesAndCallsCurrentDateTimeTool() = runBlocking {
        assertTrue("arm64 실기기가 아니면 생성 모델 검증을 통과시킬 수 없습니다",
            Build.SUPPORTED_ABIS.any { it == "arm64-v8a" })
        assertFalse("에뮬레이터에서는 실제 Gemma 생성 검증을 통과시킬 수 없습니다",
            AppContainer.isAndroidEmulator())

        val application = InstrumentationRegistry.getInstrumentation().targetContext
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

        val delta = container.runtimeCounters.snapshot() - before
        val action = container.sessionSnapshot().conversationMemory.actions.lastOrNull()
        val finalText = events.filterIsInstance<AgentEvent.FinalMessage>().lastOrNull()?.text.orEmpty()
        val streamedText = events.filterIsInstance<AgentEvent.Token>().joinToString("") { it.text }
        assertTrue("actual gateway produced no decision: $delta", delta.modelInvocationSuccesses >= 1)
        assertEquals("model invocation failed: $delta", 0, delta.modelInvocationFailures)
        assertEquals("model invocation timed out: $delta", 0, delta.modelInvocationTimeouts)
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

    private companion object {
        const val TAG = "HjpLiteRtGatewayVerify"
        const val TURN_TIMEOUT_MILLIS = 180_000L
        const val DATETIME_TOOL_NAME = "get_current_datetime"
        const val DATETIME_TOOL_STARTED = "현재 날짜와 시각을 확인하고 있어요."
        const val DATETIME_TOOL_FINISHED = "현재 날짜와 시각을 확인했어요."
    }
}
