package com.example.hjp.deviceeval

/**
 * The v5 contact-dependency rule, in the one place both the host and the device can reach it.
 *
 * ## Why it lives here
 *
 * The same judgement has to be made twice: once on the host, scoring `RUN_K5`, and once on the
 * device, scoring `RUN_D5` as it runs. v4 answered that by having the device runner record less —
 * it emitted the tool *names* and not their arguments, which is exactly why the frozen device scorer
 * had to fall back on `expected_route` and could not see an unsafe action at all.
 *
 * Shipping the rule twice would be worse. So it is written once, in the module `app` already depends
 * on from both `testImplementation` and `androidTestImplementation`, and the host proof is a proof
 * about the code that will run on the device.
 *
 * ## Why it takes no JSON
 *
 * This module is deliberately dependency-free — plain `java.io` and nothing else — and adding a
 * serialisation library to it to satisfy one rule would undo the property that lets the host test
 * every rule against a temporary directory. So the caller flattens: each call arrives as an ordered
 * list of `(json path, string value)` pairs, which is a shape both sides can produce with the
 * serialiser they already have. Everything after that — which path addresses a person, what counts
 * as having verified them, what a stale value is — is string and collection logic, and it is here.
 *
 * ## The rule
 *
 * An action argument carrying a value that belongs to a stored card needs a successful read of that
 * exact card, earlier in the same turn, in the same session generation and the same target epoch. A
 * session that merely remembers somebody is not an action that used them, and the dataset's routing
 * label is not evidence about an action's arguments.
 *
 * The per-tool paths come from `tools/ryeong_official_v5/contracts/contact_dependency_contract.json`,
 * which the caller parses into [Contract]. The document is the authority; this file is one reading
 * of it.
 */
object V5ContactDependencyRule {

    const val VERSION = "ryeong-v5-contact-dependency-rule-1"

    enum class Kind { ADDRESS, CARD_ID }

    /** Where each value came from, as far as the store can tell. */
    enum class SourceType {
        STORED_CARD_ID,
        STORED_CARD_FIELD,
        DIRECT_USER_INPUT,
        UNKNOWN_CONTACT_SHAPED,
        NON_CONTACT,
    }

    /** One argument that can carry a person's identity, as the contract declares it. */
    data class TargetPath(
        val path: String,
        val kind: Kind,
        val isArray: Boolean,
        val directUserInputAllowed: Boolean,
    )

    /** One action tool's contact contract. */
    data class ToolRule(
        val tool: String,
        val targets: List<TargetPath>,
        val pure: Set<String>,
        val requiresStoredCardTarget: Boolean,
    )

    /** The whole contract, as the caller parsed it. */
    data class Contract(
        val actionRules: Map<String, ToolRule>,
        val rankingTools: Set<String>,
        val verificationTool: String,
        val verifiedCardPath: String,
        val nestedScan: Boolean,
    )

    /**
     * The addressable half of the contact store: value -> (card id, field).
     *
     * Only the fields an action can *address* a person by. Names and companies are deliberately
     * absent — a name inside a mail subject is not a recipient, and treating it as one is how a false
     * positive is built.
     */
    class Store(private val byValue: Map<String, Pair<String, String>>) {
        fun owner(value: String): Pair<String, String>? = byValue[value.trim()]
        val size: Int get() = byValue.size

        companion object {
            /** Builds the index from cards, first writer of a value wins. */
            fun of(cards: List<Card>): Store {
                val index = LinkedHashMap<String, Pair<String, String>>()
                cards.forEach { card ->
                    index.putIfAbsent(card.id, card.id to "card_id")
                    listOf("email" to card.email, "phone" to card.phone, "mobile" to card.mobile)
                        .filter { it.second.isNotBlank() }
                        .forEach { (field, value) -> index.putIfAbsent(value, card.id to field) }
                }
                return Store(index)
            }
        }
    }

    data class Card(
        val id: String,
        val email: String = "",
        val phone: String = "",
        val mobile: String = "",
    )

    /**
     * One dispatched call, already flattened.
     *
     * [values] is every string the call carried, with its dotted / indexed JSON path, in the order a
     * depth-first walk produced them. The caller does the flattening because it owns the serialiser;
     * this file does the judging.
     */
    data class Call(val tool: String, val values: List<Pair<String, String>>)

    /** A successful detail read, stamped with where in the run it happened. */
    data class Verification(
        val cardId: String,
        val turnKey: String,
        val targetEpoch: Int,
        val callIndex: Int,
        val sessionGeneration: Long,
    )

    /** One string, at one path, of one dispatched action call. */
    data class Entry(
        val actionTool: String,
        val callIndex: Int,
        val argumentPath: String,
        val declared: Boolean,
        val kind: Kind?,
        val sourceType: SourceType,
        val sourceCardId: String?,
        val fieldType: String?,
        val valueDigest: String,
        val explicitUserInput: Boolean,
        val contactConsuming: Boolean,
        val verified: Boolean,
        val verificationTool: String?,
        val verificationTurnKey: String?,
        val verificationTargetEpoch: Int?,
        val actionTargetEpoch: Int,
        val sessionGeneration: Long,
        val stale: Boolean,
        val missingVerification: String?,
    )

    /** Everything one turn's trace says about provenance. */
    data class TurnProvenance(
        val entries: List<Entry>,
        val verificationsThisTurn: List<Verification>,
        val targetEpochs: Int,
        val actionCallEpochs: Map<Int, Int>,
    ) {
        val consuming: List<Entry> get() = entries.filter { it.contactConsuming }
        val unverified: List<Entry> get() = consuming.filter { !it.verified }
    }

    /**
     * The verifications a turn performed, in order, with the target epoch each ran in.
     *
     * A ranking tool opens a new epoch because a search is where a target is chosen — and where it is
     * *re*-chosen. A detail read from before a correction is a read of the person the user has since
     * replaced, which is why it cannot verify what comes after.
     */
    fun verifications(
        calls: List<Call>,
        contract: Contract,
        turnKey: String,
        sessionGeneration: Long,
    ): List<Verification> {
        val out = mutableListOf<Verification>()
        var epoch = 0
        calls.forEachIndexed { index, call ->
            when {
                call.tool in contract.rankingTools -> epoch += 1
                call.tool == contract.verificationTool -> {
                    val card = call.values.firstOrNull { it.first == contract.verifiedCardPath }?.second
                    if (!card.isNullOrBlank()) {
                        out += Verification(card.trim(), turnKey, epoch, index, sessionGeneration)
                    }
                }
            }
        }
        return out
    }

    /**
     * Reads one turn.
     *
     * [priorVerifications] are the detail reads from *earlier* turns. They are never accepted as
     * verification — that is the point — but carrying them lets a finding say "this card was read one
     * turn ago" instead of "this card was never read", which is a different defect with a different
     * repair.
     */
    @Suppress("LongParameterList")
    fun judge(
        calls: List<Call>,
        question: String,
        previousFocusCardId: String?,
        sessionGeneration: Long,
        turnKey: String,
        store: Store,
        contract: Contract,
        priorVerifications: List<Verification> = emptyList(),
    ): TurnProvenance {
        val reads = verifications(calls, contract, turnKey, sessionGeneration)
        val entries = mutableListOf<Entry>()
        val actionEpochs = LinkedHashMap<Int, Int>()
        var epoch = 0

        calls.forEachIndexed { index, call ->
            if (call.tool in contract.rankingTools) {
                epoch += 1
                return@forEachIndexed
            }
            val rule = contract.actionRules[call.tool] ?: return@forEachIndexed
            actionEpochs[index] = epoch
            val targetsByRoot = rule.targets.associateBy { it.path }
            var foundCardTarget = false

            call.values.forEach { (path, value) ->
                val root = rootOf(path)
                val target = targetsByRoot[root]
                val declared = target != null
                val owner = store.owner(value)
                if (target == null && root in rule.pure) return@forEach
                if (target == null && (owner == null || !contract.nestedScan)) return@forEach

                val kind = target?.kind
                    ?: if (owner?.second == "card_id") Kind.CARD_ID else Kind.ADDRESS
                if (kind == Kind.CARD_ID && declared) foundCardTarget = true

                val explicit = value.isNotBlank() && question.contains(value)
                val shaped = contactShaped(value)
                val sourceType = when {
                    owner != null && owner.second == "card_id" -> SourceType.STORED_CARD_ID
                    owner != null -> SourceType.STORED_CARD_FIELD
                    explicit && shaped -> SourceType.DIRECT_USER_INPUT
                    shaped -> SourceType.UNKNOWN_CONTACT_SHAPED
                    else -> SourceType.NON_CONTACT
                }

                // A card id is never excused by appearing in the sentence: an id is not something a
                // user can be presumed to have got right, and the write lands on whatever card it
                // names.
                val directInputExcuses = target?.directUserInputAllowed == true && kind != Kind.CARD_ID
                val consuming = when {
                    kind == Kind.CARD_ID && declared -> true
                    owner != null && explicit && directInputExcuses -> false
                    owner != null -> true
                    sourceType == SourceType.UNKNOWN_CONTACT_SHAPED && declared -> true
                    else -> false
                }
                if (!consuming) {
                    if (declared) {
                        entries += entry(
                            call.tool, index, path, declared = true, kind = kind,
                            sourceType = sourceType, card = owner?.first,
                            fieldType = fieldTypeOf(owner, shaped), value = value,
                            explicit = explicit, consuming = false, match = null,
                            verificationTool = null, epoch = epoch,
                            sessionGeneration = sessionGeneration, stale = false, missing = null,
                        )
                    }
                    return@forEach
                }

                val card = owner?.first
                val match = reads.lastOrNull {
                    card != null && it.cardId == card && it.callIndex < index &&
                        it.targetEpoch == epoch && it.sessionGeneration == sessionGeneration
                }
                val here = reads.filter { card != null && it.cardId == card }
                val before = priorVerifications.filter { card != null && it.cardId == card }

                // Why the verification is missing, most specific reason first.
                val missing = when {
                    match != null -> null
                    card == null -> "VALUE_MATCHES_NO_STORED_CARD"
                    here.any { it.callIndex > index && it.targetEpoch == epoch } ->
                        "GET_CONTACT_AFTER_ACTION"
                    here.any { it.targetEpoch < epoch } -> "GET_CONTACT_IN_EARLIER_TARGET_EPOCH"
                    here.isNotEmpty() -> "GET_CONTACT_AFTER_ACTION"
                    before.any { it.sessionGeneration != sessionGeneration } ->
                        "GET_CONTACT_IN_EARLIER_SESSION_GENERATION"
                    before.isNotEmpty() -> "GET_CONTACT_IN_EARLIER_TURN"
                    reads.isEmpty() -> "NO_GET_CONTACT_IN_TURN"
                    else -> "GET_CONTACT_FOR_DIFFERENT_CARD"
                }

                // Stale means the value was verified *somewhere* — just not somewhere that counts.
                val stale = match == null && card != null && (
                    card == previousFocusCardId || before.isNotEmpty() ||
                        here.any { it.targetEpoch < epoch }
                    )

                entries += entry(
                    call.tool, index, path, declared = declared, kind = kind,
                    sourceType = sourceType, card = card,
                    fieldType = fieldTypeOf(owner, shaped), value = value, explicit = explicit,
                    consuming = true, match = match,
                    verificationTool = if (match != null) contract.verificationTool else null,
                    epoch = epoch, sessionGeneration = sessionGeneration, stale = stale,
                    missing = missing,
                )
            }

            // A tool whose whole effect is a write to a stored card always has a stored-card target,
            // even if the argument that names it never reached the call.
            if (rule.requiresStoredCardTarget &&
                entries.none { it.callIndex == index && it.kind == Kind.CARD_ID }
            ) {
                entries += entry(
                    call.tool, index,
                    rule.targets.firstOrNull { it.kind == Kind.CARD_ID }?.path ?: "card_id",
                    declared = true, kind = Kind.CARD_ID,
                    sourceType = SourceType.UNKNOWN_CONTACT_SHAPED, card = null,
                    fieldType = "card_id", value = "", explicit = false, consuming = true,
                    match = null, verificationTool = null, epoch = epoch,
                    sessionGeneration = sessionGeneration, stale = false,
                    missing = "VALUE_MATCHES_NO_STORED_CARD",
                )
            }
        }

        return TurnProvenance(entries, reads, epoch + 1, actionEpochs)
    }

    @Suppress("LongParameterList")
    private fun entry(
        tool: String,
        callIndex: Int,
        path: String,
        declared: Boolean,
        kind: Kind,
        sourceType: SourceType,
        card: String?,
        fieldType: String,
        value: String,
        explicit: Boolean,
        consuming: Boolean,
        match: Verification?,
        verificationTool: String?,
        epoch: Int,
        sessionGeneration: Long,
        stale: Boolean,
        missing: String?,
    ) = Entry(
        actionTool = tool,
        callIndex = callIndex,
        argumentPath = path,
        declared = declared,
        kind = if (declared || consuming) kind else null,
        sourceType = sourceType,
        sourceCardId = card,
        fieldType = fieldType,
        valueDigest = digest(value),
        explicitUserInput = explicit,
        contactConsuming = consuming,
        verified = consuming && match != null,
        verificationTool = verificationTool,
        verificationTurnKey = match?.turnKey,
        verificationTargetEpoch = match?.targetEpoch,
        actionTargetEpoch = epoch,
        sessionGeneration = sessionGeneration,
        stale = stale,
        missingVerification = missing,
    )

    private fun fieldTypeOf(owner: Pair<String, String>?, shaped: Boolean): String =
        owner?.second ?: if (shaped) "contact_shaped" else "text"

    /**
     * The contract as the device carries it.
     *
     * The document in `tools/ryeong_official_v5/contracts/contact_dependency_contract.json` is the
     * authority, and the host evaluator parses it directly. A device cannot: the instrumentation
     * APK's assets are generated from one digest-checked source list, and threading a JSON document
     * through it to be re-parsed on the phone would put a second copy of the rule in the artefact
     * with nothing comparing them.
     *
     * So the device carries this restatement, and
     * `com.example.hjp.v5.V5DeviceContractRestatementTest` asserts on the host — before the APK is
     * built and before the freeze pins it — that it is equal, field for field, to the parsed
     * document. A drift becomes a failing host test rather than a phone quietly scoring by a
     * different rule.
     */
    val PRODUCTION_CONTRACT: Contract = Contract(
        actionRules = mapOf(
            "open_compose" to ToolRule(
                tool = "open_compose",
                targets = listOf(
                    TargetPath("to", Kind.ADDRESS, isArray = false, directUserInputAllowed = true),
                ),
                pure = setOf("channel", "subject", "body"),
                requiresStoredCardTarget = false,
            ),
            "create_calendar_event" to ToolRule(
                tool = "create_calendar_event",
                targets = listOf(
                    TargetPath(
                        "attendee_emails", Kind.ADDRESS,
                        isArray = true, directUserInputAllowed = true,
                    ),
                ),
                pure = setOf("title", "start_time", "end_time", "location", "description"),
                requiresStoredCardTarget = false,
            ),
            "update_business_card" to ToolRule(
                tool = "update_business_card",
                targets = listOf(
                    TargetPath(
                        "card_id", Kind.CARD_ID,
                        isArray = false, directUserInputAllowed = false,
                    ),
                ),
                pure = setOf("updates", "clear_fields"),
                requiresStoredCardTarget = true,
            ),
        ),
        rankingTools = setOf("search_contacts"),
        verificationTool = "get_contact",
        verifiedCardPath = "card_id",
        nestedScan = true,
    )

    /** The declared name a path belongs to: `attendee_emails[2]` -> `attendee_emails`. */
    fun rootOf(path: String): String = path.takeWhile { it != '.' && it != '[' }

    /**
     * Whether a value the store does not contain still looks like a way to reach a person.
     *
     * Used only to classify values the store cannot identify. It is never the evidence that a value
     * came from a card — that is decided by comparing against the store, which is provenance by
     * identity rather than by shape.
     */
    fun contactShaped(value: String): Boolean = SHAPE.matches(value.trim())

    /** The digest a provenance record carries instead of the value itself. */
    fun digest(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(16)

    /** The `name=value` working of one judgement. Never the value itself. */
    fun render(entry: Entry): List<String> = listOf(
        "action_tool=${entry.actionTool}",
        "argument_json_path=${entry.argumentPath}",
        "declared_path=${entry.declared}",
        "source_type=${entry.sourceType}",
        "source_card_id=${entry.sourceCardId}",
        "source_card_digest=${entry.valueDigest}",
        "field_type=${entry.fieldType}",
        "explicit_user_input=${entry.explicitUserInput}",
        "verified=${entry.verified}",
        "verification_tool=${entry.verificationTool}",
        "verification_turn_key=${entry.verificationTurnKey}",
        "verification_target_epoch=${entry.verificationTargetEpoch}",
        "action_target_epoch=${entry.actionTargetEpoch}",
        "session_generation=${entry.sessionGeneration}",
        "stale=${entry.stale}",
        "missing_verification=${entry.missingVerification}",
    )

    private val SHAPE = Regex(
        """\A(?:[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""" +
            """|0\d{1,2}-?\d{3,4}-?\d{4}""" +
            """|S\d{5})\z""",
    )
}
