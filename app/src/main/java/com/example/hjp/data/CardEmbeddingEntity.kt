package com.example.hjp.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.hjp.tool.contact.StoredCardEmbedding

@Entity(
    tableName = "card_embeddings",
    primaryKeys = ["card_id", "model_name"],
    foreignKeys = [
        ForeignKey(
            entity = BusinessCardEntity::class,
            parentColumns = ["id"],
            childColumns = ["card_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("card_id"), Index("model_name")],
)
data class CardEmbeddingEntity(
    @ColumnInfo(name = "card_id") val cardId: String,
    @ColumnInfo(name = "model_name") val modelName: String,
    val dimension: Int,
    @ColumnInfo(name = "vector_blob", typeAffinity = ColumnInfo.BLOB)
    val vectorBlob: ByteArray,
    @ColumnInfo(name = "source_text_hash") val sourceTextHash: String,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at_millis") val updatedAtMillis: Long,
)

fun CardEmbeddingEntity.toStoredEmbedding() = StoredCardEmbedding(
    cardId = cardId,
    modelName = modelName,
    dimension = dimension,
    vectorBlob = vectorBlob,
    sourceTextHash = sourceTextHash,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun StoredCardEmbedding.toEmbeddingEntity() = CardEmbeddingEntity(
    cardId = cardId,
    modelName = modelName,
    dimension = dimension,
    vectorBlob = vectorBlob,
    sourceTextHash = sourceTextHash,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)
