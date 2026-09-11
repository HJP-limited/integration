package com.example.hjp.desktop

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.hjp.search.TextEmbeddingProvider
import com.example.hjp.search.normalize
import java.io.File
import java.nio.LongBuffer

/**
 * 노트북에서 EmbeddingGemma-300M 을 ONNX 로 돌린다.
 *
 * 안드로이드는 같은 모델을 `.tflite` + AI Edge RAG SDK 로 돌리는데, 그 SDK 의 네이티브가
 * arm64 전용이라 x86_64 에서는 못 쓴다. 그래서 데스크톱만 런타임이 다르다 — **모델과
 * 전처리는 같다.**
 *
 * 실측(2026-09-11): 앱 assets 에 번들된 사전 계산 벡터와 코사인 **1.0000** 으로 일치.
 * 즉 노트북에서 잰 벡터 검색 결과는 앱이 쓰는 의미공간 그대로다.
 *
 * 모델: `onnx-community/embeddinggemma-300m-ONNX` 의 `onnx/model.onnx`(fp32, 외부 데이터
 * 파일 `model.onnx_data` 가 같은 폴더에 있어야 한다) + `tokenizer.json`.
 * 그래프가 pooling·Dense·normalize 까지 포함해 `sentence_embedding`[batch,768] 을 직접 낸다.
 */
class OnnxEmbeddingProvider(modelDir: File) : TextEmbeddingProvider {

    private var session: OrtSession? = null
    private var tokenizer: HuggingFaceTokenizer? = null
    private var status: String = "not initialized"

    override val name: String = "EmbeddingGemma ONNX (${modelDir.name})"
    override val isModelBacked: Boolean get() = session != null
    override val diagnosticStatus: String get() = status

    init {
        val model = File(modelDir, "onnx/model.onnx")
        val tokenizerJson = File(modelDir, "tokenizer.json")
        when {
            !model.isFile -> status = "missing: ${model.absolutePath}"
            !tokenizerJson.isFile -> status = "missing: ${tokenizerJson.absolutePath}"
            else -> try {
                tokenizer = HuggingFaceTokenizer.newInstance(tokenizerJson.toPath())
                // 외부 데이터(model.onnx_data)를 같은 폴더에서 찾게 경로로 연다.
                session = OrtEnvironment.getEnvironment()
                    .createSession(model.absolutePath, OrtSession.SessionOptions())
                status = "loaded from ${model.absolutePath}"
            } catch (e: Throwable) {
                session = null
                status = "load failed: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    override fun embedQuery(text: String): FloatArray = embed(QUERY_PROMPT + text)

    override fun embedDocument(text: String): FloatArray = embed(DOCUMENT_PROMPT + text)

    private fun embed(text: String): FloatArray {
        val ort = session ?: throw IllegalStateException("EmbeddingGemma is not loaded: $status")
        val tok = tokenizer ?: throw IllegalStateException("tokenizer is not loaded: $status")
        val encoding = tok.encode(text)
        val ids = encoding.ids
        val mask = encoding.attentionMask
        val shape = longArrayOf(1, ids.size.toLong())
        val env = OrtEnvironment.getEnvironment()

        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { idsTensor ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { maskTensor ->
                ort.run(mapOf("input_ids" to idsTensor, "attention_mask" to maskTensor)).use { out ->
                    val tensor = out.get("sentence_embedding").get() as OnnxTensor
                    @Suppress("UNCHECKED_CAST")
                    val rows = tensor.value as Array<FloatArray>
                    // 그래프가 이미 정규화하지만, 앱과 같은 코드로 한 번 더 통과시켜
                    // 코사인 계산 전제(단위 벡터)를 두 경로에서 동일하게 만든다.
                    return normalize(rows[0].copyOf())
                }
            }
        }
    }

    override fun close() {
        session?.close()
        tokenizer?.close()
    }

    companion object {
        /**
         * EmbeddingGemma 는 태스크 프리픽스를 붙여야 제 성능이 난다. 안드로이드에서는 AI Edge
         * RAG SDK 가 `EmbedData.TaskType` 을 보고 자동으로 붙이므로 앱 코드에는 안 보이는데,
         * 여기서는 직접 붙여야 **같은 벡터**가 나온다.
         *
         * 값은 모델 저장소의 `config_sentence_transformers.json` 의 prompts 정의 그대로다.
         * 바꾸면 앱에 번들된 사전 계산 벡터와 즉시 어긋난다.
         */
        const val QUERY_PROMPT = "task: search result | query: "
        const val DOCUMENT_PROMPT = "title: none | text: "
    }
}
