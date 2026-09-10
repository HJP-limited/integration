package com.example.hjp.ocr

/**
 * 인식 텍스트 후처리 — `HJP/OCR/postprocess.py` stage 1 이식본.
 * 법인 접두어 변형('[주)', '(쥐)' …)을 '(주)' 로 정규화한다.
 */
object Postprocess {
    private val PREFIX_BRACKET = Regex("""^\s*[\[(]\s*[주쥐]\s*[)\]]\s*""")

    fun apply(text: String): String {
        if (text.isEmpty()) return text
        return PREFIX_BRACKET.replaceFirst(text, "(주)")
    }
}
