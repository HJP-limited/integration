package com.example.hjp.search

import android.content.Context
import android.os.Build
import com.google.ai.edge.localagents.rag.models.EmbedData
import com.google.ai.edge.localagents.rag.models.EmbeddingRequest
import com.google.ai.edge.localagents.rag.models.GemmaEmbeddingModel
import com.hjp.searchlookup.EmbeddingEngine
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Ryeong llm-integration-work의 실기기 검증 경로를 현재 검색 코어에 연결한다.
 *
 * 모델은 APK에 추가하지 않는다. 앱 내부/외부 models 디렉터리의 두 파일을 모두
 * 확인한 뒤에만 초기화하며, 실패 상태는 [diagnosticStatus]로 상위 fallback에 전달한다.
 */
class AndroidEmbeddingGemmaEngine(context: Context) : EmbeddingEngine, AutoCloseable {
    private val appContext = context.applicationContext
    private val bootstrapDiagnostics = bootstrapBundledAssets()
    private val modelFile = findFile(MODEL_FILE_NAME)
    private val tokenizerFile = findFile(TOKENIZER_FILE_NAME)
    private val modelHash = modelFile?.let(::sha256).orEmpty()
    private val tokenizerHash = tokenizerFile?.let(::sha256).orEmpty()
    private var model: GemmaEmbeddingModel? = null
    @Volatile private var status: String

    init {
        status = when {
            isEmulator() ->
                "UNSUPPORTED_EMULATOR_NATIVE_SME2:use KEYWORD_ONLY or a physical ARM64 device"
            modelFile == null ->
                "ASSET_MISSING:$MODEL_FILE_NAME expected=${expectedLocations(MODEL_FILE_NAME)};$bootstrapDiagnostics"
            tokenizerFile == null ->
                "ASSET_MISSING:$TOKENIZER_FILE_NAME expected=${expectedLocations(TOKENIZER_FILE_NAME)};$bootstrapDiagnostics"
            modelHash != EXPECTED_MODEL_SHA256 ->
                "MODEL_HASH_MISMATCH:expected=$EXPECTED_MODEL_SHA256 actual=$modelHash"
            tokenizerHash != EXPECTED_TOKENIZER_SHA256 ->
                "TOKENIZER_HASH_MISMATCH:expected=$EXPECTED_TOKENIZER_SHA256 actual=$tokenizerHash"
            else -> try {
                model = GemmaEmbeddingModel(
                    modelFile.absolutePath,
                    tokenizerFile.absolutePath,
                    false,
                )
                "ready:model=${modelFile.absolutePath};model_sha256=$modelHash;" +
                    "tokenizer=${tokenizerFile.absolutePath};tokenizer_sha256=$tokenizerHash"
            } catch (error: Throwable) {
                model = null
                "INITIALIZATION_FAILED:${describe(error)}"
            }
        }
    }

    override fun embed(input: String): FloatArray = embedQuery(input)

    override fun embedQuery(input: String): FloatArray =
        run(EmbedData.create(input, EmbedData.TaskType.RETRIEVAL_QUERY, true))

    override fun embedDocument(input: String): FloatArray =
        run(EmbedData.create(input, EmbedData.TaskType.RETRIEVAL_DOCUMENT, false))

    override fun name(): String = if (modelHash.isNotBlank()) {
        "$MODEL_NAME#m=${modelHash.take(16)};t=${tokenizerHash.take(16)}"
    } else {
        MODEL_NAME
    }

    override fun isModelBacked(): Boolean = model != null

    override fun diagnosticStatus(): String = status

    override fun close() {
        model = null
        status = "closed"
    }

    private fun run(data: EmbedData<String>): FloatArray {
        val activeModel = model ?: throw IllegalStateException(
            "EmbeddingGemma is not ready: $status",
        )
        return try {
            val values = activeModel.getEmbeddings(
                EmbeddingRequest.create(listOf(data)),
            ).get()
            FloatArray(values.size) { values[it] }.also(::validateVector)
        } catch (error: Throwable) {
            model = null
            status = "INFERENCE_FAILED:${describe(error)}"
            throw IllegalStateException(status, error)
        }
    }

    private fun validateVector(vector: FloatArray) {
        require(vector.size == OUTPUT_DIMENSION) {
            "DIMENSION_MISMATCH expected=$OUTPUT_DIMENSION actual=${vector.size}"
        }
        require(vector.all(Float::isFinite)) { "NON_FINITE_VECTOR" }
        require(vector.any { it != 0f }) { "ZERO_VECTOR" }
    }

    private fun findFile(fileName: String): File? =
        expectedFiles(fileName).firstOrNull { it.isFile && it.canRead() && it.length() > 0L }

    /** Copies bundled assets once; the original APK asset is never modified. */
    private fun bootstrapBundledAssets(): String {
        val messages = mutableListOf<String>()
        listOf(MODEL_FILE_NAME, TOKENIZER_FILE_NAME).forEach { fileName ->
            val targetDir = File(appContext.filesDir, "models")
            val target = File(targetDir, fileName)
            val expectedHash = when (fileName) {
                MODEL_FILE_NAME -> EXPECTED_MODEL_SHA256
                TOKENIZER_FILE_NAME -> EXPECTED_TOKENIZER_SHA256
                else -> ""
            }
            if (target.isFile && target.length() > 0L) {
                if (expectedHash.isEmpty() || sha256(target) == expectedHash) {
                    messages += "$fileName=existing"
                    return@forEach
                }
                messages += "$fileName=stale-replaced"
            }
            try {
                targetDir.mkdirs()
                val temporary = File(targetDir, ".$fileName.copying")
                appContext.assets.open("models/$fileName").use { input ->
                    temporary.outputStream().use(input::copyTo)
                }
                require(expectedHash.isEmpty() || sha256(temporary) == expectedHash) {
                    "bundled $fileName hash mismatch"
                }
                if (target.exists()) require(target.delete()) { "cannot replace stale $fileName" }
                require(temporary.renameTo(target)) { "cannot move bundled asset into models directory" }
                messages += "$fileName=copied"
            } catch (error: Throwable) {
                File(targetDir, ".$fileName.copying").delete()
                messages += "$fileName=unavailable:${error.javaClass.simpleName}"
            }
        }
        return messages.joinToString(",")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun expectedFiles(fileName: String): List<File> = listOfNotNull(
        File(File(appContext.filesDir, "models"), fileName),
        appContext.getExternalFilesDir("models")?.let { File(it, fileName) },
    )

    private fun expectedLocations(fileName: String): String =
        expectedFiles(fileName).joinToString("|") { it.absolutePath }

    private fun describe(error: Throwable): String =
        generateSequence(error) { it.cause }
            .take(4)
            .joinToString(" -> ") {
                it.javaClass.simpleName + it.message?.let { message -> ":$message" }.orEmpty()
            }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
            Build.MANUFACTURER.contains("Genymotion", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk_gphone", ignoreCase = true)

    companion object {
        const val MODEL_NAME = "google/embeddinggemma-300m-ai-edge-rag"
        const val MODEL_FILE_NAME = "embeddinggemma-300m.tflite"
        const val TOKENIZER_FILE_NAME = "sentencepiece.model"
        const val OUTPUT_DIMENSION = 768
        const val EXPECTED_MODEL_SHA256 =
            "37115ef7bff76cd37dd86abe503ff511b1032bf85fc624a85c49c84899e92bc5"
        const val EXPECTED_TOKENIZER_SHA256 =
            "d6daa52d93d7aad10e8388bd526c4e501d914b47177398d1d9621f1fe48438c7"
    }
}
