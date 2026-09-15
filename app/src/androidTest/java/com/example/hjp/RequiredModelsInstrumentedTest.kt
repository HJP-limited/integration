package com.example.hjp

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.hjp.ocr.AndroidOcr
import com.example.hjp.search.AndroidEmbeddingGemmaEngine
import com.hjp.agent.core.ArtifactIdentification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 통합 완료 게이트: 필수 모델이 하나라도 없거나 로드되지 않으면 skip 없이 실패한다. */
@RunWith(AndroidJUnit4::class)
class RequiredModelsInstrumentedTest {
    @Test
    fun everyRequiredModelIsPresentAndLoadableOnPhysicalArm64Device() {
        assertTrue("통합 완료 테스트는 arm64 실기기에서만 실행해야 합니다", Build.SUPPORTED_ABIS.any {
            it == "arm64-v8a"
        })
        assertFalse("에뮬레이터 결과는 통합 완료로 인정하지 않습니다", AppContainer.isAndroidEmulator())

        val application = ApplicationProvider.getApplicationContext<HjpApplication>()
        val container = application.container
        assertEquals(ArtifactIdentification.KNOWN_ARTIFACT_VERIFIED, container.deployment.identifiedBy)
        assertTrue("Gemma 생성 모델이 없거나 검증되지 않았습니다", container.modelReady)

        val ocr = AndroidOcr.createOrNull(application)
        assertTrue("det/rec OCR 모델 또는 OpenCV를 로드하지 못했습니다", ocr != null)
        ocr!!.use {
            assertEquals("KIE 모델/토크나이저가 로드되지 않았습니다", "MiniLM KIE", it.fieldClassifier)
            assertTrue("textline_ori 모델이 로드되지 않았습니다", it.textLineOrientationModelLoaded)
        }

        AndroidEmbeddingGemmaEngine(application).use { embedding ->
            assertTrue(embedding.diagnosticStatus(), embedding.isModelBacked)
            assertEquals(768, embedding.embedQuery("필수 모델 로드 검증").size)
        }
    }
}
