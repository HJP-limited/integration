package com.example.hjp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.hjp.tool.contact.SearchIndexText
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

    @Query("SELECT count(*) FROM business_cards_fts")
    suspend fun countFts(): Int

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
        // 색인 문자열 규칙은 [SearchIndexText] 한 곳에만 있다 — 노트북 러너도 같은 것을 부른다.
        // 예전에는 앱과 데스크톱이 각자 같은 목록을 손으로 들고 있었고, 실제로 한쪽에만
        // 주소·이메일이 빠져 있던 적이 있다.
        searchableText = SearchIndexText.build(
            name = name,
            nameEn = nameEn,
            company = company,
            title = title,
            department = department,
            industry = industry,
            location = location,
            address = address,
            email = email,
            website = website,
            memo = memo,
            tags = tagsJson,
            phone = phone,
            mobile = mobile,
        ),
    )
