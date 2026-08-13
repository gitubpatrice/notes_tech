package com.filestech.notes_tech

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Substitue [HiltTestApplication] à [NotesTechApplication] dans les tests instrumentés.
 *
 * Sans ce lanceur, Hilt ne peut pas injecter dans un test : l'application réelle est annotée
 * `@HiltAndroidApp` et construit le graphe de production, pas celui du test.
 *
 * ⚠️ `connectedAndroidTest` **efface les données de l'application** avant de s'exécuter. Sur un
 * appareil portant de vraies notes, c'est une perte définitive. Ces tests ne tournent que sur le
 * S9 de test — jamais sur le téléphone réel.
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
