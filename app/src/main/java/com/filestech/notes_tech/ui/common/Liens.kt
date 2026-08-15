package com.filestech.notes_tech.ui.common

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import timber.log.Timber

/**
 * Ouvre un lien **hors de l'application**, et copie l'adresse si personne ne sait l'ouvrir.
 *
 * ## 🔴 Le repli n'est pas une politesse, c'est ce qui rend le lien utilisable
 *
 * Un appareil sans navigateur, sans client de courrier, ou dont le profil professionnel bloque
 * l'ouverture externe, lève `ActivityNotFoundException`. Sans repli, toucher « Nous écrire » ne
 * ferait **rien du tout** — un geste sans effet et parfaitement silencieux, ce que ce dépôt refuse
 * partout ailleurs. L'application publiée copie l'adresse dans le presse-papiers et le dit
 * (`about_screen.dart:339-350`) ; c'est repris tel quel.
 *
 * ## ⚠️ Ce que cette fonction ne fait PAS
 *
 * Elle n'ouvre rien depuis l'intérieur de l'application : elle **délègue au système**. Notes Tech
 * n'a pas la permission `INTERNET`, et c'est une promesse publique — un lien ne se charge donc
 * jamais ici, il part chez une application qui, elle, a le droit.
 *
 * @param onCopie appelé quand l'adresse a été copiée faute d'application pour l'ouvrir. L'appelant
 *   affiche le message : cette fonction ne connaît ni `SnackbarHostState` ni composition.
 */
fun ouvrirUnLien(context: Context, url: String, onCopie: () -> Unit) {
    val intention = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intention)
    } catch (e: ActivityNotFoundException) {
        Timber.i(e, "aucune application pour ouvrir un lien, repli sur le presse-papiers")
        copierDansLePressePapiers(context, url)
        onCopie()
    }
}

private fun copierDansLePressePapiers(context: Context, texte: String) {
    val presse = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    // ⚠️ L'étiquette est visible dans l'historique du presse-papiers de certains claviers. Elle ne
    // porte donc que le nom de l'application, jamais le contenu.
    presse.setPrimaryClip(ClipData.newPlainText("Notes Tech", texte))
}
