package com.filestech.notes_tech.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.domain.model.NoteSortMode
import com.filestech.notes_tech.security.vault.VaultParams
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Les réglages de quelqu'un survivent-ils à la bascule 3.0.0 ?**
 *
 * Ligne `settings_service.dart` de `docs/05-PARITE.md`, critère écrit : *« lire les clés `flutter.*`
 * existantes »*.
 *
 * ## 🔴🔴 Ce qui se joue, et pourquoi ça ne se voit pas
 *
 * `shared_preferences` n'écrit pas où l'on croit : le fichier est `FlutterSharedPreferences.xml`,
 * chaque clé porte le préfixe **`flutter.`**, et un `int` Dart est stocké en **`Long`**. Trois
 * conventions, et il suffit d'en manquer une pour que l'application neuve ne trouve **rien** —
 * auquel cas elle ne se plaint pas : elle applique ses valeurs par défaut.
 *
 * L'utilisateur retrouverait donc, après mise à jour, sa langue et son thème remis à zéro. Et
 * surtout **le délai de verrouillage automatique des coffres**, qui est un réglage de sécurité :
 * quelqu'un qui l'avait mis à une minute se retrouverait à quinze sans en être averti.
 *
 * ## Ce que ce fichier ajoute
 *
 * `AppSettings` et `LegacyPreferences` n'avaient **aucun test à eux** : ils n'apparaissaient que
 * comme collaborateurs d'autres cas, qui les traversent sans jamais vérifier ce qu'ils lisent. Ici,
 * les préférences sont écrites **exactement comme le greffon les écrit** — fichier, préfixe et types
 * compris — et c'est `AppSettings` qui les relit.
 *
 * ⚠️ Le contrôle négatif est le cœur du fichier : sans lui, un test qui écrit et relit par le même
 * chemin passe même si le préfixe est faux des deux côtés.
 */
@RunWith(AndroidJUnit4::class)
class ReglagesHeritesTest {

    private lateinit var context: Context
    private lateinit var fichier: SharedPreferences
    private lateinit var reglages: AppSettings
    private var sauvegarde: Map<String, Any?> = emptyMap()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        fichier = context.getSharedPreferences(FICHIER_DU_GREFFON, Context.MODE_PRIVATE)
        reglages = AppSettings(LegacyPreferences(context))
        // ⚠️ Ce fichier est celui de la vraie application sur l'appareil : on rend ce qu'on emprunte.
        sauvegarde = fichier.all.toMap()
        fichier.edit().clear().commit()
    }

    @After
    fun tearDown() {
        val edition = fichier.edit().clear()
        sauvegarde.forEach { (cle, valeur) ->
            when (valeur) {
                is String -> edition.putString(cle, valeur)
                is Boolean -> edition.putBoolean(cle, valeur)
                is Long -> edition.putLong(cle, valeur)
                is Int -> edition.putInt(cle, valeur)
                is Float -> edition.putFloat(cle, valeur)
                else -> Unit
            }
        }
        edition.commit()
    }

    // ── Le sens qui décide de la bascule ─────────────────────────────────────────────────────────

    /**
     * 🔴🔴 **Les cinq réglages écrits comme le greffon les écrit sont relus.**
     *
     * Rien n'est écrit par le portage ici : les valeurs sont posées à la main dans le fichier du
     * greffon, avec le préfixe `flutter.` et les types du greffon — `String`, `Boolean`, et **`Long`
     * pour un entier**. C'est l'état exact que laisse une installation de la 2.0.x.
     */
    @Test
    fun les_cinq_reglages_ecrits_par_le_GREFFON_sont_relus_par_le_portage() {
        fichier.edit()
            .putString("flutter.theme_mode", "dark")
            .putString("flutter.app_locale", "en")
            .putString("flutter.note_sort_mode", "titleAsc")
            .putBoolean("flutter.secure_window_enabled", false)
            .putLong("flutter.vault_auto_lock_minutes", 1L)
            .commit()

        assertThat(reglages.themeNow()).isEqualTo(ThemePreference.DARK)
        assertThat(reglages.localeNow()).isEqualTo(LocalePreference.ENGLISH)
        assertThat(reglages.sortNow()).isEqualTo(NoteSortMode.TITLE_ASC)
        assertThat(reglages.secureWindowNow()).isFalse()
        assertThat(reglages.vaultAutoLockMinutesNow()).isEqualTo(1)
    }

    /**
     * ⚠️⚠️ **Le contrôle négatif, et c'est lui qui donne son sens au cas précédent.**
     *
     * Les mêmes clés, aux mêmes valeurs, **sans le préfixe `flutter.`**. Rien ne doit être vu : le
     * portage doit retomber sur ses défauts. Sans ce cas, un portage qui aurait oublié le préfixe
     * des deux côtés — en lecture comme en écriture — passerait le cas précédent sans jamais voir
     * une seule préférence réelle.
     *
     * ⚠️ Les défauts attendus sont eux-mêmes des affirmations : `SYSTEM`, fenêtre sécurisée
     * **activée**, et le délai de `VaultParams`. Un portage qui rendrait n'importe quoi tomberait.
     */
    @Test
    fun les_memes_cles_SANS_le_prefixe_flutter_ne_sont_pas_vues() {
        fichier.edit()
            .putString("theme_mode", "dark")
            .putString("app_locale", "en")
            .putString("note_sort_mode", "titleAsc")
            .putBoolean("secure_window_enabled", false)
            .putLong("vault_auto_lock_minutes", 1L)
            .commit()

        assertThat(reglages.themeNow()).isEqualTo(ThemePreference.SYSTEM)
        assertThat(reglages.localeNow()).isEqualTo(LocalePreference.SYSTEM)
        assertThat(reglages.secureWindowNow()).isTrue()
        assertThat(reglages.vaultAutoLockMinutesNow())
            .isEqualTo(VaultParams.DEFAULT_AUTO_LOCK_MINUTES)
    }

    /**
     * 🔴 **Un entier écrit en `Int` et non en `Long` doit être lu quand même.**
     *
     * Le greffon écrit un `Long` ; du code Android natif écrirait un `Integer`. Un `getLong` sur un
     * `Integer` **lève** — sur un réglage de sécurité, ce serait une exception au démarrage plutôt
     * qu'une valeur. `LegacyPreferences` lit donc un `Number` et convertit.
     *
     * ⚠️ Le second volet est le pendant : un type qui n'est pas un nombre du tout doit **dégrader
     * vers le défaut**, jamais faire tomber l'application.
     */
    @Test
    fun le_delai_de_verrouillage_se_lit_en_Int_comme_en_Long_et_degrade_sur_le_reste() {
        fichier.edit().putInt("flutter.vault_auto_lock_minutes", 5).commit()
        assertThat(reglages.vaultAutoLockMinutesNow()).isEqualTo(5)

        fichier.edit().putLong("flutter.vault_auto_lock_minutes", 30L).commit()
        assertThat(reglages.vaultAutoLockMinutesNow()).isEqualTo(30)

        fichier.edit().putString("flutter.vault_auto_lock_minutes", "trente").commit()
        assertThat(reglages.vaultAutoLockMinutesNow())
            .isEqualTo(VaultParams.DEFAULT_AUTO_LOCK_MINUTES)
    }

    /**
     * 🔴🔴 **Les six clés de tri, transcrites du Dart et non lues depuis le portage.**
     *
     * Le commentaire du Dart dit pourquoi ces clés sont écrites à la main plutôt que dérivées de
     * `mode.name` : la release passe par `--obfuscate`, donc un nom d'enum n'est pas un contrat de
     * sérialisation. Le portage a le même `when` exhaustif — et rien ne vérifiait que les douze
     * chaînes concordent.
     *
     * ⚠️ Une seule qui divergerait ne casserait rien : le tri de l'utilisateur retomberait
     * silencieusement sur le défaut, et il croirait l'avoir mal réglé.
     */
    @Test
    fun les_SIX_cles_de_tri_sont_celles_du_Dart() {
        val duDart = mapOf(
            "updatedDesc" to NoteSortMode.UPDATED_DESC,
            "updatedAsc" to NoteSortMode.UPDATED_ASC,
            "createdDesc" to NoteSortMode.CREATED_DESC,
            "createdAsc" to NoteSortMode.CREATED_ASC,
            "titleAsc" to NoteSortMode.TITLE_ASC,
            "titleDesc" to NoteSortMode.TITLE_DESC,
        )
        // Le témoin : la table couvre bien TOUS les modes. Un mode ajouté sans clé décidée doit
        // faire tomber ce cas, pas passer inaperçu.
        assertThat(duDart.values).containsExactlyElementsIn(NoteSortMode.entries)

        duDart.forEach { (cle, attendu) ->
            fichier.edit().putString("flutter.note_sort_mode", cle).commit()
            assertThat(reglages.sortNow()).isEqualTo(attendu)
        }

        // ⚠️ Et le repli du Dart, qui est `updatedDesc` : une valeur inconnue ne doit pas rendre
        // n'importe quel mode.
        fichier.edit().putString("flutter.note_sort_mode", "title_asc").commit()
        assertThat(reglages.sortNow()).isEqualTo(NoteSortMode.UPDATED_DESC)
    }

    /**
     * 🔴 Le sens inverse : ce que le portage écrit, une 2.0.x réinstallée doit le relire.
     *
     * Une bascule n'est pas toujours définitive. Le contrôle porte sur le **fichier brut** : bonne
     * clé préfixée, et un entier stocké en `Long` comme le greffon l'attend.
     */
    @Test
    fun ce_que_le_portage_ecrit_est_au_FORMAT_du_greffon() {
        reglages.setTheme(ThemePreference.LIGHT)
        reglages.setVaultAutoLockMinutes(3)
        reglages.setSecureWindow(false)

        assertThat(fichier.getString("flutter.theme_mode", null)).isEqualTo("light")
        assertThat(fichier.all["flutter.vault_auto_lock_minutes"]).isEqualTo(3L)
        assertThat(fichier.getBoolean("flutter.secure_window_enabled", true)).isFalse()
    }

    /**
     * ⚠️ `keysStartingWith` rend des clés **nues**, et c'est le mode panique qui en dépend.
     *
     * Il s'en sert pour retrouver les `vault_wipe_pending_*`. Une clé rendue avec son préfixe serait
     * ensuite re-préfixée à la suppression — `flutter.flutter.vault_wipe_pending_…` — et
     * l'effacement ne trouverait rien, en silence.
     */
    @Test
    fun les_cles_rendues_sont_NUES_et_la_suppression_les_retrouve() {
        val prefs = LegacyPreferences(context)
        fichier.edit()
            .putBoolean("flutter.vault_wipe_pending_aaa", true)
            .putBoolean("flutter.vault_wipe_pending_bbb", true)
            .putBoolean("flutter.theme_mode_leurre", true)
            .commit()

        val trouvees = prefs.keysStartingWith("vault_wipe_pending_")

        assertThat(trouvees).containsExactly("vault_wipe_pending_aaa", "vault_wipe_pending_bbb")
        trouvees.forEach(prefs::remove)
        assertThat(prefs.keysStartingWith("vault_wipe_pending_")).isEmpty()
        // Le témoin : la suppression n'a pas emporté ce qui ne la regardait pas.
        assertThat(fichier.contains("flutter.theme_mode_leurre")).isTrue()
    }

    private companion object {
        /** Transcrit du greffon `shared_preferences`, pas lu depuis `LegacyPreferences`. */
        const val FICHIER_DU_GREFFON = "FlutterSharedPreferences"
    }
}
