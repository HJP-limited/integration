package com.example.hjp.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 방향 분류기가 **없을 때** 파이프라인이 어떻게 되는지 고정한다.
 *
 * 모델을 읽는 시험은 여기 없다 — 실제 추론은 ONNX Runtime 네이티브가 필요해서 JVM 단위
 * 시험으로는 못 돌린다(det·rec 도 같은 이유로 여기서 안 돈다). 대신 **없어도 죽지 않는다**는
 * 계약을 지킨다. KIE 분류기(113MB)가 저장소에 없을 수 있는 것과 같은 이유로, 자산이 빠진
 * 상태에서도 촬영·인식 자체는 살아 있어야 하기 때문이다.
 *
 * 모델이 실제로 뒤집힌 글줄을 가려내는지는 변환 직후 확인했다: 합성 한글 글줄 기준으로
 * 똑바로 0.88~1.00 / 뒤집힘 0.67~1.00 로 양쪽이 분리됐다. 그 값이 [TextLineOrientation] 의
 * 임계값 주석에 남아 있다.
 */
class TextLineOrientationTest {

    /** 아무 자산도 없는 공급자. */
    private object EmptyAssets : OcrAssets {
        override fun bytes(name: String): ByteArray? = null
    }

    /** 파일은 있는데 ONNX 가 아닌 경우. */
    private object GarbageAssets : OcrAssets {
        override fun bytes(name: String): ByteArray? =
            if (name == TextLineOrientation.ASSET) "not a model".toByteArray() else null
    }

    @Test
    fun `a missing model yields no stage rather than an exception`() {
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        assertNull(TextLineOrientation.createOrNull(env, EmptyAssets))
    }

    @Test
    fun `a corrupt model yields no stage rather than an exception`() {
        // 이름만 맞고 내용이 엉뚱한 파일을 집었을 때 촬영 화면이 죽으면 안 된다.
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        assertNull(TextLineOrientation.createOrNull(env, GarbageAssets))
    }

    @Test
    fun `the asset name matches what the repository ships`() {
        // 이름이 어긋나면 모델이 있어도 조용히 안 쓰인다 — 폰 앞에서 한참 헤매게 되는 실패다.
        assertEquals("textline_ori.onnx", TextLineOrientation.ASSET)
    }
}
