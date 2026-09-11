package com.hjp.agent.core

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the estimator against the real Gemma 4 E2B SentencePiece tokenizer.
 *
 * The expected counts come from `tools/agent_eval/measure_context_budget.py`, which tokenizes these
 * exact strings with `models/gemma-4-E2B-it.litertlm`. The estimator must never *under*-count, or a
 * prompt could silently exceed the artifact's context.
 */
class CalibratedGemmaTokenEstimatorTest {
    private val samples = listOf(
        Sample("김지원에게 지난 미팅 감사 메일 작성해줘. 정중한 말투로 부탁해.", measured = 21),
        Sample("‘김지원’ 명함을 찾았습니다. 비전글로벌 대표이사이고 이메일은 jiwon@example.com입니다.", measured = 29),
        Sample(
            "[conversation_memory]\nschema_version: 2\ntopic: 김지원에게 감사 메일\n" +
                "selected_contact:\n- card_id=C001 name=김지원 company=비전글로벌 title=대표이사 " +
                "basis=SINGLE_RESULT provenance=TOOL_VERIFIED\nactions:\n- COMPLETED 김지원 명함 찾아줘\n",
            measured = 76,
        ),
        Sample(
            "{\"name\":\"search_contacts\",\"description\":\"저장된 명함을 검색합니다.\"," +
                "\"parameters\":{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}," +
                "\"required\":[\"query\"]}}",
            measured = 39,
        ),
    )

    @Test
    fun `estimate never under-counts the measured tokenizer`() {
        samples.forEach { sample ->
            val estimate = CalibratedGemmaTokenEstimator.estimate(sample.text)
            assertTrue(
                "estimate=$estimate measured=${sample.measured} for '${sample.text.take(24)}'",
                estimate >= sample.measured,
            )
        }
    }

    @Test
    fun `estimate stays within a usable margin of the measured tokenizer`() {
        samples.forEach { sample ->
            val estimate = CalibratedGemmaTokenEstimator.estimate(sample.text)
            assertTrue(
                "estimate=$estimate measured=${sample.measured}",
                estimate <= sample.measured * 1.30,
            )
        }
    }

    @Test
    fun `empty text costs nothing`() {
        assertTrue(CalibratedGemmaTokenEstimator.estimate("") == 0)
    }

    private data class Sample(val text: String, val measured: Int)
}
