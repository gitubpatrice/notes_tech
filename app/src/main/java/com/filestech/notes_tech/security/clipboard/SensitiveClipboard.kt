package com.filestech.notes_tech.security.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import com.filestech.notes_tech.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Le presse-papiers a refusé de rendre ou de reprendre ce qu'on y avait mis. */
class ClipboardException(message: String) : Exception(message)

/**
 * Le presse-papiers, pour du contenu qui peut être le clair d'une note de coffre.
 *
 * Trois garanties, dans l'ordre où elles comptent :
 *
 * 1. **Marquage « sensible »** (Android 13+) : les gestionnaires tiers, l'historique Knox et les
 *    aperçus système sont censés ne pas pré-afficher ni mémoriser le contenu.
 * 2. **Effacement automatique** au bout d'une minute — et seulement si le presse-papiers contient
 *    *encore* ce qu'on y a mis.
 * 3. **Effacement immédiat** sur panique, qui invalide au passage toute copie et toute minuterie.
 *
 * ## 🔴 La génération, et pourquoi UN SEUL compteur pour les copies ET les purges
 *
 * Reprise de `note_actions.dart:54`, où une relecture externe avait décrit la séquence exacte : la
 * minuterie de la copie A se réveille, l'utilisateur copie B pendant qu'elle travaille, puis A
 * reprend et remet l'état à zéro — la minuterie de B existe encore mais son état a disparu, et **le
 * contenu de B reste indéfiniment dans le presse-papiers**. Avec un compteur commun, une opération
 * périmée ne touche plus à rien.
 *
 * ## ⚠️ Ce que le portage n'a PAS eu à reprendre
 *
 * La version Flutter passait par un `MethodChannel`, donc par un `await` : deux de ses défauts
 * critiques venaient de ce que l'état du monde pouvait changer **pendant** l'appel — une purge de
 * panique tombant entre la demande de copie et son écriture, et un repli qui réinjectait le clair
 * une fraction de seconde après la purge. Ici [ClipboardManager.setPrimaryClip] est **synchrone** :
 * cette fenêtre n'existe pas. Le [Mutex] ferme la seule qui reste, entre l'écriture et l'armement
 * de la minuterie.
 *
 * De même, la version Flutter refusait un « repli non sécurisé » pour du contenu de coffre. Ce repli
 * était le `Clipboard.setData` ordinaire de Flutter, utilisé quand le canal natif manquait. Il n'a
 * pas d'équivalent ici : il n'existe qu'un seul chemin d'écriture, et il échoue ou réussit.
 *
 * ## ⚠️⚠️ La limite que la plateforme impose, et qu'il ne faut pas maquiller
 *
 * Depuis Android 10, une application qui n'a pas le focus **ne peut pas lire** le presse-papiers :
 * [ClipboardManager.getPrimaryClip] rend `null`. L'effacement différé ne peut donc pas vérifier que
 * le contenu est encore le sien quand l'application est en arrière-plan — et il **n'efface pas à
 * l'aveugle**, sous peine de détruire un secret que l'utilisateur aurait copié ailleurs entre-temps.
 *
 * Conséquence à assumer : *l'effacement automatique n'est fiable que si l'application est au premier
 * plan à l'échéance.* La version publiée a exactement la même limite, par la même cause. Le chemin
 * garanti est [annulerEtEffacer], déclenché par la panique, qui tourne toujours au premier plan.
 */
@Singleton
class SensitiveClipboard @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    private val verrou = Mutex()

    /** Incrémenté par **chaque** copie et par **chaque** purge. Cf. la note de classe. */
    private var generation = 0L

    /** Ce que cette façade a déposé, gardé pour ne réécrire que sur notre propre valeur. */
    private var texteDepose: String? = null

    private var effacementDiffere: Job? = null

    /**
     * Dépose [texte] et arme son effacement.
     *
     * @throws ClipboardException si la plateforme refuse l'écriture. Rien n'est armé dans ce cas :
     *   il n'y a rien à retirer.
     */
    suspend fun copier(texte: String) {
        verrou.withLock {
            // 🔴 **La génération ne bouge QU'APRÈS une écriture réussie.**
            //
            // Elle était incrémentée d'abord. Séquence : copie A réussie, minuterie armée ;
            // l'utilisateur copie B et l'écriture échoue ; la génération a déjà avancé, donc la
            // minuterie de A se réveille périmée et **ne fait rien**, alors que A est toujours dans
            // le presse-papiers. Plus aucune minuterie ne repasse : le clair y reste à vie. Une
            // copie qui échoue ne doit rien invalider — elle n'a rien changé au monde.
            // Relevé CONFIRMÉ par une relecture externe (GPT-5.2, 2026-08-15).
            ecrire(texte)
            val mien = ++generation
            effacementDiffere?.cancel()
            texteDepose = texte
            effacementDiffere = scope.launch {
                delay(DELAI_EFFACEMENT_MS)
                effacerSiCestEncoreLeNotre(mien)
            }
        }
    }

    /**
     * Efface **maintenant**, et invalide toute copie ou minuterie en cours. C'est l'étape
     * [com.filestech.notes_tech.security.panic.PanicStep.CLIPBOARD_CLEAR].
     *
     * ## ⚠️ Cette purge ne doit PAS avaler son propre échec
     *
     * Elle attrapait tout en silence dans la version Flutter : si l'effacement échouait, le contenu
     * restait dans le presse-papiers, l'état interne était nettoyé, aucune minuterie ne repassait, et
     * **le mode panique enregistrait l'étape comme réussie**. C'est le pire endroit pour un
     * « au mieux » muet. Une reprise, puis on lève : l'écran de fin n'annoncera pas un effacement
     * complet.
     *
     * ⚠️ **L'instantané n'est pas oublié tant que la purge n'a pas abouti** — sans lui, plus rien ne
     * saurait quel texte retirer, et aucune minuterie ne repasserait.
     *
     * @throws ClipboardException si le presse-papiers n'a pas pu être vidé.
     */
    suspend fun annulerEtEffacer() {
        verrou.withLock {
            val mien = ++generation
            effacementDiffere?.cancel()
            effacementDiffere = null
            val aRetirer = texteDepose

            repeat(NB_ESSAIS) { essai ->
                try {
                    if (viderEtVerifier()) {
                        texteDepose = null
                        return
                    }
                    Timber.w("purge du presse-papiers : du texte subsiste après l'essai %d", essai + 1)
                } catch (e: Exception) {
                    Timber.w(e, "purge du presse-papiers : essai %d", essai + 1)
                }
            }

            // Échec confirmé : on garde de quoi réessayer, et on réarme plutôt que d'abandonner le
            // texte sur place.
            texteDepose = aRetirer
            rearmer(mien)
            throw ClipboardException("presse-papiers non vidé : le contenu copié peut subsister")
        }
    }

    /**
     * L'échéance d'une copie.
     *
     * ⚠️ **Ne touche à rien si [generation] a bougé** : une copie plus récente ou une purge est
     * passée, et son état ne nous appartient pas.
     */
    private suspend fun effacerSiCestEncoreLeNotre(generationAttendue: Long) {
        verrou.withLock {
            if (generationAttendue != generation) return
            val notre = texteDepose ?: return

            when (val etat = lire()) {
                is EtatDuPressePapiers.Texte ->
                    if (etat.valeur == notre) {
                        try {
                            // 🔴 **Vérifier ici AUSSI.** L'échéance se contentait de l'absence
                            // d'exception, alors que la purge de panique relisait. Deux chemins qui
                            // effacent la même chose, un seul qui vérifie : jumeau asymétrique.
                            // Un `vider()` ignoré en silence laissait le clair dans le presse-papiers
                            // **et** le service l'oubliait — plus aucune minuterie ne repassait.
                            // Relevé CONFIRMÉ par une relecture externe (Gemini, 2026-08-15).
                            if (viderEtVerifier()) texteDepose = null else rearmer(generationAttendue)
                        } catch (e: Exception) {
                            Timber.w(e, "effacement différé du presse-papiers")
                            rearmer(generationAttendue)
                        }
                    } else {
                        // Le presse-papiers porte un autre texte : ce n'est plus notre affaire.
                        texteDepose = null
                    }

                // Lecture réussie, mais l'élément n'est pas du texte — une image, une URI. Notre
                // valeur n'y est donc plus, et il n'y a rien à effacer.
                EtatDuPressePapiers.SansTexte -> texteDepose = null

                // ⚠️ Illisible ≠ absent. En arrière-plan, Android rend `null` — cf. la note de
                // classe. Effacer ici détruirait peut-être le secret d'une autre application ; on
                // repasse, en gardant l'instantané.
                EtatDuPressePapiers.Illisible -> rearmer(generationAttendue)
            }
        }
    }

    /**
     * Repasse plus tard, **sans jamais oublier le texte tant que son sort n'est pas connu**.
     *
     * ## 🔴 Ce réarmement était borné à un seul essai, et ça fuyait
     *
     * Version précédente : au second passage infructueux, elle posait `texteDepose = null` et
     * n'armait plus rien. Séquence — copie, l'utilisateur bascule aussitôt sur une autre application,
     * la lecture rend `null` à 60 s puis encore à 120 s, et là le service **oublie** le texte. La
     * minuterie est morte, mais **le clair est toujours dans le presse-papiers système**, où
     * n'importe quelle application le trouvera. Relevé CONFIRMÉ par une relecture externe
     * (Gemini, 2026-08-15).
     *
     * ⚠️ **Le raisonnement qui l'avait produit était faux, et c'est lui qu'il faut retenir** : il
     * disait « insister maintiendrait le texte en mémoire ici, ce qu'on cherche à éviter ». Mais la
     * mémoire de l'application est isolée par le bac à sable ; le presse-papiers, lui, est **public**.
     * Garder le texte pour pouvoir l'effacer plus tard est strictement moins dangereux que
     * l'abandonner là où tout le monde peut le lire. *Une purge de la copie sécurisée avait été
     * préférée à une purge de la copie exposée.*
     *
     * Il n'y a donc plus de borne. Les seules sorties sont l'effacement confirmé et le constat que le
     * presse-papiers porte désormais autre chose — deux cas où [texteDepose] est remis à zéro par
     * [effacerSiCestEncoreLeNotre]. Le coût est une coroutine qui se réveille chaque minute tant que
     * l'application vit ; elle disparaît avec le processus, et un processus mort n'a de toute façon
     * plus aucun moyen d'effacer quoi que ce soit.
     */
    /*
     * ⚠️ **À n'appeler qu'en détenant [verrou]** : cette fonction touche à [effacementDiffere] sans
     * le prendre elle-même. Elle ne peut pas le prendre — ses deux appelants le détiennent déjà, et
     * le [Mutex] n'est pas réentrant : ce serait un interblocage. Relevé comme risque de maintenance
     * par une relecture externe (GPT-5.2, 2026-08-15) : la contrainte n'existe que dans ce
     * commentaire, un futur appel hors section critique la briserait sans que rien ne le signale.
     */
    private fun rearmer(generationAttendue: Long) {
        effacementDiffere = scope.launch {
            delay(DELAI_EFFACEMENT_MS)
            effacerSiCestEncoreLeNotre(generationAttendue)
        }
    }

    /**
     * Vide, **relit**, et dit si le presse-papiers ne porte plus de texte.
     *
     * ⚠️ Un `vider()` qui ne lève pas ne prouve pas que le presse-papiers a changé : une
     * implémentation constructeur peut l'ignorer en silence. Déclarer l'effacement acquis sur la
     * seule absence d'exception, c'est annoncer ce qu'on n'a pas vérifié.
     *
     * ⚠️⚠️ **Le test porte sur « du texte non vide subsiste », pas sur « c'est encore le nôtre ».**
     * La comparaison à notre instantané ne marche pas quand il n'y en a pas — après un redémarrage
     * du processus, le singleton a perdu son état alors que le presse-papiers, lui, a gardé le
     * contenu de la session précédente. Le contrôle passait alors **toujours**, et la panique
     * déclarait l'étape réussie sans rien avoir regardé. Relevé CONFIRMÉ par une relecture externe
     * (Gemini, 2026-08-15).
     *
     * ⚠️ **Une lecture impossible n'est pas une preuve d'échec.** Sur API 28+, `clearPrimaryClip`
     * fait justement rendre `null` à la lecture suivante : traiter ce cas comme un échec ferait
     * échouer **toutes** les purges réussies. On rend donc `true` — l'effacement n'est pas réfuté —
     * et l'appelant garde par ailleurs son instantané tant que rien ne l'a confirmé.
     */
    private fun viderEtVerifier(): Boolean {
        vider()
        val etat = lire()
        return !(etat is EtatDuPressePapiers.Texte && etat.valeur.isNotEmpty())
    }

    private fun presse(): ClipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: throw ClipboardException("presse-papiers indisponible")

    private fun ecrire(texte: String) {
        val clip = ClipData.newPlainText(ETIQUETTE, texte)
        // Android 13 introduit le marqueur qui empêche l'aperçu et signale aux gestionnaires de ne
        // pas historiser. En dessous, il n'existe pas : le contenu est copié sans lui, comme dans
        // l'application publiée, et l'effacement d'une minute reste la seule protection.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        try {
            presse().setPrimaryClip(clip)
        } catch (e: ClipboardException) {
            throw e
        } catch (e: Exception) {
            throw ClipboardException("copie refusée par la plateforme : ${e::class.java.simpleName}")
        }
    }

    /**
     * Ce que la lecture du presse-papiers permet de conclure.
     *
     * 🔴 **Un `String?` confondait deux situations opposées** : « je n'ai pas pu lire » et « j'ai lu,
     * et ce n'est pas du texte ». Les traiter pareil coûtait cher depuis que la relance est sans
     * borne — un presse-papiers contenant une image aurait fait tourner une coroutine indéfiniment
     * en gardant le clair en mémoire, pour un texte qui n'y était déjà plus. Relevé CONFIRMÉ par une
     * relecture externe (GPT-5.2, 2026-08-15).
     */
    private sealed interface EtatDuPressePapiers {
        /** Lecture refusée, ou presse-papiers vide : on ne peut rien conclure. */
        data object Illisible : EtatDuPressePapiers

        /** Lecture réussie, mais l'élément ne porte pas de texte. */
        data object SansTexte : EtatDuPressePapiers

        data class Texte(val valeur: String) : EtatDuPressePapiers
    }

    /**
     * ⚠️ **Un `primaryClip` nul est classé [EtatDuPressePapiers.Illisible], même quand il signifie
     * « vide ».** La plateforme ne distingue pas les deux, et se tromper dans ce sens-là ne coûte
     * qu'une coroutine qui se réveille pour rien ; se tromper dans l'autre ferait oublier un texte
     * qui est peut-être encore exposé. Le doute penche du côté qui ne fuit pas.
     */
    private fun lire(): EtatDuPressePapiers = try {
        val clip = presse().primaryClip
        when {
            clip == null -> EtatDuPressePapiers.Illisible
            clip.itemCount == 0 -> EtatDuPressePapiers.SansTexte
            else -> clip.getItemAt(0).text?.toString()
                ?.let { EtatDuPressePapiers.Texte(it) }
                ?: EtatDuPressePapiers.SansTexte
        }
    } catch (e: Exception) {
        Timber.w(e, "lecture du presse-papiers")
        EtatDuPressePapiers.Illisible
    }

    /**
     * ⚠️ `clearPrimaryClip` n'existe qu'à partir de l'API 28. En dessous, y poser une chaîne vide
     * est tout ce que la plateforme permet : le presse-papiers n'est alors pas vide, il contient une
     * chaîne vide. C'est suffisant — le texte de la note n'y est plus.
     */
    private fun vider() {
        val presse = presse()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            presse.clearPrimaryClip()
        } else {
            presse.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    private companion object {
        /** Une minute, comme `note_actions.dart:30`. */
        const val DELAI_EFFACEMENT_MS = 60_000L
        const val NB_ESSAIS = 2

        /** Visible dans certains gestionnaires de presse-papiers ; celle de l'application publiée. */
        const val ETIQUETTE = "note"
    }
}
