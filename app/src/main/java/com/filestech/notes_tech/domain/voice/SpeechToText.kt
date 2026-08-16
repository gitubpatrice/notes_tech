package com.filestech.notes_tech.domain.voice

/**
 * Le contrat du moteur de transcription **sur l'appareil**.
 *
 * ## Pourquoi ce fichier existe avant son implémentation
 *
 * `D-002` décidait dès la phase 1 d'isoler la dictée derrière une interface de domaine, « pour que
 * son absence temporaire ne contamine aucune autre couche ». La décision était écrite, l'interface
 * ne l'était pas : le paquet `domain/` ne contenait que `export`, `links`, `model` et `repository`.
 *
 * ⚠️⚠️ **Une décision qui décrit une isolation que le code ne porte pas est du même genre qu'un
 * commentaire qui ment** — elle se lit comme un acquis. Relevé le 2026-08-15, en préparant la
 * phase 7. Le contrat est donc posé ici **avant** le moteur, ce qui est l'ordre que la décision
 * annonçait.
 *
 * ## Ce que ce contrat isole
 *
 * L'implémentation visée est whisper.cpp en JNI. Elle n'existe pas encore, et rien de ce qui est
 * décrit ici n'en dépend : un autre moteur — `sherpa-onnx`, une variante future — se substituerait
 * sans toucher aux appelants. C'est la même raison qui a fait poser `VaultOpener` à côté de
 * `VaultSealer` : une dépendance à « transcrire » ne doit pas donner « enregistrer » en prime.
 *
 * ## 🔴 Ce que ce contrat REFUSE, et c'est délibéré
 *
 * Le contrat Dart d'origine (`files_tech_voice`) expose un **téléchargeur** de modèles. Il n'a pas
 * d'équivalent ici, et n'en aura pas : Notes Tech retire `INTERNET` de son manifeste fusionné —
 * vérifié, `tools:node="remove"` côté publié, aucune permission réseau côté portage. Le modèle
 * s'obtient par **import d'un fichier choisi par l'utilisateur**, jamais par le réseau.
 *
 * L'application publiée fait déjà exactement ça : elle embarque le téléchargeur du plugin mais
 * n'appelle que `fileFor`, `isInstalled`, `uninstall` et `purgeTempCaptures` — **jamais** la
 * descente réseau. Ce qui était une discipline d'appel devient ici une **absence d'API**.
 *
 * ## Cycle de vie
 *
 * 1. [initialize] avec un [SttModel] déjà présent sur l'appareil. Idempotent : rappeler avec le
 *    même modèle ne fait rien.
 * 2. autant d'appels à [transcribeFile] que voulu ;
 * 3. [dispose] rend le contexte natif.
 */
interface SpeechToText {

    /** `true` entre une [initialize] réussie et [dispose]. */
    val isInitialized: Boolean

    /** Le modèle chargé, ou `null` si le moteur ne l'est pas. */
    val loadedModel: SttModel?

    /**
     * Charge le modèle natif.
     *
     * @throws SttModelMissingException le fichier n'est pas là.
     * @throws SttModelChecksumMismatchException l'empreinte ne correspond plus — fichier corrompu
     *   ou remplacé. **Le fichier est alors supprimé** : un modèle dont on ne peut pas prouver
     *   l'origine ne se charge pas, et le garder inviterait à réessayer.
     * @throws SttEngineUnavailableException la bibliothèque native ne démarre pas.
     *
     * ⚠️ **Rappelée avec un modèle DIFFÉRENT alors qu'un autre est chargé, elle remplace.** Le
     * contrat ne disait rien de ce cas, et une implémentation aurait pu aussi bien l'ignorer,
     * lever, ou charger deux moteurs. Le remplacement est le seul comportement qui ne surprenne
     * personne : l'appelant demande explicitement un autre modèle. Relevé par une relecture externe
     * (Gemini, 2026-08-15) comme non spécifié — et un contrat muet se tranche par l'implémentation,
     * c'est-à-dire au mauvais endroit.
     */
    suspend fun initialize(model: SttModel)

    /**
     * Transcrit un fichier audio.
     *
     * Format garanti : **WAV PCM 16 bits, mono, 16 kHz**. D'autres peuvent passer selon le moteur ;
     * aucun comportement n'est promis pour eux.
     *
     * @param language code ISO 639-1 forcé (`"fr"`, `"en"`). Omis, le moteur suit la langue du
     *   modèle — détection automatique s'il est multilingue.
     * @throws SttTranscriptionFailedException audio illisible, mémoire insuffisante, délai dépassé.
     * @throws SttEngineUnavailableException appelée alors que [isInitialized] est `false`.
     *
     * ⚠️ **Ce dernier cas était non spécifié**, et c'est plus grave qu'il n'y paraît : sans lui, une
     * implémentation aurait naturellement levé une `IllegalStateException`, donc **hors** de la
     * hiérarchie scellée. Un `when` exhaustif chez l'appelant aurait alors laissé passer l'échec le
     * plus banal de tous — le moteur pas encore chargé. Relevé par une relecture externe (GPT-5.2,
     * 2026-08-15).
     */
    suspend fun transcribeFile(audioPath: String, language: String? = null): SttTranscription

    /** Rend les ressources natives. Idempotent. */
    suspend fun dispose()
}

/**
 * Le résultat d'une transcription.
 *
 * @param segments découpage horodaté. L'interface n'en affiche que [text] ; les segments sont
 *   conservés parce qu'ils sont **produits gratuitement** par le moteur et qu'un surlignage au fil
 *   de la lecture ne serait pas reconstructible après coup.
 * @param durationMillis durée de l'audio transcrit. Millisecondes en `Long`, comme partout ailleurs
 *   dans ce dépôt.
 */
data class SttTranscription(
    val text: String,
    val segments: List<SttSegment>,
    val detectedLanguage: String,
    val durationMillis: Long,
) {
    /** ⚠️ Sur le texte **rogné** : un moteur qui ne rend que des espaces n'a rien transcrit. */
    val isEmpty: Boolean get() = text.isBlank()
}

/** Un segment horodaté, depuis le début de l'audio fourni. */
data class SttSegment(val text: String, val startMillis: Long, val endMillis: Long)

/**
 * Un modèle de transcription présent sur l'appareil.
 *
 * ⚠️ **Sans `url`, contrairement au modèle Dart.** Le portage n'a pas de chemin de téléchargement :
 * porter le champ inviterait à en écrire un. Cf. la note de [SpeechToText].
 *
 * @param expectedSha256 empreinte attendue, en hexadécimal minuscule. Vérifiée **à chaque
 *   chargement**, pas seulement à l'import : un fichier peut être remplacé entre les deux.
 * @param sizeBytes taille attendue. Sert de garde-fou avant même de hacher plusieurs centaines de
 *   mégaoctets — un fichier de taille absurde est rejeté sans lecture complète. ⚠️ **Approximative
 *   et sans autorité** : c'est l'empreinte qui décide. D'où la tolérance large de l'import.
 * @param fichierAmont le nom du fichier à récupérer, tel qu'il s'appelle à la source.
 *
 *   ⚠️ **Ce n'est pas l'`url` qu'on a refusée.** Sans le nom exact, l'utilisateur ne peut pas faire
 *   l'import du tout : la page amont propose une trentaine de variantes dont les noms ne diffèrent
 *   que par un suffixe, et se tromper coûte un téléchargement de plusieurs dizaines de mégaoctets
 *   pour finir sur une empreinte qui ne correspond pas. Un nom de fichier se lit à l'écran et se
 *   recopie ; il n'ouvre aucun chemin réseau, là où un champ d'adresse invite à en écrire un.
 */
data class SttModel(
    val id: String,
    val displayName: String,
    val expectedSha256: String,
    val sizeBytes: Long,
    val language: String,
    val notes: String,
    val fichierAmont: String,
) {
    /**
     * Le nom du fichier local.
     *
     * 🔴 **La validation de [id] est une barrière de chemin, pas une coquetterie.** L'identifiant
     * sert de nom de fichier ; un `..` ou un séparateur y ferait écrire hors du répertoire des
     * modèles. Le catalogue est aujourd'hui en dur dans le code, donc le risque est nul — mais
     * c'est exactement ce qu'on disait des noms de dossier d'archive avant d'y trouver trois
     * défauts (`04-PIEGES.md`).
     */
    val fileName: String
        get() {
            require(MOTIF_ID_SUR.matches(id)) {
                "identifiant de modele invalide : attendu ^[a-z0-9_-]+$, recu \"$id\""
            }
            return "$id.bin"
        }

    private companion object {
        val MOTIF_ID_SUR = Regex("^[a-z0-9_-]+$")
    }
}
