package com.example.hjp.desktop

import com.hjp.searchlookup.EmbeddingEngine
import com.hjp.searchlookup.OnDeviceEmbeddingEngine
import com.hjp.tool.contact.BusinessCardRepository
import com.hjp.tool.contact.RyeongContactSearchBackend

/** Production desktop composition: missing models and failed inference must stop the operation. */
internal fun requiredDesktopSearchBackend(
    repository: BusinessCardRepository,
    embeddingEngine: EmbeddingEngine,
) = RyeongContactSearchBackend(
    repository,
    embeddingEngineFactory = { OnDeviceEmbeddingEngine.required(embeddingEngine) },
    requireModelBacked = true,
)
