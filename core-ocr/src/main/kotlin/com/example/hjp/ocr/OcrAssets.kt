package com.example.hjp.ocr

/**
 * OCR 모델 파일 공급자. 안드로이드는 APK assets, 데스크톱은 파일 경로에서 읽는다.
 *
 * 이것만 플랫폼별로 다르고 검출·인식 로직은 [OcrPipeline] 한 벌을 공유한다.
 */
interface OcrAssets {
    /** 없으면 null — 호출부가 폴백을 고를 수 있어야 한다(예: KIE 모델 미배치). */
    fun bytes(name: String): ByteArray?

    fun text(name: String): String? = bytes(name)?.toString(Charsets.UTF_8)

    fun require(name: String): ByteArray =
        bytes(name) ?: throw IllegalStateException("OCR asset not found: $name")
}
