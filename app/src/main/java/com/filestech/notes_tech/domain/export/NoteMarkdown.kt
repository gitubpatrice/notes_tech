package com.filestech.notes_tech.domain.export

import com.filestech.notes_tech.core.text.DartTextSemantics
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.Note
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Le rendu d'une note en Markdown, et le nommage de son fichier.
 *
 * Transposition de `services/export/note_export_service.dart`. Fonctions pures : pas d'accès disque,
 * pas de coffre, pas de contexte Android. C'est ce qui les rend vérifiables ligne à ligne, et c'est
 * délibéré — le format produit est ce que l'utilisateur retrouvera dans dix ans, sans l'application.
 *
 * ## Le format est un contrat avec d'autres logiciels
 *
 * Frontmatter YAML puis corps Markdown : Obsidian, Logseq, Bear, Foam et Dendron le lisent tel quel.
 * Un champ mal échappé ne casse pas l'export, il casse **l'import chez le destinataire**, des mois
 * plus tard, sans que rien ici ne l'ait signalé. D'où l'échappement explicite plutôt qu'une
 * dépendance YAML : chaque règle est visible et testée.
 */
object NoteMarkdown {

    /**
     * Le nom du dossier de la boîte de réception dans l'archive.
     *
     * ⚠️ Volontairement **non traduit**, contrairement à ce que l'interface affiche. Deux exports
     * faits par le même utilisateur dans deux langues doivent produire la même arborescence, sans
     * quoi une réimportation croise deux dossiers pour la même chose.
     */
    const val INBOX_DIR_NAME = "inbox"

    /** Au-delà, le nom est tronqué. 80 caractères laissent la marge sous les 255 octets d'un FAT32. */
    private const val MAX_FILE_NAME_LENGTH = 80

    /**
     * Noms réservés par Windows. Un fichier **ou un dossier** qui en porte un fait échouer
     * l'extraction complète de l'archive chez le destinataire.
     *
     * ⚠️ Le jumeau asymétrique existait dans la version publiée : `safeFileName` appliquait cette
     * liste et `_safeFolderDirName` l'ignorait, si bien qu'un dossier nommé « CON » suffisait à
     * rendre l'archive inextractible. Corrigé en amont, côté Dart ; le portage garde les deux
     * usages sur la **même** constante pour que la question ne se repose pas.
     */
    private val WINDOWS_RESERVED: Set<String> = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        for (n in 1..9) {
            add("COM$n")
            add("LPT$n")
        }
    }

    /** Caractères interdits par les systèmes de fichiers, et caractères de contrôle. */
    private val FORBIDDEN = Regex("""[<>:"/\\|?*\x00-\x1f\x7f]""")

    /**
     * Les commandes de sens d'écriture et l'indicateur d'ordre des octets.
     *
     * ⚠️ **Le motif d'usurpation le plus connu passe par là** : `note‮gpj.md` s'affiche
     * `note.mdgpj` dans une fenêtre de partage. Le destinataire croit ouvrir une image.
     *
     * Écrits en hexadécimal parce qu'ils sont invisibles : en littéral, ils masqueraient leur propre
     * rôle dans le code qui les filtre.
     */
    private val BIDI = Regex("[\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")

    /** Caractères de contrôle qu'un analyseur YAML strict — Logseq — refuse à l'état brut. */
    private val YAML_CONTROL = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]")

    /**
     * `2026-08-14T15:07:23.123` — la forme que rend `DateTime.toIso8601String()` de Dart sur une
     * date **locale**.
     *
     * ⚠️ Ni `Z`, ni décalage horaire, et ce n'est pas un oubli : les dates de note sont construites
     * par `DateTime.fromMillisecondsSinceEpoch`, qui produit une date locale, et Dart n'ajoute de
     * suffixe que sur les dates UTC. Écrire un `Z` ici décalerait de plusieurs heures toutes les
     * dates lues par un importeur, silencieusement.
     *
     * ⚠️ `uuuu` et non `yyyy` : le second désigne l'année **de l'ère** et exige un champ d'ère que ce
     * motif n'a pas. La différence ne se voit qu'avant l'an 1, où `yyyy` lève.
     *
     * Les millisecondes sont toujours écrites, y compris `.000` — c'est ce que fait Dart. Les
     * microsecondes, elles, sont omises quand elles valent zéro, ce qui est toujours le cas ici
     * puisque la source est un nombre de millisecondes.
     */
    private val ISO_LOCAL = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS", Locale.ROOT)

    /**
     * Le fichier Markdown complet d'une note : frontmatter puis corps.
     *
     * @param folderLabel le nom affiché du dossier. L'appelant décide s'il traduit la boîte de
     *   réception ou non — le service ne connaît pas la langue de l'interface.
     * @param vaultMention ajouté en **commentaire** YAML, pas en clé : une clé inconnue polluerait
     *   les imports Obsidian et Logseq, alors qu'un commentaire reste lisible à l'œil de qui
     *   décompresse l'archive.
     * @param zone le fuseau qui convertit les instants en heure locale. Injecté pour que les tests
     *   ne dépendent pas du fuseau de la machine qui les exécute.
     */
    fun render(
        note: Note,
        folderLabel: String,
        vaultMention: String? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = buildString {
        append("---\n")
        append("title: ").append(yamlString(note.title)).append('\n')
        append("folder: ").append(yamlString(folderLabel)).append('\n')
        if (note.tags.isEmpty()) {
            append("tags: []\n")
        } else {
            append("tags: [").append(note.tags.joinToString(", ") { yamlString(it) }).append("]\n")
        }
        append("created: ").append(iso(note.createdAt, zone)).append('\n')
        append("updated: ").append(iso(note.updatedAt, zone)).append('\n')
        if (note.pinned) append("pinned: true\n")
        if (note.favorite) append("favorite: true\n")
        if (!vaultMention.isNullOrEmpty()) {
            // Replié sur une seule ligne : un commentaire YAML s'arrête au saut de ligne, et la
            // suite deviendrait du contenu interprété.
            append("# ").append(vaultMention.replace("\n", " ")).append('\n')
        }
        append("---\n")
        append('\n')
        append(note.content)
        // Saut de ligne final garanti : certains analyseurs refusent un fichier qui n'en a pas.
        if (!note.content.endsWith("\n")) append('\n')
    }

    /**
     * Le nom de fichier d'une note, sûr pour FAT32, exFAT, ext4 et APFS.
     *
     * @param fallbackId sert quand il ne reste rien du titre après nettoyage — un titre vide, fait
     *   de caractères interdits, ou qui se réduit à un nom réservé.
     * @param fromUnlockedVault ajoute ` [unlocked]` avant l'extension. Le suffixe **n'est pas
     *   décoratif** : il dit à qui décompresse l'archive que ce fichier était protégé et ne l'est
     *   plus, alors même que rien dans son contenu ne le distingue d'une note ordinaire.
     */
    fun safeFileName(title: String, fallbackId: String, fromUnlockedVault: Boolean = false): String {
        var clean = DartTextSemantics.trim(title)
        clean = FORBIDDEN.replace(clean, "")
        clean = BIDI.replace(clean, "")
        clean = DartTextSemantics.trim(DartTextSemantics.WHITESPACE.replace(clean, " "))
        clean = DartTextSemantics.trim(tronque(clean))
        if (clean == "." || clean == "..") clean = ""
        if (clean.isEmpty() || estReserveWindows(clean)) {
            clean = "note-" + fallbackId.replace("-", "").take(8)
        }
        if (fromUnlockedVault) clean = "$clean [unlocked]"
        return "$clean.md"
    }

    /**
     * Le nom du sous-dossier d'une note dans l'archive.
     *
     * ⚠️ Le rejet de `..` n'est pas cosmétique : sans lui, un dossier ainsi nommé produit une entrée
     * `../note.md` qui, chez un destinataire dont l'outil de décompression ne s'en méfie pas,
     * s'extrait **en dehors** du répertoire choisi. C'est la faille dite « ZipSlip », et elle
     * s'écrit ici du côté de celui qui fabrique l'archive.
     *
     * ## 🔴 Aucun paramètre de langue, et c'est structurel
     *
     * Cette fonction a d'abord accepté un libellé de boîte de réception, par symétrie avec [render].
     * L'appelant lui a aussitôt passé le libellé traduit : deux exports faits par le même
     * utilisateur en français et en anglais produisaient `Boîte de réception/` et `Inbox/`, donc
     * deux arborescences pour la même chose, et une réimportation qui dédouble tout.
     *
     * Le paramètre a été retiré plutôt que corrigé chez l'appelant. Un paramètre qu'il ne faut pas
     * utiliser finit par être utilisé — la documentation qui l'interdisait était déjà écrite,
     * juste au-dessus, et n'a rien empêché.
     */
    fun safeFolderName(folder: Folder?, folderId: String): String {
        val brut = folder?.name ?: if (folderId == Folder.INBOX_ID) INBOX_DIR_NAME else folderId
        var clean = FORBIDDEN.replace(brut, "")
        clean = BIDI.replace(clean, "")
        clean = DartTextSemantics.trim(DartTextSemantics.WHITESPACE.replace(clean, " "))
        return if (estUnNomDeDossierUtilisable(clean)) clean else "sans-dossier"
    }

    /**
     * Les trois façons dont un nom de dossier peut être inutilisable dans une archive.
     *
     * Vide après nettoyage ; réduit à des points et des espaces — ce qui couvre `.` et `..`, que les
     * outils de décompression interprètent comme le répertoire courant ou parent, mais aussi `...`
     * ou `. `, que Windows refuse tout autant ; ou un nom réservé, qui fait échouer l'extraction
     * **entière** chez le destinataire et pas seulement celle du dossier fautif.
     */
    private fun estUnNomDeDossierUtilisable(nom: String): Boolean {
        if (nom.trimEnd(' ', '.').isEmpty()) return false
        return !estReserveWindows(nom)
    }

    /**
     * ⚠️⚠️ **Un nom de périphérique reste réservé sous Windows quelle que soit son extension.**
     *
     * `CON`, `CON.txt`, `CON.txt.md` et `CON.` désignent tous le même périphérique : aucun ne peut
     * être créé comme fichier ni comme dossier. Windows ignore par ailleurs les points et espaces
     * **finaux** d'un nom avant de le résoudre, si bien que `CON. ` retombe lui aussi sur `CON`.
     *
     * Les deux sites testaient l'égalité stricte avec la liste (`clean.uppercase() in
     * WINDOWS_RESERVED`). Une note titrée `CON.txt` produisait donc `CON.txt.md`, et un dossier
     * nommé `CON.txt` passait de même : l'archive devenait inextractible chez un destinataire
     * Windows, c'est-à-dire au moment précis où l'export sert.
     *
     * ⚠️ Le KDoc de [WINDOWS_RESERVED] se félicitait d'avoir corrigé un jumeau asymétrique — un site
     * appliquait la liste, l'autre l'ignorait — et concluait que « la question ne se repose pas ».
     * Les deux sites partageaient bien la même constante, mais avec le **même prédicat incomplet**.
     * Partager la donnée ne suffisait pas : c'est la décision qu'il fallait partager. Relevé par la
     * relecture externe du 2026-08-15.
     */
    private fun estReserveWindows(nom: String): Boolean =
        nom.trimEnd(' ', '.').substringBefore('.').uppercase(Locale.ROOT) in WINDOWS_RESERVED

    /**
     * Le fichier d'accueil de l'archive, pour l'utilisateur qui la rouvre dans six mois.
     *
     * Il porte la date et le compte, c'est-à-dire ce qui manque cruellement à un dossier de
     * fichiers `.md` retrouvé sans contexte.
     */
    fun readme(noteCount: Int, exportedAt: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        """
        |# Export Notes Tech
        |
        |- Exporté le : ${iso(exportedAt, zone)}
        |- Nombre de notes : $noteCount
        |
        |Format : un fichier Markdown par note, avec frontmatter YAML
        |(`title`, `folder`, `tags`, `created`, `updated`, `pinned`,
        |`favorite`). Compatible avec Obsidian, Logseq, Bear, Foam.
        |
        |L'arborescence reflète vos dossiers à la date de l'export.
        |Les notes en corbeille ne sont PAS incluses.
        |
        |Notes Tech — https://www.files-tech.com
        |
        """.trimMargin()

    /**
     * Tronque sans **jamais** couper une paire de substitution en deux.
     *
     * ⚠️ Divergence assumée avec la version publiée, consignée dans `docs/05-PARITE.md`. Son
     * `substring(0, 80)` coupe sur l'unité UTF-16 : un titre dont le 80ᵉ caractère est la première
     * moitié d'un émoji produit un nom de fichier contenant une demi-paire, que l'encodage UTF-8 de
     * l'archive remplace par un caractère de remplacement. Le résultat est un nom cassé dans les
     * deux versions ; celle-ci s'arrête un caractère plus tôt.
     *
     * Aucune donnée n'en dépend — c'est un nom de fichier, pas une clé d'appariement.
     */
    private fun tronque(value: String): String {
        if (value.length <= MAX_FILE_NAME_LENGTH) return value
        val fin = if (Character.isHighSurrogate(value[MAX_FILE_NAME_LENGTH - 1])) {
            MAX_FILE_NAME_LENGTH - 1
        } else {
            MAX_FILE_NAME_LENGTH
        }
        return value.substring(0, fin)
    }

    /**
     * Une chaîne YAML entre guillemets doubles, échappée selon YAML 1.2.
     *
     * ⚠️ **L'ordre des remplacements est le fond du sujet.** La barre oblique inverse passe en
     * premier : la traiter après aurait doublé celles que les échappements suivants viennent
     * d'introduire, et un titre contenant `"` serait ressorti avec une barre orpheline.
     *
     * Les guillemets sont toujours posés, même sur une chaîne anodine. C'est ce qui empêche YAML de
     * lire `true` comme un booléen, `2026-08-14` comme une date, ou un titre contenant `:` comme
     * une paire clé-valeur.
     */
    private fun yamlString(raw: String): String {
        val echappe = raw
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .let { avecControles ->
                YAML_CONTROL.replace(avecControles) { m ->
                    "\\x" + m.value[0].code.toString(16).padStart(2, '0')
                }
            }
        return "\"$echappe\""
    }

    private fun iso(instant: Instant, zone: ZoneId): String = ISO_LOCAL.format(LocalDateTime.ofInstant(instant, zone))
}
