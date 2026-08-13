package com.filestech.notes_tech.data.local

import androidx.room.withTransaction

/**
 * Écritures dans `note_links`.
 *
 * ## Pourquoi ce n'est pas un `@Dao`
 *
 * `note_links` est hors du graphe d'entités de Room (elle n'a pas de clé primaire dans la base
 * héritée — cf. [UnmanagedSchema]). Un `@Query` d'écriture serait refusé à la compilation sur une
 * table inconnue, et un `@Dao` n'a pas accès à la base pour exécuter du SQL arbitraire. Les
 * lectures, elles, passent par `@RawQuery` dans
 * [com.filestech.notes_tech.data.local.dao.NoteLinkDao].
 *
 * La séparation est délibérée : mélanger les deux mécanismes dans un même type laisserait croire
 * qu'ils offrent les mêmes garanties.
 *
 * ## ⚠️ Contrainte d'appel — la réactivité en dépend
 *
 * Room ne suit pas `note_links`. Écrire dans cette table **ne réveille aucun `Flow`**, y compris
 * ceux qui l'observent via `observedEntities = [NoteEntity::class]`.
 *
 * Ce n'est pas un problème tant que la règle suivante est tenue :
 *
 * > **Toute écriture de liens se fait dans la même transaction que l'écriture de la note dont ils
 * > sont extraits.**
 *
 * C'est l'écriture de `notes` qui déclenche l'invalidation, et les liens sont *dérivés* du contenu
 * de la note : les écrire séparément n'aurait de toute façon aucun sens métier. La règle décrit
 * donc la réalité du domaine, elle ne la contraint pas.
 */
class NoteLinkWriter(private val database: NotesDatabase) {

    /**
     * Remplace **en bloc** les liens partant de [sourceId].
     *
     * Suppression puis réinsertion plutôt qu'une réconciliation ligne à ligne : les liens n'ont pas
     * d'identité propre — ni clé primaire, ni sens hors de leur note source. Une réconciliation
     * introduirait une complexité que rien ne justifie, et la table est minuscule.
     *
     * ⚠️ `DELETE` ici ne touche **que** `note_links` : aucune cascade ne part de cette table.
     * L'inverse serait vrai d'un `DELETE` sur `notes`, qui emporterait les liens — d'où l'interdit
     * de `REPLACE` sur les notes (`docs/04-PIEGES.md` §1).
     *
     * @param links dans l'ordre d'apparition dans le texte ; `position` est repris tel quel.
     */
    suspend fun replaceLinksOf(sourceId: String, links: List<OutgoingLink>) {
        database.withTransaction {
            val db = database.openHelper.writableDatabase
            db.execSQL("DELETE FROM note_links WHERE source_id = ?", arrayOf<Any?>(sourceId))
            for (link in links) {
                db.execSQL(
                    """
                    INSERT INTO note_links
                      (source_id, target_id, target_title, target_title_norm, position)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    arrayOf<Any?>(
                        sourceId,
                        link.targetId,
                        link.targetTitle,
                        link.targetTitleNorm,
                        link.position,
                    ),
                )
            }
        }
    }

    /**
     * Défait les liens qui visaient [noteId] mais dont le titre cible ne correspond plus.
     *
     * À appeler quand une note est **renommée** : les liens `[[Ancien titre]]` qui pointaient vers
     * elle doivent redevenir fantômes, sinon ils continueraient de mener à une note qui ne porte
     * plus ce titre.
     *
     * [newTitleNorm] vide défait **tous** les liens vers cette note — c'est ce qu'il faut quand la
     * note devient inatteignable comme cible : mise au coffre, ou titre vidé par un chiffrement au
     * format 2.
     */
    suspend fun unresolveByMismatch(noteId: String, newTitleNorm: String) {
        database.withTransaction {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE note_links SET target_id = NULL WHERE target_id = ? AND target_title_norm != ?",
                arrayOf<Any?>(noteId, newTitleNorm),
            )
        }
    }

    /**
     * Rattache les liens fantômes qui visaient [titleNorm] à la note [noteId].
     *
     * À appeler quand une note est créée ou renommée : des liens `[[Titre]]` écrits avant son
     * existence attendent ce moment. Sans ça, un lien resterait fantôme à jamais alors que sa cible
     * existe — l'utilisateur verrait un lien mort vers une note qu'il vient de créer.
     *
     * L'opération est **idempotente** : relancée, elle ne fait rien de plus.
     *
     * ## ⚠️ `source_id != :noteId` — le garde-fou anti-auto-lien serait défait sans lui
     *
     * L'indexation exclut délibérément les auto-références : une note qui écrit son propre titre
     * entre crochets ne produit pas un lien vers elle-même, elle produit un fantôme. Sans la
     * condition sur la source, cette requête — appelée juste après, dans la même transaction —
     * rattacherait ce fantôme à la note qui vient de l'émettre, annulant le garde-fou une ligne
     * plus loin.
     *
     * **L'application publiée a exactement ce défaut** : `backlinks_service.dart:338` écarte
     * l'auto-lien, puis `resolveDangling` le rétablit au tour suivant. Personne ne l'a vu parce que
     * la conséquence est cosmétique — une note qui figure dans ses propres rétroliens. Ce portage
     * ne le reproduit pas : `target_id` n'est pas une clé d'appariement partagée entre les deux
     * versions, chaque réindexation le réécrit, et il n'y a donc rien à préserver ici.
     *
     * Relevé par `NotesRepositoryTest`, qui affirmait le garde-fou et l'a trouvé inopérant.
     */
    suspend fun resolveDanglingTargets(noteId: String, titleNorm: String) {
        database.withTransaction {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE note_links SET target_id = ? " +
                    "WHERE target_id IS NULL AND target_title_norm = ? AND source_id != ?",
                arrayOf<Any?>(noteId, titleNorm, noteId),
            )
        }
    }

    /**
     * Supprime les liens partant de [sourceId].
     *
     * Utile au mode panique et au vidage d'un dossier. Les liens qui *pointaient* vers cette note
     * ne sont pas touchés : la clé étrangère les repassera à `target_id = NULL` si la note est
     * supprimée, ce qui les rend fantômes — comportement hérité, et voulu, puisque le texte
     * `[[titre]]` reste écrit dans les notes sources.
     */
    suspend fun deleteLinksOf(sourceId: String) {
        database.withTransaction {
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM note_links WHERE source_id = ?",
                arrayOf<Any?>(sourceId),
            )
        }
    }
}

/**
 * Un lien `[[Titre]]` extrait du contenu d'une note, **déjà résolu**.
 *
 * ## ⚠️ La résolution ne se fait pas en SQL, et ne peut pas s'y faire
 *
 * [targetTitleNorm] est la forme normalisée : minuscules **et diacritiques dépouillés**. SQLite ne
 * sait pas dépouiller les diacritiques — `lower(title)` rend `réunion` là où la normalisation rend
 * `reunion`, et l'appariement échouerait silencieusement sur tout titre accentué, c'est-à-dire
 * l'essentiel d'un corpus français.
 *
 * La version Flutter fait donc l'appariement en mémoire, en construisant une table
 * `normalizeTitle(titre) → id` (`backlinks_service.dart:327`). Le portage doit faire pareil, avec
 * **la même** fonction de normalisation des deux côtés — deux normalisations différentes
 * produiraient des liens qui ne se retrouveraient jamais.
 *
 * ⚠️ Cette table d'appariement **exclut les notes verrouillées**. Une note de coffre n'est pas une
 * cible de lien : la résoudre exposerait son existence et son titre depuis une note qui, elle,
 * n'est pas protégée.
 *
 * @param targetId `null` = lien fantôme, vers un titre qui n'existe pas (encore).
 */
data class OutgoingLink(val targetId: String?, val targetTitle: String, val targetTitleNorm: String, val position: Int)
