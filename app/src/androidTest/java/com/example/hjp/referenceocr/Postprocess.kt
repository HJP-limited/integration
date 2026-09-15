package com.example.hjp.referenceocr

/**
 * On-device OCR text postprocess — port of HJP/OCR/postprocess.py stage 1.
 * Normalizes Korean corporate prefix variants ('[주)', '(쥐)', ...) to '(주)'.
 */
object Postprocess {
    private val PREFIX_BRACKET = Regex("""^\s*[\[(]\s*[주쥐]\s*[)\]]\s*""")

    fun apply(text: String): String {
        if (text.isEmpty()) return text
        return PREFIX_BRACKET.replaceFirst(text, "(주)")
    }
}
