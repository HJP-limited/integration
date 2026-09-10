package com.example.hjp.search

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt

interface TextEmbeddingProvider {
    val name: String
    val isModelBacked: Boolean
    val diagnosticStatus: String

    /** 검색 질의를 임베딩한다. EmbeddingGemma의 query 프롬프트 형식이 자동 적용된다. */
    fun embedQuery(text: String): FloatArray

    /** 저장할 문서(명함)를 임베딩한다. document 프롬프트 형식이 자동 적용된다. */
    fun embedDocument(text: String): FloatArray

    fun close() {}
}

/**
 * 임베딩 모델이 없는 환경(모델 미배치 기기, 데스크톱 스모크 실행)에서의 기본 구현.
 *
 * 벡터 검색을 끄고 키워드 경로로만 동작하게 한다 — 검색 결과가 없는 것과 임베딩이 없는 것을
 * 호출부가 `isModelBacked` 로 구분할 수 있어야 하이브리드/키워드 폴백 판정이 갈라지지 않는다.
 */
object NoEmbeddingProvider : TextEmbeddingProvider {
    override val name: String = "none"
    override val isModelBacked: Boolean = false
    override val diagnosticStatus: String = "embedding provider not configured"

    override fun embedQuery(text: String): FloatArray = FloatArray(0)

    override fun embedDocument(text: String): FloatArray = FloatArray(0)
}

fun normalize(source: FloatArray): FloatArray {
    var sum = 0f
    for (value in source) sum += value * value
    if (sum == 0f) return source
    val norm = sqrt(sum)
    for (i in source.indices) source[i] /= norm
    return source
}

fun cosine(a: FloatArray, b: FloatArray): Float {
    if (a.isEmpty() || a.size != b.size) return 0f
    var dot = 0f
    for (i in a.indices) dot += a[i] * b[i]
    return dot
}

fun FloatArray.toBlob(): ByteArray {
    val buffer = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
    forEach { buffer.putFloat(it) }
    return buffer.array()
}

fun ByteArray.toFloatArray(dimensions: Int): FloatArray {
    val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(dimensions) { buffer.float }
}

fun sha256(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
