# Notes Tech 3.4.0 — dépôt à l'aveugle, notes à durée de vie, notes scellées à partager

> Écrit le 2026-11-09 au soir. Origine : Patrice a demandé « des supers idées […] vraiment uniques »,
> puis : « fais 2, 3, 4 ce serait génial pour la 3.4.0 + raccourcis et clavier sans apprentissage »,
> « en intégrant les upgrade AGP aussi » (déjà dans `main` : PR #51). Ses quatre réponses du même soir
> sont les options recommandées ci-dessous.
>
> **Ce document est la spécification.** Il est écrit AVANT le code pour survivre à une compaction de la
> conversation ; chaque section dit ce qui est fait (✅) au fur et à mesure.

## Faits relevés avant de concevoir (exploration du 2026-11-09)

- **Le verrou de l'appli est une barrière d'interface**, pas un chiffrement : la base s'ouvre au
  démarrage sans secret de l'utilisateur (`AppLockKeystore.kt:8-9`, `KeystoreSealedKekSource` sans
  `setUserAuthenticationRequired`). `NotesRepository.create` ne consulte aucun verrou. Écrire une note
  appli verrouillée est donc possible sans rien changer à la clé.
- **`MainActivity` ignore tout ce que porte un intent** (`sansNavigationImposee`, `MainActivity.kt:76-79`) :
  elle est exportée, aucune autre appli ne doit pouvoir y imposer une navigation. Le partage reçu passe
  par une activité à part qui dépose en mémoire (`PartagesEnAttente`, 2 min) puis lance l'appli ; rien
  n'est écrit tant que l'appli est verrouillée.
- **La sauvegarde chiffrée** (`FichierDeSauvegarde`) : en-tête de 48 octets (magie, version, Argon2id
  t=3 m=64 Mio p=1, sel 16, nonce 12) servant d'AAD, AES-256-GCM en flux, code généré de 24 signes
  Crockford (120 bits, `SecretDeSauvegarde`). Constantes privées câblées : à paramétrer pour un autre
  conteneur.
- **Réglages** dans `FlutterSharedPreferences` (`LegacyPreferences`) ; la panique les efface tous sauf
  trois clés (`PanicService.kt:691`) — un nouveau réglage y revient à sa valeur par défaut.
- **`IME_FLAG_NO_PERSONALIZED_LEARNING`** n'est posé que pour les notes de coffre (`SaisieDeCoffre`,
  `ChampDuCorps:486`), couplé au presse-papiers sensible et au glisser.
- **Room** : version 10, une seule migration additive (`MIGRATION_9_10`, `color_id`), schémas exportés,
  rejouée par `audits/verifier-schema-room-vs-flutter.py`. Le trigger `notes_au` réécrit la ligne FTS à
  tout UPDATE.
- **Aucun** raccourci, tuile, widget, ni destination « nouvelle note » ; la dictée ne démarre que d'un
  toucher sur le micro de l'éditeur.

## D-040 — Clavier sans apprentissage, pour toute l'appli, activé par défaut

- Réglage booléen `keyboard_no_learning` (défaut **vrai**, Patrice) : tous les champs de Notes Tech
  demandent au clavier `IME_FLAG_NO_PERSONALIZED_LEARNING`. La panique le remet à vrai (défaut).
- **Les champs de secret l'ont toujours**, quel que soit le réglage (phrases de coffre et de sauvegarde,
  code d'une note scellée).
- Découplé de « protégée » : le presse-papiers sensible et le glisser restent réservés aux notes de coffre.
- Honnêteté : c'est une DEMANDE au clavier ; Gboard et SwiftKey la respectent, un clavier peut l'ignorer.
  Les suggestions restent ; seul l'apprentissage est coupé.

## D-041 — Raccourcis sur l'icône : Nouvelle note, Dictée, Dépôt rapide

- Raccourcis **dynamiques**, publiés par `MainActivity.onCreate` (`publierLesRaccourcis`), aucune
  permission. Pas de `shortcuts.xml` : un raccourci statique écrit son paquet en dur, et l'appli est
  construite sous plusieurs identifiants (release, `.next`, `.next.debug`). Publiés avec le contexte de
  l'activité : libellés dans la langue choisie dans l'appli ; une appli au premier plan n'est jamais
  limitée par le service des raccourcis.
- « Nouvelle note » et « Dictée » visent une activité **non exportée** de transit qui dépose la demande en
  mémoire puis lance l'appli : `MainActivity` continue d'ignorer les intents, aucune autre appli ne peut
  imposer une création ou un micro. La demande s'exécute **après** le déverrouillage de l'appli.
- « Dictée » crée une note et ouvre le micro comme un toucher sur le bouton : sans modèle → écran
  d'installation ; sans permission → demande de permission.
- « Dépôt rapide » ouvre le dépôt à l'aveugle (D-042).

## D-042 — Dépôt à l'aveugle : écrire sans déverrouiller, sans rien pouvoir relire

- Une activité à part, en écriture seule : un champ de titre, un champ de texte, « Déposer », « Annuler ».
  Elle ne lit **rien** de la base, n'affiche aucune note, aucun dossier, aucun nombre ; après « Déposer »,
  l'écran se referme (« Note déposée dans la Boîte de réception »). **Aucun lien** vers le reste de
  l'appli : un « Ouvrir Notes Tech » prévu au départ n'a pas été fait, une porte de moins.
- Elle peut être la PREMIÈRE activité de son processus (la tuile la démarre dans un processus neuf) :
  elle pose elle-même ce que `MainActivity` pose d'ordinaire — fenêtre protégée, actions de texte des
  autres applis et classifieur coupés, langue choisie, thème, clavier sans apprentissage.
- Rien n'est écrit pendant une panique en cours (`PanicJournal.isPending`) : le texte reste à l'écran.
- Ouverte par la **tuile des réglages rapides** et le **raccourci** « Dépôt rapide ». Si le TÉLÉPHONE est
  verrouillé, Android demande d'abord son déverrouillage (`unlockAndRun`) — seul le verrou de Notes Tech
  est contourné (Patrice).
- La note va dans la **Boîte de réception**, jamais dans un coffre ; fenêtre protégée (pas de capture,
  pas d'aperçu dans les applis récentes) ; clavier sans apprentissage selon D-040.
- Pas de brouillon gardé hors de l'écran : quitter sans déposer ne laisse rien (confirmation si du texte
  a été tapé).

## D-043 — Note à durée de vie

- Durées proposées : **1 h, 24 h, 7 jours, 30 jours, après lecture** (Patrice). Menu ⋮ de l'éditeur,
  « Durée de vie… » ; « Aucune » la retire.
- « Après lecture » : la note est effacée quand on la **referme après l'avoir rouverte** (la session où
  l'option est posée ne compte pas).
- L'effacement est un effacement définitif (même chemin que « Supprimer définitivement » : index plein
  texte et liens compris, `secure_delete`). Il a lieu **à l'ouverture de l'appli et au retour sur
  l'accueil**, pas à la seconde près (pas d'alarme, pas de permission) — dit tel quel dans l'interface.
- **Hors sauvegardes et exports** (Patrice) : une restauration ne doit pas la faire revivre.
- Colonnes `expires_at INTEGER` (nullable) et `read_once INTEGER NOT NULL DEFAULT 0` : migration 10 → 11,
  additive.
- La carte montre la durée restante ; une note de coffre fermé garde sa durée de vie (la date n'est pas
  un secret : elle n'est pas chiffrée, comme `updated_at`).

## D-044 — Note scellée à partager (`.ntnote`)

- Menu ⋮ de l'éditeur, « Partager scellée… » : la note (titre, texte, couleur, étiquettes) est chiffrée
  dans un fichier `.ntnote` par un **code généré de 24 signes** (même code que la sauvegarde), affiché
  une fois ; on envoie le fichier par n'importe quelle messagerie et le code par un autre canal.
- Même chiffrement que la sauvegarde (Argon2id, AES-256-GCM, en-tête en AAD), avec sa propre magie
  (`NTSEALED`) et une taille bornée (1 Mio).
- **Le nom du fichier ne dit rien de la note** (`note-scellee-AAAAMMJJ.ntnote`) : un titre dans un nom de
  fichier fuirait par la messagerie.
- Option « Lecture unique chez le destinataire » : la note reçue y arrive avec la durée de vie « après
  lecture » (D-043). Honnête : le destinataire peut garder le fichier.
- Réception (Patrice) : « Ouvrir avec » (`ACTION_VIEW` sur `application/octet-stream`, activité à part,
  comme le partage reçu) et menu « Ouvrir une note scellée » de l'accueil. Un fichier qui n'est pas une
  note scellée est refusé poliment. Le code se tape **après** le déverrouillage ; la note s'affiche, puis
  « Ajouter » la met dans la Boîte de réception, jamais dans un coffre.
- Une note de coffre se partage scellée seulement coffre ouvert, comme l'export.

## État

- [x] **D-040 clavier** (`5e18eab`) — tests instrumentés sur le S9 : `ClavierSansApprentissageTest` (3),
  `ChampDuCorpsTest` +1, `ReglagesHeritesTest` +1, `SaisieDeCoffreTest` inchangé (8) ; la ligne du réglage
  vue dans l'écran, section Sécurité.
- [x] **D-041 raccourcis** (`5e18eab`) — `RaccourcisTest` (4, JVM). Sur le S9 (lanceur Samsung) : les trois
  raccourcis s'affichent à l'appui long, le lanceur démarre bien l'activité NON exportée (aucune
  `SecurityException`), « Nouvelle note » ouvre une note vierge côté écriture, « Dicter une note » sans
  modèle ouvre l'écran d'installation. Comme le bouton « Nouvelle note » depuis la 2.0.9, une note vide
  quittée reste (pas d'effacement des notes vides : comportement inchangé).
- [x] **D-042 dépôt** (`5e18eab`) — `DepotViewModelTest` (5, JVM : Boîte de réception, `confirmVerified`
  — rien d'autre n'est appelé —, panique en cours, échec qui garde le texte, limites). Sur le S9, **avec
  le verrou de l'appli activé** (code posé pour l'essai) : dépôt par le raccourci sans code, l'appli
  demande toujours son code à l'ouverture, la note est dans la Boîte de réception ; la tuile
  (`cmd statusbar add-tile` / `click-tile`) ouvre le dépôt. **Non essayé sur appareil** : la tuile
  téléphone verrouillé (`unlockAndRun`) — le S9 n'a pas de code d'écran, en poser un change un réglage
  global.
- [ ] D-043 durée de vie · [ ] D-044 scellée
- [ ] Textes 5 langues · [ ] version 3.4.0 (507) · [ ] journaux 5071-5073 · [ ] README, SECURITY, CGU/confidentialité
- [ ] Audits (3 axes, sécurité des nouvelles entrées, i18n, cohérence) · [ ] relecture externe · [ ] S9 · [ ] CI
- [ ] Avant le tag : build F-Droid sous AGP 9.5 (voir d'abord comment F-Droid a construit SMS 1.30.0)
