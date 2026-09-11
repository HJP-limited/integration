package com.example.hjp

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var liveActivities = 0
    private var sessionTouched = false

    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(SessionLifecycleObserver())
    }

    /** Marks that the session now holds conversation state worth discarding. */
    fun markSessionUsed() {
        sessionTouched = true
    }

    override fun onTerminate() {
        container.close()
        super.onTerminate()
    }

    private inner class SessionLifecycleObserver : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity is MainActivity) {
                val recreatedByConfigChange = savedInstanceState != null
                val coldEntry = !recreatedByConfigChange && liveActivities == 0
                if (coldEntry && sessionTouched) {
                    // Runs inside Activity.onCreate, before the first composition, so a dismissed
                    // task can never show the previous conversation.
                    runBlocking { container.resetSession() }
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
            applicationScope.launch {
                container.resetSession()
                sessionTouched = false
            }
        }
    }
}
