package com.filestech.notes_tech.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * La portée qui vit aussi longtemps que le processus.
 *
 * ## 🔴 À quoi elle sert, et à quoi elle ne sert PAS
 *
 * Elle sert aux gestes qui **doivent survivre à l'écran qui les demande**. Le cas qui l'a rendue
 * nécessaire : l'enregistrement final d'une note quand l'éditeur se ferme. `viewModelScope` est
 * annulé à l'instant précis où ce geste est demandé, et `withContext(NonCancellable)` ne protège
 * qu'une coroutine **déjà démarrée** — sur une portée annulée, `launch` crée une coroutine qui
 * n'exécute jamais son corps. Le texte tapé disparaissait sans un mot.
 *
 * Elle ne sert **pas** à échapper au cycle de vie par confort. Un travail lancé ici n'est jamais
 * annulé : le lui confier, c'est promettre qu'il se termine vite et qu'il ne tient aucune ressource
 * liée à un écran disparu.
 *
 * `SupervisorJob` pour qu'un travail qui échoue n'emporte pas les autres — le balayage des coffres,
 * la reprise des effacements et un enregistrement de note n'ont aucune raison de dépendre les uns
 * des autres.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object ApplicationScopeModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
