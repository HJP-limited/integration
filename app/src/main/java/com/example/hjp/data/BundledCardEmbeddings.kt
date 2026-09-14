package com.example.hjp.data

import android.content.Context
import com.hjp.searchlookup.EmbeddingInput
import com.hjp.searchlookup.EmbeddingUpdater
import com.hjp.tool.contact.StoredCardEmbedding
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Validates and imports the document vectors produced by scripts/precompute_embeddings.py. */
internal class BundledCardEmbeddings(
    context: Context,
    private val json: Json = Json,
) {
    private val assets = context.applicationContext.assets

    fun supports(modelName: String): Boolean = modelName.startsWith(MODEL_NAME_PREFIX)

    fun load(modelName: String, currentCardIds: Set<String>): List<StoredCardEmbedding> {
        if (!supports(modelName)) return emptyList()

        val cardsBytes = assets.open(CARDS_ASSET).use { it.readBytes() }
        val idsBytes = assets.open(IDS_ASSET).use { it.readBytes() }
        val vectors = assets.open(VECTORS_ASSET).use { it.readBytes() }
        val stamp = assets.open(FINGERPRINT_ASSET).bufferedReader(Charsets.UTF_8).use {
            json.parseToJsonElement(it.readText()) as JsonObject
        }
        val cards = json.parseToJsonElement(cardsBytes.toString(Charsets.UTF_8)) as JsonArray
        val ids = json.parseToJsonElement(idsBytes.toString(Charsets.UTF_8)) as JsonArray

        val expectedModelName = MODEL_NAME_PREFIX +
            "${stamp.string("runtime_model_sha256").take(16)};" +
            "t=${stamp.string("runtime_tokenizer_sha256").take(16)}"
        require(modelName == expectedModelName) {
            "Bundled vectors belong to $expectedModelName, not $modelName"
        }
        require(stamp.string("embedding_input_sha256") == fingerprint(cards)) {
            "Bundled card embeddings are stale for cards_seed.json"
        }
        require(stamp.string("ids_sha256") == sha256(idsBytes)) {
            "Bundled embedding ID checksum mismatch"
        }
        require(stamp.string("vectors_sha256") == sha256(vectors)) {
            "Bundled embedding vector checksum mismatch"
        }
        require(stamp.int("count") == cards.size && ids.size == cards.size) {
            "Bundled embedding count mismatch"
        }
        require(vectors.size == ids.size * VECTOR_BYTES) {
            "Bundled embedding size mismatch: expected=${ids.size * VECTOR_BYTES} actual=${vectors.size}"
        }

        val cardsById = cards.associate { element ->
            val card = element as JsonObject
            card.string("id") to card
        }
        val now = System.currentTimeMillis()
        return ids.mapIndexedNotNull { index, element ->
            val cardId = (element as JsonPrimitive).content
            val card = cardsById[cardId] ?: error("Embedding references unknown card: $cardId")
            if (cardId !in currentCardIds) return@mapIndexedNotNull null
            val start = index * VECTOR_BYTES
            StoredCardEmbedding(
                cardId = cardId,
                modelName = modelName,
                dimension = DIMENSIONS,
                vectorBlob = vectors.copyOfRange(start, start + VECTOR_BYTES),
                sourceTextHash = EmbeddingUpdater.sha256(card.embeddingInput()),
                createdAtMillis = now,
                updatedAtMillis = now,
            )
        }
    }

    private fun JsonObject.embeddingInput(): String = EmbeddingInput.forCard(
        string("name"),
        string("nameEn"),
        string("company"),
        string("title"),
        string("department"),
        string("industry"),
        string("location"),
        string("memo"),
        string("tags").split(',').map(String::trim).filter(String::isNotEmpty),
    )

    /** Matches scripts/card_fingerprint.py: sorted cards, sorted keys, compact UTF-8 JSON. */
    private fun fingerprint(cards: JsonArray): String {
        val rows = cards.map { it as JsonObject }
            .sortedBy { it.string("id") }
            .map { card ->
                JsonObject(FINGERPRINT_FIELDS.sorted().associateWith { field ->
                    card[field] ?: JsonPrimitive("")
                })
            }
        return sha256(JsonArray(rows).toString().toByteArray(Charsets.UTF_8))
    }

    private fun JsonObject.string(key: String): String =
        (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.int(key: String): Int = string(key).toInt()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MODEL_NAME_PREFIX = "google/embeddinggemma-300m-ai-edge-rag#m="
        const val DIMENSIONS = 768
        const val VECTOR_BYTES = DIMENSIONS * Float.SIZE_BYTES
        const val CARDS_ASSET = "cards/cards_seed.json"
        const val IDS_ASSET = "cards/cards_embeddings_ids.json"
        const val VECTORS_ASSET = "cards/cards_embeddings.bin"
        const val FINGERPRINT_ASSET = "cards/cards_embeddings_fingerprint.json"
        val FINGERPRINT_FIELDS = listOf(
            "name", "nameEn", "company", "title", "department",
            "industry", "location", "memo", "tags",
        )
    }
}
