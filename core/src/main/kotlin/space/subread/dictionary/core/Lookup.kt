package space.subread.dictionary.core

/** A term with the dictionary it comes from. The dictionary position is the order the user chose. */
data class StoredTerm(val dictionaryId: Long, val dictionaryTitle: String, val dictionaryPosition: Int, val term: Term)

/** Where the terms are: on a phone, a SQLite table. In a test, a list. */
interface TermSource {
    /** Each term whose expression, or whose reading, is one of the texts. */
    fun terms(texts: Collection<String>): List<StoredTerm>
}

/**
 * One term that matches the text.
 *
 * @param length how many characters of the text, from the scan position, the term covers.
 * @param reasons the deinflection, from the text to the dictionary form. Empty when the text is the dictionary form.
 */
data class Match(val stored: StoredTerm, val length: Int, val reasons: List<String>)

/** Looks a text up: the longest term at the scan position first, like the Yomitan popup. */
object Lookup {

    /** The most characters a term can cover. Longer terms are rare, and each character costs a scan. */
    const val MAX_LENGTH = 24

    /**
     * Each term that starts at `offset` in `text`, longest first. Within one length, the
     * dictionaries in their order, and within one dictionary the higher score first.
     */
    fun find(text: String, offset: Int, source: TermSource): List<Match> {
        val end = minOf(text.length, offset + MAX_LENGTH)
        if (offset < 0 || offset >= end) return emptyList()

        // Every dictionary form for every prefix, then one query for all of them.
        data class Candidate(val form: Deinflection, val length: Int)
        val candidates = ArrayList<Candidate>()
        for (stop in end downTo offset + 1) {
            val slice = text.substring(offset, stop)
            val variants = linkedSetOf(slice, Kana.toHiragana(slice))
            for (variant in variants) {
                for (form in Deinflector.deinflect(variant)) candidates.add(Candidate(form, stop - offset))
            }
        }
        val byText = candidates.groupBy { it.form.text }
        val found = source.terms(byText.keys)

        val matches = ArrayList<Match>()
        // One match per term: the longest one, because the candidates come longest first.
        // 食べる is both the text itself and the continuative 食べ plus る; the text itself wins.
        val seen = HashSet<Pair<Long, Term>>()
        for (stored in found) {
            val term = stored.term
            // A kana text matches the reading; a text with kanji matches the expression.
            val forms = (byText[term.expression].orEmpty() +
                (if (term.reading.isNotEmpty() && term.reading != term.expression) byText[term.reading].orEmpty() else emptyList()))
                .sortedByDescending { it.length }
            for (candidate in forms) {
                if (!candidate.form.accepts(term.rules)) continue
                if (!seen.add(stored.dictionaryId to term)) continue
                matches.add(Match(stored, candidate.length, candidate.form.reasons))
            }
        }
        return matches.sortedWith(
            compareByDescending<Match> { it.length }
                .thenBy { it.reasons.size }
                .thenBy { it.stored.dictionaryPosition }
                .thenByDescending { it.stored.term.score },
        )
    }
}
