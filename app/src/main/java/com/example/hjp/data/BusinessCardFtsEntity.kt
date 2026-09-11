package com.example.hjp.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

/** Production FTS index. The string Room card ID is stored as an indexed payload. */
@Fts4(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    prefix = [2, 3, 4],
)
@Entity(tableName = "business_cards_fts")
data class BusinessCardFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Int,
    @ColumnInfo(name = "card_id")
    val cardId: String,
    @ColumnInfo(name = "searchable_text")
    val searchableText: String,
)
