# Isolation pendant le chantier

> **Règle** : tant que la phase 8 n'est pas close, l'application Kotlin **ne remplace pas** Notes
> Tech et **ne touche pas** aux données réelles de l'utilisateur.
>
> Cette règle n'est pas une consigne à retenir. Elle est appliquée par le système.

---

## 1. Comment l'isolation est obtenue

`app/build.gradle.kts` compose l'`applicationId` à partir d'une propriété Gradle qui vaut `false`
par défaut :

| Commande | `applicationId` | Effet à l'installation |
|---|---|---|
| `./gradlew assembleRelease` | `com.filestech.notes_tech.next` | **s'installe à côté** |
| `./gradlew assembleDebug` | `com.filestech.notes_tech.next.debug` | s'installe à côté |
| `./gradlew assembleRelease -Pnotestech.replaceInstalledApp=true` | `com.filestech.notes_tech` | **prend la place** |

Android isole les applications par `applicationId`. Avec le suffixe `.next`, la build Kotlin
obtient un UID différent, donc :

- elle **ne peut pas lire** `/data/user/0/com.filestech.notes_tech/` — ni la base, ni les
  `SharedPreferences` ;
- elle **ne peut pas accéder** aux clés de l'`AndroidKeyStore` de l'autre application, qui sont
  liées à l'UID ;
- elle démarre donc sur une base vide, par le chemin « première installation ».

Ce n'est pas de la discipline, c'est une propriété du système d'exploitation. Rien de ce qui est
écrit dans ce dépôt ne peut abîmer les notes réelles tant que la propriété vaut `false`.

Le `namespace` Kotlin, lui, **ne change pas** : il reste `com.filestech.notes_tech`. C'est lui qui
détermine le nom des classes ; le confondre avec l'`applicationId` mènerait à renommer des alias
Keystore. Cf. [01-DECISIONS.md](01-DECISIONS.md) D-007.

## 2. Ce que ça implique pour les tests

L'isolation a un coût, et il faut le regarder en face : **la build isolée ne peut pas exercer
l'acquisition réelle de la KEK** (couches ① et ②), puisqu'elle n'a accès ni aux
`SharedPreferences` ni au Keystore de l'application d'origine.

La vérification se scinde donc en deux, et les deux comptent :

### Ce qui se teste dans la build isolée — l'essentiel, en continu

Sur une base **fabriquée par le test**, avec une KEK connue :

- adoption du schéma hérité par Room (la validation passe ou échoue, sans ambiguïté) ;
- lecture des dossiers, des notes, des liens ;
- réponse de l'index FTS5, y compris le masquage des notes verrouillées ;
- écritures, corbeille, purge, cascades de clés étrangères ;
- ouverture d'un coffre à partir de colonnes `vault_*` produites par la version Flutter.

Une base d'essai peut être extraite d'une installation Flutter de test, puis **poussée dans le
sandbox de la build isolée**. C'est la manière propre de travailler sur des données réalistes sans
travailler sur les données réelles.

### Ce qui ne se teste qu'à la bascule — une fois, sous contrôle

Sur le **S9 de test**, jamais sur le S24 FE :

1. installer Notes Tech **2.0.3**, créer du contenu, un coffre passphrase, un coffre PIN ;
2. installer la passerelle **2.0.4**, l'ouvrir une fois ;
3. installer la build `-Pnotestech.replaceInstalledApp=true` ;
4. dérouler le protocole de [03-KEK-ACQUISITION.md](03-KEK-ACQUISITION.md) §4.

⚠️ `connectedAndroidTest` **efface les données de l'application**. Il ne tourne jamais sur le
téléphone réel.

## 3. Les deux applications cohabitent, et c'est voulu

Pendant tout le chantier, le lanceur affiche :

- **Notes Tech** — la Flutter, en service, avec les vraies notes ;
- **Notes Tech (Kotlin)** — le portage, avec ses propres données de test.

Elles portent des libellés distincts délibérément : deux entrées identiques mènent tôt ou tard à
tester la mauvaise.

## 4. Le geste de bascule

Il n'aura lieu qu'une fois, et seulement quand :

- [ ] [05-PARITE.md](05-PARITE.md) ne comporte plus de case vide sans justification écrite ;
- [ ] le protocole de migration de [03-KEK-ACQUISITION.md](03-KEK-ACQUISITION.md) §4 est passé sur
      le S9, **y compris** le cas où la KEK est introuvable — en vérifiant que la base est
      toujours là après le refus ;
- [ ] la passerelle 2.0.4 est publiée **et** installée depuis assez longtemps pour que les
      utilisateurs y soient passés ;
- [ ] une revue externe a été passée sur le delta complet.

Tant qu'une de ces conditions manque, `notestech.replaceInstalledApp` reste à `false`, et Notes
Tech reste la version Dart. C'est la version qui marche.
