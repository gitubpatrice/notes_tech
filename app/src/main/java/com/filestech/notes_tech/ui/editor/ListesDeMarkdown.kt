package com.filestech.notes_tech.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * What opens a Markdown line, as written: its container — indentation and quote markers —, its list
 * marker, its task box. Each part keeps the spaces that follow it, so that putting a line back together
 * changes nothing the user did not ask to change.
 *
 * @param marqueur `- `, `* `, `+ `, `1. `, `1) ` and their spaces; `null` when the line is not a list item.
 * @param case `[ ] `, `[x] `, `[X] `; `null` when the item has no task box — or the line is no item.
 */
internal data class DebutDeLigne(val conteneur: String, val marqueur: String?, val case: String?) {

    /** Characters before the line's own text. */
    val longueur: Int get() = conteneur.length + (marqueur?.length ?: 0) + (case?.length ?: 0)

    val estUneTache: Boolean get() = marqueur != null && case != null

    val estNumerote: Boolean get() = marqueur?.first()?.isDigit() == true
}

/**
 * Reads the start of a Markdown line, the way the preview's parser will (CommonMark + GFM task boxes):
 * one rule for D-048 (insert a checklist) and D-049 (Enter continues a list).
 *
 * ⚠️ **A list marker needs a space after it**, as in CommonMark: `1.Eat` and `-text` are text, the same
 * call as D-045 (issue #57) — continuing them would invent a list the preview does not draw.
 */
internal object DebutsDeLigne {

    /** Indentation and quote markers, as many as the line holds (`> > - x`, `>   - x`). */
    private val CONTENEUR = Regex("^[ \\t]*(?:>[ \\t]*)*")
    private val PUCE = Regex("^[-*+][ \\t]+")
    private val NUMERO = Regex("^\\d{1,9}[.)][ \\t]+")
    private val CASE = Regex("^\\[[ xX]](?:[ \\t]+|$)")

    /** `---`, `* * *`, `___`: a rule, not a list of dashes. */
    private val SEPARATEUR = Regex("^[ \\t]{0,3}([-*_])(?:[ \\t]*\\1){2,}[ \\t]*$")

    fun lire(ligne: String): DebutDeLigne {
        val conteneur = CONTENEUR.find(ligne)?.value.orEmpty()
        val reste = ligne.substring(conteneur.length)
        if (SEPARATEUR.matches(reste)) return DebutDeLigne(conteneur, null, null)
        val marqueur = (PUCE.find(reste) ?: NUMERO.find(reste))?.value
            ?: return DebutDeLigne(conteneur, null, null)
        val case = CASE.find(reste.substring(marqueur.length))?.value
        return DebutDeLigne(conteneur, marqueur, case)
    }

    /** The marker of the NEXT item: the same bullet, or the next number with the same delimiter. */
    fun marqueurSuivant(marqueur: String): String {
        val chiffres = marqueur.takeWhile { it.isDigit() }
        if (chiffres.isEmpty()) return marqueur
        return (chiffres.toLong() + 1).toString() + marqueur.substring(chiffres.length)
    }

    // ── Lines, as the app reads them ─────────────────────────────────────────────────────────────────
    // `\n`, `\r\n` or a lone `\r` (`MarkdownPreviewReader.lineFeedsOnly`): an imported Windows or old Mac
    // file keeps its line ends, and a lone `\r` must not merge two lines (GPT-5.6 review of 3.5.0).

    fun estUneFinDeLigne(caractere: Char): Boolean = caractere == '\n' || caractere == '\r'

    /** Where the line holding [position] starts in [texte]. */
    fun debutDeLaLigne(texte: String, position: Int): Int {
        var i = position
        while (i > 0 && !estUneFinDeLigne(texte[i - 1])) i--
        return i
    }

    /** Where the line holding [position] ends: at its line end, or at the end of [texte]. */
    fun finDeLaLigne(texte: String, position: Int): Int {
        var i = position
        while (i < texte.length && !estUneFinDeLigne(texte[i])) i++
        return i
    }

    /** Where the line after the one ending at [fin] starts: past its `\n`, `\r` or `\r\n`. */
    fun ligneSuivante(texte: String, fin: Int): Int =
        if (fin + 1 < texte.length && texte[fin] == '\r' && texte[fin + 1] == '\n') fin + 2 else fin + 1

    /**
     * Whether the list line starting at [debut] is in fact CODE (D-049, GPT-5.6 review of 3.5.0): inside a
     * ``` or ~~~ fence, or indented by four columns at least with no list item on the line above —
     * CommonMark's indented code. Enter there is an ordinary line break, never a new item.
     *
     * Erring on the side of code: a nested item under a parent written on several lines is not continued —
     * an Enter that does nothing special, rather than a marker typed into someone's code.
     */
    fun estDuCode(texte: String, debut: Int, ligne: DebutDeLigne): Boolean {
        val indenteCommeDuCode = colonnesDIndentation(ligne.conteneur) >= COLONNES_DU_CODE
        return dansUneClotureDeCode(texte, debut) || (indenteCommeDuCode && !listeJusteAuDessus(texte, debut))
    }

    private fun dansUneClotureDeCode(texte: String, jusqua: Int): Boolean {
        var ouverture: String? = null
        var debut = 0
        while (debut < jusqua) {
            val fin = finDeLaLigne(texte, debut)
            // The quote markers off, not the indentation: four spaces before ``` make code, not a fence.
            val sansCitation = texte.substring(debut, fin).replaceFirst(CITATIONS, "")
            val cloture = CLOTURE.find(sansCitation)?.groupValues?.get(1)
            if (cloture != null) {
                ouverture = when {
                    ouverture == null -> cloture
                    cloture[0] == ouverture[0] && cloture.length >= ouverture.length -> null
                    else -> ouverture
                }
            }
            debut = ligneSuivante(texte, fin)
        }
        return ouverture != null
    }

    private fun listeJusteAuDessus(texte: String, debut: Int): Boolean {
        var courante = debut
        while (courante > 0) {
            var fin = courante - 1
            if (texte[fin] == '\n' && fin > 0 && texte[fin - 1] == '\r') fin--
            val precedente = texte.substring(debutDeLaLigne(texte, fin), fin)
            if (precedente.isNotBlank()) return lire(precedente).marqueur != null
            courante = debutDeLaLigne(texte, fin)
        }
        return false
    }

    /** The columns of indentation after the last quote marker; a tab counts as four, as CommonMark's tab stops. */
    private fun colonnesDIndentation(conteneur: String): Int =
        conteneur.substringAfterLast('>').sumOf { if (it == '\t') COLONNES_DU_CODE else 1 }

    /** A fence opening or closing a code block: up to three spaces, then three backticks or tildes at least. */
    private val CLOTURE = Regex("^ {0,3}(`{3,}|~{3,})")

    /** The quote markers opening a line, with the one space each may take. */
    private val CITATIONS = Regex("^(?: {0,3}> ?)*")
    private const val COLONNES_DU_CODE = 4

    const val CASE_VIDE = "[ ] "
}

/**
 * D-048 (3.5.0): the "Checklist" entry of the writing menu, on the lines the cursor or the selection
 * touches.
 *
 * - Not all of them are tasks yet → each line that is not one gets a box: `texte` → `- [ ] texte`,
 *   `- texte` → `- [ ] texte`, `* texte` → `* [ ] texte` (the bullet is kept: changing it would start a
 *   new list in CommonMark), `1. texte` → `1. [ ] texte` (the number is the user's).
 * - All of them are tasks already → the second press takes the boxes off: `- [ ] texte` → `texte`,
 *   `1. [x] texte` → `1. texte`.
 * - The cursor's line is blank → `- [ ] `, the cursor after it. Blank lines of a selection are left as
 *   they are.
 *
 * Indentation and quote markers are kept. The cursor and the selection follow the text they were in.
 * Pure, like [InsertionDeTexte], and tested the same way: an offset wrong by one shows nowhere but here.
 */
internal object ListeACocher {

    /** A line of the text: `[debut, fin)`, [fin] at its `\n` or at the end of the text. */
    private data class Ligne(val debut: Int, val fin: Int)

    /** Replaces `[debut, debut + ancienne)` of the old text with [nouveau]. */
    private data class Modification(val debut: Int, val ancienne: Int, val nouveau: String)

    fun basculer(valeur: TextFieldValue): TextFieldValue {
        val texte = valeur.text
        val debutSel = valeur.selection.min.coerceIn(0, texte.length)
        val finSel = valeur.selection.max.coerceIn(debutSel, texte.length)
        val lignes = lignesTouchees(texte, debutSel, finSel)
        val visees = if (lignes.size == 1) lignes else lignes.filter { texte.substring(it.debut, it.fin).isNotBlank() }
        val retirer = visees.isNotEmpty() &&
            visees.all { DebutsDeLigne.lire(texte.substring(it.debut, it.fin)).estUneTache }

        val modifications = visees.mapNotNull { ligne ->
            val dansLaLigne = modification(texte.substring(ligne.debut, ligne.fin), retirer)
            dansLaLigne?.copy(debut = ligne.debut + dansLaLigne.debut)
        }
        if (modifications.isEmpty()) return valeur
        // From the last line up, so that the offsets of the lines above stay valid.
        val resultat = StringBuilder(texte)
        modifications.asReversed().forEach { resultat.replace(it.debut, it.debut + it.ancienne, it.nouveau) }
        val fin = suivre(finSel, modifications, avantUnAjout = false)
        val selection = if (debutSel == finSel) {
            TextRange(fin)
        } else {
            TextRange(suivre(debutSel, modifications, avantUnAjout = true), fin)
        }
        return TextFieldValue(text = resultat.toString(), selection = selection)
    }

    /**
     * Where [position] of the old text lands in the new one, [modifications] being in text order. An offset
     * before an edit stays; one after it moves by what the edit added or took; one inside the replaced
     * part goes to the end of what replaced it.
     *
     * @param avantUnAjout an offset right where something is ADDED stays before it: the start of a
     *   selection, which then takes in the new box. A cursor goes after it, to write the item.
     */
    private fun suivre(position: Int, modifications: List<Modification>, avantUnAjout: Boolean): Int {
        var decalage = 0
        for (m in modifications) {
            when {
                position < m.debut -> return position + decalage
                avantUnAjout && position == m.debut && m.ancienne == 0 -> return position + decalage
                position >= m.debut + m.ancienne -> decalage += m.nouveau.length - m.ancienne
                else -> return m.debut + decalage + m.nouveau.length
            }
        }
        return position + decalage
    }

    /** The edit of one line, relative to its start, or `null` when the line stays as it is. */
    private fun modification(ligne: String, retirer: Boolean): Modification? {
        val debut = DebutsDeLigne.lire(ligne)
        val ici = debut.conteneur.length
        val marqueur = debut.marqueur
        val case = debut.case
        return when {
            retirer && marqueur != null && case != null -> if (debut.estNumerote) {
                Modification(ici + marqueur.length, case.length, "")
            } else {
                Modification(ici, marqueur.length + case.length, "")
            }
            retirer || debut.estUneTache -> null
            marqueur != null -> Modification(ici + marqueur.length, 0, DebutsDeLigne.CASE_VIDE)
            else -> Modification(ici, 0, NOUVEL_ELEMENT)
        }
    }

    /**
     * The lines [debut]..[fin] touches. A selection that ends right at the start of a line does not take
     * that line: it was selected up to the line break before it.
     */
    private fun lignesTouchees(texte: String, debut: Int, fin: Int): List<Ligne> {
        // Ending right after a line end: the selection stops at the line above — at the START of that line
        // end, a `\r\n` being one.
        val derniere = when {
            fin <= debut || !DebutsDeLigne.estUneFinDeLigne(texte[fin - 1]) -> fin
            texte[fin - 1] == '\n' && fin - 2 >= debut && texte[fin - 2] == '\r' -> fin - 2
            else -> fin - 1
        }
        val lignes = mutableListOf<Ligne>()
        var depart = DebutsDeLigne.debutDeLaLigne(texte, debut)
        while (true) {
            val bout = DebutsDeLigne.finDeLaLigne(texte, depart)
            lignes += Ligne(depart, bout)
            if (bout >= derniere || bout == texte.length) return lignes
            depart = DebutsDeLigne.ligneSuivante(texte, bout)
        }
    }

    private const val NOUVEL_ELEMENT = "- [ ] "
}

/**
 * D-049 (3.5.0): Enter on a list line opens the next item.
 *
 * - `- [ ] a` and `- [x] a` → `- [ ] ` (a new item is never ticked); `- a`, `* a`, `+ a` → the same
 *   bullet; `1. a` → `2. `, `1) a` → `2) `. Indentation and quote markers are kept.
 * - Enter in the middle of an item: the rest of the line goes to the new item, as typed.
 * - Not in code: a ``` fence, or four columns of indentation with no list item above (CommonMark's code).
 * - **Enter on an empty item** (`- [ ] ` alone) takes the marker off, and the line break with it: the
 *   list ends there, as in Obsidian, Joplin or Google Keep.
 *
 * Applies only to ONE line break typed at the cursor: [apres] is [avant] with a single `\n` inserted where
 * its cursor was, and the cursor right after it. A paste of several lines, the dictation (which inserts
 * through `insererAuCurseur`, not here), a keyboard replacing a word along with the line break: none of
 * them matches, and they are left alone.
 *
 * @return the value to keep instead of [apres], or `null` when [apres] stays as typed.
 */
internal object SuiteDeListe {

    fun apres(avant: TextFieldValue, apres: TextFieldValue): TextFieldValue? {
        val texte = avant.text
        val ici = avant.selection.start
        if (!estUnRetourALaLigneTapeIci(texte, ici, avant.selection.collapsed, apres)) return null

        val debutDeLigne = DebutsDeLigne.debutDeLaLigne(texte, ici)
        val finDeLigne = DebutsDeLigne.finDeLaLigne(texte, ici)
        val ligne = texte.substring(debutDeLigne, finDeLigne)
        val debut = DebutsDeLigne.lire(ligne)
        val marqueur = debut.marqueur
        // Not a list item, Enter inside the marker itself, or a "list" in code: an ordinary line break.
        val dansLeMarqueur = ici - debutDeLigne < debut.longueur
        if (marqueur == null || dansLeMarqueur || DebutsDeLigne.estDuCode(texte, debutDeLigne, debut)) return null

        return if (ligne.substring(debut.longueur).isBlank()) {
            finDeListe(texte, debutDeLigne, finDeLigne, debut)
        } else {
            elementSuivant(texte, ici, debut, marqueur)
        }
    }

    /** The empty item: the list ends. The line keeps its container — a quote stays a quote. */
    private fun finDeListe(texte: String, debutDeLigne: Int, finDeLigne: Int, debut: DebutDeLigne): TextFieldValue {
        val sansElement = texte.substring(0, debutDeLigne) + debut.conteneur + texte.substring(finDeLigne)
        return TextFieldValue(sansElement, TextRange(debutDeLigne + debut.conteneur.length))
    }

    /** The next item, the rest of the line in it, the cursor after its marker. */
    private fun elementSuivant(texte: String, ici: Int, debut: DebutDeLigne, marqueur: String): TextFieldValue {
        val case = if (debut.case != null) DebutsDeLigne.CASE_VIDE else ""
        val suite = debut.conteneur + DebutsDeLigne.marqueurSuivant(marqueur) + case
        // The rest of the line follows AS TYPED, its spaces included (GPT-5.6 review of 3.5.0): nothing the
        // user wrote disappears — the rule the preview follows since D-024.
        val nouveau = texte.substring(0, ici) + "\n" + suite + texte.substring(ici)
        return TextFieldValue(nouveau, TextRange(ici + 1 + suite.length))
    }

    private fun estUnRetourALaLigneTapeIci(texte: String, ici: Int, rienDeSelectionne: Boolean, apres: TextFieldValue) =
        rienDeSelectionne &&
            ici in 0..texte.length &&
            apres.selection.collapsed &&
            apres.selection.start == ici + 1 &&
            apres.text.length == texte.length + 1 &&
            apres.text[ici] == '\n' &&
            apres.text.regionMatches(0, texte, 0, ici) &&
            apres.text.regionMatches(ici + 1, texte, ici, texte.length - ici)
}
