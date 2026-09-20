package com.example.hjp

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AiStartupScreenInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @After
    fun clearPreference() {
        context.getSharedPreferences(
            AiReadyNoticePreference.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
    }

    @Test
    fun preparingScreenExplainsThatAiIsStarting() {
        compose.setContent { MaterialTheme { AiPreparingScreen() } }

        compose.onNodeWithText("AI 기능을 준비하고 있습니다").assertIsDisplayed()
        compose.onNodeWithText("대화 모델과 검색 모델을 기기에서 시작하고 있습니다.")
            .assertIsDisplayed()
    }

    @Test
    fun readyScreenOffersBothRequiredActions() {
        var doNotShowAgain = false
        var confirmed = false
        compose.setContent {
            MaterialTheme {
                AiReadyScreen(
                    onDoNotShowAgain = { doNotShowAgain = true },
                    onConfirm = { confirmed = true },
                )
            }
        }

        compose.onNodeWithText("AI 준비 완료").assertIsDisplayed()
        compose.onNodeWithText("다시 보지 않기").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(doNotShowAgain) }
        compose.onNodeWithText("확인").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(confirmed) }
    }

    @Test
    fun doNotShowAgainChoicePersistsForLaterLaunches() {
        val preference = AiReadyNoticePreference(context)
        assertTrue(preference.shouldShow())

        assertTrue(preference.doNotShowAgain())

        assertFalse(AiReadyNoticePreference(context).shouldShow())
    }
}
