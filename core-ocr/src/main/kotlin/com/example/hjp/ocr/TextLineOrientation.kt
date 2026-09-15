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
        // 짧은 크롭은 이 모델이 못 맞힌다. 묻지 않는다 — [isWorthAsking] 참고.
        if (!isWorthAsking(crop.cols(), crop.rows())) return
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
         * 이보다 납작한 글줄에만 묻는다. 짧은 크롭에서는 **확신을 갖고 틀리기** 때문이다.
         *
         * 실측(합성 한글 글줄, 똑바로 선 크롭의 P(180) — 낮아야 맞는 것):
         *
         * ```
         *   가로세로비  글자수   P(180)
         *      1.07       1     0.752   <- 틀림
         *      1.89       2     0.755   <- 틀림
         *      2.74       3     0.866   <- 틀림 ("남다은")
         *      3.58       4     0.893   <- 틀림
         *      5.26       6     0.609   <- 틀림
         *      5.56       7     0.001   <- 맞음
         *      8.39      11     0.000   <- 맞음
         *     12.51      17     0.000   <- 맞음
         * ```
         *
         * 긴 글줄에서 0.001 대 0.999 로 깨끗하게 갈리는 것이 전처리가 맞다는 증거다. 모델이
         * 짧은 크롭에 약한 것이고, 그건 신뢰도로 거를 수 없다 — **틀린 답이 0.9 로 확신에 차
         * 있다.** 실제로 이 경계를 두기 전에는 이름("남다은")이 통째로 뒤집혀 "긍그" 로 읽혔다.
         *
         * 경계는 5.56 부터 맞으므로 여유를 둬 6 으로 잡았다. 이보다 짧은 글줄은 이 단계가
         * 없던 때와 같게 지나간다 — 방향 보정을 못 할 뿐, 멀쩡한 글줄을 망치지는 않는다.
         */
        private const val MIN_ASPECT = 6.0

        /**
         * 이 크기의 글줄에 대해 모델의 답을 믿어도 되는가.
         *
         * Mat 이 아니라 치수만 받는다 — 규칙을 눈으로 확인할 수 있어야 하는데, OpenCV 네이티브는
         * JVM 단위 시험에 없어서 Mat 을 만드는 순간 시험이 못 돈다.
         */
        internal fun isWorthAsking(width: Int, height: Int): Boolean =
            height > 0 && width.toDouble() / height >= MIN_ASPECT

        /**
         * 뒤집혔을 확률이 이보다 커야 돌린다. [MIN_ASPECT] 를 통과한 크롭은 보통 0.001 이나
         * 0.999 로 나오므로 이 값이 실제로 갈라야 할 일은 드물다 — 경계가 애매한 크롭을 위한
         * 두 번째 안전장치다.
         */
        private const val FLIP_THRESHOLD = 0.6f

        /** 모델이 없거나 로드에 실패하면 null — 파이프라인이 이 단계를 건너뛴다. */
        fun createOrNull(env: OrtEnvironment, assets: OcrAssets): TextLineOrientation? =
            loadOptional(assets) { bytes ->
                OrtSession.SessionOptions().use { options ->
                    TextLineOrientation(env, env.createSession(bytes, options))
                }
            }

        /** 선택 자산 계약을 네이티브 런타임 없이도 시험할 수 있게 한 단일 실패 경계. */
        internal fun <T> loadOptional(assets: OcrAssets, loader: (ByteArray) -> T): T? {
            val bytes = assets.bytes(ASSET) ?: return null
            return runCatching { loader(bytes) }.getOrNull()
        }
    }
}
