package com.filestech.notes_tech.data.local

import android.content.Context
import java.io.File

/**
 * Où vit le fichier de base — et pourquoi il n'est pas là où on l'attendrait.
 *
 * ## Le répertoire s'appelle `app_flutter`, et il reste
 *
 * La version Flutter ouvre sa base dans `getApplicationDocumentsDirectory()`
 * (`notes_tech/lib/data/db/database.dart:75`), pas dans le répertoire `databases/` où Room place
 * les siennes. Sur Android, `path_provider` implémente cet appel par
 * `context.getDir("flutter", MODE_PRIVATE)`, soit :
 *
 * ```
 * /data/user/0/com.filestech.notes_tech/app_flutter/notes_tech.db
 * ```
 *
 * ⚠️ **Ne pas « ranger » ce fichier dans `databases/`.** Un lecteur futur verra un répertoire
 * nommé d'après un framework que l'application n'utilise plus et sera tenté de nettoyer. Ce
 * déplacement suppose de fermer proprement la base, de rabattre le journal WAL, puis de déplacer
 * trois fichiers de façon atomique et de survivre à une interruption entre les deux. Le bénéfice
 * est cosmétique ; le risque est la perte de toutes les notes. Cf. `docs/01-DECISIONS.md` D-003.
 *
 * Le déplacement reste possible plus tard, comme lot dédié et testé. Il est **différé, pas
 * oublié**.
 *
 * ## Pourquoi pas un chemin en dur
 *
 * `getDir("flutter", MODE_PRIVATE)` reproduit exactement ce que faisait `path_provider`, en
 * demandant le chemin au système plutôt qu'en le supposant. Un chemin littéral casserait sur les
 * profils secondaires et les profils professionnels, où le préfixe n'est pas `/data/user/0/`.
 */
object LegacyDatabaseLocation {

    /** Nom du répertoire créé par `path_provider` côté Flutter. Ne pas changer. */
    private const val FLUTTER_DOCUMENTS_DIR = "flutter"

    /** `notes_tech/lib/core/constants.dart:16`. */
    const val DATABASE_FILE_NAME = "notes_tech.db"

    /**
     * Suffixes des fichiers annexes du journal WAL. Toute opération sur le fichier de base doit
     * les traiter comme un ensemble : un `-wal` désaccordé avec son `.db` se lit comme une
     * corruption.
     */
    val SIDECAR_SUFFIXES = listOf("", "-wal", "-shm")

    fun databaseFile(context: Context): File =
        File(context.getDir(FLUTTER_DOCUMENTS_DIR, Context.MODE_PRIVATE), DATABASE_FILE_NAME)

    /**
     * `true` si une base existe déjà sur le disque.
     *
     * **C'est le test qui distingue une première installation d'un utilisateur qui migre**, et
     * donc celui qui décide si l'absence de KEK est normale ou catastrophique.
     * Cf. `docs/03-KEK-ACQUISITION.md` §2, couche ③.
     */
    fun databaseExists(context: Context): Boolean = databaseFile(context).exists()
}
