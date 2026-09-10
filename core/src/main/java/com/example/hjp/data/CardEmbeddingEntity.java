package com.example.hjp.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;

@Entity(
        tableName = "card_embeddings",
        primaryKeys = {"cardId", "modelName"}
)
public class CardEmbeddingEntity {
    @NonNull
    public String cardId;
    @NonNull
    public String modelName;
    public byte[] vector;
    public int dimensions;
    public String sourceHash;
    public long updatedAtMillis;

    public CardEmbeddingEntity(
            @NonNull String cardId,
            @NonNull String modelName,
            byte[] vector,
            int dimensions,
            String sourceHash,
            long updatedAtMillis
    ) {
        this.cardId = cardId;
        this.modelName = modelName;
        this.vector = vector == null ? new byte[0] : vector;
        this.dimensions = dimensions;
        this.sourceHash = sourceHash == null ? "" : sourceHash;
        this.updatedAtMillis = updatedAtMillis;
    }
}
