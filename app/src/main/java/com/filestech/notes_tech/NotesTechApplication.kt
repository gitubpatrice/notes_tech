package com.filestech.notes_tech

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.filestech.notes_tech.security.vault.FolderVaultService
import com.filestech.notes_tech.security.vault.VaultAutoLocker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class NotesTechApplication : Application() {

    @Inject
    lateinit var autoLocker: VaultAutoLocker

    @Inject
    lateinit var vaults: FolderVaultService

    /**
     * La portée du processus. `SupervisorJob` pour qu'un travail qui échoue n'emporte pas les
     * autres — le balayage des coffres et la reprise des effacements n'ont aucune raison de
     * dépendre l'un de l'autre.
     */
    private val applicationScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.LOG_ENABLED) {
            Timber.plant(Timber.DebugTree())
        }
        // Aucun arbre planté en release, et c'est délibéré : une application dont l'argument est
        // que rien ne sort de l'appareil n'a pas à écrire le contenu des notes dans `logcat`, que
        // n'importe quelle application disposant de la permission peut lire sur un appareil rooté.
        //
        // R8 supprime de toute façon les appels Timber en release. Pour instrumenter un défaut qui
        // ne se reproduit qu'en build signée, le seul canal qui survit est `println` — cf.
        // `docs/04-PIEGES.md` et les notes de SMS Tech.

        autoLocker.start(applicationScope)
        observerLeCycleDeVieDuProcessus()

        // 🔴 Reprise des effacements de coffre interrompus, **au démarrage et une seule fois**.
        //
        // Un effacement déclenché par cinq codes faux touche le Keystore, la base et les
        // préférences : aucune transaction ne couvre les trois. Si l'application est tuée en cours
        // de route, le dossier reste un coffre dont plus aucune clé n'ouvre les notes, et rien ne
        // le signale. Le drapeau posé avant le premier geste est la seule trace, et c'est ici qu'on
        // la relit.
        //
        // Lancé sans bloquer le démarrage : la reprise est du rattrapage, pas un préalable à
        // l'affichage. `runCatching` parce qu'un échec ici ne doit empêcher aucun lancement — le
        // drapeau, lui, est conservé et la reprise sera retentée au démarrage suivant.
        applicationScope.launch {
            runCatching { vaults.resumePendingWipes() }
        }
    }

    /**
     * Verrouille tous les coffres dès que l'application passe en arrière-plan.
     *
     * ⚠️ **C'est la protection principale, pas le délai d'inactivité.** Le verrouillage automatique
     * couvre l'utilisateur qui laisse l'application ouverte ; celui-ci couvre l'aperçu des
     * applications récentes, le prêt du téléphone, et la fouille. Les deux sont nécessaires, et
     * c'est celui-ci qui répond au modèle de menace annoncé — quelqu'un qui voit l'appareil
     * déverrouillé ne doit pas trouver un coffre ouvert.
     */
    private fun observerLeCycleDeVieDuProcessus() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    vaults.lockAll()
                }
            },
        )
    }
}
