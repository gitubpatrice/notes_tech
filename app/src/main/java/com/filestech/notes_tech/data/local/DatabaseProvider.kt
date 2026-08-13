package com.filestech.notes_tech.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Détient l'unique instance de [NotesDatabase] et en diffère l'ouverture.
 *
 * ## Pourquoi la base n'est pas simplement fournie par Hilt
 *
 * L'ouvrir coûte cher et peut **échouer légitimement** : elle suppose un aller-retour dans le
 * Keystore, la dérivation d'un matériel de clé, une ouverture native SQLCipher et la validation
 * de schéma de Room. Un `@Provides` la construirait sur le fil appelant — donc potentiellement le
 * fil principal — et transformerait un échec de clé en plantage à l'injection, à un endroit où
 * plus personne ne peut afficher d'explication.
 *
 * Ici, l'échec remonte à l'appelant sous forme d'exception typée, et l'interface le présente.
 *
 * ## L'ouverture n'a lieu qu'une fois
 *
 * Le double contrôle avec verrou n'est pas de la superstition : sans lui, deux appelants
 * simultanés au démarrage — l'écran d'accueil et une reprise d'état — ouvriraient deux instances
 * Room sur le même fichier, chacune avec son pool de connexions et son suivi d'invalidation. Les
 * `Flow` de l'une ne verraient pas les écritures de l'autre.
 */
@Singleton
class DatabaseProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val factory: NotesDatabaseFactory,
    private val ioDispatcher: CoroutineDispatcher,
) {

    private val mutex = Mutex()

    @Volatile
    private var instance: NotesDatabase? = null

    /**
     * Rend la base, en l'ouvrant au premier appel.
     *
     * @throws com.filestech.notes_tech.security.kek.KekFailure si la clé est introuvable ou
     *   inaccessible. **La base sur le disque n'est jamais touchée** dans ce cas — l'échec est
     *   rejouable une fois la cause levée.
     */
    suspend fun get(): NotesDatabase = instance ?: mutex.withLock {
        // Relecture sous verrou : un appelant a pu ouvrir pendant qu'on attendait.
        instance ?: withContext(ioDispatcher) { factory.build(context) }.also { instance = it }
    }

    /**
     * `true` si la base a déjà été ouverte. Ne déclenche **pas** l'ouverture.
     *
     * Sert aux chemins qui doivent savoir sans provoquer : fermeture, mode panique, diagnostic.
     */
    fun isOpen(): Boolean = instance?.isOpen == true

    /**
     * Ferme la base et oublie l'instance.
     *
     * Utilisé par le mode panique, qui doit fermer avant d'effacer le fichier. Idempotent.
     */
    suspend fun close() = mutex.withLock {
        instance?.let { withContext(ioDispatcher) { it.close() } }
        instance = null
    }
}
