package com.filestech.notes_tech.domain.voice

/**
 * Les modèles de transcription que l'application sait vérifier.
 *
 * ## 🔴 Ce catalogue est une liste d'**empreintes**, pas une liste de suggestions
 *
 * L'application n'a aucun moyen de télécharger un modèle — ni permission réseau, ni API pour le
 * faire, cf. [SpeechToText]. Le fichier arrive de l'extérieur : l'utilisateur le récupère sur un
 * ordinateur et le transfère, ou le télécharge avec son navigateur. Autrement dit, **le seul
 * contrôle d'origine qui existe est celui-ci** : une empreinte écrite dans le code, comparée à ce
 * qui a été copié.
 *
 * Conséquence directe : un modèle absent de ce catalogue ne peut pas être importé. Ce n'est pas une
 * limitation à contourner — un binaire de plusieurs dizaines de mégaoctets, exécuté par une
 * bibliothèque native sur le contenu des notes, ne se charge pas sur la foi du nom de son fichier.
 *
 * ## Tenir les empreintes à jour
 *
 * Elles se calculent depuis le fichier publié :
 *
 * ```
 * sha256sum ggml-base-q5_1.bin
 * ```
 *
 * ⚠️ **Si la source republie un fichier sous le même nom, l'import le refusera** — et c'est le
 * comportement voulu, pas une panne. Le remède est de recalculer l'empreinte et de la corriger ici,
 * dans le même commit que la mise à jour, jamais d'assouplir le contrôle.
 */
object SttModelCatalogue {

    /**
     * D'où viennent ces fichiers, **en texte affichable**.
     *
     * ⚠️ Affichée à l'utilisateur pour qu'il sache où aller ; elle n'est ouverte par aucun code de
     * l'application. Le jour où un écran voudrait en faire un lien, ce serait une décision à peser :
     * confier l'adresse au navigateur ne demande aucune permission, mais l'application cesserait
     * d'être celle qui « ne parle à personne » du point de vue de l'utilisateur.
     */
    const val SOURCE_PUBLIQUE = "huggingface.co/ggerganov/whisper.cpp"

    /**
     * Le modèle conseillé : qualité française correcte pour une taille raisonnable.
     *
     * Tourne sans peine sur l'appareil de test le plus modeste du parc — un S9 de 2018, 4 Go de
     * mémoire.
     */
    val whisperBaseQ5 = SttModel(
        id = "whisper-base-q5_1",
        displayName = "Whisper Base",
        expectedSha256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
        sizeBytes = 59_700_000L,
        language = "auto",
        notes = "Conseille. Bonne qualite en francais, environ 3 s de calcul pour 5 s de parole.",
        fichierAmont = "ggml-base-q5_1.bin",
    )

    /** Deux fois plus rapide et deux fois plus léger, au prix d'une qualité française approximative. */
    val whisperTinyQ5 = SttModel(
        id = "whisper-tiny-q5_1",
        displayName = "Whisper Tiny",
        expectedSha256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
        sizeBytes = 32_200_000L,
        language = "auto",
        notes = "Leger et rapide, qualite en francais approximative. Pour les appareils modestes.",
        fichierAmont = "ggml-tiny-q5_1.bin",
    )

    /** Dans l'ordre d'affichage : le conseillé d'abord. */
    val tous: List<SttModel> = listOf(whisperBaseQ5, whisperTinyQ5)

    /** Celui que l'écran d'installation propose en premier. */
    val parDefaut: SttModel = whisperBaseQ5

    /**
     * Le modèle portant cet identifiant, ou `null`.
     *
     * ⚠️ **`null` est un cas normal, pas une anomalie** : une préférence peut désigner un modèle
     * retiré du catalogue par une mise à jour de l'application. L'appelant doit alors oublier la
     * préférence et reconduire vers l'installation, jamais échouer.
     */
    fun parIdentifiant(id: String): SttModel? = tous.firstOrNull { it.id == id }
}
