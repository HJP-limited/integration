package com.example.hjp.models

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class DownloadableModel(val fileName: String, val url: String, val size: Long, val sha256: String)

/** Pinned generic CPU artifacts; never substitute a chipset-specific or newer model silently. */
object ModelDownloads {
    private const val GEMMA = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/"
    private const val EMBEDDING = "https://huggingface.co/litert-community/embeddinggemma-300m/resolve/29888fcee3216acadc7e844906e5fe0d79a61875/"
    val required = listOf(
        DownloadableModel("embeddinggemma-300m.tflite", EMBEDDING + "embeddinggemma-300M_seq256_mixed-precision.tflite", 179131736,
            "37115ef7bff76cd37dd86abe503ff511b1032bf85fc624a85c49c84899e92bc5"),
        DownloadableModel("sentencepiece.model", EMBEDDING + "sentencepiece.model", 4683319,
            "d6daa52d93d7aad10e8388bd526c4e501d914b47177398d1d9621f1fe48438c7"),
        DownloadableModel("hjp-agent.litertlm", GEMMA + "gemma-4-E2B-it.litertlm", 2588147712,
            "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"),
    )
}

/** Screen cancellation retains a resumable .part; only verified bytes replace the final file. */
class ModelInstaller(
    private val directory: File,
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    companion object { private val transferMutex = Mutex() }

    suspend fun download(model: DownloadableModel, token: String, progress: suspend (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            transferMutex.withLock {
                require(model.fileName == File(model.fileName).name && !model.fileName.contains(".."))
                check(directory.isDirectory || directory.mkdirs()) { "모델 저장 폴더를 만들 수 없습니다." }
                val target = File(directory, model.fileName)
                if (verified(target, model)) return@withLock target
                val partial = File(directory, model.fileName + ".part")
                if (partial.length() > model.size) check(partial.delete())
                if (partial.length() == model.size && !verified(partial, model)) check(partial.delete())
                val offset = partial.length()
                if (offset < model.size) {
                    check(directory.usableSpace > model.size - offset + 16L * 1024 * 1024) {
                        "모델 다운로드를 위한 저장 공간이 부족합니다."
                    }
                    val connection = open(model.url, token.trim(), offset)
                    try {
                        val status = connection.responseCode
                        check(status != 401 && status != 403) {
                            "Hugging Face에서 모델 이용 조건에 동의하고 읽기 권한 토큰을 입력해 주세요. (HTTP $status)"
                        }
                        check(status == 200 || status == 206) { "모델 다운로드 실패 (HTTP $status)" }
                        val append = status == 206
                        if (append) {
                            check(connection.getHeaderField("Content-Range") == "bytes $offset-${model.size - 1}/${model.size}") {
                                "이어받기 응답 범위가 올바르지 않습니다."
                            }
                        } else {
                            check(directory.usableSpace > model.size + 16L * 1024 * 1024) {
                                "서버가 이어받기를 지원하지 않아 전체 다운로드 공간이 필요합니다."
                            }
                        }
                        var received = if (append) offset else 0L
                        val remaining = model.size - received
                        val contentLength = connection.getHeaderFieldLong("Content-Length", -1)
                        check(contentLength < 0 || contentLength == remaining) { "모델 파일 크기가 예상과 다릅니다." }
                        connection.inputStream.use { input ->
                            FileOutputStream(partial, append).use { output ->
                                val buffer = ByteArray(256 * 1024)
                                var lastReported = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    check(received + count <= model.size) { "모델 파일 크기 초과" }
                                    output.write(buffer, 0, count)
                                    received += count
                                    if (received - lastReported >= 1024 * 1024 || received == model.size) {
                                        progress(received, model.size)
                                        lastReported = received
                                    }
                                }
                                output.fd.sync()
                            }
                        }
                        check(received == model.size) { "다운로드가 중단됐습니다. 다시 누르면 이어받습니다." }
                    } finally {
                        connection.disconnect()
                    }
                }
                progress(model.size, model.size)
                if (!verified(partial, model)) {
                    check(partial.delete()) { "손상된 임시 모델 파일을 지우지 못했습니다." }
                    error("모델 SHA-256 검증 실패. 기존 파일은 변경하지 않았습니다.")
                }
                currentCoroutineContext().ensureActive()
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                target
            }
        }

    suspend fun installFrom(model: DownloadableModel, openInput: () -> java.io.InputStream): File =
        withContext(Dispatchers.IO) {
            transferMutex.withLock {
                require(model.fileName == File(model.fileName).name && !model.fileName.contains(".."))
                check(directory.isDirectory || directory.mkdirs()) { "모델 저장 폴더를 만들 수 없습니다." }
                check(directory.usableSpace > model.size + 16L * 1024 * 1024) { "모델 가져오기 저장 공간 부족" }
                val staging = File.createTempFile("model-import-", ".part", directory)
                try {
                    var received = 0L
                    openInput().use { input ->
                        FileOutputStream(staging).use { output ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                received += count
                                check(received <= model.size) { "모델 파일 크기가 예상과 다릅니다." }
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                    check(verified(staging, model)) { "모델 크기 또는 SHA-256 검증 실패. 기존 파일은 변경하지 않았습니다." }
                    currentCoroutineContext().ensureActive()
                    val target = File(directory, model.fileName)
                    Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    target
                } finally { staging.delete() }
            }
        }

    private suspend fun verified(file: File, model: DownloadableModel): Boolean {
        if (!file.isFile || file.length() != model.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == model.sha256
    }

    private suspend fun open(address: String, token: String, offset: Long): HttpURLConnection {
        var url = URL(address)
        repeat(6) {
            currentCoroutineContext().ensureActive()
            require(url.protocol == "https" && url.userInfo == null) { "HTTPS 다운로드만 허용합니다." }
            val connection = connect(url)
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                // Never forward the user's token to signed CDN redirects.
                if (url.host == "huggingface.co" && token.isNotBlank()) {
                    connection.setRequestProperty("Authorization", "Bearer $token")
                }
                if (connection.responseCode !in listOf(301, 302, 303, 307, 308)) return connection
                val location = connection.getHeaderField("Location") ?: error("다운로드 리디렉션 주소가 없습니다.")
                url = URL(url, location)
            } catch (failure: Throwable) {
                connection.disconnect()
                throw failure
            }
            connection.disconnect()
        }
        error("다운로드 리디렉션 횟수 초과")
    }
}
