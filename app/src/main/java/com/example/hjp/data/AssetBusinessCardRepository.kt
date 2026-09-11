package com.example.hjp.data

import android.content.Context
import com.hjp.tool.contact.BusinessCardRecord
import com.hjp.tool.contact.BusinessCardRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class AssetBusinessCardRepository(
    context: Context,
    private val assetPath: String = "cards/business_cards.json",
    private val json: Json = Json { ignoreUnknownKeys = true },
) : BusinessCardRepository {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    @Volatile private var cached: List<BusinessCardRecord>? = null

    override suspend fun loadAll(): List<BusinessCardRecord> {
        cached?.let { return it }
        return mutex.withLock {
            cached?.let { return@withLock it }
            withContext(Dispatchers.IO) {
                appContext.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { reader ->
                    val root = json.parseToJsonElement(reader.readText()) as? JsonArray
                        ?: error("Business card asset must be a JSON array")
                    val cards = root.mapIndexed { index, element ->
                        val value = element as? JsonObject ?: error("Card at $index must be an object")
                        value.toBusinessCard(index)
                    }
                    require(cards.map { it.id }.distinct().size == cards.size) { "Duplicate business card id" }
                    cards
                }
            }.also { cached = it }
        }
    }

    override suspend fun getById(cardId: String): BusinessCardRecord? =
        loadAll().firstOrNull { it.id == cardId.trim() }

    private fun JsonObject.toBusinessCard(index: Int): BusinessCardRecord {
        fun text(key: String): String = (get(key) as? JsonPrimitive)?.content?.trim().orEmpty()
        fun required(key: String): String = text(key).takeIf { it.isNotBlank() }
            ?: error("Card at $index is missing $key")
        val tags = (get("tags") as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty)
        }.orEmpty()
        return BusinessCardRecord(
            id = required("id"), name = required("name"), nameEn = text("name_en"),
            company = text("company"), title = text("title"), department = text("department"),
            industry = text("industry"), location = text("location"), phone = text("phone"),
            mobile = text("mobile"), email = text("email"), address = text("address"),
            website = text("website"), memo = text("memo"), tags = tags, updatedAt = text("updated_at"),
        )
    }
}
