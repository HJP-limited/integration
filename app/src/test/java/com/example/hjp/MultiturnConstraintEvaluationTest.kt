package com.example.hjp

import com.hjp.tool.contact.AppliedSearchConstraints
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MultiturnConstraintEvaluationTest {
    private val actual = AppliedSearchConstraints(listOf("서울"), listOf("개발자"),
        listOf("한빛테크"), listOf("연구팀"), true)

    @Test fun `wrong location fails even if the right card happened to be returned`() {
        val result = MultiturnConstraintEvaluation.evaluate(JSONObject("""{"locations":["부산"],"titles":["개발자"]}"""), actual)
        assertEquals(listOf("applied_slot_mismatch:locations"), result.failures)
    }
    @Test fun `observed slots are compared as sets and unimplemented axes remain explicit`() {
        val result = MultiturnConstraintEvaluation.evaluate(JSONObject("""{"locations":["서울","서울"],"titles":["개발자"],"companies":["한빛테크"],"departments":["연구팀"]}"""), actual)
        assertTrue(result.failures.isEmpty())
        assertEquals(listOf("names", "focus", "legacy_route"), result.unscoredAxes)
    }
    @Test fun `an unexpected constraint is also a mismatch`() {
        assertEquals(2, MultiturnConstraintEvaluation.evaluate(JSONObject("{}"), actual).failures.size)
    }
    @Test fun `missing observation is not a fabricated empty successful plan`() {
        val result = MultiturnConstraintEvaluation.evaluate(JSONObject("{}"), null)
        assertTrue(result.failures.isEmpty())
        assertEquals(listOf("no_current_search_plan"), result.unscoredAxes)
    }
}
