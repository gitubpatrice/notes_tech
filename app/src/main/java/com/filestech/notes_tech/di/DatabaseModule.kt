package com.filestech.notes_tech.di

import android.content.Context
import android.os.SystemClock
import com.filestech.notes_tech.data.local.LegacyDatabaseLocation
import com.filestech.notes_tech.data.local.NotesDatabaseFactory
import com.filestech.notes_tech.domain.repository.UnavailableVaultSealer
import com.filestech.notes_tech.domain.repository.VaultSealer
import com.filestech.notes_tech.security.kek.FlutterSecureStorageKekSource
import com.filestech.notes_tech.security.kek.KekRepository
import com.filestech.notes_tech.security.kek.KeystoreSealedKekSource
import com.filestech.notes_tech.security.kek.WritableKekSource
import com.filestech.notes_tech.security.vault.AndroidVaultKeystore
import com.filestech.notes_tech.security.vault.FolderVaultService
import com.filestech.notes_tech.security.vault.MonotonicClock
import com.filestech.notes_tech.security.vault.VaultKeystore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Injecté plutôt que référencé directement : un test doit pouvoir imposer un répartiteur
     * déterministe. `Dispatchers.IO` en dur rend les tests dépendants de l'ordonnancement réel.
     */
    @Provides
    @Singleton
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    fun provideKeystoreKekSource(@ApplicationContext context: Context): WritableKekSource =
        KeystoreSealedKekSource(context)

    /**
     * Ordre des sources de clé — il est **significatif**, pas décoratif.
     *
     * **La couche ① d'abord** : l'alias natif du Keystore, écrit par la release passerelle 2.0.4.
     * C'est le chemin nominal, et il n'utilise que des API de plateforme.
     *
     * **La couche ② ensuite** : la lecture directe du format `flutter_secure_storage`, pour
     * l'utilisateur qui saute la 2.0.4. L'ordre compte — la couche ② transcrit à l'envers la
     * cryptographie d'une bibliothèque tierce, et rien ne garantit qu'une version future de
     * celle-ci garde ce format. Elle ne doit être atteinte que si la première n'a rien.
     *
     * Une clé trouvée par la couche ② est **recopiée** dans la couche ① par [KekRepository] : dès
     * le premier démarrage réussi, le format tiers sort du chemin critique.
     *
     * La couche ③ n'est pas une source : c'est le refus, et il vit dans [KekRepository]. Ne pas
     * chercher à l'implémenter ici sous forme de source « qui génère » — ce serait exactement la
     * faute que toute la conception écarte.
     *
     * Cf. `docs/03-KEK-ACQUISITION.md`.
     */
    @Provides
    @Singleton
    fun provideKekRepository(@ApplicationContext context: Context, primary: WritableKekSource): KekRepository =
        KekRepository(
            sources = listOf(primary, FlutterSecureStorageKekSource(context)),
            primary = primary,
            // Passé en lambda et non évalué ici : l'existence du fichier doit être constatée au moment
            // de l'acquisition, pas au moment où le graphe d'injection se construit.
            databaseExists = { LegacyDatabaseLocation.databaseExists(context) },
        )

    @Provides
    @Singleton
    fun provideDatabaseFactory(kekRepository: KekRepository): NotesDatabaseFactory = NotesDatabaseFactory(kekRepository)

    /**
     * L'horloge, injectée plutôt que lue en dur.
     *
     * `Clock.systemUTC()` et non `systemDefaultZone()` : tous les horodatages de la base sont des
     * millisecondes depuis l'époque Unix, une valeur qui n'a pas de fuseau. Attacher un fuseau à
     * une horloge dont on ne lit que `millis()` et `instant()` ne servirait qu'à laisser croire
     * qu'il compte quelque part.
     *
     * Un test substitue `Clock.fixed(...)` pour vérifier ce qui touche au temps — notamment qu'une
     * opération de réparation **ne remonte pas** `updated_at`. Une horloge à moitié injectable rend
     * ce genre de test vacant : vert, mais pour la mauvaise raison.
     */
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()

    /**
     * L'horloge monotone du verrouillage automatique.
     *
     * `elapsedRealtime` et non `uptimeMillis` : la première continue de compter pendant la veille
     * profonde. Un téléphone posé une heure verrouille donc son coffre au réveil, au lieu de le
     * retrouver ouvert comme si l'heure ne s'était pas écoulée.
     *
     * ⚠️ **Écart assumé avec la version Flutter**, qui s'appuie sur un `Stopwatch` — lequel
     * n'avance pas pendant la veille. L'écart ne va que dans un sens : ce portage verrouille plus
     * tôt, jamais plus tard. Pour une garde, c'est le bon sens de l'erreur.
     */
    @Provides
    @Singleton
    fun provideMonotonicClock(): MonotonicClock = MonotonicClock { SystemClock.elapsedRealtime() }

    @Provides
    @Singleton
    fun provideVaultKeystore(keystore: AndroidVaultKeystore): VaultKeystore = keystore

    /**
     * 🔴 Le scelleur de coffres. La phase 4 a livré : la cible n'est plus le bouchon qui refuse.
     *
     * Le contrat, lui, n'a pas bougé — c'est tout l'intérêt de l'avoir écrit avant l'implantation.
     * [UnavailableVaultSealer] reste dans le dépôt : il documente ce qu'un bouchon doit faire dans
     * cette application, à savoir **refuser**. Un bouchon permissif aurait eu exactement le
     * comportement qu'on cherche à rendre impossible — écrire en clair les notes d'un coffre — et
     * il ne se serait pas vu, puisque rien n'aurait échoué.
     */
    @Provides
    @Singleton
    fun provideVaultSealer(service: FolderVaultService): VaultSealer = service
}
