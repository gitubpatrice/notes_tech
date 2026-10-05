package com.filestech.notes_tech.domain.voice

/**
 * Les échecs de la dictée, **classés par ce que l'utilisateur peut y faire**.
 *
 * ## 🔴 Pourquoi une hiérarchie et non un message
 *
 * C'est la leçon déjà payée deux fois dans ce dépôt. `VaultAttempt` a dû cesser de transporter
 * `exception.message` — du texte interne, non traduit — parce que l'écran le montrait tel quel dans
 * les deux langues. Et le classement des échecs de coffre porte sur le **type**, jamais sur le
 * texte : un classement par message se casse à la première traduction.
 *
 * Ici la distinction décide de ce qu'on propose : réimporter un modèle, ouvrir les réglages système,
 * ou simplement réessayer. Trois réponses différentes, qu'un « échec de la dictée » unique rendrait
 * impossibles à choisir.
 *
 * ⚠️ Scellée : un `when` exhaustif fera **échouer la compilation** le jour où un cas s'ajoutera sans
 * chaîne pour le dire. C'est le même mécanisme que `VaultValidationException.Reason`.
 *
 * ⚠️⚠️ **Chaque cas porte une [cause] facultative, et ce n'est pas de la décoration.** Le message
 * seul suffit à l'écran ; il ne suffit pas à un diagnostic. Une `SecurityException` du micro ou une
 * panne de bibliothèque native perdue en route, c'est un rapport d'incident où il ne reste que
 * « capture impossible ». Ce que l'utilisateur voit et ce que la trace retient sont deux besoins
 * différents — le premier ne doit pas amputer le second.
 */
sealed class SttException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Le modèle n'est pas — ou plus — sur l'appareil.
 *
 * L'interface doit conduire vers l'import, jamais proposer de réessayer : rien ne changera.
 */
class SttModelMissingException(message: String, cause: Throwable? = null) : SttException(message, cause)

/**
 * L'empreinte du fichier ne correspond plus à celle attendue.
 *
 * ⚠️⚠️ **Le fichier est supprimé, et ce n'est pas un ménage.** Un modèle est un binaire exécuté par
 * une bibliothèque native sur des centaines de mégaoctets ; celui-ci n'est pas celui qu'on croit —
 * corruption disque, remplacement, import interrompu. Le garder, c'est laisser l'utilisateur
 * réessayer avec exactement le même fichier.
 */
class SttModelChecksumMismatchException(message: String, cause: Throwable? = null) : SttException(message, cause)

/**
 * Le fichier choisi pour l'import n'est pas celui qu'on attend.
 *
 * Illisible, disparu entre le choix et la lecture, d'une taille sans rapport avec le modèle visé, ou
 * servi par un fournisseur de contenu qui n'avance plus.
 *
 * ⚠️ **Distinct de [SttModelChecksumMismatchException], et la différence est ce qu'on propose.** Ici
 * l'utilisateur s'est probablement trompé de fichier, et le geste utile est d'en choisir un autre.
 * Là-bas, le fichier était le bon en taille mais son contenu ne correspond pas — téléchargement
 * interrompu, disque abîmé, source douteuse — et le geste utile est de le retélécharger.
 */
class SttModelSourceInvalidException(message: String, cause: Throwable? = null) : SttException(message, cause)

/**
 * Il n'y a pas la place d'écrire le modèle.
 *
 * ⚠️ **Un cas à part, parce que c'est le seul dont le remède n'est pas dans l'application.** Un
 * modèle pèse des dizaines de mégaoctets ; sur un téléphone plein, l'import échouerait de toute
 * façon, mais au milieu de la copie et sous la forme d'une panne d'écriture opaque. Le contrôle
 * préalable existe pour pouvoir dire « libérez de la place » plutôt que « l'import a échoué ».
 */
class SttModelStorageFullException(message: String, cause: Throwable? = null) : SttException(message, cause)

/**
 * L'import a échoué pour une raison technique — écriture, renommage, répertoire inaccessible.
 *
 * Le fourre-tout **assumé** de la hiérarchie : ce qui reste quand aucun des cas précédents ne
 * s'applique. La seule chose à proposer est de réessayer.
 *
 * ⚠️ Il existe pour que l'import ne laisse **jamais** échapper une exception hors de [SttException].
 * Sans lui, une `IOException` traverserait un `when` exhaustif chez l'appelant — c'est le défaut que
 * `SpeechToText.transcribeFile` a déjà eu à documenter.
 */
class SttModelImportFailedException(message: String, cause: Throwable? = null) : SttException(message, cause)

/**
 * La permission `RECORD_AUDIO` a été refusée.
 *
 * @param permanently `true` quand le refus est définitif — « ne plus demander ». La distinction
 *   n'est pas cosmétique : redemander la permission dans ce cas-là est **ignoré en silence par
 *   Android**, et l'utilisateur voit un bouton qui ne fait rien. Le seul recours est d'ouvrir les
 *   réglages système, et l'interface doit alors proposer *ça* — la chaîne
 *   `voice_open_system_settings` existe et attend exactement ce cas.
 *
 *   ⚠️⚠️ **Seule l'interface peut renseigner ce drapeau à `true`.** `VoiceCapture` lève toujours
 *   avec `false`, et ce n'est pas un oubli : le caractère définitif d'un refus se lit par
 *   `shouldShowRequestPermissionRationale`, qui demande une `Activity`. Une couche de données n'en
 *   a pas, et lui en donner une pour ça serait faire remonter l'interface dans le service.
 *
 *   La conséquence est à connaître : **si l'interface ne recalcule pas le drapeau, l'utilisateur ne
 *   se verra jamais proposer les réglages système** et retentera une demande qu'Android ignore en
 *   silence. Relevé par une relecture externe (Gemini, 2026-08-15) — le contrat était juste, mais
 *   personne n'était désigné pour le tenir.
 */
class SttPermissionDeniedException(message: String, val permanently: Boolean = false, cause: Throwable? = null) :
    SttException(message, cause)

/** La bibliothèque native n'a pas démarré : absente, modèle illisible, mémoire insuffisante. */
class SttEngineUnavailableException(message: String, cause: Throwable? = null) : SttException(message, cause)

/**
 * La capture micro a échoué.
 *
 * Matériel indisponible, micro monopolisé par une autre application, appel entrant. ⚠️ Distinct de
 * [SttPermissionDeniedException] : ici la permission est **accordée**, et proposer d'ouvrir les
 * réglages n'aurait aucun sens.
 */
class SttRecordingFailedException(message: String, cause: Throwable? = null) : SttException(message, cause)

/** La transcription a échoué : audio illisible, délai dépassé, mémoire insuffisante. */
class SttTranscriptionFailedException(message: String, cause: Throwable? = null) : SttException(message, cause)
