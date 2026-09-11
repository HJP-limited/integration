package com.hjp.searchlookup;

public final class CardEmbedding {
    public final String cardId, modelName, sourceTextHash;
    public final int dim;
    public final byte[] vectorBlob;
    public final long createdAt, updatedAt;
    public CardEmbedding(String cardId, String modelName, int dim, byte[] vectorBlob, String sourceTextHash, long createdAt, long updatedAt) {
        this.cardId=cardId; this.modelName=modelName; this.dim=dim; this.vectorBlob=vectorBlob; this.sourceTextHash=sourceTextHash; this.createdAt=createdAt; this.updatedAt=updatedAt;
    }
    public float[] vector() { return FloatVectorCodec.fromBlob(vectorBlob); }
}
