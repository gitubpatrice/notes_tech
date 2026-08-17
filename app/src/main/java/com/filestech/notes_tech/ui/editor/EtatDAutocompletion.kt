package com.filestech.notes_tech.ui.editor

import com.filestech.notes_tech.domain.links.TitleNormalizer
import com.filestech.notes_tech.domain.model.Note

/**
 * Les titres proposés pour un `[[…]]`, **et la saisie à laquelle ils répondent**.
 *
 * ## 🔴🔴 Pourquoi la réponse porte sa question — le mécanisme de §76, appliqué ici
 *
 * Les suggestions passent par un freinage de 120 ms. Pendant cette fenêtre, la liste est **vide** —
 * volontairement vidée à chaque frappe, parce qu'une liste **périmée** laissait toucher une
 * proposition appartenant à la requête précédente, et insérer un lien vers une autre note que celle
 * cherchée.
 *
 * Mais une liste vide « parce qu'on n'a pas encore cherché » et une liste vide « parce qu'il n'y a
 * rien » sont deux choses que rien ne distinguait — et la feuille en tirait deux conclusions fausses :
 *
 * 1. elle proposait **« Créer *X* »** avant d'avoir cherché, donc pour un titre qui existe peut-être ;
 * 2. sa validation au clavier consulte cette même liste pour décider **lier ou créer**, et une liste
 *    vide la faisait toujours **créer**. Valider dans les 120 ms qui suivent une frappe produisait
 *    donc le doublon que cette garde existe précisément pour empêcher.
 *
 * ⚠️ **Ce n'est pas une régression de parité** : l'application publiée propose « Créer » dans la même
 * fenêtre (`snap.data ?? const <Note>[]`) et sa validation crée **toujours**, sans consulter quoi que
 * ce soit — son commentaire en fait même une garantie d'interface. C'est la divergence **délibérée**
 * du portage — lier plutôt que créer un homonyme — qui était **incomplètement efficace**.
 *
 * ⚠️ [pour] est **nullable**, et c'est la même raison qu'au §76 : `null` veut dire « aucune réponse,
 * pour aucune requête ». Une chaîne vide, elle, est une **vraie** réponse — celle d'une saisie vide,
 * à laquelle on répond immédiatement et sans chercher.
 */
data class SuggestionsDeLien(val pour: String? = null, val titres: List<Note> = emptyList())

/**
 * Ce que la feuille d'autocomplétion doit montrer, dérivé d'une saisie et de la réponse en main.
 *
 * Fonction **pure**, comme `etatDeRecherche` : c'est la seule partie qui décide, et elle décide sur
 * des rapports entre deux chaînes et une liste — ce qu'une table de cas dit mieux qu'un écran.
 */
internal data class EtatDAutocompletion(
    /** La saisie élaguée : c'est elle qu'on lie ou qu'on crée. */
    val requete: String,
    val titres: List<Note>,
    /** Une requête est posée, sa réponse n'est pas arrivée. */
    val enAttente: Boolean,
    val proposerLaCreation: Boolean,
    /** « Aucun résultat » — une affirmation, donc réservée au cas où l'on a bien cherché. */
    val annoncerAucunResultat: Boolean,
)

internal fun etatDAutocompletion(saisie: String, reponse: SuggestionsDeLien): EtatDAutocompletion {
    val requete = saisie.trim()
    // ⚠️ Comparé à la saisie **brute**, celle qui a été transmise au ViewModel — pas à sa version
    // élaguée. Comparer deux choses qui n'ont pas fait le même chemin est le moyen le plus sûr de
    // croire périmée une réponse qui ne l'est pas.
    val repondALaSaisie = reponse.pour == saisie
    val enAttente = requete.isNotEmpty() && !repondALaSaisie
    val proposerLaCreation = requete.isNotEmpty() &&
        repondALaSaisie &&
        aucuneCorrespondanceExacte(reponse.titres, requete)
    return EtatDAutocompletion(
        requete = requete,
        titres = reponse.titres,
        enAttente = enAttente,
        proposerLaCreation = proposerLaCreation,
        // ⚠️ **Ni pendant l'attente, ni quand on propose de créer.** Le premier dirait « aucun » avant
        // d'avoir cherché — la faute exacte de §76 ; le second afficherait « Aucun résultat » au-dessus
        // d'une entrée qui en est un.
        annoncerAucunResultat = reponse.titres.isEmpty() && !proposerLaCreation && !enAttente,
    )
}

/**
 * Ce que la validation au clavier doit faire.
 *
 * ⚠️ [Attendre] n'est pas un refus : la feuille retient la validation et l'applique dès que la réponse
 * arrive. Ne rien faire du tout ferait de la touche « Entrée » un geste sans effet — et la refaire
 * agir en créant serait le doublon d'origine.
 */
internal sealed interface DecisionDeValidation {
    /** Rien à valider : la saisie est vide. */
    data object Rien : DecisionDeValidation

    /** La réponse ne répond pas encore à cette saisie. */
    data object Attendre : DecisionDeValidation

    /** Ce titre existe déjà : on le lie, plutôt que d'en créer un homonyme. */
    data class Lier(val titre: String) : DecisionDeValidation

    data class Creer(val titre: String) : DecisionDeValidation
}

/**
 * ⚠️ **La divergence délibérée avec l'application publiée vit ici**, et elle n'est tenable que parce
 * que [DecisionDeValidation.Attendre] existe : décider « lier ou créer » sur une réponse périmée
 * revient à créer à chaque fois, c'est-à-dire à ne pas diverger du tout.
 *
 * ⚠️ Le titre rendu par [DecisionDeValidation.Lier] est celui de la **note trouvée**, pas celui qui a
 * été tapé : l'appariement est insensible à la casse et aux diacritiques, et écrire `[[impots]]` vers
 * une note « Impôts » ferait un lien qui s'affiche autrement qu'elle s'appelle.
 *
 * ## ⚠️⚠️ C'est un « au mieux », pas une garantie — et la condition d'échec est chiffrable
 *
 * La décision ne consulte que **les suggestions affichées**, c'est-à-dire au plus huit titres.
 * `NotesRepository.suggestTitles` sur-échantillonne 32 candidats **triés par date de modification**,
 * les filtre, puis **tronque à huit**. Il suffit donc de **huit notes** dont le titre commence par la
 * saisie et qui ont été modifiées plus récemment que l'homonyme exact pour que celui-ci ne soit pas
 * dans la liste — et la validation créera un doublon.
 *
 * ⚠️ **Ce n'est pas réparable à ce niveau, et pas davantage un cran plus bas.** Un contrôle d'existence
 * exact demanderait une requête que la base ne sait pas faire : `LOWER()` de SQLite ignore les
 * diacritiques, donc `LIKE 'impots%'` ne trouve pas « Impôts ». C'est précisément pourquoi
 * l'appariement est normalisé **en Kotlin**, après un sur-échantillonnage — le montage est hérité de
 * l'application publiée.
 *
 * ⚠️ **Et l'application publiée est strictement pire** : son `_onSubmit` ne consulte rien et crée
 * **toujours**. Cette garde reste donc un gain, à condition de ne pas la lire comme une promesse.
 * Relevé par une relecture externe (GPT-5.2, 2026-08-17), vérifié dans le dépôt, **non corrigé** —
 * cf. `04-PIEGES.md` §84.
 */
internal fun decisionDeValidation(etat: EtatDAutocompletion): DecisionDeValidation = when {
    etat.requete.isEmpty() -> DecisionDeValidation.Rien
    etat.enAttente -> DecisionDeValidation.Attendre
    else -> {
        val existante = etat.titres.firstOrNull { correspondExactement(it.title, etat.requete) }
        if (existante != null) DecisionDeValidation.Lier(existante.title) else DecisionDeValidation.Creer(etat.requete)
    }
}

private fun aucuneCorrespondanceExacte(suggestions: List<Note>, requete: String): Boolean =
    suggestions.none { correspondExactement(it.title, requete) }

private fun correspondExactement(titre: String, requete: String): Boolean =
    TitleNormalizer.normalize(titre) == TitleNormalizer.normalize(requete)
