package com.filestech.notes_tech.security.kek

/**
 * Une provenance possible de la clé maître (KEK) qui chiffre la base.
 *
 * Les implémentations sont interrogées dans l'ordre par [KekRepository]. Voir
 * `docs/03-KEK-ACQUISITION.md` pour la conception d'ensemble.
 *
 * ## La distinction qui porte toute la sûreté du dispositif
 *
 * Une source doit répondre **trois** choses différentes, jamais deux :
 *
 * | Situation | Réponse |
 * |---|---|
 * | « je n'ai rien » | `null` |
 * | « j'ai la clé » | les 32 octets |
 * | « je n'ai pas pu regarder » | une [KekFailure.SourceUnavailable] |
 *
 * Confondre les deux premières avec la troisième est **la** faute à ne pas commettre : un
 * `catch (e: Exception) { return null }` transforme un hoquet du Keystore au démarrage en « aucune
 * clé n'existe », ce qui conduit à la génération d'une clé neuve — et à la perte définitive de
 * toutes les notes, alors que la vraie clé était intacte.
 *
 * Ce n'est pas une crainte théorique : Agenda Tech a livré exactement ce défaut
 * (`DatabaseFactory.acquirePassphrase`, audit F1), où un `catch` unique effaçait la base sur une
 * exception transitoire de `keystore2` pendant un démarrage.
 */
interface KekSource {

    /** Nom court pour les journaux et les messages d'erreur. Jamais de valeur secrète dedans. */
    val name: String

    /**
     * Rend les 32 octets de la KEK, ou `null` si **cette source n'en détient pas**.
     *
     * L'appelant devient propriétaire du tableau et doit l'effacer après usage.
     *
     * @throws KekFailure.SourceUnavailable si la lecture a échoué pour une raison qui ne prouve
     *   pas l'absence de clé — indisponibilité du Keystore, appareil verrouillé, erreur d'E/S.
     */
    fun load(): ByteArray?
}

/**
 * Une source capable aussi de **persister** une KEK, pour le chemin première installation.
 *
 * Séparée de [KekSource] à dessein : la plupart des sources sont en lecture seule (elles lisent ce
 * qu'une version antérieure a écrit), et une interface unique laisserait croire qu'on peut écrire
 * partout. Une seule source est autoritaire en écriture.
 */
interface WritableKekSource : KekSource {

    /**
     * Persiste [kek] **sans rien détruire** : réutilise le matériel de scellage existant s'il y en
     * a un, en crée un sinon. Ne modifie ni n'efface [kek] — c'est à l'appelant.
     *
     * @throws KekFailure.SourceUnavailable si l'écriture échoue. Ne **jamais** avaler l'échec :
     *   une KEK générée mais non persistée rend la base illisible au prochain démarrage.
     */
    fun store(kek: ByteArray)

    /**
     * Persiste [kek] en **repartant d'un matériel de scellage neuf** : tout scellé et toute clé
     * préexistants sont détruits d'abord.
     *
     * ## ⚠️ Appel réservé au chemin « aucune base sur le disque »
     *
     * Détruire le matériel de scellage rend illisible tout scellé antérieur. Si une base existait
     * et était chiffrée par la KEK que ce scellé protégeait, l'appel condamnerait les notes.
     * L'appelant doit avoir **établi** qu'il n'y a rien à perdre — pas l'avoir supposé.
     *
     * ## Pourquoi cette méthode existe
     *
     * Sans elle, une clé de l'`AndroidKeyStore` qui survit à une désinstallation ou à un effacement
     * des données, **et qui a été invalidée par le système**, bloque définitivement l'application :
     * [store] réutilise cette clé morte, échoue, et échoue encore à chaque démarrage suivant.
     * L'utilisateur n'a aucun recours, alors qu'il n'a aucune donnée à protéger.
     *
     * Constat remonté par la relecture externe (Gemini, 2026-08-13) sur ce fichier.
     *
     * @throws KekFailure.SourceUnavailable si la destruction ou l'écriture échoue.
     */
    fun replaceKeyAndStore(kek: ByteArray)
}

/** Échecs de l'acquisition de la KEK. Aucun message ne contient de valeur secrète. */
sealed class KekFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /**
     * Une source n'a pas pu être consultée. **Ne prouve pas** que la clé est absente.
     *
     * Traitement attendu : réessayer, puis renoncer **sans rien détruire**.
     */
    class SourceUnavailable(
        sourceName: String,
        cause: Throwable?,
    ) : KekFailure("source « $sourceName » indisponible", cause)

    /**
     * Aucune source ne détient de clé, **alors qu'une base existe sur le disque**.
     *
     * C'est le cas que toute la conception vise à ne jamais résoudre par une génération de clé :
     * les notes sont là, chiffrées, et une clé neuve les condamnerait. L'application doit s'arrêter
     * sur un écran qui explique la marche à suivre.
     *
     * Voir `docs/03-KEK-ACQUISITION.md` §2, couche ③.
     */
    class NoKeyForExistingDatabase :
        KekFailure(
            "aucune KEK trouvée alors que la base existe — la base n'est PAS touchée",
        )

    /** La clé lue existe mais n'a pas la forme attendue : longueur, encodage. */
    class MalformedKey(sourceName: String, cause: Throwable?) :
        KekFailure("clé mal formée dans la source « $sourceName »", cause)
}
