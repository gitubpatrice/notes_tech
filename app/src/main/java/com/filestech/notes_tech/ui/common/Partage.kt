package com.filestech.notes_tech.ui.common

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Envoie un fichier du cache privé vers une autre application.
 *
 * ## ⚠️ La permission voyage avec l'intention, et seulement avec elle
 *
 * `FLAG_GRANT_READ_URI_PERMISSION` est ce qui donne au destinataire le droit de lire **ce fichier**,
 * pour la durée de l'intention. Sans lui, le partage échoue par un `SecurityException` chez le
 * destinataire — c'est-à-dire loin d'ici, dans une trace qui ne nomme pas cette ligne.
 *
 * L'inverse — rendre le répertoire lisible de tous pour éviter la question — donnerait à n'importe
 * quelle application installée l'accès permanent aux archives d'export, qui contiennent le texte des
 * notes en clair.
 *
 * ## Pourquoi passer par un sélecteur
 *
 * `Intent.createChooser` résout toujours, même quand aucune application ne sait traiter le type :
 * l'utilisateur voit une liste vide plutôt qu'un plantage. Un `startActivity` sur l'intention nue
 * lèverait `ActivityNotFoundException`, sur un appareil dépouillé, au moment le plus inattendu.
 */
fun partagerUnFichier(context: Context, uri: Uri, mimeType: String, sujet: String?, titreDuSelecteur: String) {
    val envoi = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        if (sujet != null) putExtra(Intent.EXTRA_SUBJECT, sujet)
        // 🔴 **`clipData` n'est pas un doublon de `EXTRA_STREAM`.**
        //
        // Le système ne propage une permission d'URI que pour ce qu'il **voit** dans l'intention :
        // sa donnée principale et ses `ClipData`. Un fichier passé par le seul `EXTRA_STREAM` est un
        // extra parmi d'autres, que rien n'inspecte. Sans cette ligne, le sélecteur de partage
        // échoue à lire le fichier pour en construire l'aperçu — mesuré sur le S9 le 2026-08-14 :
        //
        //     SecurityException: Permission Denial: reading FileProvider uri … from uid=1000
        //     ChooserActivity: extract fail
        //
        // Le partage vers l'application choisie fonctionnait quand même, ce qui rend le défaut
        // presque invisible : seul l'aperçu manquait, et l'exception restait dans le journal.
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val selecteur = Intent.createChooser(envoi, titreDuSelecteur).apply {
        // ⚠️ Le drapeau doit être porté par le SÉLECTEUR aussi : c'est lui qui relaie l'intention à
        // l'application choisie, et une permission posée seulement sur l'intention interne ne
        // franchit pas ce relais sur toutes les versions d'Android.
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(selecteur)
}

/** Le type d'une archive d'export. */
const val MIME_ZIP = "application/zip"

/**
 * Le type d'une note exportée seule.
 *
 * `text/markdown` et non `text/plain` : c'est ce que produit la version publiée, via le calcul de
 * type par extension de `share_plus`. Le choix a une conséquence visible — les applications de
 * messagerie qui incorporeraient un `text/plain` dans le corps du message attachent un
 * `text/markdown` comme fichier.
 */
const val MIME_MARKDOWN = "text/markdown"
