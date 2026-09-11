package com.example.hjp.deviceeval

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter

/**
 * The file contract a device evaluation run has to keep, expressed as plain JVM I/O.
 *
 * ## What went wrong in v3
 *
 * v3 had one instrumentation class doing two jobs. A smoke run and the official run shared the class,
 * the run identifier, the output directory and the file names; the only difference between a 5-scenario
 * smoke and the 139-scenario official run was a number inside the result. Nothing stopped a second
 * execution from overwriting the first, nothing recorded that an official run had been attempted, and
 * the turn records were accumulated in memory and written once at the end — so a crash at scenario 130
 * produced no evidence at all, and a rerun produced a file indistinguishable from a clean one.
 *
 * ## What this type guarantees
 *
 *  - **Separation.** [RunMode] decides the directory, the file names and the run identifier. A smoke
 *    run cannot write into the official namespace because it never learns the path.
 *  - **One shot.** [OfficialRunGuard.claim] creates the invocation marker with `createNewFile`,
 *    which is atomic on every filesystem this runs on. Two processes racing produce one winner and
 *    one refusal — unlike `exists()` followed by a write, where both see "absent" and both proceed.
 *  - **Durability.** [DurableTurnStream] writes one JSON object per line, flushes, and calls
 *    `FileDescriptor.sync()` so the bytes survive a process kill. A run that dies at turn 300 leaves
 *    300 turns on disk.
 *  - **Atomic completion.** The stream writes `raw_turns.jsonl.partial`. It is promoted to
 *    `raw_turns.jsonl` by [DurableTurnStream.promote] only after [RunInventoryGate] agrees the run is
 *    complete, and only by `renameTo` within one directory, which is atomic. Anything else leaves the
 *    partial in place, which is the honest artefact of a run that did not finish.
 *  - **Immutability.** Nothing here ever opens an existing final artefact for writing.
 *
 * Deliberately free of Android imports so the host test suite can exercise every rule above against
 * a temporary directory, and so the rules cannot be one thing on the host and another on the device.
 */
object DeviceRunEvidence {

    const val SCHEMA = "ryeong_v4_device_run/v1"

    /** Which run this is. Everything else follows from it. */
    enum class RunMode(
        val runId: String,
        val directoryName: String,
        val countsAsOfficialInvocation: Boolean,
        val scenarioLimitAllowed: Boolean,
    ) {
        /**
         * A short shakeout. It may cap the scenario count, it never claims to be a measurement, and
         * it does not consume the official invocation.
         */
        SMOKE(
            runId = "RYEONG_PRODUCTION_COMPATIBILITY_V4_SMOKE_DEVICE",
            directoryName = "ryeong_device_eval_v4_smoke",
            countsAsOfficialInvocation = false,
            scenarioLimitAllowed = true,
        ),

        /**
         * `RUN_D4`. Exactly one invocation, the whole frozen set, no cap of any kind.
         */
        OFFICIAL(
            runId = "RYEONG_PRODUCTION_COMPATIBILITY_V4_RUN_D4_DEVICE_ACTUAL_MODEL_BASELINE",
            directoryName = "ryeong_device_eval_v4_official",
            countsAsOfficialInvocation = true,
            scenarioLimitAllowed = false,
        ),
    }

    const val PARTIAL_SUFFIX = ".partial"
    const val RAW_TURNS = "raw_turns.jsonl"
    const val RESULT = "device_result.json"
    const val STATUS = "device_run_status.json"
    const val INVOCATION_MARKER = "invocation.marker"
    const val REFUSAL_LOG = "refused_invocations.log"
    const val PROGRESS = "progress_status.json"

    /** Artefacts whose presence proves a run of this mode already happened. */
    val FINAL_ARTIFACTS = listOf(RAW_TURNS, RESULT, STATUS)

    /** Everything a run of one mode owns. Built from the mode, never passed in piecemeal. */
    class Paths(val root: File, val mode: RunMode) {
        val directory: File = File(root, mode.directoryName)
        val rawTurns: File get() = File(directory, RAW_TURNS)
        val rawTurnsPartial: File get() = File(directory, RAW_TURNS + PARTIAL_SUFFIX)
        val result: File get() = File(directory, RESULT)
        val status: File get() = File(directory, STATUS)
        val invocationMarker: File get() = File(directory, INVOCATION_MARKER)
        val refusalLog: File get() = File(directory, REFUSAL_LOG)
        val progress: File get() = File(directory, PROGRESS)

        fun prepare(): Paths = apply { directory.mkdirs() }

        fun existingFinalArtifacts(): List<String> =
            FINAL_ARTIFACTS.filter { File(directory, it).isFile }

        fun stalePartials(): List<String> =
            directory.listFiles { f -> f.name.endsWith(PARTIAL_SUFFIX) }
                .orEmpty().map { it.name }.sorted()
    }

    /** Why an official run was refused, or [Claimed] when it was not. */
    sealed interface ClaimResult {
        data class Claimed(val markerContents: String) : ClaimResult
        data class Refused(val code: String, val detail: String) : ClaimResult
    }

    /**
     * Takes the single official invocation, or explains why it cannot.
     *
     * The order matters: existing evidence is checked before the marker is attempted, so a directory
     * holding a finished run reports *that* rather than a marker collision. A refusal never touches a
     * final artefact; it appends to [Paths.refusalLog], which is the only file a refused run writes.
     */
    object OfficialRunGuard {

        fun claim(paths: Paths, markerContents: String, nowIso: String): ClaimResult {
            paths.prepare()

            val finals = paths.existingFinalArtifacts()
            if (finals.isNotEmpty()) {
                return refuse(paths, "OFFICIAL_RESULT_ALREADY_PRESENT", "found $finals", nowIso)
            }
            val partials = paths.stalePartials()
            if (partials.isNotEmpty()) {
                return refuse(
                    paths, "STALE_PARTIAL_PRESENT",
                    "found $partials — a previous run did not finish; preserve it and use a new " +
                        "evaluation version rather than overwriting it",
                    nowIso,
                )
            }
            // Atomic. Two racing processes cannot both succeed here, which an exists()-then-write
            // sequence cannot promise.
            val created = try {
                paths.invocationMarker.createNewFile()
            } catch (error: IOException) {
                return refuse(paths, "MARKER_WRITE_FAILED", error.message.orEmpty(), nowIso)
            }
            if (!created) {
                return refuse(
                    paths, "OFFICIAL_ALREADY_INVOKED",
                    "invocation marker already exists at ${paths.invocationMarker.name}", nowIso,
                )
            }
            writeDurably(paths.invocationMarker, markerContents)
            return ClaimResult.Claimed(markerContents)
        }

        /** How many official invocations this directory records. Zero or one; never inferred. */
        fun invocationCount(paths: Paths): Int = if (paths.invocationMarker.isFile) 1 else 0

        private fun refuse(paths: Paths, code: String, detail: String, nowIso: String): ClaimResult {
            paths.refusalLog.appendText("$nowIso\t$code\t$detail\n", Charsets.UTF_8)
            return ClaimResult.Refused(code, detail)
        }
    }

    /**
     * Streams turn records to a `.partial`, one line each, durably.
     *
     * Not buffered until the end: the point is that a run that dies mid-way still leaves the turns it
     * completed. `sync()` after each line is the expensive, correct choice — 386 syncs across a run
     * measured in minutes is not the bottleneck, and the alternative is evidence that exists only if
     * nothing goes wrong.
     */
    class DurableTurnStream(private val paths: Paths) : AutoCloseable {
        private val out = FileOutputStream(paths.rawTurnsPartial, /* append = */ true)
        private val writer = OutputStreamWriter(out, Charsets.UTF_8)
        var written: Int = 0
            private set

        init {
            require(!paths.rawTurns.exists()) {
                "refusing to stream: ${paths.rawTurns.name} already exists"
            }
        }

        /** [line] must be one complete JSON object with no embedded newline. */
        fun append(line: String) {
            require(!line.contains('\n')) { "a turn record must be exactly one line" }
            writer.write(line)
            writer.write("\n")
            writer.flush()
            out.fd.sync()
            written += 1
        }

        override fun close() {
            writer.flush()
            out.fd.sync()
            writer.close()
        }

        /**
         * Promotes the partial to its final name, but only when [gate] says the run is complete.
         *
         * Returns the gate's findings. A non-empty list means nothing was promoted and the partial is
         * still on disk, which is exactly what a reader should find after an incomplete run.
         */
        fun promote(gate: RunInventoryGate.Result): List<String> {
            if (gate.failures.isNotEmpty()) return gate.failures
            check(!paths.rawTurns.exists()) { "${paths.rawTurns.name} appeared during the run" }
            close()
            if (!paths.rawTurnsPartial.renameTo(paths.rawTurns)) {
                return listOf("PROMOTION_FAILED: could not rename ${paths.rawTurnsPartial.name}")
            }
            return emptyList()
        }
    }

    /**
     * The inventory a completed run must show before anything is promoted.
     *
     * Every number is compared exactly. "About 386 turns" is not a thing: a run that produced 385 has
     * lost one, and which one is unknowable after the fact.
     */
    object RunInventoryGate {

        data class Expected(
            val scenarios: Int,
            val turns: Int,
            val kinds: Int,
            val depthCounts: Map<Int, Int>,
        )

        data class Observed(
            val scenarios: Int,
            val turns: Int,
            val kinds: Int,
            val depthCounts: Map<Int, Int>,
            val duplicateTurnKeys: List<String>,
            val missingTurnKeys: List<String>,
            val crashed: Boolean,
        )

        data class Result(val failures: List<String>) {
            val complete: Boolean get() = failures.isEmpty()
        }

        fun check(expected: Expected, observed: Observed, mode: RunMode): Result {
            val failures = mutableListOf<String>()
            fun require(condition: Boolean, message: String) {
                if (!condition) failures += message
            }
            require(!observed.crashed, "RUN_CRASHED: the run did not reach the end")
            require(observed.duplicateTurnKeys.isEmpty(),
                "DUPLICATE_TURNS: ${observed.duplicateTurnKeys}")
            require(observed.missingTurnKeys.isEmpty(),
                "MISSING_TURNS: ${observed.missingTurnKeys}")
            if (mode == RunMode.OFFICIAL) {
                require(observed.scenarios == expected.scenarios,
                    "SCENARIO_COUNT: ${observed.scenarios} != ${expected.scenarios}")
                require(observed.turns == expected.turns,
                    "TURN_COUNT: ${observed.turns} != ${expected.turns}")
                require(observed.kinds == expected.kinds,
                    "KIND_COUNT: ${observed.kinds} != ${expected.kinds}")
                require(observed.depthCounts == expected.depthCounts,
                    "DEPTH_INVENTORY: ${observed.depthCounts} != ${expected.depthCounts}")
            }
            return Result(failures)
        }
    }

    /**
     * Rejects a scenario cap on a run that may not have one.
     *
     * An official run that quietly honoured `-e ryeongScenarioLimit 5` would produce a 5-scenario
     * result carrying the official run identifier, and no later reader could tell. Failing before the
     * first turn is the only place this can be caught cheaply.
     */
    fun requireScenarioLimitAllowed(mode: RunMode, scenarioLimit: Int) {
        if (scenarioLimit > 0 && !mode.scenarioLimitAllowed) {
            error(
                "$mode refuses a scenario limit (got $scenarioLimit). A capped run is a smoke run; " +
                    "execute ${RunMode.SMOKE.runId} instead.",
            )
        }
    }

    /** Writes [contents] and forces it to storage, so a later crash cannot lose it. */
    fun writeDurably(target: File, contents: String) {
        FileOutputStream(target).use { stream ->
            stream.write(contents.toByteArray(Charsets.UTF_8))
            stream.flush()
            stream.fd.sync()
        }
    }

    /**
     * Replaces [target] atomically: write a sibling temporary file, sync it, rename over the target.
     *
     * Used for progress status, which is rewritten many times during a run. A partial write of the
     * status file would be a corrupt file where an old-but-valid one would have been more useful.
     */
    fun replaceAtomically(target: File, contents: String) {
        val temporary = File(target.parentFile, target.name + PARTIAL_SUFFIX)
        writeDurably(temporary, contents)
        if (!temporary.renameTo(target)) {
            // Rename within one directory is atomic where it is supported at all. If it is not
            // supported here, that is a fact about the storage the run must not paper over.
            temporary.delete()
            error(
                "atomic replace is not available for ${target.absolutePath}; write the run's " +
                    "evidence to app-private storage instead of this location",
            )
        }
    }
}
