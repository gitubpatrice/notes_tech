# Acquisition de la KEK — le point de risque n°1

> La KEK est la clé 256 bits qui chiffre la base SQLCipher. Sans elle, les notes de l'utilisateur
> sont perdues **définitivement** : le fichier reste sur le disque, chiffré par une clé que plus
> personne ne possède.
>
> Ce document décrit comment l'application Kotlin la récupère chez un utilisateur qui vient de
> Notes Tech 2.0.3. C'est le point qui décide de la réussite du portage.

---

## 1. Le problème

Côté Flutter, la KEK est stockée par `flutter_secure_storage` **10.3.1**, sous la clé logique
`notes_tech.vault.kek.v1`, encodée en hexadécimal (64 caractères ASCII)
— `services/security/vault_service.dart:38` et `:104`.

`flutter_secure_storage` chiffre elle-même cette valeur avec son propre schéma : une clé AES
enveloppée par une clé RSA de l'`AndroidKeyStore`. Une application Kotlin ne peut pas simplement
« lire la préférence » : il faut rejouer tout le déballage.

**La règle qui prime sur tout le reste** — et le code Dart mettait déjà en garde contre exactement
ça (`vault_service.dart:53-60`, `resetOnError: false`) :

> Si la KEK est introuvable **alors que `notes_tech.db` existe sur le disque**, l'application
> **refuse de démarrer**. Elle ne génère jamais une KEK fraîche.

Un `getOrCreate` naïf ferait exactement l'inverse, et détruirait silencieusement toutes les notes.

## 2. La conception : trois couches

### Couche ① — Alias natif, écrit par la release passerelle 2.0.4 *(chemin nominal)*

Plutôt que de réimplémenter la cryptographie d'une bibliothèque tierce dans le chemin critique,
on fait faire la migration **par l'application Flutter elle-même**, pendant qu'elle est encore là.

Une release **2.0.4** de `notes_tech` re-scelle la KEK via `KeystoreBridge.kt` — 235 lignes de
Kotlin **déjà écrites, déjà en production**, dont l'application native reprendra le code tel quel :

- alias `notes_tech.db.kek.v1`, AES-256-GCM, `AndroidKeyStore`
- valeur scellée écrite dans un `SharedPreferences` ordinaire (le secret est protégé par le
  Keystore, pas par le fichier)
- **idempotent** : si l'alias existe déjà, ne rien faire
- la KEK reste **aussi** dans `flutter_secure_storage` — on ajoute un chemin, on n'en retire aucun

Côté Kotlin, la lecture est alors triviale et n'utilise que des API de plateforme.

### Couche ② — Lecture directe du format `flutter_secure_storage` *(secours)*

Pour l'utilisateur qui saute la 2.0.4 et passe de 2.0.3 à 3.0.0 directement.

Constantes relevées dans les sources de `flutter_secure_storage 10.3.1` installées dans
`J:\Pub\Cache\hosted\pub.dev\flutter_secure_storage-10.3.1`, pour la configuration **exacte** de
Notes Tech (`AndroidOptions(resetOnError: false)`, tout le reste par défaut) :

**La valeur chiffrée**

| Élément | Valeur | Source |
|---|---|---|
| Fichier de préférences | `FlutterSecureStorage` | `FlutterSecureStorageConfig.java:15` |
| Clé | `VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIHNlY3VyZSBzdG9yYWdlCg` + `_` + `notes_tech.vault.kek.v1` | `:16`, `FlutterSecureStorage.java:51` |
| Encodage | `Base64` avec les indicateurs **par défaut** (retours à la ligne inclus) | `FlutterSecureStorage.java:129` |
| Enveloppe | `IV (12 octets) ‖ AES-GCM(ciphertext ‖ tag 128 bits)` | `StorageCipherImplementationGCM.java:66-82`, `:98-103` |
| Clair | les 64 caractères ASCII hexadécimaux de la KEK | `vault_service.dart:104` |

**La clé AES, enveloppée**

| Élément | Valeur | Source |
|---|---|---|
| Fichier de préférences | `FlutterSecureKeyStorage` | `FlutterSecureStorageConfig.java:184` |
| Clé | `AESVGhpcyBpcyB0aGUga2V5IGZvciBhIHNlY3VyZSBzdG9yYWdlIEFFUyBLZXkK` | `StorageCipherImplementationGCM.java:21` |
| Taille | **16 octets — AES-128**, pas 256 | `StorageCipherImplementationGCM.java:18` |
| Déballage | `Cipher.getInstance("RSA/ECB/OAEPPadding", "AndroidKeyStoreBCWorkaround")`, `UNWRAP_MODE` | `KeyCipherImplementationRSAOAEP.java:49`, `KeyCipherImplementationRSA18.java:64` |
| Alias `AndroidKeyStore` | `com.filestech.notes_tech.FlutterSecureStoragePluginKeyOAEP` | `KeyCipherImplementationRSAOAEP.java:30` |

> 🔶 **Une constante reste à lire avant d'écrire cette couche** :
> `KeyCipherImplementationRSAOAEP.getAlgorithmParameterSpec()`. L'`AndroidKeyStore` impose un
> `OAEPParameterSpec` particulier (le MGF1 n'y utilise pas le même condensat que l'algorithme
> principal). **Ne pas l'écrire de mémoire** — la lire dans le fichier au moment de coder.

**Combinaison héritée.** Un appareil qui n'aurait jamais migré depuis la 9.x utiliserait
`AES_CBC_PKCS7Padding` + `RSA_ECB_PKCS1Padding` sous un autre alias. Vu que
`migrateOnAlgorithmChange` vaut `true` par défaut et que Notes Tech est sur la 10.x depuis la
v0.9.10, ce cas ne devrait pas exister. La couche ② le **détecte et passe la main à la couche ③** —
elle ne tente pas de deviner.

### Couche ③ — Refus honnête

Aucune KEK, et une base sur le disque ⇒ écran d'erreur dédié, sans aucune écriture :

- ce qui se passe : les notes sont là, verrouillées, **intactes**
- quoi faire : installer la 2.0.4, l'ouvrir une fois, puis remettre à jour
- ce que l'application ne fera pas : effacer quoi que ce soit

**Aucune base sur le disque** = première installation. Là, et là seulement, une KEK est générée.
C'est la présence du fichier qui distingue les deux situations, pas l'absence de clé.

## 3. Pourquoi trois couches et pas une

| | Sans couche ① | Sans couche ② | Sans couche ③ |
|---|---|---|---|
| Conséquence | la cryptographie d'une bibliothèque tierce se retrouve dans le chemin critique de chaque démarrage | l'utilisateur qui saute une version doit réinstaller une ancienne version | un `getOrCreate` détruit les notes en silence |
| Gravité | fragile | pénible | **irréversible** |

La couche ③ est celle qui ne peut pas être omise. Les deux autres existent pour qu'on l'atteigne
le moins souvent possible.

## 4. Critère de sortie

La phase 2 n'est pas finie tant que ce test instrumenté n'est pas vert :

1. installer Notes Tech **2.0.3**, créer des dossiers, des notes, un coffre passphrase, un coffre PIN ;
2. installer la **2.0.4** par-dessus, l'ouvrir une fois, la fermer ;
3. installer la build Kotlin par-dessus ;
4. vérifier que les notes, les dossiers, les liens et la recherche FTS5 répondent — et que les
   deux coffres s'ouvrent avec leurs secrets d'origine.

Puis le même parcours **en sautant l'étape 2**, pour exercer la couche ②.
Puis en sautant 2 **et** en effaçant la valeur `flutter_secure_storage`, pour vérifier que la
couche ③ refuse proprement — et **surtout que la base est toujours là après**.

⚠️ Sur le S9 de test uniquement. `connectedAndroidTest` efface les données de l'application :
**jamais sur le S24 FE**, qui est le téléphone réel.
