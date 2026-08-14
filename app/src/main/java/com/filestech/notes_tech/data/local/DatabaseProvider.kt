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

    @Volatile
    private var scelle = false

    /**
     * Rend la base, en l'ouvrant au premier appel.
     *
     * @throws DatabaseSealedException après un mode panique. Voir [sealForPanic].
     * @throws com.filestech.notes_tech.security.kek.KekFailure si la clé est introuvable ou
     *   inaccessible. **La base sur le disque n'est jamais touchée** dans ce cas — l'échec est
     *   rejouable une fois la cause levée.
     */
    suspend fun get(): NotesDatabase {
        if (scelle) throw DatabaseSealedException()
        return instance ?: mutex.withLock {
            // ⚠️ Le sceau est relu SOUS LE VERROU. Un appelant peut avoir franchi le contrôle
            // ci-dessus juste avant la panique, puis attendu ici pendant que `sealForPanic` fermait
            // la base : sans cette seconde lecture, il rouvrirait le fichier qu'on vient d'effacer.
            if (scelle) throw DatabaseSealedException()
            // Relecture sous verrou : un appelant a pu ouvrir pendant qu'on attendait.
            instance ?: withContext(ioDispatcher) { factory.build(context) }.also { instance = it }
        }
    }

    /**
     * 🔴 Ferme la base et **interdit définitivement de la rouvrir**, pour la durée du processus.
     *
     * ## Le défaut que ce sceau ferme
     *
     * [close] seul ne suffit pas : il oublie l'instance, et le prochain `get()` en ouvre une neuve.
     * Or `get()` reste appelé pendant la panique — un `Flow` de Room encore abonné, une portée
     * applicative qui n'a pas fini son travail. La reconstruction passerait par
     * [NotesDatabaseFactory], qui, ne trouvant plus ni clé ni fichier, **en générerait une paire
     * neuve**.
     *
     * Le résultat serait une base vide et une clé fraîche recréées quelques millisecondes après
     * l'effacement, c'est-à-dire un fichier de base sur le disque d'un appareil dont on vient
     * d'annoncer à son propriétaire qu'il n'en restait rien. L'étape d'effacement, elle, aurait
     * vérifié la disparition **avant** la recréation et se serait déclarée réussie.
     *
     * ## ⚠️ Le sceau ne doit pas survivre au processus
     *
     * Il vit dans un objet unique du graphe d'injection, donc aussi longtemps que le processus. Si
     * l'application était relancée sans que le processus meure, elle trouverait une base scellée et
     * ne démarrerait plus, sans explication. C'est pourquoi l'écran de fin **termine le processus**
     * et ne se contente pas de fermer l'activité.
     */
    suspend fun sealForPanic() = mutex.withLock {
        scelle = true
        instance?.let { withContext(ioDispatcher) { it.close() } }
        instance = null
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

/**
 * La base a été scellée par un mode panique et ne se rouvrira pas dans ce processus.
 *
 * Ce n'est pas une panne : c'est la conséquence voulue d'un geste de l'utilisateur. Un `Flow` qui la
 * reçoit doit se terminer sur un état d'erreur, pas se retenter.
 */
class DatabaseSealedException :
    IllegalStateException("base scellée par le mode panique — aucune réouverture dans ce processus")
