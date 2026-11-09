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
  verrouillé, Android demande d'abord son déverrouillage — seul le verrou de Notes Tech est contourné
  (Patrice). Depuis Android 14 par le `PendingIntent` de `startActivityAndCollapse`, qui attend le
  déverrouillage ; avant, par `unlockAndRun` (voir « Tuile » dans l'état).
- La tuile est **« active »** (`ACTIVE_TILE`) : liée au toucher seulement, jamais parce que le volet
  l'affiche.
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
- Même chiffrement que la sauvegarde (Argon2id, AES-256-GCM, en-tête de 48 octets en AAD), avec sa
  propre magie (`NTSEALED`, 8 octets comme `NTBACKUP`) et sa propre borne : **4 Mio** (et non 1 Mio comme
  écrit d'abord : une note peut faire 500 000 caractères, jusqu'à 1,5 Mo en UTF-8, plus le JSON).
- **Réalisation** (plan écrit avant le code, 2026-11-09 au soir) :
  - `FichierDeSauvegarde` reçoit la magie et la borne en PARAMÈTRES, avec les valeurs de la sauvegarde par
    défaut : la sauvegarde ne change pas d'un octet, ses tests restent tels quels.
  - `data/scellee/FichierDeNoteScellee` : `sceller(note, secret): ByteArray` et `ouvrir(octets, secret)`,
    en mémoire (une note est petite) ; JSON `android.util.JsonWriter/Reader` comme la sauvegarde,
    `{"format":"notes-tech-sealed-note","version":1,"title","content","color","tags","readOnce"}` ;
    limites d'une note à l'ouverture (titre 200, texte 500 000) ; tests instrumentés.
  - Code : `SecretDeSauvegarde.nouveauCode()` (24 signes, 120 bits) et sa saisie tolérante.
  - Envoi : menu ⋮ de l'éditeur « Partager scellée… » → feuille (explication, interrupteur « Lecture
    unique chez le destinataire », « Créer ») → scellement hors du fil principal (Argon2 ≈ 1 s) → fichier
    dans `cache/exports/<instant>/note-scellee-AAAAMMJJ.ntnote` (purgé au démarrage et par la panique,
    servi par le `FileProvider` des exports) → le code s'affiche, fenêtre protégée, puis « Partager le
    fichier » (`ui/common/Partage.kt`).
  - Réception : `OuvrirUneNoteScelleeActivity`, exportée, `ACTION_VIEW` sur `application/octet-stream`,
    `content://` d'une autre appli seulement (même garde que le partage reçu), lecture bornée (4 Mio,
    délai), magie vérifiée — un autre fichier : un message bref et rien d'autre. Sinon dépôt en mémoire
    (`DepotEnMemoire`) puis ouverture de l'appli ; menu ⋮ de l'accueil « Ouvrir une note scellée » →
    sélecteur SAF → même dépôt. Après le déverrouillage, un dialogue demande le code ; code juste → note
    créée dans la Boîte de réception (jamais un coffre), couleur et étiquettes reprises, « après lecture »
    si demandé, puis l'éditeur s'ouvre ; code faux ou fichier abîmé → même message, on peut réessayer.
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

> **Audits et relectures (2026-11-09)** — tous appliqués avant la PR, sauf ce que « Limites connues » dit.
> - **Relecture externe GPT** (0,024 $) : 3 défauts corrigés — note reçue créée en UN insert (couleur et
>   « après lecture » compris), lecture d'un fichier scellé bornée (une à la fois, fil dédié, flux FERMÉ
>   passé le délai, lecture de 0 octet refusée, toute `RuntimeException` d'un fournisseur rattrapée).
> - **Cohérence** (82/100) : C1-C7, C9, C10 appliqués (code oublié à toute sortie de la feuille, rappel du
>   réglage clavier sans défaut, sélecteur dans son propre ViewModel, `purgerSansEchec`, purge sous
>   `repeatOnLifecycle(STARTED)`, KDoc replacés, « … » retirés) ; C8 (idiomes d'événement) laissé.
> - **Traductions** : aucun grave ; 3 moyens corrigés (it, de, journaux de-DE).
> - **3 axes** : 0 critique, 0 élevé, 7 moyens. M3 déjà corrigé par la relecture externe ; **M4** un second
>   fichier scellé attend la fin du premier ; **M5** `FLAG_SECURE` dès la première image sur les dialogues
>   d'un code (les deux scellés ET les trois de la sauvegarde, même défaut : `ProprietesDeDialogueSecret`) ;
>   **M7** étiquettes et titre d'un fichier fabriqué normalisés comme à l'import ; **M1/M2** l'horloge du
>   téléphone et le vrai moment de l'effacement « après lecture » dits dans la feuille et SECURITY.
> - **Tests** : `NotesScelleesRecuesTest` (3 : faux puis bon code → UNE note avec couleur, étiquettes,
>   lecture unique ; pas de doublon ; le second fichier attend), un fichier fabriqué dans
>   `FichierDeNoteScelleeTest`, `ReglagesTest` avec la ligne du clavier (2 échecs de la suite complète :
>   le nouvel interrupteur, « Jamais » descendu sous l'écran). 106 cas des classes touchées verts sur le S9.
>
> **Tuile — deux défauts trouvés et corrigés après la fusion de #53 (essais du 2026-11-09 au soir)** :
> - **M6 mesuré** (ma première mesure visait un paquet inexistant, `.debug` au lieu de `.next.debug` : sa
>   conclusion « One UI 2 refuse `add-tile` » était fausse). Tuile ajoutée, processus tué : **le seul volet
>   des notifications** le redémarrait (S9 : `[]` → `[26370]`), donc purgeait `cache/exports` — un `.ntnote`
>   ou un export encore attendu par l'appli destinataire pouvait disparaître. **Corrigé** : tuile
>   `ACTIVE_TILE`, liée au toucher seulement ; état « au repos » posé à l'ajout et à chaque liaison.
>   Mesuré ensuite : volet ouvert sans démarrage du processus sur le S9 (Android 10), un émulateur
>   Android 11 et un Android 16 ; la tuile s'affiche normalement (icône, « Dépôt rapide », au repos).
> - **Tuile touchée TÉLÉPHONE VERROUILLÉ : le dépôt ne s'ouvrait jamais sur Android 16** (jamais essayé
>   jusque-là). Le code était demandé, puis le téléphone se déverrouillait sur l'écran d'accueil : le
>   rappel d'`unlockAndRun` ne revenait pas. Même résultat avec l'ancienne tuile (non active) : défaut de
>   la 3.4.0 fusionnée, pas de la correction de M6. **Corrigé** : depuis Android 14, plus
>   d'`unlockAndRun` — `startActivityAndCollapse(PendingIntent)` attend lui-même le déverrouillage.
>   Mesuré sur émulateur Android 16, code 1111 : code demandé, dépôt lancé APRÈS le déverrouillage ; sans
>   le code (demande refermée) rien ne s'ouvre, ni ne se lance plus tard. Sur émulateur Android 11, avec
>   la tuile active, `unlockAndRun` marche : code, puis dépôt.
>
> **S24 (Android 16, One UI 8, accord de Patrice du 2026-11-09)** : suite instrumentée complète sur la build
> fusionnée — 589 cas, 585 réussis, 3 ignorés, 1 échec. Ignoré à part : `AppLockKeysTest` (une empreinte
> est enregistrée). Le témoin du Keystore matériel PASSE, comme il le doit sur un vrai téléphone. L'échec :
> `RechercheDeBoutEnBoutTest.depuis_l_ecran_de_recherche` (la note cherchée n'apparaît pas en 15 s) — le
> test tape pendant que le clavier Samsung démarre (l'écran de recherche le demande à l'ouverture ;
> « getTextBeforeCursor on inactive InputConnection »), le test jumeau sans clavier automatique passe en
> 1,5 s, et la classe passe 3 fois sur 3 sur émulateur Android 16 (Gboard), sur le S9 et en CI. Une
> seconde exécution sur le S24 a été refusée par le garde-fou. Les tests du presse-papiers y laissent des
> textes d'essai dans l'historique Samsung.
>
> **Essais à la main de Patrice sur le S24, build de `main` après #55 (`1bd3289`), même soir** — « tous les
> tests sont ok » : une note trouvée par l'écran de recherche, au clavier Samsung (l'échec ci-dessus était
> donc le test, pas l'appli) ; la tuile ajoutée par le volet, **téléphone verrouillé : son code demandé,
> puis le dépôt ouvert** ; demande de code annulée : rien ne s'ouvre. Puis, mesuré par `adb` : processus
> tué, volet ouvert, l'appli ne redémarre pas (`[]` → `[]`). La build de test se désinstalle à la main
> (le garde-fou refuse `adb uninstall` sur le S24).
>
> **Limites connues (non corrigées, consignées)** :
> - Mes fichiers (Samsung) n'ouvre pas `.ntnote` (type vide, il conclut seul) ; le menu « Ouvrir une note
>   scellée » marche partout.
> - Le partage reçu (3.2.0) lit encore sans délai qui ferme le flux : à durcir plus tard.
> - Faibles de l'audit laissés : `DepotActivity` garde un brouillon en mémoire après Home (voulu : copier
>   un numéro ailleurs puis revenir) ; collage de plus de 500 000 caractères ignoré sans message ; l'écran
>   d'export ne dit pas que les notes à durée de vie en sont exclues ; pas d'index partiel pour la purge.

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
  (`cmd statusbar add-tile` / `click-tile`) ouvre le dépôt. La tuile téléphone verrouillé, essayée plus
  tard sur émulateurs (Android 11 et 16, code posé) : défaut trouvé et corrigé, voir « Tuile » ci-dessus.
  Puis **essayée sur un vrai téléphone verrouillé** : le S24 de Patrice, après #55 — code demandé, puis
  dépôt ouvert.
- [x] **D-043 durée de vie** — données (`3ac16da`) : schéma 11, migration 10 → 11 additive, vérifiée par le
  script de CI et sur le S9 (`MigrationVersDixTest`, dont le chemin réel d'une base de la 3.3.0) ; purge =
  le DELETE de « Supprimer définitivement » ; export ZIP et sauvegarde les excluent. Interface : menu ⋮ de
  l'éditeur « Durée de vie… », feuille à boutons radio, badge et phrase TalkBack sur la carte (aussi sur
  une note de coffre fermé : la durée n'est pas un secret), purge à l'ouverture (`StartupViewModel`) et
  chaque minute sur la liste (`HomeRoute`). « Après lecture » efface à `onCleared` de l'éditeur, pas à la
  disposition de l'écran (une rotation l'aurait effacée), après le dernier enregistrement ; annoncé à
  l'ouverture. detekt a fait sortir la durée de vie de `NotesRepository` (`DureesDeVie`, seuil de
  fonctions) et regrouper couleur + durée de vie en `FeuillesDeLaFiche` (seuil de paramètres de l'écran).
  Tests : `ResteDeVieTest` (5) et `DureesDeVieTest` (5) sur la JVM ; sur le S9 `LectureUniqueTest` (3 :
  effacée à la fermeture ; contrôle, choisie dans la session ; témoin sans durée), `EditeurTest` (36),
  `RechercheDansLaNoteEcranTest` (5), `FermetureDuCoffreTest` (9), `LiensDansUnCoffreTest` (3),
  `GestesSurDesNotesTest` (14), `AccueilTest` (20) — 0 échec.
- [x] **D-044 scellée** — format (`e1c18e9`) : `FichierDeSauvegarde` paramétré (magie, borne ; la
  sauvegarde inchangée, ses tests aussi), `FichierDeNoteScellee` (`NTSEALED`, 4 Mio, JSON, seul un code
  ouvre). Envoi et réception (`9464509`) : feuille « Partager scellée… » (fichier `note-AAAAMMJJ.ntnote`
  — le nom ne dit rien de la note —, code affiché une fois, copie par le presse-papiers sensible),
  activité « Ouvrir avec » exportée et menu « Ouvrir une note scellée », dépôt en mémoire, code demandé
  une fois l'appli déverrouillée. L'affichage du code passe dans `ui/common/CodeAffiche`, partagé avec la
  sauvegarde. Tests : `FichierDeNoteScelleeTest` (6, S9), `LectureScelleeTest` (4, JVM : un autre fichier
  est abandonné à ses premiers octets). **De bout en bout sur le S9** : note créée, scellée en lecture
  unique (201 octets, `NTSEALED`, le texte absent du fichier), poussée dans Téléchargements, ouverte par
  le menu de l'accueil ; un faux code refusé (« Code incorrect, ou fichier abîmé ») ; le bon, tapé en
  minuscules avec des espaces, ouvre la note dans la Boîte de réception avec l'annonce de la lecture
  unique ; refermée, elle s'efface — seule l'originale reste. Puis « Durée de vie… » → « 1 heure » sur
  l'originale : message « Durée de vie : 1 heure », badge « 1 h » sur la carte. **Non essayé sur
  appareil** : « Ouvrir avec » depuis une messagerie (l'activité exportée) — à faire avec une vraie
  messagerie, ou au moins un gestionnaire de fichiers.
- [x] Textes 5 langues · [x] version 3.4.0 (507) · [x] journaux 5071-5073 · [x] README, SECURITY, confidentialité
- [x] Audits (3 axes, cohérence, i18n) · [x] relecture externe · [x] S9 (suite complète puis classes touchées) · [ ] CI
- [ ] **⛔ Patrice, 2026-11-09 : pas de tag ni de release de la 3.4.0 tant que F-Droid ne sert pas la 3.3.0**
  (API `packages/com.filestech.notes_tech`, versions 5061-5063). La fusion dans `main` n'y change rien :
  F-Droid ne construit que les tags.
- [ ] Avant le tag : build F-Droid sous AGP 9.5 — au 2026-11-09 au soir, F-Droid n'a construit ni SMS 1.30.0
  (pas de journal `_307`) ni Notes 3.3.0 : aucune preuve encore ; essai de mise à jour depuis la 3.3.0 sur le S9
