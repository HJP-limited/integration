package com.example.hjp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Dataset integrity only. This is not a model-free substitute for the 435-turn device replay. */
class CurrentMultiturnSuiteInventoryTest {
    @Test fun `current generated suite contains every scenario and turn including generation only cases`() {
        val suite = javaClass.classLoader!!.getResourceAsStream("multiturn/current_165.json")!!.bufferedReader().use {
            JSONObject(it.readText())
        }
        assertEquals("ryeong-dynamic-v1-165", suite.getString("suite_id"))
        assertEquals(42, suite.getInt("seed"))
        val scenarios = suite.getJSONArray("scenarios")
        assertEquals(165, scenarios.length())
        var turnCount = 0
        var generationOnly = 0
        val kinds = mutableSetOf<String>()
        for (i in 0 until scenarios.length()) {
            val scenario = scenarios.getJSONObject(i)
            kinds += scenario.getString("kind")
            if (scenario.getBoolean("generate_only")) generationOnly++
            val turns = scenario.getJSONArray("turns")
            turnCount += turns.length()
            for (j in 0 until turns.length()) {
                val turn = turns.getJSONObject(j)
                assertTrue(turn.getString("q").isNotBlank())
                assertTrue(turn.has("route") && turn.has("slots") && turn.has("gold"))
                assertTrue(turn.has("must") && turn.has("must_not") && turn.has("no_cards"))
            }
        }
        assertEquals(435, turnCount)
        assertEquals(27, kinds.size)
        assertEquals(6, generationOnly)
    }
}
