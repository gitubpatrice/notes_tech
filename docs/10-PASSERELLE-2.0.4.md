# La release passerelle 2.0.4 — couche ① de l'acquisition de la KEK

> Créé le 2026-08-13, après avoir écrit et éprouvé la couche ②.
>
> ✅ **Écrite et vérifiée le 2026-08-18** — `notes_tech`, branche `fix/defauts-releves-pendant-le-portage`,
> commit `f216390`, version **2.0.4+52**. La passerelle **n'est pas publiée** : elle est écrite,
> mesurée sur le S9, et attend une décision de publication.
>
> ⚠️ Ce document décrivait quoi faire. **Deux points ont été faits autrement**, et une affirmation
> s'est révélée fausse à la mesure — voir §8.

---

## 1. Ce que la passerelle fait, et pourquoi elle vaut mieux que la couche ②

La couche ② lit le stockage de `flutter_secure_storage` en rejouant sa cryptographie à l'envers.
Elle fonctionne — c'est mesuré — mais elle repose sur le format interne d'une bibliothèque tierce,
que rien n'oblige à rester stable.

La passerelle inverse la charge : **c'est l'application Flutter qui migre sa propre clé**, pendant
qu'elle est encore installée et qu'elle sait la lire. Elle re-scelle la KEK sous un alias que ce
projet maîtrise, avec des API de plateforme, et la version Kotlin n'a plus qu'à lire.

| | Couche ① (passerelle) | Couche ② (secours) |
|---|---|---|
| Ce qui lit la KEK d'origine | l'application Flutter, avec sa propre bibliothèque | du code transcrit à la main |
| Ce dont ça dépend | l'`AndroidKeyStore` | le format interne de `flutter_secure_storage 10.3.1` |
| Quand ça sert | l'utilisateur installe la 2.0.4 avant la 3.0.0 | il saute la 2.0.4 |

⚠️ **La couche ② reste indispensable.** Un utilisateur qui met à jour rarement passera directement
de sa version actuelle à la 3.0.0. Le S9 de test porte la **2.0.1**, pas la 2.0.3 : ce cas n'est pas
théorique.

## 2. Le contrat exact — ce que la couche ① lit

`KeystoreSealedKekSource` attend **précisément** ceci, et rien d'autre :

| Élément | Valeur |
|---|---|
| Alias `AndroidKeyStore` | `notes_tech.db.kek.v1` |
| Fichier de préférences | `notes_tech.kek` |
| Clé du scellé | `db_kek_v1.blob` |
| Clé du nonce | `db_kek_v1.nonce` |
| Encodage des deux | **`Base64.NO_WRAP`** |
| Chiffrement | AES-256-GCM, tag 128 bits, nonce généré par le Keystore |
| **Clair scellé** | **les 32 octets BRUTS de la KEK** |

### 🔴 Les deux pièges de ce contrat

**1. Le clair est en octets bruts, pas en hexadécimal.**
`flutter_secure_storage` contient les **64 caractères hexadécimaux** de la KEK
(`vault_service.dart:104`). Sceller cette chaîne telle quelle produirait un clair de 64 octets, que
`KeystoreSealedKekSource.load()` rejetterait — il vérifie la longueur. L'échec serait franc, pas
silencieux, mais la migration ne se ferait pas.

**Il faut donc décoder l'hexadécimal avant de sceller.**

**2. `Base64.NO_WRAP`, jamais `Base64.DEFAULT`.**
`DEFAULT` insère des retours à la ligne. C'est la bonne hygiène, et le contrat reste écrit ainsi.

⚠️⚠️ **Mais la raison donnée ici était FAUSSE, et c'est un test qui l'a dit.** Ce paragraphe affirmait
que « le décodage strict côté Kotlin échoue ». Il n'échoue pas : `KeystoreSealedKekSource.decodeBase64`
appelle `Base64.decode(value, Base64.DEFAULT)`, et le décodeur d'Android **ignore les blancs**. Mesuré
par `KeystoreSealedKekSourceTest.un_scelle_encode_avec_des_retours_a_la_ligne_est_TOLERE_par_le_decodeur`.

*Une garde supposée est pire qu'une garde absente : on cesse de la chercher ailleurs.* Écrire en
`NO_WRAP` reste juste ; croire qu'un contrôle rattraperait l'oubli ne l'était pas.

## 3. 🔴 Pourquoi la passerelle doit écrire les préférences **en Kotlin**, jamais en Dart

C'est le point qu'on rate en écrivant cette release, et il rendrait la passerelle inopérante sans
que rien ne se voie côté Flutter.

Le greffon `shared_preferences` **préfixe toutes ses clés par `flutter.`** et écrit dans son propre
fichier. Une écriture Dart de `db_kek_v1.blob` atterrirait donc en `flutter.db_kek_v1.blob`, dans
`FlutterSharedPreferences` — pas dans `notes_tech.kek`. La couche ① ne trouverait rien, et la 2.0.4
aurait l'air d'avoir fonctionné.

L'écriture doit se faire **dans le même appel natif** que le scellement.

## 4. Ce qu'il y a à ajouter

> ⚠️⚠️ **L'extrait ci-dessous est le PLAN, et le code livré en diverge sur deux points** — voir §8.
> Il prend `kekHex` là où l'implémentation prend des **octets bruts**, et son idempotence ne regarde
> que les préférences. Il est gardé tel quel parce qu'il documente le raisonnement d'origine ; **la
> source de vérité est `notes_tech/android/.../KeystoreBridge.kt`**, pas ce bloc.

`android/app/src/main/kotlin/com/filestech/notes_tech/KeystoreBridge.kt` existe déjà et fait
l'essentiel : `createKey(alias)` est idempotent, tente StrongBox puis retombe sur le TEE, et pose
`setUnlockedDeviceRequired(true)` à partir d'Android 9 ; `wrap(alias, plaintext)` rend le couple
`{ciphertext, nonce}`.

Il manque **une méthode** qui enchaîne les trois gestes sans repasser par Dart :

```kotlin
/**
 * Re-scelle la KEK de la base sous l'alias que lira la version Kotlin.
 *
 * 🔴 Écrit les préférences ICI, en natif, et pas côté Dart : `shared_preferences` préfixe ses clés
 * par `flutter.` et utilise son propre fichier. Une écriture Dart n'atterrirait pas où la version
 * Kotlin regarde, et la migration paraîtrait réussie sans l'être.
 *
 * Idempotent : si le scellé existe déjà, ne fait rien. Rejouable sans risque à chaque démarrage.
 *
 * @param kekHex les 64 caractères hexadécimaux tels que `flutter_secure_storage` les contient.
 * @return `true` si un scellé a été écrit, `false` s'il y en avait déjà un.
 */
private fun sealDatabaseKek(kekHex: String): Boolean {
    val prefs = context.getSharedPreferences(KEK_PREFS_NAME, Context.MODE_PRIVATE)
    if (prefs.contains(KEK_BLOB) && prefs.contains(KEK_NONCE)) return false

    // ⚠️ Les 32 octets BRUTS, pas la chaîne hexadécimale : la version Kotlin vérifie la longueur
    // du clair et rejetterait 64 octets.
    require(kekHex.length == 64) { "KEK attendue en 64 caracteres hexadecimaux" }
    val raw = ByteArray(32) { i ->
        ((Character.digit(kekHex[i * 2], 16) shl 4) or Character.digit(kekHex[i * 2 + 1], 16)).toByte()
    }

    try {
        createKey(KEK_ALIAS)
        val sealed = wrap(KEK_ALIAS, raw)
        // ⚠️ NO_WRAP : `DEFAULT` insère des retours à la ligne. Voir §2 — la raison donnée
        // à l'origine était fausse, le décodeur les tolère ; la consigne, elle, reste.
        prefs.edit()
            .putString(KEK_BLOB, Base64.encodeToString(sealed["ciphertext"], Base64.NO_WRAP))
            .putString(KEK_NONCE, Base64.encodeToString(sealed["nonce"], Base64.NO_WRAP))
            .apply()
    } finally {
        raw.fill(0)
    }
    return true
}

// À ajouter au companion object existant :
private const val KEK_ALIAS = "notes_tech.db.kek.v1"
private const val KEK_PREFS_NAME = "notes_tech.kek"
private const val KEK_BLOB = "db_kek_v1.blob"
private const val KEK_NONCE = "db_kek_v1.nonce"
```

Exposée par un cas de plus dans `onMethodCall` — `"sealDatabaseKek"` — qui prend `kekHex` et rend le
booléen.

### Côté Dart, au démarrage

```dart
// Après l'ouverture de la base, quand la KEK est déjà en main.
// N'échoue JAMAIS l'application : la 2.0.4 doit rester utilisable même si le scellement rate.
try {
  await _keystoreChannel.invokeMethod<bool>('sealDatabaseKek', {'kekHex': kekHex});
} catch (e) {
  if (kDebugMode) debugPrint('re-scellage de la KEK impossible : $e');
}
```

⚠️ **Le `catch` est obligatoire, et large.** La 2.0.4 est une version de transition : un échec de
scellement ne doit pas empêcher l'utilisateur d'ouvrir ses notes. Il le fera simplement basculer sur
la couche ② au moment de la 3.0.0 — le repli existe pour ça.

⚠️ **Ne rien retirer de `flutter_secure_storage`.** La KEK doit y rester : on ajoute un chemin, on
n'en supprime aucun. Un utilisateur qui revient en arrière depuis la 3.0.0 doit retrouver une
application qui fonctionne.

## 5. Ce que `setUnlockedDeviceRequired(true)` implique

`createKey` pose ce drapeau à partir d'Android 9. La clé n'est donc utilisable **qu'écran
déverrouillé**.

Conséquence pour la version Kotlin : toute ouverture de base sur un appareil verrouillé échouera en
`KekFailure.SourceUnavailable` — c'est-à-dire du bon côté, sans rien détruire. Mais cela interdit
d'ouvrir la base depuis un travail d'arrière-plan qui tournerait écran verrouillé.

Notes Tech n'en a aucun aujourd'hui. **Si la phase 6 en introduit un** — rappel, widget, sauvegarde
planifiée — il faudra soit y renoncer, soit revoir ce drapeau, ce qui demanderait de re-sceller
toutes les clés existantes. À décider avant d'écrire un tel travail, pas après.

## 6. Procédure de vérification, sur le S9 uniquement

⚠️ Le S9 portait `com.filestech.notes_tech` en **2.0.3** (versionCode 2051) au 2026-08-18 — et non
2.0.1 comme écrit ici jusque-là. Toute manipulation ci-dessous **efface ou remplace cette
installation** ; le S9 est un téléphone de test, confirmé par Patrice.

1. installer la version Flutter, créer dossiers, notes, liens, un coffre passphrase, un coffre PIN ;
2. installer la **2.0.4** par-dessus, l'ouvrir une fois, la fermer ;
3. vérifier que `notes_tech.kek` contient bien les deux clés — sur une build **debug** :
   `adb shell run-as com.filestech.notes_tech cat shared_prefs/notes_tech.kek.xml` ;
4. installer la build Kotlin **avec `-Pnotestech.replaceInstalledApp=true`** ;
5. vérifier que notes, dossiers, liens et recherche répondent, et que les deux coffres s'ouvrent.

Puis le même parcours **en sautant l'étape 2**, pour exercer la couche ②.
Puis en sautant 2 **et** en effaçant la valeur `flutter_secure_storage`, pour vérifier que la couche
③ refuse proprement — **et surtout que la base est toujours là après**.

⚠️ `connectedAndroidTest` efface les données de l'application : jamais sur le S24 FE.

## 7. Ce qui reste à décider — pas à deviner

| Question | Pourquoi elle n'est pas tranchée ici |
|---|---|
| Publier une 2.0.4 | demande la clé de signature et une décision de Patrice |
| Dégeler `notes_tech` le temps du correctif | le dépôt est gelé pour éviter la dérive de parité |
| Numéro de version et `versionCode` | dépend de la 2.0.3 publiée et du décalage des splits ABI |

---

## 8. ✅ Ce qui a été fait le 2026-08-18, et les trois écarts avec ce document

### Fait

| | |
|---|---|
| `KeystoreBridge.sealDatabaseKek` | écrit, côté natif, avec les quatre valeurs du §2 |
| Appel Dart au démarrage | après l'ouverture de la base, **avant** le `wipe` de la KEK, `catch` large |
| Version | **2.0.4+52**, `AppConstants.appVersion` bumpée avec le `pubspec` |
| Contrat d'appel | mesuré — `test/keystore_bridge_seal_test.dart`, 3 cas |
| **La moitié lectrice** | mesurée — `KeystoreSealedKekSourceTest`, **8 cas**, dont un scellé produit *comme la passerelle le produit* |
| Vérification sur le S9 | §6 étapes 1 à 3 : `notes_tech.kek.xml` écrit, **blob 48 octets** (32 + tag GCM), **nonce 12**, aucun retour à la ligne |

### 🔴 Écart 1 — l'argument est en **octets bruts**, pas en hexadécimal

Ce document prévoyait `kekHex`, parce qu'il partait de ce que `flutter_secure_storage` contient. Mais
au point d'appel réel la KEK est **déjà matérialisée** en `Uint8List` : la repasser par une `String`
créerait une copie du secret **que Dart ne sait pas effacer** — une `String` est immuable et survit
jusqu'au ramasse-miettes. Le code Flutter prend justement soin d'effacer son `Uint8List` dès la base
ouverte. *Réintroduire une copie ineffaçable pour la commodité d'un paramètre serait défaire ce soin.*

Ce que la version Kotlin lit ne change pas : le clair scellé reste les 32 octets bruts.

### 🔴 Écart 2 — l'idempotence regarde les préférences **et** l'alias

Le document proposait `if (prefs.contains(BLOB) && prefs.contains(NONCE)) return false`. Un scellé
présent **sans sa clé Keystore** est indéchiffrable : il faut le refaire, pas le garder. Ne regarder
que les préférences condamnerait cet appareil à la couche ② pour toujours.

### ⚠️ Écart 3 — la raison donnée au `NO_WRAP` était fausse

Voir §2. La consigne reste ; sa justification est corrigée.

---

## 9. 🔴 Ce qui reste, et qui n'est PAS une décision de rédaction

### La vérification de bout en bout (§6 étapes 4-5) n'est pas faite

Elle demande une build **signée** des deux côtés : `applicationIdSuffix = ".debug"` fait qu'une build
debug du portage s'installe sous `com.filestech.notes_tech.debug` et ne peut donc pas prendre la place
de l'application Flutter. Le code du portage le dit lui-même : *« un test de migration sur base réelle
doit être mené sur une build SIGNÉE, pas en debug »*.

Ce qui est mesuré aujourd'hui : la passerelle **écrit** un scellé de la bonne forme (S9), et la couche
① **relit** un scellé écrit de cette façon (S9). Ce qui ne l'est pas : les deux **sur la même
installation**, avec les vraies données.

### ✅ La clé est identifiée, et vérifiée contre l'artefact publié

`notes_tech/android/notestech-release.jks`, alias déclaré dans `notes_tech/android/key.properties`.

| | |
|---|---|
| Certificat du keystore | `DD:B3:85:DE:E3:A4:16:AA:D9:FE:5A:46:17:3E:36:84:E0:76:2D:C4:33:C7:E8:DE:6D:B4:8F:64:ED:E6:42:E9` |
| Certificat de `notes-tech-arm64-v8a-2.0.3.apk` **publié** | `ddb385dee3a416aad9fe5a46173e3684e0762dc433c7e8de6db48f64ede642e9` |
| Verdict | **identiques** |

⚠️ Comparé à l'**APK réellement diffusé** — téléchargé depuis la release GitHub `v2.0.3` et lu par
`apksigner verify --print-certs` — et non à une valeur recopiée d'une note. *Une empreinte notée
quelque part n'est pas une empreinte vérifiée.*

### 🔴🔴 Le portage n'a **aucun `key.properties`** — il ne peut pas produire de build signée

`app/build.gradle.kts` prévoit la configuration de signature, mais le fichier est absent. Or la 3.0.0
**doit** être signée avec la clé de l'application publiée — `notes_tech/android/notestech-release.jks` —
sans quoi elle ne s'installera pas par-dessus Notes Tech, pour personne.

⚠️ **Ce n'est pas un détail de release, c'est une condition de la migration elle-même.** Sans cette
clé, il n'y a ni vérification de bout en bout, ni bascule possible.
