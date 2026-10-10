package com.filestech.notes_tech.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
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
 * ## Rétrocompatibilité avec la version Flutter — levée en 3.1.0
 *
 * Jusqu'à la 3.0.0, Room n'incrémentait pas `user_version` et se contentait d'ajouter
 * `room_master_table`, que sqflite ignore : une base ouverte par cette application restait lisible par
 * la version Dart, filet de sécurité de la transition.
 *
 * **Decided by Patrice on 2026-10-10 (D-028): schema 10, for the notes' colour.** That way back was
 * already closed in practice — Android refuses to install 2.0.9 (4071-4073) over 3.x (5001+), and
 * uninstalling erases the notes — so it protected no one any more. [MIGRATION_9_10] is a real migration,
 * additive, and a base adopted from Flutter (no `room_master_table`, `user_version` 9) takes it too.
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
         * 9 is the version of the inherited schema (`notes_tech/lib/core/constants.dart:31`); 10 adds
         * the notes' colour (3.1.0, D-028).
         *
         * ⚠️ Raised only with a real migration, registered in `NotesDatabaseFactory`, never to "start clean": every installed
         * base is 9 or 10, and `audits/verifier-schema-room-vs-flutter.py` checks that the Flutter
         * schema, migrated, gives exactly what Room expects.
         */
        const val VERSION = 10

        /**
         * 9 → 10: the notes' colour (3.1.0). One nullable column: every existing note gets `NULL`, no
         * colour, which is what it showed. The FTS index and `note_links` do not read it.
         *
         * ⚠️ Kept in step with `MIGRATIONS` of `audits/verifier-schema-room-vs-flutter.py`, which
         * replays this SQL on the Flutter schema and compares the result with the exported `10.json`;
         * `MigrationVersDixTest` replays this very object on a real SQLCipher base.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN color_id INTEGER")
            }
        }

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
                UnmanagedSchema.ensureSecureDeleteInFullTextIndex(db)
                UnmanagedSchema.detachSealedNotesFromLinks(db)
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
            // 🔴 A deleted row does not stay in a free page (security audit of 2026-09-26, P3),
            // whatever the library's compiled default: a note emptied from the trash could stay
            // readable, to whoever holds the key, until SQLite happened to reuse its page. Read
            // with `query`: this pragma answers with a row, which `execSQL` refuses.
            db.query("PRAGMA secure_delete = ON").use { it.moveToFirst() }
        }
    }
}
