package com.filestech.notes_tech.data.prefs

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Le fichier de préférences **de la version Flutter**, lu et écrit dans son format d'origine.
 *
 * ## Pourquoi pas DataStore
 *
 * Parce qu'à la bascule, les réglages de l'utilisateur doivent survivre. Écrire ailleurs
 * obligerait à une migration — c'est-à-dire à un chemin de code qui ne s'exécute qu'une fois, chez
 * les autres, et qu'aucun test ne rejoue jamais dans les conditions réelles. Le portage garde donc
 * le même fichier, au même endroit, dans les mêmes formats. Cf. `docs/01-DECISIONS.md` D-015.
 *
 * ## ⚠️ Les formats ne sont PAS ceux qu'on devinerait
 *
 * Relevés dans `shared_preferences_android-2.4.26`, la version que `pubspec.lock` fige pour la
 * 2.0.3 — `android/src/main/java/.../LegacySharedPreferencesPlugin.java`. Ils ont été **lus dans
 * le greffon**, pas supposés :
 *
 * | Type Dart | Sur le disque | Ligne |
 * |---|---|---|
 * | `bool` | `putBoolean` | 76 |
 * | `String` | `putString` | 90 |
 * | **`int`** | **`putLong`** | **95** |
 * | `double` | `putString("VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu" + valeur)` | 101 |
 * | `List<String>` | `putString(LIST_IDENTIFIER + base64(sérialisation Java))` | 120 |
 *
 * 🔴 **`int` → `putLong` est le piège qui coûte le plus cher** : `getInt` sur une clé écrite par
 * Flutter lève `ClassCastException`. Le délai d'auto-verrouillage des coffres est un `int`. Une
 * lecture naïve ferait donc planter le premier démarrage après la bascule — sur le chemin des
 * coffres, et seulement chez les utilisateurs qui ont changé le réglage.
 *
 * ⚠️ **Le préfixe `flutter.`** est ajouté ici, une fois pour toutes. La clé passée en argument est
 * la clé Dart nue. L'oublier ne produit pas d'erreur : ça produit une lecture qui ne trouve rien et
 * retombe sur le défaut, ce qui ressemble à « l'utilisateur n'a jamais changé ce réglage ».
 */
@Singleton
class LegacyPreferences @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(FLUTTER_PREFS_FILE, Context.MODE_PRIVATE)

    fun string(key: String): String? = prefs.getString(prefixed(key), null)

    /** Whether [key] exists, whatever the type of its value. */
    fun contains(key: String): Boolean = prefs.contains(prefixed(key))

    /**
     * The value of [key] if it is a `String`; `null` when it is absent OR of another type.
     *
     * [string] throws `ClassCastException` on a value of another type. For a security setting that
     * is a crash on the launch path, every launch: the app lock reads through this one, and treats
     * "present but not a string" as unreadable — never as absent.
     */
    fun stringOrNull(key: String): String? = prefs.all[prefixed(key)] as? String

    /** A `bool` read the same way: `null` when absent or of another type, never an exception. */
    fun booleanOrNull(key: String): Boolean? = prefs.all[prefixed(key)] as? Boolean

    fun putString(key: String, value: String) = prefs.edit { putString(prefixed(key), value) }

    fun boolean(key: String, default: Boolean): Boolean = prefs.getBoolean(prefixed(key), default)

    fun putBoolean(key: String, value: Boolean) = prefs.edit { putBoolean(prefixed(key), value) }

    /**
     * Un entier Dart. **Stocké en `long`** — cf. l'avertissement de la classe.
     *
     * Passe par `all` plutôt que par `getLong` pour accepter aussi bien un `Long` (ce qu'écrit le
     * greffon) qu'un `Integer` (ce qu'écrirait du code Android natif mal avisé). Un `getLong` sur
     * un `Integer` lèverait, et cette lecture-ci sert un réglage de sécurité : elle doit dégrader
     * vers le défaut, jamais faire tomber l'application.
     */
    fun int(key: String, default: Int): Int = (prefs.all[prefixed(key)] as? Number)?.toInt() ?: default

    fun putInt(key: String, value: Int) = prefs.edit { putLong(prefixed(key), value.toLong()) }

    /**
     * A Dart `int` read in full — the plugin stores it as a `Long`, and [int] truncates to 32 bits.
     * Needed for monotonic timestamps, which pass `Int.MAX_VALUE` milliseconds after 25 days of uptime.
     */
    fun long(key: String, default: Long): Long = (prefs.all[prefixed(key)] as? Number)?.toLong() ?: default

    fun remove(key: String) = prefs.edit { remove(prefixed(key)) }

    /**
     * Several writes in ONE `commit()`, reporting whether the disk accepted them.
     *
     * ## Why this exists next to the `apply()`-based setters
     *
     * `apply()` returns at once and writes later. For a theme that is fine; for security state it is
     * not: an app lock enabled with `apply()` and a process killed a moment later would come back
     * without a lock, and a failed-PIN counter could lose increments to a well-timed force-stop —
     * handing out fresh free attempts. Callers that guard something use this, check the result, and
     * refuse to report success when it is `false`.
     *
     * The same three conventions as everything else in this class — `flutter.` prefix, `int` as
     * `Long` — are applied by [Edition], so the committed values stay readable by the plugin.
     */
    fun commit(changes: Edition.() -> Unit): Boolean {
        val editor = prefs.edit()
        Edition(editor).changes()
        return editor.commit()
    }

    /** The subset of `SharedPreferences.Editor` this file's format allows, keys prefixed. */
    inner class Edition internal constructor(private val editor: SharedPreferences.Editor) {
        fun putString(key: String, value: String) {
            editor.putString(prefixed(key), value)
        }

        fun putBoolean(key: String, value: Boolean) {
            editor.putBoolean(prefixed(key), value)
        }

        /** A Dart `int`, stored as a `Long` like the plugin does. */
        fun putLong(key: String, value: Long) {
            editor.putLong(prefixed(key), value)
        }

        fun remove(key: String) {
            editor.remove(prefixed(key))
        }
    }

    /** Les clés Dart nues qui commencent par [prefix]. Le préfixe `flutter.` est retiré. */
    fun keysStartingWith(prefix: String): List<String> {
        val complet = prefixed(prefix)
        return prefs.all.keys.filter { it.startsWith(complet) }.map { it.removePrefix(FLUTTER_KEY_PREFIX) }
    }

    /**
     * Efface **tout**, sauf les clés Dart nues listées dans [conserver].
     *
     * ## 🔴 Une liste blanche, jamais une liste noire
     *
     * L'appel sert au mode panique. Y énumérer ce qu'on veut effacer laisserait survivre toute clé
     * ajoutée plus tard sans qu'on y pense — c'est-à-dire exactement les clés qu'on n'a pas en tête
     * au moment d'écrire la liste. Ici, une préférence nouvelle est effacée par défaut, et la
     * conserver demande une décision.
     *
     * ⚠️ `commit()` et non `apply()` : la panique doit savoir si l'effacement a réellement abouti
     * avant d'annoncer quoi que ce soit. Un `apply()` rend la main tout de suite et écrit plus tard,
     * ce qui ferait annoncer un effacement pendant qu'il reste à faire.
     *
     * @return le nombre de clés effacées.
     * @throws IllegalStateException si le fichier de préférences a refusé l'écriture. ⚠️ Ne pas
     *   avaler : des traces d'usage survivraient à une panique qui se déclarerait complète.
     */
    fun clearAllExcept(conserver: Set<String>): Int {
        val preservees = conserver.map(::prefixed).toSet()
        val aEffacer = prefs.all.keys - preservees
        if (aEffacer.isEmpty()) return 0
        val edition = prefs.edit()
        aEffacer.forEach(edition::remove)
        if (!edition.commit()) {
            error("effacement des preferences refuse (${aEffacer.size} cles)")
        }
        return aEffacer.size
    }

    /**
     * Une liste de chaînes, dans l'un des **trois** états qu'on peut trouver sur le disque.
     *
     * L'application a plus de deux ans et le greffon a changé de format en route. Une installation
     * ancienne peut porter n'importe lequel des trois, et n'en gérer que le dernier reviendrait à
     * perdre silencieusement la valeur de ceux qui ont l'application depuis le début.
     *
     * Rend une liste vide sur données illisibles : les deux usages de cette fonction sont des
     * indications d'interface, jamais des décisions de sécurité. Une bannière absente vaut mieux
     * qu'un démarrage qui échoue.
     */
    fun stringList(key: String): List<String> {
        val complet = prefixed(key)
        return when (val brut = runCatching { prefs.all[complet] }.getOrNull()) {
            is String -> decodeStringList(brut)
            // Les toutes premières versions du greffon utilisaient `putStringSet`. L'ordre est
            // alors perdu — c'est le format qui l'a perdu, pas cette lecture.
            is Set<*> -> brut.filterIsInstance<String>()
            else -> emptyList()
        }
    }

    /**
     * Écrit une liste **dans le format qu'écrit le greffon**, sérialisation Java comprise.
     *
     * Réécrire en JSON serait plus propre à lire, et c'est exactement pourquoi il ne faut pas :
     * deux formats coexisteraient sur le disque des utilisateurs, l'un écrit avant la bascule et
     * l'autre après, sans que rien ne distingue les deux périodes.
     */
    fun putStringList(key: String, value: List<String>) {
        val encode = ByteArrayOutputStream().use { octets ->
            ObjectOutputStream(octets).use { it.writeObject(ArrayList(value)) }
            Base64.encodeToString(octets.toByteArray(), Base64.DEFAULT)
        }
        prefs.edit { putString(prefixed(key), LIST_IDENTIFIER + encode) }
    }

    /**
     * Émet à chaque écriture dans le fichier.
     *
     * `conflate` parce que l'abonné ne relit qu'un état courant : rater une notification
     * intermédiaire ne change rien, et empiler des notifications qui disent toutes « relis » n'a
     * aucune valeur.
     *
     * ⚠️ La référence à l'écouteur est retenue dans une `val` locale. `SharedPreferences` ne garde
     * ses écouteurs qu'en références **faibles** : un écouteur qui n'existe qu'en argument est
     * collecté par le ramasse-miettes à un moment imprévisible, et le flux se tait sans erreur.
     */
    fun changes(): Flow<Unit> = callbackFlow {
        val cible = prefs
        val ecouteur = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        cible.registerOnSharedPreferenceChangeListener(ecouteur)
        trySend(Unit)
        awaitClose { cible.unregisterOnSharedPreferenceChangeListener(ecouteur) }
    }.conflate()

    private fun prefixed(key: String) = "$FLUTTER_KEY_PREFIX$key"

    private fun decodeStringList(brut: String): List<String> = when {
        !brut.startsWith(LIST_IDENTIFIER) -> emptyList()

        // Le format JSON se distingue par un `!` de plus — un caractère que le base64 du format
        // précédent ne peut pas produire, ce qui rend les deux discernables sans ambiguïté.
        brut.startsWith(JSON_LIST_IDENTIFIER) ->
            runCatching { org.json.JSONArray(brut.removePrefix(JSON_LIST_IDENTIFIER)) }
                .map { tableau -> (0 until tableau.length()).mapNotNull { tableau.optString(it) } }
                .getOrDefault(emptyList())

        else -> runCatching {
            val octets = Base64.decode(brut.removePrefix(LIST_IDENTIFIER), Base64.DEFAULT)
            ListeDeChainesSeulement(ByteArrayInputStream(octets)).use { flux ->
                (flux.readObject() as List<*>).filterIsInstance<String>()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Un `ObjectInputStream` qui **refuse tout ce qui n'est pas une liste de chaînes**.
     *
     * ## 🔴 Ce n'est pas de la prudence décorative
     *
     * Désérialiser du Java, c'est laisser les données décider des classes à instancier. Le fichier
     * de préférences appartient au bac à sable de l'application, mais il est restauré par les
     * sauvegardes, copié par les outils de migration de téléphone, et lisible sur un appareil
     * rooté. Une valeur fabriquée y suffirait à déclencher une chaîne de gadgets au démarrage.
     *
     * La liste blanche est reprise à l'identique de `StringListObjectInputStream` du greffon : ce
     * n'est pas une invention locale, et s'en écarter « pour être plus permissif » serait rouvrir
     * un trou que les auteurs du greffon ont fermé délibérément.
     */
    private class ListeDeChainesSeulement(input: InputStream) : ObjectInputStream(input) {
        override fun resolveClass(desc: ObjectStreamClass?): Class<*> {
            val nom = desc?.name
            if (nom != null && nom !in AUTORISEES) throw ClassNotFoundException(nom)
            return super.resolveClass(desc)
        }

        private companion object {
            val AUTORISEES = setOf(
                "java.util.Arrays\$ArrayList",
                "java.util.ArrayList",
                "java.lang.String",
                "[Ljava.lang.String;",
            )
        }
    }

    private companion object {
        /** Le fichier qu'écrit le greffon `shared_preferences` sur Android. */
        const val FLUTTER_PREFS_FILE = "FlutterSharedPreferences"

        /** `SharedPreferences.getInstance()` préfixe toutes ses clés — `shared_preferences_legacy.dart:22`. */
        const val FLUTTER_KEY_PREFIX = "flutter."

        /** `LegacySharedPreferencesPlugin.java:36`. Base64 de « This is the prefix for a list. ». */
        const val LIST_IDENTIFIER = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu"

        /** Idem, ligne 39. Le `!` ne peut pas apparaître dans du base64 — c'est le point. */
        const val JSON_LIST_IDENTIFIER = LIST_IDENTIFIER + "!"
    }
}
