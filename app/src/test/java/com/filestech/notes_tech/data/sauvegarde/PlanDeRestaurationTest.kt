package com.filestech.notes_tech.data.sauvegarde

import com.filestech.notes_tech.data.sauvegarde.PlanDeRestauration.Decision
import com.filestech.notes_tech.data.sauvegarde.PlanDeRestauration.DossierDuTelephone
import com.filestech.notes_tech.data.sauvegarde.RaisonDIgnorer.AUTRE_COFFRE
import com.filestech.notes_tech.data.sauvegarde.RaisonDIgnorer.CONFLIT_SCELLE
import com.filestech.notes_tech.data.sauvegarde.RaisonDIgnorer.DOSSIER_ABSENT
import com.filestech.notes_tech.data.sauvegarde.RaisonDIgnorer.EN_CLAIR_DANS_UN_COFFRE
import com.filestech.notes_tech.data.sauvegarde.RaisonDIgnorer.SCELLEE_HORS_COFFRE
import com.filestech.notes_tech.security.vault.VaultCrypto
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** What a restore does with each folder and note (D-031): the rules a forged backup meets. */
class PlanDeRestaurationTest {

    private val cleDuCoffre = ByteArray(32) { 3 }
    private val autreCle = ByteArray(32) { 4 }
    private val verificateur = VaultCrypto.verifierFor(cleDuCoffre)

    private fun dossier(id: String, parent: String? = null, coffre: CoffreSauvegarde? = null) =
        DossierSauvegarde(id, "Dossier $id", parent, null, null, 1L, 2L, coffre)

    private fun phrase(verif: ByteArray) = CoffreSauvegarde.APhrase(ByteArray(16), ByteArray(48), ByteArray(12), verif)

    private fun telephone(
        id: String,
        parent: String? = null,
        coffre: ByteArray? = null,
        estUnCoffre: Boolean = coffre != null,
    ) = id to DossierDuTelephone(id, parent, estUnCoffre, coffre)

    private fun note(id: String, dossier: String, scelle: ByteArray? = null, titre: String = "T") = NoteSauvegardee(
        id = id,
        dossierId = dossier,
        titre = titre,
        contenu = "C",
        scelle = scelle,
        versionDeChiffrement = 1,
        etiquettes = listOf("a"),
        epinglee = false,
        favorite = false,
        archivee = false,
        misALaCorbeilleLe = null,
        couleur = null,
        creeeLe = 1L,
        modifieeLe = 2L,
    )

    private fun surLeTelephone(n: NoteSauvegardee, titre: String = n.titre) = NoteDuTelephone(
        n.dossierId, titre, n.contenu, n.scelle, n.versionDeChiffrement, n.etiquettes, n.epinglee, n.favorite,
        n.archivee, n.misALaCorbeilleLe, n.couleur, n.creeeLe, n.modifieeLe,
    )

    @Test
    fun `a folder the phone has is reused, others are created parents first`() {
        val plan = PlanDeRestauration(
            listOf(
                dossier("enfant", "parent"),
                dossier("inbox"),
                dossier("parent", "inbox"),
                dossier("orphelin", "absent"),
            ),
            mapOf(telephone("inbox")),
        )

        assertThat(plan.reutilises).isEqualTo(1)
        assertThat(plan.aCreer.map { it.dossier.id to it.parentId })
            .containsExactly("parent" to "inbox", "orphelin" to null, "enfant" to "parent").inOrder()
    }

    /** R-6: a folder its own parent, a cycle, a tree too deep — the folder goes to the top level. */
    @Test
    fun `a parent that would close a cycle or go too deep is dropped`() {
        val profond = (1..40).map { dossier("p$it", if (it == 1) null else "p${it - 1}") }
        val plan = PlanDeRestauration(
            listOf(dossier("soi", "soi"), dossier("a", "b"), dossier("b", "a")) + profond,
            emptyMap(),
        )
        val parents = plan.aCreer.associate { it.dossier.id to it.parentId }

        assertThat(parents["soi"]).isNull()
        assertThat(listOf(parents["a"], parents["b"])).containsExactly(null, "a")
        // The folder that would go past the bound starts a new tree; the ones under it follow it.
        assertThat(parents["p34"]).isNull()
        assertThat(parents["p40"]).isEqualTo("p39")
        fun profondeur(id: String): Int = parents[id]?.let { 1 + profondeur(it) } ?: 1
        assertThat(profond.maxOf { profondeur(it.id) }).isAtMost(33)
    }

    @Test
    fun `a forged backup repeating a folder id is read by its first description`() {
        val plan = PlanDeRestauration(listOf(dossier("x"), dossier("x", coffre = phrase(verificateur))), emptyMap())

        assertThat(plan.aCreer).hasSize(1)
        assertThat(plan.decider(note("n", "x"), null)).isEqualTo(Decision.Ajoutee)
    }

    /** 🔴 R-4: each note against the folder it REALLY goes to. */
    @Test
    fun `a note goes in clear into an ordinary folder and sealed into its own vault, nowhere else`() {
        val plan = PlanDeRestauration(
            listOf(
                dossier("ordinaire"),
                dossier("coffre", coffre = phrase(verificateur)),
                dossier("autre", coffre = phrase(verificateur)),
                dossier("nouveau", coffre = CoffreSauvegarde.ACode(cleDuCoffre)),
            ),
            mapOf(
                telephone("ordinaire"),
                telephone("coffre", coffre = verificateur),
                telephone("autre", coffre = VaultCrypto.verifierFor(autreCle)),
            ),
        )
        val blob = ByteArray(40)

        assertThat(plan.decider(note("1", "ordinaire"), null)).isEqualTo(Decision.Ajoutee)
        assertThat(plan.decider(note("2", "ordinaire", blob), null)).isEqualTo(Decision.Ignoree(SCELLEE_HORS_COFFRE))
        assertThat(plan.decider(note("3", "coffre", blob), null)).isEqualTo(Decision.Ajoutee)
        assertThat(plan.decider(note("4", "coffre"), null)).isEqualTo(Decision.Ignoree(EN_CLAIR_DANS_UN_COFFRE))
        assertThat(plan.decider(note("5", "autre", blob), null)).isEqualTo(Decision.Ignoree(AUTRE_COFFRE))
        assertThat(plan.decider(note("6", "nouveau", blob), null)).isEqualTo(Decision.Ajoutee)
        assertThat(plan.decider(note("7", "nulle-part"), null)).isEqualTo(Decision.Ignoree(DOSSIER_ABSENT))
        assertThat(plan.coffresACodeACreer.map { it.id }).containsExactly("nouveau")
    }

    /** 🔴 A vault of the phone with no verifier is still a vault: it takes nothing, in clear least of all. */
    @Test
    fun `a vault the phone cannot check takes no note at all`() {
        val plan = PlanDeRestauration(
            listOf(dossier("abime", coffre = phrase(verificateur))),
            mapOf(telephone("abime", coffre = null, estUnCoffre = true)),
        )

        assertThat(plan.decider(note("1", "abime"), null)).isEqualTo(Decision.Ignoree(EN_CLAIR_DANS_UN_COFFRE))
        assertThat(plan.decider(note("2", "abime", ByteArray(40)), null)).isEqualTo(Decision.Ignoree(AUTRE_COFFRE))
    }

    /** R-3: nothing is overwritten. */
    @Test
    fun `a note the phone has is left, added beside it, or ignored when sealed`() {
        val plan = PlanDeRestauration(
            listOf(dossier("ordinaire"), dossier("coffre", coffre = phrase(verificateur))),
            mapOf(telephone("ordinaire"), telephone("coffre", coffre = verificateur)),
        )
        val claire = note("1", "ordinaire")
        val scellee = note("2", "coffre", ByteArray(40))

        assertThat(plan.decider(claire, surLeTelephone(claire))).isEqualTo(Decision.DejaLa)
        assertThat(plan.decider(claire, surLeTelephone(claire, titre = "Autre"))).isEqualTo(Decision.ACote)
        assertThat(plan.decider(scellee, surLeTelephone(scellee, titre = "Autre")))
            .isEqualTo(Decision.Ignoree(CONFLIT_SCELLE))
    }
}
