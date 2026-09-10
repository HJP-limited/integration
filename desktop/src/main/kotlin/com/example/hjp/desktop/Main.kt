package com.example.hjp.desktop

import com.example.hjp.ocr.CardParser
import com.example.hjp.ocr.KieParser
import com.example.hjp.ocr.OcrAssets
import com.example.hjp.ocr.OcrPipeline
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.system.exitProcess

/** 모델을 디렉터리에서 읽는다. 안드로이드의 assets/ocr/ 과 같은 파일 구성. */
class FileOcrAssets(private val dir: File) : OcrAssets {
    override fun bytes(name: String): ByteArray? =
        File(dir, name).takeIf { it.isFile }?.readBytes()
}

private const val DEFAULT_MODEL_DIR = "app/src/main/assets/ocr"

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println(
            """
            사용법: desktop <명함이미지> [모델디렉터리]

              모델디렉터리 기본값: $DEFAULT_MODEL_DIR
              (안드로이드 APK 가 쓰는 것과 같은 파일들 — det.onnx, rec.onnx,
               korean_dict.txt, kie_*.onnx, kie_labels.json)
            """.trimIndent()
        )
        exitProcess(1)
    }

    val image = File(args[0])
    if (!image.isFile) {
        System.err.println("이미지가 없다: ${image.absolutePath}")
        exitProcess(1)
    }
    val modelDir = File(args.getOrElse(1) { DEFAULT_MODEL_DIR })
    if (!modelDir.isDirectory) {
        System.err.println("모델 디렉터리가 없다: ${modelDir.absolutePath}")
        exitProcess(1)
    }

    nu.pattern.OpenCV.loadShared()

    val assets = FileOcrAssets(modelDir)
    val pipeline = OcrPipeline(assets)
    val kie = KieParser.createOrNull(assets)
    println("필드 분류기: ${if (kie != null) "MiniLM KIE" else "CardParser 휴리스틱(폴백)"}")

    val bgr = Imgcodecs.imread(image.absolutePath)
    if (bgr.empty()) {
        System.err.println("이미지를 읽지 못했다: ${image.absolutePath}")
        exitProcess(1)
    }
    println("이미지: ${image.name} (${bgr.cols()}x${bgr.rows()})")

    val startedAt = System.currentTimeMillis()
    val regions = pipeline.run(bgr)
    val ocrMs = System.currentTimeMillis() - startedAt

    val fields = kie?.parse(regions) ?: CardParser.parse(regions)
    val totalMs = System.currentTimeMillis() - startedAt
    bgr.release()

    println()
    println("인식 영역 ${regions.size}개 · OCR ${ocrMs}ms · 전체 ${totalMs}ms")
    println("-".repeat(60))
    regions.forEach { println("  [%.2f] %s".format(it.score, it.text)) }
    println()
    println("추출 필드 ${fields.size}개")
    println("-".repeat(60))
    fields.forEach { println("  ${it.icon} ${it.label}: ${it.value}") }
}
