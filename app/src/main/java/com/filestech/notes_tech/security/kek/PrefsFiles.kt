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
 * ## ⚠️⚠️ Deux fichiers, pas un : `<nom>.xml` ET `<nom>.xml.bak`
 *
 * `SharedPreferencesImpl` écrit ses enregistrements en deux temps : il **renomme** l'ancien fichier
 * en `.bak`, écrit le nouveau, puis efface le `.bak`. Entre ces deux instants, le contenu complet
 * des préférences vit dans le fichier de sauvegarde — et si le processus meurt là (batterie, arrêt
 * forcé, ou quelqu'un qui coupe l'appareil en voyant ce qui se passe), le `.bak` reste sur le disque
 * avec **tout l'ancien contenu**, sans que `<nom>.xml` existe.
 *
 * La version précédente sortait alors par le `return false` du haut, sans rien supprimer ni rien
 * signaler : sur les trois fichiers que la panique vise ici — `notes_tech.kek`,
 * `FlutterSecureStorage`, `FlutterSecureKeyStorage` — cela veut dire une clé enveloppée intacte,
 * dans une étape qui vient de se déclarer réussie.
 *
 * Le contrôle porte donc sur les deux noms, avant comme après. Signalé comme PROBABLE par la
 * relecture externe du 2026-08-15, sur la seule constatation que le `.bak` n'était pas vérifié ; le
 * chemin qui le rend atteignable est la fenêtre d'écriture ci-dessus.
 *
 * ⚠️ Après cet appel, toute instance de `SharedPreferences` déjà obtenue pour ce nom a un contenu
 * indéfini. C'est acceptable ici et nulle part ailleurs : la panique est terminale.
 *
 * @return `true` si un fichier a été supprimé, `false` s'il n'y avait rien.
 * @throws IllegalStateException si un fichier existait et a résisté.
 */
internal fun supprimerLeFichierDePreferences(context: Context, nom: String): Boolean {
    val repertoire = File(context.applicationInfo.dataDir, "shared_prefs")
    val fichiers = listOf(File(repertoire, "$nom.xml"), File(repertoire, "$nom.xml.bak"))
    if (fichiers.none { it.exists() }) return false

    // `deleteSharedPreferences` supprime déjà les deux et vide le cache mémoire. On ne le croit pas
    // sur parole pour autant : c'est le contrôle qui suit, et lui seul, qui empêche l'étape de
    // mentir.
    context.deleteSharedPreferences(nom)
    fichiers.forEach { it.delete() }

    val survivants = fichiers.count { it.exists() }
    if (survivants > 0) error("fichier de preferences « $nom » non supprime")
    return true
}
