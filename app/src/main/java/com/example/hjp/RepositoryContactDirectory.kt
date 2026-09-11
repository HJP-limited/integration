package com.example.hjp

import com.hjp.agent.contract.DirectoryNameMatch
import com.hjp.agent.core.ContactDirectory
import com.hjp.agent.core.ContactNameCandidates
import com.hjp.tool.contact.BusinessCardRecord
import com.hjp.tool.contact.BusinessCardRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Answers "is this span somebody's name?" from the real card store.
 *
 * It lives in the composition root rather than in `tool-contact`, because it is the one place that
 * is allowed to know both a card repository and the agent's routing contract. Putting it in the
 * plugin module would make a plugin depend on the kernel, which is the wrong way round.
 *
 * Matching is **exact** on the stored name, with whitespace squeezed out so "마이클 첸" and "마이클첸"
 * are the same person. Nothing here does prefix matching, edit distance or scoring: the router's
 * question is whether a card with this exact name exists, and any looser answer turns ordinary words
 * into colleagues.
 *
 * The index is built once and reused. Loading every card on every turn to answer a question whose
 * answer only changes when a card does would be a per-turn cost for a startup-time fact, so
 * [invalidate] is wired to the same place that already invalidates the search backend after an edit.
 */
class RepositoryContactDirectory(
    private val repository: BusinessCardRepository,
) : ContactDirectory {

    private data class Index(
        /** Squeezed name -> the cards filed under it, plus the name as the store spells it. */
        val byName: Map<String, Entry>,
        /** Every company, job title, department and industry, squeezed. Not people. */
        val nonPersonVocabulary: Set<String>,
    )

    private data class Entry(val storedName: String, val cardIds: List<String>)

    private val mutex = Mutex()

    @Volatile
    private var index: Index? = null

    /** Drops the cached index. Call after anything that can add, rename or remove a card. */
    fun invalidate() {
        index = null
    }

    override suspend fun resolve(
        candidates: List<ContactNameCandidates.Candidate>,
    ): List<DirectoryNameMatch> {
        if (candidates.isEmpty()) return emptyList()
        val current = index() ?: return emptyList()

        // Candidates arrive longest first, so the first match is the longest one: a sentence
        // containing 김민준 is about 김민준 even when 김민 is also a card. A shorter match contained in
        // one already taken is that same person seen again, so it is dropped rather than reported.
        val matches = mutableListOf<DirectoryNameMatch>()
        val claimed = mutableListOf<String>()
        candidates.forEach { candidate ->
            val key = candidate.span.squeeze()
            val entry = current.byName[key] ?: return@forEach
            if (claimed.any { it.contains(key) }) return@forEach
            claimed += key
            matches += DirectoryNameMatch(
                span = candidate.span,
                name = entry.storedName,
                cardIds = entry.cardIds,
                personMarked = candidate.personMarked,
                alsoNonPersonVocabulary = key in current.nonPersonVocabulary,
            )
        }
        return matches
    }

    private suspend fun index(): Index? {
        index?.let { return it }
        return mutex.withLock {
            index?.let { return@withLock it }
            try {
                build(repository.loadAll()).also { index = it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // An unreachable store means the router simply learns nothing about this sentence.
                null
            }
        }
    }

    private fun build(cards: List<BusinessCardRecord>): Index {
        val byName = LinkedHashMap<String, Entry>()
        val nonPerson = LinkedHashSet<String>()
        cards.forEach { card ->
            addName(byName, card.name, card.id)
            // A Latin-script name is held in its own column and is just as much this person's name.
            addName(byName, card.nameEn, card.id)
            listOf(card.company, card.title, card.department, card.industry)
                .map { it.squeeze() }
                .filter(String::isNotEmpty)
                .forEach(nonPerson::add)
        }
        return Index(byName, nonPerson)
    }

    private fun addName(into: MutableMap<String, Entry>, name: String, cardId: String) {
        val key = name.squeeze()
        if (key.isEmpty()) return
        val existing = into[key]
        into[key] = if (existing == null) {
            Entry(name, listOf(cardId))
        } else {
            existing.copy(cardIds = (existing.cardIds + cardId).distinct())
        }
    }

    private fun String.squeeze(): String = replace(WHITESPACE, "")

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
