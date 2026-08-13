package com.filestech.notes_tech

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class NotesTechApplication : Application() {

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
    }
}
