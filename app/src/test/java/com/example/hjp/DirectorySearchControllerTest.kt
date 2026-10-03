package com.example.hjp

import com.hjp.tool.contact.BusinessCardRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DirectorySearchControllerTest {
    @Test fun failedInferenceIsNotAnEmptySuccessfulSearchAndRetryRecovers() = runTest {
        var fail = true
        val controller = DirectorySearchController(this) {
            if (fail) error("required embedding inference failed")
            emptyList()
        }
        controller.submit("query")
        runCurrent()
        assertTrue(controller.state.value.failed)
        assertNull(controller.state.value.results)
        assertFalse(controller.state.value.searching)
        fail = false
        controller.submit("query")
        runCurrent()
        assertFalse(controller.state.value.failed)
        assertEquals(emptyList<BusinessCardRecord>(), controller.state.value.results)
    }

    @Test fun cancelledOlderSearchCannotReplaceNewerResults() = runTest {
        val releaseOld = CompletableDeferred<Unit>()
        val controller = DirectorySearchController(this) { query ->
            if (query == "old") withContext(NonCancellable) { releaseOld.await() }
            listOf(BusinessCardRecord(query, query))
        }
        controller.submit("old")
        runCurrent()
        controller.submit("new")
        runCurrent()
        assertEquals("new", controller.state.value.results?.single()?.id)
        releaseOld.complete(Unit)
        runCurrent()
        assertEquals("new", controller.state.value.results?.single()?.id)
        assertFalse(controller.state.value.failed)
    }

    @Test fun clearingQueryCancelsPendingSearchWithoutDisplayingFailure() = runTest {
        val release = CompletableDeferred<Unit>()
        val controller = DirectorySearchController(this) {
            withContext(NonCancellable) { release.await() }
            listOf(BusinessCardRecord("old", "Old"))
        }
        controller.submit("old")
        runCurrent()
        controller.reset()
        release.complete(Unit)
        runCurrent()
        assertEquals(DirectorySearchState(), controller.state.value)
    }
}
