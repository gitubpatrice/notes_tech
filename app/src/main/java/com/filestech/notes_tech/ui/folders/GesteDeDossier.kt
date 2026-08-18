package com.filestech.notes_tech.ui.folders

import com.filestech.notes_tech.domain.model.Folder

/**
 * Un geste de dossier qui **doit lire le contenu du coffre** avant d'agir, donc qui peut exiger un
 * déverrouillage préalable.
 *
 * ## 🔴🔴 Pourquoi ce type existe : deux gestes, une seule reprise écrite
 *
 * Retirer la protection d'un coffre et le supprimer en gardant ses notes font la même chose au
 * fond — ils **déchiffrent tout le dossier**, ce qui exige la clé, donc une session ouverte. Les
 * deux étaient écrits séparément dans `HomeRoute`, et un seul mémorisait son intention :
 *
 * | Geste | coffre fermé ⇒ | intention mémorisée |
 * |---|---|---|
 * | retirer la protection | feuille de déverrouillage | **oui** |
 * | supprimer en gardant les notes | feuille de déverrouillage | **NON** |
 *
 * Conséquence, sur un chemin destructeur : l'utilisateur confirme « supprimer ce dossier et déplacer
 * ses notes », on lui demande le secret du coffre, il le tape correctement — **et rien ne se passe**.
 * Le coffre s'ouvre, le dossier reste, le geste est abandonné sans un mot. L'application publiée
 * enchaîne les deux (`folders_drawer.dart`, `_withVaultSession`).
 *
 * **Motif du jumeau asymétrique.** La correction qui tient n'est pas d'ajouter la ligne manquante,
 * c'est de supprimer le jumeau : les deux gestes passent désormais par le même porteur et la même
 * décision, et le `when` qui les exécute est exhaustif — un troisième geste ne pourra pas naître sans
 * qu'on décide de sa reprise.
 */
internal sealed interface GesteDeDossier {

    /**
     * Le dossier concerné.
     *
     * ⚠️ Porté par le geste, et **comparé** à celui que la feuille vient d'ouvrir : la feuille de
     * déverrouillage peut avoir été ouverte pour un autre dossier entre-temps — par le
     * verrouillage automatique, par exemple. Reprendre sur la foi d'un simple booléen ferait partir
     * un geste destructeur sur le mauvais dossier.
     */
    val dossier: Folder

    data class RetirerLaProtection(override val dossier: Folder) : GesteDeDossier

    data class SupprimerEnGardantLesNotes(override val dossier: Folder) : GesteDeDossier
}

/**
 * Ce geste exige-t-il d'ouvrir le coffre d'abord ?
 *
 * ⚠️ **Se relit au moment d'agir, jamais au moment de proposer.** Un dialogue de confirmation prend
 * le temps qu'il prend, et le verrouillage automatique peut tomber pendant ce temps-là : une décision
 * prise à l'ouverture du dialogue serait périmée à sa fermeture.
 *
 * ⚠️ Un dossier **ordinaire** ne demande rien, même si l'ensemble des coffres ouverts est vide : la
 * question n'est pas « ce dossier est-il ouvert ? » mais « ce dossier a-t-il quelque chose à
 * ouvrir ? ». Confondre les deux ferait demander un secret pour un dossier qui n'en a pas.
 */
internal fun deverrouillageRequis(geste: GesteDeDossier, coffresOuverts: Set<String>): Boolean =
    geste.dossier.isVault && geste.dossier.id !in coffresOuverts
