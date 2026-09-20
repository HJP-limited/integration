package com.example.hjp

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiStartupPreparationInstrumentedTest {
    @Test
    fun startupPreparationLoadsNativeSessionAndEmbeddingModel() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<HjpApplication>()
        val container = application.container
        assertTrue("verified model is required for startup", container.modelReady)

        container.prepareForUse()

        val counters = container.runtimeCounters.snapshot()
        assertTrue("startup did not finish", container.isPreparedForUse)
        assertTrue(
            "native model session was not opened: $counters",
            counters.modelSessionOpenSuccesses >= 1L,
        )
        assertTrue(
            "EmbeddingGemma was not initialized: ${container.diagnosticsSnapshot()}",
            container.diagnosticsSnapshot()["embedding_model_backed"] == "true",
        )
    }
}
