package com.filestech.notes_tech.di

import com.filestech.notes_tech.data.voice.WhisperStt
import com.filestech.notes_tech.domain.voice.SpeechToText
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * La dictée, reliée à son implémentation.
 *
 * ## Pourquoi un module pour une seule ligne
 *
 * Parce que c'est la ligne qui rend la décision **D-002** vraie. Elle annonçait l'isolation de la
 * dictée derrière une interface de domaine ; tant que les appelants nommaient `WhisperStt`, cette
 * isolation n'existait que sur le papier — et une décision qui décrit une propriété que le code ne
 * porte pas est du même genre qu'un commentaire qui ment.
 *
 * ⚠️ **Les appelants dépendent de [SpeechToText], jamais de [WhisperStt].** Le jour où le moteur
 * change — `sherpa-onnx`, une variante future — seul ce fichier bouge. C'est la même raison qui a
 * fait poser `VaultOpener` à côté de `VaultSealer` : une dépendance à « transcrire » ne doit pas
 * donner « charger un binaire natif » en prime.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceModule {

    @Binds
    @Singleton
    abstract fun lieLaDictee(implementation: WhisperStt): SpeechToText
}
