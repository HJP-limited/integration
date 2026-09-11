package com.hjp.tool.contact

import com.hjp.searchlookup.BusinessCard
import com.hjp.searchlookup.EmbeddingEngine
import com.hjp.searchlookup.LocalEmbeddingEngine
import com.hjp.searchlookup.OnDeviceEmbeddingEngine
import com.hjp.searchlookup.RetrievalMode
import com.hjp.searchlookup.SearchLookupService
import java.lang.management.ManagementFactory
import kotlin.math.roundToLong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RyeongSearchEvaluatorTest {
    @Test
    fun `integrated retrieval is no worse than previous adapter on room seed fixture`() = runBlocking {
        val cards = fixtureCards()
        val cases = evalCases()
        val previousInitStarted = System.nanoTime()
        val previousService = SearchLookupService(
            cards.map { it.toRyeongCard() },
            LocalEmbeddingEngine(),
        )
        val previousInitializationMillis = nanosToMillis(System.nanoTime() - previousInitStarted)
        val previous = evaluate(cases) { query ->
            previousService.retrieve(query, 5, RetrievalMode.KEYWORD_ONLY)
                .results.map { it.cardId }
        }
        val previousLatencies = latencySamples(100) {
            previousService.retrieve(
                cases[it % cases.size].query,
                5,
                RetrievalMode.KEYWORD_ONLY,
            )
        }
        val previousHeapPeakMb = measureHeapPeakMb {
            repeat(100) {
                previousService.retrieve(
                    cases[it % cases.size].query,
                    5,
                    RetrievalMode.KEYWORD_ONLY,
                )
            }
        }

        val repository = object : BusinessCardRepository {
            override suspend fun loadAll() = cards
            override suspend fun getById(cardId: String) = cards.firstOrNull { it.id == cardId }
        }
        val integratedBackend = RyeongContactSearchBackend(repository)
        val initStarted = System.nanoTime()
        val first = integratedBackend.search(cases.first().query, 5)
        val initializationMillis = nanosToMillis(System.nanoTime() - initStarted)
        val integrated = evaluate(cases) { query ->
            integratedBackend.search(query, 5).hits.map { it.card.id }
        }
        val integratedLatencies = latencySamples(100) {
            integratedBackend.search(cases[it % cases.size].query, 5)
        }
        val integratedHeapPeakMb = measureHeapPeakMb {
            repeat(100) {
                integratedBackend.search(cases[it % cases.size].query, 5)
            }
        }
        val fallbackRatio = cases.count {
            integratedBackend.search(it.query, 5).fallbackUsed
        }.toDouble() / cases.size

        val hybridBackend = RyeongContactSearchBackend(
            repository,
            embeddingEngineFactory = {
                OnDeviceEmbeddingEngine.production(DeterministicEmbeddingGemma())
            },
        )
        val hybrid = evaluate(cases) { query ->
            hybridBackend.search(query, 5).hits.map { it.card.id }
        }
        val hybridHeapPeakMb = measureHeapPeakMb {
            repeat(100) { hybridBackend.search(cases[it % cases.size].query, 5) }
        }

        assertEquals("C001", first.hits.first().card.id)
        assertTrue(integrated.recallAt1 >= previous.recallAt1)
        assertTrue(integrated.recallAt5 >= previous.recallAt5)
        assertTrue(integrated.mrr >= previous.mrr)
        assertTrue(hybrid.semanticSuccess >= integrated.semanticSuccess)
        assertEquals(1.0, fallbackRatio, 0.0)

        println(
            "[RYEONG_EVAL] " +
                "previous=${previous.compact()} " +
                "integrated_fallback=${integrated.compact()} " +
                "deterministic_hybrid=${hybrid.compact()} " +
                "previous_init_ms=${"%.3f".format(previousInitializationMillis)} " +
                "integrated_init_ms=${"%.3f".format(initializationMillis)} " +
                "previous_p50_ms=${"%.3f".format(percentile(previousLatencies, 0.50))} " +
                "previous_p95_ms=${"%.3f".format(percentile(previousLatencies, 0.95))} " +
                "integrated_p50_ms=${"%.3f".format(percentile(integratedLatencies, 0.50))} " +
                "integrated_p95_ms=${"%.3f".format(percentile(integratedLatencies, 0.95))} " +
                "previous_heap_peak_mb=${"%.3f".format(previousHeapPeakMb)} " +
                "integrated_heap_peak_mb=${"%.3f".format(integratedHeapPeakMb)} " +
                "deterministic_hybrid_heap_peak_mb=${"%.3f".format(hybridHeapPeakMb)} " +
                "fallback_ratio=${"%.3f".format(fallbackRatio)}",
        )
    }

    private suspend fun evaluate(
        cases: List<EvalCase>,
        search: suspend (String) -> List<String>,
    ): EvalMetrics {
        var recallAt1 = 0
        var recallAt5 = 0
        var reciprocalRank = 0.0
        var exactNameSuccess = 0
        var semanticSuccess = 0
        cases.forEach { case ->
            val results = search(case.query)
            val rank = results.indexOf(case.cardId) + 1
            if (rank == 1) recallAt1++
            if (rank in 1..5) {
                recallAt5++
                reciprocalRank += 1.0 / rank
            }
            if (case.type == "exact" && rank == 1) exactNameSuccess++
            if (case.type == "semantic" && rank in 1..5) semanticSuccess++
        }
        val count = cases.size.toDouble()
        return EvalMetrics(
            recallAt1 / count,
            recallAt5 / count,
            reciprocalRank / count,
            exactNameSuccess.toDouble() / cases.count { it.type == "exact" },
            semanticSuccess.toDouble() / cases.count { it.type == "semantic" },
        )
    }

    private suspend fun latencySamples(count: Int, block: suspend (Int) -> Unit): List<Double> =
        List(count) { index ->
            val started = System.nanoTime()
            block(index)
            nanosToMillis(System.nanoTime() - started)
        }

    private suspend fun measureHeapPeakMb(block: suspend () -> Unit): Double {
        val pools = ManagementFactory.getMemoryPoolMXBeans().filter {
            it.type == java.lang.management.MemoryType.HEAP
        }
        pools.forEach { it.resetPeakUsage() }
        block()
        return pools.sumOf { it.peakUsage.used.coerceAtLeast(0L) }.toDouble() /
            (1024.0 * 1024.0)
    }

    private fun percentile(values: List<Double>, fraction: Double): Double {
        val sorted = values.sorted()
        val index = ((sorted.lastIndex * fraction).roundToLong()).toInt()
        return sorted[index]
    }

    private fun nanosToMillis(value: Long): Double = value / 1_000_000.0

    private fun fixtureCards() = listOf(
        BusinessCardRecord(
            "C001",
            "김지원",
            "Jiwon Kim",
            "비전글로벌",
            "대표이사",
            "전략팀",
            "finance",
            "서울",
            memo = "스타트업 투자와 파트너십 미팅에서 만난 대표",
            tags = listOf("대표", "투자", "파트너십"),
        ),
        BusinessCardRecord(
            "C002",
            "오성령",
            "Sungryung Oh",
            "코어AI",
            "AI 엔지니어",
            "플랫폼팀",
            "it",
            "판교",
            memo = "EmbeddingGemma와 로컬 벡터 검색을 실험 중",
            tags = listOf("AI", "개발자", "임베딩", "검색"),
        ),
    )

    private fun evalCases() = listOf(
        EvalCase("김지원", "C001", "exact"),
        EvalCase("오성령", "C002", "exact"),
        EvalCase("Jiwon Kim", "C001", "exact"),
        EvalCase("Sungryung Oh", "C002", "exact"),
        EvalCase("비전글로벌 대표이사", "C001", "keyword"),
        EvalCase("서울 전략팀 투자", "C001", "keyword"),
        EvalCase("코어AI 엔지니어", "C002", "keyword"),
        EvalCase("판교 플랫폼팀 개발자", "C002", "keyword"),
        EvalCase("벤처 자금 조달 책임자", "C001", "semantic"),
        EvalCase("인공지능 기술 전문가", "C002", "semantic"),
    )

    private fun BusinessCardRecord.toRyeongCard() = BusinessCard(
        id,
        name,
        nameEn,
        company,
        title,
        department,
        industry,
        location,
        phone,
        email,
        address,
        memo,
        tags,
    )

    private data class EvalCase(val query: String, val cardId: String, val type: String)

    private data class EvalMetrics(
        val recallAt1: Double,
        val recallAt5: Double,
        val mrr: Double,
        val exactNameSuccess: Double,
        val semanticSuccess: Double,
    ) {
        fun compact(): String =
            "{r1=${"%.3f".format(recallAt1)},r5=${"%.3f".format(recallAt5)}," +
                "mrr=${"%.3f".format(mrr)},exact=${"%.3f".format(exactNameSuccess)}," +
                "semantic=${"%.3f".format(semanticSuccess)}}"
    }

    private class DeterministicEmbeddingGemma : EmbeddingEngine {
        override fun embed(input: String): FloatArray = embedQuery(input)
        override fun embedQuery(input: String): FloatArray = vector(input)
        override fun embedDocument(input: String): FloatArray = vector(input)
        override fun name() = "deterministic-embeddinggemma-evaluator"
        override fun isModelBacked() = true

        private fun vector(input: String): FloatArray {
            val result = FloatArray(768)
            when {
                input.contains("투자") || input.contains("스타트업") ||
                    input.contains("벤처") || input.contains("자금") ||
                    input.contains("파트너십") -> result[0] = 1f
                input.contains("AI", true) || input.contains("인공지능") ||
                    input.contains("EmbeddingGemma", true) -> result[1] = 1f
                else -> result[767] = 1f
            }
            return result
        }
    }
}
