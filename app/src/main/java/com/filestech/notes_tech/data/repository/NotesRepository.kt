package com.filestech.notes_tech.data.repository

import androidx.room.withTransaction
import com.filestech.notes_tech.core.text.DartTextSemantics
import com.filestech.notes_tech.data.local.DatabaseProvider
import com.filestech.notes_tech.data.local.NotesDatabase
import com.filestech.notes_tech.data.local.OutgoingLink
import com.filestech.notes_tech.data.local.entity.NoteEntity
import com.filestech.notes_tech.data.local.mapper.TagCodec
import com.filestech.notes_tech.data.local.mapper.toDomain
import com.filestech.notes_tech.domain.links.TitleNormalizer
import com.filestech.notes_tech.domain.links.WikiLinkParser
import com.filestech.notes_tech.domain.model.EncryptedFormat
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.domain.model.NoteSortMode
import com.filestech.notes_tech.domain.repository.VaultOpener
import com.filestech.notes_tech.domain.repository.VaultSealer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Les notes : ce qui les lit, ce qui les écrit, et l'indexation de leurs liens.
 *
 * ## 🔴 Une écriture de note est UNE transaction, liens compris
 *
 * C'est la décision structurante de cette couche (`docs/01-DECISIONS.md` D-009). Écrire une note et
 * réindexer ses liens `[[Titre]]` sont **une seule opération** : les liens sont dérivés du contenu,
 * ils n'ont pas d'existence propre, et un état où les deux divergent n'a pas de sens.
 *
 * L'application publiée fait autrement — elle émet un événement, qu'un service reprend une
 * demi-seconde plus tard. Ce détour lui a coûté cher, et le code Flutter en porte les cicatrices :
 * un cache d'index titre→identifiant à durée de vie de cinq secondes pour ne pas relire toute la
 * base à chaque frappe, une horloge monotone pour qu'un appareil dont on recule l'heure ne puisse
 * pas le figer, une invalidation explicite à chaque renommage — et malgré tout ça, une fenêtre
 * pendant laquelle les rétroliens désignaient un titre périmé.
 *
 * Rien de cela n'est nécessaire ici, et pas parce que le portage serait plus habile : parce que la
 * transaction rend la question sans objet. Il n'existe aucun instant où la note est écrite et ses
 * liens ne le sont pas encore.
 *
 * ## Ce qui remplace le flux d'événements
 *
 * L'invalidation de Room. Écrire dans `notes` réveille tous les flux qui l'observent, y compris ceux
 * qui lisent `note_links` par `observedEntities` (cf. `NoteLinkDao`). L'interface se rafraîchit sans
 * qu'aucun code ne le lui demande.
 *
 * C'est pourquoi le modèle `NoteChange` de la version Flutter n'a **pas** été porté : il servait à
 * prévenir trois écouteurs qu'une note avait changé, ce que Room fait déjà. Le porter aurait produit
 * un chemin mort.
 *
 * ## La base s'obtient, elle ne s'injecte pas
 *
 * [DatabaseProvider] l'ouvre au premier usage, et cette ouverture peut **échouer légitimement** —
 * la clé peut être introuvable. Injecter la base construirait le graphe autour d'une valeur qui
 * n'existe peut-être pas, et transformerait un échec de clé explicable en plantage à l'injection.
 *
 * ## L'horloge est injectée
 *
 * [clock] plutôt que `Instant.now()` : un test qui vérifie qu'une opération ne remonte pas
 * `updated_at` doit pouvoir observer le temps, pas l'espérer. La leçon vient d'ailleurs — une
 * horloge à moitié injectable rend les tests vacants, verts pour la mauvaise raison.
 */
@Singleton
class NotesRepository @Inject constructor(
    private val databases: DatabaseProvider,
    private val folders: FoldersRepository,
    private val sealer: VaultSealer,
    private val opener: VaultOpener,
    private val clock: Clock,
) {

    // ── Lectures ─────────────────────────────────────────────────────────────

    fun observeInFolder(
        folderId: String,
        sort: NoteSortMode = NoteSortMode.DEFAULT,
        includeArchived: Boolean = false,
    ): Flow<List<Note>> = observing { it.noteDao().observeInFolder(folderId, sort, includeArchived) }
        .map { it.toDomain() }

    /**
     * Toutes les notes hors corbeille, dans l'ordre demandé — la liste d'accueil sans filtre.
     *
     * ⚠️ Inclut les archives, contrairement à [observeInFolder]. L'asymétrie vient de
     * l'application publiée ; elle est expliquée sur `NoteDao.observeAllAlive`.
     */
    fun observeAllAlive(sort: NoteSortMode = NoteSortMode.DEFAULT): Flow<List<Note>> =
        observing { it.noteDao().observeAllAlive(sort) }.map { it.toDomain() }

    fun observeRecent(limit: Int): Flow<List<Note>> =
        observing { it.noteDao().observeRecent(limit) }.map { it.toDomain() }

    fun observeTrash(): Flow<List<Note>> = observing { it.noteDao().observeTrash() }.map { it.toDomain() }

    fun observeFavorites(): Flow<List<Note>> = observing { it.noteDao().observeFavorites() }.map { it.toDomain() }

    fun observeById(id: String): Flow<Note?> = observing { it.noteDao().observeById(id) }.map { it?.toDomain() }

    suspend fun find(id: String): Note? = databases.get().noteDao().findById(id)?.toDomain()

    suspend fun listAllAlive(): List<Note> = databases.get().noteDao().listAllAlive().toDomain()

    suspend fun countInFolder(folderId: String): Int = databases.get().noteDao().countInFolder(folderId)

    /** Corbeille comprise — voir `NoteDao.countAllInFolder`, qui dit pourquoi. */
    suspend fun countAllInFolder(folderId: String): Int = databases.get().noteDao().countAllInFolder(folderId)

    /**
     * Charge plusieurs notes en une fois, par tranches.
     *
     * SQLite plafonne le nombre de paramètres liés d'une requête — 999 sur beaucoup de versions.
     * Au-delà, la requête échoue : le découpage n'est pas une optimisation mais une condition de
     * fonctionnement. La taille de tranche reprend celle de l'application publiée.
     *
     * ⚠️ **L'ordre du résultat ne suit pas celui des identifiants demandés** — c'est celui que rend
     * SQLite. L'appelant qui a besoin d'un ordre le rétablit lui-même.
     */
    suspend fun findMany(ids: List<String>): List<Note> {
        if (ids.isEmpty()) return emptyList()
        val dao = databases.get().noteDao()
        return ids.chunked(SQLITE_VARIABLE_CHUNK).flatMap { dao.findByIds(it) }.toDomain()
    }

    /**
     * Pré-filtre pour l'auto-complétion `[[…]]`, affiné en mémoire sur les titres normalisés.
     *
     * Deux passes, comme l'application publiée : SQLite écarte le gros du corpus avec un `LIKE`
     * insensible à la casse, puis la normalisation tranche sur les diacritiques — que SQLite ne sait
     * pas dépouiller. Le sur-échantillonnage compense ce que la première passe laisse passer.
     */
    suspend fun suggestTitles(query: String, limit: Int = SUGGESTION_LIMIT, excludeId: String? = null): List<Note> {
        val needle = TitleNormalizer.normalize(query)
        if (needle.isEmpty()) return emptyList()
        // ⚠️ `DartTextSemantics` et non `trim().lowercase()` de Kotlin. Ce pré-filtre alimente le
        // `LIMIT` de la requête : une transformation différente de celle de Flutter ramène un jeu de
        // candidats différent, donc des suggestions différentes pour la même saisie. Relevé par une
        // relecture externe (GPT-5.2, 2026-08-13) — l'écart est petit, mais c'est précisément la
        // classe d'écart que `DartTextSemantics` existe pour supprimer.
        val escaped = escapeLike(DartTextSemantics.lowercase(DartTextSemantics.trim(query)))
        val candidates = databases.get().noteDao().findByTitleLike(
            pattern = "$escaped%",
            wordPattern = "% $escaped%",
            limit = limit * SUGGESTION_OVERFETCH,
            excludeId = excludeId,
        )
        return candidates.asSequence()
            .filter { it.title.isNotEmpty() }
            .filter { entity ->
                val normalized = TitleNormalizer.normalize(entity.title)
                normalized.startsWith(needle) || normalized.contains(" $needle")
            }
            .take(limit)
            .toList()
            .toDomain()
    }

    /**
     * The note a `[[title]]` points to, or `null` — asked when the link is tapped in the preview,
     * like notes_tech 2.0.9's `BacklinksService.resolveTitle`.
     *
     * ⚠️ A vault note is **never** a target, locked or not: `NoteDao.titlesForLinking` leaves out
     * every encrypted note, so a link cannot reveal, from a note that is not protected, that a
     * vault holds a note of that title. Same rule as the indexer, by the same map.
     */
    suspend fun resolveTitle(title: String): String? {
        val normalized = TitleNormalizer.normalize(title)
        if (normalized.isEmpty()) return null
        return linkTargets(databases.get())[normalized]
    }

    // ── Écritures ────────────────────────────────────────────────────────────

    /**
     * Crée une note, scellée **avant** son insertion si elle porte du lisible et que son dossier est
     * un coffre.
     *
     * L'ordre est tout : sceller puis insérer ne laisse aucun instant où le clair est sur le disque.
     * Insérer puis chiffrer laisserait cet instant, et un arrêt brutal entre les deux y figerait la
     * note en clair dans un coffre.
     *
     * ## ⚠️ Une note entièrement vide n'est **pas** scellée, et c'est nécessaire
     *
     * Sans titre ni contenu, il n'y a rien à protéger — et c'est ce qui permet à l'éditeur de créer
     * la note **avant** que l'utilisateur ait tapé quoi que ce soit. Refuser cette écriture-là
     * bloquerait la création dans un coffre ; l'application publiée fait le même choix, par la même
     * sortie anticipée.
     *
     * La première frappe, elle, passe par le scellement : dès que le titre ou le contenu n'est plus
     * vide, [carriesPlaintext] rend `true`.
     *
     * Il ne faut donc pas lire « toute écriture dans un coffre échoue tant que la phase 4 n'est pas
     * livrée » mais « toute écriture **qui porte du lisible** ». Nuance relevée par une relecture
     * externe (GPT-5.2, 2026-08-13), qui la présentait comme un contournement — c'en serait un si la
     * note vide pouvait ensuite être écrite en clair, ce que [saveEdits] empêche.
     */
    suspend fun create(
        folderId: String,
        title: String = "",
        content: String = "",
        tags: List<String> = emptyList(),
    ): Note {
        requireTitleWithinLimit(title)
        val now = clock.instant()
        val draft = Note(
            id = UUID.randomUUID().toString(),
            title = title,
            content = content,
            folderId = folderId,
            tags = tags,
            pinned = false,
            favorite = false,
            archived = false,
            trashedAt = null,
            createdAt = now,
            updatedAt = now,
            encrypted = null,
            encVersion = EncryptedFormat.CONTENT_ONLY,
        )

        return inTransaction { database ->
            val persisted = sealIfVault(draft)
            database.noteWriteDao().insert(persisted.toNewEntity())
            reindexLinks(database, persisted)
            resolveIncoming(database, persisted)
            persisted
        }
    }

    /**
     * Enregistre ce que l'éditeur a modifié : titre, contenu, étiquettes.
     *
     * ⚠️ **N'écrit jamais le blob chiffré et ne l'efface jamais.** La requête sous-jacente porte
     * `AND encrypted_content IS NULL` : appelée avec l'éphémère déchiffrée d'une note de coffre,
     * elle ne touche rien et rend `0`. Verrouiller et déverrouiller sont des gestes nommés,
     * ailleurs.
     *
     * @return la note telle qu'elle est en base après écriture, ou `null` si l'identifiant est
     *   inconnu — une note supprimée pendant l'édition est un cas nominal, pas une erreur.
     */
    suspend fun saveEdits(id: String, title: String, content: String, tags: List<String>): Note? {
        requireTitleWithinLimit(title)
        return inTransaction { database ->
            val previous = database.noteDao().findById(id) ?: return@inTransaction null
            val candidate = previous.toDomain().copy(
                title = title,
                content = content,
                tags = tags,
                updatedAt = clock.instant(),
            )
            val persisted = sealIfVault(candidate)

            if (persisted.isLocked) {
                // Le scellement a produit un blob : c'est un verrouillage, pas une édition en clair.
                // `lockNote` écrit `content = ''` en dur, donc le texte que portait l'éphémère ne
                // peut pas atteindre le disque, même si le scellement avait échoué à le vider.
                database.linkWriter.deleteLinksOf(id)
                database.noteWriteDao().lockNote(
                    id = id,
                    encryptedContent = requireNotNull(persisted.encrypted).toByteArray(),
                    encVersion = persisted.encVersion,
                    plainTitle = persisted.title,
                    // Les étiquettes d'une note de coffre restent en clair : elles sont éditables,
                    // et ce chemin est le seul par lequel l'éditeur les écrit pour une telle note.
                    // Les omettre les perdait en silence.
                    tags = TagCodec.encode(persisted.tags),
                    // C'est une ÉDITION, pas une reprotection : la note doit remonter en tête de
                    // « modifiées récemment », comme le fait l'application publiée.
                    updatedAt = persisted.updatedAt.toEpochMilli(),
                )
            } else {
                database.noteWriteDao().updateEditableFields(
                    id = id,
                    title = persisted.title,
                    content = persisted.content,
                    tags = TagCodec.encode(persisted.tags),
                    updatedAt = persisted.updatedAt.toEpochMilli(),
                )
                reindexLinks(database, persisted)
            }

            // ⚠️ Sans condition sur le changement de titre, et ce n'est pas une facilité.
            //
            // Une première version ne réaccrochait les liens entrants que si le titre avait changé.
            // Elle laissait passer le cas le plus grave : une note qui vient d'être VERROUILLÉE au
            // format 1 garde son titre en clair — donc « le titre n'a pas changé », donc les liens
            // qui pointaient vers elle restaient résolus, et une note non protégée continuait
            // d'afficher un lien cliquable vers une note désormais au coffre.
            //
            // Les deux opérations sont idempotentes et portent sur une table minuscule. Les appeler
            // à chaque fois coûte deux `UPDATE` et supprime la question.
            resolveIncoming(database, persisted)
            database.noteDao().findById(id)?.toDomain()
        }
    }

    /**
     * Écrit les seules étiquettes — le seul champ éditable d'une note **verrouillée**.
     *
     * Les étiquettes d'une note de coffre sont stockées en clair : le trigger de l'index plein texte
     * les masque, il ne les chiffre pas. Les modifier ne touche ni au contenu ni au blob ; le geste
     * est donc sûr sur n'importe quelle note, et c'est pourquoi il n'a pas de garde.
     */
    suspend fun updateTags(id: String, tags: List<String>): Boolean =
        databases.get().noteWriteDao().updateTags(id, TagCodec.encode(tags), clock.millis()) > 0

    /**
     * Épingle ou désépingle.
     *
     * 🔴 N'écrit **que** ce drapeau. C'est très précisément ce geste — un tap sur une icône
     * d'épinglage — qui détruisait la protection d'une note de coffre dans l'application publiée,
     * parce qu'il passait par une réécriture de ligne entière.
     */
    suspend fun setPinned(id: String, pinned: Boolean): Boolean =
        databases.get().noteWriteDao().updateFlags(id = id, updatedAt = clock.millis(), pinned = pinned) > 0

    suspend fun setFavorite(id: String, favorite: Boolean): Boolean =
        databases.get().noteWriteDao().updateFlags(id = id, updatedAt = clock.millis(), favorite = favorite) > 0

    suspend fun setArchived(id: String, archived: Boolean): Boolean =
        databases.get().noteWriteDao().updateFlags(id = id, updatedAt = clock.millis(), archived = archived) > 0

    /**
     * Met une note à la corbeille.
     *
     * ⚠️ **Ses liens disparaissent dans la même transaction.** Une note en corbeille n'apparaît plus
     * nulle part ; garder ses liens sortants ferait remonter des rétroliens depuis une note que
     * l'utilisateur croit supprimée. Les liens qui *pointaient* vers elle repassent fantômes, ce qui
     * masque au passage son titre.
     */
    suspend fun moveToTrash(id: String): Boolean = inTransaction { database ->
        val now = clock.millis()
        val touched = database.noteWriteDao().setTrashedAt(id = id, updatedAt = now, trashedAt = now)
        if (touched > 0) {
            database.linkWriter.deleteLinksOf(id)
            database.linkWriter.unresolveByMismatch(noteId = id, newTitleNorm = "")
        }
        touched > 0
    }

    /**
     * Restaure une note depuis la corbeille et réindexe ses liens.
     *
     * La réindexation n'est pas décorative : les liens ont été effacés à la mise en corbeille, et
     * sans cette passe la note reviendrait sans aucun de ses rétroliens.
     */
    suspend fun restoreFromTrash(id: String): Boolean = inTransaction { database ->
        val restored = database.noteWriteDao().setTrashedAt(id = id, updatedAt = clock.millis(), trashedAt = null)
        if (restored > 0) {
            database.noteDao().findById(id)?.toDomain()?.let { note ->
                reindexLinks(database, note)
                resolveIncoming(database, note)
            }
        }
        restored > 0
    }

    /**
     * Suppression définitive.
     *
     * Les liens partants tombent par cascade et l'index plein texte se nettoie par trigger : il n'y
     * a rien à faire de plus. Les liens qui *pointaient* vers elle repassent à `NULL` — comportement
     * hérité et voulu, puisque le texte `[[titre]]` reste écrit dans les notes sources.
     */
    suspend fun deletePermanently(id: String): Boolean = databases.get().noteWriteDao().deletePermanently(id) > 0

    /**
     * Vide la corbeille et retourne le nombre de notes détruites.
     *
     * ⚠️ **Y compris les notes de coffre**, qui y sont encore scellées. Rien à déchiffrer : on
     * supprime la ligne, blob compris. C'est justement le seul geste destructif qui n'a pas besoin
     * de la clé du coffre — et il ne doit surtout pas l'exiger, sinon un coffre dont la phrase est
     * perdue rendrait sa corbeille invidable.
     */
    suspend fun emptyTrash(): Int = databases.get().noteWriteDao().emptyTrash()

    /**
     * Déplace une note vers un autre dossier.
     *
     * 🔴 **Entrer dans un coffre chiffre la note dans la MÊME transaction que le déplacement.**
     * Déplacer d'abord et chiffrer ensuite laisserait, entre les deux, une note en clair dans un
     * dossier coffre — exactement l'état qu'on ne veut jamais écrire sur le disque. Une première
     * version de cette méthode faisait précisément ça : elle appelait le scellement, **jetait son
     * résultat**, puis déplaçait la note en clair. Le chiffrement s'y donnait des airs de garde sans
     * rien garder.
     *
     * @throws VaultRelocationException si la note est verrouillée. Sortir d'un coffre, ou passer
     *   d'un coffre à un autre, exige la clé du coffre d'origine : chaque coffre a la sienne, et le
     *   blob ne se transporte pas tel quel. C'est [relocateLockedNote] qui en est capable, et il
     *   porte un autre nom parce que c'est un autre geste — celui qui peut retirer une protection.
     * @throws com.filestech.notes_tech.domain.repository.VaultLockedException si la destination est
     *   un coffre dont la session n'est pas ouverte.
     */
    suspend fun moveToFolder(id: String, folderId: String): Boolean = inTransaction { database ->
        val current = database.noteDao().findById(id) ?: return@inTransaction false
        if (current.folderId == folderId) return@inTransaction false
        if (current.isLocked) throw VaultRelocationException(noteId = id, folderId = current.folderId)

        val relocated = sealIfVault(current.toDomain().copy(folderId = folderId))
        if (relocated.isLocked) {
            database.noteWriteDao().lockNote(
                id = id,
                encryptedContent = requireNotNull(relocated.encrypted).toByteArray(),
                encVersion = relocated.encVersion,
                plainTitle = relocated.title,
                // Le déplacement ne modifie pas les étiquettes.
                tags = null,
                // `moveToFolder`, juste après, écrit `updated_at` : le poser ici en ferait deux
                // écritures pour un seul geste, dont l'une serait aussitôt écrasée.
                updatedAt = null,
            )
            // ⚠️ Les deux **aides**, et non les deux écritures à la main qu'elles produisent.
            //
            // Le résultat est identique — `reindexLinks` d'une note verrouillée efface ses liens,
            // `resolveIncoming` d'une note verrouillée force la clé de titre à vide — mais l'écrire
            // à la main créait un troisième site portant la même règle. Le prochain correctif de
            // l'indexation aurait corrigé les deux aides et laissé celui-ci en arrière.
            //
            // Relevé par l'audit de cohérence du 2026-08-15, sans conséquence fonctionnelle.
            reindexLinks(database, relocated)
            resolveIncoming(database, relocated)
        }
        database.noteWriteDao().moveToFolder(id = id, folderId = folderId, updatedAt = clock.millis()) > 0
    }

    /**
     * Déplace une note **verrouillée** : la sortir de son coffre, ou la faire passer dans un autre.
     *
     * ## 🔴 C'est le seul geste de l'application qui peut retirer la protection d'UNE note
     *
     * `FolderVaultService.decryptAllNotesInFolder` déprotège un dossier entier ; celui-ci déprotège
     * une note. Les deux méritent leur nom propre, et aucun des deux ne doit pouvoir se déclencher
     * par un appel qui ressemble à autre chose — c'est pourquoi [moveToFolder] **refuse** une note
     * verrouillée au lieu de router vers ici. L'appelant doit demander explicitement ce geste-là,
     * après la confirmation que l'interface pose (`note_editor_exit_vault_*`).
     *
     * ## L'ordre, et pourquoi tout tient dans UNE transaction
     *
     * Déchiffrer puis déplacer, en deux écritures, laisserait entre les deux une note **en clair
     * dans un dossier coffre** — l'état exact que [moveToFolder] refuse d'écrire dans l'autre sens.
     * Un plantage au mauvais moment le figerait sur le disque, sous un cadenas qui ne protège plus
     * rien. La transaction rend cet instant inobservable, et son échec rend la note à son état
     * scellé d'origine.
     *
     * ## ⚠️ L'état intermédiaire dans la transaction : analysé, assumé, et le correctif proposé REFUSÉ
     *
     * Une relecture externe (GPT-5.2, 2026-08-15) note à juste titre que l'écriture du clair précède
     * le déplacement de la ligne : pendant la transaction, `content` est lisible alors que
     * `folder_id` désigne encore le coffre. Le constat est exact. La conclusion qu'il en tire ne
     * l'est pas, et son remède serait une régression :
     *
     * - Le fichier de base **et son journal WAL** sont chiffrés par SQLCipher. Un résidu n'est donc
     *   pas « du clair au repos » : il est sous la même couche que tout le reste de la base.
     * - L'utilisateur vient de **consentir explicitement** à ce que ce contenu devienne lisible.
     *   L'état final est celui qu'il a demandé ; l'état intermédiaire ne l'expose à rien de plus.
     * - Le seul écart réel est un échec de transaction : la note reste scellée logiquement alors
     *   qu'une image de son clair peut subsister dans le WAL jusqu'à recyclage. Fenêtre étroite,
     *   sous SQLCipher, sur une donnée que l'utilisateur voulait déchiffrer.
     * - 🔴 **Le remède proposé — un `UPDATE` unique portant `folder_id`, `content`, `title`,
     *   `encrypted_content` et `enc_v` — est exactement l'écriture de ligne large que le DAO
     *   interdit**, et dont l'absence est l'invariant le plus important de cette couche : c'est ce
     *   type d'écriture qui a détruit la protection d'une note dans l'application publiée. Il
     *   n'effacerait même pas le résidu qu'il prétend viser, le WAL contenant de toute façon la
     *   page réécrite.
     *
     * Réordonner n'aide pas davantage : déplacer d'abord produirait la faute symétrique, un blob de
     * la clé d'origine dans un dossier qui n'est plus le sien.
     *
     * ## ⚠️ L'ouvreur est VÉRIFIÉ, comme le scelleur l'est dans [sealIfVault]
     *
     * Un ouvreur qui rendrait la note inchangée — encore scellée — ferait écrire, selon la
     * destination, un blob illisible dans un dossier ordinaire, ou bien `content = ""` et
     * `title = ""` par `NoteWriteDao.unlockNote` : **la note serait vidée**. La symétrie n'est pas
     * décorative : c'est le même motif de défaut que côté scellement, et il détruit ici au lieu de
     * fuir.
     *
     * @param folderId la destination. Si c'est un autre coffre, la note est **rescellée avec la clé
     *   de celui-là** — les deux sessions doivent donc être ouvertes.
     * @return `false` si l'identifiant est inconnu ou si la note est déjà dans ce dossier.
     * @throws IllegalStateException si la note n'est **pas** verrouillée. Un geste nommé « sortir du
     *   coffre » exécuté sur une note qui n'y est pas veut dire que l'appelant s'est trompé de
     *   chemin, ou que l'état de son écran est périmé ; le silence y masquerait une confirmation
     *   demandée à l'utilisateur pour une action qui n'était pas celle-là.
     * @throws com.filestech.notes_tech.domain.repository.VaultLockedException si la session de la
     *   destination n'est pas ouverte. La session **d'origine** fermée lève, elle, l'exception du
     *   service de coffres — cf. `VaultOpener.decrypt`.
     */
    suspend fun relocateLockedNote(id: String, folderId: String): Boolean = inTransaction { database ->
        val current = database.noteDao().findById(id) ?: return@inTransaction false
        if (current.folderId == folderId) return@inTransaction false
        check(current.isLocked) { "la note $id n'est pas verrouillee : ce chemin n'est pas le sien" }

        val clear = opener.decrypt(current.toDomain())
        check(clear.encrypted == null) {
            "ouverture incomplete pour la note $id : la note rendue porte encore son chiffre"
        }

        val persisted = sealIfVault(clear.copy(folderId = folderId))
        if (persisted.isLocked) {
            // Coffre → coffre : le blob qui part en base est celui de la clé de DESTINATION.
            database.noteWriteDao().lockNote(
                id = id,
                encryptedContent = requireNotNull(persisted.encrypted).toByteArray(),
                encVersion = persisted.encVersion,
                plainTitle = persisted.title,
                // Ni les étiquettes ni l'horodatage : elles ne changent pas, et `moveToFolder`
                // juste en dessous écrit `updated_at` une fois pour le geste entier.
                tags = null,
                updatedAt = null,
            )
            database.linkWriter.deleteLinksOf(id)
        } else {
            // Coffre → dossier ordinaire : le clair revient dans ses colonnes, le blob disparaît.
            database.noteWriteDao().unlockNote(id = id, content = persisted.content, plainTitle = persisted.title)
            // ⚠️ **Après** le déverrouillage, jamais avant : l'indexation lit la table des titres,
            // qui ignore les notes scellées. Réindexer d'abord, c'est indexer un état qui n'est
            // plus. La note redevient au passage une cible légitime pour les liens des autres.
            reindexLinks(database, persisted)
        }
        val lignes = database.noteWriteDao().moveToFolder(id = id, folderId = folderId, updatedAt = clock.millis())
        // La note vient d'être lue **et** réécrite dans cette transaction : un zéro voudrait dire
        // qu'elle a disparu entre-temps, ce que la transaction rend impossible. Le vérifier coûte
        // une comparaison et interdit d'annoncer un déplacement qui n'aurait pas eu lieu — alors
        // que la protection, elle, aurait bien été retirée.
        check(lignes > 0) { "la note $id n'a pas ete deplacee alors que son contenu a ete reecrit" }
        resolveIncoming(database, persisted)
        true
    }

    /**
     * Purge les notes en corbeille au-delà de la rétention héritée de trente jours.
     *
     * @return le nombre de notes réellement supprimées, pour que l'appelant puisse le rapporter au
     *   lieu de l'affirmer.
     */
    suspend fun purgeExpiredTrash(): Int {
        val cutoff = clock.instant().minusMillis(TRASH_RETENTION_MILLIS)
        return databases.get().noteWriteDao().purgeTrashedBefore(cutoff.toEpochMilli())
    }

    // ── Rouages internes ─────────────────────────────────────────────────────

    /**
     * Ouvre la base au moment de la **collecte**, pas à la construction du flux.
     *
     * Un `Flow` construit tôt et collecté tard est la norme dans une interface ; si l'ouverture
     * avait lieu à la construction, un écran assemblé avant que la clé soit disponible échouerait
     * sans que personne ne collecte encore.
     */
    private fun <T> observing(source: (NotesDatabase) -> Flow<T>): Flow<T> = flow { emitAll(source(databases.get())) }

    private suspend fun <T> inTransaction(block: suspend (NotesDatabase) -> T): T {
        val database = databases.get()
        return database.withTransaction { block(database) }
    }

    /**
     * Chiffre la note si son dossier est un coffre et qu'il y a une raison de le faire.
     *
     * ⚠️ **La présence d'un blob ne suffit pas à conclure que tout est protégé**, et c'était le trou
     * de la version publiée : elle sortait dès qu'un chiffré existait, donc une note portant un blob
     * **et** du clair ajouté à côté traversait toutes les défenses. Relevé en critique par une
     * relecture externe, sur le correctif lui-même.
     *
     * ## 🔴🔴 Deux raisons de sceller, et n'en voir qu'une PERDAIT un effacement
     *
     * La première est la confidentialité : la note porte du lisible. La seconde est l'exactitude :
     * **la note porte déjà un blob**, et pour elle ce blob *est* le contenu — le laisser tel quel
     * revient à ignorer l'écriture en cours.
     *
     * Ne tester que la première produisait ceci, mesuré sur le chemin réel : l'utilisateur ouvre une
     * note de coffre, **efface tout** pour détruire un secret, l'enregistrement se déclenche. La note
     * n'a alors plus ni titre ni contenu lisibles — donc « rien à protéger » — donc pas de
     * scellement, donc `lockNote` réécrivait **l'ancien blob**. L'effacement était ignoré en silence,
     * et le secret réapparaissait intact à la réouverture.
     *
     * Une note vide **jamais scellée** reste, elle, hors du chiffrement : c'est le cas d'une note
     * qu'on vient de créer dans un coffre avant d'avoir tapé quoi que ce soit, et c'est la parité
     * avec l'application publiée.
     *
     * ⚠️ Effet de bord voulu : vider une note de coffre dont la session s'est refermée **échoue**
     * désormais bruyamment au lieu de « réussir » sans rien changer. C'est la vérité — l'effacement
     * n'a pas eu lieu — et l'éditeur sait déjà le dire (`signalerLaPerte`).
     *
     * Relevé CONFIRMÉ par une relecture externe (Gemini 3.1 Pro, 2026-08-15), vérifié ligne à ligne
     * avant correction.
     */
    private suspend fun sealIfVault(note: Note): Note {
        if (!carriesPlaintext(note) && !note.isLocked) return note
        if (!folders.isVaultFolder(note.folderId)) return note

        val sealed = sealer.seal(note)
        // 🔴 Le scelleur est vérifié, pas cru sur parole.
        //
        // Sans ce contrôle, un scelleur qui chiffrerait correctement mais oublierait de vider
        // `content` — ou de vider `title` en format 2 — ferait insérer le blob ET le texte lisible
        // dans la même ligne. La note paraîtrait protégée à l'écran et serait lisible au repos.
        //
        // La phase 4 n'est pas écrite : ce contrôle porte donc sur du code qui n'existe pas encore,
        // et c'est exactement le moment de le poser. Signalé par une relecture externe (GPT-5.2,
        // 2026-08-13) comme le défaut qui « explosera au moment où le vrai scelleur arrivera ».
        //
        // Le message ne cite que l'identifiant : une exception voyage dans les journaux.
        check(!carriesPlaintext(sealed)) {
            "scellement incomplet pour la note ${note.id} : la note rendue porte encore du lisible"
        }
        return sealed
    }

    /**
     * Dit si [note] transporte encore du texte qu'un coffre devrait protéger.
     *
     * Le test ne peut pas être « blob présent ⇒ rien en clair » : le format 1 est légitimement un
     * blob de contenu avec le titre en clair dans la colonne. La distinction se fait par le format.
     *
     * | État | Verdict | Pourquoi |
     * |---|---|---|
     * | pas de blob, titre ou contenu non vide | lisible | rien ne le protège |
     * | blob, contenu non vide | lisible | le contenu est vidé au chiffrement, quel que soit le format |
     * | blob format 2, titre non vide | lisible | le titre a rejoint le blob, la colonne doit être vide |
     * | blob format 1, titre non vide | protégé | c'est l'état hérité, et il est normal |
     */
    private fun carriesPlaintext(note: Note): Boolean {
        if (!note.isLocked) return note.title.isNotEmpty() || note.content.isNotEmpty()
        if (note.content.isNotEmpty()) return true
        return note.encVersion == EncryptedFormat.TITLE_AND_CONTENT && note.title.isNotEmpty()
    }

    /**
     * Recalcule les liens sortants de [note]. **À n'appeler que dans une transaction.**
     *
     * Trois chemins, dans l'ordre où ils se présentent :
     *
     * 1. **Note verrouillée** : ses liens sont effacés, jamais indexés. Le contenu chiffré ne
     *    contient pas de `[[`, mais d'anciens liens peuvent survivre d'une indexation antérieure à
     *    sa mise au coffre — et ils désigneraient des cibles depuis une note devenue secrète.
     * 2. **Aucun `[[` dans le texte** : rien à indexer, mais les liens existants sont **quand même**
     *    effacés. Une note dont on retire le dernier `[[` doit perdre ses liens, pas les garder.
     *    L'application publiée mesure que quatre notes sur cinq n'ont aucun `[[` ; ce test leur
     *    épargne la lecture de la table des titres.
     * 3. Sinon, la table d'appariement est construite et les liens réécrits en bloc.
     */
    private suspend fun reindexLinks(database: NotesDatabase, note: Note) {
        if (note.isLocked || !note.content.contains(WIKI_LINK_MARKER)) {
            database.linkWriter.deleteLinksOf(note.id)
            return
        }
        val extracted = WikiLinkParser.extract(note.content)
        if (extracted.isEmpty()) {
            database.linkWriter.deleteLinksOf(note.id)
            return
        }

        val byNormalizedTitle = linkTargets(database)

        database.linkWriter.replaceLinksOf(
            sourceId = note.id,
            links = extracted.map { link ->
                val target = byNormalizedTitle[link.titleNorm]
                OutgoingLink(
                    // Une note qui se cite elle-même ne produit pas de lien vers elle-même.
                    targetId = if (target == note.id) null else target,
                    targetTitle = link.title,
                    targetTitleNorm = link.titleNorm,
                    position = link.position,
                )
            },
        )
    }

    /**
     * Titre normalisé → identifiant de la note qu'un `[[Titre]]` désigne.
     *
     * ⚠️ `associate` garde la DERNIÈRE valeur en cas de clé répétée, et la requête trie par
     * `updated_at DESC` : deux notes de même titre normalisé résolvent donc vers la moins
     * récemment modifiée. C'est le comportement de l'application publiée, dont le littéral de
     * map écrase les doublons dans le même ordre. Changer l'un des deux ferait pointer les
     * liens ambigus ailleurs.
     *
     * One map for the indexer and for [resolveTitle]: the preview and the links panel must name
     * the same note for the same title, and a second copy of this rule is how they would not.
     */
    private suspend fun linkTargets(database: NotesDatabase): Map<String, String> =
        database.noteDao().titlesForLinking().associate { TitleNormalizer.normalize(it.title) to it.id }

    /**
     * Réaccroche les liens qui visent [note], et détache ceux qui ne la visent plus.
     *
     * Les deux gestes vont ensemble : le titre courant attire les liens fantômes écrits avant
     * l'existence de la note, l'ancien titre détache ceux devenus incorrects.
     *
     * ⚠️ Une note verrouillée n'est **jamais** une cible : la clé normalisée est forcée à vide, ce
     * qui détache tout lien vers elle. Sans cela, un rétrolien révélerait le titre et l'existence
     * d'une note de coffre depuis une note qui, elle, n'est pas protégée.
     */
    private suspend fun resolveIncoming(database: NotesDatabase, note: Note) {
        val normalized = if (note.isLocked) "" else TitleNormalizer.normalize(note.title)
        if (normalized.isNotEmpty()) {
            database.linkWriter.resolveDanglingTargets(noteId = note.id, titleNorm = normalized)
        }
        database.linkWriter.unresolveByMismatch(noteId = note.id, newTitleNorm = normalized)
    }

    private fun Note.toNewEntity(): NoteEntity = NoteEntity(
        id = id,
        title = title,
        content = content,
        encryptedContent = encrypted?.toByteArray(),
        folderId = folderId,
        tags = TagCodec.encode(tags),
        pinned = pinned,
        favorite = favorite,
        archived = archived,
        trashedAt = trashedAt?.toEpochMilli(),
        createdAt = createdAt.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli(),
        encVersion = encVersion,
    )

    private fun requireTitleWithinLimit(title: String) {
        require(title.length <= TITLE_MAX_LENGTH) {
            "titre de ${title.length} caracteres, maximum $TITLE_MAX_LENGTH"
        }
    }

    /** Échappe `%`, `_` et `\` pour un `LIKE ... ESCAPE '\'`. */
    private fun escapeLike(value: String): String = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    companion object {
        /**
         * Valeur héritée : `AppConstants.noteTitleMaxLength`.
         *
         * ⚠️ **Publique, et le compagnon avec** : l'éditeur en a besoin pour dire *pourquoi* un
         * enregistrement échoue. Recopier `200` là-bas aurait créé deux vérités pour une règle, dont
         * l'une n'est appliquée nulle part — le jour où la limite bouge, le message mentirait.
         */
        const val TITLE_MAX_LENGTH = 200

        /** Trente jours en millisecondes. Valeur héritée : `AppConstants.trashRetentionDays`. */
        const val TRASH_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000

        /** Sous la limite de paramètres liés de SQLite, avec de la marge. */
        const val SQLITE_VARIABLE_CHUNK = 500

        const val SUGGESTION_LIMIT = 8

        /** Le `LIKE` de SQLite ignore les diacritiques : il faut ramener plus large qu'il ne faut. */
        const val SUGGESTION_OVERFETCH = 4

        const val WIKI_LINK_MARKER = "[["
    }
}

/**
 * On a demandé à déplacer une note **verrouillée** d'un dossier à un autre.
 *
 * Chaque coffre a sa propre clé : le blob d'une note ne se transporte pas d'un coffre à l'autre, et
 * ne redevient pas lisible en sortant. L'opération exige de déchiffrer avec la clé d'origine puis de
 * rechiffrer — donc une session ouverte.
 *
 * Refuser ici plutôt que déplacer la ligne : une note déplacée avec un blob que plus aucune clé
 * n'ouvre est une note perdue, sans le moindre message.
 *
 * ⚠️ **Ce refus ne dit plus « pas encore », il dit « pas par ce chemin ».** L'opération existe :
 * [NotesRepository.relocateLockedNote]. Elle porte un autre nom parce qu'elle fait autre chose —
 * elle peut retirer la protection d'une note — et l'y router silencieusement depuis un déplacement
 * ordinaire ferait exactement ce que toute cette couche s'emploie à rendre impossible.
 */
class VaultRelocationException(val noteId: String, val folderId: String) :
    IllegalStateException(
        "deplacement refuse : la note $noteId est verrouillee dans le coffre $folderId, le transfert " +
            "exige la cle de ce coffre",
    )
