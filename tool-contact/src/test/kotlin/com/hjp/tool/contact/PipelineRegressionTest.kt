package com.hjp.tool.contact

import com.hjp.searchlookup.EmbeddingEngine
import com.hjp.searchlookup.OnDeviceEmbeddingEngine
import com.hjp.tool.contact.*
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolRequest
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/** Boundary-contract tests with controlled vectors; no actual-model success is claimed. */
class PipelineRegressionTest {
    private open class Engine : EmbeddingEngine {
        override fun name() = "pipeline-audit-controlled-vectors"
        override fun isModelBacked() = true
        override fun embed(input: String) = embedQuery(input)
        override fun embedQuery(input: String) = FloatArray(768).also { it[0] = 1f }
        override fun embedDocument(input: String) = FloatArray(768).also { it[0] = 1f }
    }

    private open class Repository(initial: BusinessCardRecord) : MutableBusinessCardRepository, BusinessCardEmbeddingStore {
        var card = initial
        val vectors = linkedMapOf<String, StoredCardEmbedding>()
        override suspend fun loadAll() = listOf(card)
        override suspend fun getById(cardId: String) = card.takeIf { it.id == cardId }
        override suspend fun loadEmbeddings(modelName: String) = vectors.values.filter { it.modelName == modelName }
        override suspend fun upsertEmbeddings(embeddings: List<StoredCardEmbedding>) {
            embeddings.forEach { vectors[it.modelName + ":" + it.cardId] = it }
        }
        override suspend fun update(cardId: String, updates: Map<String, String>, clearFields: Set<String>, updatedAt: String): BusinessCardUpdateResult? {
            val before = getById(cardId) ?: return null
            card = before.copy(name = updates["name"] ?: before.name, memo = updates["memo"] ?: before.memo, updatedAt = updatedAt)
            return BusinessCardUpdateResult(before, card)
        }
        override suspend fun restore(snapshot: BusinessCardRecord): Boolean { card = snapshot; return true }
    }

    @Test fun searchHitPreservesTheStoredContactFields() = runBlocking {
        val original = BusinessCardRecord("audit-card", "Audit Person", phone = "02-1234-5678",
            mobile = "010-9876-5432", website = "https://example.test", updatedAt = "2026-10-04T00:00:00Z")
        val backend = RyeongContactSearchBackend(Repository(original), { OnDeviceEmbeddingEngine.required(Engine()) }, requireModelBacked = true)
        val hit = backend.search("Audit Person", 1).hits.single().card
        assertEquals("A search hit used by the UI must preserve the repository record", original, hit)
    }

    @Test fun successfullyExecutedSemanticSearchMayReturnZeroCandidates() = runBlocking {
        val engine = object : Engine() {
            override fun embedDocument(input: String) = FloatArray(768).also { it[1] = 1f }
        }
        val backend = RyeongContactSearchBackend(Repository(BusinessCardRecord("audit-card", "Audit Person")),
            { OnDeviceEmbeddingEngine.required(engine) }, requireModelBacked = true)
        val response = backend.search("unmatched-concept", 5)
        assertTrue("Valid inference with no matching vectors is an empty search, not a model failure", response.hits.isEmpty())
        assertEquals("HYBRID", response.mode)
        assertFalse(response.fallbackUsed)
    }

    @Test fun compoundSurnameQueryFiltersOutOtherStoredSurnames() = runBlocking {
        val original = BusinessCardRecord("audit-card", "남궁민")
        val other = BusinessCardRecord("other-card", "김철수")
        val singleSurname = BusinessCardRecord("single-surname", "남철수")
        val repository = object : Repository(original) {
            override suspend fun loadAll() = listOf(original, other, singleSurname)
            override suspend fun getById(cardId: String) = loadAll().firstOrNull { it.id == cardId }
        }
        val backend = RyeongContactSearchBackend(repository,
            { OnDeviceEmbeddingEngine.required(Engine()) }, requireModelBacked = true)
        for (query in listOf("남궁씨 성을 가진 사람 찾아줘", "남궁씨", "남궁씨성 가진 사람", "성이 남궁", "성은 남궁씨")) {
            assertEquals(query, listOf(original.id), backend.search(query, 5).hits.map { it.card.id })
        }
        assertEquals(listOf(singleSurname.id), backend.search("남씨 성을 가진 사람", 5).hits.map { it.card.id })
        assertTrue(backend.search("황보씨 성을 가진 사람", 5).hits.isEmpty())
        assertEquals(original.id, backend.search("남궁민", 5).hits.single().card.id)
    }

    @Test fun strictCompositionActuallyStopsWhenInferenceFails() = runBlocking {
        val engine = object : Engine() {
            override fun embedQuery(input: String): FloatArray = error("injected query inference failure")
        }
        val backend = RyeongContactSearchBackend(Repository(BusinessCardRecord("audit-card", "Audit Person", memo = "auditword")),
            { OnDeviceEmbeddingEngine.required(engine) }, requireModelBacked = true)
        var stopped = false
        try { backend.search("auditword", 1) } catch (_: IllegalStateException) { stopped = true }
        assertTrue("The strict Android composition propagates real inference failure", stopped)
    }

    @Test fun cancelledMutationRollsBackBothDataAndPublishedSearchSnapshot() = runBlocking {
        withTimeout(10000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var blockWrites = false
            val before = BusinessCardRecord("audit-card", "Old Person", memo = "oldmemo")
            val repository = object : Repository(before) {
                override suspend fun upsertEmbeddings(embeddings: List<StoredCardEmbedding>) {
                    if (blockWrites) { entered.complete(Unit); release.await() }
                    super.upsertEmbeddings(embeddings)
                }
            }
            val backend = RyeongContactSearchBackend(repository, { OnDeviceEmbeddingEngine.required(Engine()) }, requireModelBacked = true)
            backend.search("oldmemo", 1)
            val oldHash = repository.vectors.values.single().sourceTextHash
            blockWrites = true
            val plugin = UpdateBusinessCardPlugin(repository, onUpdated = { backend.refreshAfterCardChange() })
            val mutation = async {
                plugin.execute(ToolRequest("audit-update", ContactToolContracts.Update.capabilityId,
                    ContactToolContracts.Update.version, buildJsonObject {
                        put("card_id", before.id)
                        put("updates", buildJsonObject { put("name", "New Person"); put("memo", "newmemo") })
                    }), ToolExecutionContext("audit-session", "audit-turn", "ko-KR", "Asia/Seoul"))
            }
            entered.await()
            mutation.cancel(CancellationException("injected cancellation at vector persistence"))
            release.complete(Unit)
            mutation.join()
            assertEquals("The repository rollback should succeed", before, repository.card)
            val hit = backend.search("newmemo", 1).hits.single().card
            assertEquals("The search snapshot must agree with the rolled-back repository", repository.card.name, hit.name)
            assertEquals(oldHash, repository.vectors.values.single().sourceTextHash)
        }
    }

    @Test fun rollbackInvalidatesSnapshotRebuiltWhileDatabaseStillHeldTheMutation() = runBlocking {
        val before = BusinessCardRecord("audit-card", "Old Person", memo = "oldmemo")
        lateinit var backend: RyeongContactSearchBackend
        val repository = object : Repository(before) {
            override suspend fun restore(snapshot: BusinessCardRecord): Boolean {
                // A concurrent reader can rebuild between refresh failure and DB restoration.
                assertEquals("New Person", backend.search("newmemo", 1).hits.single().card.name)
                return super.restore(snapshot)
            }
        }
        backend = RyeongContactSearchBackend(repository, { OnDeviceEmbeddingEngine.required(Engine()) },
            requireModelBacked = true)
        val plugin = UpdateBusinessCardPlugin(repository,
            onUpdated = { backend.refreshAfterCardChange(); error("injected failure after publication") },
            onRolledBack = { backend.invalidate() })
        val failure = runCatching {
            plugin.execute(ToolRequest("rollback-update", ContactToolContracts.Update.capabilityId,
                ContactToolContracts.Update.version, buildJsonObject {
                    put("card_id", before.id)
                    put("updates", buildJsonObject { put("name", "New Person"); put("memo", "newmemo") })
                }), ToolExecutionContext("audit-session", "audit-turn", "ko-KR", "Asia/Seoul"))
        }.exceptionOrNull()
        assertEquals("injected failure after publication", failure?.message)
        assertEquals(before, backend.search("oldmemo", 1).hits.single().card)
    }
}
