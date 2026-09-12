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

/**
 * 번들된 명함을 읽어 첫 실행 때 채운다.
 *
 * 파일 이름과 키 모양을 **둘 다** 받아 준다. Agent_0910 은 `cards/business_cards.json` 에
 * snake_case 로 네 장을 넣어 두었고, 우리 제품 데이터는 `cards/cards_seed.json` 에
 * camelCase 로 1000장이 들어 있다. 그걸 맞추지 않아 앱이 **시작하자마자 죽었다**
 * (FileNotFoundException: cards/business_cards.json — 실기기에서 잡았다).
 */
class AssetBusinessCardRepository(
    context: Context,
    private val assetPaths: List<String> = listOf("cards/cards_seed.json", "cards/business_cards.json"),
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
                val assetPath = assetPaths.firstOrNull { candidate ->
                    runCatching { appContext.assets.open(candidate).close() }.isSuccess
                } ?: error("No bundled card asset found: " + assetPaths.joinToString())
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
        // 태그는 배열로도, 쉼표로 이은 한 줄로도 들어온다.
        val tags = (get("tags") as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty)
        } ?: text("tags").split(',').map(String::trim).filter(String::isNotEmpty)
        // 키는 두 표기를 다 본다 — 두 시드가 서로 다른 표기를 쓴다.
        fun either(snake: String, camel: String): String = text(snake).ifBlank { text(camel) }
        return BusinessCardRecord(
            id = required("id"), name = required("name"),
            nameEn = either("name_en", "nameEn"),
            company = text("company"), title = text("title"), department = text("department"),
            industry = text("industry"), location = text("location"), phone = text("phone"),
            mobile = text("mobile"), email = text("email"), address = text("address"),
            website = text("website"), memo = text("memo"),
            tags = tags,
            updatedAt = either("updated_at", "updatedAt"),
        )
    }
}
