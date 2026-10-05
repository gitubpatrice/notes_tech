package com.filestech.notes_tech.data.local

import android.content.Context
import androidx.room.Room
import com.filestech.notes_tech.core.crypto.wipe
import com.filestech.notes_tech.security.kek.KekRepository
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import timber.log.Timber
import java.io.File

/**
 * Ouvre la base héritée, chiffrée par SQLCipher.
 *
 * Reprend les leçons déjà payées dans `DatabaseFactory.kt` d'Agenda Tech — durée de vie de la
 * passphrase, ouverture forcée, absence de repli destructif — appliquées ici à une base qui, en
 * plus, **n'a pas été créée par cette application**.
 *
 * Voir `docs/02-SCHEMA-HERITE.md` §2 pour les paramètres, et `docs/04-PIEGES.md` §9-10 pour les
 * deux pièges que ce fichier désamorce.
 */
class NotesDatabaseFactory(
    private val kekRepository: KekRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /**
     * Résolution du fichier de base. Paramétrée, et pas seulement pour la forme.
     *
     * Les tests instrumentés s'exécutent dans le bac à sable de l'application installée. Sans ce
     * point d'entrée, ils ouvriraient la base **réelle** et y écriraient leurs jeux d'essai. Sur
     * un appareil de test c'est du désordre ; ailleurs ce serait une corruption de données.
     *
     * La version Flutter avait exactement le même dispositif, pour exactement la même raison
     * (`database.dart:46-59`). Un paramètre de constructeur plutôt qu'un état modifiable : il n'y
     * a rien à réinitialiser entre deux tests, donc rien à oublier de réinitialiser.
     */
    private val databaseFile: (Context) -> File = LegacyDatabaseLocation::databaseFile,
) {

    /**
     * Construit la base. **Propage** toute défaillance d'acquisition de clé sans rien détruire :
     * c'est à la couche interface d'afficher un écran d'erreur exploitable.
     *
     * @throws com.filestech.notes_tech.security.kek.KekFailure si la clé est introuvable ou
     *   inaccessible. Dans tous les cas, la base sur le disque est laissée **intacte**.
     */
    fun build(context: Context): NotesDatabase {
        loadNativeLibraryOnce()

        val kek = kekRepository.acquire()
        // Le matériel de clé au format brut attendu par SQLCipher : les 67 octets ASCII de
        // `x'<hex>'`. Cf. [SqlCipherRawKey] — passer les 32 octets bruts rendrait la base illisible.
        val rawKey = try {
            SqlCipherRawKey.encode(kek)
        } finally {
            // La KEK a rempli son office dès l'encodage : plus tôt elle disparaît, mieux c'est.
            kek.wipe()
        }

        return try {
            open(context, rawKey)
        } finally {
            // Notre exemplaire, pas celui de SQLCipher — voir [open].
            rawKey.wipe()
        }
    }

    private fun open(context: Context, rawKey: ByteArray): NotesDatabase {
        // ⚠️ SQLCipher clé **chaque** connexion que son pool ouvre, pas seulement la première :
        // chaque connexion relit le mot de passe dans sa configuration. Le tableau doit donc
        // survivre aussi longtemps que la base est ouverte.
        //
        // D'où deux précautions :
        //   1. SQLCipher reçoit sa PROPRE copie, que l'appelant n'effacera pas ;
        //   2. `clearPassphrase = false` — le défaut de la bibliothèque met le tableau à zéro
        //      après la première ouverture, ce qui fait échouer la connexion SUIVANTE. Le défaut
        //      est particulièrement vicieux : la première connexion fonctionne, donc rien ne se
        //      voit tant que le pool n'a pas grandi.
        val factory = SupportOpenHelperFactory(rawKey.copyOf(), compatibilityHook(), false)

        val database = Room
            .databaseBuilder(
                context,
                NotesDatabase::class.java,
                // Chemin ABSOLU : la base est dans `app_flutter/`, pas dans `databases/`.
                // `Context.getDatabasePath` rend `File(name)` tel quel quand le nom est absolu —
                // c'est un mécanisme officiel, pas un contournement. Cf. D-003.
                databaseFile(context).absolutePath,
            )
            .openHelperFactory(factory)
            .addCallback(NotesDatabase.callback(nowMillis))
            // ⚠️ AUCUN `fallbackToDestructiveMigration*` ici, et cette absence est porteuse.
            //
            // Room 2.7 a changé la signature de `fallbackToDestructiveMigrationOnDowngrade` : le
            // booléen est `dropAllTables`, pas un interrupteur. L'appeler ARME le chemin destructif
            // quel que soit l'argument passé. Agenda Tech portait la ligne avec `false` sous un
            // commentaire affirmant l'inverse de ce qu'elle faisait, et un `adb install -r -d`
            // pendant un test de migration suffisait à effacer les tables.
            //
            // Les défauts du constructeur — `requireMigration = true`,
            // `allowDestructiveMigrationOnDowngrade = false` — donnent l'exception visible qu'on
            // veut. Ne rien dire est ici la bonne façon de le dire.
            .build()

        // Room ouvre paresseusement, à la première requête. Sans ce déclenchement, aucun
        // `PRAGMA key` n'a encore été exécuté à la sortie de `build()` : un échec de clé
        // ressortirait bien plus tard, sous la forme d'un plantage de DAO sans rapport apparent.
        // On force donc l'ouverture réelle ici, là où l'échec est encore attribuable.
        //
        // C'est aussi le point où la validation de schéma de Room s'exécute — donc le point où
        // une divergence entre les entités et la base réelle se déclare. Cf. D-005.
        database.openHelper.writableDatabase

        return database
    }

    /**
     * `PRAGMA cipher_compatibility = 4` avant le calage de la clé.
     *
     * Défensif et assumé comme tel : la 4.16 utilise déjà le format v4 par défaut, donc ce pragma
     * ne change rien **aujourd'hui**. Il protège d'une montée de version majeure de SQLCipher qui
     * passerait au format v5 en silence — auquel cas les bases existantes deviendraient
     * illisibles sans le moindre avertissement. La version Flutter pose le même pragma pour la
     * même raison (`database.dart:474`).
     *
     * Il doit être exécuté **avant** la clé : après, il n'aurait plus d'effet sur la dérivation.
     */
    private fun compatibilityHook() = object : SQLiteDatabaseHook {
        override fun preKey(connection: SQLiteConnection) {
            connection.execute("PRAGMA cipher_compatibility = 4;", null, null)
        }

        override fun postKey(connection: SQLiteConnection) = Unit
    }

    private companion object {
        @Volatile
        private var nativeLoaded = false

        /**
         * La bibliothèque native doit être chargée une fois avant la première connexion.
         *
         * `@Synchronized` sur une méthode d'objet compagnon : le double contrôle sans verrou est
         * précisément le motif qui produit des chargements concurrents sur les démarrages à froid,
         * là où plusieurs composants touchent la base en même temps.
         */
        @Synchronized
        fun loadNativeLibraryOnce() {
            if (nativeLoaded) return
            System.loadLibrary("sqlcipher")
            nativeLoaded = true
            Timber.d("bibliothèque native SQLCipher chargée")
        }
    }
}
