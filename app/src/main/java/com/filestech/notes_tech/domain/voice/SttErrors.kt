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
 */
sealed class SttException(message: String) : Exception(message)

/**
 * Le modèle n'est pas — ou plus — sur l'appareil.
 *
 * L'interface doit conduire vers l'import, jamais proposer de réessayer : rien ne changera.
 */
class SttModelMissingException(message: String) : SttException(message)

/**
 * L'empreinte du fichier ne correspond plus à celle attendue.
 *
 * ⚠️⚠️ **Le fichier est supprimé, et ce n'est pas un ménage.** Un modèle est un binaire exécuté par
 * une bibliothèque native sur des centaines de mégaoctets ; celui-ci n'est pas celui qu'on croit —
 * corruption disque, remplacement, import interrompu. Le garder, c'est laisser l'utilisateur
 * réessayer avec exactement le même fichier.
 */
class SttModelChecksumMismatchException(message: String) : SttException(message)

/**
 * La permission `RECORD_AUDIO` a été refusée.
 *
 * @param permanently `true` quand le refus est définitif — « ne plus demander ». La distinction
 *   n'est pas cosmétique : redemander la permission dans ce cas-là est **ignoré en silence par
 *   Android**, et l'utilisateur voit un bouton qui ne fait rien. Le seul recours est d'ouvrir les
 *   réglages système, et l'interface doit alors proposer *ça* — la chaîne
 *   `voice_open_system_settings` existe et attend exactement ce cas.
 */
class SttPermissionDeniedException(message: String, val permanently: Boolean = false) : SttException(message)

/** La bibliothèque native n'a pas démarré : absente, modèle illisible, mémoire insuffisante. */
class SttEngineUnavailableException(message: String) : SttException(message)

/**
 * La capture micro a échoué.
 *
 * Matériel indisponible, micro monopolisé par une autre application, appel entrant. ⚠️ Distinct de
 * [SttPermissionDeniedException] : ici la permission est **accordée**, et proposer d'ouvrir les
 * réglages n'aurait aucun sens.
 */
class SttRecordingFailedException(message: String) : SttException(message)

/** La transcription a échoué : audio illisible, délai dépassé, mémoire insuffisante. */
class SttTranscriptionFailedException(message: String) : SttException(message)
