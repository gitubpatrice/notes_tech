package com.filestech.notes_tech.ui.editor

import com.filestech.notes_tech.data.repository.NotesRepository
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **La table de cas du plafond du titre.**
 *
 * Deuxième application de la leçon §76 : l'écran prouve ce qu'il fait d'**une** saisie, jamais ce que
 * la règle fait de toutes. Ce qui peut se tromper ici est un rapport de longueurs — sous le plafond,
 * au plafond, au-delà, et le cas hérité d'un titre déjà plus long que la limite.
 *
 * ⚠️ Le cas qui a coûté une mesure sur appareil est [un_titre_deja_au_plafond_refuse_la_saisie] : la
 * première version tronquait dans tous les cas, et rognait donc **un caractère existant à la fin** à
 * chaque frappe faite en tête d'un titre trop long. Cf. `04-PIEGES.md` §81.
 */
class PlafondDuTitreTest {

    @Test
    @DisplayName("une saisie sous le plafond passe intacte")
    fun une_saisie_sous_le_plafond_passe_intacte() {
        assertThat(PlafondDuTitre.applique(actuel = "", nouveau = "Les impots de 2024"))
            .isEqualTo("Les impots de 2024")
    }

    @Test
    @DisplayName("une saisie exactement au plafond passe intacte")
    fun une_saisie_exactement_au_plafond_passe_intacte() {
        val pile = "x".repeat(LIMITE)

        assertThat(PlafondDuTitre.applique(actuel = "", nouveau = pile)).isEqualTo(pile)
    }

    /** Le cas du collage : deux cents caractères gardés valent mieux que rien du tout. */
    @Test
    @DisplayName("un collage qui deborde est tronque a la limite")
    fun un_collage_qui_deborde_est_tronque_a_la_limite() {
        val trop = "x".repeat(LIMITE + 100)

        val ajuste = PlafondDuTitre.applique(actuel = "", nouveau = trop)

        assertThat(ajuste).hasLength(LIMITE)
        assertThat(ajuste).isEqualTo(trop.take(LIMITE))
    }

    /**
     * ⚠️ **Le trop-plein est rogné, ce qui était déjà là ne l'est pas** : un titre à un caractère de
     * la limite garde son texte et n'accepte qu'un caractère du collage.
     */
    @Test
    @DisplayName("un collage sur un titre presque plein conserve ce qui etait deja ecrit")
    fun un_collage_sur_un_titre_presque_plein_conserve_ce_qui_etait_deja_ecrit() {
        val deja = "a".repeat(LIMITE - 1)

        val ajuste = PlafondDuTitre.applique(actuel = deja, nouveau = deja + "bcdef")

        assertThat(ajuste).isEqualTo(deja + "b")
    }

    /**
     * 🔴🔴 **Un collage AILLEURS qu'à la fin est refusé, il n'est pas tronqué.**
     *
     * C'est le cas relevé par une relecture externe (GPT-5.2), et c'était un défaut réel de la
     * version précédente : une troncature garde le **début** du candidat. Sur un titre de 180
     * caractères, coller 50 caractères **en tête** produit un candidat de 230 dont la troncature à 200
     * emporte les **30 derniers caractères du titre existant**, en silence.
     *
     * Le discriminant est `nouveau.startsWith(actuel)` : si le texte en place n'est pas un préfixe du
     * candidat, la saisie n'a pas eu lieu à la fin, et rogner la fin détruirait de l'existant.
     *
     * ⚠️ Le témoin est dans le même test : **le même titre, le même collage, mais à la fin** — et là
     * la troncature est sans danger, donc elle a lieu. Sans ce second cas, l'assertion passerait sur
     * une règle qui refuserait tout collage.
     */
    @Test
    @DisplayName("un collage en TETE est refuse, le meme collage a la fin est tronque")
    fun un_collage_ailleurs_qu_a_la_fin_est_refuse() {
        val deja = "a".repeat(LIMITE - 20)
        val colle = "b".repeat(50)

        assertThat(PlafondDuTitre.applique(actuel = deja, nouveau = colle + deja)).isNull()

        val aLaFin = PlafondDuTitre.applique(actuel = deja, nouveau = deja + colle)
        assertThat(aLaFin).hasLength(LIMITE)
        assertThat(aLaFin).startsWith(deja)
    }

    /**
     * 🔴🔴 **Tout sélectionner puis coller — un REMPLACEMENT — passe, et se contente d'être tronqué.**
     *
     * C'était l'angle mort de cette table, et le défaut qu'il a laissé passer : la première version du
     * discriminant exigeait `nouveau.startsWith(actuel)`, ce qui **interdisait tout remplacement**
     * débordant — un geste courant, écraser un titre par un autre — en silence, alors que le même
     * collage dans un champ **vide** fonctionnait. Relevé par une relecture externe (Gemini Pro,
     * 2026-08-17) **sur le correctif d'une autre relecture**.
     *
     * ⚠️ Ce qui distingue ce cas d'une insertion en tête : l'utilisateur a **supprimé** le texte en
     * place. Préfixe et suffixe communs ne couvrent donc pas `actuel`, et rogner la fin ne détruit
     * rien qu'il ait voulu garder.
     */
    @Test
    @DisplayName("tout selectionner puis coller passe, et n'est que tronque")
    fun un_remplacement_complet_est_tronque_et_non_refuse() {
        val deja = "a".repeat(150)
        val remplacant = "b".repeat(LIMITE + 10)

        val ajuste = PlafondDuTitre.applique(actuel = deja, nouveau = remplacant)

        assertThat(ajuste).isEqualTo("b".repeat(LIMITE))
    }

    /**
     * 🔴 **Une insertion au MILIEU est refusée elle aussi**, et pas seulement en tête.
     *
     * C'est le cas que le seul `startsWith` laissait passer dans l'autre sens : `actuel` n'est pas un
     * préfixe du candidat, mais il n'en est pas non plus absent — il est **coupé en deux**. Rogner la
     * fin emporterait la seconde moitié du titre existant.
     *
     * ⚠️ Le discriminant qui le voit est `préfixe commun + suffixe commun >= actuel.length` : ici
     * 90 + 90 = 180, soit tout le titre. C'est donc une insertion pure, et elle n'est pas au bout.
     */
    @Test
    @DisplayName("une insertion au MILIEU est refusee, comme celle en tete")
    fun une_insertion_au_milieu_est_refusee() {
        val deja = "a".repeat(180)
        val candidat = deja.take(90) + "b".repeat(50) + deja.drop(90)

        assertThat(PlafondDuTitre.applique(actuel = deja, nouveau = candidat)).isNull()
    }

    /**
     * ⚠️ **Une troncature ne coupe jamais une paire de substituts.**
     *
     * `String.take` compte des unités UTF-16 : couper au milieu d'un emoji laisserait un
     * demi-caractère, que l'affichage rend en losange et que le stockage garde tel quel.
     *
     * Le cas est construit pour que la coupe tombe **exactement** entre les deux moitiés : le titre
     * fait `LIMITE - 1` caractères, et l'emoji ajouté en occupe deux. Le résultat doit donc faire
     * `LIMITE - 1` — l'emoji entier écarté — et non `LIMITE`.
     */
    @Test
    @DisplayName("une troncature ne coupe jamais une paire de substituts")
    fun une_troncature_ne_coupe_pas_un_emoji_en_deux() {
        val deja = "a".repeat(LIMITE - 1)

        val ajuste = PlafondDuTitre.applique(actuel = deja, nouveau = deja + EMOJI + "zzz")

        assertThat(ajuste).isEqualTo(deja)
        assertThat(ajuste!!.none { it.isSurrogate() }).isTrue()
    }

    /**
     * 🔴🔴 **Un titre déjà au plafond REFUSE la saisie au lieu de se faire rogner la fin.**
     *
     * C'est le cas trouvé par la mesure sur le S9 : `performTextInput` insère au curseur, donc en
     * tête, et une troncature aveugle mangeait le dernier caractère du titre existant — une frappe,
     * un caractère perdu, en silence.
     */
    @Test
    @DisplayName("un titre deja au plafond REFUSE la saisie au lieu de se faire rogner la fin")
    fun un_titre_deja_au_plafond_refuse_la_saisie() {
        val plein = "a".repeat(LIMITE)

        assertThat(PlafondDuTitre.applique(actuel = plein, nouveau = "x$plein")).isNull()
    }

    /**
     * 🔴 **Un titre HÉRITÉ plus long que la limite n'est ni tronqué, ni figé.**
     *
     * Ni tronqué : la saisie qui le ferait grandir est refusée, pas rognée. Ni figé : toute saisie
     * plus courte passe, donc il se répare en le raccourcissant — le seul chemin qui débloque
     * l'enregistrement sans rien détruire.
     */
    @Test
    @DisplayName("un titre herite trop long ne peut que raccourcir")
    fun un_titre_herite_trop_long_ne_peut_que_raccourcir() {
        val herite = "a".repeat(LIMITE + 50)

        assertThat(PlafondDuTitre.applique(actuel = herite, nouveau = "x$herite")).isNull()
        assertThat(PlafondDuTitre.applique(actuel = herite, nouveau = herite.dropLast(1)))
            .isEqualTo(herite.dropLast(1))
        assertThat(PlafondDuTitre.applique(actuel = herite, nouveau = "")).isEmpty()
    }

    /**
     * Le balayage : pour toutes les combinaisons de longueurs autour de la limite, l'issue ne dépasse
     * **jamais** le plafond, et ne raccourcit **jamais** le titre courant.
     *
     * ⚠️ Ce sont les deux seuls invariants de cette fonction, et les écrire ainsi vaut mieux que de
     * recopier la formule : un test qui rejoue l'implémentation ne mesure que lui-même.
     */
    @Test
    @DisplayName("balayage : quelles que soient les longueurs, l'issue ne deborde ni ne raccourcit")
    fun quelles_que_soient_les_longueurs_l_issue_ne_deborde_ni_ne_raccourcit() {
        val longueurs = listOf(0, 1, LIMITE - 1, LIMITE, LIMITE + 1, LIMITE + 50)

        for (actuelle in longueurs) {
            for (nouvelle in longueurs) {
                val actuel = "a".repeat(actuelle)
                // La saisie **prolonge** le texte en place : c'est le seul cas où une troncature est
                // permise, donc le seul où l'invariant « ne raccourcit pas » a du contenu.
                val candidat = actuel + "b".repeat(nouvelle)
                val ajuste = PlafondDuTitre.applique(actuel, candidat) ?: continue

                assertThat(ajuste.length).isAtMost(maxOf(LIMITE, actuelle))
                assertThat(ajuste.length).isAtLeast(minOf(actuelle, LIMITE))
                assertThat(candidat).startsWith(ajuste)
            }
        }
    }

    private companion object {
        const val LIMITE = NotesRepository.TITLE_MAX_LENGTH

        /** Un caractère hors du plan multilingue de base : **deux** unités UTF-16. */
        const val EMOJI = "🔐"
    }
}
