package com.example.hjp

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface AiPreparationState {
    data object Idle : AiPreparationState
    data object Preparing : AiPreparationState
    data object Ready : AiPreparationState
    data class Failed(val message: String) : AiPreparationState
}

/**
 * Owns the single temporary agent session for the life of the process.
 *
 * What Android actually guarantees, and what this class does with it:
 *
 *  - home, another app, screen lock, recents, background/resume: the activity is stopped, never
 *    created again, so nothing here fires and the session continues.
 *  - configuration change (rotation): the activity is destroyed and recreated with a non-null
 *    `savedInstanceState`, which is the signal used to *keep* the session.
 *  - in-app exit / back out of the last activity: `isFinishing` is true and the session is dropped.
 *  - swipe-away from recents: Android gives no reliable callback when it removes a task while
 *    keeping a cached process. Rather than claiming to detect it, the next cold creation of
 *    [MainActivity] — a new activity with no other live activity and no saved state — drops the
 *    previous session *before the first frame*. The user-visible guarantee is therefore "reopening
 *    the app after its task was dismissed starts empty", which holds whether or not the process
 *    survived.
 *  - process death / force stop: nothing is persisted, so the next launch is empty by construction.
 *
 * No keep-alive service is added; nothing is written to disk; no previous session can be restored.
 */
class HjpApplication : Application() {
    val modelSetup by lazy { com.example.hjp.models.ServiceModelSetup(this) }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableAiPreparationState = MutableStateFlow<AiPreparationState>(AiPreparationState.Idle)
    internal val aiPreparationState = mutableAiPreparationState.asStateFlow()
    private var preparationJob: Job? = null
    private var liveActivities = 0
    private var sessionTouched = false

    private val containerHolder = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(this).also { it.onSessionUsed = ::markSessionUsed }
    }
    val container: AppContainer get() = containerHolder.value

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(SessionLifecycleObserver())
    }

    /** Starts one process-owned warm-up. It survives activity recreation and backgrounding. */
    @Synchronized
    internal fun prepareAi() {
        if (mutableAiPreparationState.value == AiPreparationState.Ready) return
        if (preparationJob?.isActive == true) return
        mutableAiPreparationState.value = AiPreparationState.Preparing
        preparationJob = applicationScope.launch {
            try {
                container.prepareForUse()
                mutableAiPreparationState.value = AiPreparationState.Ready
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableAiPreparationState.value = AiPreparationState.Failed(
                    error.message ?: "AI 엔진을 시작하지 못했습니다.",
                )
            }
        }
    }

    /** Marks that the session now holds conversation state worth discarding. */
    fun markSessionUsed() {
        sessionTouched = true
    }

    override fun onTerminate() {
        applicationScope.cancel()
        if (containerHolder.isInitialized()) containerHolder.value.close()
        super.onTerminate()
    }

    private inner class SessionLifecycleObserver : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity is MainActivity) {
                val recreatedByConfigChange = savedInstanceState != null
                val coldEntry = !recreatedByConfigChange && liveActivities == 0
                if (coldEntry && sessionTouched) {
                    // Clear the UI before composition, and gate new submissions until the
                    // suspending engine reset completes. Never block the Android main thread.
                    container.chat.reset()
                    sessionTouched = false
                }
            }
            liveActivities += 1
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) {
            liveActivities = (liveActivities - 1).coerceAtLeast(0)
            if (activity !is MainActivity) return
            if (activity.isChangingConfigurations) return
            if (!activity.isFinishing) return
            // Use the same submission gate as the in-app "new conversation" button. A detached
            // reset could otherwise finish after a newly reopened activity had started a turn.
            if (sessionTouched) {
                container.chat.reset()
                sessionTouched = false
            }
        }
    }
}
