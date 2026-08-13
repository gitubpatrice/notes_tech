package com.filestech.notes_tech.di

import android.content.Context
import com.filestech.notes_tech.data.local.LegacyDatabaseLocation
import com.filestech.notes_tech.data.local.NotesDatabaseFactory
import com.filestech.notes_tech.domain.repository.UnavailableVaultSealer
import com.filestech.notes_tech.domain.repository.VaultSealer
import com.filestech.notes_tech.security.kek.KekRepository
import com.filestech.notes_tech.security.kek.KeystoreSealedKekSource
import com.filestech.notes_tech.security.kek.WritableKekSource
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
     * Aujourd'hui une seule source : la couche ①, l'alias natif du Keystore. La couche ② (lecture
     * directe du format `flutter_secure_storage`, pour l'utilisateur qui saute la release
     * passerelle) s'ajoutera **après** celle-ci, sans rien modifier d'autre.
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
            sources = listOf(primary),
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
     * 🔴 Le scelleur de coffres tant que la phase 4 n'a rien livré : il **refuse** tout.
     *
     * Un bouchon permissif aurait exactement le comportement qu'on cherche à rendre impossible —
     * écrire en clair les notes d'un coffre — et il ne se verrait pas, puisque rien n'échouerait.
     * Celui-ci se signale à la première tentative.
     *
     * ⚠️ Quand la phase 4 livrera, cette liaison change de cible. Elle ne se supprime pas : le
     * contrat, lui, reste.
     */
    @Provides
    @Singleton
    fun provideVaultSealer(): VaultSealer = UnavailableVaultSealer()
}
