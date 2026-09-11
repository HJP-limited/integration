package com.hjp.searchlookup;

public final class OnDeviceEmbeddingEngine implements EmbeddingEngine {
    public static final String MODEL_NAME = "google/embeddinggemma-300m";
    public static final int DEFAULT_DIMENSION = 768;
    private final EmbeddingEngine modelEngine;
    private final EmbeddingEngine fallback;
    private final boolean fallbackEnabled;
    private volatile boolean fallbackUsed;
    private volatile String fallbackReason = "";

    public OnDeviceEmbeddingEngine(EmbeddingEngine modelEngine, EmbeddingEngine fallback) {
        this.modelEngine = modelEngine;
        this.fallback = fallback == null ? new LocalEmbeddingEngine() : fallback;
        this.fallbackEnabled = true;
        if (modelEngine == null || !modelEngine.isModelBacked()) {
            this.fallbackUsed = true;
            this.fallbackReason = modelEngine == null
                    ? "MODEL_ENGINE_MISSING"
                    : modelEngine.diagnosticStatus();
        }
    }

    public static OnDeviceEmbeddingEngine production() {
        return new OnDeviceEmbeddingEngine(null, new LocalEmbeddingEngine());
    }
    public static OnDeviceEmbeddingEngine production(EmbeddingEngine modelEngine) {
        return new OnDeviceEmbeddingEngine(modelEngine, new LocalEmbeddingEngine());
    }

    @Override public float[] embed(String input) {
        return embedQuery(input);
    }

    @Override public float[] embedQuery(String input) {
        return run(input, true);
    }

    @Override public float[] embedDocument(String input) {
        return run(input, false);
    }

    private float[] run(String input, boolean query) {
        if (!isAvailable()) {
            return fallback(input, query, unavailableMessage());
        }
        try {
            if (modelEngine == null) {
                throw new UnsupportedOperationException(
                        "ONNX runtime delegate is not configured for this platform");
            }
            float[] vector = query
                    ? modelEngine.embedQuery(input)
                    : modelEngine.embedDocument(input);
            validate(vector);
            return vector;
        } catch (Throwable error) {
            return fallback(input, query, "INFERENCE_FAILED: " + describe(error));
        }
    }

    private float[] fallback(String input, boolean query, String reason) {
        if (!fallbackEnabled) throw new IllegalStateException(reason);
        fallbackUsed = true;
        fallbackReason = reason;
        return query ? fallback.embedQuery(input) : fallback.embedDocument(input);
    }

    private void validate(float[] vector) {
        if (vector == null || vector.length != DEFAULT_DIMENSION) {
            throw new IllegalStateException(
                    "DIMENSION_MISMATCH expected=" + DEFAULT_DIMENSION
                            + " actual=" + (vector == null ? 0 : vector.length));
        }
        double norm = 0.0;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalStateException("NON_FINITE_VECTOR");
            }
            norm += value * value;
        }
        if (norm == 0.0) throw new IllegalStateException("ZERO_VECTOR");
    }

    private String unavailableMessage() {
        return modelEngine == null ? "MODEL_ENGINE_MISSING" : modelEngine.diagnosticStatus();
    }

    private String describe(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    @Override public String name() {
        if (fallbackUsed || modelEngine == null) {
            return MODEL_NAME + " (keyword-fallback:" + fallback.name() + ")";
        }
        // The Android model engine includes the model/tokenizer hashes in its
        // identity. Preserve that identity so cached card vectors cannot be
        // reused after either artifact changes.
        return modelEngine.name();
    }
    @Override public boolean isModelBacked() { return isAvailable() && !fallbackUsed; }
    public boolean isFallbackUsed() { return fallbackUsed || !isAvailable(); }
    public String fallbackReason() {
        return fallbackReason.isEmpty() && !isAvailable() ? unavailableMessage() : fallbackReason;
    }
    public int outputDimension() { return DEFAULT_DIMENSION; }
    public boolean isAvailable() {
        if (fallbackUsed) return false;
        return modelEngine != null && modelEngine.isModelBacked();
    }

    @Override public String diagnosticStatus() {
        return isModelBacked() ? "ready" : fallbackReason();
    }
}
