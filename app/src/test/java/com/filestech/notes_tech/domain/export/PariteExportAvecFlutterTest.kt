package com.filestech.notes_tech.domain.export

import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.Note
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Les vecteurs de l'export, **relevés en exécutant le vrai code Dart**.
 *
 * ## D'où viennent ces chaînes
 *
 * D'un `flutter test` lancé sur `notes_tech` le 2026-08-14, avec un fichier de relevé temporaire
 * supprimé aussitôt après. Elles ne sont pas déduites d'une lecture de
 * `note_export_service.dart` : une lecture produit ce qu'on croit que le code fait, et c'est
 * exactement ce dont on veut se passer ici.
 *
 * ## Pourquoi le fuseau est figé
 *
 * Les dates de note sont des **dates locales** côté Dart — `DateTime.fromMillisecondsSinceEpoch`
 * n'est pas UTC, et `toIso8601String()` n'écrit alors ni `Z` ni décalage. Les vecteurs ont donc été
 * relevés en Europe/Paris et ne veulent rien dire ailleurs. Figer le fuseau ici est ce qui rend le
 * test reproductible sur une machine d'intégration réglée sur UTC.
 */
class PariteExportAvecFlutterTest {

    private val paris = ZoneId.of("Europe/Paris")

    private fun note(
        id: String = "11111111-2222-3333-4444-555555555555",
        title: String = "",
        content: String = "",
        folderId: String = Folder.INBOX_ID,
        tags: List<String> = emptyList(),
        pinned: Boolean = false,
        favorite: Boolean = false,
    ) = Note(
        id = id,
        title = title,
        content = content,
        folderId = folderId,
        tags = tags,
        pinned = pinned,
        favorite = favorite,
        archived = false,
        trashedAt = null,
        createdAt = Instant.ofEpochMilli(1_700_000_000_000L),
        updatedAt = Instant.ofEpochMilli(1_700_000_123_456L),
        encrypted = null,
        encVersion = 0,
    )

    // ── Noms de fichier ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("un titre ordinaire garde ses accents")
    fun nomSimple() {
        assertThat(NoteMarkdown.safeFileName("Réunion du 12", note().id)).isEqualTo("Réunion du 12.md")
    }

    @Test
    @DisplayName("les caractères interdits par les systèmes de fichiers disparaissent")
    fun nomInterdits() {
        assertThat(NoteMarkdown.safeFileName("a/b<c>d:e\"f|g?h*i", note().id)).isEqualTo("abcdefghi.md")
    }

    /** 🔴 `note<U+202E>gpj.md` s'afficherait `note.mdgpj` dans une fenêtre de partage. */
    @Test
    @DisplayName("la commande de sens d'écriture est retirée")
    fun nomBidi() {
        assertThat(NoteMarkdown.safeFileName("note‮gpj", note().id)).isEqualTo("notegpj.md")
    }

    @Test
    @DisplayName("les espaces sont compressés puis élagués")
    fun nomEspaces() {
        assertThat(NoteMarkdown.safeFileName("  a b   c  ", note().id)).isEqualTo("a b c.md")
    }

    @Test
    @DisplayName("un titre vide retombe sur les huit premiers caractères de l'identifiant")
    fun nomVide() {
        assertThat(NoteMarkdown.safeFileName("   ", note().id)).isEqualTo("note-11111111.md")
    }

    @Test
    @DisplayName("« .. » ne devient jamais un nom de fichier")
    fun nomPoint() {
        assertThat(NoteMarkdown.safeFileName("..", note().id)).isEqualTo("note-11111111.md")
    }

    @Test
    @DisplayName("un nom réservé de Windows retombe sur l'identifiant, quelle que soit sa casse")
    fun nomReserve() {
        assertThat(NoteMarkdown.safeFileName("con", note().id)).isEqualTo("note-11111111.md")
        assertThat(NoteMarkdown.safeFileName("LPT9", note().id)).isEqualTo("note-11111111.md")
    }

    /**
     * 🔴 **Un nom de périphérique reste réservé avec une extension**, et les deux contrôles ne le
     * voyaient pas : ils comparaient la chaîne entière à la liste, si bien que `CON.txt` passait.
     *
     * Ce n'est pas un cas tordu. `CON.txt` est un titre de note plausible, et le fichier produit —
     * `CON.txt.md` — rend l'archive **entière** inextractible chez un destinataire Windows, pas
     * seulement ce fichier-là. L'export échoue donc au moment précis où il sert.
     *
     * ⚠️ Le KDoc de la constante se félicitait d'avoir corrigé un jumeau asymétrique. Les deux sites
     * partageaient bien la même liste — avec le **même prédicat incomplet**. Partager la donnée ne
     * suffisait pas : c'est la décision qu'il fallait partager, et c'est maintenant le cas
     * (`estReserveWindows`). Ce test verrouille les deux sites.
     */
    @Test
    @DisplayName("un nom réservé le reste avec une extension, un point final ou une espace finale")
    fun nomReserveAvecExtension() {
        val id = note().id
        assertThat(NoteMarkdown.safeFileName("CON.txt", id)).isEqualTo("note-11111111.md")
        assertThat(NoteMarkdown.safeFileName("nul.md", id)).isEqualTo("note-11111111.md")
        assertThat(NoteMarkdown.safeFileName("COM1.tar.gz", id)).isEqualTo("note-11111111.md")
        assertThat(NoteMarkdown.safeFileName("PRN.", id)).isEqualTo("note-11111111.md")

        // ⚠️ Le contrôle porte sur le nom de base, pas sur un préfixe : une note dont le titre
        // *commence* par un nom réservé est parfaitement valable et ne doit pas être renommée.
        assertThat(NoteMarkdown.safeFileName("Console", id)).isEqualTo("Console.md")
        assertThat(NoteMarkdown.safeFileName("CONTRAT.pdf", id)).isEqualTo("CONTRAT.pdf.md")

        // ⚠️ En revanche `CON.TRAT.pdf` **est** réservé, et le renommer est correct : Windows résout
        // un nom de périphérique en coupant au **premier** point, donc ce nom-là désigne CON. Une
        // relecture externe a signalé ce cas comme un faux positif de notre prédicat le 2026-08-15 ;
        // c'est la relecture qui se trompait, et ce test fige la réponse pour la prochaine fois.
        assertThat(NoteMarkdown.safeFileName("CON.TRAT.pdf", id)).isEqualTo("note-11111111.md")
    }

    /**
     * ⚠️ La liste qui circule s'arrête à `COM1`. Celle que Microsoft publie commence à `COM0` et
     * comporte en plus les variantes en exposants Unicode — `COM¹` désigne le même périphérique que
     * `COM1`. Relevé par la relecture externe du 2026-08-15, qui n'avait vu que les exposants.
     */
    @Test
    @DisplayName("COM0, LPT0 et les variantes en exposants sont réservés eux aussi")
    fun nomsReservesOublies() {
        val id = note().id
        assertThat(NoteMarkdown.safeFileName("COM0", id)).isEqualTo("note-11111111.md")
        assertThat(NoteMarkdown.safeFileName("LPT0", id)).isEqualTo("note-11111111.md")
        assertThat(NoteMarkdown.safeFileName("COM¹", id)).isEqualTo("note-11111111.md")
        assertThat(NoteMarkdown.safeFileName("lpt³.txt", id)).isEqualTo("note-11111111.md")
        assertThat(NoteMarkdown.safeFolderName(null, "COM0")).isEqualTo("untitled-folder")

        // COM10 n'existe pas comme périphérique : la liste s'arrête à un seul chiffre.
        assertThat(NoteMarkdown.safeFileName("COM10", id)).isEqualTo("COM10.md")
    }

    @Test
    @DisplayName("un dossier réservé le reste avec une extension — le jumeau du test ci-dessus")
    fun dossierReserveAvecExtension() {
        assertThat(NoteMarkdown.safeFolderName(null, "CON.txt")).isEqualTo("untitled-folder")
        assertThat(NoteMarkdown.safeFolderName(null, "aux.old")).isEqualTo("untitled-folder")
        // Réduit à des points : « . » et « .. » étaient déjà rejetés, « ... » ne l'était pas, et
        // Windows le refuse tout autant.
        assertThat(NoteMarkdown.safeFolderName(null, "...")).isEqualTo("untitled-folder")
        assertThat(NoteMarkdown.safeFolderName(null, "Contrats")).isEqualTo("Contrats")
    }

    /**
     * 🔴 Le TROISIEME jumeau asymetrique entre `safeFileName` et `safeFolderName`.
     *
     * Apres la liste des noms reserves, puis le predicat qui l'applique, c'etait la troncature :
     * le fichier bornait a 80 caracteres, le dossier ne bornait rien. Un dossier de trois cents
     * caracteres produit une entree dont le chemin depasse la limite de 260 de Windows —
     * l'archive est valide et refusee a l'extraction, chez le destinataire.
     *
     * ⚠️ Chaque fois, le commentaire de la correction precedente affirmait que la question etait
     * close. Deux fonctions qui doivent produire des noms sûrs se relisent ENSEMBLE.
     */
    @Test
    @DisplayName("un nom de dossier trop long est tronqué comme un nom de fichier")
    fun nomDeDossierLong() {
        assertThat(NoteMarkdown.safeFolderName(null, "é".repeat(300))).isEqualTo("é".repeat(80))
    }

    /**
     * ⚠️ Windows refuse un dossier dont le nom finit par un point ou une espace.
     *
     * `estUnNomDeDossierUtilisable` les ignorait deja pour JUGER le nom, mais la fonction rendait
     * la forme non nettoyee : elle validait une chaine et en renvoyait une autre.
     */
    @Test
    @DisplayName("les points et espaces finaux sont retirés du nom de dossier rendu")
    fun nomDeDossierSansPointFinal() {
        assertThat(NoteMarkdown.safeFolderName(null, "Secret.")).isEqualTo("Secret")
        assertThat(NoteMarkdown.safeFolderName(null, "Secret. ")).isEqualTo("Secret")
        // Le nom ne se reduit pas a des points : il reste utilisable une fois nettoye.
        assertThat(NoteMarkdown.safeFolderName(null, "Dossier..")).isEqualTo("Dossier")
    }

    @Test
    @DisplayName("un titre trop long est tronqué à 80 caractères")
    fun nomLong() {
        assertThat(NoteMarkdown.safeFileName("é".repeat(100), note().id)).isEqualTo("é".repeat(80) + ".md")
    }

    @Test
    @DisplayName("une note venue d'un coffre ouvert porte la mention dans son nom")
    fun nomCoffre() {
        assertThat(NoteMarkdown.safeFileName("Secret", note().id, fromUnlockedVault = true))
            .isEqualTo("Secret [unlocked].md")
    }

    @Test
    @DisplayName("un caractère de contrôle au milieu du titre disparaît sans couper le nom")
    fun nomControle() {
        assertThat(NoteMarkdown.safeFileName("abc", note().id)).isEqualTo("abc.md")
    }

    // ── Markdown ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("guillemets et barres obliques inverses sont échappés dans l'ordre")
    fun markdownSimple() {
        val rendu = NoteMarkdown.render(
            note = note(title = """Titre: avec "guillemets"\et barre""", content = "Corps"),
            folderLabel = "Dossier",
            zone = paris,
        )
        assertThat(rendu).isEqualTo(
            """
            ---
            title: "Titre: avec \"guillemets\"\\et barre"
            folder: "Dossier"
            tags: []
            created: 2023-11-14T23:13:20.000
            updated: 2023-11-14T23:15:23.456
            ---

            Corps
            """.trimIndent() + "\n",
        )
    }

    @Test
    @DisplayName("étiquettes, épinglage et favori suivent l'ordre du frontmatter")
    fun markdownComplet() {
        val rendu = NoteMarkdown.render(
            note = note(title = "T", content = "C\n", tags = listOf("a", "b\tc"), pinned = true, favorite = true),
            folderLabel = "Boîte de réception",
            zone = paris,
        )
        assertThat(rendu).isEqualTo(
            """
            ---
            title: "T"
            folder: "Boîte de réception"
            tags: ["a", "b\tc"]
            created: 2023-11-14T23:13:20.000
            updated: 2023-11-14T23:15:23.456
            pinned: true
            favorite: true
            ---

            C
            """.trimIndent() + "\n",
        )
    }

    /**
     * Un analyseur YAML strict — Logseq — refuse un caractère de contrôle brut dans une chaîne. La
     * forme `\xNN` est en hexadécimal **minuscule**, sur deux chiffres.
     */
    @Test
    @DisplayName("les caractères de contrôle deviennent des échappements hexadécimaux")
    fun markdownControle() {
        val rendu = NoteMarkdown.render(
            note = note(title = "abc", content = ""),
            folderLabel = "Inbox",
            zone = paris,
        )
        assertThat(rendu).contains("""title: "a\x01b\x7fc"""")
        // Un corps vide produit quand même la ligne blanche et le saut final.
        assertThat(rendu).endsWith("---\n\n\n")
    }

    /**
     * ⚠️ La mention de coffre est un **commentaire**, replié sur une seule ligne. Un commentaire
     * YAML s'arrête au saut de ligne : sans le repli, la suite deviendrait du contenu interprété.
     */
    @Test
    @DisplayName("la mention de coffre est un commentaire d'une seule ligne, avant la clôture")
    fun markdownMention() {
        val rendu = NoteMarkdown.render(
            note = note(title = "V", content = "x"),
            folderLabel = "Inbox",
            vaultMention = "Note du coffre : Perso\nsuite",
            zone = paris,
        )
        assertThat(rendu).isEqualTo(
            """
            ---
            title: "V"
            folder: "Inbox"
            tags: []
            created: 2023-11-14T23:13:20.000
            updated: 2023-11-14T23:15:23.456
            # Note du coffre : Perso suite
            ---

            x
            """.trimIndent() + "\n",
        )
    }

    // ── Noms de dossier ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("un dossier nommé « .. » ne produit pas une entrée qui s'extrait dans le parent")
    fun dossierZipSlip() {
        assertThat(NoteMarkdown.safeFolderName(null, "..")).isEqualTo("untitled-folder")
    }

    /**
     * ⚠️ Le jumeau asymétrique de la version publiée : `safeFileName` appliquait la liste des noms
     * réservés, `_safeFolderDirName` l'ignorait — un dossier « CON » suffisait à rendre l'archive
     * inextractible sous Windows.
     */
    @Test
    @DisplayName("un dossier ne peut pas porter un nom réservé de Windows non plus")
    fun dossierReserve() {
        assertThat(NoteMarkdown.safeFolderName(null, "CON")).isEqualTo("untitled-folder")
    }

    @Test
    @DisplayName("la boîte de réception porte un nom de dossier NON traduit")
    fun dossierBoiteDeReception() {
        assertThat(NoteMarkdown.safeFolderName(null, Folder.INBOX_ID)).isEqualTo("inbox")
    }
}
