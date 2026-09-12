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
        /** 디자인용 선행 기호. "↑ 서울시…", ") 010-…" 처럼 검출에 딸려 들어온다. */
        private val JUNK_PREFIX = Regex("""^[^\w가-힣(]+""")

        /** "E." "M." "A:" 같은 한 글자 라벨. 필드 판별에 쓰고 나면 값에는 남기지 않는다. */
        private val ONE_LETTER_LABEL = Regex("""^[A-Za-z]\s*[.:]\s*""")

        /** 인식기가 앞에 0 을 하나 더 붙이는 일이 있다: "0010-…" -> "010-…". */
        private val DOUBLED_LEADING_ZERO = Regex("""^00(?=\d)""")

        /**
         * 값에서 군더더기를 걷어낸다(`OCR/app.py` 의 `_clean` 이식본).
         *
         * 라벨은 **필드 판별 신호로 쓰고 나서 버린다** — "M." 을 보고 휴대폰으로 배정했다면
         * 그 "M." 이 전화번호 값 안에 남을 이유가 없다. 남으면 그대로 저장돼서 나중에 그
         * 번호로 검색해도 안 맞는다.
         */
        internal fun clean(raw: String): String {
            var s = raw.trim()
            s = JUNK_PREFIX.replaceFirst(s, "").trim()
            s = ONE_LETTER_LABEL.replaceFirst(s, "").trim()
            s = DOUBLED_LEADING_ZERO.replaceFirst(s, "0")
            return s.trim { c -> c.isWhitespace() || c in "↑↓·°" }
        }

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

    /**
     * Regions -> UI fields, same Field type/ordering contract as [CardParser].
     *
     * 분류와 UI 매핑 **사이에** [FieldGrouping] 이 들어간다. 검출기는 글줄 단위로 자르는데
     * 명함의 칸과 글줄은 일대일이 아니라서, 그 사이를 메우지 않으면 줄바꿈된 주소가 반 토막
     * 나고 "TEL … FAX …" 한 줄이 통째로 전화번호 칸에 들어간다.
     *
     * @param imageWidth 원본 이미지 가로 픽셀. 같은 열인지 판정할 때 쓴다 — 고정 픽셀 수로
     *   정하면 해상도가 바뀔 때마다 틀리므로 이미지 너비에 대한 비율로 본다.
     */
    fun parse(
        regions: List<OcrPipeline.Region>,
        imageWidth: Int = 0,
        imageHeight: Int = 0,
    ): List<CardParser.Field> {
        val texts = regions.map { it.text.trim() }
        val labelled = texts.zip(classify(texts)).mapIndexed { index, (text, field) ->
            FieldGrouping.Labeled(regions[index].poly, text, field, regions[index].score)
        }
        // 이미지 크기를 모르면(옛 호출부) 검출된 글상자들이 차지한 너비로 대신한다.
        // 합치기 자체를 건너뛰면 줄바꿈된 주소가 그대로 반 토막 난다.
        val width = if (imageWidth > 0) imageWidth else {
            regions.flatMap { it.poly }.maxOfOrNull { it.x }?.toInt() ?: 0
        }
        val height = if (imageHeight > 0) imageHeight else {
            regions.flatMap { it.poly }.maxOfOrNull { it.y }?.toInt() ?: 0
        }
        val grouped = FieldGrouping.postprocess(labelled, width, height)
        val out = ArrayList<CardParser.Field>()
        for (entry in grouped) {
            val t = entry.text
            val field = entry.field
            if (t.length < 2 || LABEL_ONLY.matches(t)) continue
            val (icon, label, _) = FIELD_UI[field] ?: continue
            val value = when (field) {
                "email" -> EMAIL.find(t)?.value ?: t
                "mobile", "tel_office", "fax" -> PHONE.find(t)?.value ?: clean(t)
                "address_ko" -> clean(t.replace(ADDR_PREFIX, ""))
                "website" -> t.replace(URL_PREFIX, "").trim().ifEmpty { t }
                else -> clean(t)
            }
            if (value.isBlank()) continue
            out.add(CardParser.Field(icon, label, value))
        }
        return out.sortedBy { f ->
            FIELD_UI.values.firstOrNull { it.second == f.label }?.third ?: 99
        }
    }
}
