package com.example.hjp

import android.content.Context

/** Persists only the user's choice to skip the post-warm-up notice on later app launches. */
internal class AiReadyNoticePreference(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun shouldShow(): Boolean = !preferences.getBoolean(KEY_DO_NOT_SHOW_AGAIN, false)

    fun doNotShowAgain(): Boolean = preferences.edit()
        .putBoolean(KEY_DO_NOT_SHOW_AGAIN, true)
        .commit()

    internal companion object {
        const val PREFERENCES_NAME = "ai_ready_notice"
        const val KEY_DO_NOT_SHOW_AGAIN = "do_not_show_again"
    }
}
