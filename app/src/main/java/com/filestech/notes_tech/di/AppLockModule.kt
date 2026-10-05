package com.filestech.notes_tech.di

import com.filestech.notes_tech.security.applock.AndroidAppLockKeystore
import com.filestech.notes_tech.security.applock.AndroidBiometricUnlockKey
import com.filestech.notes_tech.security.applock.AndroidBootCounter
import com.filestech.notes_tech.security.applock.AppLockKeystore
import com.filestech.notes_tech.security.applock.AppLockPinVerifier
import com.filestech.notes_tech.security.applock.AppLockStore
import com.filestech.notes_tech.security.applock.BiometricUnlockKey
import com.filestech.notes_tech.security.applock.BootCounter
import com.filestech.notes_tech.security.applock.PinVerifier
import com.filestech.notes_tech.security.applock.PreferencesAppLockStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The app lock's device-bound pieces, each behind an interface so that `AppLockManager` — where every
 * decision is taken — is tested on the JVM (D-023).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppLockModule {

    @Binds
    @Singleton
    abstract fun bindKeystore(implementation: AndroidAppLockKeystore): AppLockKeystore

    @Binds
    @Singleton
    abstract fun bindVerifier(implementation: AppLockPinVerifier): PinVerifier

    @Binds
    @Singleton
    abstract fun bindStore(implementation: PreferencesAppLockStore): AppLockStore

    @Binds
    @Singleton
    abstract fun bindBiometricKey(implementation: AndroidBiometricUnlockKey): BiometricUnlockKey

    @Binds
    @Singleton
    abstract fun bindBootCounter(implementation: AndroidBootCounter): BootCounter
}
