package com.filestech.notes_tech.security.kek

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.data.local.SqlCipherRawKey
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import javax.crypto.KeyGenerator

/**
 * 🔴 **The key of the 2.0.4 bridge deleted by Android: the database opens again, from the Flutter copy,
 * and is resealed** — security audit of 2026-09-26, K3, the path read by hand that afternoon and never
 * measured until then.
 *
 * The 2.0.4-2.0.9 bridge sealed the database key under a Keystore key with `setUnlockedDeviceRequired`,
 * and Android deletes such a key when the screen lock is removed (measured on API 34, D-026). The
 * test builds that key, seals under it, deletes it as Android would, then asks the real
 * [KekRepository]: the primary source must answer "nothing here" — not fail — the
 * `flutter_secure_storage` copy must give the key, and the primary must hold it again afterwards.
 *
 * ⚠️ **Its own alias and its own preferences file**: the real ones hold the key of the test app's
 * database, which this test deletes a key of.
 */
@RunWith(AndroidJUnit4::class)
class PasserelleCleDisparueTest {

    private val reel: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var prefs: SharedPreferences
    private lateinit var source: KeystoreSealedKekSource

    private class ContexteIsole(base: Context) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences(PREFS_DE_TEST, mode)
    }

    @Before
    fun preparer() {
        // The attribute exists from Android 9; below it, the bridge key could not carry it.
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        prefs = reel.getSharedPreferences(PREFS_DE_TEST, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        supprimerLaCle()
        source = KeystoreSealedKekSource(ContexteIsole(reel), keyAlias = ALIAS_DE_TEST)
    }

    @After
    fun nettoyer() {
        prefs.edit().clear().commit()
        supprimerLaCle()
    }

    @Test
    fun the_bridge_key_deleted_by_android_is_rescued_by_the_flutter_copy_and_resealed() {
        val kek = ByteArray(SqlCipherRawKey.KEY_SIZE_BYTES) { (it * 17 + 3).toByte() }
        creerCommeLaPasserelle()
        source.store(kek.copyOf()) // seals under the existing key: the bridge's
        assertThat(source.load()).isEqualTo(kek)

        supprimerLaCle() // what removing the screen lock does to that key

        // "Nothing here", not a failure: a failure would stop the scan before the Flutter copy.
        assertThat(source.load()).isNull()

        val copieFlutter = object : KekSource {
            override val name = "flutter-test"
            override fun load(): ByteArray = kek.copyOf()
            override fun destroy() = Unit
        }
        val obtenue = KekRepository(
            sources = listOf(source, copieFlutter),
            primary = source,
            databaseExists = { true },
        ).acquire()

        assertThat(obtenue).isEqualTo(kek)
        // Resealed: the primary opens the database on its own again, under a key of the port's.
        assertThat(source.load()).isEqualTo(kek)
    }

    /** The bridge's `KeyGenParameterSpec`, attribute included (`KeystoreBridge.kt:221-248` at v2.0.9; no StrongBox, which changes nothing here). */
    private fun creerCommeLaPasserelle() {
        val generateur = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generateur.init(
            KeyGenParameterSpec.Builder(
                ALIAS_DE_TEST,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .setUnlockedDeviceRequired(true)
                .build(),
        )
        generateur.generateKey()
    }

    private fun supprimerLaCle() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(ALIAS_DE_TEST)) ks.deleteEntry(ALIAS_DE_TEST)
    }

    private companion object {
        const val ALIAS_DE_TEST = "notes_tech.db.kek.test.passerelle"
        const val PREFS_DE_TEST = "notes_tech.kek.test.passerelle"
    }
}
