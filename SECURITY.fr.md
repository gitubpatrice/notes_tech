# Politique de sécurité — Notes Tech

*English version: [SECURITY.md](SECURITY.md)*

**Version actuelle : v3.1.0 — octobre 2026.** Notes Tech 3.0 est une réécriture en Kotlin. Le journal
de sécurité des versions Flutter 1.x et 2.x est conservé tel quel, dans
[`SECURITY.fr.md` au tag v2.0.9](https://github.com/gitubpatrice/notes_tech/blob/v2.0.9/SECURITY.fr.md).

## v3.1.0 — appui long, mode lecture, couleur des notes (2026-10-10)

- **Mettre à la corbeille ou effacer une note de coffre ne demande aucune clé** : la ligne part, blob
  scellé compris. L'appui long sur une note, et la nouvelle « Supprimer définitivement » de l'éditeur,
  vérifient donc le coffre **au moment d'agir, sur une relecture de la note** : coffre fermé, le geste
  demande le secret et s'exécute une fois le coffre ouvert. Prouvé par sabotage : sans ce contrôle, les
  tests de refus échouent.
- **La couleur d'une note est stockée hors de l'enveloppe du coffre**, comme ses étiquettes (dans la base
  SQLCipher). Les listes ne l'affichent sur une note de coffre que coffre ouvert, et la masquent à
  nouveau au verrouillage automatique. La politique de confidentialité le dit désormais, pour les
  étiquettes aussi.
- **Le mode lecture** garde les protections d'une note de coffre (FLAG_SECURE, session, verrouillage
  automatique), n'ouvre aucun clavier, et son rendu n'est pas sélectionnable — aucun chemin de copie qui
  contournerait le presse-papiers protégé.
- **Schéma 10** : une colonne facultative (`color_id`), ajoutée par une migration rejouée en test sur une
  base Flutter et sur une base adoptée par la 3.0.0 ; aucune note ni aucun blob scellé ne change.
- Relu modification par modification par deux modèles externes et un audit pré-publication sur trois
  axes (aucun critique, aucun élevé ; un moyen corrigé : un geste en attente d'un coffre disparaît avec
  sa feuille de déverrouillage).

## v3.0.0 — réécriture en Kotlin et audit de sécurité complet (2026-09-26)

Le portage a été audité avant publication selon une méthode de chercheurs et de panel adverse : cinq
chercheurs, chacun sur un composant et un angle, puis trois vérificateurs par candidat chargés de le
réfuter. **Aucun constat critique ni élevé. 3 moyens, 13 faibles — tous corrigés, chacun avec un
test et un contrôle négatif** (le défaut remis en place pour vérifier que le test le voit). Le
rapport complet est dans [`audits/securite-2026-09-26.md`](audits/securite-2026-09-26.md).

Les trois constats moyens :

- **V1** — refermer un coffre laissait la note déchiffrée à l'écran dans l'éditeur. L'éditeur
  l'efface désormais et redemande le secret.
- **K1** — un texte supprimé, ou déplacé dans un coffre, restait dans l'index plein texte. FTS5
  fonctionne désormais en mode `secure-delete`, SQLite avec `secure_delete = ON`, et les index
  existants sont purgés une fois.
- **K4** — avant Android 9, la clé d'un coffre à code ne peut pas être liée au déverrouillage du
  téléphone : la limite de cinq essais ne tient donc pas face à un téléphone saisi. Aucun nouveau
  coffre à code n'y est plus créé ; un coffre existant s'ouvre toujours, et le dit.

## Versions prises en charge

Seule la **dernière version publiée** reçoit des correctifs de sécurité : l'application n'a aucun
accès réseau et ne se met jamais à jour d'elle-même, un correctif ne peut donc arriver que par une
nouvelle version.

## Signaler une vulnérabilité

Si vous pensez avoir trouvé un problème de sécurité dans Notes Tech, merci de **ne pas ouvrir
d'issue GitHub publique**. Utilisez le signalement privé de GitHub — onglet *Security* du dépôt, puis
*Report a vulnerability* (https://github.com/gitubpatrice/notes_tech/security/advisories/new) — ou
écrivez à :

📧 **contact@files-tech.com**

Objet : `[SECURITY] Notes Tech — <résumé court>`

Indiquez :
- une description du problème et de son impact possible ;
- les étapes pour le reproduire (ou une preuve de concept) ;
- la version concernée (Réglages → À propos de Notes Tech) ;
- un moyen de vous recontacter.

Vous recevrez un accusé de réception sous **72 heures**. Un calendrier de divulgation coordonnée sera
convenu si le problème est confirmé.

## Périmètre

### Dans le périmètre
- Le code de l'application (`app/src/main/`), y compris le pont JNI
  `app/src/main/cpp/notes_stt_jni.cpp`
- La cryptographie : clé de la base, clés des coffres et du verrouillage, irréversibilité du mode
  panique
- La gestion des permissions (`RECORD_AUDIO`, biométrie)
- La gestion des fichiers : import du modèle, exports, traversée de chemin

### Hors périmètre
- Les problèmes du code tiers — y compris les sources de whisper.cpp incluses dans le dépôt — sauf si
  l'usage qu'en fait l'application crée le problème (merci de le signaler aussi à l'amont).
- Les problèmes qui exigent un appareil rooté ou un logiciel malveillant déjà installé avec des
  privilèges élevés.
- L'ingénierie sociale contre l'utilisateur.
- Le déni de service par des notes ou des fichiers volontairement démesurés.

## Modèle de menace

Notes Tech s'adresse à qui veut des notes **strictement locales** avec de solides garanties
cryptographiques. Trois classes d'adversaires sont prises en compte.

### 1. Perte ou vol du téléphone
- **Confidentialité au repos** : SQLCipher (AES-256) pour la base, AES-256-GCM pour les notes d'un
  coffre. La clé de la base est scellée par le Keystore Android (matériel sur les appareils récents).
- Un coffre ajoute un second facteur (phrase secrète ou code) au verrouillage du téléphone ; le
  verrouillage de l'application en ajoute un devant toute l'application.

### 2. Contrainte (fouille, « donnez-moi votre téléphone », contrôle à une frontière)
- **Mode panique** : un effacement confirmé qui exécute une séquence ordonnée et déterministe
  (ci-dessous).
- **Effacement automatique du code** : 5 échecs sur un coffre à code suppriment la clé de ce coffre,
  de façon atomique, et l'opération reprend au lancement suivant si elle est interrompue.
- **Aucun facteur biométrique sur les clés des coffres** (`setUserAuthenticationRequired(false)`) : le
  code est le seul facteur — on peut forcer un doigt sur un capteur. La biométrie n'existe que pour
  le **verrouillage de l'application**, et elle est désactivée par défaut.

### 3. Logiciel malveillant dans une autre application du téléphone
- Aucune permission `INTERNET` : une dépendance compromise n'a aucune voie réseau standard pour
  envoyer les notes.
- Aucun service au premier plan, aucune notification, aucun récepteur au démarrage : surface
  d'attaque minimale.
- `FLAG_SECURE` (activé par défaut) bloque les captures d'écran et l'aperçu des applications récentes.
- `allowBackup=false` et `dataExtractionRules` bloquent la sauvegarde Android et les copies lors d'un
  transfert d'appareil.
- Dans une note de coffre, la copie passe par une entrée du presse-papiers marquée sensible, effacée
  au bout de 60 secondes, et le clavier est prié de ne rien apprendre
  (`IME_FLAG_NO_PERSONALIZED_LEARNING`).

## Briques cryptographiques

- **Argon2id** (RFC 9106, Bouncy Castle) : `m = 64 Mio, t = 3, p = 1`, sortie de 32 octets pour une
  phrase secrète ; `m = 32 Mio, t = 2` pour le code d'un coffre et celui du verrouillage, dont les
  clés Keystore et les limites d'essais sont la défense principale.
- **AES-256-GCM** pour le contenu des notes avec **AAD = identifiant de la note**, et pour
  l'enveloppe de la clé de coffre avec **AAD = identifiant du dossier** : un bloc chiffré ne peut pas
  être rejoué dans une autre note ou un autre dossier.
- **Vérificateur à temps constant** pour détecter un mauvais secret sans tenter de déchiffrer les
  notes.
- **Clé de la base (KEK)** : 32 octets aléatoires, scellés par une clé AES-256-GCM de
  l'`AndroidKeyStore`. SQLCipher 4 (AES-256, HMAC-SHA512).
- **Clés des coffres à code** : une clé `AndroidKeyStore` par coffre, liée au déverrouillage du
  téléphone (`setUnlockedDeviceRequired`, Android 9 et plus).
- **Verrouillage de l'application** : le code passe par Argon2id, puis par une clé HMAC-SHA256 gardée
  par le Keystore ; cinq essais libres, puis des délais qui doublent de 30 secondes à une heure. Le
  déverrouillage biométrique facultatif utilise sa propre clé Keystore, utilisable seulement par une
  biométrie forte (classe 3) dans un `CryptoObject`.

## Mode panique — ordonné, en plusieurs étapes

Réglages → Mode panique, confirmé en tapant le mot demandé (`EFFACER` en français, `WIPE` en anglais). La séquence est déterministe et au mieux : une
étape en échec n'arrête pas les suivantes, elle est consignée, et l'écran final signale un effacement
incomplet. Un journal écrit avant la première étape fait **reprendre au lancement suivant** une
séquence interrompue.

1. **Fenêtre protégée** — `FLAG_SECURE` forcé
2. **Dictée** — enregistrement interdit et celui en cours abandonné, avant tout le reste
3. **Presse-papiers** — vidé
4. **Coffres** — chaque coffre ouvert verrouillé, clés effacées de la mémoire
5. **Clés des coffres à code** — chaque clé Keystore `vault_pin_*` supprimée, orphelines comprises
6. **Clés du verrouillage** — sa clé de vérification du code et sa clé biométrique supprimées
7. **Clé de la base** — détruite : dès lors, la base n'est plus que du bruit
8. **Exports** — archives portant le texte intégral des notes effacées
9. **Enregistrements** — enregistrements de dictée effacés
10. **Base** — fermée, en-tête écrasé (16 Mio), fichier et annexes supprimés
11. **Modèle vocal** — le modèle importé supprimé
12. **Anciens modèles** — fichiers de modèles laissés par les versions ≤ 1.1.6
13. **Préférences** — effacées, sauf `secure_window_enabled`, `db_encrypted_v1` et le journal de la
    panique
14. **Cache** — aperçus, fichiers temporaires, restes de bibliothèques

## Limites acceptées

- **La clé de la base est utilisable téléphone verrouillé**, par du code exécuté sous l'identité de
  l'application. Elle n'est volontairement pas liée au déverrouillage : Android supprime une telle clé
  quand on retire le verrouillage d'écran, ce qui détruirait la base avec elle. Les coffres gardent
  leur propre secret.
- **Une copie de la clé de la base gardée par les versions 2.x** (`flutter_secure_storage`) est
  laissée en place : à partir d'Android 12, c'est elle qui récupère la base quand Android a supprimé la
  clé créée par la passerelle 2.x. Seul le mode panique la retire.
- **Retirer le verrouillage d'écran du téléphone supprime les clés des coffres à code** (Android le
  fait pour les clés liées au déverrouillage) : leurs notes deviennent illisibles. Le choix du mode
  prévient avant la création d'un coffre à code ; un coffre à phrase secrète ne dépend pas du
  verrouillage d'écran.
- **Un coffre à code créé avant Android 9** (par une version 2.x) ne tient pas sa limite de cinq
  essais face à un téléphone saisi. L'application le dit sur ce coffre.
- Une copie de la mémoire d'un téléphone rooté et déverrouillé, pendant l'usage, peut révéler du
  texte en clair.
- Un attaquant déterminé disposant d'exploits noyau sur mesure est hors périmètre.
- `FLAG_SECURE` est activé par défaut mais peut être désactivé dans les Réglages.

## Divulgation responsable

Par défaut, nous suivons une fenêtre de divulgation de 90 jours :
1. **Jour 0** : réception de votre signalement.
2. **Jours 0 à 7** : premier tri, gravité attribuée.
3. **Jours 7 à 60** : correctif développé, testé, audité.
4. **Jours 60 à 90** : publication de la version corrigée, CVE publique le cas échéant.
5. **Après 90 jours** : vous êtes libre de publier votre analyse.

Les problèmes critiques (extraction de clé, exfiltration complète des données) peuvent être corrigés
plus vite.

## Contrôles avant une publication

- Tests unitaires (JVM) et tests instrumentés sur de vrais téléphones (Android 10 et 16)
- Android lint, detekt, ktlint
- [`tools/check-manifest-permissions.py`](tools/check-manifest-permissions.py) sur le manifeste
  **fusionné** de la release : aucune permission réseau ne doit y apparaître
- Audit de sécurité avec panel adverse pour les changements importants, et relectures externes

## Décisions de conception

- **Écrasement de l'en-tête de la base limité à 16 Mio** : détruire la clé Keystore juste avant rend
  déjà toute la base illisible. Écraser le fichier entier n'apporte rien sur une mémoire flash à
  répartition d'usure, où les blocs physiques ne correspondent plus aux blocs logiques.
- **Aucune authentification de l'utilisateur sur les clés des coffres à code** : le code de
  l'application est le seul facteur du coffre ; exiger une biométrie exposerait l'utilisateur à une
  empreinte forcée, et une clé liée à la biométrie survit à un redémarrage.
- **AAD = identifiant du dossier / de la note** : aucune confusion possible entre contextes
  cryptographiques distincts.
- **Aucune liaison au déverrouillage sur la clé de la base** — voir *Limites acceptées*.

---

**Code source** : https://github.com/gitubpatrice/notes_tech
**Licence** : Apache License 2.0
