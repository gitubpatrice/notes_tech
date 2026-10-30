package com.filestech.notes_tech.ui.editor

import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.ui.CHAMP_DE_SAISIE
import com.filestech.notes_tech.ui.champsDeSaisieSansNom
import com.filestech.notes_tech.ui.theme.NotesTechTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * **"Find in note" on the editor's screen** (3.2.0, D-033): what the bar counts, highlights and
 * moves to, while reading and while writing, and what it must not do.
 */
@RunWith(AndroidJUnit4::class)
class RechercheDansLaNoteEcranTest {

    @get:Rule
    val regle = createAndroidComposeRule<ComponentActivity>()

    private val etat = mutableStateOf(EditorUiState())
    private val contenus = mutableListOf<TextFieldValue>()
    private val sorties = mutableListOf<Unit>()

    /** The current occurrence's background, read from the theme the screen is drawn with. */
    private var fondDeLaCourante = Color.Unspecified

    private fun texte(id: Int, vararg arguments: Any): String = regle.activity.getString(id, *arguments)

    /** The screen over a loaded note; the fields write back into it, as the ViewModel does. */
    private fun poser(corps: String, lecture: Boolean, recherche: String? = null) {
        etat.value = EditorUiState(
            loading = false,
            titre = TextFieldValue(TITRE),
            content = TextFieldValue(corps),
            note = note(corps),
            folder = dossier(),
            originalTitle = TITRE,
            originalContent = corps,
        )
        regle.setContent {
            NotesTechTheme {
                fondDeLaCourante = stylesDeSurlignage().courante.background
                NoteEditorScreen(
                    state = etat.value,
                    liens = PanneauDeLiens(),
                    messages = remember { SnackbarHostState() },
                    dicteeActive = false,
                    onQuitter = { sorties += Unit },
                    onTitreChange = { etat.value = etat.value.copy(titre = it) },
                    onContenuChange = {
                        contenus += it
                        etat.value = etat.value.copy(content = it)
                    },
                    onDicter = {},
                    onInsererUnLien = {},
                    onEpingler = {},
                    onFavori = {},
                    onInfos = {},
                    onDeplacer = {},
                    onCouleur = {},
                    onExporter = {},
                    onCopier = {},
                    onCorbeille = {},
                    onSupprimerDefinitivement = {},
                    onOuvrirNote = {},
                    onLienFantome = {},
                    lecture = lecture,
                    onModifier = {},
                    onTerminer = {},
                    onLienDeLApercu = {},
                    rechercheALOuverture = recherche,
                )
            }
        }
        regle.waitForIdle()
    }

    private fun ouvrirLaBarre() {
        regle.onNodeWithContentDescription(texte(R.string.note_editor_tooltip_more)).performClick()
        regle.onNodeWithText(texte(R.string.note_editor_menu_find)).performClick()
    }

    private fun attendreLeCompte(rang: Int, total: Int) {
        val dit = listOf(texte(R.string.note_find_count_description, rang, total))
        regle.waitUntil(DELAI_MS) {
            regle.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, dit))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    /** The highlighted pieces of the drawn texts, top to bottom: their text and their background. */
    private fun surlignages(): List<Pair<String, Color>> = regle
        .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .sortedBy { it.boundsInRoot.top }
        .flatMap { noeud ->
            noeud.config[SemanticsProperties.Text].flatMap { annote ->
                annote.spanStyles
                    .filter { it.item.background != Color.Unspecified }
                    .sortedBy { it.start }
                    .map { annote.text.substring(it.start, it.end) to it.item.background }
            }
        }

    /** The current occurrence: the one highlighted in the colour no other one has. */
    private fun courante(): String {
        val pieces = surlignages()
        val couleur = pieces.groupBy { it.second }.values.single { it.size == 1 }.single()
        return "${pieces.indexOf(couleur)}:${couleur.first}"
    }

    @Test
    fun en_lecture_la_barre_compte_surligne_et_avance_d_une_occurrence() {
        poser("Une pomme.\n\nDeux pommes et une POMME.", lecture = true)

        ouvrirLaBarre()
        regle.onNode(hasSetTextAction()).performTextInput("pomme")

        attendreLeCompte(1, 3)
        assertThat(surlignages().map { it.first }).containsExactly("pomme", "pomme", "POMME").inOrder()
        assertThat(courante()).isEqualTo("0:pomme")

        regle.onNodeWithContentDescription(texte(R.string.note_find_next)).performClick()

        attendreLeCompte(2, 3)
        assertThat(courante()).isEqualTo("1:pomme")
        // The bar's field keeps its name with text in it — the editor's own fields' defect (§80).
        assertThat(regle.onAllNodes(CHAMP_DE_SAISIE).fetchSemanticsNodes()).hasSize(1)
        assertThat(regle.champsDeSaisieSansNom()).isEmpty()
    }

    /** From a search result: the bar is filled, the far occurrence brought on screen, no keyboard. */
    @Test
    fun ouverte_depuis_un_resultat_la_barre_amene_l_occurrence_lointaine_a_l_ecran() {
        val corps = (1..PARAGRAPHES).joinToString("\n\n") { "Paragraphe $it." } + "\n\nLa cible est ici."
        poser(corps, lecture = true, recherche = "cible")

        attendreLeCompte(1, 1)
        regle.waitUntil(DELAI_MS) { surlignages().isNotEmpty() }
        regle.onNodeWithText("La cible est ici.").assertIsDisplayed()
        regle.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    /** 🔴 The cursor goes to the occurrence when the bar moves — and not when the user types. */
    @Test
    fun en_ecriture_la_barre_place_le_curseur_mais_une_frappe_ne_l_y_ramene_pas() {
        poser("début pomme fin", lecture = false)

        ouvrirLaBarre()
        // Three fields while writing: the bar's is the empty one.
        val vide = SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
        regle.onNode(hasSetTextAction() and vide).performTextInput("pomme")
        attendreLeCompte(1, 1)
        regle.waitUntil(DELAI_MS) { contenus.any { it.selection == TextRange(6) } }

        // Typed at the start of the body: the occurrence moves to 8, and the cursor stays where it is.
        regle.runOnIdle { etat.value = etat.value.copy(content = TextFieldValue("XY début pomme fin", TextRange(2))) }
        regle.waitForIdle()

        assertThat(contenus.map { it.selection }).doesNotContain(TextRange(8))
        assertThat(etat.value.content.selection).isEqualTo(TextRange(2))
    }

    /**
     * While writing, the bar puts the cursor on the occurrence and counts on the body field to scroll
     * to its cursor by itself — the field being unfocused, the bar's having the keyboard. Measured on
     * the pixels: the current occurrence's colour must be on screen.
     */
    @Test
    fun en_ecriture_une_occurrence_lointaine_vient_a_l_ecran() {
        val corps = (1..PARAGRAPHES).joinToString("\n") { "Ligne $it." } + "\nLa cible est ici."
        poser(corps, lecture = false)
        assertThat(pixelsDe(fondDeLaCourante)).isEqualTo(0)

        ouvrirLaBarre()
        val vide = SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
        regle.onNode(hasSetTextAction() and vide).performTextInput("cible")
        attendreLeCompte(1, 1)
        regle.waitUntil(DELAI_MS) { contenus.any { it.selection == TextRange(corps.indexOf("cible")) } }

        regle.waitUntil(DELAI_MS) { pixelsDe(fondDeLaCourante) > 0 }
    }

    private fun pixelsDe(couleur: Color): Int {
        val image = regle.onRoot().captureToImage().asAndroidBitmap()
        val argb = couleur.toArgb()
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        return pixels.count { it == argb }
    }

    @Test
    fun le_retour_ferme_la_barre_avant_de_quitter_la_note() {
        poser("Une pomme.", lecture = true)
        ouvrirLaBarre()
        regle.onNodeWithContentDescription(texte(R.string.note_find_close)).assertIsDisplayed()
        // Opened from the menu, the field has the keyboard: the system's first Back closes the keyboard.
        Espresso.closeSoftKeyboard()

        Espresso.pressBack()

        regle.onNodeWithContentDescription(texte(R.string.note_editor_tooltip_more)).assertIsDisplayed()
        assertThat(regle.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()).isEmpty()
        assertThat(sorties).isEmpty()
    }

    private fun note(corps: String) = Note(
        id = "a",
        title = TITRE,
        content = corps,
        folderId = "dossier",
        tags = emptyList(),
        pinned = false,
        favorite = false,
        archived = false,
        trashedAt = null,
        createdAt = Instant.ofEpochMilli(HORODATAGE),
        updatedAt = Instant.ofEpochMilli(HORODATAGE),
        encrypted = null,
        encVersion = 1,
    )

    private fun dossier() = Folder(
        id = "dossier",
        name = "Dossier",
        parentId = null,
        color = null,
        icon = null,
        createdAt = Instant.ofEpochMilli(HORODATAGE),
        updatedAt = Instant.ofEpochMilli(HORODATAGE),
        vault = null,
    )

    private companion object {
        const val TITRE = "Courses"
        const val PARAGRAPHES = 300
        const val DELAI_MS = 5_000L
        const val HORODATAGE = 1_700_000_000_000L
    }
}
