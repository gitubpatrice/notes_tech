package com.filestech.notes_tech.domain.links

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * Rejoue, sur le portage Kotlin, ce que **le vrai code Flutter a réellement produit**.
 *
 * ## Ce test n'est pas une reformulation de l'implémentation
 *
 * Les valeurs attendues ne sont pas écrites à la main d'après une lecture du Dart : elles sortent
 * d'un `flutter test` exécuté sur `notes_tech` 2.0.3 le 2026-08-13, qui a appelé
 * `BacklinksService.normalizeTitle` et `BacklinksService.extractFromContent` sur un corpus choisi
 * pour ses pièges, et écrit ce qu'elles rendaient. La procédure est dans
 * `docs/09-VECTEURS-DE-PARITE.md` — elle est reproductible.
 *
 * C'est la différence entre « je crois que Java et Dart s'accordent ici » et « je les ai fait
 * tourner tous les deux ». Trois divergences réelles sont sorties de cette confrontation, dont
 * aucune n'aurait été trouvée par relecture : la casse simple contre la casse complète, l'élagage
 * de U+0085 contre celui de U+001F, et le sigma final.
 *
 * ## Pourquoi ce test compte plus que sa taille ne le suggère
 *
 * `target_title_norm` est la colonne par laquelle un lien `[[Titre]]` retrouve sa note. Les deux
 * versions écrivent dans la **même base**. Une divergence d'un seul caractère ne lève rien : elle
 * fait disparaître des rétroliens, en silence, chez un utilisateur qui vient de migrer.
 */
@DisplayName("Parité avec l'application Flutter publiée")
class PariteAvecFlutterTest {

    /**
     * Les seuls vecteurs où ce portage **diverge sciemment** de Dart.
     *
     * La table de casse de Dart ignore quelques alphabets ajoutés à Unicode après elle ; Java les
     * traite. La liste est fermée dans les deux sens : un vecteur qui diverge sans y figurer fait
     * échouer le test, **et** un vecteur qui y figure sans plus diverger le fait échouer aussi.
     * Sans cette seconde moitié, la liste se remplirait de dispenses périmées.
     *
     * Cf. `DartTextSemantics.lowercase` pour la portée exacte de l'écart et pourquoi il est tenu
     * pour acceptable.
     */
    private val divergencesAssumees = setOf(
        pointDeCode(0x104B0), // osage majuscule, ajouté à Unicode 9.0
        pointDeCode(0x1E900), // adlam majuscule, ajouté à Unicode 9.0
    )

    @Test
    @DisplayName("normalizeTitle rend exactement ce que rend le Dart")
    fun normalisationIdentiqueAuDart() {
        val vecteurs = chargerVecteurs("normalize.tsv")
        assertThat(vecteurs).isNotEmpty()

        val ecartsInattendus = mutableListOf<String>()
        val dispensesPerimees = mutableListOf<String>()

        for (ligne in vecteurs) {
            val entree = ligne[0]
            val attendu = ligne[1]
            val obtenu = TitleNormalizer.normalize(entree)
            val diverge = obtenu != attendu
            when {
                diverge && entree !in divergencesAssumees ->
                    ecartsInattendus += "${echapper(entree)} : Dart=${echapper(attendu)} Kotlin=${echapper(obtenu)}"

                !diverge && entree in divergencesAssumees ->
                    dispensesPerimees += echapper(entree)
            }
        }

        assertAll(
            { assertThat(ecartsInattendus).isEmpty() },
            { assertThat(dispensesPerimees).isEmpty() },
        )
    }

    @Test
    @DisplayName("l'extraction des liens rend exactement ce que rend le Dart")
    fun extractionIdentiqueAuDart() {
        val vecteurs = chargerVecteurs("extract.tsv")
        assertThat(vecteurs).isNotEmpty()

        for (ligne in vecteurs) {
            val contenu = ligne[0]
            val attendus = (0 until ligne[1].toInt()).map { rang ->
                val base = 2 + rang * 3
                ExtractedLink(
                    title = ligne[base],
                    titleNorm = ligne[base + 1],
                    position = ligne[base + 2].toInt(),
                )
            }
            assertThat(WikiLinkParser.extract(contenu)).isEqualTo(attendus)
        }
    }

    /**
     * ⚠️ Ce cas mérite d'être nommé : `[[<U+001F>]]` **est un lien** dans l'application publiée.
     *
     * `String.trim()` de Kotlin l'aurait fait disparaître — U+001F y compte comme un espace, pas
     * chez Dart. Le vecteur est déjà dans `extract.tsv`, mais noyé parmi vingt-cinq autres ; isolé
     * ici, il dit à la prochaine relecture pourquoi `DartTextSemantics.trim` existe au lieu d'un
     * `trim()` ordinaire.
     */
    @Test
    @DisplayName("un titre réduit à un séparateur d'unité reste un lien, comme en Dart")
    fun leSeparateurDUniteNEstPasUnEspacePourDart() {
        val separateurDUnite = pointDeCode(0x1F)

        val liens = WikiLinkParser.extract("[[$separateurDUnite]]")

        assertThat(liens).hasSize(1)
        assertThat(liens.single().title).isEqualTo(separateurDUnite)
        // Et voici ce que le `trim()` de Kotlin en aurait fait : rien, donc pas de lien.
        assertThat(separateurDUnite.trim()).isEmpty()
    }

    private fun chargerVecteurs(nom: String): List<List<String>> {
        val flux = checkNotNull(javaClass.getResourceAsStream("/parite/$nom")) {
            "ressource /parite/$nom absente — regénérer avec docs/09-VECTEURS-DE-PARITE.md"
        }
        return flux.bufferedReader(Charsets.UTF_8).useLines { lignes ->
            lignes.filterNot { it.startsWith("#") || it.isBlank() }
                .map { ligne -> ligne.split('\t').map(::desechapper) }
                .toList()
        }
    }

    /** Inverse de l'échappement `\uXXXX` posé par le générateur Dart. */
    private fun desechapper(champ: String): String {
        if (!champ.contains("\\u")) return champ
        val out = StringBuilder(champ.length)
        var i = 0
        while (i < champ.length) {
            if (champ[i] == '\\' && champ.getOrNull(i + 1) == 'u' && i + 6 <= champ.length) {
                out.append(champ.substring(i + 2, i + 6).toInt(16).toChar())
                i += 6
            } else {
                out.append(champ[i])
                i++
            }
        }
        return out.toString()
    }

    /**
     * Construit une chaîne à partir d'un point de code.
     *
     * Les caractères en cause — séparateur d'unité, osage, adlam — ne sont pas écrits en littéral
     * dans cette source : un caractère de contrôle y serait invisible, et une paire de substitution
     * survivrait mal à un changement d'encodage. Or c'est précisément leur identité qu'on teste.
     */
    private fun pointDeCode(valeur: Int): String = String(Character.toChars(valeur))

    /** Rend une chaîne lisible dans un message d'échec, où l'invisible est justement le sujet. */
    private fun echapper(valeur: String): String = valeur.map { caractere ->
        if (caractere.code in 0x20..0x7E) {
            caractere.toString()
        } else {
            "\\u%04X".format(caractere.code)
        }
    }.joinToString("")
}
