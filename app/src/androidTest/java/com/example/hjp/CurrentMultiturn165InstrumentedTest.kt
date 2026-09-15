package com.example.hjp

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hjp.agent.contract.AgentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Full real-app replay. Historical Python route/JGA scores are not treated as app scores. */
@RunWith(AndroidJUnit4::class)
class CurrentMultiturn165InstrumentedTest {
    @Test fun replayAll165ScenariosAnd435Turns() = runBlocking {
        // No assume/skip and no model-free gateway. Stop before replay if any model is absent.
        RequiredModelsInstrumentedTest().everyRequiredModelIsPresentAndLoadableOnPhysicalArm64Device()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as HjpApplication
        val container = application.container
        val suite = instrumentation.context.assets.open("multiturn/current_165.json").bufferedReader().use {
            JSONObject(it.readText())
        }
        val scenarios = suite.getJSONArray("scenarios")
        assertEquals(165, scenarios.length())
        assertEquals(435, suite.getInt("turn_count"))
        val currentIds = container.contactRepository.loadAll().mapTo(hashSetOf()) { it.id }
        for (i in 0 until scenarios.length()) {
            val turns = scenarios.getJSONObject(i).getJSONArray("turns")
            for (j in 0 until turns.length()) {
                for (id in turns.getJSONObject(j).optJSONArray("gold").strings()) {
                    assertTrue("Evaluation card missing from app DB: $id", id in currentIds)
                }
            }
        }
        val reportDir = File(application.getExternalFilesDir(null) ?: application.filesDir, "evaluation")
        check(reportDir.isDirectory || reportDir.mkdirs())
        val report = File(reportDir, "multiturn-165-${System.currentTimeMillis()}.jsonl")
        val countersBefore = container.runtimeCounters.snapshot()
        var executed = 0
        var failed = 0
        var retrievalScored = 0
        var retrievalUnscored = 0
        report.bufferedWriter().use { writer ->
            writer.appendLine(JSONObject().put("type", "run_start")
                .put("suite_id", suite.getString("suite_id"))
                .put("generator_sha256", suite.getString("generator_sha256"))
                .put("expected_cards_sha256", suite.getString("cards_sha256"))
                .put("scoring", "answer checklist; observed search Hit@5/Recall@5/MRR; direct-read gold membership; legacy route/JGA/UI not scored")
                .put("scenarios", 165).put("turns", 435).toString())
            writer.flush()
            try {
                for (scenarioIndex in 0 until scenarios.length()) {
                    container.resetSession()
                    val scenario = scenarios.getJSONObject(scenarioIndex)
                    val turns = scenario.getJSONArray("turns")
                    for (turnIndex in 0 until turns.length()) {
                        val turn = turns.getJSONObject(turnIndex)
                        val question = turn.getString("q")
                        val events = mutableListOf<AgentEvent>()
                        val problems = mutableListOf<String>()
                        container.contactBackend.clear()
                        try {
                            withTimeout(180_000L) {
                                container.engine.runTurn(question).collect { event ->
                                    events += event
                                    // Never approve DB writes on behalf of the evaluator.
                                    if (event is AgentEvent.ConfirmationRequested) container.answerConfirmation(false)
                                }
                            }
                        } catch (timeout: TimeoutCancellationException) {
                            problems += "turn_timeout"
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            problems += "turn_exception:${error.javaClass.simpleName}"
                        }
                        val answer = events.filterIsInstance<AgentEvent.FinalMessage>().lastOrNull()?.text
                            ?: events.filterIsInstance<AgentEvent.Token>().joinToString("") { it.text }
                        if (answer.isBlank()) problems += "empty_answer"
                        events.filterIsInstance<AgentEvent.UserError>().forEach { problems += "user_error:${it.messageKo}" }
                        val must = turn.optJSONArray("must") ?: JSONArray()
                        for (k in 0 until must.length()) {
                            if (must.getJSONArray(k).strings().none { it.isNotEmpty() && answer.contains(it) }) {
                                problems += "missing_required_answer_group:$k"
                            }
                        }
                        turn.optJSONArray("must_not").strings().filter(answer::contains).forEach {
                            problems += "forbidden_answer:$it"
                        }
                        val displayed = container.contactBackend.lastHits.map { it.id }
                        val readIds = container.contactBackend.lastReadCardIds
                        val retrieval = MultiturnRetrievalEvaluation.evaluate(
                            turn.optJSONArray("gold").strings(), container.contactBackend.searchPerformed, displayed, readIds,
                        )
                        problems += retrieval.failures
                        val applied = container.contactBackend.lastAppliedConstraints
                        val slotEvaluation = MultiturnConstraintEvaluation.evaluate(turn.optJSONObject("slots"), applied)
                        problems += slotEvaluation.failures
                        if (retrieval.basis == "search" || retrieval.basis == "direct_read") retrievalScored++
                        if (retrieval.coverageGap != null) retrievalUnscored++
                        val memory = container.sessionSnapshot().conversationMemory
                        val currentTurnId = events.filterIsInstance<AgentEvent.TurnStarted>().lastOrNull()?.turnId
                        val action = currentTurnId?.let(memory::action)
                        executed++
                        if (problems.isNotEmpty()) failed++
                        writer.appendLine(JSONObject().put("type", "turn")
                            .put("scenario", scenarioIndex).put("turn", turnIndex)
                            .put("kind", scenario.getString("kind")).put("expected", turn)
                            .put("answer", answer).put("search_hit_card_ids", JSONArray(displayed))
                            .put("read_card_ids", JSONArray(readIds))
                            .put("applied_search_constraints", applied?.let { constraints ->
                                JSONObject().put("locations", JSONArray(constraints.locations))
                                    .put("titles", JSONArray(constraints.titles))
                                    .put("companies", JSONArray(constraints.companies))
                                    .put("departments", JSONArray(constraints.departments))
                                    .put("strict_filter_applied", constraints.strictFilterApplied)
                            } ?: JSONObject.NULL)
                            .put("unscored_slot_axes", JSONArray(slotEvaluation.unscoredAxes))
                            .put("retrieval", JSONObject().put("basis", retrieval.basis)
                                .put("hit_at_5", retrieval.hitAt5 ?: JSONObject.NULL)
                                .put("recall_at_5", retrieval.recallAt5 ?: JSONObject.NULL)
                                .put("reciprocal_rank", retrieval.reciprocalRank ?: JSONObject.NULL)
                                .put("coverage_gap", retrieval.coverageGap ?: JSONObject.NULL))
                            .put("selected_card_id", memory.selectedContact?.cardId ?: JSONObject.NULL)
                            .put("candidate_card_ids", JSONArray(memory.candidateContacts.map { it.cardId }))
                            .put("executed_tools", JSONArray(action?.executedTools.orEmpty()))
                            .put("diagnostics", JSONObject(container.diagnosticsSnapshot()))
                            .put("failures", JSONArray(problems)).toString())
                        writer.flush()
                        Log.i("HjpMultiturn165", "completed=$executed/435 failed=$failed")
                        // An inference-disabled engine cannot be accepted for the remaining turns.
                        check(container.modelReady) { "Required generation model became unavailable" }
                        val modelBacked = container.diagnosticsSnapshot()["embedding_model_backed"]
                        check(modelBacked != "false") { "Required embedding model became unavailable" }
                    }
                }
            } finally {
                writer.appendLine(JSONObject().put("type", "run_end").put("executed", executed)
                    .put("failed", failed).put("complete", executed == 435)
                    .put("retrieval_scored_turns", retrievalScored).put("retrieval_unscored_turns", retrievalUnscored)
                    .put("all_quality_axes_evaluated", false).toString())
                writer.flush()
                container.resetSession()
            }
        }
        val delta = container.runtimeCounters.snapshot() - countersBefore
        assertTrue("No actual model executed during replay", delta.actualModelExecuted)
        assertEquals("Some turns were not run; evidence: ${report.absolutePath}", 435, executed)
        assertEquals("Failed turns; evidence: ${report.absolutePath}", 0, failed)
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }
}
