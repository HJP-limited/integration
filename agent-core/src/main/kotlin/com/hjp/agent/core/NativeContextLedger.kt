package com.hjp.agent.core

/**
 * Shadow record of everything the native LiteRT `Conversation` is holding.
 *
 * LiteRT re-renders the whole stored message list through the model chat template on every send, so
 * the string handed to `sendMessage()` is only the *new* part of the input. The runtime exposes no
 * accumulated-input token count, so this ledger reproduces the same content in the same order and
 * reports an **estimate**. It is not a measurement of the runtime's own tokenization, and it must
 * never be reported as one.
 */
class NativeContextLedger(
    private val estimator: TokenEstimator = CalibratedGemmaTokenEstimator,
) {
    private val history = mutableListOf<String>()
    private var fixedText: String = ""

    var rotations: Int = 0
        private set

    /** Cost that every request pays: system instruction plus the whole tool catalog. */
    fun setFixedContext(systemInstruction: String, toolCatalogText: String) {
        fixedText = "<|turn>system\n$systemInstruction\n\n$toolCatalogText<turn|>"
    }

    fun appendUser(renderedPrompt: String) {
        history += "<|turn>user\n$renderedPrompt<turn|>"
    }

    fun appendAssistant(text: String) {
        if (text.isBlank()) return
        history += "<|turn>model\n$text<turn|>"
    }

    fun appendToolCall(toolName: String, arguments: String) {
        history += "<|tool_call>call:$toolName$arguments<tool_call|>"
    }

    fun appendToolResponse(toolName: String, payload: String) {
        history += "<|tool_response>response:$toolName$payload<tool_response|>"
    }

    /** A fresh native conversation keeps the fixed cost and drops the accumulated history. */
    fun rotate() {
        history.clear()
        rotations += 1
    }

    fun clear() {
        history.clear()
        rotations = 0
    }

    fun serialized(): String = (listOf(fixedText) + history).filter(String::isNotEmpty).joinToString("\n")

    fun fixedTokens(): Int = estimator.estimate(fixedText)

    fun historyTokens(): Int = history.sumOf(estimator::estimate)

    fun totalTokens(): Int = fixedTokens() + historyTokens()

    fun snapshot(): NativeContextSnapshot = NativeContextSnapshot(
        fixedTokens = fixedTokens(),
        historyTokens = historyTokens(),
        totalTokens = totalTokens(),
        entries = history.size,
        rotations = rotations,
    )
}

data class NativeContextSnapshot(
    val fixedTokens: Int,
    val historyTokens: Int,
    val totalTokens: Int,
    val entries: Int,
    val rotations: Int,
)
