package com.filestech.notes_tech.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.notes_tech.R
import com.filestech.notes_tech.data.prefs.AppSettings
import com.filestech.notes_tech.data.prefs.LegacyPreferences
import com.filestech.notes_tech.data.prefs.LocalePreference
import com.filestech.notes_tech.data.prefs.ThemePreference
import com.filestech.notes_tech.domain.model.NoteSortMode
import com.filestech.notes_tech.ui.actionnablesSansNom
import com.filestech.notes_tech.ui.actionsPerduesALaFusion
import com.filestech.notes_tech.ui.common.libelleDeTri
import com.filestech.notes_tech.ui.secure.LocalSecureWindow
import com.filestech.notes_tech.ui.secure.SecureWindowController
import com.filestech.notes_tech.ui.theme.NotesTechTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Ce que les réglages annoncent, et ce qu'ils font de chaque choix.**
 *
 * Quatrième ligne d'écran de `docs/05-PARITE.md`, et le plus gros : 802 lignes côté publié, vingt
 * lignes de réglages, quatre dialogues de choix, un export et le **mode panique**.
 *
 * ## 🔴 Pourquoi le découpage sans état compte doublement ici
 *
 * Le mode panique détruit irrémédiablement les notes. L'exercer à travers le vrai `PanicViewModel`
 * effacerait la base du S9 **et le modèle vocal de 57 Mo** — exactement le sinistre de
 * `04-PIEGES.md` §72, mais volontaire. `SettingsScreen` ne reçoit qu'un booléen et un rappel : la
 * confirmation, son annulation et l'affichage de la progression se mesurent donc **sans rien
 * détruire**, et c'est le seul moyen de les mesurer du tout.
 *
 * ## ⚠️ Ce que ce fichier NE couvre pas, et qui reste dans la `Route`
 *
 * L'annonce du changement de langue et la recréation de l'activité, qui demandent `LocalActivity` et
 * `LocalView`, et le recouvrement de panique, qui appelle `exitProcess`. L'écran remonte le choix de
 * langue ; ce qu'on en fait est vérifié par lecture, pas ici.
 */
@RunWith(AndroidJUnit4::class)
class ReglagesTest {

    @get:Rule
    val regle = createAndroidComposeRule<ComponentActivity>()

    private val retours = mutableListOf<Unit>()
    private val themes = mutableListOf<ThemePreference>()
    private val langues = mutableListOf<LocalePreference>()
    private val tris = mutableListOf<NoteSortMode>()
    private val fenetresProtegees = mutableListOf<Boolean>()
    private val delais = mutableListOf<Int>()
    private val paniques = mutableListOf<Unit>()
    private val aPropos = mutableListOf<Unit>()
    private val dictees = mutableListOf<Unit>()

    private fun texte(id: Int): String = regle.activity.getString(id)

    private val etatCourant = mutableStateOf(SettingsUiState())
    private val paniqueEnCours = mutableStateOf(false)

    private var pose = false

    private fun poser(etat: SettingsUiState = SettingsUiState(), panique: Boolean = false) {
        if (pose) {
            regle.runOnIdle {
                etatCourant.value = etat
                paniqueEnCours.value = panique
            }
            regle.waitForIdle()
            return
        }
        etatCourant.value = etat
        paniqueEnCours.value = panique
        pose = true
        regle.setContent {
            // ⚠️⚠️ **Sans ce fournisseur, le dialogue de panique lève** — et c'est voulu :
            // `LocalSecureWindow` n'a **aucun défaut**, parce qu'un contrôleur muet ferait passer un
            // écran non protégé pour un écran protégé. Mes deux premiers tests de panique ont échoué
            // là-dessus, avec le message exact du dépôt. Le garde-fou fait son travail.
            //
            // Le contrôleur est **réel** : `SecureWindowGuard` n'appelle que `force()` et `release()`,
            // qui ne touchent qu'un compteur en mémoire. ⚠️ Ce qu'il ne vérifie **pas** : que le
            // dialogue pose bien `FLAG_SECURE`. `activeNow()` mêle le compteur au réglage de
            // l'utilisateur, donc le mesurer demanderait d'écrire dans les préférences réelles de
            // l'application — ce que la leçon §72 interdit à un test.
            CompositionLocalProvider(LocalSecureWindow provides controleurDeFenetre()) {
                NotesTechTheme {
                    SettingsScreen(
                        state = etatCourant.value,
                        paniqueEnCours = paniqueEnCours.value,
                        onBack = { retours += Unit },
                        onTheme = { themes += it },
                        onLocale = { langues += it },
                        onSort = { tris += it },
                        onSecureWindow = { fenetresProtegees += it },
                        onAutoLock = { delais += it },
                        onPanic = { paniques += Unit },
                        onOpenAbout = { aPropos += Unit },
                        onOpenVoiceSetup = { dictees += Unit },
                    )
                }
            }
        }
        regle.waitForIdle()
    }

    /**
     * Le vrai contrôleur, bâti sur le vrai contexte : sa chaîne de construction ne demande qu'un
     * `Context`, et rien de ce que `SecureWindowGuard` en appelle ne lit ni n'écrit de préférence.
     */
    private fun controleurDeFenetre(): SecureWindowController =
        SecureWindowController(AppSettings(LegacyPreferences(regle.activity)))

    /**
     * 🔴🔴 **Le balayage, sur l'écran qui compte le plus d'actionnables du portage.**
     *
     * Vingt lignes de réglages, toutes bâties sur un `ListItem` dont l'icône porte
     * `contentDescription = null` et dont le clic est posé sur le **modificateur du `ListItem`**, pas
     * sur un composant bouton. C'est précisément la forme qui a produit §74 sur la carte de note : si
     * `clickable` ne fusionnait pas ses descendants, chacune de ces lignes serait un actionnable
     * **sans nom**.
     *
     * ⚠️ L'écran défile : le balayage ne voit que les nœuds composés. `verticalScroll` compose tout
     * son contenu, contrairement à une `LazyColumn` — c'est ce qui rend ce balayage exhaustif ici, et
     * ce qu'il faudra vérifier écran par écran ailleurs.
     */
    @Test
    fun aucun_element_actionnable_des_reglages_n_est_sans_nom() {
        poser()

        assertThat(regle.actionnablesSansNom()).isEmpty()
    }

    /**
     * 🔴 **Le motif INVERSE, sur l'écran qui compte vingt lignes actionnables.**
     *
     * Le filet de régression de §74 : aucune action ne doit disparaître au nœud fusionné qui l'annonce.
     * Sur cet écran, chaque ligne de réglage est un `ListItem` dont le clic est posé sur le
     * modificateur — donc exactement la forme où l'action et le nom peuvent se retrouver sur deux
     * nœuds différents.
     */
    @Test
    fun aucune_action_des_reglages_n_est_perdue_a_la_fusion() {
        poser()

        assertThat(regle.actionsPerduesALaFusion()).isEmpty()
    }

    /** Le même balayage, mode panique **en cours** : le rouage remplace le chevron. */
    @Test
    fun aucun_element_actionnable_n_est_sans_nom_pendant_une_panique() {
        poser(panique = true)

        assertThat(regle.actionnablesSansNom()).isEmpty()
    }

    /**
     * 🔴 **La langue avant le thème**, dans cet ordre, comme `settings_screen.dart:61`.
     *
     * Le portage les avait inversés. ⚠️ L'ordre est vérifié par les **coordonnées** des deux nœuds et
     * non par leur ordre de parcours : c'est ce que l'utilisateur voit, et un ordre de parcours peut
     * différer de l'ordre visuel.
     */
    @Test
    fun la_langue_est_annoncee_avant_le_theme() {
        poser()

        val langue = regle.onNodeWithText(texte(R.string.settings_language)).fetchSemanticsNode()
        val theme = regle.onNodeWithText(texte(R.string.settings_theme)).fetchSemanticsNode()

        assertThat(langue.boundsInRoot.top).isLessThan(theme.boundsInRoot.top)
    }

    /**
     * Chaque ligne de choix montre **la valeur courante** en sous-titre, et pas un libellé figé.
     *
     * ⚠️ C'est le contrôle qui manquait au défaut du 2026-08-14 sur le menu de tri : quatre entrées y
     * partageaient deux libellés, et seule la position du bouton radio disait ce qu'on avait choisi.
     * Ici on vérifie que la ligne **rend** le choix, pour deux valeurs différentes du même réglage.
     */
    @Test
    fun chaque_ligne_de_choix_affiche_la_valeur_courante() {
        poser(SettingsUiState(theme = ThemePreference.DARK, locale = LocalePreference.ENGLISH))
        regle.onNodeWithText(texte(R.string.settings_theme_dark)).assertIsDisplayed()
        regle.onNodeWithText(texte(R.string.settings_language_en)).assertIsDisplayed()

        poser(SettingsUiState(theme = ThemePreference.LIGHT, locale = LocalePreference.FRENCH))
        regle.onNodeWithText(texte(R.string.settings_theme_light)).assertIsDisplayed()
        regle.onNodeWithText(texte(R.string.settings_language_fr)).assertIsDisplayed()
        regle.onNodeWithText(texte(R.string.settings_theme_dark)).assertDoesNotExist()
    }

    /**
     * 🔴 **« Jamais » n'est pas « 0 minute ».** `0` est un choix légitime d'auto-verrouillage, et
     * l'écran doit le nommer plutôt que de laisser le pluriel rendre « 0 minutes ».
     *
     * ⚠️ Le libellé attendu est reconstruit depuis les ressources avec le **même** nombre : écrire
     * « 15 minutes » en dur ferait passer ce test pour de mauvaises raisons dès que la langue de
     * l'appareil change, et la forme plurielle du français ne se devine pas.
     */
    @Test
    fun un_delai_nul_s_annonce_jamais_et_pas_zero_minute() {
        val quinze = regle.activity.resources
            .getQuantityString(R.plurals.settings_vault_auto_lock_minutes, 15, 15)

        poser(SettingsUiState(vaultAutoLockMinutes = 0))
        regle.onNodeWithText(texte(R.string.settings_vault_auto_lock_never)).assertIsDisplayed()

        poser(SettingsUiState(vaultAutoLockMinutes = 15))
        regle.onNodeWithText(quinze).assertIsDisplayed()
        regle.onNodeWithText(texte(R.string.settings_vault_auto_lock_never)).assertDoesNotExist()
    }

    /**
     * 🔴🔴 **Un SEUL nœud basculable, qui porte le libellé ET l'état** — c'est la propriété de §77.
     *
     * Avant correctif, l'écran en portait deux : le `ListItem` avec le texte, et un `Switch` **sans
     * nom** en `trailingContent`. Ce test l'interdit de trois façons à la fois, et chacune tomberait
     * séparément si le correctif était défait :
     *
     * 1. `assertCountEquals(1)` — deux cibles pour un seul réglage, dont une sous le minimum
     *    accessible, c'est exactement ce que l'idiome `onCheckedChange = null` évite ;
     * 2. le nœud basculable **porte le libellé** de la ligne : sans ça il s'annonce « interrupteur,
     *    activé » sans dire de quoi, ce qui était le défaut mesuré ;
     * 3. son état suit `state.secureWindow`, et le clic remonte **l'inverse**.
     *
     * ⚠️ Vérifié par `assertIsOn` / `assertIsOff`, donc par l'état **annoncé** — pas par une capture
     * d'écran. Un interrupteur dessiné à droite mais annoncé « désactivé » est le défaut classique de
     * ce composant, et l'œil ne le voit pas.
     */
    @Test
    fun un_seul_noeud_basculable_porte_le_libelle_de_la_fenetre_protegee_et_son_etat() {
        poser(SettingsUiState(secureWindow = true))

        regle.onAllNodes(isToggleable()).assertCountEquals(1)
        val interrupteur = regle.onNode(isToggleable())
        interrupteur.assertIsOn()
        assertThat(
            interrupteur.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .map { it.text },
        ).contains(texte(R.string.settings_secure_window))

        interrupteur.performClick()
        assertThat(fenetresProtegees).containsExactly(false)

        poser(SettingsUiState(secureWindow = false))
        regle.onNode(isToggleable()).assertIsOff()
    }

    /**
     * Le dialogue de thème rend le choix, et **fermer n'en choisit aucun**.
     *
     * ⚠️ Le second point est celui qui vaut d'être mesuré : un dialogue dont la fermeture
     * appellerait quand même le rappel changerait un réglage que l'utilisateur a renoncé à changer.
     */
    @Test
    fun le_dialogue_de_theme_rend_le_choix_et_fermer_ne_choisit_rien() {
        poser(SettingsUiState(theme = ThemePreference.SYSTEM))

        regle.onNodeWithText(texte(R.string.settings_theme)).performClick()
        regle.waitForIdle()
        regle.onNode(dansLeDialogue(texte(R.string.common_close))).performClick()
        regle.waitForIdle()
        assertThat(themes).isEmpty()

        regle.onNodeWithText(texte(R.string.settings_theme)).performClick()
        regle.waitForIdle()
        regle.onNode(dansLeDialogue(texte(R.string.settings_theme_dark))).performClick()

        assertThat(themes).containsExactly(ThemePreference.DARK)
    }

    /**
     * Le dialogue de tri offre **six libellés distincts**.
     *
     * ⚠️⚠️ Le compte de libellés **distincts** est ce qui fige le défaut corrigé des deux côtés le
     * 2026-08-14 : le menu publié affichait six entrées dont quatre partageaient deux libellés, et
     * seule la position du bouton radio disait ce qu'on avait choisi. Compter six **entrées** n'aurait
     * rien vu — c'est l'unicité qui portait le défaut.
     */
    @Test
    fun le_dialogue_de_tri_offre_six_libelles_distincts() {
        poser()

        // ⚠️ `hasClickAction()` est indispensable : `home_sort_mode` sert **deux** fois sur cet écran
        // — au titre de section et à la ligne — et `onNodeWithText` seul désignait deux nœuds.
        regle.onNode(hasText(texte(R.string.home_sort_mode)) and hasClickAction())
            .performScrollTo()
            .performClick()
        regle.waitForIdle()

        val libelles = NoteSortMode.entries.map { mode ->
            regle.onNode(dansLeDialogue(libelleDeTriVisible(mode))).fetchSemanticsNode().id
        }
        assertThat(libelles.toSet()).hasSize(NoteSortMode.entries.size)
    }

    /**
     * 🔴🔴 **Le mode panique ne se déclenche PAS sans avoir écrit le mot-clé.**
     *
     * C'est le geste qui détruit tout, et sa seule protection est ce mot à recopier. Ce test est le
     * seul endroit du dépôt où la garde est exercée, et il ne peut exister que parce que l'écran est
     * sans état : à travers le vrai `PanicViewModel`, il effacerait la base du S9 **et** le modèle
     * vocal de 57 Mo.
     *
     * Trois états mesurés, dans l'ordre où un utilisateur les traverse : champ **vide** ⇒ refusé,
     * mot **faux** ⇒ refusé, mot juste ⇒ accepté.
     *
     * ⚠️ Le mot juste est saisi **en minuscules**, exprès : la comparaison ignore la casse, et c'est
     * un choix écrit dans `PanicScreens.kt` — la mise en majuscules automatique ne s'applique ni aux
     * claviers physiques ni à certaines méthodes de saisie, et quelqu'un sous stress tape sans
     * majuscule. Vérifier avec le mot en majuscules laisserait ce choix non mesuré.
     */
    @Test
    fun le_mode_panique_ne_se_declenche_pas_sans_le_mot_cle() {
        val motCle = texte(R.string.panic_confirm_keyword).trim()
        poser()

        regle.onNodeWithText(texte(R.string.settings_panic_subtitle)).performScrollTo().performClick()
        regle.waitForIdle()

        val confirmer = regle.onNode(hasText(texte(R.string.panic_confirm_yes)) and hasAnyAncestor(isDialog()))
        val champ = regle.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))

        confirmer.assertIsNotEnabled()

        champ.performTextInput("PAS LE BON MOT")
        regle.waitForIdle()
        confirmer.assertIsNotEnabled()

        champ.performTextClearance()
        champ.performTextInput(motCle.lowercase())
        regle.waitForIdle()
        confirmer.assertIsEnabled()

        confirmer.performClick()
        assertThat(paniques).hasSize(1)
    }

    /**
     * ⚠️ **Annuler la confirmation ne déclenche rien, et referme le dialogue.**
     *
     * Séparé du test précédent : un dialogue dont l'annulation appellerait quand même l'action, ou la
     * laisserait ouverte, sont deux défauts distincts du refus de confirmer sans mot-clé.
     */
    @Test
    fun annuler_la_confirmation_de_panique_ne_declenche_rien() {
        poser()

        regle.onNodeWithText(texte(R.string.settings_panic_subtitle)).performScrollTo().performClick()
        regle.waitForIdle()
        regle.onNodeWithText(texte(R.string.panic_confirm_title)).assertIsDisplayed()

        regle.onNode(dansLeDialogue(texte(R.string.common_cancel))).performClick()
        regle.waitForIdle()

        assertThat(paniques).isEmpty()
        regle.onNodeWithText(texte(R.string.panic_confirm_title)).assertDoesNotExist()
    }

    /**
     * 🔴 **Pendant une panique, la ligne est DÉSACTIVÉE — et le mesurer demande la bonne assertion.**
     *
     * `Modifier.clickable(enabled = !enCours)` : sans cette garde, un second appui pendant
     * l'effacement rouvrirait la confirmation par-dessus une destruction en cours. Le rouage ayant
     * remplacé le chevron, rien d'autre ne dirait à un lecteur d'écran que le geste est fermé.
     *
     * ⚠️⚠️ **Ma première version cherchait l'absence d'action `OnClick`, et elle a échoué :
     * `clickable(enabled = false)` CONSERVE l'action dans l'arbre de sémantique** et pose la propriété
     * `Disabled` à côté. C'est cohérent — un nœud désactivé reste annoncé, avec sa nature et son
     * indisponibilité — mais ça se mesure par `assertIsNotEnabled`, jamais par `assertDoesNotExist`.
     *
     * ⚠️ Conséquence pour le balayage de `ui/BalayageDAccessibilite.kt` : il **voit** les actionnables
     * désactivés, et c'est ce qu'on veut. Un bouton grisé sans nom reste un bouton sans nom.
     *
     * Le second geste est celui qui compte vraiment : appuyer pendant la panique **n'ouvre pas** la
     * confirmation.
     */
    @Test
    fun pendant_une_panique_la_ligne_est_desactivee_et_ne_rouvre_pas_la_confirmation() {
        val ligne = { regle.onNode(hasText(texte(R.string.settings_panic_subtitle)) and hasClickAction()) }

        poser(panique = true)
        ligne().assertIsNotEnabled()

        ligne().performScrollTo().performClick()
        regle.waitForIdle()
        regle.onNodeWithText(texte(R.string.panic_confirm_title)).assertDoesNotExist()

        // Le témoin : hors panique, la même ligne est active et ouvre bien la confirmation. Sans lui,
        // les deux assertions ci-dessus passeraient aussi sur un écran qui n'aurait jamais de ligne.
        poser(panique = false)
        ligne().assertIsEnabled()
        ligne().performScrollTo().performClick()
        regle.waitForIdle()
        regle.onNodeWithText(texte(R.string.panic_confirm_title)).assertIsDisplayed()
    }

    /** Les trois sorties de l'écran : retour, dictée, à propos. */
    @Test
    fun les_trois_sorties_de_l_ecran_remontent_a_l_appelant() {
        poser()

        regle.onNodeWithContentDescription(texte(R.string.common_back)).performClick()
        regle.onNodeWithText(texte(R.string.voice_setup_enable)).performScrollTo().performClick()
        regle.onNodeWithText(texte(R.string.settings_about)).performScrollTo().performClick()

        assertThat(retours).hasSize(1)
        assertThat(dictees).hasSize(1)
        assertThat(aPropos).hasSize(1)
    }

    /**
     * ⚠️ **Pas de ligne « mentions légales » dans les réglages.** Elle n'existe que dans « à propos »
     * côté publié, et l'y dupliquer donnait deux chemins vers le même écran — dont un que la
     * référence n'a pas. L'écart est écrit dans le code ; ce test l'empêche de revenir par
     * inadvertance.
     */
    @Test
    fun les_reglages_ne_proposent_pas_les_mentions_legales() {
        poser()

        regle.onNodeWithText(texte(R.string.legal_title)).assertDoesNotExist()
    }

    /** Le libellé visible d'un mode de tri, tel que l'écran le rend. */
    private fun libelleDeTriVisible(mode: NoteSortMode): String = texte(libelleDeTri(mode))

    /**
     * ⚠️ `hasClickAction()` fait partie du sélecteur : plusieurs dialogues de cet écran portent le
     * même libellé sur leur **titre** et sur un de leurs boutons, et sans cette clause le sélecteur
     * désignerait deux nœuds. Leçon du dialogue de vidange de la corbeille, cf. `04-PIEGES.md` §74.
     */
    private fun dansLeDialogue(libelle: String) = hasText(libelle) and
        hasAnyAncestor(isDialog()) and hasClickAction()
}
