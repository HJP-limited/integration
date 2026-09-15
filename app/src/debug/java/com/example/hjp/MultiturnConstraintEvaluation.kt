package com.example.hjp

import com.hjp.tool.contact.AppliedSearchConstraints
import org.json.JSONObject

/** Partial slot scoring. Name/focus/route semantics are deliberately not manufactured. */
internal object MultiturnConstraintEvaluation {
    data class Result(val failures: List<String>, val unscoredAxes: List<String>)

    fun evaluate(expected: JSONObject?, actual: AppliedSearchConstraints?): Result {
        if (expected == null) return Result(emptyList(), emptyList())
        if (actual == null) return Result(emptyList(), listOf("no_current_search_plan"))
        val failures = mutableListOf<String>()
        val fields = linkedMapOf("locations" to actual.locations, "titles" to actual.titles)
        if (expected.has("companies")) fields["companies"] = actual.companies
        if (expected.has("departments")) fields["departments"] = actual.departments
        fields.forEach { (key, values) ->
            val array = expected.optJSONArray(key)
            val wanted = if (array == null) emptySet() else (0 until array.length()).map { array.getString(it) }.toSet()
            if (wanted != values.toSet()) failures += "applied_slot_mismatch:$key"
        }
        return Result(failures, listOf("names", "focus", "legacy_route"))
    }
}
