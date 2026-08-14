package com.filestech.notes_tech.security.vault

import com.filestech.notes_tech.core.crypto.wipe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Une horloge qui ne recule pas.
 *
 * ## ⚠️ Pourquoi pas l'heure du système
 *
 * L'auto-verrouillage se calcule par différence entre deux instants. Avec l'heure murale, il suffit
 * de reculer la date de l'appareil pour rendre cette différence négative, donc toujours inférieure
 * au délai : le coffre ne se verrouille alors plus jamais. L'application publiée a corrigé
 * exactement ça en v1.0.3 (F8).
 *
 * ## Injectée **des deux côtés**, jamais à moitié
 *
 * Poser l'échéance avec l'horloge réelle puis la consulter avec une horloge de test — ou l'inverse
 * — donne des délais absurdes et des tests qui passent pour la mauvaise raison. Le portefeuille en
 * a déjà fait les frais : quatre tests verts sur un délai négatif.
 */
fun interface MonotonicClock {
    fun elapsedMillis(): Long
}

/**
 * Les coffres ouverts, et le temps qu'il leur reste.
 *
 * Une entrée présente signifie qu'une clé de coffre est **en mémoire vive**. C'est la seule chose
 * qui sépare des notes chiffrées de leur contenu, et c'est pourquoi cette classe fait si peu : elle
 * garde des clés, elle les efface, et elle ne sait rien de la base ni du chiffrement.
 *
 * ## Le verrouillage automatique est vérifié DEUX fois, et il en faut deux
 *
 * [sessionKey] refuse une session périmée même si aucun balayage n'est passé — sans quoi une clé
 * expirée resterait utilisable jusqu'au prochain réveil. [sweep] efface celles qui ont expiré même
 * si personne ne les demande — sans quoi une clé oubliée resterait en mémoire indéfiniment.
 *
 * L'un sans l'autre est un demi-garde : le premier ne libère rien, le second ne protège rien entre
 * deux passages. C'est la question à se poser devant toute garde échantillonnée — « si la condition
 * devient vraie une seconde plus tard, qui rappelle ce code ? »
 */
@Singleton
class VaultSessions @Inject constructor(private val clock: MonotonicClock) {

    private class Session(val key: ByteArray, var lastActivityMillis: Long)

    private val lock = Any()
    private val sessions = LinkedHashMap<String, Session>()
    private val unlockInProgress = mutableSetOf<String>()
    private val lockoutUntilMillis = HashMap<String, Long>()
    private val consecutiveFailures = HashMap<String, Int>()

    private val _unlockedFolderIds = MutableStateFlow<Set<String>>(emptySet())

    /** Les coffres ouverts, pour l'interface. Ne porte **aucune** clé — seulement des identifiants. */
    val unlockedFolderIds: StateFlow<Set<String>> = _unlockedFolderIds.asStateFlow()

    /** Délai d'inactivité avant verrouillage. `0` ou moins = jamais, réglage hérité. */
    @Volatile
    var autoLockMillis: Long = VaultParams.DEFAULT_AUTO_LOCK_MINUTES * MILLIS_PER_MINUTE

    /**
     * Ouvre une session en prenant la propriété de [key].
     *
     * ⚠️ **L'appelant ne doit plus effacer [key] après cet appel** : le tableau n'est pas recopié,
     * et l'effacer reviendrait à remplir de zéros la clé de la session qui vient de s'ouvrir. La
     * propriété passe ici, et c'est [lock] qui efface.
     */
    fun open(folderId: String, key: ByteArray) = synchronized(lock) {
        sessions.put(folderId, Session(key, clock.elapsedMillis()))?.let { it.key.wipe() }
        publishUnlocked()
    }

    /**
     * La clé du coffre, ou `null` s'il n'est pas ouvert — **ou s'il vient d'expirer**.
     *
     * Consulter une session compte comme une activité : c'est ce qui repousse l'échéance tant que
     * l'utilisateur travaille dans le coffre.
     *
     * ## 🔴 Une COPIE est rendue, et l'appelant DOIT l'effacer
     *
     * Rendre le tableau de la session ouvrait une course qui n'existe pas dans la version Flutter,
     * parce que Dart est mono-fil et que Kotlin ne l'est pas :
     *
     * ```
     *   coroutine A : val cle = sessionKey(f)      // référence sur le tableau de la session
     *   coroutine B : lock(f)                      // remplit CE MÊME tableau de zéros
     *   coroutine A : VaultCrypto.seal(cle, …)     // chiffre sous une clé nulle
     * ```
     *
     * Le résultat serait une note « chiffrée » sous une clé de zéros, écrite en base et
     * indéchiffrable par la suite — la pire des issues, puisqu'elle se présente comme une réussite.
     * Le verrouillage automatique est justement conçu pour tomber sans prévenir : la fenêtre n'est
     * pas théorique.
     *
     * Une copie de trente-deux octets par opération est un prix négligeable pour fermer ça, et elle
     * rend la propriété explicite : ce que l'appelant reçoit est à lui, et il l'efface.
     *
     * @return une copie à effacer par l'appelant, ou `null`.
     */
    fun sessionKey(folderId: String): ByteArray? = synchronized(lock) {
        val session = sessions[folderId] ?: return null
        val now = clock.elapsedMillis()
        if (hasExpired(session, now)) {
            lockLocked(folderId)
            return null
        }
        session.lastActivityMillis = now
        session.key.copyOf()
    }

    /**
     * `true` si une session vivante existe, **sans en matérialiser la clé**.
     *
     * Séparé de [sessionKey] pour qu'un simple prédicat d'affichage — « ce carnet est-il ouvert ? »
     * — ne recopie pas un secret que personne n'effacera ensuite.
     */
    fun hasLiveSession(folderId: String): Boolean = synchronized(lock) {
        val session = sessions[folderId] ?: return false
        val now = clock.elapsedMillis()
        if (hasExpired(session, now)) {
            lockLocked(folderId)
            return false
        }
        session.lastActivityMillis = now
        true
    }

    fun isUnlocked(folderId: String): Boolean = hasLiveSession(folderId)

    /** Repousse l'échéance sans consommer la clé — une frappe, un défilement, une sauvegarde. */
    fun touch(folderId: String) {
        hasLiveSession(folderId)
    }

    /** Ferme un coffre et efface sa clé. Idempotent. */
    fun lock(folderId: String) = synchronized(lock) {
        lockLocked(folderId)
    }

    /** Ferme tout. Appelé à la mise en pause de l'application, et par le mode panique. */
    fun lockAll() = synchronized(lock) {
        if (sessions.isEmpty()) return
        sessions.values.forEach { it.key.wipe() }
        sessions.clear()
        publishUnlocked()
    }

    /**
     * Efface les sessions inactives depuis plus que [autoLockMillis].
     *
     * @return le nombre de millisecondes avant la prochaine échéance, ou `null` s'il n'y a plus
     *   rien à surveiller. C'est ce que l'appelant utilise pour se rendormir juste assez.
     */
    fun sweep(): Long? = synchronized(lock) {
        val delai = autoLockMillis
        if (sessions.isEmpty() || delai <= 0) return null
        val now = clock.elapsedMillis()
        sessions.entries
            .filter { (id, session) -> id !in unlockInProgress && hasExpired(session, now) }
            .map { it.key }
            .forEach(::lockLocked)
        nextDeadlineLocked(now)
    }

    /**
     * Le délai avant la prochaine échéance, ou `null` s'il n'y a rien à surveiller.
     *
     * ⚠️ **C'est l'échéance la plus PROCHE, pas le délai complet.** Se rendormir systématiquement
     * pour [autoLockMillis] créait une famine dans l'application publiée : un seul réveil est
     * partagé par tous les coffres, et toute activité sur l'un repoussait le seul réveil qui aurait
     * pu verrouiller l'autre. Avec deux coffres ouverts et une activité régulière sur le premier,
     * le second ne se verrouillait jamais — la politique était contournée sans que rien ne le dise.
     */
    fun nextDeadlineMillis(): Long? = synchronized(lock) { nextDeadlineLocked(clock.elapsedMillis()) }

    /**
     * Marque un déverrouillage en cours, pour que le balayage ne ferme pas la session qu'on est en
     * train d'ouvrir.
     *
     * La dérivation Argon2id prend de l'ordre de la seconde sur un appareil ancien. Sans ce
     * marquage, une échéance qui tombe pendant ce calcul effacerait la clé entre le moment où elle
     * est posée et celui où elle sert.
     */
    suspend fun <T> whileUnlocking(folderId: String, block: suspend () -> T): T {
        synchronized(lock) { unlockInProgress.add(folderId) }
        return try {
            block()
        } finally {
            synchronized(lock) { unlockInProgress.remove(folderId) }
        }
    }

    // ── Freinage après échec ─────────────────────────────────────────────────────────────────────

    /**
     * Le temps restant avant qu'une nouvelle tentative soit acceptée. `0` si aucune n'est en cours.
     *
     * Le compteur vit en mémoire : redémarrer l'application le remet à zéro. Ce n'est pas un trou —
     * qui redémarre paie le coût du redémarrage à chaque essai, et le compteur de tentatives des
     * coffres à code, lui, est persisté et borne le tout à cinq.
     */
    fun lockoutRemainingMillis(folderId: String): Long = synchronized(lock) {
        val until = lockoutUntilMillis[folderId] ?: return 0
        val remaining = until - clock.elapsedMillis()
        if (remaining <= 0) {
            lockoutUntilMillis.remove(folderId)
            return 0
        }
        remaining
    }

    /** Arme le freinage après un échec, et rend le nombre d'échecs consécutifs. */
    fun recordFailure(folderId: String): Int = synchronized(lock) {
        val failures = (consecutiveFailures[folderId] ?: 0) + 1
        consecutiveFailures[folderId] = failures
        lockoutUntilMillis[folderId] = clock.elapsedMillis() + backoffMillis(failures)
        failures
    }

    /**
     * Efface freinage et compteur après une réussite.
     *
     * ⚠️ **Les deux, pas seulement le compteur.** L'application publiée purgeait bien les deux sur
     * le chemin phrase secrète et n'en purgeait qu'un sur le chemin code : après un échec suivi
     * d'une réussite, verrouiller puis rouvrir se heurtait à un freinage fantôme, hérité de l'échec
     * précédent. Jumeau asymétrique, relevé par une relecture externe.
     */
    fun clearFailures(folderId: String) = synchronized(lock) {
        lockoutUntilMillis.remove(folderId)
        consecutiveFailures.remove(folderId)
    }

    private fun hasExpired(session: Session, now: Long): Boolean {
        val delai = autoLockMillis
        return delai > 0 && now - session.lastActivityMillis >= delai
    }

    private fun lockLocked(folderId: String) {
        sessions.remove(folderId)?.let {
            it.key.wipe()
            publishUnlocked()
        }
    }

    private fun nextDeadlineLocked(now: Long): Long? {
        val delai = autoLockMillis
        if (delai <= 0) return null
        if (sessions.isEmpty()) {
            // Aucune session ouverte — mais une ouverture peut être en cours, et sa clé entrera
            // dans la table une seconde plus tard. Rendre `null` ici annoncerait « plus rien à
            // surveiller » au moment précis où il va y avoir quelque chose à surveiller.
            return if (unlockInProgress.isEmpty()) null else MIN_SWEEP_DELAY_MILLIS
        }
        // 🔴 **Les sessions en cours de déverrouillage comptent ICI**, alors qu'elles sont exclues
        // de [sweep]. Les deux filtres n'ont pas le même objet, et les confondre ouvre un trou.
        //
        // Exclure une session du VERROUILLAGE pendant son ouverture est nécessaire : sinon la clé
        // s'efface entre sa pose et son usage. L'exclure du CALCUL D'ÉCHÉANCE ne l'est pas, et si
        // c'est la seule session ouverte, cette méthode rendrait `null` — « plus rien à
        // surveiller ». Le planificateur s'arrêterait, l'ouverture se terminerait une seconde plus
        // tard, et plus personne ne rappellerait le balayage : la clé resterait en mémoire
        // indéfiniment.
        //
        // C'est la question à poser devant toute garde échantillonnée — « si la condition devient
        // vraie une seconde plus tard, qui rappelle ce code ? ». Ici, personne. Défaut trouvé en
        // écrivant `VaultSessionsTest`, sur du code que je venais d'écrire.
        //
        // Le plancher borne le coût : au pire un réveil par seconde pendant la dérivation.
        val plusProche = sessions.values.minOf { delai - (now - it.lastActivityMillis) }
        return plusProche.coerceAtLeast(MIN_SWEEP_DELAY_MILLIS)
    }

    private fun publishUnlocked() {
        _unlockedFolderIds.value = sessions.keys.toSet()
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L

        /** Plancher de réveil : évite une rafale si une échéance est déjà dépassée. */
        const val MIN_SWEEP_DELAY_MILLIS = 1_000L

        const val BACKOFF_BASE_MILLIS = 1_000L
        const val BACKOFF_MAX_MILLIS = 30_000L
        const val BACKOFF_MAX_SHIFT = 5

        /** 1, 2, 4, 8, 16 puis 30 secondes. Recopié de `_armPinLockout`. */
        fun backoffMillis(failures: Int): Long {
            val shift = (failures - 1).coerceIn(0, BACKOFF_MAX_SHIFT)
            return (BACKOFF_BASE_MILLIS shl shift).coerceIn(BACKOFF_BASE_MILLIS, BACKOFF_MAX_MILLIS)
        }
    }
}
