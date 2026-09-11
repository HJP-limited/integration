package com.hjp.agent.core

import com.hjp.agent.contract.DirectoryNameMatch

/**
 * Spans of an utterance that might be somebody's name, offered to the contact store for checking.
 *
 * Pure and deliberately over-generous. It proposes; the store disposes. A candidate that matches no
 * card is discarded at zero cost, so the safe error here is to propose too many — proposing too few
 * is what loses a person whose name happens to be spelled like something else.
 *
 * The alternative, deciding *here* what looks like a Korean name, is the thing this design refuses.
 * Every closed rule that was tried failed on real data: a two-to-four-syllable ceiling loses 남궁여진
 * and 황보라온해; a surname list loses every name not on it; stripping a trailing 을/는/도/가 turns
 * 설태을 into 설태 and 하도 into 하. So no rule here decides what a name looks like. The utterance is
 * cut into every reasonable span, both as written and with a trailing particle removed, and the
 * store's own contents settle which of them is a person.
 */
object ContactNameCandidates {

    /** How many whitespace-separated tokens one candidate may span. Covers "마이클 첸", "안나 리". */
    private const val MAX_TOKEN_SPAN = 3

    /** Longest candidate worth proposing, in characters. Longer runs are phrases, not names. */
    private const val MAX_LENGTH = 24

    /** Honorifics and datives that only ever follow a person. */
    private val PERSON_MARKERS = listOf("씨", "님", "군", "양", "에게", "한테", "께", "선배", "선생")

    /**
     * Particles that attach to a noun and are not part of it.
     *
     * Stripped only to make an *extra* candidate. The unstripped span is always proposed first and
     * always wins an exact match, which is what keeps 설태을 a person rather than a search for 설태.
     */
    private val TRAILING_PARTICLES = listOf(
        "이라는", "라는", "이란", "란", "에게", "한테", "께서", "께", "이랑", "랑", "하고",
        "으로", "로", "와", "과", "은", "는", "이", "가", "을", "를", "의", "도", "만", "좀",
    )

    private val TOKEN_SPLIT = Regex("\\s+")

    /** A candidate, with what the sentence said about it. */
    data class Candidate(
        val span: String,
        /** The sentence marked this span as a person, so a common noun here is a name. */
        val personMarked: Boolean,
    )

    /**
     * Every span of [text] worth asking the store about, longest first.
     *
     * Longest first is the priority rule: when 김민 and 김민준 are both cards, a sentence containing
     * 김민준 is about 김민준. Ordering here means the caller can stop at the first match per position
     * without a second ranking pass.
     */
    fun candidates(text: String): List<Candidate> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val tokens = trimmed.split(TOKEN_SPLIT).filter(String::isNotBlank)
        if (tokens.isEmpty()) return emptyList()

        // Insertion-ordered so the "longest first" contract survives de-duplication.
        val found = LinkedHashMap<String, Boolean>()
        fun offer(span: String, personMarked: Boolean) {
            val value = span.trim().trim(*PUNCTUATION)
            if (value.length < MIN_LENGTH || value.length > MAX_LENGTH) return
            if (value in PersonNameMask.DOMAIN_TOKENS) return
            // A marker seen anywhere for this span is remembered: "박서준씨 회사" marks it once.
            found[value] = (found[value] ?: false) || personMarked
        }

        for (span in MAX_TOKEN_SPAN downTo 1) {
            for (start in 0..tokens.size - span) {
                val window = tokens.subList(start, start + span).joinToString(" ")
                val cleaned = window.trim(*PUNCTUATION)
                if (cleaned.isEmpty()) continue

                // 1. Exactly as written. This is the candidate that must win when it matches.
                offer(cleaned, personMarked = false)

                // 2. With a person marker removed, and remembered as person-marked. "박서준씨" is
                //    unambiguous evidence; the store is asked about 박서준.
                PERSON_MARKERS.firstOrNull { cleaned.endsWith(it) && cleaned.length > it.length }
                    ?.let { marker ->
                        val stem = cleaned.removeSuffix(marker)
                        offer(stem, personMarked = true)
                        // "박서준씨는", "남지후님께" — a particle may follow the honorific.
                        TRAILING_PARTICLES.firstOrNull { stem.endsWith(it) && stem.length > it.length }
                            ?.let { offer(stem.removeSuffix(it), personMarked = true) }
                    }

                // 3. With a trailing particle removed. Only ever an additional candidate.
                TRAILING_PARTICLES.firstOrNull { cleaned.endsWith(it) && cleaned.length > it.length }
                    ?.let { particle ->
                        val stem = cleaned.removeSuffix(particle)
                        offer(stem, personMarked = false)
                        // Compact honorific + particle ("음동주씨로", "남지후님께") is
                        // common in correction turns. Strip both layers so the directory can
                        // resolve the actual person name before routing.
                        PERSON_MARKERS.firstOrNull { stem.endsWith(it) && stem.length > it.length }
                            ?.let { marker -> offer(stem.removeSuffix(marker), personMarked = true) }
                    }
            }
        }

        // A span written directly in front of one of the person's own card fields is a person:
        // "박서준 회사가 어디야?" says so without any honorific. The existing production span finder
        // already knows those positions, so this asks it rather than growing a second one.
        PersonNameMask.nameSpans(trimmed).forEach { offer(it, personMarked = true) }

        return found.entries
            .sortedByDescending { it.key.length }
            .map { Candidate(it.key, it.value) }
    }

    private const val MIN_LENGTH = 2
    private val PUNCTUATION =
        charArrayOf('?', '!', '.', ',', '。', '？', '！', ':', ';', '"', '\'', '“', '”', '‘', '’', ' ')
}

/**
 * The contact store, asked one question: which of these spans is somebody's name?
 *
 * A port rather than a repository dependency. `agent-core` must not know what a business card is or
 * where it is stored, and the router must stay a pure function — so the store is consulted once, in
 * the kernel, and the answer travels into routing as typed state.
 *
 * Implementations answer by **exact** match on the store's own name field. Nothing here does fuzzy
 * matching, prefix matching or scoring: "is there a card called exactly this?" is a question with a
 * yes-or-no answer, and anything looser would make an ordinary word into a person.
 */
fun interface ContactDirectory {

    /**
     * The candidates that are real contact names, in the order they were offered.
     *
     * Returning an empty list is always a valid answer and means "this sentence names nobody I hold".
     * An implementation that cannot reach its store must return empty rather than throw: a lookup
     * being unavailable is a reason to fall back to the ordinary rules, not to fail the turn.
     */
    suspend fun resolve(candidates: List<ContactNameCandidates.Candidate>): List<DirectoryNameMatch>

    companion object {
        /** The default for every caller without a store. It never claims a sentence names anybody. */
        val None: ContactDirectory = ContactDirectory { emptyList() }
    }
}
