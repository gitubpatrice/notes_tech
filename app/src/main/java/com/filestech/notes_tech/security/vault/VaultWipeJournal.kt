package com.filestech.notes_tech.security.vault

import com.filestech.notes_tech.data.prefs.LegacyPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Le drapeau « un effacement de coffre était en cours ».
 *
 * ## Pourquoi un drapeau, et pas une transaction
 *
 * Effacer un coffre à code touche trois mondes qu'aucune transaction ne couvre ensemble : le
 * Keystore, la base, et les préférences. Une interruption entre deux — l'application tuée, la
 * batterie vide — laisserait des notes chiffrées par une clé qui n'existe plus, dans un dossier qui
 * se présente encore comme un coffre. Personne ne le verrait, et la prochaine saisie du code
 * échouerait sans expliquer pourquoi.
 *
 * Le drapeau est posé **avant** le premier geste et retiré **après** le dernier. S'il est encore là
 * au démarrage suivant, l'effacement est repris.
 *
 * ## ⚠️ Le préfixe `flutter.` n'est pas décoratif
 *
 * Le greffon `shared_preferences` préfixe automatiquement toutes ses clés. La clé réellement écrite
 * sur le disque par l'application publiée est `flutter.vault_wipe_pending_<id>`, pas
 * `vault_wipe_pending_<id>`. Lire sans le préfixe ne trouverait jamais un effacement interrompu par
 * la version Flutter — c'est-à-dire précisément le cas que ce mécanisme existe pour rattraper, au
 * premier démarrage après la bascule. Cf. `docs/02-SCHEMA-HERITE.md` §5.
 *
 * Ce préfixe, et le nom du fichier, sont désormais la connaissance de [LegacyPreferences] **et
 * d'elle seule**. Cette classe les portait aussi, en copie, jusqu'au 2026-08-14 : deux endroits
 * décidaient de l'emplacement du même fichier, et une correction sur l'un aurait laissé l'autre en
 * arrière — le jumeau asymétrique dont `docs/04-PIEGES.md` §25 fait le motif le plus tenace du
 * portage.
 */
@Singleton
class VaultWipeJournal @Inject constructor(private val prefs: LegacyPreferences) {

    /** Note qu'un effacement commence. À appeler **avant** de toucher au Keystore. */
    fun markPending(folderId: String) {
        prefs.putBoolean(keyFor(folderId), true)
    }

    /** Note qu'il s'est terminé. À appeler **après** le dernier geste, jamais avant. */
    fun clearPending(folderId: String) {
        prefs.remove(keyFor(folderId))
    }

    /**
     * Les coffres dont l'effacement n'a pas été mené à son terme.
     *
     * Balaie toutes les clés plutôt que d'interroger une liste connue : au premier démarrage après
     * la bascule, la seule trace d'un effacement interrompu par la version Flutter est ici.
     */
    fun pendingFolderIds(): List<String> = prefs.keysStartingWith(VaultParams.WIPE_PENDING_PREF_PREFIX)
        .map { it.removePrefix(VaultParams.WIPE_PENDING_PREF_PREFIX) }

    private fun keyFor(folderId: String) = "${VaultParams.WIPE_PENDING_PREF_PREFIX}$folderId"
}
