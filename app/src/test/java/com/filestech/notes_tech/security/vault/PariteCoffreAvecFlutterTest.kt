package com.filestech.notes_tech.security.vault

import com.filestech.notes_tech.core.crypto.SecretBytes
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Rejoue contre le portage les vecteurs produits en **exécutant** le code Dart de Notes Tech 2.0.3.
 *
 * ## Pourquoi ces tests sont les plus importants de la phase 4
 *
 * Une erreur ici ne casse pas une fonctionnalité : elle rend inouvrables des coffres qui existent
 * déjà sur les téléphones des utilisateurs, sans qu'aucun message ne dise pourquoi. Il n'y a pas de
 * correctif après coup — la clé dérivée d'une passphrase avec les mauvais paramètres n'est pas
 * « presque » la bonne.
 *
 * ## D'où viennent les vecteurs, et ce qu'ils valent
 *
 * Produits par un harnais temporaire exécuté dans le dépôt Flutter, qui appelle les vraies
 * primitives du paquet `cryptography` 2.9.0 avec les vrais paramètres. Puis **recoupés contre une
 * troisième implantation** : `argon2-cffi`, qui est le C de référence de la RFC 9106, et
 * `cryptography` Python, qui est OpenSSL. 37 concordances, zéro divergence.
 *
 * Ce recoupement compte : il exclut qu'un défaut du paquet Dart soit reproduit à l'identique côté
 * Kotlin et passe pour une réussite. Les trois implantations sont d'accord, donc c'est le standard
 * qui est implanté des deux côtés, pas une bizarrerie commune.
 *
 * ⚠️ **Ce que ces tests ne prouvent pas** : qu'un utilisateur réel rouvre son coffre. Ils figent un
 * format ; le critère de sortie de la phase 4 reste d'ouvrir un coffre réellement créé par la
 * version Flutter. Procédure et limites : `docs/09-VECTEURS-DE-PARITE.md`.
 */
class PariteCoffreAvecFlutterTest {

    @Test
    @DisplayName("Argon2id rend exactement les clés du Dart, sur les deux jeux de paramètres")
    fun argon2idConcordeAvecLeDart() {
        val cas = chargerVecteurs("coffre_argon2.tsv")
        assertThat(cas).hasSize(16)

        for (ligne in cas) {
            val (variante, nom, motDePasseHex, selHex) = ligne
            val iterations = ligne[4].toInt()
            val memoireKib = ligne[5].toInt()
            val attendu = ligne[6]

            val obtenu = VaultCrypto.deriveKey(
                secret = SecretBytes.fromHex(motDePasseHex),
                salt = SecretBytes.fromHex(selHex),
                iterations = iterations,
                memoryKib = memoireKib,
            )

            assertWithMessage("$variante/$nom").that(SecretBytes.toHex(obtenu)).isEqualTo(attendu)
        }
    }

    @Test
    @DisplayName("les raccourcis passphrase et PIN portent bien les paramètres de leur mode")
    fun lesRaccourcisPortentLesBonsParametres() {
        // Le vrai risque n'est pas de mal implanter Argon2id — Bouncy Castle s'en charge — mais
        // d'appeler le mode PIN avec les paramètres de la passphrase, ou l'inverse. Les deux
        // rendraient une clé bien formée, de la bonne longueur, et parfaitement inutile.
        val cas = chargerVecteurs("coffre_argon2.tsv")
        val unePassphrase = cas.first { it[0] == "passphrase" }
        val unPin = cas.first { it[0] == "pin" }

        val parRaccourciPassphrase = VaultCrypto.derivePassphraseKey(
            SecretBytes.fromHex(unePassphrase[2]),
            SecretBytes.fromHex(unePassphrase[3]),
        )
        val parRaccourciPin = VaultCrypto.derivePinKey(
            SecretBytes.fromHex(unPin[2]),
            SecretBytes.fromHex(unPin[3]),
        )

        assertThat(SecretBytes.toHex(parRaccourciPassphrase)).isEqualTo(unePassphrase[6])
        assertThat(SecretBytes.toHex(parRaccourciPin)).isEqualTo(unPin[6])
    }

    @Test
    @DisplayName("le vérificateur HMAC concorde, et rejette une clé voisine")
    fun leVerificateurConcorde() {
        for (ligne in chargerVecteurs("coffre_verifier.tsv")) {
            val cle = SecretBytes.fromHex(ligne[0])
            assertThat(SecretBytes.toHex(VaultCrypto.verifierFor(cle))).isEqualTo(ligne[1])
            assertThat(VaultCrypto.matchesVerifier(cle, SecretBytes.fromHex(ligne[1]))).isTrue()

            // Un seul bit de différence sur la clé doit suffire à ne plus concorder.
            val voisine = cle.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            assertThat(VaultCrypto.matchesVerifier(voisine, SecretBytes.fromHex(ligne[1]))).isFalse()
        }
    }

    @Test
    @DisplayName("le scellement AES-GCM reproduit l'enveloppe du Dart, octet pour octet")
    fun leScellementReproduitLEnveloppeDart() {
        for (ligne in chargerVecteurs("coffre_wrap.tsv")) {
            val (nom, cleHex, nonceHex, clairHex) = ligne
            val aadHex = ligne[4]
            val attendu = ligne[5]

            val scelle = VaultCrypto.seal(
                key = SecretBytes.fromHex(cleHex),
                nonce = SecretBytes.fromHex(nonceHex),
                plaintext = SecretBytes.fromHex(clairHex),
                aad = SecretBytes.fromHex(aadHex),
            )
            assertWithMessage(nom).that(SecretBytes.toHex(scelle)).isEqualTo(attendu)

            val rouvert = VaultCrypto.open(
                key = SecretBytes.fromHex(cleHex),
                nonce = SecretBytes.fromHex(nonceHex),
                sealed = SecretBytes.fromHex(attendu),
                aad = SecretBytes.fromHex(aadHex),
            )
            assertWithMessage(nom).that(SecretBytes.toHex(rouvert)).isEqualTo(clairHex)
        }
    }

    @Test
    @DisplayName("changer l'AAD d'un seul octet fait échouer l'ouverture")
    fun lAadEstLiante() {
        // C'est tout l'objet de l'AAD : empêcher de recopier le `vault_kek_wrapped` d'un coffre sur
        // un autre pour en réutiliser la passphrase. Si ce test passait sans elle, la liaison
        // n'existerait pas.
        val ligne = chargerVecteurs("coffre_wrap.tsv").first()
        val aad = SecretBytes.fromHex(ligne[4]).also { it[0] = (it[0].toInt() xor 1).toByte() }

        assertThrows<WrongSecretException> {
            VaultCrypto.open(
                key = SecretBytes.fromHex(ligne[1]),
                nonce = SecretBytes.fromHex(ligne[2]),
                sealed = SecretBytes.fromHex(ligne[5]),
                aad = aad,
            )
        }
    }

    @Test
    @DisplayName("les blobs de note du Dart se relisent, dans les deux formats")
    fun lesBlobsDeNoteSeRelisent() {
        val cas = chargerVecteurs("coffre_note.tsv")
        assertThat(cas).hasSize(5)

        for (ligne in cas) {
            val nom = ligne[0]
            val encV = ligne[1].toInt()
            val folderKey = SecretBytes.fromHex(ligne[2])
            val noteId = SecretBytes.fromHex(ligne[3])
            val titreAttendu = String(SecretBytes.fromHex(ligne[4]), Charsets.UTF_8)
            val contenuAttendu = String(SecretBytes.fromHex(ligne[5]), Charsets.UTF_8)
            val blob = SecretBytes.fromHex(ligne[6])

            val clair = VaultCrypto.open(
                key = folderKey,
                nonce = NoteEnvelope.nonceOf(blob),
                sealed = NoteEnvelope.sealedOf(blob),
                aad = noteId,
            )

            if (encV == NoteEnvelope.ENC_V_TITLE_AND_CONTENT) {
                val decoupe = NoteEnvelope.unpackTitleAndContent(clair)
                assertWithMessage("$nom titre").that(decoupe.title).isEqualTo(titreAttendu)
                assertWithMessage("$nom contenu").that(decoupe.content).isEqualTo(contenuAttendu)
                // Et le chemin inverse : recomposer doit rendre le blob d'origine à l'octet près.
                val recompose = NoteEnvelope.composeBlob(
                    nonce = NoteEnvelope.nonceOf(blob),
                    sealed = VaultCrypto.seal(
                        key = folderKey,
                        nonce = NoteEnvelope.nonceOf(blob),
                        plaintext = NoteEnvelope.packTitleAndContent(decoupe.title, decoupe.content),
                        aad = noteId,
                    ),
                )
                assertWithMessage("$nom recompose").that(SecretBytes.toHex(recompose)).isEqualTo(ligne[6])
            } else {
                assertWithMessage("$nom contenu v1").that(String(clair, Charsets.UTF_8)).isEqualTo(contenuAttendu)
            }
        }
    }

    @Test
    @DisplayName("un coffre passphrase entier s'ouvre à partir de la seule passphrase")
    fun unCoffreEntierSOuvreDepuisLaPassphrase() {
        // Le scénario complet, et celui qui ressemble le plus à la réalité : on ne dispose que de
        // ce qui serait en base, plus ce que l'utilisateur tape.
        val ligne = chargerVecteurs("coffre_complet.tsv").single()
        val passphrase = SecretBytes.fromHex(ligne[0])
        val folderId = SecretBytes.fromHex(ligne[1])
        val sel = SecretBytes.fromHex(ligne[2])
        val vaultIv = SecretBytes.fromHex(ligne[3])
        val kekEmballee = SecretBytes.fromHex(ligne[4])
        val verifierAttendu = SecretBytes.fromHex(ligne[5])

        val kekDerivee = VaultCrypto.derivePassphraseKey(passphrase, sel)
        assertThat(SecretBytes.toHex(kekDerivee)).isEqualTo(ligne[6])

        val folderKey = VaultCrypto.open(kekDerivee, vaultIv, kekEmballee, folderId)
        assertThat(SecretBytes.toHex(folderKey)).isEqualTo(ligne[7])
        assertThat(VaultCrypto.matchesVerifier(folderKey, verifierAttendu)).isTrue()

        val noteId = SecretBytes.fromHex(ligne[8])
        val blob = SecretBytes.fromHex(ligne[11])
        val decoupe = NoteEnvelope.unpackTitleAndContent(
            VaultCrypto.open(folderKey, NoteEnvelope.nonceOf(blob), NoteEnvelope.sealedOf(blob), noteId),
        )
        assertThat(decoupe.title).isEqualTo(String(SecretBytes.fromHex(ligne[9]), Charsets.UTF_8))
        assertThat(decoupe.content).isEqualTo(String(SecretBytes.fromHex(ligne[10]), Charsets.UTF_8))
    }

    @Test
    @DisplayName("une passphrase fausse échoue en WrongSecretException, pas autrement")
    fun unePassphraseFausseEchoueProprement() {
        val ligne = chargerVecteurs("coffre_complet.tsv").single()
        val mauvaise = VaultCrypto.derivePassphraseKey(
            "pas la bonne passphrase".toByteArray(Charsets.UTF_8),
            SecretBytes.fromHex(ligne[2]),
        )

        // Le type importe autant que l'échec : c'est lui qui autorise à compter une tentative.
        assertThrows<WrongSecretException> {
            VaultCrypto.open(
                mauvaise,
                SecretBytes.fromHex(ligne[3]),
                SecretBytes.fromHex(ligne[4]),
                SecretBytes.fromHex(ligne[1]),
            )
        }
    }

    @Test
    @DisplayName("les deux couches internes d'un coffre PIN concordent avec le Dart")
    fun lesCouchesInternesDuCoffrePinConcordent() {
        // La couche Keystore est liée à l'appareil ET à l'UID : aucun vecteur ne la reproduit. Ce
        // qui est vérifié ici, c'est ce qui ENTRE dans le Keystore.
        val ligne = chargerVecteurs("coffre_pin.tsv").single()
        val pin = SecretBytes.fromHex(ligne[0])
        val folderId = SecretBytes.fromHex(ligne[1])

        assertThat(ligne[2]).isEqualTo(VaultParams.pinKeystoreAlias(String(folderId, Charsets.UTF_8)))

        val pinKek = VaultCrypto.derivePinKey(pin, SecretBytes.fromHex(ligne[3]))
        assertThat(SecretBytes.toHex(pinKek)).isEqualTo(ligne[5])

        val folderKey = VaultCrypto.open(pinKek, SecretBytes.fromHex(ligne[4]), SecretBytes.fromHex(ligne[7]), folderId)
        assertThat(SecretBytes.toHex(folderKey)).isEqualTo(ligne[6])
    }

    private fun chargerVecteurs(nom: String): List<List<String>> {
        val flux = checkNotNull(javaClass.getResourceAsStream("/parite/$nom")) {
            "ressource /parite/$nom absente — regénérer avec docs/09-VECTEURS-DE-PARITE.md"
        }
        return flux.bufferedReader(Charsets.UTF_8).useLines { lignes ->
            lignes.filterNot { it.startsWith("#") || it.isBlank() }
                .map { it.split('\t') }
                .toList()
        }
    }

    /** Déstructuration des quatre premières colonnes — le reste se lit par indice. */
    private operator fun List<String>.component4(): String = this[3]
}
