package com.example.hjp

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hjp.agent.contract.AgentEvent
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Guards the process-owned GPU engine against accidental Activity lifecycle ownership. */
@RunWith(AndroidJUnit4::class)
class BackgroundEngineRetentionInstrumentedTest {
    @Test
    fun homeStyleBackgroundAndResumeReuseTheInitializedGpuEngine() = runBlocking<Unit> {
        assertTrue(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" })
        assertFalse(AppContainer.isAndroidEmulator())

        val application = InstrumentationRegistry.getInstrumentation().targetContext
            .applicationContext as HjpApplication
        val container = application.container
        assertTrue(container.modelReady)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            val firstMillis = runDatetimeTurn(container, FIRST_PROMPT)
            val afterFirst = container.runtimeCounters.snapshot()
            assertTrue("the actual model was not initialized: $afterFirst",
                afterFirst.modelLoadSuccesses >= 1L)
            assertEquals("GPU initialization fell back to CPU: $afterFirst",
                0L, afterFirst.modelBackendFallbacks)

            // CREATED is the ActivityScenario representation of a stopped/background Activity.
            // The Application and its AppContainer remain alive, exactly as after pressing HOME.
            scenario.moveToState(Lifecycle.State.CREATED)
            val whileBackgrounded = container.runtimeCounters.snapshot()
            scenario.moveToState(Lifecycle.State.RESUMED)

            val secondMillis = runDatetimeTurn(container, SECOND_PROMPT)
            val afterSecond = container.runtimeCounters.snapshot()
            assertEquals("backgrounding triggered another engine load attempt",
                whileBackgrounded.modelLoadAttempts, afterSecond.modelLoadAttempts)
            assertEquals("backgrounding replaced the initialized engine",
                whileBackgrounded.modelLoadSuccesses, afterSecond.modelLoadSuccesses)
            assertEquals("background resume fell back to CPU",
                whileBackgrounded.modelBackendFallbacks, afterSecond.modelBackendFallbacks)
            Log.i(TAG, "RETENTION_SUCCESS first_ms=$firstMillis resumed_ms=$secondMillis " +
                "load_attempts=${afterSecond.modelLoadAttempts}")
        }
    }

    private suspend fun runDatetimeTurn(container: AppContainer, prompt: String): Long {
        val before = container.runtimeCounters.snapshot()
        val events = mutableListOf<AgentEvent>()
        val startedAt = SystemClock.elapsedRealtime()
        withTimeout(TURN_TIMEOUT_MILLIS) {
            container.engine.runTurn(prompt).collect(events::add)
        }
        val elapsedMillis = SystemClock.elapsedRealtime() - startedAt
        val delta = container.runtimeCounters.snapshot() - before
        val action = container.sessionSnapshot().conversationMemory.actions.lastOrNull()
        assertTrue("actual Gemma did not execute: $delta", delta.actualModelExecuted)
        assertTrue("datetime tool did not execute: $action",
            action?.executedTools?.contains(DATETIME_TOOL_NAME) == true)
        assertTrue("turn produced no answer",
            events.any { it is AgentEvent.FinalMessage || it is AgentEvent.Token })
        return elapsedMillis
    }

    private companion object {
        const val TAG = "HjpEngineRetention"
        const val DATETIME_TOOL_NAME = "get_current_datetime"
        const val TURN_TIMEOUT_MILLIS = 180_000L
        const val FIRST_PROMPT = "현재 날짜와 시간을 도구로 확인해서 알려줘."
        const val SECOND_PROMPT = "지금 시각을 도구로 다시 확인해줘."
    }
}
