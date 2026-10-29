package com.filestech.notes_tech.domain.recherche

import com.filestech.notes_tech.core.text.DartTextSemantics
import java.text.Normalizer
import java.util.Locale

/**
 * Where the words of a search appear in a text (3.2.0, D-033: "Find in note"), insensitive to case
 * and accents, as the app's search is, and **in the text as written**: positions in the original
 * string, the one the screen draws, never in a folded copy of it.
 *
 * - The search is cut into words at the spaces — Dart's set of them, as the app's search
 *   ([DartTextSemantics.WHITESPACE]) — and each word is looked for on its own, **inside** words
 *   too: "budget" finds "budgets", as a find-in-page does.
 * - A character is folded on its own: decomposed (NFD), its accents dropped, lower-cased. So "ete"
 *   finds "Été", "été" finds "ete", and a text typed in decomposed form ("e" + a combining acute)
 *   is found too, its accent inside the occurrence.
 * - ⚠️ **The typographic apostrophes fold to `'`.** The app's search (FTS5, `unicode61`) treats an
 *   apostrophe as a separator, so "l'été" typed on a keyboard finds a note that writes "l’été"; the
 *   note must then show that occurrence, not "0 / 0".
 *
 * ⚠️ Nothing is copied: a note has no size limit, and a folded copy plus its table back to the
 * original would weigh several times the note. Each character is folded where it is read, a cache
 * keeping the folds already made.
 */
object RechercheDansLeTexte {

    /** An occurrence: from [debut] included to [fin] excluded, positions in the original text. */
    data class Plage(val debut: Int, val fin: Int)

    /** The words of [saisie], folded, without repeats. Empty for a search of spaces only. */
    fun mots(saisie: String): List<String> = saisie.split(DartTextSemantics.WHITESPACE)
        .map { mot -> buildString { forEachPoint(mot) { append(replier(it, null)) } } }
        .filter { it.isNotEmpty() }
        .distinct()

    /**
     * The occurrences of [mots] in [texte], in order, at most [max] of them. Two occurrences that
     * overlap — "bud" and "budget" — are one.
     *
     * @param verifier called now and then on a long text: the caller's cancellation, as in
     *   `MarkdownPreviewReader.read`.
     */
    fun occurrences(texte: String, mots: List<String>, max: Int, verifier: () -> Unit = {}): List<Plage> {
        if (mots.isEmpty() || max <= 0) return emptyList()
        val cache = HashMap<Int, String>()
        val plages = ArrayList<Plage>()
        var debut = 0
        var lus = 0
        while (debut < texte.length) {
            if (++lus % PAS_DE_VERIFICATION == 0) verifier()
            val point = texte.codePointAt(debut)
            // An occurrence starts on a letter, never on the accent that follows one.
            val fin = if (replier(point, cache).isEmpty()) -1 else mots.maxOf { finDe(texte, debut, it, cache) }
            if (fin > debut) {
                val derniere = plages.lastOrNull()
                if (derniere != null && debut < derniere.fin) {
                    plages[plages.lastIndex] = Plage(derniere.debut, maxOf(derniere.fin, fin))
                } else {
                    if (plages.size == max) break
                    plages += Plage(debut, fin)
                }
            }
            debut += Character.charCount(point)
        }
        return plages
    }

    /** Where [mot] ends if it starts at [debut], or -1; the accents right after it are part of it. */
    private fun finDe(texte: String, debut: Int, mot: String, cache: HashMap<Int, String>): Int {
        var position = debut
        var k = 0
        while (k < mot.length) {
            if (position >= texte.length) return -1
            val point = texte.codePointAt(position)
            for (lettre in replier(point, cache)) {
                // A character folding to several letters may end past the word: it is in.
                if (k == mot.length) break
                if (lettre != mot[k]) return -1
                k++
            }
            position += Character.charCount(point)
        }
        while (position < texte.length) {
            val point = texte.codePointAt(position)
            if (replier(point, cache).isNotEmpty()) break
            position += Character.charCount(point)
        }
        return position
    }

    private fun replier(point: Int, cache: HashMap<Int, String>?): String {
        if (point < ASCII_REPLIE.size) return ASCII_REPLIE[point]
        cache?.get(point)?.let { return it }
        val replie = when (point) {
            APOSTROPHE_OUVRANTE, APOSTROPHE, APOSTROPHE_MODIFICATIVE -> "'"
            else -> Normalizer.normalize(String(Character.toChars(point)), Normalizer.Form.NFD)
                .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
                .lowercase(Locale.ROOT)
        }
        cache?.put(point, replie)
        return replie
    }

    private inline fun forEachPoint(texte: String, action: (Int) -> Unit) {
        var i = 0
        while (i < texte.length) {
            val point = texte.codePointAt(i)
            action(point)
            i += Character.charCount(point)
        }
    }

    private val ASCII_REPLIE = Array(128) { it.toChar().lowercaseChar().toString() }
    private const val APOSTROPHE_OUVRANTE = 0x2018
    private const val APOSTROPHE = 0x2019
    private const val APOSTROPHE_MODIFICATIVE = 0x02BC
    private const val PAS_DE_VERIFICATION = 64 * 1024
}
