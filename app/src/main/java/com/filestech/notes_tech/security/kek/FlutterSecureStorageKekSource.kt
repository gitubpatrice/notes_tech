package com.filestech.notes_tech.security.kek

import android.content.Context
import android.util.Base64
import com.filestech.notes_tech.core.crypto.SecretBytes
import com.filestech.notes_tech.core.crypto.wipe
import java.security.KeyStore
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Couche ② — lit la KEK **directement dans le stockage de `flutter_secure_storage`**.
 *
 * Pour l'utilisateur qui saute la release passerelle 2.0.4 et passe de la version Flutter à la
 * version Kotlin d'un coup. Cf. `docs/03-KEK-ACQUISITION.md` §2.
 *
 * ## Ce que ce fichier est vraiment
 *
 * La cryptographie d'une bibliothèque tierce, rejouée à l'envers. Ce n'est pas une conception, c'est
 * une **transcription** : chaque constante ci-dessous a été relevée dans les sources Java de
 * `flutter_secure_storage 10.3.1`, à l'emplacement cité, et non déduite d'une documentation.
 *
 * C'est aussi pourquoi cette couche est un **secours** et non le chemin nominal : rien ici ne
 * garantit qu'une version future de la bibliothèque garde ce format. La couche ① existe précisément
 * pour que ce code ne soit presque jamais exécuté.
 *
 * ## ⚠️ Le piège que la lecture des sources a évité
 *
 * L'enveloppe RSA-OAEP utilise **SHA-256 comme condensat principal et SHA-1 pour MGF1**
 * (`KeyCipherImplementationRSAOAEP.java:53`). Écrire `MGF1ParameterSpec.SHA256`, ce que tout le monde
 * ferait de mémoire, produit une `BadPaddingException` — et un `catch` distrait la transformerait en
 * « aucune clé », donc en génération d'une clé neuve, donc en perte de toutes les notes.
 *
 * ## Deux comportements à ne jamais confondre
 *
 * | Situation | Réponse |
 * |---|---|
 * | aucune valeur dans les préférences | `null` — cette source ne détient rien |
 * | valeur présente mais illisible, Keystore muet, algorithmes inattendus | [KekFailure.SourceUnavailable] |
 *
 * La seconde ligne est celle qui compte : **une lecture qui échoue ne prouve pas qu'il n'y a rien.**
 * C'est la faute qui a détruit des données dans Agenda Tech, citée dans [KekSource].
 *
 * ## Cette source n'écrit rien, jamais
 *
 * Elle n'implémente pas [WritableKekSource] et c'est délibéré : écrire dans le stockage d'une autre
 * bibliothèque signifierait reproduire aussi son chemin d'écriture, avec le risque de corrompre ce
 * que la version Flutter relira si l'utilisateur revient en arrière. On lit, on recopie dans la
 * couche ①, on ne touche pas à l'original.
 */
class FlutterSecureStorageKekSource(
    private val context: Context,
    private val keyAliasBase: String = PUBLISHED_APPLICATION_ID,
) : KekSource {

    override val name: String = "flutter_secure_storage"

    override fun load(): ByteArray? {
        // Lecture atomique : deux `getString` successifs pourraient tomber de part et d'autre d'une
        // écriture concurrente de la version Flutter, si elle tourne encore. L'ouverture du fichier
        // est dans le même `runCatching` : elle peut échouer sur un stockage abîmé, et cet échec-là
        // ne prouve pas davantage l'absence de clé.
        val snapshot = runCatching {
            context.getSharedPreferences(DATA_PREFS, Context.MODE_PRIVATE).all
        }.getOrElse { cause ->
            throw KekFailure.SourceUnavailable(name, cause)
        }

        val encodedValue = readStoredValue(snapshot) ?: return null

        requireExpectedAlgorithms()

        return decryptHexEncodedKek(encodedValue, unwrapStorageKey())
    }

    /**
     * Distingue **trois** états de la valeur stockée, là où un `as? String ?: return null` n'en
     * distinguait que deux.
     *
     * 🔴 C'est exactement le motif que ce fichier existe pour éviter, et il s'y était glissé.
     *
     * `snapshot[VALUE_KEY] as? String ?: return null` rend `null` dans **deux** situations très
     * différentes : la clé est absente — il n'y a effectivement rien — ou bien elle est présente avec
     * un type inattendu. Le second cas est une préférence abîmée, pas une absence, et le traiter
     * comme une absence conduit `KekRepository` à conclure « aucune clé nulle part », puis, si la
     * base n'existait pas encore, à en générer une neuve.
     *
     * Le cas est rare — les préférences sont typées, et seule une corruption ou une écriture par un
     * autre programme le produirait. Mais c'est précisément la forme du défaut que tout ce fichier
     * cherche à rendre impossible, et le tolérer ici au motif qu'il est improbable reviendrait à
     * choisir lequel des trois états on veut bien distinguer.
     *
     * Relevé en relisant ce fichier avec le motif en tête, après l'avoir écrit.
     */
    private fun readStoredValue(snapshot: Map<String, *>): String? {
        if (!snapshot.containsKey(VALUE_KEY)) return null
        return snapshot[VALUE_KEY] as? String
            ?: throw KekFailure.SourceUnavailable(
                name,
                IllegalStateException("valeur presente mais d'un type inattendu"),
            )
    }

    /**
     * Refuse de deviner quand la bibliothèque n'a pas utilisé les algorithmes attendus.
     *
     * La bibliothèque **enregistre elle-même** ce qu'elle a employé, dans deux préférences
     * (`StorageCipherFactory.java:13-15`). C'est une chance : plutôt que d'essayer une combinaison,
     * puis une autre, jusqu'à ce que l'une déchiffre, on lit ce qui a servi et on s'arrête si ce
     * n'est pas ce qu'on sait lire.
     *
     * Deux cas mènent ici, et aucun ne doit être traité comme « pas de clé » :
     *
     * - **marqueurs absents** — données écrites par une 9.x, chiffrées en `AES_CBC_PKCS7Padding` +
     *   `RSA_ECB_PKCS1Padding` (`StorageCipherFactory.java:30`). Ne devrait pas exister, Notes Tech
     *   étant sur la 10.x depuis la 0.9.10, mais l'appareil d'un utilisateur ne connaît pas nos
     *   suppositions ;
     * - **marqueurs différents** — version future, ou configuration modifiée.
     *
     * Dans les deux cas, la couche ③ prend le relais : elle refuse proprement et n'écrit rien.
     * Essayer de déchiffrer à l'aveugle donnerait des octets arbitraires qui passeraient peut-être
     * le contrôle de longueur, et ouvriraient une base avec une clé fausse.
     */
    private fun requireExpectedAlgorithms() {
        // 🔴🔴 Ces deux marqueurs ne sont PAS dans le fichier de donnees.
        //
        // `StorageCipherFactory` les ecrit dans un fichier de CONFIGURATION distinct, nomme
        // `FlutterSecureStorageConfiguration:` + le nom du fichier de donnees. Les chercher dans
        // `snapshot` -- ce que faisait ce code -- les rendait TOUJOURS nuls, donc toujours
        // differents des attendus, donc `SourceUnavailable` a chaque fois.
        //
        // Consequence mesuree le 2026-08-20 sur le S9 : une bascule 2.0.3 -> 3.0.0 echouait
        // systematiquement, alors que la valeur, la cle enveloppee et les deux algorithmes etaient
        // tous corrects sur l'appareil. La couche ② n'a jamais pu fonctionner.
        //
        // ⚠️ Les 12 cas JVM ne l'ont pas vu : ils ecrivaient les marqueurs dans le meme fichier
        // que la valeur. Ils rejouaient une convention supposee, pas celle de la bibliotheque.
        val config = runCatching {
            context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE).all
        }.getOrElse { cause ->
            // Meme regle que pour les donnees : une lecture qui echoue ne prouve pas une absence.
            throw KekFailure.SourceUnavailable(name, cause)
        }
        val keyAlgorithm = config[ALGORITHM_KEY_MARKER] as? String
        val storageAlgorithm = config[ALGORITHM_STORAGE_MARKER] as? String
        if (keyAlgorithm != EXPECTED_KEY_ALGORITHM || storageAlgorithm != EXPECTED_STORAGE_ALGORITHM) {
            throw KekFailure.SourceUnavailable(
                name,
                IllegalStateException(
                    "combinaison d'algorithmes non prise en charge : cle=$keyAlgorithm stockage=$storageAlgorithm",
                ),
            )
        }
    }

    /**
     * Détruit le stockage de `flutter_secure_storage` : la valeur scellée, la clé AES enveloppée,
     * et la clé RSA de l'`AndroidKeyStore` qui l'enveloppait.
     *
     * ## 🔴 L'exception à « cette source n'écrit rien, jamais »
     *
     * La règle du reste de ce fichier — on lit, on recopie dans la couche ①, on ne touche pas à
     * l'original — protège l'utilisateur qui reviendrait à la version Flutter. Le mode panique a
     * exactement le but opposé : **il n'y a plus de retour en arrière à préserver**.
     *
     * Et c'est bien ici que la panique se joue pour l'utilisateur qui migre. Sa KEK vit encore dans
     * ce stockage-là ; ne détruire que la couche ① laisserait la base parfaitement déchiffrable par
     * quiconque réinstalle la version Flutter. La panique aurait paru fonctionner sans rien
     * protéger — le pire des deux mondes, puisque l'utilisateur, lui, la croirait faite.
     *
     * Les deux fichiers de préférences sont vidés **entièrement** : ils n'appartiennent qu'à cette
     * bibliothèque, qui n'y range rien d'autre que ses secrets.
     */
    override fun destroy() {
        // 🔴🔴 **Chaque geste est tenté, même si le précédent a échoué.**
        //
        // La version précédente enveloppait les quatre gestes dans un `try` unique : si la
        // suppression du premier fichier levait, ni les deux autres, **ni l'alias RSA du
        // Keystore** n'étaient tentés. La valeur scellée, la clé AES enveloppée et la clé qui la
        // déballe restaient alors **ensemble sur l'appareil** — c'est-à-dire que la KEK restait
        // récupérable, après un mode panique.
        //
        // Une destruction d'urgence est un **meilleur effort** : on tente tout, puis on dit si
        // quelque chose a manqué. S'arrêter au premier obstacle est le seul comportement qui ne
        // convienne pas. Relevé par une relecture externe le 2026-08-20.
        //
        // ⚠️ L'échec n'est pas avalé pour autant : le premier est conservé et relancé à la fin,
        // pour que l'appelant sache que la panique est incomplète.
        var premierEchec: Exception? = null
        fun tenter(geste: () -> Unit) {
            try {
                geste()
            } catch (e: Exception) {
                if (premierEchec == null) premierEchec = e
            }
        }

        run {
            // Les données d'abord, la clé qui les ouvre ensuite — même raison que pour la couche ① :
            // une interruption entre les deux doit laisser un état cohérent, jamais un scellé
            // orphelin qui ressemble à un secret protégé.
            // ⚠️ SUPPRIMER le fichier, pas le vider. `getSharedPreferences(...).edit().clear()`
            // **crée** le fichier s'il n'existe pas : sur un appareil qui n'a jamais vu la version
            // Flutter, la panique faisait apparaître deux fichiers nommés « SecureStorage » —
            // vides, mais créés par le geste censé tout effacer. Mesuré sur le S9 le 2026-08-14.
            // 🔴 **CONFIG_PREFS en fait partie**, et il manquait.
            //
            // Il ne porte pas de clé — seulement les noms d'algorithmes employés — mais le contrat
            // de cette méthode est d'effacer **tout** le stockage de la bibliothèque, et le mode
            // panique n'a pas vocation à laisser une trace disant quel format était en place.
            //
            // ⚠⚠ Le contrôle d'après-panique ne pouvait pas le voir : l'absence de `DATA_PREFS`
            // suffit à faire rendre `null` à `load()`, donc la vérification passait. Et le fixture
            // des tests, lui, nettoyait bien les trois fichiers — *le test était plus propre que la
            // production, ce qui rendait l'oubli invisible des deux côtés.* Relevé par une relecture
            // externe le 2026-08-20.
            for (fichier in listOf(DATA_PREFS, KEY_STORAGE_PREFS, CONFIG_PREFS)) {
                tenter { supprimerLeFichierDePreferences(context, fichier) }
            }
            tenter {
                val alias = "$keyAliasBase$KEY_ALIAS_SUFFIX"
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
            }
        }
        premierEchec?.let { throw KekFailure.SourceUnavailable(name, it) }
    }

    /**
     * Déballe la clé AES-128 de la bibliothèque, scellée par une clé RSA de l'`AndroidKeyStore`.
     *
     * ⚠️ **16 octets, pas 32** (`StorageCipherImplementationGCM.java:18`). La taille surprend sur un
     * composant qui se présente comme du stockage sécurisé, mais c'est ce qu'il fait, et la lire
     * autrement ne déchiffrerait rien.
     */
    private fun unwrapStorageKey(): SecretKey {
        val alias = "$keyAliasBase$KEY_ALIAS_SUFFIX"

        // ⚠️ L'ouverture du fichier est DANS le `runCatching`, comme dans `load`. Elle en était
        // dehors, et c'était une asymétrie : sur un stockage verrouillé, l'exception brute serait
        // sortie sans passer par `SourceUnavailable`, donc sans la nouvelle tentative de
        // `KekRepository`. Relevé par deux relectures externes (2026-08-13).
        val wrapped = runCatching {
            context.getSharedPreferences(KEY_STORAGE_PREFS, Context.MODE_PRIVATE)
                .getString(WRAPPED_KEY, null)
        }.getOrElse { cause -> throw KekFailure.SourceUnavailable(name, cause) }
            ?: throw KekFailure.SourceUnavailable(
                name,
                IllegalStateException("valeur presente mais cle AES enveloppee absente"),
            )

        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val privateKey = checkNotNull(keyStore.getKey(alias, null)) { "alias absent du Keystore" }

            val cipher = Cipher.getInstance(RSA_TRANSFORMATION, KEYSTORE_RSA_PROVIDER)
            cipher.init(Cipher.UNWRAP_MODE, privateKey, oaepParameters())
            val unwrapped = cipher.unwrap(Base64.decode(wrapped, Base64.DEFAULT), AES, Cipher.SECRET_KEY)

            // ⚠️ Le `SecretKey` est rendu tel quel, sans repasser par `.encoded`.
            //
            // `.encoded` matérialise les octets de la clé, qu'il faut ensuite recopier dans un
            // `SecretKeySpec` pour s'en servir : deux copies de matériel secret pour un résultat
            // identique. Aucune n'est effaçable de façon fiable une fois passée au fournisseur.
            //
            // Relevé par une relecture externe (GPT-5.5, 2026-08-13).
            val secretKey = checkNotNull(unwrapped as? SecretKey) { "cle deballee d'un type inattendu" }

            // La taille est **annoncée** par la bibliothèque (16 octets) ; la vérifier la rend
            // constatée. Une divergence signalerait un format qu'on ne sait pas lire, pas une clé
            // à essayer quand même.
            //
            // 🔴🔴 **La copie rendue par `.encoded` est EFFACÉE**, et c'est tout l'objet de ces
            // quatre lignes. Le commentaire juste au-dessus explique qu'il ne faut pas matérialiser
            // la clé — et la version précédente écrivait `secretKey.encoded?.size` quatre lignes plus
            // bas, laissant 16 octets de clé AES en clair sur le tas jusqu'au passage du
            // ramasse-miettes, sans aucune référence pour les effacer. Relevé par une relecture
            // externe le 2026-08-20 — la même relecture qui avait fait écrire le commentaire.
            //
            // ⚠️ `getEncoded()` rend une **copie défensive** à chaque appel : effacer la nôtre ne
            // touche pas la clé du fournisseur, qui reste utilisable. C'est bien une copie de trop
            // qu'on supprime, pas la clé elle-même.
            val brut = secretKey.encoded
            val size = brut?.size
            brut?.fill(0)
            check(size == null || size == AES_KEY_SIZE) { "cle AES de taille inattendue : $size" }
            secretKey
        } catch (cause: Exception) {
            // ⚠️ Aucun `return null` ici, et c'est le point le plus important du fichier. Un Keystore
            // momentanement indisponible, un appareil verrouille, une cle invalidee : rien de tout
            // cela ne prouve l'absence de KEK, et le traiter comme une absence conduirait a en
            // generer une neuve — donc a condamner les notes que la vraie cle protegeait.
            throw KekFailure.SourceUnavailable(name, cause)
        }
    }

    /**
     * ⚠️ `MGF1ParameterSpec.SHA1` avec un condensat principal en SHA-256 — relevé dans
     * `KeyCipherImplementationRSAOAEP.java:53`, **pas écrit de mémoire**.
     *
     * L'asymétrie est celle de l'`AndroidKeyStore`, pas un choix de la bibliothèque. Elle ne se
     * devine pas, et s'en écarter rend le déballage impossible.
     */
    private fun oaepParameters(): OAEPParameterSpec =
        OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

    /**
     * Déchiffre l'enveloppe `IV (12) ‖ AES-GCM(ciphertext ‖ tag 128 bits)`
     * (`StorageCipherImplementationGCM.java:66-96`), puis décode les 64 caractères hexadécimaux que
     * Notes Tech y a écrits (`vault_service.dart:104`).
     */
    /**
     * ⚠⚠ **La clé AES n'est PAS détruite à la sortie, et ce n'est pas un oubli.**
     *
     * Trois relectures externes l'ont signalé le 2026-08-20 : le clair est bien effacé
     * (`clearText.wipe()`), la clé qui l'a produit ne l'est pas. Le constat est **juste sur le
     * principe**. Il est **inapplicable ici**, et c'est mesuré, pas supposé :
     *
     * ```
     * MESURE destroy() : levee=DestroyFailedException isDestroyed=false
     * ```
     *
     * (S9, Android 10 — `DestructionDeCleAesTest`.) `SecretKey` hérite de `Destroyable`, dont
     * l'implémentation **par défaut lève** au lieu d'effacer. Appeler `aesKey.destroy()` ajouterait
     * un `try`/`catch` qui n'effacerait rien — *du code qui donne l'impression d'une protection sans
     * en offrir aucune, ce qui est pire que son absence, parce qu'on cesse d'y penser.*
     *
     * 🔴 L'atteindre par réflexion, sur le champ privé de `SecretKeySpec`, a été écarté : cela
     * dépendrait d'un détail d'implémentation, et les restrictions d'accès non-SDK d'Android le
     * casseraient sans prévenir.
     *
     * Ce qui est fait à la place : la **copie** rendue par `.encoded` est effacée (voir
     * `unwrapStorageKey`), et la clé elle-même n'est ni conservée dans un champ, ni rendue à un
     * appelant — sa portée s'arrête à cette fonction.
     */
    private fun decryptHexEncodedKek(encodedValue: String, aesKey: SecretKey): ByteArray {
        val clearText = decryptEnvelope(encodedValue, aesKey)
        return try {
            // ⚠️ **Aucune `String` n'est construite ici**, et c'est délibéré.
            //
            // Une `String` Java est immuable : on ne peut pas l'effacer. En construire une à partir
            // du clair laisserait les 64 caractères hexadécimaux de la clé maître lisibles dans le
            // tas jusqu'au prochain ramasse-miettes — exactement ce qu'un vidage mémoire ramasse.
            //
            // Relevé indépendamment par les deux relectures externes du 2026-08-13.
            parseHexKek(clearText)
        } finally {
            clearText.wipe()
        }
    }

    /**
     * Ouvre l'enveloppe `IV (12) ‖ AES-GCM(ciphertext ‖ tag 128 bits)`
     * (`StorageCipherImplementationGCM.java:66-96`).
     */
    private fun decryptEnvelope(encodedValue: String, aesKey: SecretKey): ByteArray {
        val envelope = try {
            Base64.decode(encodedValue, Base64.DEFAULT)
        } catch (cause: IllegalArgumentException) {
            throw KekFailure.MalformedKey(name, cause)
        }
        if (envelope.size <= IV_SIZE) throw KekFailure.MalformedKey(name, null)

        return try {
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                aesKey,
                GCMParameterSpec(GCM_TAG_BITS, envelope, 0, IV_SIZE),
            )
            cipher.doFinal(envelope, IV_SIZE, envelope.size - IV_SIZE)
        } catch (cause: Exception) {
            // Le tag GCM n'a pas validé, ou le Keystore a rendu une clé qui ne convient pas. On ne
            // sait pas distinguer « donnée corrompue » de « mauvaise clé » : indisponible, donc,
            // jamais « absente ».
            throw KekFailure.SourceUnavailable(name, cause)
        }
    }

    /**
     * Décode les 64 caractères hexadécimaux que Notes Tech écrit (`vault_service.dart:104`).
     *
     * ⚠️ Ici, et seulement ici, un échec vaut [KekFailure.MalformedKey] plutôt que
     * [KekFailure.SourceUnavailable] : le déchiffrement a réussi, donc la clé de déchiffrement était
     * la bonne. Ce qui cloche est la **donnée**, et aucune autre source ne la réparera.
     */
    private fun parseHexKek(hex: ByteArray): ByteArray {
        val kek = try {
            SecretBytes.fromHexAscii(hex)
        } catch (cause: IllegalArgumentException) {
            throw KekFailure.MalformedKey(name, cause)
        }
        if (kek.size != KEK_SIZE_BYTES) {
            kek.wipe()
            throw KekFailure.MalformedKey(name, null)
        }
        return kek
    }

    companion object {
        /**
         * L'identifiant de l'application **publiée**, qui n'est pas forcément celui de la build en
         * cours.
         *
         * L'alias du Keystore est construit par `context.getPackageName()` côté bibliothèque
         * (`KeyCipherImplementationRSA18.java:41`). Or la build de portage porte le suffixe `.next`
         * tant que la phase 8 n'a pas eu lieu : lire `packageName` ici donnerait un alias qui
         * n'existe nulle part.
         *
         * Écrire l'identifiant en clair dit ce qu'on cherche : **le matériel que l'application
         * publiée a créé**. Au moment où ce code servira pour de bon, la build aura repris cet
         * identifiant et les deux coïncideront.
         *
         * ⚠️ Ce n'est pas ce qui rend la lecture possible : le Keystore est cloisonné par UID, et
         * une build isolée ne verra jamais le matériel de l'application réelle, quel que soit
         * l'alias demandé. Le paramètre existe pour que les tests puissent viser le leur.
         */
        const val PUBLISHED_APPLICATION_ID = "com.filestech.notes_tech"

        /** `FlutterSecureStorageConfig.java:15` */
        const val DATA_PREFS = "FlutterSecureStorage"

        /** `FlutterSecureStorageConfig.java:184` — sans espace de noms, pas de suffixe. */
        const val KEY_STORAGE_PREFS = "FlutterSecureKeyStorage"

        /**
         * `StorageCipherFactory.java:13-15` — les marqueurs d'algorithme vivent ici, et non
         * dans [DATA_PREFS]. Nom verifie sur appareil le 2026-08-20 :
         * `FlutterSecureStorageConfiguration:FlutterSecureStorage.xml`.
         */
        const val CONFIG_PREFS = "FlutterSecureStorageConfiguration:$DATA_PREFS"

        /** `FlutterSecureStorageConfig.java:16` + `FlutterSecureStorage.java:51` (`prefixe + "_" + cle`). */
        const val VALUE_KEY =
            "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIHNlY3VyZSBzdG9yYWdlCg_notes_tech.vault.kek.v1"

        /** `StorageCipherImplementationGCM.java:21` */
        const val WRAPPED_KEY = "AESVGhpcyBpcyB0aGUga2V5IGZvciBhIHNlY3VyZSBzdG9yYWdlIEFFUyBLZXkK"

        /** `StorageCipherFactory.java:14-15` — la bibliothèque note ce qu'elle a employé. */
        const val ALGORITHM_KEY_MARKER = "FlutterSecureSAlgorithmKey"
        const val ALGORITHM_STORAGE_MARKER = "FlutterSecureSAlgorithmStorage"

        const val EXPECTED_KEY_ALGORITHM = "RSA_ECB_OAEPwithSHA_256andMGF1Padding"
        const val EXPECTED_STORAGE_ALGORITHM = "AES_GCM_NoPadding"

        /** `KeyCipherImplementationRSAOAEP.java:30` */
        const val KEY_ALIAS_SUFFIX = ".FlutterSecureStoragePluginKeyOAEP"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** `KeyCipherImplementationRSAOAEP.java:49` — le fournisseur compte autant que la transformation. */
        private const val KEYSTORE_RSA_PROVIDER = "AndroidKeyStoreBCWorkaround"
        private const val RSA_TRANSFORMATION = "RSA/ECB/OAEPPadding"

        private const val AES = "AES"

        /** `StorageCipherImplementationGCM.java:18` — 16 octets, donc AES-128. */
        private const val AES_KEY_SIZE = 16
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"

        /** `StorageCipherImplementationGCM.java:99` */
        private const val IV_SIZE = 12

        /** `StorageCipherImplementationGCM.java:19` */
        private const val GCM_TAG_BITS = 128

        /** La KEK de Notes Tech : 32 octets, écrits en 64 caractères hexadécimaux. */
        private const val KEK_SIZE_BYTES = 32
    }
}
