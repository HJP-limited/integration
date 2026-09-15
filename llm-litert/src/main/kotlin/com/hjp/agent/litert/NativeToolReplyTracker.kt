package com.hjp.agent.litert

/** Native tool messages may only answer outstanding calls; kernel-owned results are context. */
class NativeToolReplyTracker {
    private val pending = mutableListOf<String>()
    fun recordAssistantCalls(names: List<String>) {
        pending.clear()
        pending.addAll(names)
    }
    fun consume(name: String): Boolean = pending.remove(name)
    fun reset() = pending.clear()
}
