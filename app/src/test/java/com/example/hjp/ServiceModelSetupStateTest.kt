package com.example.hjp

import com.example.hjp.models.ModelSetupState
import com.example.hjp.models.ServiceModelSetup
import org.junit.Assert.*
import org.junit.Test

class ServiceModelSetupStateTest {
    @Test fun `only verified ready phase unlocks model use`() {
        for (phase in listOf("checking", "missing", "downloading", "waiting", "verifying", "error", "broken")) {
            assertFalse(phase, ModelSetupState(phase).ready)
        }
        assertTrue(ModelSetupState("ready").ready)
        assertTrue(ModelSetupState("waiting").busy)
        assertFalse(ModelSetupState("error").busy)
    }
    @Test fun `deployment refusal never asks users for credentials`() {
        for (reason in listOf(401, 403)) {
            val message = ServiceModelSetup.downloadFailureMessage(reason)
            assertFalse(message.contains("토큰"))
            assertTrue(message.contains("배포 오류"))
        }
    }
}
