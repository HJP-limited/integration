package com.example.hjp

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.hjp.ocr.AndroidOcrAssets
import com.example.hjp.ocr.KieParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** APK에 패키징된 tokenizer와 KIE 분류기를 실제 Android ONNX Runtime으로 검증한다. */
@RunWith(AndroidJUnit4::class)
class OcrAssetsInstrumentedTest {
    @Test
    fun packagedTrimmedTokenizerAndClassifierAreAWorkingPair() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val parser = KieParser.createOrNull(AndroidOcrAssets(context))
        assertNotNull("KIE pair failed the startup canary", parser)

        val samples = listOf(
            "홍길동" to "name_ko",
            "Gildong Hong" to "name_en",
            "대표이사" to "title",
            "AI사업부" to "department",
            "(주)에이비씨" to "company_ko",
            "010-1234-5678" to "mobile",
            "02-555-1234" to "tel_office",
            "gildong@abc.com" to "email",
            "www.abc.com" to "website",
            "서울특별시 강남구 테헤란로 123" to "address_ko",
        )
        parser!!.use {
            assertEquals(samples.map { sample -> sample.second }, it.classify(samples.map { sample -> sample.first }))
        }
    }
}
