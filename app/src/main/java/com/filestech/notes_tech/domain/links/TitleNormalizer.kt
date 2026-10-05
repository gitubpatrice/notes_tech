package com.filestech.notes_tech.domain.links

import com.filestech.notes_tech.core.text.DartTextSemantics

/**
 * Réduit un titre à sa **clé d'appariement** : minuscules, diacritiques latins dépouillés, espaces
 * normalisés.
 *
 * ## Ce que cette fonction porte réellement
 *
 * C'est elle qui écrit `note_links.target_title_norm`, et c'est par cette colonne qu'un lien
 * `[[Réunion budget]]` retrouve la note intitulée « réunion budget ». Les deux versions de
 * l'application lisent et écrivent la même base : **si les deux normalisations divergent d'un seul
 * caractère, les liens ne s'apparient plus**, et rien ne le signale — ni erreur, ni journal. Un
 * utilisateur verrait simplement ses rétroliens disparaître après la mise à jour.
 *
 * Transposition de `BacklinksService.normalizeTitle` (`notes_tech/lib/services/backlinks_service.dart:134`)
 * et de `TextUtils.stripLatinDiacritic` (`lib/utils/text_utils.dart:16`). Les subtilités de casse et
 * d'espaces sont dans [DartTextSemantics] ; ici ne reste que la table de dépouillement.
 *
 * ## L'ordre des trois étapes n'est pas indifférent
 *
 * Minuscules **d'abord**, dépouillement ensuite : la table ne couvre que les formes minuscules
 * (`0x00E0` à `0x00FF`). `É` n'y figure pas et n'a pas à y figurer — il est devenu `é` à l'étape
 * précédente. Inverser les deux étapes laisserait passer toutes les majuscules accentuées.
 */
internal object TitleNormalizer {

    /**
     * @return la clé d'appariement, éventuellement vide si le titre ne contenait que des espaces.
     */
    fun normalize(raw: String): String {
        val lowered = DartTextSemantics.lowercase(raw)
        val stripped = StringBuilder(lowered.length)
        for (character in lowered) {
            stripped.append(stripLatinDiacritic(character))
        }
        return DartTextSemantics.trim(
            DartTextSemantics.WHITESPACE.replace(stripped, " "),
        )
    }

    /**
     * Ramène un caractère latin diacrité à son équivalent ASCII, ou le rend inchangé.
     *
     * ⚠️ **Décalque volontairement incomplet.** La table héritée couvre le supplément Latin-1 et
     * deux ligatures ; elle laisse passer `ð`, `þ`, **`ø`**, et tout le latin étendu (`ā`, `ē`, `ş`,
     * `ı`, `đ`). Ce n'est pas un oubli à réparer : compléter la table ici produirait des clés que
     * l'application publiée ne produit pas, donc des liens qui cesseraient de s'apparier entre les
     * deux versions. La table se complétera le jour où les deux côtés bougeront ensemble — ou
     * quand la version Flutter ne sera plus en service.
     *
     * `ø` mérite une mention parce que son absence est presque invisible : la table couvre
     * `0x00F2` à `0x00F6` puis reprend à `0x00F9`, sautant `0x00F7` (÷) et `0x00F8` (ø). Un titre
     * norvégien ne s'apparie donc qu'à lui-même — dans les deux versions, ce qui est la seule
     * chose qui compte ici.
     */
    private fun stripLatinDiacritic(character: Char): Char = when (character.code) {
        0x00E0, 0x00E1, 0x00E2, 0x00E3, 0x00E4, 0x00E5 -> 'a'
        0x00E6 -> 'a' // æ → a, la ligature est perdue et c'est assumé
        0x00E7 -> 'c'
        0x00E8, 0x00E9, 0x00EA, 0x00EB -> 'e'
        0x00EC, 0x00ED, 0x00EE, 0x00EF -> 'i'
        0x00F1 -> 'n'
        0x00F2, 0x00F3, 0x00F4, 0x00F5, 0x00F6 -> 'o'
        0x0153 -> 'o' // œ → o
        0x00F9, 0x00FA, 0x00FB, 0x00FC -> 'u'
        0x00FD, 0x00FF -> 'y'
        else -> character
    }
}
