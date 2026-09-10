package com.example.hjp.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.extensions.OrtxPackage
import org.json.JSONArray
import java.nio.LongBuffer

/**
 * MiniLM KIE 필드 분류기 — [CardParser] 휴리스틱을 대체한다.
 *
 * `OCR/kie/export_onnx.py` 의 onnx_pipeline_predict 이식본이다(torch 참조와 853/853 일치).
 * OCR 출력 기준 라인 정확도 98.0% 로 휴리스틱 85.3% 보다 뚜렷이 높다. 세션 두 개:
 *   kie_tokenizer.onnx   — XLM-R SentencePiece (onnxruntime-extensions custom op),
 *                          문자열 입력 -> `<s>...</s>` 가 이미 붙은 flat int32 ids
 *   kie_minilm_int8.onnx — int8 분류기, (ids, mask) -> logits
 *
 * 분류는 MiniLM 이 하고, 값 정제(번호 추출·라벨 접두어 제거)는 [CardParser] 의 검증된
 * 정규식을 그대로 쓴다.
 */
class KieParser private constructor(
    private val env: OrtEnvironment,
    private val tokenizer: OrtSession,
    private val classifier: OrtSession,
    private val labels: List<String>,
) {

    companion object {
        private const val MAX_LEN = 48
        private const val PAD_ID = 1L // XLM-R <pad>

        const val CLASSIFIER_ASSET = "kie_minilm_int8.onnx"
        const val TOKENIZER_ASSET = "kie_tokenizer.onnx"
        const val LABELS_ASSET = "kie_labels.json"

        /**
         * 자산이 다 있으면 만들고, 하나라도 없거나 로드에 실패하면 null.
         *
         * KIE 모델(118MB)은 저장소에 커밋하지 않으므로 없을 수 있다. 그때는 호출부가
         * [CardParser] 폴백으로 내려가야 하고, 여기서 예외를 던지면 촬영 자체가 죽는다.
         */
        fun createOrNull(assets: OcrAssets): KieParser? {
            val classifierBytes = assets.bytes(CLASSIFIER_ASSET) ?: return null
            val tokenizerBytes = assets.bytes(TOKENIZER_ASSET) ?: return null
            val labelsJson = assets.text(LABELS_ASSET) ?: return null
            return try {
                val env = OrtEnvironment.getEnvironment()
                val tokOpts = OrtSession.SessionOptions()
                tokOpts.registerCustomOpLibrary(OrtxPackage.getLibraryPath())
                val tokenizer = env.createSession(tokenizerBytes, tokOpts)
                val classifier = env.createSession(classifierBytes, OrtSession.SessionOptions())
                val arr = JSONArray(labelsJson)
                KieParser(env, tokenizer, classifier, List(arr.length()) { arr.getString(it) })
            } catch (_: Throwable) {
                null
            }
        }

        private val EMAIL = Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""")
        private val PHONE = Regex("""(?:0\d{1,2}|1\d{3})[-. ]?\d{3,4}[-. ]?\d{4}""")
        private val URL_PREFIX = Regex("""(?i)^\s*(W|Web|Website|Homepage)[.: ]*""")
        private val ADDR_PREFIX = Regex("""(?i)^\s*(Address|주소)[.: ]*""")
        private val LABEL_ONLY = Regex(
            """(?i)^(W|Web|Website|T|Tel|M|Mobile|F|Fax|E|E-?mail|H|HP|Address|주소|전화|휴대폰|팩스|이메일)[.:]?$""")

        // field -> (icon, ui label, sort priority)
        private val FIELD_UI = mapOf(
            "name_ko" to Triple("👤", "이름", 0),
            "name_en" to Triple("👤", "영문명", 1),
            "name_hanja" to Triple("👤", "한자명", 2),
            "title" to Triple("💼", "직함", 3),
            "department" to Triple("👥", "부서", 4),
            "company_ko" to Triple("🏢", "회사", 5),
            "company_en" to Triple("🏢", "회사(영문)", 6),
            "mobile" to Triple("📱", "휴대폰", 7),
            "tel_office" to Triple("📞", "전화", 8),
            "fax" to Triple("📠", "팩스", 9),
            "email" to Triple("✉️", "이메일", 10),
            "website" to Triple("🌐", "웹", 11),
            "address_ko" to Triple("📍", "주소", 12),
            "slogan" to Triple("💬", "슬로건", 13),
            "logo_text" to Triple("🔖", "로고", 14),
        )
    }

    /** Tokenize one line: flat ids from the SentencePiece graph, truncated. */
    private fun tokenize(text: String): LongArray {
        OnnxTensor.createTensor(env, arrayOf(text)).use { input ->
            tokenizer.run(mapOf("inputs" to input)).use { result ->
                val raw = result[0].value as IntArray
                val n = minOf(raw.size, MAX_LEN)
                return LongArray(n) { raw[it].toLong() }
            }
        }
    }

    /** Classify each region text into a KIE field name. */
    fun classify(texts: List<String>): List<String> {
        if (texts.isEmpty()) return emptyList()
        val ids = texts.map { tokenize(it) }
        val maxLen = ids.maxOf { it.size }
        val n = texts.size
        val idsBuf = LongBuffer.allocate(n * maxLen)
        val maskBuf = LongBuffer.allocate(n * maxLen)
        for (row in ids) {
            for (j in 0 until maxLen) {
                idsBuf.put(if (j < row.size) row[j] else PAD_ID)
                maskBuf.put(if (j < row.size) 1L else 0L)
            }
        }
        idsBuf.rewind(); maskBuf.rewind()
        val shape = longArrayOf(n.toLong(), maxLen.toLong())
        OnnxTensor.createTensor(env, idsBuf, shape).use { idsT ->
            OnnxTensor.createTensor(env, maskBuf, shape).use { maskT ->
                classifier.run(
                    mapOf("input_ids" to idsT, "attention_mask" to maskT)
                ).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val logits = result[0].value as Array<FloatArray>
                    return logits.map { row ->
                        labels[row.indices.maxBy { row[it] }]
                    }
                }
            }
        }
    }

    /** Regions -> UI fields, same Field type/ordering contract as [CardParser]. */
    fun parse(regions: List<OcrPipeline.Region>): List<CardParser.Field> {
        val texts = regions.map { it.text.trim() }
        val fields = classify(texts)
        val out = ArrayList<CardParser.Field>()
        for ((t, field) in texts.zip(fields)) {
            if (t.length < 2 || LABEL_ONLY.matches(t)) continue
            val (icon, label, _) = FIELD_UI[field] ?: continue
            val value = when (field) {
                "email" -> EMAIL.find(t)?.value ?: t
                "mobile", "tel_office", "fax" -> PHONE.find(t)?.value ?: t
                "address_ko" -> t.replace(ADDR_PREFIX, "")
                    .trim { c -> c.isWhitespace() || c in "↑↓·°" }
                "website" -> t.replace(URL_PREFIX, "").trim().ifEmpty { t }
                else -> t
            }
            out.add(CardParser.Field(icon, label, value))
        }
        return out.sortedBy { f ->
            FIELD_UI.values.firstOrNull { it.second == f.label }?.third ?: 99
        }
    }
}
