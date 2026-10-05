package com.filestech.notes_tech.ui.voice

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **Où mène un appui sur le micro, selon ce qui est installé et ce qui est accordé ?**
 *
 * Trois issues : installer le modèle, demander la permission, démarrer. Elles étaient écrites en
 * `if` imbriqués dans le corps d'un rappel Compose — donc hors d'atteinte de toute mesure, puisqu'il
 * aurait fallu Hilt, un magasin de modèles et une permission réelle pour savoir laquelle partait.
 *
 * 🔴 **Le défaut que ces quatre cas verrouillent** : sans modèle installé, l'appui affichait « Aucun
 * modèle de transcription installé. » et n'allait nulle part. L'écran d'installation existait, mais
 * seulement depuis les réglages, et rien dans l'éditeur ne l'indiquait. L'application publiée ouvre
 * cet écran directement (`voice_record_button.dart:33-38`).
 *
 * ⚠️⚠️ **Ce que ce fichier ne mesure pas** : que le rappel de `rememberControleurDeDictee` passe
 * bien par [gesteDuMicro], ni que `NotesTechNavHost` mène `onInstallerLaDictee` à
 * `Destination.VoiceSetup`. Le prouver demanderait Hilt, une permission d'appareil et un graphe de
 * navigation monté. Ce qui protège cette partie est structurel — un seul appel, un `when` exhaustif
 * — et non mesuré. *Le dire vaut mieux que de laisser croire le contraire* (même limite qu'au §91).
 *
 * 🔴 **Le filet contre la re-divergence est [etiquette]** : une quatrième issue ajoutée à
 * [GesteDuMicro] fera **échouer la compilation de ce fichier** tant que personne n'aura décidé de sa
 * destination.
 */
class GesteDuMicroTest {

    /**
     * ⚠️ `when` **exhaustif sur l'interface scellée**, et c'est lui le filet — pas les cas.
     */
    private fun etiquette(geste: GesteDuMicro): String = when (geste) {
        GesteDuMicro.InstallerLeModele -> "installer le modele"
        GesteDuMicro.DemanderLaPermission -> "demander la permission"
        GesteDuMicro.Demarrer -> "demarrer"
    }

    @Test
    @DisplayName("sans modele, on va l'installer — et on ne demande PAS le micro")
    fun sans_modele_on_va_l_installer() {
        val geste = gesteDuMicro(modeleInstalle = false, permissionAccordee = true)

        assertThat(etiquette(geste)).isEqualTo("installer le modele")
    }

    /**
     * 🔴 **Le cœur de l'ordre entre les deux contrôles.** Sans modèle *et* sans permission, c'est
     * l'installation qui gagne : demander le micro pour une dictée qui ne peut pas aboutir, c'est
     * faire refuser durablement une permission dont on n'avait pas encore l'usage — et un refus
     * définitif ne se reprend que dans les réglages système.
     */
    @Test
    @DisplayName("sans modele NI permission, l'installation passe AVANT la demande de micro")
    fun sans_modele_ni_permission_l_installation_gagne() {
        val geste = gesteDuMicro(modeleInstalle = false, permissionAccordee = false)

        assertThat(etiquette(geste)).isEqualTo("installer le modele")
    }

    @Test
    @DisplayName("modele present mais micro non accorde : on demande")
    fun modele_present_sans_permission_on_demande() {
        val geste = gesteDuMicro(modeleInstalle = true, permissionAccordee = false)

        assertThat(etiquette(geste)).isEqualTo("demander la permission")
    }

    /**
     * ⚠️ **Une permission déjà accordée ne se redemande pas.** Rouvrir une boîte système à chaque
     * dictée est le meilleur moyen de faire refuser celle-là aussi.
     */
    @Test
    @DisplayName("modele present et micro accorde : on demarre, sans rouvrir de boite systeme")
    fun tout_est_pret_on_demarre() {
        val geste = gesteDuMicro(modeleInstalle = true, permissionAccordee = true)

        assertThat(etiquette(geste)).isEqualTo("demarrer")
    }

    /**
     * ⚠️ Les quatre combinaisons, d'un coup : une table qui rendrait deux fois la même issue là où
     * elle devrait différer passerait les cas isolés ci-dessus un par un — chacun est vrai
     * séparément — et tomberait ici.
     */
    @Test
    @DisplayName("les quatre combinaisons donnent trois issues distinctes")
    fun les_quatre_combinaisons() {
        val table = listOf(false, true).flatMap { modele ->
            listOf(false, true).map { permission ->
                etiquette(gesteDuMicro(modeleInstalle = modele, permissionAccordee = permission))
            }
        }

        assertThat(table).containsExactly(
            "installer le modele",
            "installer le modele",
            "demander la permission",
            "demarrer",
        ).inOrder()
    }
}
