package com.example.hjp

import com.example.hjp.models.DownloadableModel
import com.example.hjp.models.ModelInstaller
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelInstallerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "verified model bytes".toByteArray()
    private fun model() = DownloadableModel("model.bin", "https://huggingface.co/model", bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

    @Test fun `complete file is verified installed and reused without another request`() = runBlocking {
        var requests = 0
        val installer = ModelInstaller(temporary.root) { requests++; Response(it, bytes) }
        val file = installer.download(model(), "") { _, _ -> }
        assertArrayEquals(bytes, file.readBytes())
        installer.download(model(), "") { _, _ -> }
        assertEquals(1, requests)
        assertFalse(File(temporary.root, "model.bin.part").exists())
    }

    @Test fun `partial file resumes only with matching range and final hash`() = runBlocking {
        File(temporary.root, "model.bin.part").writeBytes(bytes.take(4).toByteArray())
        lateinit var response: Response
        val installer = ModelInstaller(temporary.root) {
            Response(it, bytes.drop(4).toByteArray(), 206, mapOf("Content-Range" to "bytes 4-${bytes.size - 1}/${bytes.size}"))
                .also { response = it }
        }
        assertArrayEquals(bytes, installer.download(model(), "") { _, _ -> }.readBytes())
        assertEquals("bytes=4-", response.getRequestProperty("Range"))
        assertTrue(response.closed)
    }

    @Test fun `hash mismatch preserves existing model and removes corrupt partial`() = runBlocking {
        val old = File(temporary.root, "model.bin").apply { writeText("old model") }
        val installer = ModelInstaller(temporary.root) { Response(it, ByteArray(bytes.size)) }
        assertTrue(runCatching { installer.download(model(), "") { _, _ -> } }.isFailure)
        assertEquals("old model", old.readText())
        assertFalse(File(temporary.root, "model.bin.part").exists())
    }

    @Test fun `cdn redirect never receives hugging face token`() = runBlocking {
        val connections = mutableListOf<Response>()
        val installer = ModelInstaller(temporary.root) { url ->
            (if (connections.isEmpty()) Response(url, byteArrayOf(), 302, mapOf("Location" to "https://cdn.example/model"))
            else Response(url, bytes)).also { connections += it }
        }
        installer.download(model(), "test-token") { _, _ -> }
        assertEquals("Bearer test-token", connections.first().getRequestProperty("Authorization"))
        assertNull(connections.last().getRequestProperty("Authorization"))
        assertTrue(connections.all { it.closed })
    }

    @Test fun `http redirect and forbidden response never become installed models`() = runBlocking {
        for (response in listOf(302, 403)) {
            val installer = ModelInstaller(temporary.root) {
                Response(it, bytes, response, mapOf("Location" to "http://cdn.example/model"))
            }
            assertTrue(runCatching { installer.download(model(), "") { _, _ -> } }.isFailure)
            assertFalse(File(temporary.root, "model.bin").exists())
        }
    }

    @Test fun `import also verifies and preserves old file when truncated`() = runBlocking {
        val old = File(temporary.root, "model.bin").apply { writeText("old") }
        val installer = ModelInstaller(temporary.root) { error("Import must not use network") }
        assertTrue(runCatching { installer.installFrom(model()) { bytes.take(3).toByteArray().inputStream() } }.isFailure)
        assertEquals("old", old.readText())
        assertArrayEquals(bytes, installer.installFrom(model()) { bytes.inputStream() }.readBytes())
    }

    @Test fun `download manifest agrees with runtime model identities`() {
        val models = com.example.hjp.models.ModelDownloads.required.associateBy { it.fileName }
        assertEquals(com.hjp.agent.core.ModelDeploymentResolver.OFFICIAL_GENERATIVE_ARTIFACT_SHA256,
            models.getValue("hjp-agent.litertlm").sha256)
        assertEquals(com.example.hjp.search.AndroidEmbeddingGemmaEngine.EXPECTED_MODEL_SHA256,
            models.getValue("embeddinggemma-300m.tflite").sha256)
        assertEquals(com.example.hjp.search.AndroidEmbeddingGemmaEngine.EXPECTED_TOKENIZER_SHA256,
            models.getValue("sentencepiece.model").sha256)
    }

    @Test fun `system download is verified then promoted without duplicating model`() = runBlocking {
        val staged = File(temporary.root, "model.bin.download").apply { writeBytes(bytes) }
        val installer = ModelInstaller(temporary.root) { error("Promotion must not use network") }
        assertArrayEquals(bytes, installer.promoteDownload(model()).readBytes())
        assertFalse(staged.exists())
    }

    @Test fun `corrupt system download cannot replace existing verified model`() = runBlocking {
        val existing = File(temporary.root, "model.bin").apply { writeBytes(bytes) }
        File(temporary.root, "model.bin.download").writeBytes(ByteArray(bytes.size))
        assertTrue(runCatching { ModelInstaller(temporary.root).promoteDownload(model()) }.isFailure)
        assertArrayEquals(bytes, existing.readBytes())
    }

    @Test fun `service only downloads public generative model and bundles embedding pair`() {
        val manifest = com.example.hjp.models.ModelDownloads
        assertEquals("hjp-agent.litertlm", manifest.generative.fileName)
        assertEquals(setOf("embeddinggemma-300m.tflite", "sentencepiece.model"), manifest.bundled.map { it.fileName }.toSet())
        assertTrue(manifest.generative.url.startsWith("https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/b3ca0d2"))
    }

    private class Response(url: URL, private val bytes: ByteArray, private val status: Int = 200,
        private val headers: Map<String, String> = emptyMap()) : HttpURLConnection(url) {
        var closed = false
        override fun getResponseCode() = status
        override fun getHeaderField(name: String) = headers[name] ?: if (name == "Content-Length") bytes.size.toString() else null
        override fun getInputStream() = bytes.inputStream()
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
    }
}
