package com.filestech.notes_tech.security.kek

import com.filestech.notes_tech.data.local.SqlCipherRawKey
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * La règle que ces tests protègent, et la seule qui compte vraiment :
 *
 * > Si aucune source ne rend de clé **alors que la base existe sur le disque**, on s'arrête.
 * > On ne génère **jamais** une clé neuve dans ce cas.
 *
 * Une clé neuve laisserait la base en place, chiffrée par une clé que plus personne ne possède :
 * perte définitive, sans message, sans trace. C'est le comportement naturel de tout `getOrCreate`,
 * d'où le soin mis ici à prouver que ce n'en est pas un.
 */
class KekRepositoryTest {

    private val kek = ByteArray(SqlCipherRawKey.KEY_SIZE_BYTES) { (it + 1).toByte() }

    // ── Le cas nominal ───────────────────────────────────────────────────────

    @Test
    fun `la cle de la premiere source disponible est rendue`() {
        val primaire = FakeWritableSource("primaire", kek)
        val repository = KekRepository(listOf(primaire), primaire, databaseExists = { true })

        assertThat(repository.acquire()).isEqualTo(kek)
        assertThat(primaire.ecritures).isEqualTo(0)
    }

    @Test
    fun `les sources sont interrogees dans l'ordre`() {
        val vide = FakeSource("vide", null)
        val porteuse = FakeSource("porteuse", kek)
        val primaire = FakeWritableSource("primaire", null)
        val repository = KekRepository(listOf(vide, porteuse), primaire, databaseExists = { true })

        assertThat(repository.acquire()).isEqualTo(kek)
        assertThat(vide.lectures).isEqualTo(1)
        assertThat(porteuse.lectures).isEqualTo(1)
    }

    // ── La règle ─────────────────────────────────────────────────────────────

    @Test
    fun `aucune cle et une base presente donne un refus, et AUCUNE ecriture`() {
        val primaire = FakeWritableSource("primaire", null)
        val repository = KekRepository(listOf(primaire), primaire, databaseExists = { true })

        assertThrows<KekFailure.NoKeyForExistingDatabase> { repository.acquire() }

        // L'assertion qui porte la promesse faite à l'utilisateur sur l'écran d'échec.
        assertThat(primaire.ecritures).isEqualTo(0)
    }

    @Test
    fun `aucune cle et aucune base declenche une generation persistee AVANT d'etre rendue`() {
        val primaire = FakeWritableSource("primaire", null)
        val repository = KekRepository(listOf(primaire), primaire, databaseExists = { false })

        val obtenue = repository.acquire()

        assertThat(obtenue).hasLength(SqlCipherRawKey.KEY_SIZE_BYTES)
        assertThat(primaire.ecritures).isEqualTo(1)
        // Persistée avant d'être rendue : sinon l'appelant pourrait créer une base chiffrée par
        // une clé que le processus mourrait avant d'avoir écrite.
        assertThat(primaire.derniereEcriture).isEqualTo(obtenue)
    }

    @Test
    fun `la generation passe par le remplacement du materiel de scellage`() {
        val primaire = FakeWritableSource("primaire", null)
        val repository = KekRepository(listOf(primaire), primaire, databaseExists = { false })

        repository.acquire()

        // `replaceKeyAndStore` et non `store` : une clé Keystore invalidée ayant survécu à une
        // désinstallation bloquerait sinon l'application définitivement, alors que l'utilisateur
        // n'a aucune donnée à protéger. Cf. docs/07-RELECTURES.md R-001, constat 3.
        assertThat(primaire.remplacements).isEqualTo(1)
        assertThat(primaire.ecrituresNonDestructives).isEqualTo(0)
    }

    @Test
    fun `une cle generee mais non persistable n'est pas rendue`() {
        val primaire = FakeWritableSource("primaire", null, echoueEnEcriture = true)
        val repository = KekRepository(listOf(primaire), primaire, databaseExists = { false })

        assertThrows<KekFailure.SourceUnavailable> { repository.acquire() }
    }

    // ── Ce qui distingue « absente » de « pas pu regarder » ───────────────────

    @Test
    fun `une source indisponible est retentee une fois`() {
        val instable = FakeSource("instable", kek, echecsAvantSucces = 1)
        val primaire = FakeWritableSource("primaire", null)
        val repository = KekRepository(listOf(instable), primaire, databaseExists = { true })

        // La tentative unique vise une classe d'échec précise : le premier appel pendant que la
        // plateforme finit de démarrer.
        assertThat(repository.acquire()).isEqualTo(kek)
        assertThat(instable.lectures).isEqualTo(2)
    }

    @Test
    fun `une source durablement indisponible fait echouer sans generer`() {
        val cassee = FakeSource("cassee", null, echecsAvantSucces = Int.MAX_VALUE)
        val primaire = FakeWritableSource("primaire", null)
        // ⚠️ `databaseExists = false` : même sans base à protéger, un échec de lecture ne doit PAS
        // être confondu avec une absence de clé. Sinon une source en panne conduirait à générer
        // une clé neuve par-dessus une situation qu'on n'a pas su lire.
        val repository = KekRepository(listOf(cassee), primaire, databaseExists = { false })

        assertThrows<KekFailure.SourceUnavailable> { repository.acquire() }

        assertThat(primaire.ecritures).isEqualTo(0)
    }

    @Test
    fun `une source cassee n'empeche pas d'atteindre la suivante`() {
        val cassee = FakeSource("cassee", null, echecsAvantSucces = Int.MAX_VALUE)
        val porteuse = FakeSource("porteuse", kek)
        val primaire = FakeWritableSource("primaire", null)
        val repository =
            KekRepository(listOf(cassee, porteuse), primaire, databaseExists = { true })

        // Le défaut que ce test fige : une première version s'arrêtait à la première source en
        // échec. Une couche ① définitivement cassée aurait empêché d'atteindre la couche ② qui,
        // elle, détient la clé — refus d'ouverture alors que les notes étaient récupérables.
        assertThat(repository.acquire()).isEqualTo(kek)
    }

    @Test
    fun `une cle de mauvaise taille est rejetee plutot qu'utilisee`() {
        val tronquee = FakeSource("tronquee", ByteArray(16))
        val primaire = FakeWritableSource("primaire", null)
        val repository = KekRepository(listOf(tronquee), primaire, databaseExists = { true })

        assertThrows<KekFailure.MalformedKey> { repository.acquire() }
    }

    // ── Destruction : le point de non-retour du mode panique ─────────────────

    /**
     * 🔴 Ces doublures existaient depuis l'écriture de `destroy()` **et aucun test ne s'en
     * servait**. Le motif du chemin mort, appliqué aux tests : l'échafaudage était là, la
     * vérification manquait. Relevé par un audit de sécurité (2026-08-14).
     */
    @Test
    @DisplayName("détruire vide TOUTES les sources, pas seulement la première")
    fun `destroy vide toutes les sources`() {
        val couche1 = FakeWritableSource("keystore", kek)
        val couche2 = FakeSource("flutter_secure_storage", kek)
        val repository = KekRepository(listOf(couche1, couche2), couche1, databaseExists = { true })

        repository.destroy()

        assertThat(couche1.destructions).isEqualTo(1)
        assertThat(couche2.destructions).isEqualTo(1)
    }

    /**
     * 🔴 **Le cas de l'utilisateur venu de la version Flutter.**
     *
     * Sa KEK vit dans la couche ②. Si l'échec de la couche ① interrompait le parcours, la panique
     * paraîtrait avoir fonctionné sans rien protéger : la base resterait parfaitement déchiffrable
     * par quiconque réinstalle l'ancienne version.
     */
    @Test
    @DisplayName("l'échec d'une source n'empêche pas la destruction des suivantes")
    fun `destroy poursuit apres un echec`() {
        val couche1 = FakeWritableSource("keystore", kek).apply { refuseLaDestruction = true }
        val couche2 = FakeSource("flutter_secure_storage", kek)
        val repository = KekRepository(listOf(couche1, couche2), couche1, databaseExists = { true })

        assertThrows<KekFailure.SourceUnavailable> { repository.destroy() }

        // L'échec est bien remonté — mais APRÈS que la seconde source a été détruite.
        assertThat(couche2.destructions).isEqualTo(1)
    }

    /**
     * 🔴 **Le défaut que l'application publiée avait, et que la vérification ferme.**
     *
     * `_storage.delete` ne rend aucun statut : un échec côté plateforme est indiscernable d'un
     * succès. Sans relecture, le rapport porterait « clé détruite » et l'écran de fin annoncerait
     * des notes irrécupérables à quelqu'un dont les notes restent lisibles.
     */
    @Test
    @DisplayName("une suppression silencieusement inopérante fait ÉCHOUER la destruction")
    fun `destroy echoue si la cle survit`() {
        val source = FakeWritableSource("keystore", kek).apply { destructionInoperante = true }
        val repository = KekRepository(listOf(source), source, databaseExists = { true })

        assertThrows<KekFailure.SourceUnavailable> { repository.destroy() }

        // La destruction a bien été TENTÉE : c'est la relecture qui a refusé de la déclarer faite.
        assertThat(source.destructions).isEqualTo(1)
    }

    /**
     * ⚠️ Sur tout autre chemin, « je n'ai pas pu regarder » ne prouve rien et l'on s'abstient. Ici
     * c'est l'inverse : **ne pas pouvoir vérifier qu'une clé a disparu est un motif de ne pas
     * annoncer sa disparition.** Le doute tombe du côté de l'utilisateur.
     */
    @Test
    @DisplayName("une source devenue illisible après destruction fait échouer la destruction")
    fun `destroy echoue si la verification est impossible`() {
        val source = FakeWritableSource("keystore", kek).apply { illisibleApresDestruction = true }
        val repository = KekRepository(listOf(source), source, databaseExists = { true })

        assertThrows<KekFailure.SourceUnavailable> { repository.destroy() }
    }

    // ── Doublures ────────────────────────────────────────────────────────────

    private open class FakeSource(override val name: String, kek: ByteArray?, private val echecsAvantSucces: Int = 0) :
        KekSource {
        /** Muable : une destruction réussie doit se **voir** à la relecture, comme en production. */
        private var cle: ByteArray? = kek

        var lectures = 0
            private set

        var destructions = 0
            private set

        /** Simule une source qui résiste à la destruction — un Keystore muet, par exemple. */
        var refuseLaDestruction = false

        /**
         * Simule une suppression **silencieusement inopérante** : elle ne lève pas, et la clé reste.
         *
         * 🔴 C'est exactement le cas que la vérification de [KekRepository.destroy] existe pour
         * attraper, et celui que l'application publiée ne détectait pas.
         */
        var destructionInoperante = false

        /** Simule une source devenue illisible **après** la destruction : on ne peut plus vérifier. */
        var illisibleApresDestruction = false

        override fun load(): ByteArray? {
            lectures++
            if (lectures <= echecsAvantSucces) {
                throw KekFailure.SourceUnavailable(name, null)
            }
            if (destructions > 0 && illisibleApresDestruction) {
                throw KekFailure.SourceUnavailable(name, null)
            }
            return cle?.copyOf()
        }

        override fun destroy() {
            if (refuseLaDestruction) throw KekFailure.SourceUnavailable(name, null)
            destructions++
            if (!destructionInoperante) cle = null
        }
    }

    private class FakeWritableSource(name: String, kek: ByteArray?, private val echoueEnEcriture: Boolean = false) :
        FakeSource(name, kek),
        WritableKekSource {
        var ecrituresNonDestructives = 0
            private set
        var remplacements = 0
            private set
        var derniereEcriture: ByteArray? = null
            private set

        val ecritures: Int get() = ecrituresNonDestructives + remplacements

        override fun store(kek: ByteArray) {
            if (echoueEnEcriture) throw KekFailure.SourceUnavailable(name, null)
            ecrituresNonDestructives++
            derniereEcriture = kek.copyOf()
        }

        override fun replaceKeyAndStore(kek: ByteArray) {
            if (echoueEnEcriture) throw KekFailure.SourceUnavailable(name, null)
            remplacements++
            derniereEcriture = kek.copyOf()
        }
    }

    /**
     * Une clé trouvée par une source **secondaire** est recopiée dans la primaire.
     *
     * C'est ce qui fait de la couche ② un secours et non un chemin permanent : dès le premier
     * démarrage réussi, la transcription du format d'une bibliothèque tierce sort du chemin
     * critique.
     */
    @Test
    @DisplayName("une clé trouvée ailleurs est recopiée dans la source primaire")
    fun promotionVersLaSourcePrimaire() {
        val attendue = ByteArray(32) { (it * 3).toByte() }
        val primaire = SourceEnregistreuse(detient = null)
        val secours = SourceSimple("secours", attendue)
        val depot = KekRepository(listOf(primaire, secours), primaire, databaseExists = { true })

        val obtenue = depot.acquire()

        assertThat(obtenue).isEqualTo(attendue)
        assertThat(primaire.stockee).isEqualTo(attendue)
        // ⚠️ `store` et non `replaceKeyAndStore` : une base existe, détruire le matériel de
        // scellage existant serait la faute que toute la conception écarte.
        assertThat(primaire.remplacements).isEqualTo(0)
    }

    /**
     * ⚠️ **Une recopie qui échoue ne doit pas faire échouer l'acquisition.**
     *
     * La clé est déjà en main et reste lisible à sa source d'origine. Laisser l'échec remonter
     * transformerait un démarrage qui fonctionne en refus d'ouverture.
     */
    @Test
    @DisplayName("l'échec de la recopie ne fait pas échouer l'acquisition")
    fun promotionEnEchecNEmpecheRien() {
        val attendue = ByteArray(32) { (it * 5).toByte() }
        val primaire = SourceEnregistreuse(detient = null, echoueALEcriture = true)
        val secours = SourceSimple("secours", attendue)
        val depot = KekRepository(listOf(primaire, secours), primaire, databaseExists = { true })

        assertThat(depot.acquire()).isEqualTo(attendue)
    }

    /**
     * 🔴 **Une clé secondaire n'est PAS recopiée si une source a échoué avant elle.**
     *
     * Le scénario fermé par cette condition : la source primaire est momentanément indisponible, une
     * secondaire rend une clé bien formée, et la recopie écrase le scellé primaire — qui contenait
     * peut-être une AUTRE clé.
     *
     * Une clé bien formée prouve qu'elle a 32 octets, pas qu'elle ouvre la base sur le disque.
     * Relevé par une relecture externe (GPT-5.5, 2026-08-13).
     */
    @Test
    @DisplayName("aucune recopie si une source a echoue pendant le parcours")
    fun pasDePromotionApresUnEchec() {
        val attendue = ByteArray(32) { (it * 7).toByte() }
        val primaire = SourceEnregistreuse(detient = null, echoueALaLecture = true)
        val secours = SourceSimple("secours", attendue)
        val depot = KekRepository(listOf(primaire, secours), primaire, databaseExists = { true })

        assertThat(depot.acquire()).isEqualTo(attendue)

        // La clé est bien rendue — l'utilisateur ouvre ses notes — mais rien n'a été écrasé.
        assertThat(primaire.stockee).isNull()
    }

    private class SourceSimple(override val name: String, private val cle: ByteArray?) : KekSource {
        var detruite = false
            private set

        override fun load(): ByteArray? = cle?.copyOf()

        override fun destroy() {
            detruite = true
        }
    }

    private class SourceEnregistreuse(
        private val detient: ByteArray?,
        private val echoueALEcriture: Boolean = false,
        private val echoueALaLecture: Boolean = false,
    ) : WritableKekSource {
        override val name = "primaire"
        var stockee: ByteArray? = null
        var remplacements = 0

        override fun load(): ByteArray? {
            if (echoueALaLecture) throw KekFailure.SourceUnavailable(name, null)
            return detient?.copyOf()
        }

        override fun store(kek: ByteArray) {
            if (echoueALEcriture) throw KekFailure.SourceUnavailable(name, null)
            stockee = kek.copyOf()
        }

        override fun replaceKeyAndStore(kek: ByteArray) {
            remplacements++
            stockee = kek.copyOf()
        }

        override fun destroy() {
            stockee = null
        }
    }
}
