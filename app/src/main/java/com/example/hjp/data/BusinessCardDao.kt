package com.example.hjp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface BusinessCardDao {
    @Query("SELECT * FROM business_cards ORDER BY id")
    suspend fun loadAll(): List<BusinessCardEntity>

    @Query("SELECT * FROM business_cards WHERE id = :id")
    suspend fun getById(id: String): BusinessCardEntity?

    @Query("SELECT COUNT(*) FROM business_cards")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(cards: List<BusinessCardEntity>)

    @Update
    suspend fun update(card: BusinessCardEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFts(rows: List<BusinessCardFtsEntity>)

    @Query("DELETE FROM business_cards_fts")
    suspend fun clearFts()

    @Query(
        "SELECT card_id FROM business_cards_fts " +
            "WHERE business_cards_fts MATCH :matchQuery LIMIT :limit",
    )
    suspend fun searchFtsIds(matchQuery: String, limit: Int): List<String>

    @Query(
        "SELECT card_id FROM business_cards_fts " +
            "WHERE searchable_text LIKE :likeQuery LIMIT :limit",
    )
    suspend fun searchLikeIds(likeQuery: String, limit: Int): List<String>

    @Query("DELETE FROM business_cards WHERE id = :cardId")
    suspend fun deleteCard(cardId: String)

    @Transaction
    suspend fun rebuildFts() {
        clearFts()
        upsertFts(loadAll().mapIndexed { index, card -> card.toFtsEntity(index + 1) })
    }

    @Transaction
    suspend fun insertAllAndReindex(cards: List<BusinessCardEntity>) {
        insertAll(cards)
        rebuildFts()
    }

    @Transaction
    suspend fun updateAndReindex(card: BusinessCardEntity) {
        update(card)
        rebuildFts()
    }

    @Transaction
    suspend fun deleteAndReindex(cardId: String) {
        deleteCard(cardId)
        rebuildFts()
    }

    @Query("SELECT * FROM card_embeddings WHERE model_name = :modelName ORDER BY card_id")
    suspend fun loadEmbeddings(modelName: String): List<CardEmbeddingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmbeddings(embeddings: List<CardEmbeddingEntity>)
}

private fun BusinessCardEntity.toFtsEntity(rowId: Int): BusinessCardFtsEntity =
    BusinessCardFtsEntity(
        rowId = rowId,
        cardId = id,
        searchableText = buildString {
            append(
                listOf(
                    name,
                    nameEn,
                    company,
                    title,
                    department,
                    industry,
                    location,
                    // 주소·이메일이 빠져 있었다. "판교"는 location("경기도 성남시 분당구")이
                    // 아니라 address 에만 있어서, 판교 명함 9장이 키워드로 한 건도 안 잡히고
                    // 의미검색만 남아 엉뚱한 지역이 나왔다(실측). 이메일도 같은 이유로 넣는다.
                    address,
                    email,
                    memo,
                    tagsJson,
                    phone,
                    mobile,
                ).joinToString(" "),
            )
            append(' ')
            append(phone.filter(Char::isDigit))
            append(' ')
            append(mobile.filter(Char::isDigit))
        }.lowercase(),
    )
