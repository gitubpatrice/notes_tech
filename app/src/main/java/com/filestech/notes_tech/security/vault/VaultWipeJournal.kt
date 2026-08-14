package com.filestech.notes_tech.security.vault

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
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
 */
@Singleton
class VaultWipeJournal @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs get() = context.getSharedPreferences(FLUTTER_PREFS, Context.MODE_PRIVATE)

    /** Note qu'un effacement commence. À appeler **avant** de toucher au Keystore. */
    fun markPending(folderId: String) {
        prefs.edit { putBoolean(keyFor(folderId), true) }
    }

    /** Note qu'il s'est terminé. À appeler **après** le dernier geste, jamais avant. */
    fun clearPending(folderId: String) {
        prefs.edit { remove(keyFor(folderId)) }
    }

    /**
     * Les coffres dont l'effacement n'a pas été mené à son terme.
     *
     * Balaie toutes les clés plutôt que d'interroger une liste connue : au premier démarrage après
     * la bascule, la seule trace d'un effacement interrompu par la version Flutter est ici.
     */
    fun pendingFolderIds(): List<String> = prefs.all.keys
        .filter { it.startsWith(PREFIXED) }
        .map { it.removePrefix(PREFIXED) }

    private fun keyFor(folderId: String) = "$PREFIXED$folderId"

    private companion object {
        /** Le fichier qu'écrit le greffon `shared_preferences` sur Android. */
        const val FLUTTER_PREFS = "FlutterSharedPreferences"

        /** `flutter.` + `vault_wipe_pending_`. Voir l'avertissement de la classe. */
        const val PREFIXED = "flutter.${VaultParams.WIPE_PENDING_PREF_PREFIX}"
    }
}
