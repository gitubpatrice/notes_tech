package com.filestech.notes_tech.security.kek

import android.content.Context
import java.io.File

/**
 * Suppression d'un fichier de préférences **entier**, pour le mode panique.
 *
 * ## 🔴 Pourquoi vider ne suffit pas
 *
 * `getSharedPreferences(nom, …).edit().clear().commit()` **crée le fichier s'il n'existe pas**.
 * Mesuré sur le S9 le 2026-08-14 : une panique sur un appareil qui n'avait jamais vu la version
 * Flutter faisait apparaître `FlutterSecureStorage.xml` et `FlutterSecureKeyStorage.xml` dans le
 * répertoire de l'application — deux fichiers vides, portant le mot « SecureStorage », créés par le
 * geste censé tout effacer.
 *
 * Aucun secret n'y était, mais l'écran de fin venait d'annoncer qu'il ne restait rien. Ce qui est
 * faux d'un octet est faux.
 *
 * ## Ce que fait cette fonction
 *
 * Rien si le fichier n'existe pas — c'est le cas normal d'une installation neuve, et ce n'est pas un
 * échec. Sinon `deleteSharedPreferences`, qui retire le fichier **et** la copie en mémoire, puis un
 * contrôle : sans lui, l'étape se déclarerait réussie sur un fichier resté en place.
 *
 * ⚠️ Après cet appel, toute instance de `SharedPreferences` déjà obtenue pour ce nom a un contenu
 * indéfini. C'est acceptable ici et nulle part ailleurs : la panique est terminale.
 *
 * @return `true` si un fichier a été supprimé, `false` s'il n'y avait rien.
 * @throws IllegalStateException si le fichier existait et a résisté.
 */
internal fun supprimerLeFichierDePreferences(context: Context, nom: String): Boolean {
    val fichier = File(File(context.applicationInfo.dataDir, "shared_prefs"), "$nom.xml")
    if (!fichier.exists()) return false
    context.deleteSharedPreferences(nom)
    if (fichier.exists()) error("fichier de preferences « $nom » non supprime")
    return true
}
