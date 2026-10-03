package com.example.hjp

import com.hjp.tool.contact.BusinessCardRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

data class DirectorySearchState(
    val searching: Boolean = false,
    val results: List<BusinessCardRecord>? = null,
    val failed: Boolean = false,
)

/** A failed search is distinct from an empty successful search; older jobs cannot publish. */
class DirectorySearchController(
    private val scope: CoroutineScope,
    private val search: suspend (String) -> List<BusinessCardRecord>,
) {
    private val mutableState = MutableStateFlow(DirectorySearchState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun reset() {
        job?.cancel()
        mutableState.value = DirectorySearchState()
    }

    fun submit(query: String) {
        reset()
        if (query.isBlank()) return
        mutableState.value = DirectorySearchState(searching = true)
        job = scope.launch {
            try {
                val results = search(query.trim())
                coroutineContext.ensureActive()
                mutableState.value = DirectorySearchState(results = results)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                coroutineContext.ensureActive()
                mutableState.value = DirectorySearchState(failed = true)
            }
        }
    }
}
