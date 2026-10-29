package com.filestech.notes_tech.security.sauvegarde

import java.security.SecureRandom
import java.text.Normalizer

/**
 * The secret of a `.ntbackup` file (3.2.0, `docs/13-VERSION-3.2.md` D-029): a code drawn at random by
 * default, or a phrase the user chooses.
 *
 * The file leaves the phone and can be attacked offline with no limit on attempts: only a secret drawn
 * at random holds for sure. Hence the code — 24 signs of Crockford's base 32, 120 bits — shown once and
 * typed back before the file is written.
 *
 * - **Crockford's base 32** (`0-9`, `A-Z` without `I`, `L`, `O`, `U`): 32 signs, so 5 bits each, and
 *   the look-alikes folded when typed — `O` is `0`, `I` and `L` are `1` — so that a code read off a
 *   piece of paper cannot be mistyped by one of them. Case, spaces and dashes do not count.
 * - **One rule for creating and restoring** ([octets]): what has the shape of a code is used as its
 *   canonical form, anything else as a phrase in Unicode NFC — the same "é" typed on two keyboards
 *   gives two byte sequences otherwise. A restore never has to guess which kind of secret made the file.
 */
internal object SecretDeSauvegarde {

    const val LONGUEUR_DU_CODE = 24
    const val TAILLE_DES_GROUPES = 4
    const val LONGUEUR_MIN_DE_LA_PHRASE = 12

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** A new code, shown as six groups of four: `K7QM-T2XB-…`. */
    fun nouveauCode(hasard: SecureRandom = SecureRandom()): String =
        (1..LONGUEUR_DU_CODE).map { ALPHABET[hasard.nextInt(ALPHABET.length)] }
            .chunked(TAILLE_DES_GROUPES)
            .joinToString("-") { it.joinToString("") }

    /** [saisie] as a code, canonical — or `null` if it is not one. */
    fun codeCanonique(saisie: String): String? {
        val signes = StringBuilder(LONGUEUR_DU_CODE)
        for (c in saisie) {
            // `isWhitespace` covers the no-break spaces a copy-paste brings along.
            if (c.isWhitespace() || c == '-') continue
            when (val signe = c.uppercaseChar()) {
                'O' -> signes.append('0')
                'I', 'L' -> signes.append('1')
                else -> if (signe in ALPHABET) signes.append(signe) else return null
            }
        }
        return signes.toString().takeIf { it.length == LONGUEUR_DU_CODE }
    }

    /** The bytes the key is derived from — to be wiped by the caller. */
    fun octets(saisie: String): ByteArray =
        (codeCanonique(saisie) ?: Normalizer.normalize(saisie, Normalizer.Form.NFC)).toByteArray(Charsets.UTF_8)

    enum class Refus { TROP_COURTE, EVIDENTE }

    /**
     * Why a chosen phrase is refused, or `null` when it is accepted: at least [LONGUEUR_MIN_DE_LA_PHRASE]
     * characters, and not an obvious one — a short pattern repeated (`aaaaaaaaaaaa`, `abcabcabcabc`), a
     * run (`123456789012`, `abcdefghijkl`), or one of the commonest. Not a strength meter: only what is
     * certainly weak is refused, and the screen says why.
     */
    fun refusDeLaPhrase(phrase: String): Refus? {
        val nfc = Normalizer.normalize(phrase, Normalizer.Form.NFC)
        val points = nfc.codePoints().toArray()
        val minuscules = nfc.lowercase()
        return when {
            points.size < LONGUEUR_MIN_DE_LA_PHRASE -> Refus.TROP_COURTE
            estUnMotifRepete(minuscules) || estUneSuite(minuscules) || minuscules in COURANTES -> Refus.EVIDENTE
            else -> null
        }
    }

    private fun estUnMotifRepete(texte: String): Boolean = (1..MOTIF_MAX).any { taille ->
        texte.length % taille == 0 && texte.chunked(taille).toSet().size == 1
    }

    /** Digits only or letters only, each the next (or the previous) of the one before, wrapping around. */
    private fun estUneSuite(texte: String): Boolean {
        val taille = when {
            texte.all { it in '0'..'9' } -> CHIFFRES
            texte.all { it in 'a'..'z' } -> LETTRES
            else -> return false
        }
        val pas = texte.zipWithNext { a, b -> Math.floorMod(b - a, taille) }.toSet()
        return pas == setOf(1) || pas == setOf(taille - 1)
    }

    private const val MOTIF_MAX = 4
    private const val CHIFFRES = 10
    private const val LETTRES = 26

    /** The commonest long passwords, lowercased: what a dictionary tries first. */
    private val COURANTES = setOf(
        "password1234", "password12345", "password123!", "passwordpassword",
        "motdepasse12", "motdepasse123", "motdepasse1234", "motdepassemotdepasse",
        "azertyuiop12", "azertyuiop123", "azertyuiopqs", "qwertyuiop12", "qwertyuiop123", "qwertyuiopas",
        "qwertzuiop12", "qwertzuiop123", "iloveyou1234", "jetaime12345", "jetaimejetaime",
        "1234567890ab", "123456789abc", "abc123abc123", "azertyazerty", "qwertyqwerty",
        "passwort1234", "contraseña12", "contrasena12", "password2024", "password2025", "password2026",
    )
}
