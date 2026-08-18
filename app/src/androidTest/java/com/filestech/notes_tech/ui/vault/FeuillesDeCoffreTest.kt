package com.filestech.notes_tech.ui.vault

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.notes_tech.R
import com.filestech.notes_tech.security.vault.VaultParams
import com.filestech.notes_tech.ui.CHAMP_DE_SAISIE
import com.filestech.notes_tech.ui.actionnablesSansNom
import com.filestech.notes_tech.ui.actionsPerduesALaFusion
import com.filestech.notes_tech.ui.champsDeSaisieSansNom
import com.filestech.notes_tech.ui.theme.NotesTechTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Ce que les deux feuilles de coffre annoncent, et ce qu'elles laissent faire.**
 *
 * Trois lignes de `docs/05-PARITE.md` d'un coup — `vault_pin_sheets.dart`,
 * `vault_passphrase_sheets.dart`, `passphrase_text_field.dart` —, plus `vault_warning_banner.dart`
 * qui vit dans le même fichier Kotlin. C'est **là qu'un défaut d'annonce coûte le plus** : on y
 * saisit le secret d'un coffre qui se détruit au cinquième essai.
 *
 * ## Les quatre questions de la phase 8, appliquées ici
 *
 * 1. *Que reçoit un lecteur d'écran ?* → les trois balayages, **plus** le décompte des champs, plus
 *    ce qui est propre à un secret : le champ est-il déclaré mot de passe, et que porte son
 *    `EditableText` ?
 * 2. *Quels états ne sait-on pas atteindre à la main ?* → un coffre effacé, une temporisation, une
 *    conversion partielle, la phase de chiffrement. Les deux feuilles ont donc été **rendues sans
 *    état** pour ce fichier, comme `HomeScreen` et `TrashScreen` avant elles. `FermetureDeFeuilleTest`
 *    mesurait jusqu'ici une feuille **synthétique** : il a départagé deux relectures, mais il ne
 *    disait rien de celles-ci.
 * 3. *Combien de tests ont été ignorés ?* → compté des deux côtés, `-3` / `-4` à l'instrumentation
 *    et somme des `tests=` en JVM. `04-PIEGES.md` §72 et §82.
 * 4. *Que voulait le publié ?* → il annonce trois choses que le portage n'annonçait pas, et il
 *    retire son pavé quand il n'y a plus rien à essayer.
 *
 * ## 🔴 Ce que les mesures ont RÉFUTÉ, et qu'il ne faut pas re-chercher
 *
 * Trois soupçons sérieux sont tombés à la mesure, et c'est le résultat le plus utile de la journée :
 * les deux champs **gardent** leur nom une fois remplis (`label`, et non le `placeholder` de §80) ;
 * `EditableText` porte les **puces**, jamais le secret ; et Compose **retire déjà** `CopyText` et
 * `CutText` d'un champ à transformation mot de passe, si bien que le `contextMenuBuilder` du publié
 * n'a pas d'équivalent à écrire ici. Chacun de ces trois faits a son test ci-dessous, avec son
 * témoin — *une propriété qu'on croit acquise est une propriété que personne ne verra partir*.
 */
@RunWith(AndroidJUnit4::class)
class FeuillesDeCoffreTest {

    @get:Rule
    val regle = createAndroidComposeRule<ComponentActivity>()

    private val etatCourant = mutableStateOf(VaultSheetState())
    private var pose = false
    private val fermetures = mutableListOf<Unit>()
    private val validations = mutableListOf<String>()
    private val sorties = mutableListOf<Unit>()

    private fun texte(id: Int, vararg args: Any): String = regle.activity.getString(id, *args)

    /** Pose la feuille **une fois**, puis ne fait plus que remplacer l'état du ViewModel. */
    private fun poserLeCode(creation: Boolean = false, etat: VaultSheetState = VaultSheetState()) {
        if (pose) {
            regle.runOnIdle { etatCourant.value = etat }
            regle.waitForIdle()
            return
        }
        etatCourant.value = etat
        pose = true
        regle.setContent {
            NotesTechTheme {
                FeuilleDeCode(
                    state = etatCourant.value,
                    nomDuDossier = DOSSIER,
                    creating = creation,
                    chiffrementEnCours = { false },
                    onQuitter = { sorties += Unit },
                    onValider = { validations += it },
                    onFermerSurUneIssueFinale = { fermetures += Unit },
                )
            }
        }
        regle.waitForIdle()
    }

    private fun poserLaPhrase(creation: Boolean = false, etat: VaultSheetState = VaultSheetState()) {
        if (pose) {
            regle.runOnIdle { etatCourant.value = etat }
            regle.waitForIdle()
            return
        }
        etatCourant.value = etat
        pose = true
        regle.setContent {
            NotesTechTheme {
                FeuilleDePhraseSecrete(
                    state = etatCourant.value,
                    nomDuDossier = DOSSIER,
                    creating = creation,
                    chiffrementEnCours = { false },
                    onQuitter = { sorties += Unit },
                    onValider = { validations += it },
                    onFermerSurUneIssueFinale = { fermetures += Unit },
                )
            }
        }
        regle.waitForIdle()
    }

    private fun touche(chiffre: String) =
        regle.onNodeWithContentDescription(texte(R.string.vault_pin_key_label, chiffre))

    /**
     * 🔴🔴 **La poignée de material3 pose DEUX nœuds aux mêmes coordonnées**, l'un nommé, l'autre
     * portant `OnLongClick` **seul et sans nom**. Ce n'est pas le portage, ce n'est pas nommable
     * depuis l'appelant, et c'est mesuré ici plutôt qu'écrit : l'exception est ancrée sur le nœud qui
     * porte `Dismiss`, et l'assertion reste un `containsExactly`. Elle tombera le jour où material3
     * nommera son nœud, et ce sera l'ordre de retirer l'exception. Même idiome qu'`AutocompletionTest`.
     */
    private fun poignee(): Rect =
        regle.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).fetchSemanticsNode().boundsInRoot

    // ── Question 1 : ce qu'un lecteur d'écran reçoit ─────────────────────────────────────────────

    /**
     * 🔴 **Le filet de §80, sur les deux champs qui coûteraient le plus cher.**
     *
     * Le défaut de l'éditeur — un champ qui perd son nom dès qu'il porte du texte — venait d'un
     * `placeholder`, qui disparaît de l'arbre en même temps que de l'écran. Ici c'est un `label`, et
     * la mesure le confirme : le nom reste. ⚠️ **Les champs sont remplis avant d'être balayés** ; à
     * vide, ce test passerait quelle que soit la faute.
     *
     * ⚠️ Le décompte vient **d'abord** : un balayage qui n'a rien trouvé à balayer est vert lui aussi.
     */
    @Test
    fun les_deux_champs_de_phrase_secrete_gardent_leur_nom_une_fois_remplis() {
        poserLaPhrase(creation = true)

        assertThat(regle.onAllNodes(CHAMP_DE_SAISIE).fetchSemanticsNodes()).hasSize(2)

        regle.onAllNodes(CHAMP_DE_SAISIE)[0].performTextInput(SECRET)
        regle.onAllNodes(CHAMP_DE_SAISIE)[1].performTextInput(SECRET)
        regle.waitForIdle()

        assertThat(regle.champsDeSaisieSansNom()).isEmpty()
        assertThat(regle.onAllNodes(CHAMP_DE_SAISIE)[0].fetchSemanticsNode().nomAnnonce())
            .isEqualTo(texte(R.string.vault_pass_field))
        assertThat(regle.onAllNodes(CHAMP_DE_SAISIE)[1].fetchSemanticsNode().nomAnnonce())
            .isEqualTo(texte(R.string.vault_pass_confirm_field))
    }

    /**
     * 🔴🔴 **Ce qu'un champ de secret doit à l'arbre sémantique : se déclarer, et ne rien livrer.**
     *
     * `Password` fait poser `AccessibilityNodeInfo.isPassword` — c'est ce qui empêche un lecteur
     * d'écran d'épeler le secret à voix haute. `EditableText`, lui, est lisible par **tout** service
     * d'accessibilité : s'il portait la valeur brute, `FLAG_SECURE` n'y changerait rien, il ne bloque
     * que la capture d'image.
     *
     * Mesuré le 2026-08-18 : la propriété est posée, et `EditableText` ne porte que des puces.
     * ⚠️ L'assertion **compare au secret** au lieu de se contenter de « ce n'est pas vide » : c'est la
     * seule forme qui tombe si un jour la transformation saute.
     */
    @Test
    fun le_champ_de_phrase_secrete_se_declare_mot_de_passe_et_ne_livre_pas_le_secret() {
        poserLaPhrase()
        regle.onNode(CHAMP_DE_SAISIE).performTextInput(SECRET)
        regle.waitForIdle()

        val noeud = regle.onNode(CHAMP_DE_SAISIE).fetchSemanticsNode()
        assertThat(noeud.config.getOrNull(SemanticsProperties.Password)).isNotNull()
        val editable = noeud.config.getOrNull(SemanticsProperties.EditableText)?.text
        assertThat(editable).isNotEqualTo(SECRET)
        assertThat(editable).hasLength(SECRET.length)
        assertThat(editable?.toSet()).containsExactly('•')
    }

    /**
     * 🔴🔴 **Compose retire lui-même « Copier » et « Couper » d'un champ mot de passe** — et c'est
     * pour ça que le `contextMenuBuilder` de l'application publiée n'a pas d'équivalent à écrire.
     *
     * Le publié retire ces deux entrées à la main, avec toute une histoire derrière
     * (`passphrase_text_field.dart:60`) : un appui long, « Tout sélectionner », « Copier », et la
     * phrase secrète part au presse-papiers. Chercher ce garde dans le portage et ne pas le trouver
     * ressemble à s'y méprendre à une régression de sécurité. Ce n'en est pas une.
     *
     * ⚠️⚠️ **Le témoin est la moitié du test.** Un champ **ordinaire**, même harnais, même geste,
     * porte bien `CopyText` et `CutText`. Sans lui, un instrument qui ne saurait pas les lire rendrait
     * « aucune action de copie » sur n'importe quoi — et c'est exactement ce qui est arrivé deux fois
     * avant d'écrire celui-ci : un espion de barre de sélection n'a rien vu **sur le témoin non plus**,
     * donc n'a rien prouvé.
     *
     * ⚠️ Le champ mesuré est un `OutlinedTextField` posé ici, et non `ChampDePhraseSecrete` qui est
     * privé — **avec la même transformation et le même type de clavier**, qui sont les deux seules
     * entrées dont ce comportement dépend (mesuré : le retrait suit la transformation, pas le clavier).
     */
    @Test
    fun un_champ_mot_de_passe_n_offre_ni_copier_ni_couper_la_ou_un_champ_ordinaire_les_offre() {
        regle.setContent {
            NotesTechTheme {
                Column {
                    var ordinaire by remember { mutableStateOf(SECRET) }
                    OutlinedTextField(
                        value = ordinaire,
                        onValueChange = { ordinaire = it },
                        label = { Text("Ordinaire") },
                        singleLine = true,
                    )
                    var secrete by remember { mutableStateOf(SECRET) }
                    OutlinedTextField(
                        value = secrete,
                        onValueChange = { secrete = it },
                        label = { Text("Phrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
            }
        }
        regle.waitForIdle()

        fun actionsDeCopie(index: Int): Pair<Boolean, Boolean> {
            val champ = regle.onAllNodes(CHAMP_DE_SAISIE)[index]
            champ.performSemanticsAction(SemanticsActions.SetSelection) { it(0, SECRET.length, false) }
            regle.waitForIdle()
            val config = champ.fetchSemanticsNode().config
            return (config.getOrNull(SemanticsActions.CopyText) != null) to
                (config.getOrNull(SemanticsActions.CutText) != null)
        }

        // Le témoin d'abord : sans lui, l'assertion suivante ne vaut rien.
        assertThat(actionsDeCopie(0)).isEqualTo(true to true)
        assertThat(actionsDeCopie(1)).isEqualTo(false to false)
    }

    /**
     * ⚠️⚠️ **Fil-piège, et NON un balayage : la feuille de code n'a aucune zone de saisie.**
     *
     * Elle se pilote au pavé, et ses chiffres sont des pastilles. Y brancher
     * `champsDeSaisieSansNom` rendrait une liste vide **pour la mauvaise raison** — l'assertion
     * vacante que ce dépôt a déjà relevée. Ce test dit donc ce qui est vrai et se vérifie, comme
     * `CorbeilleTest` : **aucun nœud éditable**, aux deux étapes de la création comme au
     * déverrouillage. Il tombera le jour où quelqu'un y mettra un champ, et ce sera l'ordre de
     * brancher le balayage.
     */
    @Test
    fun la_feuille_de_code_ne_porte_aucune_zone_de_saisie() {
        poserLeCode(creation = true)
        assertThat(regle.onAllNodes(CHAMP_DE_SAISIE).fetchSemanticsNodes()).isEmpty()

        repeat(4) { touche("1").performClick() }
        regle.onNodeWithText(texte(R.string.common_validate)).performClick()
        regle.waitForIdle()
        assertThat(regle.onAllNodes(CHAMP_DE_SAISIE).fetchSemanticsNodes()).isEmpty()

        poserLeCode(etat = VaultSheetState(attempt = VaultAttempt.WrongSecret(3)))
        assertThat(regle.onAllNodes(CHAMP_DE_SAISIE).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun aucun_actionnable_de_la_feuille_de_code_n_est_sans_nom() {
        poserLeCode(creation = true)
        assertThat(regle.actionnablesSansNom()).containsExactly(poignee())

        repeat(4) { touche("1").performClick() }
        regle.onNodeWithText(texte(R.string.common_validate)).performClick()
        regle.waitForIdle()
        assertThat(regle.actionnablesSansNom()).containsExactly(poignee())

        poserLeCode(etat = VaultSheetState(attempt = VaultAttempt.Created(encrypted = 3, failed = 2)))
        assertThat(regle.actionnablesSansNom()).containsExactly(poignee())
    }

    @Test
    fun aucun_actionnable_de_la_feuille_a_phrase_secrete_n_est_sans_nom() {
        poserLaPhrase(creation = true)
        regle.onAllNodes(CHAMP_DE_SAISIE)[0].performTextInput(SECRET)
        regle.waitForIdle()

        assertThat(regle.actionnablesSansNom()).containsExactly(poignee())
    }

    /** Le filet de régression de §74, sur les deux feuilles. */
    @Test
    fun aucune_action_des_feuilles_de_coffre_n_est_perdue_a_la_fusion() {
        poserLeCode(creation = true)
        assertThat(regle.actionsPerduesALaFusion()).isEmpty()

        poserLeCode(etat = VaultSheetState(attempt = VaultAttempt.WrongSecret(2)))
        assertThat(regle.actionsPerduesALaFusion()).isEmpty()
    }

    @Test
    fun aucune_action_de_la_feuille_a_phrase_secrete_n_est_perdue_a_la_fusion() {
        poserLaPhrase(creation = true)

        assertThat(regle.actionsPerduesALaFusion()).isEmpty()
    }

    // ── Question 2 : les états qu'aucun doigt n'atteint ──────────────────────────────────────────

    /**
     * 🔴🔴 **Le défaut §86 : la destruction du coffre n'était annoncée à personne.**
     *
     * Cinq codes faux détruisent le contenu du dossier. Le message part à l'écran ; aucun nœud de
     * cette feuille ne portait de région active, dans **aucun** état. Quelqu'un qui n'a pas l'écran
     * n'apprenait rien. L'application publiée, elle, appelle `SemanticsService.announce` sur ce
     * chemin précis (`vault_pin_sheets.dart:456`) en le commentant « annonce critique ».
     *
     * ⚠️ `Assertive` et non `Polite` : ce message doit interrompre ce qui est en train d'être lu.
     */
    @Test
    fun la_destruction_du_coffre_est_annoncee() {
        poserLeCode(etat = VaultSheetState(attempt = VaultAttempt.Wiped))

        val region = regle.onNodeWithTag(EMPLACEMENT_DU_MESSAGE).fetchSemanticsNode()
        assertThat(region.config.getOrNull(SemanticsProperties.LiveRegion)).isNotNull()
        assertThat(region.nomAnnonce()).isEqualTo(texte(R.string.vault_pin_wiped))
    }

    /** Le même défaut sur l'issue la plus fréquente — et celle qui porte le décompte qui reste. */
    @Test
    fun un_code_faux_est_annonce_avec_les_tentatives_restantes() {
        poserLeCode(etat = VaultSheetState(attempt = VaultAttempt.WrongSecret(3)))

        val region = regle.onNodeWithTag(EMPLACEMENT_DU_MESSAGE).fetchSemanticsNode()
        assertThat(region.config.getOrNull(SemanticsProperties.LiveRegion)).isNotNull()
        assertThat(region.nomAnnonce()).contains(texte(R.string.vault_pin_attempts_left, 3))
    }

    /** Argon2id dure une seconde ou plus : ne rien dire laisse croire que l'appui n'a pas pris. */
    @Test
    fun la_derivation_en_cours_est_annoncee() {
        poserLeCode(etat = VaultSheetState(busy = true, phase = PhaseDeCoffre.DERIVATION))

        val region = regle.onNodeWithTag(EMPLACEMENT_DU_MESSAGE).fetchSemanticsNode()
        assertThat(region.config.getOrNull(SemanticsProperties.LiveRegion)).isNotNull()
        assertThat(region.nomAnnonce()).contains(texte(R.string.vault_pass_deriving))
    }

    /**
     * 🔴 **Le témoin des trois précédents, et il n'est pas décoratif.**
     *
     * Une région active posée en permanence ferait annoncer **le silence** à chaque fois qu'un
     * message s'efface — c'est-à-dire à chaque frappe sur le pavé, qui remet `erreurLocale` à zéro.
     * Sans ce cas, les trois assertions ci-dessus passeraient tout aussi bien avec une région
     * toujours posée, qui est un défaut d'un autre genre.
     */
    @Test
    fun l_emplacement_vide_n_est_PAS_une_region_active() {
        poserLeCode()

        val region = regle.onNodeWithTag(EMPLACEMENT_DU_MESSAGE).fetchSemanticsNode()
        assertThat(region.config.getOrNull(SemanticsProperties.LiveRegion)).isNull()
    }

    /** La feuille à phrase secrète portait le même défaut : un jumeau corrigé des deux côtés. */
    @Test
    fun la_feuille_a_phrase_secrete_annonce_aussi_son_issue() {
        poserLaPhrase(etat = VaultSheetState(attempt = VaultAttempt.WrongSecret(null)))

        val region = regle.onNodeWithTag(EMPLACEMENT_DU_MESSAGE).fetchSemanticsNode()
        assertThat(region.config.getOrNull(SemanticsProperties.LiveRegion)).isNotNull()
        assertThat(region.nomAnnonce()).isEqualTo(texte(R.string.vault_pass_wrong))
    }

    // ── Question 4 : ce que le publié voulait ────────────────────────────────────────────────────

    /**
     * 🔴🔴 **Le défaut §87 : l'emplacement du message ne réservait qu'UNE ligne.**
     *
     * Le pavé est ancré sous le message : tout ce qui grandit au-dessus le pousse. L'application
     * publiée réserve **deux** lignes suivant `textScaler`, après un bug utilisateur — « on dirait que
     * le clavier n'est pas tactile du tout ». Le portage réservait une ligne blanche, et à la taille
     * de texte par défaut tous ses messages tiennent sur une ligne : **le défaut était invisible**.
     * Mesuré à `font_scale 2,0` le 2026-08-18, la touche « 5 » descendait de **96 px** entre un
     * emplacement vide et « PIN incorrect. Tentatives restantes : 3 ».
     *
     * ⚠️⚠️ **Ce test ne dépend d'aucune taille de texte, et c'est délibéré.** Comparer des positions
     * de touche à l'échelle par défaut ne prouverait rien — rien ne bouge, avant comme après le
     * correctif : l'assertion serait **vacante**. Ce qu'il mesure, c'est l'invariant lui-même : *la
     * hauteur réservée à vide vaut au moins deux fois celle d'une ligne de message*. La hauteur d'une
     * ligne est **mesurée**, pas écrite, donc elle suit la taille de texte de l'appareil.
     */
    @Test
    fun l_emplacement_du_message_reserve_la_hauteur_de_DEUX_lignes() {
        poserLeCode()
        val hauteurAVide = regle.onNodeWithTag(EMPLACEMENT_DU_MESSAGE).fetchSemanticsNode().boundsInRoot.height

        // ⚠️⚠️ **Le témoin est le message le plus COURT que cette feuille sache produire** — « Erreur »,
        // six caractères. Il tenait d'abord « PIN incorrect. Tentatives restantes : 3 », qui tient sur
        // une ligne à la taille par défaut mais en prend **deux** à 200 % : `messageDUneLigne` aurait
        // alors valu deux lignes, l'assertion en aurait exigé quatre, et le test serait tombé **à
        // tort** sur le réglage d'accessibilité qu'il est justement là pour défendre. Relevé par une
        // relecture externe (Gemini, 2026-08-18).
        poserLeCode(etat = VaultSheetState(attempt = VaultAttempt.Failed(null)))
        // ⚠️ **L'arbre NON fusionné, et c'est le seul endroit de ce fichier où il le faut.**
        // L'emplacement fusionne ses descendants pour n'annoncer qu'une phrase ; dans l'arbre
        // fusionné, demander « le nœud qui porte ce texte » rend donc **l'emplacement lui-même**, et
        // la comparaison porterait sur une grandeur et elle-même. Mesuré : 96 px des deux côtés.
        val messageDUneLigne = regle
            .onNodeWithText(texte(R.string.common_error), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertThat(hauteurAVide).isAtLeast(messageDUneLigne.height * 2f)
    }

    /**
     * ✅ **La conséquence : le pavé ne bouge pas quand un message apparaît.**
     *
     * ⚠️ Et son **témoin**, qui est ce qui rend l'assertion négative crédible : un message
     * volontairement très long, lui, **déplace** le pavé. L'instrument voit donc bien le mouvement,
     * et l'égalité au-dessus n'est pas celle d'une mesure qui ne mesure rien.
     *
     * ⚠️ Ce témoin dit aussi la **limite assumée** de la garde : deux lignes, pas davantage. Figer la
     * hauteur comme le fait le publié tronquerait à l'ellipse la phrase qui annonce la destruction du
     * coffre — et sur ce chemin-là, il n'y a de toute façon plus de pavé à déplacer.
     */
    @Test
    fun un_message_ordinaire_ne_deplace_pas_le_pave_mais_un_message_demesure_le_deplace() {
        poserLeCode()
        val repos = touche("5").fetchSemanticsNode().boundsInRoot

        poserLeCode(etat = VaultSheetState(attempt = VaultAttempt.WrongSecret(3)))
        assertThat(touche("5").fetchSemanticsNode().boundsInRoot).isEqualTo(repos)

        poserLeCode(etat = VaultSheetState(attempt = VaultAttempt.Failed(MESSAGE_DEMESURE)))
        assertThat(touche("5").fetchSemanticsNode().boundsInRoot).isNotEqualTo(repos)
    }

    /**
     * 🔴🔴 **Le défaut §89 : après l'effacement, le portage laissait le pavé et « Valider ».**
     *
     * Le garde qui retire les commandes quand l'action n'a plus de sens n'existait que pour une
     * conversion partielle. `Wiped` a la même forme et n'était pas couvert : retaper un code sur un
     * coffre qui n'existe plus fait remonter un refus qui **écrase la seule phrase** disant que les
     * notes ont été détruites. Le publié retire son pavé sur ce chemin et propose « Fermer ».
     *
     * ⚠️ Le témoin suit : sur un code **faux**, le pavé est toujours là. Sans lui, ce test passerait
     * sur une feuille qui n'aurait jamais de pavé du tout.
     */
    @Test
    fun apres_l_effacement_du_coffre_il_ne_reste_ni_pave_ni_valider() {
        poserLeCode(etat = VaultSheetState(attempt = VaultAttempt.Wiped))

        regle.onAllNodesWithContentDescription(texte(R.string.vault_pin_key_label, "5")).assertCountEquals(0)
        regle.onAllNodesWithContentDescription(texte(R.string.vault_pin_key_delete)).assertCountEquals(0)
        regle.onAllNodesWithText(texte(R.string.vault_pass_unlock_action)).assertCountEquals(0)
        regle.onAllNodesWithContentDescription(pastilles()).assertCountEquals(0)
        regle.onNodeWithText(texte(R.string.common_close)).assertIsDisplayed().performClick()
        assertThat(fermetures).hasSize(1)

        // Le témoin : sur un code faux, tout est encore là.
        poserLeCode(etat = VaultSheetState(attempt = VaultAttempt.WrongSecret(3)))
        touche("5").assertIsDisplayed()
        regle.onAllNodesWithContentDescription(pastilles()).assertCountEquals(1)
    }

    /**
     * 🔴 **Le jumeau du précédent, côté phrase secrète — et il était FAUX.**
     *
     * Le KDoc de `plusRienAEssayer` annonçait « ni pavé, ni champ » ; côté phrase secrète, les deux
     * champs restaient affichés sous le bouton « Fermer », à côté du message disant que des notes
     * sont restées en clair. Une surface de saisie qui laisse croire qu'on peut réessayer, alors que
     * le seul geste offert est de partir — et le secret déjà tapé reste à l'écran.
     *
     * Relevé par une relecture externe (GPT-5.2, 2026-08-18), qui l'a vu **sans avoir le reste de la
     * fonction sous les yeux** : le commentaire promettait plus que le code.
     *
     * ⚠️ Le témoin d'abord : en création ordinaire, les deux champs sont bien là.
     */
    @Test
    fun apres_une_conversion_partielle_la_feuille_a_phrase_secrete_ne_montre_plus_ses_champs() {
        poserLaPhrase(creation = true)
        assertThat(regle.onAllNodes(CHAMP_DE_SAISIE).fetchSemanticsNodes()).hasSize(2)

        poserLaPhrase(etat = VaultSheetState(attempt = VaultAttempt.Created(encrypted = 3, failed = 2)))
        assertThat(regle.onAllNodes(CHAMP_DE_SAISIE).fetchSemanticsNodes()).isEmpty()
        regle.onNodeWithText(texte(R.string.common_close)).assertIsDisplayed()
        regle.onAllNodesWithText(texte(R.string.common_cancel)).assertCountEquals(0)
    }

    /**
     * 🔴 **La bannière d'avertissement est affichée aux DEUX étapes de la création, et le pavé ne
     * bouge pas entre elles.**
     *
     * C'est un défaut que l'application publiée a eu et corrigé (`vault_pin_sheets.dart:287`) : la
     * bannière n'apparaissait qu'à la première saisie, et en passant à la confirmation elle
     * disparaissait — faisant remonter tout le pavé « de 70 à 90 dp, soit plus d'une hauteur de
     * touche. L'utilisateur appuyait là où la touche venait de ne plus être. »
     *
     * Le portage n'a pas ce défaut, parce que sa condition est `creating` et non l'étape. Mais rien
     * ne le mesurait, et *une propriété qu'on tient d'un heureux hasard est une propriété que le
     * prochain remaniement emportera sans bruit*. Ce test couvre la ligne `vault_warning_banner.dart`.
     */
    @Test
    fun l_avertissement_reste_affiche_aux_deux_etapes_et_le_pave_ne_bouge_pas() {
        poserLeCode(creation = true)
        regle.onNodeWithText(texte(R.string.vault_pin_warning_wipe)).assertIsDisplayed()
        val avant = touche("5").fetchSemanticsNode().boundsInRoot

        repeat(4) { touche("1").performClick() }
        regle.onNodeWithText(texte(R.string.common_validate)).performClick()
        regle.waitForIdle()

        // On est bien passé à la confirmation : le titre a changé.
        regle.onNodeWithText(texte(R.string.vault_pin_confirm_field)).assertIsDisplayed()
        regle.onNodeWithText(texte(R.string.vault_pin_warning_wipe)).assertIsDisplayed()
        assertThat(touche("5").fetchSemanticsNode().boundsInRoot).isEqualTo(avant)
    }

    /** Et son pendant : au déverrouillage, il n'y a rien à avertir — la bannière est absente. */
    @Test
    fun l_avertissement_est_absent_au_deverrouillage() {
        poserLeCode(creation = false)

        regle.onAllNodesWithText(texte(R.string.vault_pin_warning_wipe)).assertCountEquals(0)
        regle.onNodeWithText(texte(R.string.vault_pin_unlock_body, DOSSIER)).assertIsDisplayed()
    }

    // ── Outils ──────────────────────────────────────────────────────────────────────────────────

    private fun SemanticsNode.nomAnnonce(): String {
        val description = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
        val texte = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
        return description ?: texte.orEmpty()
    }

    /** La rangée de pastilles s'annonce par son décompte : c'est son seul nom. */
    private fun pastilles(): String = texte(R.string.vault_pin_digits_announce, 0, VaultParams.PIN_MAX_LENGTH)

    private companion object {
        const val DOSSIER = "Dossier"
        const val SECRET = "phrase-secrete-de-test"

        /** Assez long pour dépasser deux lignes sur n'importe quel écran de téléphone. */
        val MESSAGE_DEMESURE = "détail technique ".repeat(40)
    }
}
