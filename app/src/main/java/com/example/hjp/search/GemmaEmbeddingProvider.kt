package com.example.hjp.search

import android.content.Context
import com.google.ai.edge.localagents.rag.models.EmbedData
import com.google.ai.edge.localagents.rag.models.EmbeddingRequest
import com.google.ai.edge.localagents.rag.models.GemmaEmbeddingModel
import java.io.File

/**
 * EmbeddingGemma 300M을 AI Edge RAG SDK(GemmaEmbeddingModel)로 실행한다.
 *
 * 필요한 파일 2개 (files/models 또는 외부 앱 폴더 models/):
 * - embeddinggemma-300m.tflite  (litert-community 양자화 모델)
 * - sentencepiece.model         (같은 저장소의 토크나이저)
 *
 * MediaPipe TextEmbedder는 이 모델에 필요한 메타데이터가 없어 로드하지 못한다
 * ("could not build model from the provided pre-loaded flatbuffer").
 */
class GemmaEmbeddingProvider(context: Context) : TextEmbeddingProvider {
    private val appContext = context.applicationContext
    private var model: GemmaEmbeddingModel? = null
    private var status = "not initialized"

    private val modelFile = findFile(MODEL_FILE_NAME)
    private val tokenizerFile = findFile(TOKENIZER_FILE_NAME)

    override val name: String
        get() = "EmbeddingGemma RAG (${modelFile?.name ?: "no model"})"

    override val isModelBacked: Boolean
        get() = model != null

    override val diagnosticStatus: String
        get() = status

    init {
        when {
            modelFile == null -> status = "missing: $MODEL_FILE_NAME — ${expectedLocations(MODEL_FILE_NAME).joinToString(" | ")}"
            tokenizerFile == null -> status = "missing: $TOKENIZER_FILE_NAME — ${expectedLocations(TOKENIZER_FILE_NAME).joinToString(" | ")}"
            else -> try {
                // GPU 초기화는 기기별 편차가 커서 CPU로 고정. seq256 문장 하나는 CPU로도 수십 ms 수준.
                model = GemmaEmbeddingModel(modelFile.absolutePath, tokenizerFile.absolutePath, false)
                status = "loaded from ${modelFile.absolutePath}"
            } catch (e: Throwable) {
                model = null
                status = "load failed: ${describeThrowable(e)}"
            }
        }
    }

    override fun embedQuery(text: String): FloatArray =
        run(EmbedData.create(text, EmbedData.TaskType.RETRIEVAL_QUERY, true))

    override fun embedDocument(text: String): FloatArray =
        run(EmbedData.create(text, EmbedData.TaskType.RETRIEVAL_DOCUMENT, false))

    private fun run(data: EmbedData<String>): FloatArray {
        val embedder = model ?: throw IllegalStateException("EmbeddingGemma is not loaded: $status")
        return try {
            val values = embedder.getEmbeddings(EmbeddingRequest.create(listOf(data))).get()
            normalize(FloatArray(values.size) { values[it] })
        } catch (e: Throwable) {
            status = "embed failed: ${describeThrowable(e)}"
            throw IllegalStateException(status, e)
        }
    }

    private fun findFile(fileName: String): File? =
        expectedFiles(fileName).firstOrNull { it.exists() && it.length() > 0L }

    private fun expectedFiles(fileName: String): List<File> {
        val external = appContext.getExternalFilesDir("models")
        return listOfNotNull(
            File(File(appContext.filesDir, "models"), fileName),
            external?.let { File(it, fileName) },
        )
    }

    private fun expectedLocations(fileName: String): List<String> =
        expectedFiles(fileName).map { it.absolutePath }

    private fun describeThrowable(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(4).toList()
        return chain.joinToString(" -> ") { item ->
            item.javaClass.simpleName + item.message?.let { ": $it" }.orEmpty()
        }
    }

    companion object {
        const val MODEL_FILE_NAME = "embeddinggemma-300m.tflite"
        const val TOKENIZER_FILE_NAME = "sentencepiece.model"
    }
}
