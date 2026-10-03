package com.hjp.tool.contact

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** The same save/refresh/rollback contract for Android, desktop CLI and desktop web OCR. */
class BusinessCardRegistration(
    private val repository: InsertableBusinessCardRepository,
    private val onAdded: suspend () -> Unit,
    private val onRolledBack: suspend () -> Unit = {},
) {
    private val mutex = Mutex()

    suspend fun add(record: BusinessCardRecord) = mutex.withLock {
        coroutineContext.ensureActive()
        require(repository.getById(record.id) == null) { "Business card ID already exists: ${record.id}" }
        var inserted = false
        try {
            // Short DB write only; model inference in onAdded remains cancellable.
            withContext(NonCancellable) {
                repository.insert(record)
                inserted = true
            }
            coroutineContext.ensureActive()
            onAdded()
            coroutineContext.ensureActive()
        } catch (error: Throwable) {
            if (inserted) {
                val removed = try {
                    withContext(NonCancellable) {
                        val removed = repository.delete(record.id)
                        if (removed) onRolledBack()
                        removed
                    }
                } catch (rollbackError: Throwable) {
                    error.addSuppressed(rollbackError)
                    false
                }
                if (!removed) {
                    throw IllegalStateException("OCR card insert could not be rolled back", error)
                }
            }
            throw error
        }
    }
}
