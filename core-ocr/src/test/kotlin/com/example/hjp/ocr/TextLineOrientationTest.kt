package com.example.hjp.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertNull(TextLineOrientation.loadOptional(EmptyAssets) {
            error("loader must not run without an asset")
        })
    }

    @Test
    fun `a corrupt model yields no stage rather than an exception`() {
        // 이름만 맞고 내용이 엉뚱한 파일을 집었을 때 촬영 화면이 죽으면 안 된다.
        assertNull(TextLineOrientation.loadOptional(GarbageAssets) {
            throw IllegalArgumentException("not an ONNX model")
        })
    }

    @Test
    fun `the asset name matches what the repository ships`() {
        // 이름이 어긋나면 모델이 있어도 조용히 안 쓰인다 — 폰 앞에서 한참 헤매게 되는 실패다.
        assertEquals("textline_ori.onnx", TextLineOrientation.ASSET)
    }

    // ---- 짧은 글줄에는 묻지 않는다 -------------------------------------------------------

    /**
     * 이 모델은 짧은 크롭에서 **확신을 갖고 틀린다.** 아래 수치는 똑바로 선 합성 한글 글줄을
     * 실제로 재서 얻은 P(180) 이다 — 낮아야 맞는 것이다:
     *
     * ```
     *   가로세로비  글자수   P(180)
     *      1.07       1     0.752   틀림
     *      2.74       3     0.866   틀림  ("남다은")
     *      3.58       4     0.893   틀림
     *      5.26       6     0.609   틀림
     *      5.56       7     0.001   맞음
     *     12.51      17     0.000   맞음
     * ```
     *
     * 이 경계를 두기 전에는 이름이 통째로 뒤집혀 "긍그" 로 읽혔다 — 분류기를 넣기 전에는
     * 멀쩡하던 글줄이다. 신뢰도로는 못 거른다: **틀린 답이 0.9 로 확신에 차 있다.**
     */
    @Test
    fun `the classifier is only asked about lines long enough to be reliable`() {
        // 실측에서 틀린 비율들 — 물어보면 안 된다.
        listOf(160 to 150, 160 to 60, 160 to 45, 160 to 31).forEach { (w, h) ->
            assertFalse(
                "aspect ${w.toDouble() / h} should not be asked",
                TextLineOrientation.isWorthAsking(w, h),
            )
        }
        // 실측에서 맞은 비율들 — 물어봐야 한다.
        listOf(160 to 26, 160 to 19, 160 to 12).forEach { (w, h) ->
            assertTrue(
                "aspect ${w.toDouble() / h} should be asked",
                TextLineOrientation.isWorthAsking(w, h),
            )
        }
    }

    @Test
    fun `a zero-height crop is not asked about`() {
        assertFalse(TextLineOrientation.isWorthAsking(160, 0))
    }
}
