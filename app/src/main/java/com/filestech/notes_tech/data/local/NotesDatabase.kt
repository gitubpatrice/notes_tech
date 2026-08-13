package com.filestech.notes_tech.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.filestech.notes_tech.data.local.dao.FolderDao
import com.filestech.notes_tech.data.local.dao.NoteDao
import com.filestech.notes_tech.data.local.dao.NoteLinkDao
import com.filestech.notes_tech.data.local.dao.NoteSearchDao
import com.filestech.notes_tech.data.local.dao.NoteWriteDao
import com.filestech.notes_tech.data.local.entity.FolderEntity
import com.filestech.notes_tech.data.local.entity.NoteEntity

/**
 * Base héritée de Notes Tech, adoptée telle quelle.
 *
 * ## Room n'a pas créé cette base, il l'adopte
 *
 * Le fichier existe déjà chez l'utilisateur, écrit par sqflite, avec `user_version = 9` mais
 * **sans** `room_master_table`. Room emprunte alors son chemin « base pré-empaquetée » : faute
 * d'empreinte d'identité, il appelle `onValidateSchema`, et s'il valide, il écrit lui-même son
 * empreinte.
 *
 * D'où [VERSION] = 9, et non 10 : aucun palier de migration fictif à faire porter à quiconque
 * relira ça dans deux ans. Cf. `docs/01-DECISIONS.md` D-005.
 *
 * ### La validation de schéma est le filet, pas la contrainte
 *
 * Elle constitue la **preuve vérifiée par la machine** que [FolderEntity] et [NoteEntity]
 * décrivent la base réelle. Une divergence de type, de nullabilité, de valeur par défaut, de nom
 * d'index, d'ordre de tri ou d'action de clé étrangère fait échouer l'ouverture avec un message
 * `Expected: … Found: …` exploitable.
 *
 * ⚠️ Corollaire : **tous** les index doivent être déclarés, y compris ceux qu'aucune requête
 * n'utilise. Room compare l'ensemble complet.
 *
 * ## Ce que Room ne gère pas
 *
 * `notes_fts`, ses trois triggers et `note_links` vivent hors du graphe d'entités, pour des
 * raisons structurelles détaillées dans [UnmanagedSchema]. Room les ignore — il ne valide que ses
 * propres tables, et une table supplémentaire ne le gêne pas.
 *
 * ## Rétrocompatibilité avec la version Flutter
 *
 * Room n'incrémente pas `user_version` et se contente d'ajouter `room_master_table`, que sqflite
 * ignore. **Une base ouverte par cette application reste donc lisible par la version Dart.**
 * C'est le filet de sécurité de toute la transition : un utilisateur peut revenir en arrière.
 * Aucune évolution de schéma ne doit le retirer sans décision explicite.
 */
@Database(
    entities = [FolderEntity::class, NoteEntity::class],
    version = NotesDatabase.VERSION,
    exportSchema = true,
)
abstract class NotesDatabase : RoomDatabase() {

    abstract fun folderDao(): FolderDao

    abstract fun noteDao(): NoteDao

    /**
     * Écritures dans `notes`, séparées des lectures à dessein : c'est là que se joue la protection
     * des coffres, et la surface entière tient dans un fichier court.
     */
    abstract fun noteWriteDao(): NoteWriteDao

    abstract fun noteSearchDao(): NoteSearchDao

    /**
     * Lectures de `note_links` uniquement. Les écritures passent par [NoteLinkWriter] — la table
     * est hors du graphe d'entités, donc Room ne peut pas générer son SQL d'écriture.
     */
    abstract fun noteLinkDao(): NoteLinkDao

    /**
     * Écritures de `note_links`.
     *
     * Attaché à la base plutôt qu'injecté : il n'a pas d'état propre, et le lier ici garantit qu'il
     * écrit dans **cette** instance — donc dans la transaction en cours, condition de tout le
     * dispositif décrit dans [NoteLinkWriter].
     */
    val linkWriter: NoteLinkWriter by lazy { NoteLinkWriter(this) }

    companion object {
        /**
         * Version du schéma hérité — `notes_tech/lib/core/constants.dart:31`.
         *
         * ⚠️ Ne se bumpe pas pour « repartir proprement ». La faire monter impose d'écrire une
         * migration que la version Flutter ne saura pas redescendre, ce qui supprime la
         * possibilité de revenir en arrière décrite plus haut.
         */
        const val VERSION = 9

        /**
         * Rattache à Room ce qu'il ne sait pas créer, et applique le profil d'ouverture.
         *
         * @param nowMillis injecté et non lu de l'horloge système : une horloge à moitié
         *   injectable rend les tests vacants — on écrit avec l'heure réelle et on vérifie contre
         *   une heure figée, et les assertions passent pour la mauvaise raison.
         */
        fun callback(nowMillis: () -> Long): Callback = object : Callback() {

            /** Base neuve : Room vient de créer `folders` et `notes`, le reste nous revient. */
            override fun onCreate(db: SupportSQLiteDatabase) {
                UnmanagedSchema.createOnFreshDatabase(db)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                applyPragmas(db)
                // À chaque ouverture, pas seulement à la création : le dossier racine est
                // indélébile côté produit, et une base restaurée ou réparée doit le retrouver.
                UnmanagedSchema.ensureInboxFolder(db, nowMillis())
            }
        }

        /**
         * Profil d'ouverture recopié sur celui de la version Flutter
         * (`docs/02-SCHEMA-HERITE.md` §2). Ce ne sont pas des réglages de confort : ils décrivent
         * le comportement qu'ont déjà les bases installées.
         *
         * Deux absences volontaires :
         *
         * - **`PRAGMA foreign_keys`** n'est pas posé ici. Room l'active lui-même quand des
         *   entités déclarent des clés étrangères, et il le fait au bon moment vis-à-vis de ses
         *   propres transactions. Le poser une seconde fois donnerait deux propriétaires à un même
         *   réglage.
         * - **`PRAGMA mmap_size`** n'est pas posé, et cette absence est porteuse : il a été retiré
         *   côté Flutter en v1.0.7.1 parce que sur certaines builds natives de SQLCipher, le mmap
         *   peut se bloquer au démarrage à froid — page blanche figée. Le gain était marginal pour
         *   des bases de texte. Ne pas le rétablir sans reproduire la mesure.
         */
        private fun applyPragmas(db: SupportSQLiteDatabase) {
            // Compromis perf/cohérence assumé : avec WAL, un arrêt brutal peut perdre les
            // dernières écritures non synchronisées, mais la base se rouvre cohérente. `FULL`
            // coûterait un fsync par commit, soit environ dix fois plus cher sur le flux de
            // sauvegarde automatique de l'éditeur (toutes les 500 ms).
            db.execSQL("PRAGMA synchronous = NORMAL")
            db.execSQL("PRAGMA temp_store = MEMORY")
            // Négatif = kibioctets. 32 Mo, soit moins de 1 % de la mémoire des appareils visés,
            // pour réduire les relectures disque sur les parcours FTS et les listes longues.
            db.execSQL("PRAGMA cache_size = -32000")
        }
    }
}
