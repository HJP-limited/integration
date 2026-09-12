package com.example.hjp.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * 글줄 하나가 똑바로 섰는지 뒤집혔는지 판정한다(`PP-LCNet_x0_25_textline_ori`).
 *
 * 왜 필요한가: 검출기는 글줄을 사각형으로 잘라 주지만 **위아래는 알려주지 않는다.**
 * 세로로 쓴 글줄은 [OcrPipeline.cropQuad] 가 90도 돌려 가로로 눕히는데, 어느 쪽으로 돌리든
 * 원래 글자가 어느 방향이었는지에 따라 결과가 180도 뒤집힐 수 있다. 뒤집힌 채로 인식기에
 * 넣으면 글자가 통째로 엉킨다.
 *
 * 참조 구현(`OCR/ocr.py`)은 이 단계를 **항상** 켜 둔다. 우리 이식본에는 이 단계가 없어서
 * 크롭을 어느 쪽으로 돌리는지가 곧 결과였다(그래서 회전 방향만이라도 원본과 맞춰 뒀다).
 *
 * **없으면 없는 대로 돈다.** 모델 파일이 배치되지 않았으면 [createOrNull] 이 null 을 주고
 * 파이프라인은 예전처럼 크롭을 그대로 인식한다 — KIE 분류기와 같은 규칙이다. 인식 품질이
 * 떨어질 뿐 촬영 자체는 살아 있어야 한다.
 */
class TextLineOrientation private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : AutoCloseable {

    /**
     * 뒤집혔으면 제자리에서 180도 돌린다. 판정에 실패하면 그대로 둔다 — 확신이 없을 때
     * 돌리는 것은 안 돌리는 것보다 나쁘다(멀쩡한 글줄을 뒤집는다).
     */
    fun upright(crop: Mat) {
        if (crop.empty()) return
        val flipped = runCatching { isUpsideDown(crop) }.getOrDefault(false)
        if (flipped) Core.rotate(crop, crop, Core.ROTATE_180)
    }

    private fun isUpsideDown(crop: Mat): Boolean {
        val input = FloatArray(3 * INPUT_H * INPUT_W)
        val resized = Mat()
        val float = Mat()
        try {
            // 원본 규격(inference.yml): ResizeImage [160, 80] -> NormalizeImage -> ToCHWImage.
            // 가로세로비를 지키지 않고 그냥 늘린다 — 참조 구현이 그렇게 학습·추론한다.
            Imgproc.resize(crop, resized, Size(INPUT_W.toDouble(), INPUT_H.toDouble()))
            resized.convertTo(float, CvType.CV_32FC3, 1.0 / 255.0)
            val hwc = FloatArray(INPUT_H * INPUT_W * 3)
            float.get(0, 0, hwc)
            val plane = INPUT_H * INPUT_W
            for (i in 0 until plane) {
                // OpenCV 는 BGR, 분류기는 RGB 로 학습됐다. 여기서 뒤집어 준다.
                val b = hwc[i * 3]
                val g = hwc[i * 3 + 1]
                val r = hwc[i * 3 + 2]
                input[i] = (r - MEAN[0]) / STD[0]
                input[plane + i] = (g - MEAN[1]) / STD[1]
                input[2 * plane + i] = (b - MEAN[2]) / STD[2]
            }
        } finally {
            resized.release()
            float.release()
        }

        OnnxTensor.createTensor(
            env, FloatBuffer.wrap(input),
            longArrayOf(1, 3, INPUT_H.toLong(), INPUT_W.toLong()),
        ).use { tensor ->
            session.run(mapOf(INPUT_NAME to tensor)).use { out ->
                // 그래프에 softmax 가 들어 있어 두 값의 합이 1 이다(실측 확인). 차이가 아니라
                // 확률 자체로 판정한다 — 차이로 보면 0.669 대 0.331 같은 진짜 뒤집힘을 놓친다.
                val probabilities = (out[0] as OnnxTensor).floatBuffer
                return probabilities.get(1) > FLIP_THRESHOLD
            }
        }
    }

    override fun close() = session.close()

    companion object {
        const val ASSET = "textline_ori.onnx"

        /** `inference.yml` 의 ResizeImage size 는 [가로, 세로] 순서다. */
        private const val INPUT_W = 160
        private const val INPUT_H = 80
        private const val INPUT_NAME = "x"

        /** ImageNet 통계. 원본 `inference.yml` 의 NormalizeImage 와 같은 값이다. */
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        /**
         * 뒤집혔을 확률이 이보다 커야 돌린다. 단순 다수결(0.5)보다 조금 높게 잡았다.
         *
         * 분류기는 0/180 둘 중 하나를 반드시 고르므로, 장식 글자나 로고처럼 위아래가 무의미한
         * 조각에서도 답을 낸다. 그런 경우 절반은 틀리는데, **틀려서 돌린 쪽이 안 돌린 쪽보다
         * 나쁘다** — 원래 멀쩡했을 글줄을 뒤집기 때문이다. 그래서 반반에 가까우면 그대로 둔다.
         *
         * 실측(합성 한글 글줄): 똑바로 0.88~1.00, 뒤집힘 0.67~1.00. 0.6 이면 그 뒤집힘들을
         * 잡으면서 애매한 구간은 건드리지 않는다.
         */
        private const val FLIP_THRESHOLD = 0.6f

        /** 모델이 없거나 로드에 실패하면 null — 파이프라인이 이 단계를 건너뛴다. */
        fun createOrNull(env: OrtEnvironment, assets: OcrAssets): TextLineOrientation? {
            val bytes = assets.bytes(ASSET) ?: return null
            return runCatching {
                TextLineOrientation(env, env.createSession(bytes, OrtSession.SessionOptions()))
            }.getOrNull()
        }
    }
}
