package com.filestech.notes_tech.ui.editor

import com.filestech.notes_tech.data.repository.NotesRepository

/**
 * Le plafond du titre d'une note, **appliqué à la saisie**.
 *
 * ## 🔴 Pourquoi il existe : sans lui, plus rien ne s'enregistrait
 *
 * `NotesRepository.saveEdits` refuse un titre de plus de [NotesRepository.TITLE_MAX_LENGTH]
 * caractères — et il refuse **le titre et le corps ensemble**, puisque c'est un seul appel. Coller un
 * paragraphe dans le champ du titre faisait donc échouer *chaque* enregistrement différé de la note,
 * indéfiniment : le texte tapé ensuite n'était écrit nulle part. La bannière le dit tant qu'on est sur
 * l'écran ; quitter l'emportait en silence, l'enregistrement au départ échouant lui aussi.
 *
 * L'application publiée n'a pas ce trou : elle pose un `LengthLimitingTextInputFormatter` sur le champ
 * (`note_editor_screen.dart:1080`), ce qui rend l'état inatteignable. C'était donc une **régression du
 * portage**, pas un écart hérité.
 *
 * ## ⚠️ Fonction pure, et testée sur la JVM — la leçon de §76
 *
 * Un test d'écran prouve ce que l'écran fait d'une saisie ; il ne balaie pas les combinaisons. Les
 * cas qui comptent ici sont des **longueurs relatives** — sous le plafond, au plafond, au-delà, et le
 * cas hérité d'un titre déjà plus long — et ils se disent en une table.
 */
object PlafondDuTitre {

    /**
     * Ce que le champ doit remonter pour une saisie [nouveau], sachant qu'il porte [actuel] — ou
     * `null` si la saisie doit être **ignorée**.
     *
     * ## ⚠️⚠️ Le plafond est `max(limite, longueur actuelle)`, jamais la limite sèche
     *
     * Un titre **déjà** plus long que la limite doit pouvoir être **raccourci**. Le tronquer à 200 à
     * la première frappe détruirait du texte de l'utilisateur pour appliquer une règle qu'il n'a pas
     * enfreinte — c'est exactement l'argument retenu pour le nom de dossier dans `05-PARITE.md`.
     * L'application publiée, elle, tronque : écart assumé, et dans le bon sens.
     *
     * ## 🔴🔴 On ne tronque QUE si rien de ce qui est déjà écrit n'y passe
     *
     * Une troncature garde le **début** du candidat. Il faut donc distinguer deux gestes que la seule
     * comparaison des longueurs confond :
     *
     * | Geste | Ce que la troncature ferait |
     * |---|---|
     * | **insertion** au bout d'un titre | rogne de la saisie — sans danger |
     * | **insertion** en tête ou au milieu | rogne la **fin du titre existant**, en silence |
     * | **remplacement** (tout sélectionner puis coller) | rogne de la saisie — sans danger |
     *
     * Le portage n'a pas la sélection sous la main — le titre est une `String`, pas un
     * `TextFieldValue` — mais il n'en a pas besoin : **la comparaison des deux chaînes suffit à
     * reconnaître une insertion pure.** Si le préfixe commun et le suffixe commun couvrent à eux deux
     * tout le texte en place, alors `nouveau` est `actuel` avec quelque chose d'inséré quelque part, et
     * ce quelque part se lit dans le préfixe. Sinon, l'utilisateur a **supprimé** du texte au passage :
     * c'est un remplacement, et rogner la fin ne détruit rien qu'il ait voulu garder.
     *
     * D'où la règle : une insertion pure ailleurs qu'à la fin est **refusée** ; tout le reste est
     * tronqué. L'utilisateur voit alors son geste sans effet, ce qui est réparable ; une fin de titre
     * disparue ne l'est pas.
     *
     * ⚠️ **Les deux relectures externes du 2026-08-17 ont chacune vu la moitié de cette règle, et la
     * seconde a rattrapé un défaut de la première.** GPT-5.2 a signalé que ma version d'origine
     * tronquait sans regarder **où** la saisie avait eu lieu, donc mangeait la fin d'un titre sur un
     * collage en tête. Le correctif — exiger `nouveau.startsWith(actuel)` — a introduit son propre
     * défaut : **tout sélectionner puis coller** cessait de fonctionner, en silence, alors que le même
     * collage dans un champ vide passait. C'est Gemini Pro qui l'a vu, sur le correctif. *Un correctif
     * de relecture est du code neuf, et il se relit.*
     *
     * ⚠️ L'application publiée a le défaut que GPT a signalé : son `LengthLimitingTextInputFormatter`
     * garde les 200 premiers caractères du nouveau texte quelle que soit la position du curseur. Écart
     * assumé de plus, dans le bon sens.
     *
     * ⚠️ Quand `actuel` est court ou répétitif, préfixe et suffixe communs peuvent **se recouvrir**, et
     * la somme surestime alors la couverture : on classe « insertion pure » un peu trop souvent, donc
     * on refuse un peu trop souvent. L'erreur va dans le sens conservateur — elle ne détruit rien — et
     * sur des caractères identiques, rogner un bout ou l'autre est de toute façon indiscernable.
     *
     * ## ⚠️ Et une troncature ne coupe jamais une paire de substituts
     *
     * `String.take` compte des unités UTF-16. Couper au milieu d'une paire — un emoji, une écriture
     * hors du plan multilingue de base — laisserait un demi-caractère, c'est-à-dire une chaîne que
     * l'affichage rend en losange et que le stockage garde telle quelle. Le dernier haut-substitut
     * orphelin est donc retiré. Relevé par la même relecture.
     *
     * ⚠️ La limite reste comptée en **unités UTF-16**, comme celle du dépôt (`title.length`) et comme
     * celle du dépôt Dart publié (`title.length` y compte aussi des unités UTF-16). Compter des
     * graphèmes ici ferait passer des titres que `saveEdits` refuserait ensuite — c'est-à-dire
     * exactement le défaut que ce plafond existe pour fermer.
     *
     * ## Les issues, dans l'ordre
     *
     * 1. la saisie tient sous le plafond : elle passe **intacte** ;
     * 2. c'est une **insertion pure** faite ailleurs qu'à la fin : elle est **refusée**, elle ne fait
     *    rien — c'est le seul cas où rogner la fin détruirait du texte déjà écrit ;
     * 3. sinon — ajout au bout, ou remplacement — on **tronque** le trop-plein, comme le publié.
     *    Coller trois cents caractères dans un champ vide en garde deux cents plutôt que rien, et
     *    taper au bout d'un titre déjà plein ne fait rien de plus que ne rien changer.
     *
     * 🔴 La deuxième issue n'était pas prévue, et c'est le test sur appareil qui l'a imposée :
     * `performTextInput` insère **au curseur**, position 0 sur un champ fraîchement composé. Une
     * frappe en tête d'un titre de 250 caractères produisait `x` + les 250 anciens, tronqué à 250 —
     * c'est-à-dire un caractère **existant mangé à la fin**, à chaque frappe, sans que rien ne le dise.
     */
    fun applique(actuel: String, nouveau: String): String? {
        val plafond = maxOf(NotesRepository.TITLE_MAX_LENGTH, actuel.length)
        if (nouveau.length <= plafond) return nouveau

        val couverture = actuel.commonPrefixWith(nouveau).length + actuel.commonSuffixWith(nouveau).length
        val insertionPure = couverture >= actuel.length
        if (insertionPure && !nouveau.startsWith(actuel)) return null

        return sansSubstitutOrphelin(nouveau.take(plafond))
    }

    /**
     * Retire un haut-substitut resté seul en fin de chaîne.
     *
     * ⚠️ `isHighSurrogate` et non `isSurrogate` : un **bas**-substitut en dernière position est
     * précédé de son haut, donc la paire est entière. Seul le haut peut se retrouver orphelin par une
     * coupe en fin de chaîne.
     */
    private fun sansSubstitutOrphelin(tronque: String): String =
        if (tronque.lastOrNull()?.isHighSurrogate() == true) tronque.dropLast(1) else tronque
}
