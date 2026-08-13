package com.filestech.notes_tech.security.kek

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.core.crypto.SecretBytes
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La couche ② de l'acquisition de la KEK, éprouvée contre le **vrai format** de
 * `flutter_secure_storage 10.3.1`.
 *
 * ## Ce que ces tests prouvent, et ce qu'ils ne prouvent pas
 *
 * ✅ Le format est transcrit correctement : une valeur écrite exactement comme la bibliothèque
 * l'écrit est relue et rend la KEK d'origine, octet pour octet.
 *
 * ✅ Les échecs sont classés dans la bonne catégorie. C'est **plus important que le cas nominal** :
 * une lecture qui échoue ne doit jamais être prise pour « aucune clé », faute de quoi la couche
 * supérieure générerait une clé neuve et condamnerait les notes.
 *
 * ❌ Ils ne prouvent **pas** qu'un utilisateur qui migre récupérera sa clé. L'`AndroidKeyStore` est
 * cloisonné par UID : la build de portage porte le suffixe `.next` et ne verra jamais le matériel de
 * l'application publiée. Cela ne se vérifiera qu'à la bascule — cf.
 * `docs/06-ISOLATION-PENDANT-LE-CHANTIER.md` §2.
 *
 * Ne pas confondre les deux. « Le format se lit » et « la migration fonctionne » sont deux
 * affirmations différentes, et seule la première est ici démontrée.
 */
@RunWith(AndroidJUnit4::class)
class FlutterSecureStorageKekSourceTest {

    private lateinit var context: Context
    private lateinit var source: FlutterSecureStorageKekSource

    /**
     * Volontairement **différent** de l'identifiant publié : le test ne doit jamais toucher au
     * matériel qu'une vraie Notes Tech aurait pu laisser sur l'appareil de test.
     */
    private val aliasBase = "com.filestech.notes_tech.essai"

    /** 32 octets, écrits en 64 caractères hexadécimaux, comme le fait Notes Tech. */
    private val kek = ByteArray(32) { (it * 13 + 5).toByte() }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        source = FlutterSecureStorageKekSource(context, keyAliasBase = aliasBase)
        FlutterSecureStorageFixture.clear(context, aliasBase)
    }

    @After
    fun tearDown() {
        FlutterSecureStorageFixture.clear(context, aliasBase)
    }

    @Test
    fun une_valeur_ecrite_comme_le_fait_la_bibliotheque_est_relue_a_l_identique() {
        FlutterSecureStorageFixture.seed(context, aliasBase, SecretBytes.toHex(kek))

        val lue = source.load()

        assertThat(lue).isNotNull()
        assertThat(lue).isEqualTo(kek)
    }

    /**
     * 🔴 **Le test qui justifie d'avoir lu les sources au lieu d'écrire de mémoire — et qui a rendu
     * un résultat plus fort que prévu.**
     *
     * L'enveloppe RSA-OAEP combine un condensat principal SHA-256 avec un MGF1 en **SHA-1**. C'est
     * contre-intuitif, et personne n'écrirait ça spontanément.
     *
     * La première version de ce test scellait avec MGF1 en SHA-256 — ce que produirait une
     * transcription faite de mémoire — et attendait une lecture illisible. **Le sceau n'a même pas
     * pu être posé** : l'`AndroidKeyStore` refuse la combinaison à l'initialisation, avec le message
     * *« Unsupported MGF1 digest: SHA-256. Only SHA-1 supported »*.
     *
     * Ce que ça change : `MGF1ParameterSpec.SHA1` dans `FlutterSecureStorageKekSource` n'est pas un
     * choix de la bibliothèque qu'on recopie, c'est **la seule valeur que la plateforme accepte**.
     * Une erreur à cet endroit échouerait donc bruyamment à l'`init`, jamais en silence — et le
     * `catch` de la couche ② la classerait « indisponible », pas « absente ».
     *
     * Le test consigne cette contrainte plutôt que de la supposer stable : si un jour une version
     * d'Android acceptait SHA-256, il échouerait, et il faudrait alors vérifier ce que la
     * bibliothèque écrit réellement sur cette version-là.
     */
    @Test
    fun la_plateforme_impose_mgf1_en_sha1_et_refuse_sha256() {
        val refus = assertThrows(java.security.InvalidAlgorithmParameterException::class.java) {
            FlutterSecureStorageFixture.seed(
                context,
                aliasBase,
                SecretBytes.toHex(kek),
                mgf1 = FlutterSecureStorageFixture.Mgf1Digest.SHA256,
            )
        }

        assertThat(refus).hasMessageThat().contains("MGF1")
    }

    /** Aucune valeur : cette source ne détient rien. C'est le seul cas qui rend `null`. */
    @Test
    fun un_stockage_vide_rend_null() {
        assertThat(source.load()).isNull()
    }

    /**
     * ⚠️ Marqueurs d'algorithme absents = données écrites par une 9.x, dans une combinaison que
     * cette couche ne sait pas lire.
     *
     * Elle passe la main plutôt que de tenter un déchiffrement à l'aveugle, qui pourrait rendre des
     * octets arbitraires passant le contrôle de longueur — et ouvrir la base avec une clé fausse.
     */
    @Test
    fun des_marqueurs_d_algorithme_absents_rendent_la_source_indisponible() {
        FlutterSecureStorageFixture.seed(
            context,
            aliasBase,
            SecretBytes.toHex(kek),
            keyAlgorithmMarker = null,
            storageAlgorithmMarker = null,
        )

        assertThrows(KekFailure.SourceUnavailable::class.java) { source.load() }
    }

    @Test
    fun des_marqueurs_d_algorithme_inattendus_rendent_la_source_indisponible() {
        FlutterSecureStorageFixture.seed(
            context,
            aliasBase,
            SecretBytes.toHex(kek),
            keyAlgorithmMarker = "RSA_ECB_PKCS1Padding",
            storageAlgorithmMarker = "AES_CBC_PKCS7Padding",
        )

        assertThrows(KekFailure.SourceUnavailable::class.java) { source.load() }
    }

    /**
     * ⚠️ Une valeur présente **sans** la clé AES qui la déchiffre est un stockage incohérent, pas un
     * stockage vide. Le distinguer est ce qui empêche d'en conclure « pas de clé ».
     */
    @Test
    fun une_valeur_sans_sa_cle_enveloppee_rend_la_source_indisponible() {
        FlutterSecureStorageFixture.seed(context, aliasBase, SecretBytes.toHex(kek))
        FlutterSecureStorageFixture.removeWrappedKey(context)

        assertThrows(KekFailure.SourceUnavailable::class.java) { source.load() }
    }

    /** Le tag GCM ne valide pas : on ne sait pas distinguer corruption et mauvaise clé. */
    @Test
    fun une_valeur_corrompue_rend_la_source_indisponible() {
        FlutterSecureStorageFixture.seed(context, aliasBase, SecretBytes.toHex(kek))
        FlutterSecureStorageFixture.corruptStoredValue(context)

        assertThrows(KekFailure.SourceUnavailable::class.java) { source.load() }
    }

    /**
     * Déchiffrement réussi mais contenu inattendu : là, on **sait** que la clé de déchiffrement était
     * la bonne, donc le problème est la donnée. C'est le seul cas qui mérite `MalformedKey`.
     */
    @Test
    fun un_clair_qui_n_est_pas_hexadecimal_est_signale_comme_cle_mal_formee() {
        FlutterSecureStorageFixture.seed(context, aliasBase, "ceci n'est pas de l'hexadecimal !!")

        assertThrows(KekFailure.MalformedKey::class.java) { source.load() }
    }

    @Test
    fun un_hexadecimal_de_mauvaise_longueur_est_signale_comme_cle_mal_formee() {
        FlutterSecureStorageFixture.seed(context, aliasBase, "abcdef0123456789")

        assertThrows(KekFailure.MalformedKey::class.java) { source.load() }
    }

    /**
     * ⚠️ La source ne modifie **rien**, même quand elle réussit.
     *
     * Elle lit le stockage d'une autre bibliothèque, que la version Flutter relira si l'utilisateur
     * revient en arrière. Y écrire signifierait reproduire aussi son chemin d'écriture, avec le
     * risque de le corrompre.
     */
    @Test
    fun la_lecture_ne_modifie_pas_le_stockage() {
        FlutterSecureStorageFixture.seed(context, aliasBase, SecretBytes.toHex(kek))
        val avantDonnees = context
            .getSharedPreferences(FlutterSecureStorageKekSource.DATA_PREFS, Context.MODE_PRIVATE).all
        val avantCle = context
            .getSharedPreferences(FlutterSecureStorageKekSource.KEY_STORAGE_PREFS, Context.MODE_PRIVATE).all

        source.load()
        source.load()

        assertThat(
            context.getSharedPreferences(FlutterSecureStorageKekSource.DATA_PREFS, Context.MODE_PRIVATE).all,
        ).isEqualTo(avantDonnees)
        assertThat(
            context.getSharedPreferences(FlutterSecureStorageKekSource.KEY_STORAGE_PREFS, Context.MODE_PRIVATE).all,
        ).isEqualTo(avantCle)
    }

    /**
     * L'alias visé par défaut est celui de l'application **publiée**, pas celui de la build en cours.
     *
     * Ce test ne vérifie qu'une constante, mais c'est celle qui décidera, à la bascule, si le
     * matériel est trouvé ou non.
     */
    @Test
    fun l_alias_vise_celui_de_l_application_publiee() {
        assertThat(FlutterSecureStorageKekSource.PUBLISHED_APPLICATION_ID)
            .isEqualTo("com.filestech.notes_tech")
        assertThat(FlutterSecureStorageKekSource.KEY_ALIAS_SUFFIX)
            .isEqualTo(".FlutterSecureStoragePluginKeyOAEP")
    }
}
