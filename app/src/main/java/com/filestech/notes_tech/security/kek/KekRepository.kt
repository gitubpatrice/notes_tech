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
            if (kek != null) {
                val cle = validated(kek, source.name)
                // 🔴 On ne recopie **que** si aucune source n'a échoué avant celle-ci.
                //
                // Le scénario que cette condition ferme, relevé par une relecture externe
                // (GPT-5.5, 2026-08-13) :
                //
                //   1. la source primaire est momentanément indisponible — le parcours continue,
                //      c'est voulu ;
                //   2. une source secondaire rend une clé **bien formée** ;
                //   3. la recopie écrase le scellé primaire, qui contenait peut-être une AUTRE clé.
                //
                // Une clé bien formée prouve qu'elle a 32 octets, **pas** qu'elle ouvre la base qui
                // est sur le disque. Si les deux sources divergeaient, la recopie détruirait la
                // dernière copie persistée de la bonne clé.
                //
                // Elles ne divergent pas aujourd'hui : la version Flutter écrit la même valeur des
                // deux côtés et ne la fait jamais tourner. Mais rien dans ce code ne l'impose, et
                // la conséquence d'une divergence future serait irréversible — le même
                // raisonnement que pour les écritures de ligne entière (`docs/01-DECISIONS.md`
                // D-010).
                //
                // La perte est nulle : sans recopie, la source secondaire sera relue au prochain
                // démarrage.
                if (source !== primary && firstFailure == null) promoteToPrimary(cle)
                return cle
            }
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
    private inline fun loadOrNull(source: KekSource, onFailure: (KekFailure.SourceUnavailable) -> Unit): ByteArray? =
        try {
            source.load()
        } catch (e: KekFailure.SourceUnavailable) {
            Timber.w(e, "source « %s » indisponible — on poursuit avec les suivantes", source.name)
            onFailure(e)
            null
        }

    /**
     * Recopie dans la source primaire une clé obtenue ailleurs, pour que le prochain démarrage
     * n'ait plus à en dépendre.
     *
     * C'est ce qui fait de la couche ② un **secours** et non un chemin permanent : dès le premier
     * démarrage réussi, la clé vit sous un alias que ce projet maîtrise, et la transcription du
     * format d'une bibliothèque tierce sort du chemin critique.
     *
     * ## ⚠️ L'échec de la recopie ne doit JAMAIS faire échouer l'acquisition
     *
     * La clé est déjà en main et reste lisible depuis sa source d'origine à chaque démarrage. Une
     * recopie qui échoue coûte une lecture de plus au prochain lancement, rien d'autre. La laisser
     * remonter transformerait un démarrage qui fonctionne en refus d'ouverture — exactement le
     * genre de zèle qui casse ce qu'il prétend améliorer.
     *
     * `Throwable` et non `Exception` : le fournisseur cryptographique de la plateforme lève des
     * erreurs non typées par nous, et aucune ne justifie de perdre une clé valide.
     *
     * ## Pourquoi `store` et surtout pas `replaceKeyAndStore`
     *
     * `replaceKeyAndStore` détruit le matériel de scellage existant. Ici une base existe — c'est
     * tout le scénario — et détruire un scellé dont on ignore ce qu'il protégeait serait la faute
     * que `docs/03-KEK-ACQUISITION.md` interdit.
     */
    private fun promoteToPrimary(kek: ByteArray) {
        try {
            primary.store(kek)
            Timber.i("KEK recopiée dans la source primaire — les prochains démarrages l'y trouveront")
        } catch (t: Throwable) {
            Timber.w(t, "recopie de la KEK impossible — sans conséquence, la clé reste lisible à sa source")
        }
    }

    /**
     * 🔴 **Détruit la KEK dans TOUTES les sources. Point de non-retour du mode panique.**
     *
     * À partir du retour de cette méthode, la base chiffrée en AES-256 est cryptographiquement
     * illisible, même récupérée bit à bit sur le support. C'est la **garantie minimale** de la
     * panique : les étapes qui suivent — effacement du fichier, des préférences, des caches — sont
     * de la défense en profondeur, et une interruption après ce point ne perd plus rien d'essentiel.
     *
     * ## ⚠️ Toutes les sources, et l'échec de l'une n'arrête pas les autres
     *
     * S'arrêter à la première qui résiste laisserait la clé intacte dans les suivantes. Or c'est la
     * **seconde** couche qui compte pour l'utilisateur venu de la version Flutter : sa KEK vit dans
     * `flutter_secure_storage`, et une couche ① récalcitrante ne doit pas la protéger.
     *
     * L'échec est conservé et relancé **à la fin**. Le taire ferait annoncer à l'utilisateur une
     * destruction qui n'a pas eu lieu — sur cet écran-là, précisément, un mensonge se paie en
     * sécurité physique.
     *
     * @throws KekFailure.SourceUnavailable si au moins une source a résisté. Les autres ont bien
     *   été détruites.
     */
    @Synchronized
    fun destroy() {
        var premierEchec: KekFailure.SourceUnavailable? = null
        for (source in sources) {
            try {
                source.destroy()
                Timber.i("KEK détruite dans la source « %s »", source.name)
            } catch (e: KekFailure.SourceUnavailable) {
                Timber.e(e, "KEK NON détruite dans la source « %s » — la panique est incomplète", source.name)
                if (premierEchec == null) premierEchec = e
            }
        }
        premierEchec?.let { throw it }
        verifierQuAucuneSourceNeDetientPlusRien()
    }

    /**
     * 🔴 Relit **toutes** les sources et exige qu'aucune ne rende de clé.
     *
     * ## Pourquoi ce contrôle existe
     *
     * L'étape la plus importante de la panique était la seule à se déclarer réussie sans rien
     * relire. C'est le cas de l'application publiée : son `destroyKek()` appelle la suppression de
     * la bibliothèque de stockage et rend la main, alors que `hasKek()` est écrit dix lignes plus
     * bas et répondrait à la question. Ses étapes d'effacement de base, de préférences et de cache
     * ont toutes été corrigées pour lever si quelque chose survit — deux relectures s'en sont
     * chargées — mais pas celle-là.
     *
     * Une suppression qui échoue en silence donne exactement le pire résultat possible : un écran
     * qui annonce des notes irrécupérables à quelqu'un dont les notes sont encore lisibles.
     *
     * ## ⚠️ Une source illisible compte comme un échec
     *
     * Sur tout autre chemin, « je n'ai pas pu regarder » ne prouve rien et l'on s'abstient. Ici,
     * c'est l'inverse : ne pas pouvoir vérifier qu'une clé a disparu **est** un motif de ne pas
     * annoncer sa disparition. Le doute doit tomber du côté de l'utilisateur, pas du nôtre.
     */
    private fun verifierQuAucuneSourceNeDetientPlusRien() {
        for (source in sources) {
            val restante = try {
                source.load()
            } catch (e: KekFailure) {
                throw KekFailure.SourceUnavailable(source.name, e)
            }
            if (restante != null) {
                restante.wipe()
                Timber.e("la source « %s » détient ENCORE une clé après destruction", source.name)
                throw KekFailure.SourceUnavailable(
                    source.name,
                    IllegalStateException("cle encore presente apres destruction"),
                )
            }
        }
        Timber.i("panique : aucune source ne détient plus de clé — base cryptographiquement illisible")
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
