package com.example.hjp.ocr

import android.content.Context
import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/** APK assets/ocr/ 아래에서 모델을 읽는다. */
class AndroidOcrAssets(context: Context) : OcrAssets {
    private val appContext = context.applicationContext

    override fun bytes(name: String): ByteArray? =
        try {
            appContext.assets.open("ocr/$name").use { it.readBytes() }
        } catch (_: Throwable) {
            null
        }
}

/**
 * 안드로이드용 OCR 진입점. 파이프라인 본체는 [OcrPipeline](:core-ocr)이고 여기서는
 * Bitmap 을 OpenCV BGR Mat 으로 바꿔 넘기는 일만 한다.
 *
 * KIE 모델이 없으면 [CardParser] 휴리스틱으로 자동 폴백한다 — 분류 정확도는 떨어지지만
 * (98.0% → 85.3%) 촬영·인식 자체는 그대로 동작해야 한다.
 */
class AndroidOcr private constructor(
    private val pipeline: OcrPipeline,
    private val kie: KieParser?,
) {
    val fieldClassifier: String = if (kie != null) "MiniLM KIE" else "CardParser 휴리스틱(폴백)"

    fun read(bitmap: Bitmap): Result {
        val bgr = Mat()
        Utils.bitmapToMat(bitmap, bgr) // RGBA
        Imgproc.cvtColor(bgr, bgr, Imgproc.COLOR_RGBA2BGR)
        return try {
            val regions = pipeline.run(bgr)
            Result(regions, kie?.parse(regions) ?: CardParser.parse(regions))
        } finally {
            bgr.release()
        }
    }

    data class Result(
        val regions: List<OcrPipeline.Region>,
        val fields: List<CardParser.Field>,
    )

    companion object {
        /** OpenCV 네이티브가 없거나 모델 자산이 빠지면 null — 호출부가 안내 문구를 띄운다. */
        fun createOrNull(context: Context): AndroidOcr? {
            if (!OpenCVLoader.initLocal()) return null
            val assets = AndroidOcrAssets(context)
            val pipeline = try {
                OcrPipeline(assets)
            } catch (_: Throwable) {
                return null
            }
            return AndroidOcr(pipeline, KieParser.createOrNull(assets))
        }
    }
}
