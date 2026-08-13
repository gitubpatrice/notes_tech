package com.filestech.notes_tech.security.kek

import com.filestech.notes_tech.core.crypto.SecretBytes
import com.filestech.notes_tech.core.crypto.wipe
import com.filestech.notes_tech.data.local.SqlCipherRawKey
import timber.log.Timber

/**
 * Décide d'où vient la KEK, et surtout **quand il faut renoncer**.
 *
 * Conception d'ensemble : `docs/03-KEK-ACQUISITION.md`.
 *
 * ## La règle qui prime sur tout le reste
 *
 * > Si aucune source ne rend de clé **alors que la base existe sur le disque**, on s'arrête.
 * > On ne génère **jamais** une clé neuve dans ce cas.
 *
 * Une clé neuve laisserait la base en place, chiffrée par une clé que plus personne ne possède :
 * les notes seraient perdues définitivement, sans message, sans trace. C'est le seul dénouement
 * pire qu'un plantage — et c'est le comportement naturel de tout `getOrCreate`, d'où le soin mis
 * ici à ne pas en être un.
 *
 * L'existence du fichier est le seul discriminant entre « première installation » et « utilisateur
 * qui migre ». Pas l'absence de clé.
 *
 * @param sources interrogées **dans l'ordre**. La première est autoritaire en écriture.
 * @param databaseExists fourni par l'appelant plutôt que calculé ici : cette classe n'a pas à
 *   connaître l'emplacement du fichier, et l'injection rend le cas « base présente » testable sans
 *   fabriquer de base.
 */
class KekRepository(
    private val sources: List<KekSource>,
    private val primary: WritableKekSource,
    private val databaseExists: () -> Boolean,
) {

    /**
     * Rend les 32 octets de la KEK. **L'appelant en devient propriétaire et doit l'effacer.**
     *
     * @throws KekFailure.NoKeyForExistingDatabase base présente, aucune clé — voir la règle
     *   ci-dessus. Rien n'a été écrit ni effacé.
     * @throws KekFailure.SourceUnavailable une source n'a pas pu être consultée, deux fois de
     *   suite. Rien n'a été écrit ni effacé.
     */
    @Synchronized
    fun acquire(): ByteArray {
        // ⚠️ `@Synchronized` n'est pas décoratif — sans lui, deux appels concurrents sur une
        // première installation constatent tous les deux « pas de base, pas de clé », génèrent
        // CHACUN une KEK différente, et la dernière écriture de préférences écrase l'autre. Le
        // thread perdant crée alors une base chiffrée par une clé qui n'est persistée nulle part :
        // au redémarrage suivant, elle est illisible pour toujours.
        //
        // `DatabaseProvider` sérialise déjà l'unique appelant d'aujourd'hui, mais s'appuyer
        // là-dessus reviendrait à confier la sûreté de cette classe à une propriété d'une autre.
        // Un second appelant — service de panique, re-scellage, second processus — la remettrait
        // en défaut sans que rien ne le signale.
        //
        // Constat remonté par la relecture externe (GPT-5.2, 2026-08-13), §1.1.
        firstAvailableKey()?.let { return it }

        // Aucune source ne détient de clé, et aucune n'a échoué : l'absence est donc établie,
        // pas supposée.
        if (databaseExists()) {
            // ⚠️ Le seul chemin qui compte. Ne rien écrire, ne rien effacer, ne rien « réparer ».
            Timber.e("KEK introuvable alors que la base existe — ouverture refusée, base intacte")
            throw KekFailure.NoKeyForExistingDatabase()
        }

        return generateAndPersist()
    }

    /**
     * Parcourt les sources et rend la première clé trouvée.
     *
     * Un échec transitoire n'est **pas** traité comme une absence : il interrompt le parcours et
     * remonte, après une seule nouvelle tentative. Continuer sur les sources suivantes ferait
     * conclure « aucune clé nulle part » alors qu'une source n'a simplement pas pu être lue — et
     * cette conclusion mène soit au refus, soit, si la base n'existe pas encore, à une génération
     * qui écraserait la situation.
     *
     * La tentative unique et immédiate vise une classe d'échec précise : le premier appel pendant
     * que la plateforme finit de démarrer. Au-delà, insister ne sert à rien.
     */
    private fun firstAvailableKey(): ByteArray? = try {
        scanSources()
    } catch (first: KekFailure.SourceUnavailable) {
        Timber.w(first, "source de KEK indisponible — nouvelle tentative, rien n'est touché")
        try {
            scanSources()
        } catch (second: KekFailure.SourceUnavailable) {
            Timber.e(
                second,
                "source de KEK toujours indisponible — ouverture refusée. " +
                    "Cet échec ne prouve PAS que la clé a disparu : la base n'est pas touchée.",
            )
            throw second
        }
    }

    /**
     * Interroge **toutes** les sources, même après l'échec de l'une d'elles.
     *
     * Une première version s'arrêtait à la première [KekFailure.SourceUnavailable]. C'était faux
     * dès l'ajout d'une seconde source : une couche ① définitivement cassée — clé de scellage
     * invalidée par le système, par exemple — empêchait d'atteindre la couche ② qui, elle, détient
     * la clé. L'utilisateur se voyait refuser l'ouverture alors que ses notes étaient
     * parfaitement récupérables.
     *
     * Poursuivre le parcours ne peut rien détruire : une source ne fait que lire. Ce qui doit
     * rester vrai, c'est qu'un échec ne **disparaisse** pas — d'où sa conservation et sa relance
     * si, au bout du compte, aucune clé n'a été trouvée. Sans ça, une source en panne se lirait
     * comme une absence, et l'absence conduit à générer.
     */
    private fun scanSources(): ByteArray? {
        var firstFailure: KekFailure.SourceUnavailable? = null
        for (source in sources) {
            val kek = loadOrNull(source) { failure ->
                if (firstFailure == null) firstFailure = failure
            }
            if (kek != null) return validated(kek, source.name)
        }
        // Aucune clé. Si une source n'a pas pu être lue, l'absence n'est PAS établie : on remonte
        // l'échec, ce qui interdit à l'appelant de conclure « première installation ».
        firstFailure?.let { throw it }
        return null
    }

    /**
     * Lit une source. Rend `null` aussi bien quand la source ne détient rien que quand elle n'a pas
     * pu être lue — mais dans le second cas, [onFailure] reçoit l'échec, qui ne doit pas se perdre.
     *
     * Une [KekFailure.MalformedKey] n'est pas rattrapée : une clé présente mais illisible est un
     * état qu'aucune autre source ne peut réparer, et le taire ferait passer pour une absence ce
     * qui est une corruption.
     */
    private inline fun loadOrNull(
        source: KekSource,
        onFailure: (KekFailure.SourceUnavailable) -> Unit,
    ): ByteArray? = try {
        source.load()
    } catch (e: KekFailure.SourceUnavailable) {
        Timber.w(e, "source « %s » indisponible — on poursuit avec les suivantes", source.name)
        onFailure(e)
        null
    }

    private fun validated(kek: ByteArray, sourceName: String): ByteArray {
        if (kek.size != SqlCipherRawKey.KEY_SIZE_BYTES) {
            kek.wipe()
            throw KekFailure.MalformedKey(sourceName, null)
        }
        Timber.i("KEK obtenue depuis la source « %s »", sourceName)
        return kek
    }

    /**
     * Première installation : on génère et on persiste **avant** de rendre la clé.
     *
     * L'ordre est porteur. Rendre la clé d'abord laisserait l'appelant créer une base chiffrée par
     * une clé que le processus pourrait mourir avant d'avoir persistée — base illisible pour
     * toujours. Ici, un échec de persistance remonte et aucune base n'est créée.
     */
    private fun generateAndPersist(): ByteArray {
        Timber.i("aucune base et aucune KEK — première installation, génération d'une clé")
        val kek = SecretBytes.randomBytes(SqlCipherRawKey.KEY_SIZE_BYTES)
        try {
            // `replaceKeyAndStore` et non `store` : un matériel de scellage peut avoir survécu à
            // une désinstallation ou à un effacement des données. S'il a été invalidé par le
            // système, `store` le réutiliserait, échouerait, et échouerait encore à chaque
            // démarrage — application définitivement bloquée alors que l'utilisateur n'a AUCUNE
            // donnée à protéger.
            //
            // L'appel destructif est légitime **ici et seulement ici** : on vient d'établir, juste
            // au-dessus, que `databaseExists()` est faux. Un scellé sans base ne protège rien.
            primary.replaceKeyAndStore(kek)
        } catch (t: Throwable) {
            // `Throwable` et non `KekFailure` : le fournisseur cryptographique de la plateforme
            // peut lever une `ProviderException` ou toute autre erreur non typée par nous. Avec un
            // `catch` restreint, la clé fraîchement générée resterait en mémoire sur ce chemin.
            // Une KEK qui survit à un plantage est précisément ce qu'un vidage mémoire ramasse.
            //
            // Constat remonté par la relecture externe (GPT-5.2, 2026-08-13), §3.1.
            kek.wipe()
            throw t
        }
        return kek
    }
}
