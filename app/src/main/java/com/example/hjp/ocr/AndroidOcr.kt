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
 * det/rec, 글줄 방향, KIE 모델을 모두 로드해야 인식을 시작한다. 필수 모델이 없으면
 * 촬영 인식을 차단하며 휴리스틱으로 성공 처리하지 않는다.
 */
class AndroidOcr private constructor(
    private val pipeline: OcrPipeline,
    private val kie: KieParser,
) : AutoCloseable {
    val fieldClassifier: String = "MiniLM KIE"
    val textLineOrientationModelLoaded: Boolean get() = pipeline.textLineOrientationModelLoaded
    private var closed = false

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try { kie.close() } finally { pipeline.close() }
    }

    // Screen disposal must not release native sessions while IO inference is using them.
    @Synchronized
    fun read(bitmap: Bitmap): Result {
        check(!closed) { "OCR engine is closed" }
        val bgr = Mat()
        return try {
            Utils.bitmapToMat(bitmap, bgr) // RGBA
            Imgproc.cvtColor(bgr, bgr, Imgproc.COLOR_RGBA2BGR)
            val regions = pipeline.run(bgr)
            // 이미지 크기를 넘긴다 — 줄바꿈된 주소를 합칠 때 "같은 열인가" 를 이미지
            // 너비에 대한 비율로 보기 때문이다. 검출 상자에서 되짚으면 오른쪽 끝 글상자가
            // 없는 명함에서 너비가 작게 잡힌다.
            Result(
                regions,
                kie.parse(regions, bitmap.width, bitmap.height),
            )
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
            var transferred = false
            try {
                if (!pipeline.textLineOrientationModelLoaded) return null
                val kie = KieParser.createOrNull(assets) ?: return null
                return AndroidOcr(pipeline, kie).also { transferred = true }
            } finally {
                if (!transferred) pipeline.close()
            }
        }
    }
}
