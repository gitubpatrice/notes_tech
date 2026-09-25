# Reprise — portage Kotlin de Notes Tech

> Écrit le 2026-08-15 au soir, mis à jour le 2026-08-19, le 2026-09-24, **puis le 2026-09-25**. À lire en premier, avant `docs/00-PLAN.md`.
> Ce fichier ne remplace pas les docs : il dit **où on en est** et **quoi faire ensuite**.

## 🎯 ÉTAT AU 2026-09-25 (fin de soirée) — LIRE CECI D'ABORD

> Patrice : « consigne tout et on reprend demain ». Tout est commité, **rien n'est poussé**.

**Fait ce soir, après le compactage** (dans l'ordre des commits) : clé de coffre perdue `346d95b`,
`314e613`, `74f2b14` (D-026) ; pages légales `a94ad36`, `ebee629` (D-027 : **la version française fait
foi** ; gratuit, par un particulier, usage sous la responsabilité de l'utilisateur ; phrase RGPD
corrigée d'après la CNIL § 3.3 — Patrice publie **à titre personnel et gratuit**) ; langues terminées
`c9bad7d`, `7abf065` (test sur l'APK installé, contrôles négatifs, 0 texte en dur, relecture des
traductions : aucun contresens ; **S9 : OK (452 tests)**). Relectures externes du jour : **2,98 $**.

### 🔴 EN COURS — l'audit de sécurité, INTERROMPU (Patrice devait couper)

Tout est dans **`audits/securite-2026-09-25-etat.md`** : révision `ebee629`, palier profond, modèle
de menace, les cinq cellules, **sept candidats NON vérifiés** (V1 moyen : note de coffre encore lisible
dans l'éditeur après la fermeture du coffre ; V2 faible ; K1 moyen : ancien clair dans l'index FTS5 et
`note_links` après conversion en coffre ; K2 faible ; K3 moyen ; K4 moyen ; K5 faible), et comment
reprendre. **Demain** : relancer les cellules 3, 4, 5 ; vérifier les candidats par lots (20 agents
simultanés au plus) ; écrire chaque rapport sur le disque dès son arrivée ; le rapport final. Puis
l'**audit 3 axes**, en dernier. ⚠️ Et un point à lire soi-même (K3) : la clé de la base des
utilisateurs de la 2.0.4 porterait `setUnlockedDeviceRequired` — si oui, retirer le verrouillage
d'écran la supprime, et seule la copie `flutter_secure_storage` sauve la base.

## 🎯 ÉTAT AU 2026-09-25 (nuit, après le compactage) — section précédente

> La section « nuit » juste en dessous reste valable : sa liste « CE QUI RESTE » (les trois langues)
> n'a pas bougé, sauf son point 7, réglé ici. Tout est commité, **rien n'est poussé**.

Patrice, au retour du compactage : « il faudra passer pass_tech au vouvoiement » (**plus tard** : « on
verra pour pass tech plus tard, on termine notes_tech_kotlin » — consigné dans la mémoire du projet Pass
Tech, avec les fichiers à reprendre), et, sur la clé de coffre invalidée : « corrige le avec ce qu'il y
a de mieux ».

### Fait : une clé de coffre perdue n'est plus « trop de tentatives » — `346d95b` (§162, D-026)

- **Mesuré sur l'émulateur API 34** (clé au même `KeyGenParameterSpec` que le coffre) : retirer le
  verrouillage d'écran **supprime** la clé, en remettre un ne la ramène pas ; changer de code ou passer
  à un schéma la garde. Le S9 (Android 10) la gardait : « moins urgent que prévu » était faux ailleurs.
  Le portage disait « Veuillez réessayer » à l'infini, la 2.0.9 dit « Coffre verrouillé. ».
- **Corrigé** : clé introuvable → dite telle (« introuvable… aucun PIN ne peut ouvrir ce coffre sans
  elle »), ni comptée ni effacée, feuille réduite à « Fermer » ; clé invalidée → toujours effacée, mais
  avec sa vraie cause ; le choix du mode avertit, sur l'option code, que retirer le verrouillage
  d'écran **peut** rendre le coffre impossible à ouvrir. Cinq langues.
- **Trouvé en testant** : l'avertissement, d'abord dans la bannière de création, faisait déborder la
  feuille sur le S9 (le pavé bougeait entre les deux saisies) — déplacé au choix du mode.
- **Mesuré** : JVM 417, 0 ignoré ; S9 **OK (62 tests)** sur les trois classes de coffre ; émulateur
  56 + 5 ignorés à dessein + le témoin logiciel attendu ; lint 0 erreur, 75 avertissements ; **13
  contrôles négatifs, tous tombent** (chaque groupe exactement ses tests) ; 0 caractère invisible.
- **Relecture GPT-5.6 sol : 0,33 $ — total des relectures du jour 2,58 $.** Deux constats réels
  corrigés (le message et l'avertissement affirmaient plus que le code ne sait) ; deux préexistants,
  identiques à la 2.0.9, gardés pour l'**audit final** : deux déverrouillages concurrents (inatteignable
  par l'interface) et un remboursement d'essai qui échouerait (double panne).

### ✅ Tranché par Patrice le même soir (D-026)

1. `setUnlockedDeviceRequired(true)` **gardé** (« garde l'exigence si c'est mieux ») : sans lui la clé
   survivrait au retrait du verrouillage (mesuré), mais serait utilisable téléphone verrouillé. Le choix
   est écrit dans `AndroidVaultKeystore.specFor`, là où l'attribut est posé.
2. **Pas de 2.0.10** (« on part sur une 3.0.0 ») : la 2.0.9 publiée garde le défaut, la 3.0.0 le
   corrige — à dire dans son changelog (`docs/12-PLAN-DE-BASCULE.md`, phase 3).
3. ~~Quelle version des pages légales fait foi~~ — **réglé (D-027)** : Patrice publie **à titre
   personnel et gratuit**. Vérifié : Pass Tech et SMS Tech ne tranchent pas ; les premiers textes étaient
   en français ; modèle F-Droid (Markor : la licence Apache porte l'absence de garantie) ; CNIL,
   recommandation sur les applications mobiles § 3.3 (lue dans le PDF) : une appli dont les données
   restent sous le seul contrôle de l'utilisateur est un « simple logiciel », le RGPD ne s'applique pas à
   l'éditeur. Fait (`a94ad36` + suivant) : **la version française fait foi** (section « Langue », cinq
   langues) ; gratuit, par un particulier, tel quel (Apache 2.0 § 7-8), usage sous la responsabilité de
   l'utilisateur ; la phrase fausse « le RGPD s'applique entre vous et votre téléphone » corrigée.
   Conditions 1.1.0, confidentialité 1.2.0. `PagesLegalesTest` : plan de l'original exigé partout
   (deux contrôles négatifs tombent) ; S9 : écrans À propos, légal et réglages 21/21.

### Appareils, mis à jour

- **S9** : APK de debug **du commit `346d95b`** installés (`.next.debug`, note « Essai TalkBack »
  intacte — seules les classes de coffre ont tourné) ; la 3.0.0 release et ses données de bascule
  inchangées ; toujours aucun code d'écran.
- **Émulateur** : code 1111 remis après les mesures ; le test de mesure a été retiré du dépôt (copie
  à deux clés dans `scratchpad/MesureVerrouillageEcranTest.kt`, pour mesurer un jour Android 16).
- **S24** : inchangé (exclu de la mesure : il aurait fallu retirer son verrouillage d'écran).

## 🎯 ÉTAT AU 2026-09-25 (nuit) — section précédente

> Écrit avant un `/compact` demandé par Patrice (« consigne tout je dois compacter »). La section du
> 25 au soir, juste en dessous, reste valable (points 7 à 13 : ce qui a été fait depuis le compactage
> précédent). Tout est commité, **rien n'est poussé**.

### Commits depuis le compactage précédent

`be5f8e3` (feuille `[[` d'un coffre) → `d054c2c` → `d766d68` (panneau Infos testé) → `4819e52` →
`831eed3` (coffre sur Keystore logiciel) → `a01d96a` → **`30d60a1`** (relecture du diff de parité, GPT
0,32 $ : secrets du coffre effacés sur tous les chemins, §160) → **`2b0f8e9`** (seconde passe Gemini
0,41 $ : scellé intérieur effacé même si la dérivation échoue, §160 ; **total des relectures du jour
2,25 $**) → **`0bee89e`** (bascule depuis une vraie 2.0.9 mesurée sur le S9, phrase secrète ET code —
Patrice : « super tout fonctionne ») → **`5466624`** (allemand, espagnol, italien, §161, D-025).

### 🔴 CE QUI RESTE — les trois langues, dans l'ordre (mis à jour après le compactage)

1. ✅ **Test d'appareil** `LanguesDansLApkTest` — fait, S9 vert ; contrôle négatif (`it` retiré de
   `localeFilters`) : **tombe**.
2. ✅ **Contrôles négatifs JVM** — les cinq tombent, chacun sur son test (§161).
3. ✅ **Suite complète sur le S9** (APK de `c9bad7d`) : **OK (452 tests)**, 450 + 2 hypothèses connues,
   0 échec.
4. ✅ **Textes codés en dur** — aucun visible : 101 littéraux relus, tous internes (§161).
5. ✅ **Relecture externe des traductions** — Patrice : « ok lance la relecture ». GPT, 0,40 $ (total du
   jour **2,98 $**) : aucun contresens ; quatre retouches mineures appliquées (§161).
6. ✅ Docs : `05-PARITE.md` (ajout hors parité), `12-PLAN-DE-BASCULE.md` (fastlane de-DE/es-ES/it-IT,
   description F-Droid).
7. ~~**À dire à Patrice**~~ — **réglé après le compactage** (section au-dessus) : le ton de Pass Tech est
   décidé (vouvoiement, plus tard) ; le message de clé de coffre est fait (`346d95b`, et « retirer le
   code d'écran ne l'invalide pas » était faux sur Android 14 : il la **supprime**). Reste seul ouvert :
   quelle version des pages légales traduites fait foi — décision juridique.

Reste ensuite l'ancienne liste : docs finales, ménage de l'émulateur, TalkBack (Patrice), puis l'audit
complet « à la fin ».

### Appareils

- **S9** : `com.filestech.notes_tech` **3.0.0 release** installée (données de la bascule : notes
  « Temoin PIN 209 », coffre à code « Coffre PIN 209 », code **2468**) ; `.next.debug` avec la note
  « Essai TalkBack » ; **aucun code d'écran** (1111 posé pour la bascule, puis retiré). Les APK de debug
  du commit `5466624` ne sont **pas** encore installés.
- **Émulateur** `emu-test-api34` : tourne sans fenêtre (code 1111).
- **S24** : inchangé.
- APK signé de la bascule **supprimé** ; `scratchpad/bascule/signer.py` (aucun secret dedans : il lit
  `key.properties` à la volée) et l'APK 2.0.9 publié restent dans le scratchpad.

**Mesuré en dernier** : JVM **416 tests**, 49 classes, 0 ignoré ; ktlint, detekt, lint (0 erreur, 75
avertissements) ; `aapt2` : `de`, `es`, `fr`, `it` dans l'APK de debug. Suite complète S9 (avant les
langues) : 442 cas, 440 + 2 hypothèses connues, 0 échec.

## 🎯 ÉTAT AU 2026-09-25 (soir) — section précédente

> Écrit avant un `/compact` demandé par Patrice, **mis à jour le soir** après la reprise (point 5 et
> suivants). Tout est vérifié, pas supposé. La section du 09-24 juste en dessous reste valable pour le
> contexte (consignes, verrou, contraintes).

### Fait aujourd'hui

1. **Renommage du dossier terminé** : `J:\applications\notes_tech_kotlin`. Mémoire copiée sous la clé
   `j--applications-notes-tech-kotlin` (empreintes identiques), `build`/`app/build`/`app/.cxx`
   supprimés (le `.cxx` gardait l'ancien chemin : `clean` échouait), gate vert. §144.
2. **Aperçu Markdown (D-024, A1/A2) — commit `3a97bf4`.** Détail et écarts assumés : `docs/01-DECISIONS.md`
   D-024 « Réalisation » ; pièges : `docs/04-PIEGES.md` §145 à §154. Points saillants :
   - lecteur `domain/markdown/` (analyseur JetBrains 0.7.14), rendu `ui/editor/ApercuMarkdown.kt`,
     lecture hors fil principal `ui/editor/LectureDeLApercu.kt` ;
   - `[[Titre]]` → ouvrir **ou créer puis ouvrir**, un seul chemin avec le panneau de liens, qui
     créait SANS ouvrir depuis toujours (§150) ;
   - notes hostiles bornées (§147 : OOM à 50 000 `>`, 8 s pour des `[a](`) : balayage avant analyse,
     « tel quel » avec un avis ; l'analyse s'annule quand on quitte l'aperçu (§148) ;
   - liens annoncés au lecteur d'écran par des spans cliquables, mesuré sur l'arbre **réel** d'Android
     (§149).
3. 🔴🔴 **Plantage préexistant corrigé** : une note de plus de ~3 600 lignes faisait planter l'éditeur à
   l'ouverture depuis le 08-15 (§153). Corrigé en reprenant la **mise en page de la 2.0.9** : en-tête
   fixe, corps qui remplit l'espace et défile lui-même, panneau des liens dessous (tiers de l'écran,
   masqué avec le clavier) (§154).
4. **Relecture externe GPT-5.6 sol : 0,39 $** (budget de Patrice : 1 à 2 $). 9 constats, 5 réels
   corrigés, 3 réfutés (mesure, code, test), 1 écarté avec raison (§152).
5. **Le soir, après le compactage — commits `7c42746` et `49908f1`** :
   - contrôles négatifs de la mise en page de la 2.0.9 : les trois tombent, pour la bonne raison (§154) ;
   - **relecture Gemini 3.1 Pro des seuls correctifs : 0,44 $** (total du jour **0,83 $**) — le
     « critique » réfuté, 2 constats réels corrigés (§155) : **plancher de 120 dp pour le corps**
     (`MiseEnPageDeLEditeur` ; en paysage clavier ouvert le corps faisait **0 dp** sur le S9, 74 dp
     visibles désormais ; la 2.0.9 a le même défaut) et `DartTextSemantics.isBlank` (plus de copie de
     la note à chaque frappe) ;
   - le masquage du panneau avec le clavier a son test, par `MainActivity` (il n'en avait aucun) ;
   - **première suite complète sur Android 14** (émulateur) : un test de plateforme périmé (§156) — la
     plateforme accepte désormais MGF1 en SHA-256 ; la 2.0.9 écrit SHA-1 **en dur** (source de
     `flutter_secure_storage` 10.3.1 relue), donc **la bascule n'est pas en danger** ; test réécrit ;
   - `audit-ia.py` affiche aussi le coût d'une relecture Gemini (il l'ignorait).
6. **Plus tard le soir, sur les réponses de Patrice — commits `27f176f`, `7922a45`, `25d0f55`, `359f4e9`** :
   - **S24 (Android 16), sur son accord** : classe de la KEK + deux classes du Keystore du coffre,
     **27/27**, paquet `.next.debug` seulement ;
   - **pages légales au rendu de l'aperçu**, sélectionnables comme dans la 2.0.9 (§157). Le passage a
     révélé que le lecteur **ignorait les fins de ligne CRLF** (notes importées de Windows comprises), et
     qu'une ligne faite d'un `\r` ouvrait une **faille dans la garde anti-notes-hostiles** : corrigé ;
   - **message** « Impossible de créer la note liée » au lieu de « Création du coffre échouée » ;
   - **solution B** (§158) : depuis une note de coffre, `[[X]]` trouve la note X **de ce coffre ouvert**,
     puis les notes ordinaires ; jamais un autre coffre. Test sur un **vrai coffre** par le vrai
     ViewModel ;
   - **relecture GPT-5.6 sol du code du soir : 0,31 $** (total du jour **1,14 $**) : 2 réels corrigés
     (déchiffrement du coffre sur le fil principal ; un commentaire), 3 réfutés, 2 laissés avec raison.
7. **Après le 2e compactage — Patrice : « pour les 2 questions, fais ce qui est le mieux ! Tu as carte
   blanche » — commit `be5f8e3`** (§159, D-024) :
   - **suites de B tranchées** à la règle de la réponse publique à l'issue #10 (aucune liste de titres
     de coffre à l'écran) : le **panneau des liens** d'une note de coffre reste absent (ses rétroliens
     seraient une telle liste) ; la **feuille `[[`** ne liste pas les titres du coffre ;
   - mais la feuille était **fautive**, trouvé en examinant la question : elle ne voyait aucune note de
     coffre — dans un coffre contenant « Codes », Entrée créait un **doublon**, et le lien ouvrait
     ensuite l'ancienne note. Désormais la note du coffre dont le titre est tapé **en entier** est
     reconnue (Entrée la lie) ; tapé en partie, rien du coffre ;
   - 🔴 un **plantage évité, mesuré** : coffre refermé pendant que la feuille est ouverte → l'application
     tombait (« Process crashed ») ; la recherche en échec le dit désormais et ne propose rien ;
   - **relecture GPT-5.6 sol : 0,38 $** (total du jour **1,52 $** sur 2 $) : 2 réels corrigés (échec
     répondu en liste vide qui laissait créer ; tout le clair du coffre gardé en mémoire), 2 préexistants
     laissés (homonymes, troncature à 8 : §84, identiques dans la 2.0.9) ;
   - **TalkBack** : Claude ne peut pas entendre ; la note d'essai est **prête sur le S9** et les étapes
     sont ci-dessous (« CE QUI RESTE », point 5).
8. **Le panneau Infos a ses tests** (point 4 de l'ancienne liste) : `InfosDeLaNoteTest` (JVM, 3 : les
   comptes viennent du texte à l'écran et non de la ligne scellée d'une note de coffre ; retours à la
   ligne exclus, espaces comptés ; rien tant que la note n'est pas lisible) et `PanneauInfosTest`
   (S9, **5/5** : une ligne = un seul nœud libellé + valeur ; chiffres groupés dans la langue du lecteur ;
   dernière ligne atteignable en police double ; fermer ; balayages d'accessibilité, population
   vérifiée). **Cinq contrôles négatifs, tous tombent** (fusion retirée, `toString`, colonne non
   défilante, comptes pris sur la ligne en base, coffre à déverrouiller non écarté).
9. **La suite de l'émulateur devient lisible** (point 3 facultatif) : les tests du coffre qui ont besoin
   d'une clé du portage passent par `AndroidVaultKeystore.creerOuIgnorer` (androidTest), qui transforme
   le **seul** refus `KeystoreSoftwareOnlyException` en hypothèse non tenue. **Le témoin**
   `la_cle_creee_est_retenue_par_le_materiel_securise` ne l'utilise pas : il échoue sur l'émulateur,
   exprès (§156). Mesuré : émulateur **4 réussis, 8 ignorés, 1 échec = le témoin**, motif
   `KeystoreSoftwareOnlyException` ; S9 **13/13, rien d'ignoré** — sur un vrai matériel l'aide ne se
   déclenche jamais.
10. **Suite complète du S9 sur l'état final** (avant le point 9) : **442 cas, 440 réussis, 2 hypothèses
    connues** (pas de modèle Whisper, pas d'empreinte), **0 échec** ; la note « Essai TalkBack » y a
    survécu, et aucune donnée de test n'est restée.
11. **Patrice, à son retour : « ok lance la relecture », puis « fais relire tout ce qui est nécessaire »,
    et la bascule depuis une vraie 2.0.9 autorisée** (« tu l'as dans le dossier notes_tech »).
    Relecture du diff de parité (§160, **0,32 $**, total **1,84 $**) : 9 constats — 3 réels corrigés
    (clé dérivée hors du `try`, copies UTF-8 des secrets, rangs d'ABI), 1 quatrième trouvé en corrigeant
    (**scellé intérieur d'un coffre à code laissé en mémoire** sur un échec du Keystore), 2 réfutés, 3
    identiques à la 2.0.9. S9 **50/50** sur les classes des coffres, 3 contrôles négatifs qui tombent.
    **À proposer à Patrice** : un message dédié quand Android invalide la clé d'un coffre à code (l'écran
    dit « trop de tentatives », comme la 2.0.9).
    **Seconde passe Gemini 3.1 Pro** (§160, **0,41 $**, total du jour **2,25 $**) sur la feuille `[[` et
    ces correctifs : 4 constats — 2 réfutés, 1 identique à la 2.0.9, 1 réel sous une autre forme
    (au déverrouillage d'un coffre à code, la dérivation tournait avant le `try` qui efface le scellé
    intérieur) : corrigé, testé (S9 29/29), contrôle négatif qui tombe.
12. ✅ **Bascule depuis une vraie 2.0.9, mesurée sur le S9** (`05-PARITE.md`, ligne « Bascule 2.0.9 →
    3.0.0 ») : release GitHub v2.0.9 installée, notes, coffre à phrase et coffre à code créés à la main,
    puis la 3.0.0 signée de la clé de production posée par-dessus — tout est relu, index de liens
    compris. Code d'écran 1111 posé puis **retiré** du S9 ; retirer le code n'invalide pas la clé du
    coffre. APK signé **supprimé** du scratchpad. Le S9 porte désormais la **3.0.0 release**
    (`com.filestech.notes_tech`, données de test de la bascule) à côté de l'application de
    développement (note « Essai TalkBack »). **Patrice : « super tout fonctionne ».**
13. **Nouvelle demande de Patrice, le 25 au soir : ajouter l'allemand, l'espagnol et l'italien.**

### Mesuré

- JVM : **402 tests, 45 classes, 0 ignoré** ; ktlint, detekt, lint (0 erreur) verts.
- S9, **suite complète sur l'état final** : **423 cas, 421 réussis, 2 hypothèses non tenues connues**
  (pas de modèle Whisper ; pas d'empreinte), 0 échec. La classe du test MGF1, réécrit ensuite : 14/14.
- Émulateur API 34, suite complète : 423 cas, 409 réussis, 2 hypothèses connues, **12 échecs, tous
  Keystore et expliqués** (§156) : code de verrouillage disparu (reposé : 1111, deux passent), test
  MGF1 périmé (réécrit : sa classe 14/14), et **9 échecs par construction** — Keystore logiciel, que le
  coffre refuse exprès.
- Contrôles négatifs du jour : 22 le matin ; le soir 3 (mise en page), 6 (plancher, pleine hauteur,
  `clearFocus`, lecture, clavier, `isBlank`) et 1 (MGF1, sur l'émulateur) ; plus tard 3 (fins de ligne,
  lien de note dans une page légale, ancien message), 5 (solution B) et 1 (déchiffrement hors du fil
  principal) — chacun fait tomber **son** test, motif relu dans la sortie.
- Après la solution B : S9 **65/65** (`NotesRepositoryTest` entier, le vrai coffre, l'écran légal) ;
  émulateur **13/13** sur les tests de B, le bout en bout de l'aperçu (clavier compris) et l'écran légal.
- Après la feuille `[[` (`be5f8e3`) : JVM **405 tests**, 45 classes, 0 ignoré ; ktlint, detekt, lint
  (0 erreur, 76 avertissements) verts ; S9 **110/110** sur les cinq classes de l'éditeur (liens du
  coffre, dépôt, feuille, éditeur, bout en bout de l'aperçu). **Treize contrôles négatifs** (F1-F8,
  G1-G5), tous tombent, motif relu. ⚠️ Le contrôle F7 fait planter le processus : le `tearDown` ne
  tourne pas, et un coffre de test (« Vault 476b50c9 », 2 notes) est resté dans l'application de
  développement du S9 — données effacées ensuite (`pm clear` du seul paquet `.next.debug`).

### 🔴 CE QUI RESTE, dans l'ordre

1. ✅ ~~Contrôles négatifs de la mise en page~~, ✅ ~~seconde relecture (Gemini)~~, ✅ ~~suite complète
   S9 + émulateur~~, ✅ ~~test du masquage du panneau avec le clavier~~ — faits le 25 au soir (point 5
   ci-dessus). Outils : `scratchpad/controles_mise_en_page.py` (sa fonction `une` sert aux autres),
   `controles_plancher.py`, `controle_mgf1_emulateur.py`, `controles_legal.py`, `controles_b.py` (une
   mutation, construction, installation, test ciblé, **motif** de l'échec gardé, restauration vérifiée
   au SHA-256 ; les scratchpads ne survivent pas aux sessions).
   **État des appareils (après `be5f8e3`)** : l'émulateur `emu-test-api34` tourne encore **sans
   fenêtre** (`emulator-5554`, code 1111, sans empreinte) ; le S9 porte les APK de `be5f8e3`, données de
   l'application de développement **effacées** puis la note « Essai TalkBack » créée ; le **S24** porte
   l'application de test `.next.debug` installée **avant** les pages légales et la solution B (le soir,
   pour les classes Keystore) — sans conséquence, mais pas la dernière version. Nouveaux scripts :
   `controles_feuille.py`, `controles_feuille_2.py` (JVM : verdict lu dans le rapport XML, qui doit
   être neuf), `ecran_s9.py` (lire l'arbre d'accessibilité, toucher un nœud), `taper_note.py`.
2. ✅ ~~`FlutterSecureStorageKekSourceTest` sur un Android ≥ 14 réel~~ — S24, Android 16 : 27/27 avec
   les deux classes du Keystore du coffre (point 6 ci-dessus).
3. ✅ ~~Facultatif : les 9 tests du coffre qui échouent sur un Keystore logiciel~~ — fait le 25 au soir
   (point 9 ci-dessus) : 8 s'ignorent sur `KeystoreSoftwareOnlyException`, **le témoin**
   `la_cle_creee_est_retenue_par_le_materiel_securise` continue d'échouer sur l'émulateur, exprès.
4. ✅ ~~Les trois décisions de Patrice~~ — tranchées le 25 au soir et faites : solution **B** pour les
   liens dans un coffre, pages légales au rendu de l'aperçu, message corrigé (point 6 ci-dessus).
   ✅ **Suites de B** : tranchées par Claude sur délégation (point 7 ci-dessus, §159).
5. **TalkBack réel** sur l'aperçu — **à faire par Patrice** (Claude ne peut pas entendre ; §149 : le
   paragraphe à liens n'est pas `screenReaderFocusable`, TalkBack devrait le lire quand même). Cinq
   minutes. La note **« Essai TalkBack »** est prête dans « Notes Tech (Kotlin debug) » **sur le S9**
   (Boîte de réception) : un titre de section, un paragraphe avec un lien de note `[[Codes]]` et un lien
   web, deux puces, deux cases à cocher.
   1. Ouvrir la note, toucher **Aperçu**.
   2. Activer le lecteur d'écran : Paramètres → Accessibilité → Lecteur d'écran → **Voice Assistant**.
      ⚠️ Sur le S9, c'est l'ancien lecteur de Samsung (5.1, Android 10) ; le **S24** a le TalkBack actuel,
      plus représentatif — la même note s'y tape en une minute (l'application de test y est déjà).
   3. Balayer vers la droite, un élément à la fois. À entendre : « Une section » annoncé comme **titre** ;
      puis **le paragraphe entier** « Voir Codes et le site web » — le point non mesuré : il doit être
      lu, pas sauté ; puis les puces (« • » est un arrêt à part, comme dans la 2.0.9) ; puis « Fait »,
      « Faite », « À faire », « A faire ».
   4. Sur le paragraphe, le menu du lecteur (TalkBack récent : glisser vers le bas puis vers la droite,
      ou toucher avec trois doigts) doit proposer **« Liens »**, avec « Codes » et « site web » ; choisir
      « Codes » ouvre la note « Codes » (créée au passage).
   5. Désactiver par le même chemin (lecteur actif : un toucher sélectionne, deux touchers activent).
6. Suite de l'ancienne liste (section du 09-24 ci-dessous, points 4 à 8) : ✅ ~~test de
   `NoteInfoDialog`~~ (point 8 ci-dessus), ✅ ~~bascule depuis une vraie 2.0.9 sur le S9~~ (point 12), ✅ ~~relecture du diff de parité 2.0.9 + panneau Infos~~ (point 11), docs
   finales (`05-PARITE.md`, `12-PLAN-DE-BASCULE.md` : la 3.0.0 ajoute aussi une **dépendance**,
   `org.jetbrains:markdown`, à déclarer dans la description F-Droid s'il y a lieu), ménage de
   l'émulateur (code **1111 reposé le 25** ; l'empreinte du 24 a disparu, non reposée).
7. Avant toute publication : `docs/12-PLAN-DE-BASCULE.md` en entier (rodage, décisions A et C, phase 3).
   Y ajouter : avec `core.autocrlf=true`, les `.md` de `res/raw` sortent en CRLF d'une construction
   Windows et en LF d'une construction Linux (F-Droid) — un `.gitattributes` (`*.md text eol=lf`) est à
   envisager avant toute recherche de reproductibilité (§157).

### Pièges du jour à ne pas refaire (détail dans `04-PIEGES.md`)

- **Dans une commande Bash, `\\` arrive comme `\`** (§145) : tout contenu avec antislash par Write/Edit.
- Des caractères invisibles tapés tels quels (U+E000, U+FFFD, espace insécable) : vérifier à l'octet.
  **L'échappement `\uXXXX` écrit dans l'outil d'édition arrive comme le caractère lui-même** (U+FEFF,
  deux fois le 25 au soir) ; et un balayage dont la liste de caractères est **tapée** dans la commande
  peut tout signaler ou tout laisser passer : nommer les caractères par leur code (§159).
- `ktlintFormat` réécrit toujours les fins de ligne de `SttModelStoreTest`, `WhisperSttTest`,
  `NoteCard` : les restaurer (`git checkout --`) quand leur diff réel est vide.
- `rm -rf` est refusé par une règle `deny` globale : `rm -r` sans `-f`, sur accord.
- **Parler à Patrice en français, descriptions de commandes comprises** (mémoire `tout-en-anglais`).
- Tests Compose (§155) : `boundsInRoot` est **rogné** par le défilement (une taille : `.size`) ;
  `performScrollTo` ne fait défiler que le conteneur **le plus proche** ; clavier ouvert, il y a
  **deux racines** ; la `ComponentActivity` des tests d'écran ne voit **jamais** le clavier.
- Un émulateur redémarré sur instantané perd ce qu'on y a posé (code, empreinte) : le vérifier avant
  de lire une suite (§156). Et un test qui mesure la plateforme se périme avec elle.

## 🎯 ÉTAT AU 2026-09-24 (nuit) — contexte, consignes, verrou

> Écrit avant un `/compact` (le 2e du jour), **mis à jour en fin de soirée** (après le 3e) avant une
> coupure jusqu'au lendemain. Tout ce qui suit est vérifié, pas supposé. Les sections plus bas
> (08-15 → 08-20) sont l'historique : **elles décrivent un monde qui a bougé** (§128).

### Contexte (inchangé depuis le compactage précédent)

- Pendant l'arrêt (08-26 → 09-24), la Flutter `notes_tech` a publié **2.0.5 → 2.0.9** (`e3ee1d6`,
  versionCode **407** → splits 4071/4072/4073). MR F-Droid `!37885` en 2.0.9, « mostly ready » —
  **ne pas y toucher**. Le S9 est un téléphone de TESTS : **le rodage n'a pas eu lieu**.
- Consignes de Patrice : « fais tout ce qui est nécessaire, et proprement » ; panneau Infos ✅ ;
  **verrouillage par PIN ou biométrie** ✅ (ci-dessous).
- **Renommage du dossier : `notes_tech_kotlin`** (choisi par Patrice le 2026-09-24, soir). À faire
  **VS Code fermé** (le répertoire de travail est verrouillé par Windows pendant une session) :
  renommer `J:\applications\notes_files_tech` → `notes_tech_kotlin` ; supprimer `build`,
  `app\build`, `app\.cxx` (générés, chemins absolus CMake) ; **copier** (pas déplacer)
  `~\.claude\projects\j--applications-notes-files-tech` → `j--applications-notes-tech-kotlin` ;
  rouvrir. À la reprise : vérifier que la mémoire est bien retrouvée sous la nouvelle clé. Rien
  dans le code ne dépend de l'ancien nom (vérifié : seuls un rapport et un commentaire le citent).
- **Audit complet (3 axes, cohérence, i18n) : « on voit à la fin »** (Patrice, 2026-09-24) — pas
  avant la fin des fonctionnalités.
- **Langue** (précisé par Patrice le 09-24) : **anglais uniquement pour ce qui part dans le domaine
  public** (GitHub, F-Droid : code, commentaires neufs, commits, textes de l'app) ; tout le local
  (docs, REPRISE, audits, mémoire) en français.
- Tests : émulateur sans fenêtre ou S9 ; **le S24 seulement sur accord du moment** (donné une fois le
  09-24 pour le verrou). Relectures externes : **budget ~3 $ par série** (« j'ai peu de budget »).

### Commits du 2026-09-24, sur `master` (aucun remote, rien de poussé)

| Commit | Contenu |
|---|---|
| `8715023` | versionCode `base × 10 + ABI`, base 500 → 5001/5002/5003 (D-022) |
| `9d0c59f` | parité 2.0.9 (messages sans exception, « Inbox » traduite, export, coffre PIN sans verrouillage d'écran…) |
| `f640903` | panneau Infos |
| `1e1a71d` | docs du compactage précédent |
| `aab469b` | **verrouillage de l'application (D-023)** — cf. ci-dessous |
| `2eea4c1` | docs : D-023 « réalisation », §135-§141, cette section |
| `d4a3e74` | docs : renommage en `notes_tech_kotlin` décidé, audit complet à la fin |
| (ce commit-ci) | tests : le 15e contrôle négatif et les deux tests de feuille corrigés (§142, §143) |

### Le verrouillage de l'application — FAIT (`aab469b`)

PIN 4-6 chiffres ; délais immédiat / 15 s / 1 min / 5 min ; biométrie **classe 3 seulement** (le
visage Samsung, classe 2, n'est pas proposé) ; « PIN oublié » → mode panique ; Récents masqués ;
tout ce qui baisse la protection exige une **preuve de PIN** (unique, 2 min, liée à l'époque de
verrouillage). Détail et choix : **`docs/01-DECISIONS.md` D-023**, section « Réalisation ».
Code : `security/applock/` (cœur, testable JVM), `ui/applock/` (écran, Réglages, invite
biométrique, Récents), `ui/LockedAppHost.kt`, `ui/common/{PinPad,AppResultLauncher}.kt`.

**Mesuré :**
- JVM : **336 tests (36 classes), 0 ignoré** ; lint, detekt, ktlint, `check-manifest-permissions.py`
  verts (`USE_BIOMETRIC`/`USE_FINGERPRINT` admises + `privacy.md` v1.1.0).
- **15 contrôles négatifs** (gardes cassées une à une) : chacun fait tomber son test. Le 15e — la
  lecture d'époque corrigée après Gemini — **ne tombait PAS** au premier essai : son test
  (`lock_during_the_count_write_wins`) passait avec le défaut, son crochet verrouillant aussi pendant
  la remise à zéro du compteur (§142). Test corrigé ; le contrôle tombe désormais sur lui seul.
- Instrumenté, **avant** les correctifs de relecture : S9 **403 cas, 401 verts, 2 ignorés connus**
  (transcription : pas de modèle Whisper ; chiffrement non authentifié : pas d'empreinte sur le S9) ;
  classes du verrou vertes sur l'**émulateur API 34** et sur le **S24 (Android 16, empreinte
  réelle)**. Après les 1ers correctifs : verrou + coffre + panique sur S9, **63 verts**.
- **Soir du 09-24, APK courants (tous les correctifs)** : suite complète S9 **404 cas : 401 réussis,
  2 ignorés connus, 1 échec** ; S24 et émulateur, 9 classes (verrou, panique, coffre, Réglages) :
  **79 cas : 77 réussis, 1 ignoré attendu** (`a_failed_creation_leaves_no_key`, jumeau qui exige un
  appareil SANS empreinte), **1 échec** chacun. Les deux échecs sont des défauts de TESTS de feuille,
  pas du verrou (§143), corrigés : les 4 classes de feuilles repassent **S9 54/54, S24 54/54** ;
  contrôle négatif de l'utilitaire commun sur l'émulateur : tombe comme attendu. JVM après
  correction : 336 tests, 0 échec, 0 ignoré ; ktlint, detekt verts.
  Comptage : `scratchpad/compte_instrument.py` lit les codes de statut test par test (le lanceur
  écrit « OK » même quand des tests sont ignorés) ; validé sur l'ancienne passe (403 = 401 + 2).
- Bout en bout sur l'émulateur (empreinte simulée, `adb emu finger touch 1`, code appareil 1111) :
  activer le verrou et la biométrie par l'interface, quitter, revenir → invite automatique → doigt →
  **retour sur les Réglages à la même position**. Récents API 34 : carte neutre avec verrou, contenu
  visible sans (contrôle négatif).
- Relectures : **GPT-5.6 sol (0,56 $)** → 4 constats, tous vérifiés et corrigés (essai compté AVANT
  vérification, preuve revalidée sous le verrou d'époque, échéance négative, écran « invérifiable »
  qui cachait la biométrie) ; **Gemini 3.1 Pro** sur les correctifs → 1 course introduite par le
  premier correctif (époque lue après l'écriture bloquante), corrigée. Rapports :
  `scratchpad/relecture_verrou_{gpt,gemini}.md` (non conservés après la session — résumé ici).

**Sur le S24 de Patrice** : `com.filestech.notes_tech.next.debug` (« Notes Tech (Kotlin debug) »)
**est installée**, avec l'APK de test — distincte de sa vraie Notes Tech. Le 09-24 au soir, Patrice
a autorisé les tests (« test sur le S24 si tu veux ») : **APK courants installés** (app du 09-24
21 h 31, tous les correctifs ; APK de test du soir), classes passées (cf. Mesuré). Aucun verrou n'y
était configuré (aucune clé `app_lock_*`). **Son test à la main, avec son empreinte : pas de
retour** — facultatif.

### 🔴 CE QUI RESTE, dans l'ordre

1. **Test à la main de Patrice sur le S24** (le verrou avec son empreinte), facultatif : la version
   installée est à jour.
2. ✅ ~~Contrôle négatif du 15e garde + relance des classes du verrou~~ — fait le 09-24 au soir
   (cf. Mesuré, §142, §143). Reste, facultatif : la passe positive des 4 classes de feuilles sur
   l'émulateur, et une suite complète S9 avec les correctifs de tests, pour une mesure propre avant
   l'audit final.
3. ✅ ~~**Aperçu Markdown** (A1/A2) — D-024~~ — fait le 2026-09-25, commit `3a97bf4` (cf. section du
   09-25 ci-dessus).
4. **Test instrumenté de `NoteInfoDialog`** (balayages d'accessibilité).
5. **Bascule depuis une vraie 2.0.9** sur le S9 (jamais mesurée) — procédure dans l'ancienne liste :
   APK arm64 publié (4072), notes + coffres, puis 3.0.0 signée (`apksigner`, secret lu à la volée,
   aucun fichier de mot de passe écrit).
6. **Relecture externe du diff de parité 2.0.9 + panneau Infos** (`5f7dcd3..f640903`, jamais relu) —
   budget restant ~2 $.
7. Docs finales : `05-PARITE.md` (A1/A2 ; le verrou est un **ajout** hors parité), `12-PLAN-DE-BASCULE.md`
   (la 3.0.0 ajoute `USE_BIOMETRIC`/`USE_FINGERPRINT` : description F-Droid, site files-tech.com).
8. Ménage de l'émulateur `emu-test-api34` : une empreinte et un code appareil `1111` y ont été posés
   pour les essais (sans conséquence ; à retirer si l'AVD doit resservir « neuf »).

**⚠️ Avant toute publication — `docs/12-PLAN-DE-BASCULE.md`, à relire en entier** (oublié de cette
liste jusqu'au 09-24 au soir) : une 3.0.0 publiée est **irréversible** pour l'utilisateur. Il faut
donc, en plus des points 3 à 7 et de l'audit complet : le **rodage** (phase 0, JAMAIS fait : quel
appareil, quelles notes, combien de temps — décision de Patrice ; piste : pré-publication GitHub pour
des volontaires) ; les décisions **A** (le code poussé dans `notes_tech`, branche `kotlin`) et **C**
(la MR `!37885` laissée en 2.0.9 jusqu'à sa fusion, F-Droid en dernier) ; les métadonnées de la
phase 3 (`versionName` sans `-alpha01`, changelogs FR + EN ≤ 500 caractères, manifeste FUSIONNÉ).

### Rapports d'agents du 2026-09-24 (résumés ici : les transcriptions ne survivent pas)

- **Inventaire 2.0.4 → 2.0.9** : intégré à `docs/05-PARITE.md` (A1-A21, D4). Hors portage, signalé :
  la Flutter dit « l'audio n'est jamais persisté » dans ses textes publiés — faux si son moteur lit
  un fichier ; non vérifié côté Flutter. Et `outils/verif_i18n_retour_arriere.py` ignore
  `REMPLACEES` (donc aussi `ECARTEES`) : il était déjà rouge avant le 09-24, sur les 8 clés réécrites.
- **Verrou de SMS Tech** : défauts à NE PAS reprendre — verrou poussé comme destination de navigation
  (perd l'écran) ; délai par `delay()` sur une horloge qui s'arrête en veille ; rien pour les
  sélecteurs ; empreinte du PIN hors Keystore ; recréation silencieuse de la clé biométrique après
  invalidation ; baisser une protection sans le secret ; échec ouvert si l'empreinte manque ;
  biométrie qui franchit le blocage de façon incohérente ; libellé qui promet le visage en classe 3
  seule. À reprendre : état fermé par défaut, primitive unique de fermeture qui verrouille aussi les
  coffres, comparaison à temps constant, `when` exhaustifs sans `else`, `withResumed` avant
  `authenticate` (la 1.1.0 abandonne en silence après `onSaveInstanceState`), garde anti-empilement
  des invites.

### Aide-mémoire opérationnel — repris de `PROMPT-REPRISE.md` (fichier non suivi, supprimé le 2026-09-24)

> Toujours valable : la méthode et les contraintes dures. Seule mise à jour : les relectures externes sont désormais **autorisées** par Patrice (GPT 5.6 sol, Gemini Pro), et `pretooluse-protect` refuse toute ouverture Python en écriture dans une commande (cf. `docs/04-PIEGES.md` §133).

### La méthode qui a trouvé les douze défauts — ne pas improviser

Ne demande **pas** « est-ce que l'écran marche ? » : cette question n'a rien trouvé en six tours.
Pose les quatre autres.

#### 1. Que reçoit un lecteur d'écran ? — **trois** balayages, pas deux

Ils se recopient tels quels depuis `app/src/androidTest/.../ui/BalayageDAccessibilite.kt`, et chacun a
son **témoin** dans `BalayageDAccessibiliteTest` :

- `actionnablesSansNom()` — une **action sans nom** ;
- `actionsPerduesALaFusion()` — un **nom sans action**, le motif inverse ;
- `champsDeSaisieSansNom()` — une **zone de saisie sans nom**, née au §80.

⚠️⚠️ **Compte tes champs AVANT d'affirmer qu'aucun n'est muet.** `CHAMP_DE_SAISIE` est exposé à côté
des balayages pour ça : *un balayage qui n'a rien trouvé à balayer est vert lui aussi*. Le compte
attendu s'écrit **par écran**, jamais « au moins un ».

⚠️ Un champ **vide** ne discrimine rien — son placeholder le nomme. Pose du texte.
⚠️ Sur un écran **sans** champ, appeler le balayage est l'assertion creuse elle-même : affirme plutôt
« cet écran ne porte aucun nœud éditable », comme `CorbeilleTest` (fil-piège).

🔴 **Sur une feuille (`ModalBottomSheet`), `actionnablesSansNom` signalera TOUJOURS un nœud muet** :
`BottomSheetDefaults.DragHandle` pose **deux** nœuds aux mêmes coordonnées, l'un nommé, l'autre avec
`OnLongClick` **seul et sans nom**. Ce n'est pas le portage et ce n'est pas nommable depuis l'appelant.
Reprends l'exception d'`AutocompletionTest` : ancrée sur le nœud portant `Dismiss`, avec
`containsExactly` conservé — **jamais** dans l'instrument partagé.

#### Les deux instruments qui se sont révélés VACANTS le 08-18 — ne pas les refaire

- 🔴🔴 **Une `ModalBottomSheet` compose dans une fenêtre qui REPOSE ses propres `CompositionLocal`.**
  `LocalDensity`, `LocalTextToolbar` (et probablement les autres) fournis **au-dessus** d'elle **n'y
  entrent pas**. Deux mesures à l'échelle de texte ×2 ont rendu des bornes **identiques au pixel
  près** — impossible, et c'est ce qui a dénoncé l'instrument. Pour mesurer une taille de texte :
  `adb shell settings put system font_scale 2.0`, puis **restaurer à `1.0`**.
- 🔴 **Un espion de `TextToolbar` ne voit rien** dans ce harnais, *y compris sur un champ ordinaire* :
  `performTouchInput { longClick() }` n'ouvre pas la barre de sélection. Ce qui répond à la question
  « peut-on copier ce champ ? », c'est **l'arbre sémantique** : poser une sélection par
  `SemanticsActions.SetSelection`, puis regarder `CopyText` / `CutText`. Témoin obligatoire sur un
  champ ordinaire, qui les porte.

#### 2. Quels états ne sait-on pas atteindre à la main ?

Rends l'écran **sans état** — `Route` branché + composable qui ne reçoit qu'un état et des rappels,
comme `HomeRoute`/`HomeScreen`, `TrashRoute`, `SearchRoute`, `SettingsRoute`, `NoteEditorRoute`.
`FeuilleDAutocompletion` et `FeuilleDeDeplacement` étaient déjà sans état : vérifie avant de découper.

#### 3. Combien de tests ont été ignorés ? — **des deux côtés**

- instrumenté : `grep -c 'INSTRUMENTATION_STATUS_CODE: -3'` (ignoré) **et** `-4` (échec d'hypothèse),
  jamais le « OK (N tests) » ;
- 🔴 **JVM aussi**, depuis §82 : le dépôt tourne en **JUnit 5** (`app/build.gradle.kts:219`), et une
  classe écrite en **JUnit 4 est ignorée sans un mot**, sous `BUILD SUCCESSFUL`. Compte la somme des
  `tests=` dans `app/build/test-results/testDebugUnitTest/*.xml`, et vérifie que **le XML de ta classe
  existe**.

#### 4. Ce que le publié VOULAIT — pas seulement ce qu'il fait

C'est la question *« quel fichier joue ce rôle, et pourquoi ? »* qui a trouvé les défauts que
*« est-ce que ça marche ? »* laissait passer. Et une **chaîne traduite des deux côtés et lue nulle
part** est un signal — elle a désigné le bon libellé deux fois (§79, §80).

#### Les motifs déjà nommés, à chercher d'emblée

- une valeur initiale de `stateIn` indiscernable d'une donnée (§75, §76) ;
- une réponse qui ne dit pas **à quelle question** elle répond (§76, §84) ;
- un composant interactif en slot sans nom, ou une sémantique posée sur un autre nœud que celui qui
  agit (§74, §77) ;
- une `contentDescription` qui nomme autre chose que l'action (§73, §79) ;
- 🔴 **un commentaire qui ment** : le dépôt en a maintenant **deux** cas avérés, et le second a été
  **repris tel quel par un relecteur externe** comme preuve que le code était bon (§84).

### Contraintes dures — les enfreindre coûte des données réelles

- **Appareil de test = le S9, `ANDROID_SERIAL=22dbb7390a057ece`.** ⚠️ **JAMAIS** le S24 FE
  (`RZCY41EGKYL`) : c'est le téléphone réel de Patrice.
- **La suite instrumentée se lance par `adb shell am instrument -w -r
  com.filestech.notes_tech.next.debug.test/com.filestech.notes_tech.HiltTestRunner`**, jamais par
  `connectedAndroidTest`, qui désinstalle l'application.
- ⚠️ **Réinstalle l'APK applicatif, pas seulement celui des tests**, dès qu'une classe **de production**
  est ajoutée — sinon `ClassNotFoundException` au lancement (vécu ce soir).
- **Contrôler le modèle de 57 Mo après chaque suite** :
  `adb shell "run-as com.filestech.notes_tech.next.debug sha256sum files/stt/whisper-base-q5_1.bin"`
  doit rendre `422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898`.
- **`MSYS_NO_PATHCONV=1`** devant tout `adb` contenant `/sdcard/…` ou `/data/…`.
- **`strings.xml` est GÉNÉRÉ** par `outils/arb_vers_strings.py` : toute chaîne nouvelle s'ajoute dans
  `AJOUTS_EN` / `AJOUTS_FR` du script, **jamais** dans le XML.
- **Jamais `git add -A`** : nommer les fichiers un par un. **Commits par `-F`, jamais `-m`.**
- ⚠️ `./gradlew ktlintFormat` réécrit les fins de ligne de fichiers **non modifiés** : vérifie
  `git diff --numstat` et restaure ceux à zéro ligne de diff réel.
- **Ne jamais régénérer une baseline** pour faire passer un gate.
- `cmd | tail` rend le code de sortie de `tail` : mesurer par `cmd > /tmp/log 2>&1; echo $?`.

### Ce que j'attends en fin de tour

1. La ligne cochée dans `docs/05-PARITE.md` **uniquement sur mesure sur appareil**, en nommant le test
   qui la prouve et le nombre de cas.
2. Les défauts consignés en `docs/04-PIEGES.md` (§86 et suivants), avec la **mesure** qui les établit.
3. Le gate complet vert, le décompte des ignorés **JVM et instrumentés**, et l'empreinte du modèle.
4. Un commit par `-F`, message dense, en français.
5. **Un contrôle positif** dès que tu écris un filtre ou un test qui « ne signale rien » : remets le
   défaut en place dans le **vrai code**, mesure, puis restaure — et **vérifie la restauration au
   SHA-256** si le fichier porte du travail non commité (`git checkout --` l'emporterait).
6. Relecture externe : `~/.claude/tools/audit-ia.py --provider gpt --model gpt-5.2 --diff HEAD` et
   `--provider gemini`, avec `--prompt <fichier>`.
   ⚠️ **`--prompt` et `--out` sont obligatoires.** ⚠️ `gpt-5.5`/`5.6` **n'existent pas** côté API.
   ⚠️⚠️ **`git diff HEAD` ignore les fichiers NON SUIVIS** ⇒ `git add -N` sur les fichiers neufs
   **avant** de lancer, sinon le relecteur juge un lot amputé — et le dira.
   ⚠️ Gemini rend souvent **503** : reboucler. Un rapport vide est un **échec**, pas un « rien trouvé ».
   ⚠️ **Vérifie chaque constat avant de l'appliquer**, et **relis le correctif** : sur les deux tours
   d'hier, un correctif issu d'une relecture a introduit un défaut que l'autre relecture a rattrapé.

---

## (historique) État en trois lignes — au 2026-08-19

- Dépôt : `j:\applications\notes_files_tech`, branche `master`, arbre **propre**, et **toujours aucun
  remote** — rien n'est poussé nulle part. ⚠️ Le compte de commits n'est plus écrit ici : il devenait
  faux au commit suivant. `git rev-list --count HEAD` le dit sans dériver.
- Gate **vert** au 2026-08-19 : ktlint, detekt, **230 tests JVM**, **365 tests instrumentés** (S9),
  0 échec, **0 ignoré**, 0 échec d'hypothèse — comptés par les codes de statut.
- ⚠️⚠️ **Le compte JVM se vérifie AUSSI**, depuis le 2026-08-17 : le dépôt tourne en **JUnit 5**
  (`app/build.gradle.kts:219`), et une classe de test écrite en JUnit 4 est ignorée **sans un mot**,
  sous un `BUILD SUCCESSFUL`. Le décompte fiable est la somme des `tests=` des XML de
  `app/build/test-results/testDebugUnitTest/`, et le contrôle qui tranche est la **présence du XML de
  la classe**. Cf. `04-PIEGES.md` §82.
- 🔴 **Cette ligne était FAUSSE le 08-16**, et pas de peu : elle annonçait « 0 ignoré » alors que
  `TranscriptionSurAppareilTest` — le seul test qui prouve que la dictée transcrit — était **ignoré à
  chaque exécution de la suite**, parce que celle-ci **détruisait le modèle de 57 Mo** importé à la
  main. Cf. `04-PIEGES.md` §72 et la section datée du 08-17 en fin de fichier.
- ⚠️ **La suite instrumentée se lance par `adb shell am instrument`, plus par Gradle** :
  `connectedAndroidTest` désinstalle l'application à la fin. ⚠️⚠️ Cette précaution ne suffisait
  **pas** — la destruction venait d'un test, pas de l'outil. Et avec `am instrument` il n'y a pas de
  XML : le décompte des ignorés se lit par `grep -c 'INSTRUMENTATION_STATUS_CODE: -4'` (échec
  d'hypothèse) et `-3` (ignoré), **jamais** dans le « OK (N tests) ».
- Application publiée `notes_tech` : **`be6fe0d`** sur `main`, tag **`v2.0.4`**, versionCode **2052** —
  la passerelle de migration est **publiée** depuis le 2026-08-18 (détail plus bas). Ses trois
  répertoires non suivis (`.audit_tmp/`, `_audit_results/`, `prompts/`) ne doivent **jamais** entrer
  dans l'index — pas de `git add -A`.

## (historique) RESTE À FAIRE — au 2026-08-20 au soir — ⚠️ dépassé, cf. la section du 2026-09-24

> ✅ **La bascule a eu lieu.** Les trois dernières lignes de `05-PARITE.md` sont cochées **sur
> mesure**, pas sur raisonnement. Ce qui suit est ce qui reste derrière.

### 0. ✅ Ce qui n'est plus à faire — fait le 2026-08-20

| Fait | Preuve |
|---|---|
| 3.0.0 **signée** avec le keystore de production | certificat `ddb385de…42e9`, vérifié contre l'APK 2.0.4 **réellement installé**, pas seulement contre le `.jks` |
| Bascule **2.0.4 → 3.0.0** sur le S9 | note en clair et coffre PIN relus, clair affiché |
| Bascule **2.0.3 → 3.0.0 directe** (couche ②) | après le correctif de `04-PIEGES.md` §120 — elle échouait à 100 % avant |
| Coffre PIN créé en Flutter, ouvert en Kotlin | **deux fois**, depuis 2.0.4 et depuis 2.0.3 |
| `versionCode` par ABI | 1053 / 2053 / 3053 / 4053 — cf. §121 |

⚠️ **Le `keystore.properties` n'a PAS été créé.** Le hook `pretooluse-protect` refuse d'écrire un
fichier de mots de passe, et il a raison : la signature s'est faite par `apksigner`, avec le secret
lu à la volée depuis `notes_tech/android/key.properties` et passé par variable d'environnement.
**Aucun secret nouveau n'est sur le disque.** À décider : garder cette voie, ou créer le fichier —
auquel cas il faudra d'abord l'ajouter au `.gitignore`, qui ne couvre que `*.jks` et `*.keystore`.

### 1. ✅ Phase 0 EN COURS — le rodage a commencé le 2026-08-20

Patrice a essayé la 3.0.0 sur le S9. **Rien n'a cassé** : création de notes, de dossiers,
verrouillage d'un dossier, note dans un coffre, déplacement vers un coffre, copier-coller dans une
note verrouillée, corbeille — tout répond.

Trois retours, tous traités le jour même (commit `7c9fb59`) :

| Retour | Réponse |
|---|---|
| le damier à gauche du titre, comme les autres apps | fait, **22 dp** après un premier essai à 26 jugé trop grand |
| les messages de validation sur fond noir | fond **bleu du damier `#0B60C5`**, texte blanc, contraste **6,1:1** mesuré |
| « il faudra modifier les textes légaux » | ⚠️ **non** — vérification faite, ils sont exacts (cf. ci-dessous) |

⚠️ **Le troisième retour était une supposition, pas un constat**, et la vérification a trouvé autre
chose : `04-PIEGES.md` §125, la licence MIT s'affichait **avec ses chevrons**. *Le doute portait au
bon endroit, pas sur le bon objet.*

Ce qui a été vérifié dans les textes légaux, et qui est **exact** : aucune permission Internet dans le
manifeste **fusionné** (seule `RECORD_AUDIO`), `whisper.cpp` bien en **1.8.3**, dépôt `notes_tech`
valide, et **aucune mention de Flutter** — le changement de langage ne les périme pas.

### 2. ✅ Relecture externe du lot de sécurité — faite le 2026-08-20 (`04-PIEGES.md` §126, §127)

Deux relecteurs (`gpt-5.5-pro`, `gemini-3.1-pro`) sur `FlutterSecureStorageKekSource`,
`KekRepository`, les écrans de démarrage, le fixture, le test de non-régression et le schéma de
`versionCode`. **Quatre défauts réels, dont deux dans mes correctifs du matin même** — tous corrigés,
chacun avec son contrôle positif.

🔴 Le plus grave, **vu par un seul des deux** : `destroy()` enveloppait ses quatre gestes dans un
`try` unique, si bien qu'un premier échec laissait la valeur scellée, la clé AES et l'alias RSA
**ensemble sur l'appareil**, après un mode panique.

⚠️ **Un constat était juste et inapplicable** : détruire la clé AES après usage. Mesuré sur le S9 —
`SecretKeySpec.destroy()` lève `DestroyFailedException` sans rien effacer. L'appliquer aurait produit
du code qui donne l'impression d'une protection sans en offrir. Documenté, pas écrit.

Les deux points de conception qui restaient ouverts sont **tranchés** (§127) : une clé malformée
n'arrête plus le parcours des sources, et l'APK universel n'est plus produit.

💡 **Deux relecteurs, pas un** — les sévérités ne se recoupent pas, seul le croisement des listes
est fiable. 🔴🔴 Et le coût est réel : cf. `reference_audit_ia_api` en mémoire, **demander avant**,
le **diff** et non les fichiers, **un** relecteur d'abord, effort **`medium`**.

### 3. 🔴 Le correctif de `notes_tech` n'est pas publié

`04-PIEGES.md` §123 : `lib/ui/screens/note_editor_screen.dart` est corrigé et **mesuré** (0 exception
contre 2/2 avant), mais le commit vit dans le dépôt `notes_tech` sans release. Le défaut étant
invisible en AOT release, rien ne presse — mais il partira avec la prochaine version, pas tout seul.

### 4. Ce que la bascule N'A PAS couvert

- Une base **volumineuse** : mes deux jeux d'essai comptaient une note en clair et une note de coffre.
  Rien n'a été mesuré sur une base de plusieurs centaines de notes, ni sur la durée de migration.
- Les ABI **armeabi-v7a** et **x86_64** : seul l'arm64 a été posé sur un appareil. ✅ L'APK
  **universel**, lui, n'est plus produit du tout (§127) — il piégeait qui l'installait.
- ~~Un coffre à **passphrase** créé en Flutter~~ — ✅ **fait le 2026-08-20** : créé dans la 2.0.4,
  ouvert par la 3.0.0 après bascule. Argon2id concorde sur ses paramètres réels.

### 5. Trois points écrits et volontairement non corrigés

| Point | Pourquoi il reste ouvert | § |
|---|---|---|
| L'avertissement de clair du mode panique n'est pas une `liveRegion`, et le titre assertif dit « Effacement terminé » | demande **TalkBack sur appareil** : deux régions assertives s'interrompent, et poser la seconde sans l'écouter serait deviner | §106 |
| `forcePermanently()` **est** `force()` : un `release()` en trop annulerait la demande du mode panique | **pas atteignable** — `release()` n'a qu'un appelant, qui a toujours forcé d'abord. Durcissement contre un défaut futur, pas correction d'un défaut présent | §111 |
| Le repli de `dossierEstUnCoffre` (« une base indisponible ne doit pas faire échouer un export ») n'est pas exercé | `DatabaseProvider.close()` rouvre au premier accès : un cas écrit ainsi n'atteindrait jamais le `catch` et serait un **test vacant** de plus. Le forcer demanderait de sceller la base comme le fait la panique | §107 |

### 6. Deux écarts assumés, déjà écrits — à re-décider ou à laisser

`REPRISE.md` §430 : le `tryEmit` de la corbeille perd un message si la rotation tombe pendant
l'action (le KDoc du ViewModel choisit déjà ce compromis), et `TrashViewModel.state` n'a pas de
`catch` là où `SearchUiState` a gagné un `failed`.

### 7. Côté application publiée

`notes_tech` : la MR F-Droid **`!37885`** est épinglée sur 2.0.3/51 et attend son tour. La 2.0.4
publiée le 08-18 **ne la dérange pas** — `AutoUpdateMode: Version` + `UpdateCheckMode: Tags` feront
suivre les tags une fois la MR fusionnée. ⚠️ Ne pas la toucher sans raison.

### ⚠️ Ce qu'il ne faut PAS relancer

- Le tableau de parité : il est complet, et chaque case l'est **sur une mesure**. Le rouvrir pour
  « revérifier » referait le travail sans rien mesurer de neuf.
- L'autoremplissage du champ de phrase secrète (§88) : **conservé**, décision prise, mesure à
  l'appui (`PasteText` sans `CopyText`).
- L'œil qui révèle le code à quatre chiffres : **écart assumé** avec le publié, décision de Patrice.

## ✅ Fait le 2026-08-16 : l'import du modèle

Livré en un commit, `4fcd647`. Les quatre contraintes relevées la veille ont été tenues : le modèle
va bien dans `files/stt/`, les deux étapes de panique attendues sont entrées **avec** lui, il n'y a
aucun chemin de téléchargement, et le fichier définitif n'existe jamais à moitié.

Deux relectures externes ont rendu **sept constats disjoints** — encore une fois, chacune a vu ce
que l'autre manquait. Le plus grave n'était pas dans le code neuf mais dans la capture de la veille :
`withContext` vérifie l'annulation **au moment de rendre sa valeur**, si bien qu'une portée annulée
au mauvais instant laissait un WAV de voix orphelin que plus personne ne connaissait. La garde posée
le 08-15 couvrait la boucle, et rien après. Tout est dans `docs/04-PIEGES.md` §55-§58.

### ✅ Les tests instrumentés sont passés — et le premier jet en a raté un

Lancés sur le S9 le 08-16 : **125 tests, 0 échec, 0 ignoré**. Le premier passage en a signalé un, et
il n'accusait pas le code : mon test prétendait vérifier le **refus sur la taille annoncée** en
passant un `file://`, qui ne porte pas `OpenableColumns.SIZE`. La garde nommée ne pouvait donc pas
se déclencher, et le fichier était refusé un cran plus loin, par l'empreinte.

⚠️ *Un test qui se trompe de garde ne prouve rien de celle qu'il nomme* — même s'il est vert. Il a
été scindé en deux : l'un passe par un vrai `content://` du `FileProvider` (taille annoncée ⇒ refus
avant lecture), l'autre garde le `file://` pour figer le cas de la **source muette**, où l'empreinte
doit trancher seule. Le second correspond exactement au défaut relevé par GPT-5.5.

⚠️ Toujours `ANDROID_SERIAL=22dbb7390a057ece`, et **jamais sur le S24 FE** (`RZCY41EGKYL`) : AGP
désinstalle l'application à la fin.

### Ce qui est en place

| Fichier | Rôle |
|---|---|
| `domain/voice/SpeechToText.kt` | le contrat du moteur, `SttModel`, `SttTranscription` |
| `domain/voice/SttErrors.kt` | hiérarchie **scellée** d'erreurs, classée par ce que l'utilisateur peut faire |
| `domain/voice/WavPcm16.kt` | le format, en fonctions **pures** (10 tests JVM) |
| `data/voice/VoiceCapture.kt` | la capture micro, relue par deux relecteurs (7 défauts corrigés) |
| `PanicStep.VOICE_CAPTURES_WIPE` | purge des WAV, juste après la clé |
| `domain/voice/CopieVerifiee.kt` | copie **et** empreinte en un passage, bornée, annulable |
| `domain/voice/SttModelCatalogue.kt` | les empreintes attendues — **sans champ `url`** |
| `data/voice/SttModelStore.kt` | `files/stt/`, temporaire → empreinte → `fsync` → renommage |
| `PanicStep.VOICE_CANCEL` / `VOICE_MODEL_WIPE` | interdire la dictée ; effacer le modèle |

### 🔧 Les quatre choses qui ont guidé l'import — gardées ici, elles servent encore

1. **Le modèle vit dans `files/stt/`, PAS dans `files/models/`.** Les deux répertoires sont
   distincts, et **un test côté publié vérifie que `stt/` SURVIT** à la purge des modèles hérités :
   l'utilisateur a dû télécharger puis importer ce fichier à la main, et se tromper de dossier le
   lui ferait recommencer sans explication. Cf. `legacy_model_files.dart`, qui porte l'avertissement.

2. **La panique publiée a DEUX étapes vocales**, le portage n'en a qu'une :
   - `voiceCancel`, **très tôt** — juste après `forceSecureWindow`, **avant même le
     presse-papiers** : elle coupe l'exposition immédiate, c'est-à-dire le micro encore ouvert ;
   - `voiceWipe`, **après** `dbWipe` : le `.bin`, son cache de vérification, les WAV orphelins.

   Les deux entrent **avec** l'import, jamais avant : l'énumération `PanicStep` refuse les étapes
   qui ne s'exécutent pas. ⚠️ Et l'ordre de l'énumération **est** l'ordre d'exécution — les deux se
   déplacent ensemble, `sequenceFigee` ne fige que la première.

3. **Aucun téléchargement** — décision **D-017**. `SttModel` n'a pas même de champ `url`. Le publié
   embarque le téléchargeur du plugin et **ne l'appelle jamais** ; ici c'est une **absence d'API**,
   qui ne se contourne pas par inadvertance.

4. **Les règles du fichier en clair s'appliquent**, comme pour l'export et la capture : écrire dans
   un **fichier temporaire** puis renommer, vérifier le SHA-256 **pendant** la copie (un seul
   passage sur plusieurs centaines de Mo), effacer sur échec **et dire** si l'effacement rate.

### Où sont les sources de référence

- Contrat Dart : `J:/Pub/Cache/git/files_tech_voice-dca1e1d…/lib/src/`
  (`stt_model_importer.dart`, `stt_model_downloader.dart`)
- Service publié : `j:/applications/notes_tech/lib/services/voice/voice_service.dart`
- Écrans publiés : `lib/ui/screens/voice_setup_screen.dart`, `lib/ui/widgets/voice_record_*.dart`

## La suite, une fois les tests instrumentés passés

Il ne reste de la phase 7 que **le moteur** et **l'interface**, et le premier est bloqué. Voir
ci-dessous.

⚠️ Un point à ne pas perdre, consigné en **D-019** : l'import ne pose **aucun cache de vérification**,
contrairement à l'application publiée. C'est délibéré — rien ne l'appellerait encore. Le jour où il
entrera avec le moteur, il devra partir dans **la même étape** que `VOICE_MODEL_WIPE` : un cache qui
survivrait à la purge affirmerait qu'un fichier absent a été vérifié.

## ✅ Fait aussi le 2026-08-16 : le moteur, et le texte public

**Les deux points qui attendaient une décision sont clos** (`0376482`) :

- whisper.cpp et ggml **1.8.3** vendorisés — 87 fichiers, 3,8 Mo, MIT — avec un pont **JNI écrit
  ici** et non la couche FFI Dart du greffon. 1,3 Mo écartés, dont `dr_wav.h` : le WAV est décodé en
  Kotlin. NDK **épinglé**. Cf. **D-021** et `vendor/whisper/PROVENANCE.md` ;
- `privacy.md` corrigé — et **deux autres affirmations fausses** y ont été trouvées au passage, plus
  graves que celle qui était signalée : le texte promettait un effacement « atomique et reprenable »
  alors que toute la conception repose sur l'inverse, et sa liste de ce que la panique efface
  **omettait tout le clair**. La notice MIT est désormais dans les CGU, FR et EN.

### 🔴 Les trois choses à savoir avant de continuer

1. ✅ **CLOS le 2026-08-16 — la transcription est exercée, et elle ne l'avait jamais été.** Le modèle
   est sur le S9, `TranscriptionSurAppareilTest` fait transcrire un enregistrement au contenu connu,
   et Patrice a validé la dictée à la voix. ⚠️ C'est en posant cette question pour la première fois
   qu'on a trouvé qu'elle ne transcrivait **rien** — cf. §69 et la section datée en fin de fichier.
2. ✅ **CLOS le 2026-08-16 — la règle de conservation JNI a désormais un effet mesuré.** Elle était
   écrite mais sans effet observable, faute d'appelant. Contrôlé sur l'APK **release** une fois
   l'interface en place, et **des deux côtés de la frontière** :
   - dex (`dexdump` sur `classes.dex`) : classe `WhisperNatif` **au nom conservé**, ses **neuf**
     méthodes natives présentes avec leurs signatures exactes ;
   - `.so` (`llvm-nm --dynamic --defined-only`) : les **neuf** symboles
     `Java_com_filestech_notes_1tech_data_voice_WhisperNatif_*` exportés en `T`.

   ⚠️ `WhisperStt` est renommée en `v5.n` — c'est normal et voulu : Hilt l'atteint, aucun `-keep`
   ne la vise. ⚠️ Un `grep` du nom de méthode dans le dex **ne prouve rien** : `ouvrir` peut
   appartenir à une autre classe. Il faut `dexdump`, et il faut lire les **signatures**.
3. La bibliothèque native est **empaquetée quand même**, 2,26 Mo en arm64 (vérifié dans l'APK).

## ✅ L'interface est faite — la phase 7 est close

Écran d'installation (`ui/voice/`), bouton micro dans l'éditeur, superposition d'enregistrement,
entrée dans les réglages. ⚠️ **Toute chaîne nouvelle passe par `outils/arb_vers_strings.py`**, jamais
directement dans le XML.

### 🔴 Ce que seul l'APPAREIL a montré

Quatre défauts, dont aucun n'aurait été vu à la relecture :

1. la permission du **micro était demandée avant** de savoir qu'aucun modèle n'est installé — une
   permission qu'on fait refuser durablement pour une action qui ne peut pas aboutir ;
2. la description des modèles était un champ du **catalogue**, donc du français **en dur** servi à un
   utilisateur anglophone ;
3. la superposition disait « Parlez » **avant** que le micro n'enregistre : le premier mot se perdait ;
4. 🔴 le bouton micro a porté la barre de l'éditeur à six actions et **écrasé le titre à 24 pixels** —
   c'est Patrice qui l'a vu. Le piège était **déjà documenté à quelques lignes de là**. Épingle et
   favori sont descendues dans le menu ; le titre est remonté à 312 px, mesuré.

⚠️ La mesure qui tranche : `adb shell uiautomator dump` puis lire `bounds`. Une largeur est un
nombre ; un coup d'œil voit « un titre un peu court ».

### ⚠️ Deux tours de relecture externe, et ce qu'ils ont appris

Le second tour portait **sur les correctifs du premier** — la règle du dépôt. Il a rendu six
constats : **cinq confirmés, un partiellement faux**, et deux du tour précédent avaient déjà été
écartés. *Un relecteur voit ce qu'on lui donne : son scénario est une hypothèse, pas une mesure.*

La leçon de fond, en `04-PIEGES.md` §65 : un événement qui **agit** et un événement qui **parle**
n'ont pas la même exigence — l'un doit avoir lieu une fois et pas deux, l'autre une fois et pas zéro.
Tant qu'un seul flux portait les deux, chaque correctif d'ordre introduisait le défaut inverse.

### ⚠️ Ce qui n'a jamais été exercé

**La dictée de bout en bout.** Il faut le modèle de 50 Mo sur le S9 : le télécharger sur un
ordinateur (`ggml-base-q5_1.bin`, `huggingface.co/ggerganov/whisper.cpp`), le transférer, puis
l'importer par l'écran. Tant que ce n'est pas fait, on sait que la bibliothèque native **se charge**,
et rien de plus sur la qualité, la détection de langue ou le découpage en segments.

## Après la phase 7

`docs/05-PARITE.md` porte **39 cases vides** — 10 écrans, 16 composants, 8 services, 5 promesses
publiques. « Vérifié » y veut dire **sur appareil**, pas « le code existe ». Plus **3 cases de
migration** qui ne se cochent que le jour de la bascule, sur le S9, avec une vraie 2.0.3 installée.

### ✅ 2026-08-16 : la colonne « Kotlin » est remplie, et elle a servi tout de suite

Les 34 lignes nomment maintenant leur homologue, vérifié fichier par fichier. La colonne « Vérifié »
n'a **pas** bougé : nommer n'est pas vérifier. Deux lignes n'ont délibérément aucun homologue, et
elles le disent — `sheet_handle.dart` parce que Material3 fournit la poignée, et
`blocking_progress_dialog.dart` parce que le portage traite ses deux appelants séparément.

🔴 **C'est cette seconde ligne qui a payé le remplissage.** Le composant publié était
*volontairement bloquant* ; en cherchant qui joue ce rôle côté Kotlin, on a trouvé que la feuille de
conversion ne bloquait **pas** le balayage. Mesuré, corrigé, testé — `04-PIEGES.md` §67-§68.

⚠️ La leçon vaut pour les 39 cases restantes : la question *« quel fichier joue ce rôle ? »* trouve
des défauts que la question *« est-ce que ça marche ? »* laisse passer, parce qu'elle oblige à
relire l'intention du publié et pas seulement le comportement du portage.

## ⚠️ Rappels qui ont coûté du temps aujourd'hui

- **`cmd | tail` rend le code de sortie de `tail`**, pas celui de Gradle. Mesurer avec
  `./gradlew … > /tmp/log 2>&1; echo $?`.
- **Commit `-F`, jamais `-m`** : des accents graves dans un `-m` ont été interprétés par bash et
  trois chemins ont disparu du message.
- **Lint plante parfois sur un cache périmé** (`Unexpected failure during lint analysis`) :
  `./gradlew lintDebug --rerun-tasks`.
- **Ne jamais lancer `connectedAndroidTest` sur le S24 FE** (`RZCY41EGKYL`) — c'est le téléphone
  réel de Patrice, et AGP désinstalle l'application à la fin. Appareil de test = **S9**,
  `22dbb7390a057ece`.
- **`strings.xml` est GÉNÉRÉ** par `outils/arb_vers_strings.py`. Toute chaîne du portage s'ajoute
  dans `AJOUTS_EN` / `AJOUTS_FR` du script, **jamais** dans le XML. Après génération : comparer les
  **ensembles de noms** avant/après, puis relancer — la sortie doit être identique octet pour octet.
- **Relire les correctifs de relecture.** Aujourd'hui encore, deux tours ont trouvé des défauts
  **plus graves** que le premier — dont une régression que j'avais introduite moi-même.

## 🔴 2026-08-16 — la dictée fonctionne, et ce qui reste ouvert sur sa qualité

**Elle n'avait jamais transcrit un mot.** `detect_language` ne demande pas une détection mais
**« ne fais QUE ça »** : whisper rendait 0, sans un seul segment, et l'écran traduisait ça en
« rien n'a été entendu » — un message qui accusait le micro pour un défaut du moteur.
`04-PIEGES.md` §69. Corrigé, vérifié sur appareil par Patrice.

### Ce que « beaucoup de fautes » a donné à la mesure

Patrice a ensuite signalé des fautes à l'usage. Mesuré sur le S9, **même échantillon** — une phrase
française de 10,6 s, synthétisée, donc au texte connu :

| Réglage | Langue détectée | Durée | Texte |
|---|---|---|---|
| auto (actuel) | `fr` ✅ | **8 938 ms** | identique |
| `fr` forcé | `fr` | **4 739 ms** | identique |

⚠️ **La langue n'est donc PAS la cause** : la détection ne se trompe pas, et le modèle Base
transcrit ce son quasi parfaitement, accents et ponctuation compris. Elle coûte en revanche **le
double de temps** — whisper fait une passe d'encodage entière rien que pour identifier la langue.

Les fautes viennent de la **vraie parole** face à un modèle de 57 Mo : débit, accent, liaisons, bruit
— là où une voix de synthèse est artificiellement facile. ⚠️ Ne pas conclure d'un bon résultat sur
un échantillon propre que le moteur est bon ; ce test mesure le **câblage**, pas la robustesse.

### Les trois leviers, non retenus le 2026-08-16 — **décision de Patrice, « rien pour l'instant »**

1. **Un modèle plus grand** — `ggml-small-q5_1.bin`, **190 085 487 octets** (vérifié en ligne), soit
   3,2× le Base. Meilleur gain attendu en français, ~3× plus lent. Demanderait son SHA-256 dans
   `SttModelCatalogue` — le catalogue est une liste d'**empreintes**, rien n'entre sans la sienne.
2. **Forcer la langue** de l'interface : deux fois plus rapide, texte identique. ⚠️ Réserve : mal
   servir qui dicte dans une autre langue que celle de son application.
3. **`no_context = false`** — relevé par Gemini. Ne joue qu'au-delà de 30 s de dictée : le moteur
   garde alors le contexte d'une fenêtre à la suivante, ce qui améliore la continuité **et** favorise
   les boucles de répétition. Compromis, pas correctif.

🔧 **Refaire la mesure** — le test de diagnostic a été retiré (un test qui n'affirme rien est un test
vacant), mais la procédure tient en deux commandes. Synthèse d'un échantillon au texte connu :

```powershell
Add-Type -AssemblyName System.Speech
$s = New-Object System.Speech.Synthesis.SpeechSynthesizer
$s.SelectVoice('Microsoft Hortense Desktop')
$f = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, 'Sixteen', 'Mono')
$s.SetOutputToWaveFile('fr_test.wav', $f); $s.Speak('...'); $s.Dispose()
```

⚠️ Puis le **réécrire en en-tête canonique de 44 octets** : `WavPcm16` refuse — volontairement — les
WAV qu'il n'a pas écrits, blocs `LIST` compris. Cf. `04-PIEGES.md` §70.

## 🔴 2026-08-17 — la phase 8 s'ouvre, et sa PREMIÈRE ligne coûte trois défauts

La méthode annoncée la veille — *« quel fichier joue ce rôle ? » trouve ce que « est-ce que ça
marche ? » laisse passer* — a été appliquée aux **promesses publiques** et à la **première ligne
d'écran**. Les deux ont payé.

### ✅ Trois promesses publiques sur cinq sont désormais mesurées

| Promesse | Ce qui la prouve |
|---|---|
| Zéro permission Internet | **APK release** : `RECORD_AUDIO` + la permission interne du receiver, rien d'autre |
| 100 % local | **0** bibliothèque réseau sur 940 lignes de classpath ; **0** symbole de socket dans les 4 `.so` |
| Base chiffrée au repos | WAL réel du S9 : **7,9991 bits/octet**, 256 valeurs distinctes, 0 mot-clé de schéma |

⚠️ **Le manifeste fusionné de `build/intermediates/` ne fait pas foi** — le sien datait d'avant le
moteur et l'interface. C'est l'artefact **publié** qui répond.

⚠️⚠️ **Un instrument s'est trompé, et son témoin l'a dit.** La recherche de symboles réseau rendait
« aucun » avec un motif ancré par `$`, alors que les symboles portent `@LIBC`. Le témoin positif
(`malloc`) rendait **0** lui aussi — c'est ce qui a révélé la faute. Après correction, un seul
résultat : `sendfile@LIBC`, tiré par `<filesystem>` de libc++, sans aucun `socket()` nulle part.

### 🔴🔴 §72 — la suite instrumentée DÉTRUISAIT le modèle de 57 Mo, et ignorait le test qui compte

Le plus grave de la journée, et il ne concerne pas l'interface.

`SttModelStoreTest` et `WhisperSttTest` purgeaient le **vrai** `filesDir` en `@Before` et `@After`.
Donc : le fichier importé à la main par l'utilisateur détruit à chaque exécution de la suite, et
`TranscriptionSurAppareilTest` — qui passe après, par ordre alphabétique — **ignoré en silence** par
son `assumeTrue`, sous un « OK (144 tests) » parfaitement rassurant.

**La ligne « 137 tests, 0 échec, 0 ignoré » de ce fichier était donc fausse sur son dernier tiers**, et
le test qui prouve que la dictée transcrit n'avait jamais été vert autrement que lancé **seul**.

| | Avant | Après |
|---|---|---|
| `files/stt/` après la suite | **effacé** | intact, empreinte revérifiée |
| ignorés (codes `-3` / `-4`) | **1** | **0** |
| total | 144 | **152** |

Correctif : les deux classes travaillent sur un `ContextWrapper` dont `getFilesDir()` seul est
détourné, chacune avec un **témoin** qui compare les deux chemins. Le modèle a été restauré depuis
`J:/tmp/claude/modeles/ggml-base-q5_1.bin`, droits `700`, empreinte identique au catalogue.

⚠️ La précaution du 08-16 visait `connectedAndroidTest`. Elle était juste et **ne protégeait de
rien** ici : *se protéger d'une cause connue ne dit rien des autres.*

### 🔴 §71 et §73 — deux boutons de l'accueil mal annoncés

- **Le bouton flottant n'avait aucun nom accessible.** `ExtendedFloatingActionButton` de material3
  1.4.0 enveloppe son slot `text` dans un `clearAndSetSemantics` : le libellé est **dessiné** et
  **absent** de l'arbre fusionné. Trouvé par un relevé `uiautomator` (`NAF="true"`), confirmé sur
  l'arbre de sémantique. L'application publiée porte `label` **et** `tooltip` : c'était une régression.
- **Le bouton ⋮ s'annonçait « Réglages »**, soit le nom d'**une** de ses deux entrées de menu. Le
  publié y met le `moreButtonTooltip` de la plateforme. Trouvé par un test qui cherchait autre chose.

⚠️ **Les deux relectures externes ont convergé** pour refuser mon premier correctif du bouton flottant
(nommer l'icône) au profit du nom posé sur **le bouton**, avec deux arguments distincts : annonce en
double si material3 cesse d'effacer le slot, et arrêt de focus parasite si son `mergeDescendants`
change. Elles ont aussi trouvé, toutes les deux, que mon test de badge concluait par un **compte** qui
vaut 1 aussi bien quand tout va bien que quand deux défauts s'annulent.

### ⚠️ Un garde-fou manquait dans le générateur de chaînes

La chaîne française ajoutée pour le ⋮ est entrée dans le XML avec une **apostrophe nue** — les 425
autres du fichier sont échappées, et l'en-tête du fichier généré énonce la règle. Les blocs `AJOUTS`
sont recopiés **verbatim**, donc rien ne les contrôlait ; et dans une chaîne Python non brute, `\'`
produit `'`, il faut `\'`. Le générateur **refuse** désormais, vérifié sur un cas positif.

⚠️ *Une règle écrite dans l'en-tête d'un fichier généré ne protège personne : c'est le générateur qui
doit refuser.*

### 🔧 Les trois questions à reprendre pour les 35 cases restantes

Ce ne sont pas « est-ce que l'écran marche ? » — celle-là n'a rien trouvé :

1. **Que reçoit un lecteur d'écran ?** L'arbre **fusionné** fait foi, pas l'arbre non fusionné où les
   défauts d'étiquetage sont invisibles. Le balayage mécanique est
   `AccueilTest.aucun_element_actionnable_de_l_accueil_n_est_sans_nom`, à recopier par écran.
2. **Quels états ne sait-on pas atteindre à la main ?** Bannière de brouillons perdus, échec de
   chargement, état vide de recherche. Les composables sans état les rendent accessibles en une ligne.
3. **Combien de tests ont été ignorés ?** Jamais depuis le « OK (N tests) ».

## 🔴 2026-08-17, seconde ligne de parité : la CORBEILLE, et trois défauts de plus

Quatre cases cochées — `trash_screen.dart`, `note_card.dart`, `empty_state.dart`, et le composant de
carte dans ses trois états. **31 restantes.** Suite instrumentée : **165 tests, 0 échec, 0 ignoré**,
modèle de 57 Mo intact et empreinte revérifiée après la suite (leçon §72).

### Les trois défauts, et lequel compte

1. **§75 — la corbeille annonçait « vide » avant d'avoir lu la base.** `stateIn` rend
   obligatoirement une valeur initiale ; l'écran n'avait qu'une branche `if (notes.isEmpty())`. Le
   bouton « vider » surgissait au même instant, puisqu'il dépend de `notes.isNotEmpty()`.
2. **§74a — la carte de corbeille était cliquable pour rien** (`onClick = { }`). Le publié rend sa
   tuile **sans `onTap`**.
3. 🔴🔴 **§74b — la carte de note n'annonçait pas du tout qu'on peut l'ouvrir.** La sémantique était
   sur le `Surface`, le `clickable` sur la `Column` fille : **les actions d'un descendant ne
   remontent pas au nœud fusionné**, contrairement au texte. Sur l'accueil **et** dans la recherche,
   la carte s'annonçait comme du texte.

### ⚠️⚠️ Ce que ce troisième défaut apprend, et c'est la seule chose à retenir

**Il a été trouvé par le TÉMOIN du deuxième, pas par le deuxième.** Le test « cette carte n'est pas
actionnable » se réduit à `assertHasNoClickAction()` — vacant par construction. Son témoin pose la
**même** carte avec un clic réel et exige l'inverse. Le témoin a échoué.

⚠️ **`performClick()` ne l'aurait jamais vu** : il injecte un toucher aux coordonnées du nœud et
n'exige aucune action de sémantique. C'est pourquoi `toucher_une_carte_ouvre_la_note_correspondante`
était vert depuis le premier jour sur un nœud sans `OnClick`. Et le geste marchait aussi pour un
lecteur d'écran, dont le double-appui envoie un toucher au centre du nœud focalisé — *ce que le code
fait n'est pas ce que l'utilisateur entend*.

⚠️ **Le balayage de §71 ne pouvait pas le voir** : il cherche une action **sans nom**, celui-ci était
un nom **sans action**. Le motif inverse demande son propre contrôle, et il vaut pour les huit écrans
suivants : *un nœud qui porte un nom et se comporte comme activable annonce-t-il son action ?*

### 🔧 L'outillage est désormais partagé, à réutiliser tel quel

- `app/src/androidTest/…/ui/BalayageDAccessibilite.kt` — le filtre « actionnable sans nom », extrait
  d'`AccueilTest`. Son **témoin** vit dans `BalayageDAccessibiliteTest`, à part : il valide l'outil,
  pas un écran.
- `TrashScreen` est scindé en `TrashRoute` (Hilt) + `TrashScreen` **sans état**, comme
  `HomeRoute`/`HomeScreen`. **C'est ce découpage qui rend l'état de chargement atteignable** — sur un
  téléphone, la base répond en quelques millisecondes et le défaut ressemble à un scintillement.
- ⚠️ Les écrans restants (`SearchRoute`, `SettingsRoute`, `AboutRoute`, `LegalRoute`) portent encore
  leur `hiltViewModel()` en propre : chacun demandera le même découpage avant d'être mesurable.

⚠️ **Le motif `stateIn` est à vérifier sur chaque écran qui suit** : la valeur initiale n'est pas une
donnée, c'est une **absence** de donnée, et l'écran doit savoir les distinguer. `HomeUiState` le fait
(`loading = true` par défaut), `TrashUiState` ne le faisait pas.

### ⚠️⚠️ Les deux relectures externes ont trouvé un CINQUIÈME défaut — dans MES tests

Sept constats (Gemini Pro, GPT-5.2), **aucun recoupement sur les trois qui comptaient**. Le plus
grave : mon test « le bouton de vidange reste caché pendant le chargement » était **vacant** — posé
sur une liste vide, alors que ce bouton dépend *aussi* de `notes.isNotEmpty()`. Il passait avec la
garde **et sans**.

**Deux assertions négatives vacantes dans la même journée** : l'une trouvée par mon propre témoin,
l'autre par une relecture. 🔧 *Devant toute assertion négative : quel état la rendrait fausse si le
code était cassé ?* S'il n'est pas dans le test, le test ne mesure rien.

Sont aussi entrés, tous mesurés :

- les **étiquettes** de la carte n'étaient annoncées à **aucun** lecteur d'écran — un nœud fusionné
  qui porte une `contentDescription` explicite **remplace** la lecture de ses enfants, donc tout ce
  qui n'est pas dans la chaîne construite n'existe pas. ⚠️ Le correctif a dû reproduire la garde
  `!verrouillee`, sinon il **ouvrait** la fuite que la carte ferme : « Note verrouillée, #médical,
  #divorce » n'a rien protégé. Deux assertions le figent ;
- un `Role.Button`, sans quoi TalkBack ne nomme pas ce que c'est ;
- le dialogue de suppression définitive **disparaissait à la rotation** — `rememberSaveable`, et
  l'**identifiant** au lieu de la `Note`, qui n'a pas à devenir `Parcelable` pour ça ;
- ⚠️ mon propre durcissement était faux : j'avais remplacé l'indice `[1]` par
  `hasAnyAncestor(isDialog())` **avant** la relecture, et Gemini a vu ce que je n'avais pas vu — le
  dialogue de vidange porte son libellé **deux fois**, en titre *et* en bouton. Mon sélecteur
  désignait donc deux nœuds. `hasClickAction()` ajouté.

**Deux constats laissés en l'état, par écrit** : `tryEmit` perd un message si la rotation tombe
pendant l'action (le KDoc du ViewModel choisit déjà ce compromis), et `TrashViewModel.state` n'a pas
de `catch` là où `SearchUiState` a gagné un `failed` **parce qu'un flux non gardé avait emporté
l'application** — asymétrie entre jumeaux, assumée : l'application publiée n'a pas de filet ici non
plus, et la corbeille ne lit aucune saisie utilisateur. Détail en `04-PIEGES.md` §74.

## 🔧 La suite immédiate : `search_screen.dart`

Le défaut §75 y est **déjà localisé et confirmé par lecture des deux côtés**, non corrigé : le `when`
de la recherche passe de `query.isBlank()` à `failed` puis à `results.isEmpty()`, sans branche pour
« la requête est posée, la réponse n'est pas là ». Avec l'anti-rebond de 200 ms, « Aucun résultat.
Essayez un autre mot-clé » paraît à chaque salve de frappe — et **accuse la saisie** pour une réponse
qui n'est pas encore arrivée. Le publié rend un indicateur (`search_screen.dart:109`) : c'est une
**régression du portage**.

Le corriger demande le même découpage sans état que la corbeille — d'où le fait de le laisser à sa
propre ligne plutôt que de le traiter à part sans test.

## ✅ 2026-08-17, ligne 3 : la RECHERCHE — le premier défaut trouvé par balayage de motif

Une case de plus, **30 restantes**. 183 tests JVM + 174 instrumentés, 0 échec, 0 ignoré, modèle
intact.

**Le défaut était déjà localisé avant d'ouvrir la ligne**, par le balayage du motif §75 sur les quatre
`stateIn` du portage : la recherche affichait « Aucun résultat. Essayez un autre mot-clé » **pendant**
la recherche. Le `combine` mêle deux flux de rythmes différents — la saisie émet à chaque frappe, les
résultats passent par un freinage de 250 ms puis par une requête — donc l'état portait la **nouvelle**
requête et l'**ancienne** issue. Le message accusait la saisie de l'utilisateur. Le publié rend un
indicateur (`search_screen.dart:109`) : régression du portage.

⚠️ *Premier défaut du portage trouvé par un motif plutôt que par l'examen d'un écran.* La leçon
réutilisable : **un défaut nommé se cherche ensuite partout où son motif existe.**

### 🔧 Le mécanisme, réutilisable tel quel

Faire porter à la réponse **la question à laquelle elle répond** :
`Issue(pour: String?, resultats, echec)`, puis `repondALaSaisie = issue.pour == texte`.

- ⚠️ `pour` est **nullable**, pas vide par défaut : `null` veut dire « aucune réponse pour aucune
  requête », alors qu'une chaîne vide serait **égale** à une saisie vide, donc lue comme une réponse.
- ⚠️ `failed` n'est retenu que si l'issue répond à la saisie courante — l'échec d'une requête
  abandonnée n'accuse pas la suivante. `failed` et `searching` sont donc **exclusifs par construction**,
  et l'écran s'appuie sur cette exclusivité pour ordonner ses branches.
- ⚠️⚠️ La condition d'affichage est `searching && results.isEmpty()`, **et la seconde moitié compte** :
  sans elle, chaque frappe remplacerait la liste par un indicateur pendant 250 ms. Le correctif évident
  est plus simple à écrire et introduit un clignotement à chaque lettre. Un test le fige.

### 🔴 Deux niveaux de test, parce qu'un seul aurait été vacant

`RechercheTest` pose `searching` **à la main** : il prouve ce que l'écran fait d'un état, jamais que
quelque chose produit cet état. D'où l'extraction de la transformation en **fonction pure**
`etatDeRecherche(texte, issue, noms)`, testée sur la JVM (`RechercheEtatTest`, 7 cas dont un balayage
d'exclusivité sur 24 combinaisons). `SearchRepository` et `FoldersRepository` sont des classes
concrètes bâties sur un `DatabaseProvider` — le ViewModel entier n'est pas exerçable hors appareil,
cette fonction l'est.

⚠️ **La valeur initiale de `stateIn` passe par la même fonction**, avec `Issue()` : c'est par la valeur
initiale que §75 était entré, et deux chemins vers le même état demanderaient deux vérifications.

### ✅ Le balayage `stateIn` est CLOS — et trois candidats sur quatre n'étaient pas des défauts

| État | Verdict mesuré |
|---|---|
| `SettingsUiState` | sa valeur initiale est **lue** (`settings.themeNow()`), pas supposée |
| `TrashUiState` | 🔴 défaut réel, corrigé (§75) |
| `SearchUiState` | 🔴 défaut réel, corrigé (§76) |
| `FoldersUiState` | rien à faire : les **deux seuls** usages de `state.inbox` du dépôt (`FoldersDrawer.kt:119` et `:124`) traitent son absence **exprès**, avec un repli documenté — et `HomeRoute` collecte cet état dès sa première composition, tiroir fermé, donc la fenêtre n'est pas atteignable par le tiroir |

⚠️ *Un balayage de motif rend des candidats, pas des défauts.* Corriger les quatre au motif que le
motif existe aurait fait modifier du code correct — dont un repli délibéré, commenté comme tel.

## 🔴 2026-08-17, ligne 4 : les RÉGLAGES — un interrupteur muet, et quatre défauts dans mes tests

Une case de plus, **29 restantes**. 183 JVM + **187 instrumentés**, 0 échec, 0 ignoré, modèle intact.

**Le balayage d'accessibilité a rendu un rectangle de 156 × 96 px** — soit exactement un `Switch` de
52 × 32 dp à 3×, identifié par l'arithmétique. Un `Switch` posé en `trailingContent` d'un `ListItem`
est un nœud **séparé** de celui qui porte le texte : il détient l'action et l'état, la ligne détient le
libellé, rien ne les relie. Annoncé « interrupteur, activé », sans dire de quoi. Le publié emploie un
`SwitchListTile`, qui rend un seul nœud ⇒ **régression du portage**. §77.

⚠️⚠️ **Le correctif était déjà écrit dans le même fichier**, appliqué à ses boutons radio, avec le
commentaire qui l'explique. *Un idiome correct appliqué à un composant et pas à son voisin est plus
difficile à voir qu'une absence d'idiome* — le fichier avait l'air cohérent.

### ⚠️ Quatre de mes six premiers échecs ne visaient pas le code — à retenir

1. 🔧 **`clickable(enabled = false)` CONSERVE son action `OnClick`** et pose `Disabled` à côté. Se
   mesure par **`assertIsNotEnabled`**, jamais par `assertDoesNotExist`. ⇒ Le balayage **voit** les
   actionnables désactivés, et c'est voulu : un bouton grisé sans nom reste un bouton sans nom.
2. **`LocalSecureWindow` n'a aucun défaut, exprès**, et il a levé son message sur mes deux tests du
   dialogue de panique. Le garde-fou a fait son travail — un contrôleur muet aurait laissé le test vert
   sur un écran non protégé. Le test fournit un contrôleur **réel** (sa chaîne ne demande qu'un
   `Context`, et `SecureWindowGuard` ne touche qu'un compteur en mémoire).
   ⚠️ Non mesuré, et dit plutôt que contourné : que le dialogue pose bien `FLAG_SECURE`. `activeNow()`
   mêle le compteur au réglage utilisateur ⇒ le vérifier demanderait d'écrire dans les préférences
   réelles, ce que §72 interdit à un test.
3. **`home_sort_mode` sert DEUX fois sur cet écran** (titre de section + ligne) ⇒ `onNodeWithText` seul
   désignait deux nœuds. *Sur un écran de réglages, un libellé réutilisé est la règle.*

### 🔴 Ce que le mode panique doit au découpage sans état

`SettingsScreen` ne reçoit qu'un booléen et un rappel, donc la confirmation, son annulation, **le refus
de confirmer sans le mot-clé** et la désactivation de la ligne pendant l'effacement se mesurent **sans
rien détruire**. À travers le vrai `PanicViewModel`, ce test effacerait la base du S9 **et le modèle
vocal de 57 Mo** — le sinistre de §72, mais volontaire.

⚠️ Le mot est saisi **en minuscules** exprès : la comparaison ignore la casse, et c'est un choix écrit
(« quelqu'un sous stress tape sans majuscule »). Le vérifier en majuscules laisserait ce choix non
mesuré.

⚠️ Restent dans la `Route`, et ne descendront pas : l'annonce du changement de langue avec la recréation
de l'activité (`LocalActivity`, `LocalView`) et le recouvrement de panique, qui appelle `exitProcess`.

## ✅ 2026-08-17, balayage de cohérence sur tout `ui/` + le second instrument

**192 tests instrumentés** (187 avant), 183 JVM, 0 échec, 0 ignoré, modèle intact. 29 cases de parité.

### Un constat, sur le motif des cinq défauts de la journée

Le bouton micro de l'éditeur portait `voice_setup_title` — le titre d'un **autre écran**, que ce bouton
n'ouvre pas — alors que `note_editor_tooltip_dictate` existait, traduite des deux côtés, et n'était lue
**nulle part**. Le publié l'emploie précisément là (`voice_record_button.dart:59`). Corrigé.

⚠️ **Aucun défaut audible** : les deux valeurs coïncident dans les deux langues. Défaut **latent**, §79.

🔧 Le discriminant, déjà éprouvé en phase 6 : *une chaîne traduite des deux côtés et lue nulle part est
un signal* — puis *son jumeau est-il utilisé dans le publié, et pour quoi ?*

Les sept autres répertoires (`folders`, `vault`, `voice`, `panic`, `about`, `splash`, `common`) sont
revenus **sains** sur ce motif, vérifiés un par un.

### 🔴🔴 Le second balayage existe enfin — et il a demandé TROIS versions

`actionsPerduesALaFusion()` dans `ui/BalayageDAccessibilite.kt` : le motif **inverse** de
`actionnablesSansNom`, c'est-à-dire un **nom sans action**. `05-PARITE.md` le promettait depuis §74 et
il n'existait pas.

**Les deux premières versions rendaient 0 sur tout**, y compris sur la faute :

1. « remonter au premier ancêtre fusionnant, **soi-même inclus** » — **`Modifier.clickable` fusionne
   lui-même ses descendants**, donc le premier nœud fusionnant est toujours le nœud cliquable ;
2. « un actionnable de l'arbre non fusionné **absent** de l'arbre fusionné » — le nœud cliquable
   **existe** dans les deux. §74 n'est pas une absorption, c'est **deux nœuds distincts**.

⚠️⚠️ **Seul le témoin l'a dit** — troisième fois de la journée après le `grep` ancré par `$` et
l'assertion négative sur la carte de corbeille : *un filtre qui ne signale rien est indiscernable d'un
code sans défaut.*

### ✅ Contrôle positif sur le VRAI code, pas seulement sur un vecteur

Le défaut §74 a été **remis en place dans `NoteCard.kt`** le temps d'une mesure sur le S9. Les deux
tests ont échoué comme attendu, le second en rendant `Rect(36, 636, 1044, 930)` — la carte de note.
Fichier restauré par **`git checkout --`** : le `cp` de sauvegarde s'était révélé douteux, et git est la
seule source qui ne mente pas sur ce qu'elle contient.

⚠️ Les quatre écrans mesurés passent ce second balayage : il ne trouve **rien de neuf aujourd'hui**. Sa
valeur est le filet de régression, et les cinq écrans restants.

## 🔴 2026-08-17, ligne 5 : l'ÉDITEUR — deux champs anonymes, et un test qui n'a jamais tourné

Deux cases de plus — `note_editor_screen.dart` et `backlinks_panel.dart` —, **27 restantes**.
**194 tests JVM** (183 avant) et **215 instrumentés** (192 avant), 0 échec, **0 ignoré**, modèle de
57 Mo intact et empreinte revérifiée après la suite.

`NoteEditorScreen` est le cinquième écran scindé en `NoteEditorRoute` + composable **sans état**. Ici
le découpage ne rend pas atteignables une ou deux fenêtres rares mais **six états** : les quatre
issues de chargement, l'échec d'enregistrement, et sa raison nommée. C'est aussi le seul écran du
portage où « essayer pour voir » n'est pas neutre — l'essai écrit.

### 🔴🔴 §80 — les deux zones de saisie n'avaient AUCUN nom accessible

Elles n'avaient qu'un `placeholder`. Mesuré sur le S9, arbre fusionné, trois champs :

| Champ | `EditableText` | nom annoncé |
|---|---|---|
| `label` + contenu | `valeur-A` | **`[libelle-A]`** |
| `placeholder` + contenu | `valeur-B` | **`null`** |
| `placeholder` + **vide** | `` | `[indice-C]` |

Un placeholder ne nomme le champ **que tant qu'il est vide** — c'est-à-dire exactement l'état sous
lequel un éditeur se relit. Sur une note ouverte, un lecteur d'écran annonçait deux zones **anonymes**.
Le publié porte `labelText` sur les deux : régression de parité.

⚠️⚠️ **Aucun des deux balayages ne pouvait le voir.** `actionnablesSansNom` **exclut** les nœuds
portant un `EditableText` — au motif, juste, qu'un champ vide n'est pas un défaut d'étiquetage — et
`actionsPerduesALaFusion` ne regarde que les actionnables. *Une exclusion raisonnable dans un
instrument est un angle mort dans tous les écrans qu'il a validés.*

D'où `champsDeSaisieSansNom()`, troisième instrument, son témoin à trois cibles — la troisième étant
le champ **vide**, qui ne doit **pas** être signalé — et un **contrôle positif sur le vrai code** : les
deux `label` retirés le temps d'une mesure rendent bien deux rectangles, ceux des deux champs.

⚠️ Restauration **par l'inverse exact de l'édition, vérifiée au SHA-256**, et non par `git checkout --`
comme au §78 : le fichier portait tout le travail non commité de la session. *Une technique de
restauration se choisit d'après l'état du fichier, pas d'après l'habitude.*

🔧 Et c'est une **chaîne orpheline** qui disait où poser le libellé : `note_editor_content`, traduite
des deux côtés, lue nulle part, employée par le publié comme `labelText` de ce champ exactement. Même
discriminant qu'au §79, même écran, même jour.

### 🔴 §81 — le titre n'était pas plafonné à la saisie

`saveEdits` refuse au-delà de 200 caractères, et refuse **le titre et le corps ensemble**. Un
paragraphe collé dans le titre gelait donc **tous** les enregistrements de la note ; quitter l'écran
emportait le texte en silence, l'enregistrement au départ échouant lui aussi. Le publié pose un
`LengthLimitingTextInputFormatter` et rend l'état inatteignable.

La règle a demandé **quatre** versions : deux arrêtées par l'appareil, **deux par les relectures
externes** — dont la dernière portait sur le correctif de l'avant-dernière. Elle distingue une
**insertion** d'un **remplacement** par le préfixe et le suffixe communs, et ne refuse que l'insertion
faite ailleurs qu'à la fin, seul cas où rogner la fin détruirait de l'existant.

⚠️ **Le geste de mesure était vacant, et c'est une mesure qui l'a dit** : dans ce harnais, sur un titre
de 250 caractères, ni une frappe ni un collage de 300 caractères ne produisent un candidat plus long
que le texte en place. Aucune saisie ne peut donc produire la croissance que la garde refuse. D'où la
table JVM `PlafondDuTitreTest`. *Un geste de test peut être vacant comme une assertion peut l'être.*

### 🔴🔴 §82 — un fichier de test JUnit 4 dans un dépôt JUnit 5 ne tourne pas, sous un gate vert

`PlafondDuTitreTest` avait `import org.junit.Test`. `app/build.gradle.kts:219` porte
`useJUnitPlatform()` : la classe a été ignorée **sans erreur, sans avertissement, sans rapport**, et
`BUILD SUCCESSFUL` s'est affiché.

Ce qui l'a dit : **183 tests avant, 183 après**, sept ajoutés. Et l'absence du XML de la classe dans
`app/build/test-results/`. Jumeau exact de §72 côté JVM — *une ligne verte ne dit rien de ce qui n'a
pas tourné* — et **la forme du contrôle est la même : compter, et comparer à ce qu'on attendait**.

Motif balayé sur tout le dépôt dans la foulée : aucun autre fichier, 20 classes pour 20 rapports.

### 🔧 Un défaut LOCALISÉ pour la ligne suivante, non corrigé

`link_autocomplete_sheet.dart` : `suggestionsDeLien` vide sa liste à chaque frappe et ne la remplit
qu'après 120 ms — c'est voulu, et documenté. Mais pendant cette fenêtre, `proposerLaCreation` vaut
**vrai** par construction, et la garde que le portage a ajoutée exprès — *« si le titre tapé existe
déjà, on le lie »* — consulte une liste **vide**. Valider au clavier dans les 120 ms crée le doublon
que cette garde existe pour empêcher.

⚠️ **Pas une régression** : le publié affiche « Créer … » dans la même fenêtre et sa `_onSubmit` crée
toujours. C'est la divergence délibérée du portage qui est **incomplètement efficace**. Le mécanisme du
correctif est déjà écrit et éprouvé (§76 : faire porter à la réponse la question à laquelle elle
répond). À traiter à sa propre ligne.

### ⚠️⚠️ Les deux relectures externes, et la seconde a rattrapé le correctif de la première

Deux tours (GPT-5.2, Gemini 3.1 Pro), **quatre constats retenus, aucun recoupement**. Encore une fois,
chacune a vu ce que l'autre manquait — et cette fois la seconde portait **sur le correctif** de la
première, ce qui est la règle du dépôt et n'avait jamais autant payé.

| Constat | Sort |
|---|---|
| 🔴 GPT — **une troncature au milieu détruit du texte existant** : titre de 180, collage de 50 en tête, la troncature à 200 emporte les 30 derniers caractères **du titre**, en silence | **CONFIRMÉ, corrigé.** Le portage n'a pas la sélection sous la main — le titre est une `String` — mais il n'en a pas besoin : la comparaison des deux chaînes suffit à reconnaître une insertion |
| 🔴🔴 Gemini — **le correctif de GPT interdisait tout remplacement** : `startsWith(actuel)` refuse « tout sélectionner puis coller », en silence, alors que le même collage dans un champ vide passe | **CONFIRMÉ, corrigé.** Un correctif de relecture est du code neuf. Discriminant final : *préfixe commun + suffixe commun couvrent-ils le texte en place ?* — si oui c'est une **insertion**, sinon un **remplacement**, et seule l'insertion ailleurs qu'à la fin est refusée |
| 🔴 Gemini — **l'angle mort de ma table JVM** : elle mesurait le collage en tête et à la fin, pas le remplacement. C'est cette absence qui a laissé passer le défaut ci-dessus | **CONFIRMÉ, corrigé** — deux cas ajoutés, dont l'insertion **au milieu**, que le seul `startsWith` laissait passer dans l'autre sens |
| 🔴 Gemini — **mon test d'écran partait d'un titre VIDE**, et un champ vide passe n'importe quelle garde qui regarde le texte en place | **CONFIRMÉ, corrigé** — un test de plus, sur un titre existant écrasé par `performTextReplacement`. *Le choix des données initiales d'un test peut désarmer la garde qu'il croit mesurer* |
| 🔴 GPT — le témoin du troisième balayage n'assertait que `hasSize(1)`, pas **lequel** est signalé | **CONFIRMÉ, corrigé.** Il compare désormais les **coordonnées** du champ fautif, relevées sur son étiquette de test. Le jour où le filtre signale le champ **vide** à la place, il tombe |
| GPT — `state.title` capturé par la lambda pourrait être **périmé** | **ÉCARTÉ, avec l'argument.** L'invariant qui compte est *un titre parti sous la limite n'y repasse jamais* : le plafond ne dépasse 200 que si `actuel` dépasse 200, et `actuel` est une valeur **déjà acceptée** de l'état. Une lecture périmée est donc une valeur antérieure, elle aussi sous la limite — la récurrence tient quelle que soit la fraîcheur |
| GPT — comptage en **graphèmes** plutôt qu'en unités UTF-16 | **ÉCARTÉ sur le comptage, RETENU sur la coupe.** La limite doit rester en unités UTF-16, comme celle du dépôt *et* comme celle du dépôt Dart publié : compter des graphèmes ferait passer des titres que `saveEdits` refuserait ensuite, c'est-à-dire exactement le défaut que ce plafond ferme. En revanche la **coupe** pouvait scinder une paire de substituts et laisser un demi-caractère : corrigé, avec son cas |

⚠️ **Ce que GPT n'a pas pu voir, et pourquoi** : `git diff HEAD` **ignore les fichiers non suivis**. Son
premier tour n'a donc jamais reçu `PlafondDuTitre.kt` — il l'a dit lui-même, deux fois, en refusant de
conclure. Corrigé par un `git add -N` sur les trois fichiers neufs avant le second tour. *Un relecteur
qui annonce qu'il lui manque un fichier a raison ; c'est le harnais qu'il faut corriger, pas son
constat.*

### ✅ Le troisième balayage rétro-appliqué aux quatre écrans déjà cochés — rien de neuf, et c'est le résultat

`champsDeSaisieSansNom` est né au cinquième écran : les quatre premiers avaient donc été cochés par
**deux instruments aveugles à ce motif**. Les rouvrir était la seule façon de savoir si leur case
« Vérifié » disait la vérité. **Elle la disait** — l'éditeur était le seul cas. 219 instrumentés.

| Écran | Champs | Mesure |
|---|---|---|
| Accueil | 1, la recherche | vert, requête **remplie** |
| Recherche | 1, la requête | vert, requête **remplie** |
| Réglages | 0 dans l'écran, 1 dans le dialogue de panique | vert, champ **rempli** |
| Corbeille | **0** | fil-piège : aucun nœud éditable |

🔴🔴 **Chaque test compte d'abord ses champs.** Un balayage qui n'a rien trouvé **à balayer** est vert
lui aussi — §78 appliqué au troisième instrument. Le sélecteur `CHAMP_DE_SAISIE` est donc exposé à
côté des balayages, et le compte attendu est écrit **par écran**, jamais « au moins un ».

⚠️ Un champ **vide** ne discrimine rien (son placeholder le nomme) ; sur un écran **sans** champ,
appeler le balayage serait l'assertion creuse elle-même. Détail en `04-PIEGES.md` §83.

⚠️ Les feuilles de coffre — là où l'on saisit une phrase secrète — ne sont mesurées par **aucun** test
d'écran : `FermetureDeFeuilleTest` pose une feuille **synthétique**. C'est l'endroit où un champ sans
nom coûterait le plus, et il attend sa ligne de parité.

## ✅ 2026-08-17, ligne 6 : la FEUILLE D'AUTOCOMPLÉTION — son défaut était écrit d'avance

Une case de plus — `link_autocomplete_sheet.dart` —, **26 restantes**. **202 JVM + 228 instrumentés**,
0 échec, 0 ignoré, modèle intact.

🔴🔴 **La feuille proposait de CRÉER une note avant d'avoir cherché si elle existe**, et sa validation
au clavier créait l'homonyme que le portage refuse **exprès**. Pendant les 120 ms de freinage la liste
est vide par construction, et « vide parce que je n'ai pas cherché » était indiscernable de « vide
parce qu'il n'y a rien ».

⚠️ **Pas une régression de parité** — le publié crée toujours. C'est la divergence **délibérée** du
portage qui était incomplètement efficace, ce qui est plus dangereux : elle est écrite comme une
garantie. §84.

🔧 Mécanisme de §76, la réponse porte sa question — mais **pas ses valeurs limites** : ici la chaîne
vide est une **vraie** réponse. ⚠️⚠️ Et une validation au clavier pendant l'attente est **retenue**,
ni exécutée ni jetée.

🔴 **Premier balayage sur une feuille** : il signale un actionnable muet posé par
`BottomSheetDefaults.DragHandle` — **deux** nœuds aux mêmes coordonnées, dont un sans nom avec un
`OnLongClick` seul. Pas du portage, pas nommable depuis l'appelant, et il paraîtra sur **toutes** les
feuilles restantes. Exception nommée **dans le test**, jamais dans l'instrument partagé.

⚠️ **Un intermittent connu, le premier de la suite** : une exécution sur quatre a rendu
`le_bouton_de_vidange_reste_cache_pendant_le_chargement_meme_avec_des_notes` en échec
(« is not displayed »), non reproduit seul, en paire, ni en suite complète. Test non touché par ce lot.
Écrit pour ne pas être redécouvert à froid.

### ✅ Les deux fragilités de la relecture sont MESURÉES, pas mieux documentées — §85

Elles étaient vraies et non corrigées, pour la même raison écrite deux fois : le ViewModel n'est pas
exerçable hors appareil, et les tests d'écran **injectent** la réponse.

`fluxDeSuggestions` est donc **sorti du ViewModel** — extension sur `Flow<String>` — et quatre cas JVM
en **temps virtuel** figent le contrat : la réponse porte la saisie **brute** (espace final compris),
`pour = null` part **avant** le freinage, une saisie vide répond sans chercher, et une frappe pendant
le freinage annule la recherche en cours.

✅ **Contrôle positif** : `pour = texte.trim()` posé dans le vrai code fait bien tomber le test du
contrat. Restauration vérifiée au SHA-256. ⚠️⚠️ *Une fragilité qu'on sait seulement écrire est une
fragilité qu'on ne saura pas voir revenir — un commentaire ne tombe pas quand le code change.*

---

## 🔴 2026-08-18, lignes 7 à 10 : les FEUILLES DE COFFRE — trois défauts, et trois soupçons réfutés

Quatre lignes de `docs/05-PARITE.md` d'un coup, parce qu'elles vivent dans **un seul** fichier Kotlin,
`ui/vault/VaultSheets.kt` : `vault_pin_sheets.dart`, `vault_passphrase_sheets.dart`,
`passphrase_text_field.dart`, `vault_warning_banner.dart`. **23 cases vides** restantes (27 avant).

Gate : ktlint, detekt, lint `--rerun-tasks`, **206 tests JVM**, **248 tests instrumentés** (S9),
0 échec, **0 ignoré**, modèle de 57 Mo intact et empreinte revérifiée après la suite.

### Le découpage, et pourquoi il était obligatoire

`FermetureDeFeuilleTest` était le seul test de ce fichier — et il mesurait une feuille **synthétique**,
quatre lignes recopiées à la main. Il a rendu un vrai service le 08-16 (il a départagé deux relectures
qui se contredisaient), mais il ne disait **rien** des vraies feuilles. Quatre de leurs états ne
s'atteignent pas au doigt : coffre effacé, temporisation, conversion partielle, phase de chiffrement.

`PinSheet`/`PassphraseSheet` restent branchées à Hilt et délèguent à `FeuilleDeCode`/
`FeuilleDePhraseSecrete`, qui ne reçoivent qu'un `VaultSheetState` et des rappels. Même découpage que
`HomeRoute`/`HomeScreen`. Effet de bord utile : les trois chemins de sortie (Retour, balayage,
« Annuler ») faisaient **deux gestes différents pour un effet identique** — un jumeau de moins.

### Les trois défauts

- **§86 — la destruction du coffre n'était annoncée à personne.** Aucune région active sur ces deux
  feuilles, dans **aucun** état. Le publié en a trois. `PanicScreens.kt` savait déjà le faire :
  *l'idiome existait et n'avait pas traversé.*
- **§87 — la garde anti-décalage réservait UNE ligne.** À `font_scale 2,0` la touche « 5 » descend de
  **96 px (32 dp)** quand un message apparaît, soit 40 % du pas entre deux touches. À 100 %, zéro —
  d'où l'invisibilité. *Une garde peut être réelle et dimensionnée sur le seul cas que son auteur avait
  sous les yeux.*
- **§89 — après l'effacement, le pavé et « Valider » restaient actifs.** Retaper un code sur un coffre
  détruit fait remonter un refus qui écrase la phrase annonçant la destruction. Le garde existait pour
  la conversion partielle et pas pour l'effacement : **troisième jumeau asymétrique du même fichier.**

### 🔴 Ce qui a été RÉFUTÉ — §90, et c'est le résultat le plus utile

Trois soupçons sérieux, tous fondés sur une lecture du code publié, sont tombés à la mesure : les
champs **gardent** leur nom une fois remplis, `EditableText` ne porte que des **puces**, et Compose
**retire déjà** `CopyText`/`CutText` d'un champ à transformation mot de passe. Le `contextMenuBuilder`
du publié n'a donc pas d'équivalent à écrire.

⚠️⚠️ Le troisième a coûté **trois instruments**. Un espion de `TextToolbar` n'a rien vu — **ni sur le
témoin**, donc il ne prouvait rien. C'est la sémantique qui a tranché, avec son témoin. *Un échec
d'outillage n'est pas un verdict négatif*, et ce dépôt vient de le repayer.

⚠️ Deuxième instrument vacant du jour : une `ModalBottomSheet` compose dans une fenêtre qui **repose
ses propres `CompositionLocal`**. `LocalDensity` fourni au-dessus d'elle n'y entre pas — d'où deux
mesures à l'échelle ×2 **identiques au pixel près**, ce qui est impossible et a dénoncé l'instrument.
La taille de texte se change par `adb shell settings put system font_scale`, et se **restaure**.

### Contrôle positif

Les trois défauts ont été **remis dans le vrai code** en même temps : exactement les **six** tests
attendus sont tombés, et aucun autre. Fichier restauré par l'édition inverse, **vérifié au SHA-256**.

### Points ouverts, à décider et non à redécouvrir

- **§88 — le champ de phrase secrète est éligible à l'autoremplissage.** Mesuré : `ContentType` vient
  de `KeyboardType.Password`. Le publié s'en retire exprès (décision U1 v1.0.9). Le seul levier interne
  à Compose échangerait l'opt-out contre le comportement mot de passe du clavier — un moins bon marché.
  Le levier plateforme existe mais son **effet** n'est pas mesurable ici. **Écrit, non corrigé.**
- **L'œil du code à quatre chiffres** est un ajout du portage ; le publié le **refuse** et donne sa
  raison (défense contre le regard par-dessus l'épaule). Le commentaire d'`arb_vers_strings.py` note
  l'ajout mais pas le refus. Décision de Patrice.
- **La promesse publique « Coffres par dossier » reste décochée** : elle porte sur la crypto du
  service, pas sur ces feuilles. Rien de ce tour ne la mesure.

### Deux commentaires de plus qui mentaient — les miens, attrapés avant le commit

Écrits dans ce lot même, et corrigés : le KDoc de `FeuilleDePhraseSecrete` annonçait « cinq états »
dont « un coffre effacé », alors que `VaultPinWipedException` n'est levée que par `unlockWithPin`
(vérifié : ses trois sites y sont tous) ; et le KDoc de `plusRienAEssayer` promettait « ni pavé, ni
champ » alors que la feuille à phrase secrète gardait ses deux champs. Le second a été corrigé **dans
le code**, pas dans le commentaire.

⚠️ Un relecteur externe a par ailleurs rendu un rapport entier sur **des fichiers absents du diff**
(`NoteEditorScreen.kt`, `PlafondDuTitre` — le lot de la veille), avec des numéros de ligne préfixés de
`~`. Rapport jeté. *Un constat qui ne cite pas une ligne réelle du diff n'en est pas un.*

---

## 🔴 2026-08-18, lignes 11 et 12 : le TIROIR DES DOSSIERS — un geste destructeur qui s'évaporait

`folders_drawer.dart` et `folder_dialogs.dart`. **21 cases vides** restantes (23 avant).

Gate : ktlint, detekt, lint `--rerun-tasks`, **211 tests JVM**, **267 tests instrumentés** (S9),
0 échec, **0 ignoré**, modèle de 57 Mo intact.

### La prémisse du tour était fausse, et il a fallu vingt minutes pour le voir

`PROMPT-REPRISE.md` proposait ce tour parce que « c'est le tiroir qui appelle les feuilles de coffre,
et rien ne mesure ce qu'il fait des issues qu'elles remontent ». **C'est faux** : dans le portage,
`HomeRoute` et `NoteEditorScreen` les appellent. Le publié, lui, les ouvre bien depuis son tiroir.
*Une proposition écrite la veille se vérifie comme le reste.*

### §91 — le défaut, sur le chemin le plus destructeur de l'application

Deux gestes déchiffrent tout un dossier, donc exigent une session ouverte : **retirer la protection**
et **supprimer en gardant les notes**. Un seul mémorisait son intention avant d'ouvrir la feuille de
déverrouillage. Le parcours réel de l'autre : confirmer, saisir le secret, le coffre s'ouvre… et le
dossier est toujours là, sans un mot.

⚠️⚠️ **Le KDoc du porteur décrivait exactement ce mal — pour l'autre geste**, quinze lignes plus haut :
*« Sans ce report, l'utilisateur confirmait le geste le plus destructeur de l'application, saisissait
sa phrase secrète… et il ne se passait rien. »* Juste, au bon endroit, et sans effet. *Un commentaire
qui nomme un défaut ne protège que la ligne qu'il commente.*

🔧 Correction par **suppression du jumeau** : `GesteDeDossier` (interface scellée), une décision
unique `deverrouillageRequis`, un seul chemin `lancerLeGeste`, un `when` exhaustif à l'exécution.

⚠️ Et une limite écrite plutôt que masquée : les 5 cas JVM mesurent la **décision**, pas le câblage.
Prouver le câblage demanderait Hilt + base chiffrée + coffre réel. Ce qui protège cette partie est
**structurel** — un seul chemin, un `when` exhaustif — et non mesuré.

### §92 — le reste du tiroir : rien de cassé

Le soupçon principal est tombé : un `IconButton` posé dans le slot `badge` d'un
`NavigationDrawerItem` — un cliquable **dans** un cliquable qui fusionne — **reste atteignable**.
Mesuré par **le rappel qui part**, pas par la présence d'un nœud : `onNodeWithContentDescription`
aurait rendu la rangée fusionnée, dont le nom contient bien « Options du dossier », et
`assertHasClickAction` serait passé pendant qu'un appui sélectionnait le dossier.

Trois écarts écrits, non corrigés : **aucun appui long** dans le portage (assumé, trois raisons) ;
`FolderEvent.Deleted` porte un décompte que **personne ne lit** ⇒ supprimer un dossier déplace ses
notes en silence ; les feuilles de coffre ouvertes par `HomeRoute` et non par le tiroir. Et le tiroir
**en cours de chargement** affiche une boîte de réception de repli — motif §75/§76, hérité du publié,
**figé par un test plutôt que corrigé**.

⚠️ Un commentaire de plus corrigé : le KDoc du tiroir disait « l'appui long fait la même chose » —
vrai du publié, **faux d'ici**. Sixième de la série.

### Contrôles

Décompte des actionnables **mesuré** (9) et posé **avant** le balayage — §83. Contrôle positif sur
`deverrouillageRequis` : la clause `isVault` retirée du vrai code fait tomber un cas ; restauration
vérifiée au **SHA-256**. Compte JVM vérifié classe par classe : 206 → **211**, XML de
`GesteDeDossierTest` présent — le piège §82 ne s'est pas refermé.

---

## 🔴 2026-08-18, lignes 13 et 14 : LA DICTÉE — un écran aux balayages verts qui ne disait rien

`voice_recording_overlay.dart` et `voice_record_button.dart`. **19 cases vides** restantes (21 avant).

Gate : ktlint, detekt, lint `--rerun-tasks`, **221 tests JVM**, **280 tests instrumentés** (S9),
0 échec, **0 ignoré**, modèle de 57 Mo intact, `font_scale` restauré à 1,0.

### Ce que ce tour apprend, et qui vaut au-delà de la dictée

**Les trois balayages étaient verts avant comme après.** Aucun nœud anonyme, aucune action perdue à
la fusion, aucun champ de saisie — et pourtant l'écran ne disait rien du tout à un lecteur d'écran
une fois ouvert. *Un écran sans nœud anonyme peut être un écran qui ne dit rien.* La question « que
reçoit un lecteur d'écran ? » n'a de réponse que posée **état par état**, et c'est le troisième
fichier de suite où c'est elle, et non les balayages, qui trouve le défaut.

### §93 — pendant qu'on parle, aucune sortie qui ne transcrive

« Arrêter » transcrit et insère ; « Annuler » jette. Seul le premier existait pendant
l'enregistrement. `abandonner` était posé sur le contrôleur, câblé au `ViewModel`, traversait
jusqu'au moteur natif — et **aucun bouton ne l'appelait dans cet état**.

⚠️⚠️ **Le commentaire de la feuille argumentait en faveur du défaut** : il tenait les deux boutons
pour « deux mots pour un même geste ». Quatrième forme du commentaire qui ment, et la plus coûteuse —
un relecteur qui le lisait avait sa réponse et passait.

### §94 — le changement d'étape n'était annoncé à personne

`régionsActives=[]` dans les trois états. Un `AlertDialog` est annoncé à son ouverture ; ce qui
change ensuite dans ses emplacements ne l'est pas. Le « Parlez » qui dit que le micro est ouvert
n'était **jamais** dit — alors que l'étape `INITIALISATION` avait été ajoutée en phase 7 précisément
pour ne pas le dire trop tôt.

🔧 Deux régions, `Polite` : le titre et la consigne vivent dans deux emplacements distincts, et l'un
sans l'autre ment. `Assertive` couperait la première annonce par la seconde.

### §95 — le micro sans modèle était une impasse

Il affichait « Aucun modèle de transcription installé. » et n'allait nulle part ; l'écran
d'installation n'était atteignable que depuis les réglages. Le publié l'ouvre directement.
🔧 La décision est extraite en `gesteDuMicro`, interface scellée à trois issues — idiome du §91 —
donc mesurable en JVM là où elle était hors d'atteinte.

### §96 — la borne de deux minutes s'appliquait en silence

Rien ne distinguait « la limite est atteinte » de « l'utilisateur a appuyé sur Arrêter » : on dictait
trois minutes, il en manquait une, sans moyen de le savoir. Le publié n'a **aucune** borne mais
affiche un chronomètre.

🔧 Deux réponses, à deux moments : le compteur « 1:37 / 2:00 » **nomme** la borne pendant qu'on
parle, le message la constate après coup. Aucune ne remplace l'autre — la première sert à ne pas y
arriver, la seconde à savoir qu'on y est arrivé. `enregistrer()` rend une `Capture(fichier, fin)` au
lieu d'un `File?`, parce qu'un `File` ne peut pas dire **pourquoi** la capture s'est arrêtée.

⚠️⚠️ **Le correctif a eu son propre défaut, et le test l'a dit.** Le compteur était masqué par
`clearAndSetSemantics` — raisonnement solide, la colonne est une région active — mais **un nœud
effacé disparaît des deux arbres** : il n'était plus lisible par personne, même à l'exploration. Il
est posé en **frère** de la région. *Ne pas crier n'oblige pas à se taire.*

⚠️ Ce correctif touche `VoiceCapture`, donc la ligne `voice_service.dart`, qui **reste décochée** :
il en ferme un défaut, il ne la mesure pas.

### Contrôles

Décompte des actionnables mesuré et posé **avant** les balayages. Contrôle positif : les deux
défauts réintroduits dans le vrai code font tomber **4 cas sur 10** ; restauration vérifiée au
**SHA-256**. Deux autres contrôles pour §96 : `>=` remis en `==` fait tomber le cas du dépassement
et lui seul ; le compteur remis **dans** la région active fait tomber les deux cas d'annonce —
restaurations vérifiées au SHA-256. Suite rejouée **à `font_scale 2,0`** — les deux boutons
restent entiers — puis échelle restaurée. Compte JVM vérifié classe par classe : 211 → **221**,
XML de `GesteDuMicroTest` et `BorneDeDureeTest` présents (§82).

---

## ✅ 2026-08-18, fin de journée : les quatre écarts du publié, §88, la passerelle 2.0.4, et le COFFRE

**42 cases cochées / 17 restantes.** Gate : ktlint, detekt, lint `--rerun-tasks`, **224 tests JVM**,
**297 tests instrumentés** (S9), 0 échec, **0 ignoré**, modèle de 57 Mo intact.

### Les commits

| Dépôt | Commit | Ce qu'il ferme |
|---|---|---|
| portage | `cc1d36d` | les **quatre** écarts du publié + §88 |
| portage | `816c207` | la couche ① de la migration n'avait **aucun** test |
| portage | `92eb02f` | la clé de signature, vérifiée contre l'APK publié |
| portage | `84707a4` | **§99 + §100** — le coffre |
| `notes_tech` | `f216390` | la **passerelle 2.0.4** (branche `fix/defauts-releves-pendant-le-portage`) |

### ✅ §88 — l'autoremplissage est CONSERVÉ, décision de Patrice

Et la mesure qui a tranché n'était pas celle qu'on cherchait : le champ expose **`PasteText` et pas
`CopyText`**. Un gestionnaire de mots de passe peut donc y **déposer** un secret, personne ne peut en
**extraire** un. Un secret de coffre perdu, ce sont des notes perdues pour toujours. **Aucune ligne de
code touchée** — l'état mesuré était déjà l'état voulu.

### ✅ Les quatre écarts du publié sont tranchés

1. **Archives** — ce n'est pas un écart, c'est la définition d'une archive. Le « correctif » évident
   aurait rendu des notes **invisibles partout**. Épinglé par 3 cas.
2. **Messages de dossier — §97** : renommer se voit, **supprimer non**. Le plus injuste : supprimer un
   dossier détruit **aussi ses notes en corbeille**, encore restaurables. D'où `countAllInFolder`.
3. **Pluriel `many` — §98** : les 4 formes écrites, `MissingQuantity` à **0** — mais **elle ne sort
   pas sur le S9**, l'ICU d'Android 10 l'ignore. Le test accepte les deux formes plutôt que de mesurer
   la version d'ICU de la machine.
4. **`%1$d note(s)`** converti en vrai `<plurals>`.

### 🔴 Le coffre — §99 et §100

- **§99** : `isUnlocked` **repoussait** l'échéance du verrouillage automatique. Défaut *posé et armé,
  pas déclenché* — il attendait un appelant venu d'une recomposition.
- **§100** : la migration **v1 → v2** n'était exercée par **aucun test, des deux côtés**. Le publié
  porte `encryptNoteLegacyV1`, écrite exprès pour la rendre vérifiable, et **personne ne l'appelle**.

⚠️ **Réfutés, ne pas rechercher** : freinage identique au chiffre près, longueurs de secret
identiques, ordre de l'auto-effacement identique.

### ✅ La passerelle 2.0.4 — **PUBLIÉE le 2026-08-18**

`notes_tech` **`be6fe0d`** sur `main`, tag **`v2.0.4`**, versionCode **2052**, trois APK par ABI,
les trois workflows verts. Vérifié sur le S9 avant publication : `notes_tech.kek.xml` écrit, **blob
48 octets** (32 + tag GCM), **nonce 12**, aucun retour à la ligne. La couche ① la relit — 8 cas, dont
un scellé produit *comme la passerelle le produit*.

**Vérifié sur l'artefact PUBLIÉ, et non sur le dépôt** : certificat **identique à la 2.0.3**
(`ddb385de…42e9` — le chemin de mise à jour tient), `versionCode` 2052 / `versionName` 2.0.4, et
`sealDatabaseKek` + les trois valeurs du contrat **présents dans le dex**.

⚠️⚠️ **Le changelog fastlane a une règle qu'aucun document ne portait** : les quatre derniers sont en
**ASCII pur**, 471–492 octets. Le cap F-Droid compte des **caractères** ; en ASCII octets =
caractères, donc `wc -c` suffit à le vérifier, et un accent casse cette égalité. `52.txt` : **487**
(FR), **435** (EN), zéro non-ASCII.

⚠️ Site `files-tech.com` bumpé sur ses **cinq** surfaces (`939de98`) et **vérifié en ligne** — pas
seulement déployé, la leçon de mai.

⚠️ **La MR F-Droid `!37885` n'a pas été touchée**, exprès.

⚠️ **Un contrôle m'avait échappé** : `flutter analyze lib/` au lieu de `flutter analyze`, donc un
import inutile de mon propre test est passé. *Restreindre le périmètre d'un contrôle, c'est se
garantir qu'il ne dira rien de ce qu'on a laissé dehors.* Corrigé avant la fusion.

⚠️ **Deux écarts assumés** avec la procédure écrite : octets bruts plutôt qu'hexadécimal (une `String`
portant la KEK est ineffaçable en Dart), et idempotence sur les préférences **et** l'alias.

⚠️ **Une affirmation de la doc était fausse** : `Base64.DEFAULT` ne fait **pas** échouer le décodage,
le décodeur d'Android tolère les retours à la ligne. Mesuré, et corrigé dans le document.

### 🔴 Ce qui bloque la bascule, et ce n'est pas une décision de rédaction

**Le portage n'a aucun `key.properties`.** La 3.0.0 doit être signée avec
`notes_tech/android/notestech-release.jks` — dont l'empreinte a été **vérifiée contre l'APK
réellement publié** (`ddb385de…42e9` des deux côtés). Sans cette clé : ni vérification de bout en
bout, ni bascule.

⚠️ **F-Droid ne s'y oppose pas** : la MR `!37885` est épinglée sur 2.0.3/51 et porte
`AutoUpdateMode: Version` + `UpdateCheckMode: Tags` — une fois fusionnée, le bot suit les tags seul.
Publier une 2.0.4 ne la dérange donc pas. ⚠️ Le sens du label `waiting-for-upstream` reste **inconnu**.

## ✅ 2026-08-19 : la ligne du mode panique, cochée sur mesure — et six défauts

Trois cases d'un coup dans `docs/05-PARITE.md` : `panic_service.dart`, `panic_complete_screen.dart`,
`panic_confirm_dialog.dart`. **17 → 14 cases restantes.**

### Ce qui a été trouvé, et le motif commun

Six défauts, tous de la même famille : **le code avait été corrigé, la phrase que l'utilisateur lit
ne l'avait jamais été.**

| # | Défaut | §  |
|---|---|---|
| 1 | Le dialogue de **consentement** taisait la destruction du modèle de dictée | §101 |
| 2 | Le bilan de fin la taisait aussi | §101 |
| 3 | « du clair peut subsister » ne nommait qu'**une** des trois sources | §101 |
| 4 | Ce même message affichait « **0 étape(s)** ont échoué » — il se contredisait dans sa propre phrase | §101 |
| 5 | 🔴🔴 Le `when` rendait le bilan et l'avertissement **exclusifs** : une séquence réussie effaçait l'avertissement | §102 |
| 6 | 🔴🔴 « Du clair » était défini **deux fois** dans le même fichier, et les deux divergeaient | §103 |
| 7 | 🔴🔴 **Créé par le correctif du 5** : « Clé détruite » s'affichait sur l'écran qui annonce que la clé a SURVÉCU | §106 |
| 8 | 🔴 « Toutes les données ont été effacées » coexistait avec « du lisible peut subsister » | §106 |
| 9 | 🔴 `walkTopDown` ignore en silence un répertoire illisible ⇒ faux négatif de la mesure | §106 |

Les défauts 1 et 2 venaient d'une justification datée — *« la dictée arrive en phase 7 »* — vraie à
l'écriture, périmée le 2026-08-16, et restée lisible et plausible. ⚠️ Elle figurait même au registre
des chaînes orphelines de `05-PARITE.md`, qui a donc **attesté qu'il n'y avait rien à voir**.

Le défaut 5 est le plus instructif : `clairSurLeDisque` se replie sur `true` quand la **mesure**
échoue, et l'affichage annulait ce repli. *Un repli de sûreté n'en est pas un si l'affichage
l'écrase.*

### Ce qui les a trouvés, et ce qui ne pouvait pas

`PanicReportTest` compte quinze cas JVM, tous justes, et **ne rend aucun écran**. Il prouve les
branches ; le produit, ici, est la **phrase**. D'où `PanicEcransTest` — **10 cas**, les deux écrans
composés pour de vrai. Il a trouvé le défaut 5 **à son premier lancement sur le S9**.

⚠️ Les défauts 3, 4 et 6 ont été relevés par des relectures externes, chacune par un chemin
différent, et **aucune n'a vu ce que l'autre voyait**. Une seule n'aurait pas suffi.

### Rejeu complet de la séquence sur le S9 — §104

La vérification précédente datait du 2026-08-14 et **précédait trois des treize étapes**. Rejouée
deux fois à travers l'interface réelle :

- **Nominal** : treize étapes, zéro échec, base et modèle détruits, cache vide, préférences réduites
  à leur liste blanche, quatre puces affichées. Après relance : « Aucune note ».
- **Résidu forcé** (`cache/exports` rendu non supprimable) : `EXPORTS_WIPE` et `CACHE_PURGE`
  échouent, et l'écran affiche la seule phrase juste — les trois sources nommées, aucun compteur
  d'étapes, aucune puce rassurante.

⚠️⚠️ **Une panique détruit le modèle de 57 Mo.** Il a été copié hors de l'appareil avant, restauré
après, SHA-256 identique des deux côtés (`422f1ae4…8898`). *Une vérification destructrice doit
emporter sa restauration avec elle, sinon elle n'est faisable qu'une fois.*

### ⚠️⚠️ Deux sessions Claude ont travaillé en parallèle sur ce dépôt — §105

Deux suites complètes se sont arrêtées à mi-parcours (209/306, puis 27/306) en rendant « Process
crashed », **sans un seul test en échec**. Un second `claude.exe`, survivant d'une session
précédente, exécutait la même suite sur le même téléphone et avait réinstallé l'APK en plein milieu.

Avant toute campagne de mesure sur appareil :

```powershell
Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'am instrument' }
```

⚠️ Avant de tuer quoi que ce soit, **sauvegarder l'arbre de travail** : le travail de l'autre session
était réel, non commité, et bon — le défaut 6 vient de lui.

### Ce qui reste

Par ordre de risque : `note_export_service.dart` (519 lignes, **défaut connu** — l'export `.md`
d'une note de coffre rend un corps vide), puis `keystore_bridge.dart` (207).

🔴 **Toujours le même point bloquant pour la bascule 3.0.0** : le portage n'a **aucun
`key.properties`**. La clé (`notes_tech/android/notestech-release.jks`) est identifiée et vérifiée
contre l'APK publié. C'est une décision de Patrice, pas un travail à faire.

### ⚠️⚠️ La relecture du correctif en a trouvé trois de plus, dont un qu'il venait de créer

Sortir l'avertissement du `when` (défaut 5) lui a fait perdre l'exclusivité qui le protégeait du cas
« la clé a survécu » — et sa phrase commence par « Clé détruite ». Les huit cas instrumentés écrits
le matin même ne l'ont pas vu ; une relecture externe oui.

> **Un correctif est du code neuf.** Il ne bénéficie d'aucune des relectures qui ont validé ce qu'il
> remplace. Relancer une revue **sur** les correctifs de revue n'est pas du zèle.

⚠️ **Reste ouvert, faute de mesure** : l'avertissement de clair n'est pas une `liveRegion`, et le
titre assertif dit « Effacement terminé » dans l'état où du lisible subsiste. Poser une seconde
région assertive sans l'écouter sous TalkBack serait deviner. Cf. `04-PIEGES.md` §106.

ℹ️ **Il n'y a pas de `gpt-5.5`** : `audit-ia.py --provider gpt --list` s'arrête à `gpt-5.2`, et la
liste de l'API fait foi. Les tiers au-dessus disponibles sont `gpt-5-pro` et `gpt-5.1-codex-max`.

## ✅ 2026-08-19 (suite) — l'export : la couche qui ÉCRIT n'avait aucun test

`note_export_service.dart` cochée. **14 → 13 cases restantes.**

Le critère de cette ligne nomme un **vrai défaut de l'application publiée** : une note de coffre a
`content` vide en base, et exporter la ligne brute produisait un `.md` au corps vide.
**Le portage ne l'a pas** — et rien ne l'établissait avant aujourd'hui : `NoteMarkdown` et
`NoteArchive` avaient 30 cas JVM, `NoteExporter` — les 400 lignes qui déchiffrent et écrivent — en
avait **zéro**. Six cas instrumentés (`NoteExporteurTest`) le mesurent maintenant.

🔴🔴 **Deux de ces six cas étaient faux, et l'un passait au VERT.** L'horloge est figée : deux
archives d'un même cas portent le **même nom de fichier**, et l'aide qui le retrouvait faisait
`first`. Le cas dont toutes les assertions sont des **absences** était donc vert sur l'archive de
référence. *Un cas fait uniquement de `doesNotContain` ne distingue pas « c'est absent » de « je
regarde ailleurs».* Correctif : `single`, pour qu'une ambiguïté fasse échouer au lieu d'être
tranchée en silence. Cf. `04-PIEGES.md` §107.

## ✅ 2026-08-19 (suite) — le Keystore : sept cas prouvaient qu'il se relit LUI-MÊME

`keystore_bridge.dart` cochée. **13 → 12 cases restantes.**

`AndroidVaultKeystoreTest` a sept cas justes, et aucun ne dit rien du seul risque qui compte à la
bascule 3.0.0 : **un coffre à code créé par la 2.0.x doit s'ouvrir sous la 3.0.0.** Une divergence
de paramètre ne casse rien — elle produit **une autre clé**, tout fonctionne, et le coffre de
quelqu'un ne s'ouvre plus jamais. Il n'y a pas de message d'erreur pour ça.

`PariteKeystoreAvecFlutterTest`, **6 cas**, ne recopie rien du portage : tout est transcrit de
`KeystoreBridge.kt` puis **comparé**. Un scellé produit comme le pont publié s'ouvre par le portage,
et réciproquement ; contrôle négatif compris. ⚠️ La comparaison des deux spécifications passe par
**`KeyInfo`** — on demande au matériel ce qu'il a fabriqué — parce que lire `VaultParams` des deux
côtés serait circulaire.

⚠️⚠️ **Contrôle positif fait, et il a servi** : deux paramètres du côté publié faussés
(`setKeySize(128)`, `GCMParameterSpec(96, …)`) font tomber **exactement les deux cas visés**, et eux
seuls. Six cas verts ne disent rien tant qu'on n'a pas vu l'instrument tomber. Cf. `04-PIEGES.md`
§108.

⚠️ Ne prouve pas qu'un coffre d'un vrai téléphone s'ouvre : la clé est liée à l'**UID** et la build de
portage est suffixée `.next`. C'est la seule chose mesurable avant la bascule.

## ✅ 2026-08-19 (fin) — deux cases de plus, et deux contrôles positifs

**12 → 10 cases restantes.**

### `settings_service.dart` — trois conventions, une seule à manquer pour tout perdre

`shared_preferences` écrit dans **`FlutterSharedPreferences.xml`**, préfixe chaque clé de
**`flutter.`**, et stocke un `int` Dart en **`Long`**. En manquer une, et l'application neuve ne
trouve **rien** — sans se plaindre : elle applique ses défauts. L'utilisateur perdrait sa langue, son
thème, et surtout **le délai de verrouillage des coffres**, qui est un réglage de sécurité.

⚠️ `AppSettings` et `LegacyPreferences` apparaissaient dans **cinq** fichiers de test, **comme
collaborateurs**. Aucun ne vérifiait ce qu'ils lisent. *Être utilisé partout n'est pas être vérifié.*

⚠️ Les **six clés de tri** n'étaient vérifiées nulle part. Le Dart les écrit à la main plutôt que par
`mode.name`, parce que la release passe par `--obfuscate` ; le portage a le même `when` exhaustif, et
rien ne comparait les douze chaînes. Une seule qui divergerait ne casserait rien : le tri retomberait
sur le défaut, et l'utilisateur croirait l'avoir mal réglé.

⚠️⚠️ **Contrôle positif** : préfixe faussé ⇒ **5 cas sur 6 tombent**, et le sixième — le contrôle
négatif — **devait** rester vert. Les deux sortes de contrôle ne se remplacent pas : le négatif seul
laisserait passer un portage qui se trompe de préfixe **des deux côtés**, parfaitement cohérent avec
lui-même et aveugle à tout ce que l'utilisateur avait réglé. Cf. `04-PIEGES.md` §110.

### La promesse publique « paramètres de coffre identiques »

Elle était **déjà mesurée** — 9 cas sur des vecteurs produits en **exécutant** le Dart de la 2.0.3,
recoupés contre `argon2-cffi` (le C de référence de la RFC 9106) et OpenSSL : 37 concordances, zéro
divergence. Ce qui manquait n'était pas la mesure, c'était la vérification de l'instrument.

⚠️⚠️ `VaultParams.PASSPHRASE_ITERATIONS` faussé fait tomber **deux** cas — mais **pas** celui qui
s'appelle « Argon2id rend exactement les clés du Dart », qui passe ses paramètres en clair. C'est le
bon choix : un vecteur doit être figé indépendamment des constantes qu'il sert à vérifier. Mais la
protection tient donc en **deux maillons**, et *le test au nom le plus rassurant n'est pas celui qui
protège la constante*. Cf. `04-PIEGES.md` §109.

## ✅ 2026-08-19 — `FLAG_SECURE` : huit cas sur le compteur, zéro sur ce qui l'incrémente

`secure_window_service.dart` cochée. **10 → 9 cases restantes.**

`SecureWindowControllerTest` a huit cas justes sur l'arithmétique du compteur — et **tous sur des
appels que le test fait lui-même**. Or personne n'appelle `force()` ni `release()` à la main : les
deux seuls appelants du programme sont le `DisposableEffect` de `SecureWindowGuard` et l'étape 1 du
mode panique.

Le risque était donc **l'appariement des deux gestes au cycle de vie d'une composition**. Un
déséquilibre ne lève rien : il retire la protection d'un **autre** écran — l'éditeur d'une note de
coffre resté ouvert derrière une feuille qui se ferme — et la capture redevient possible sans un mot.

`SecureWindowGuardTest`, **7 cas** dans une vraie composition, dont le jumeau superposé, la bascule
d'`active` dans les deux sens (`NoteEditorScreen` est le seul à s'en servir), et **`FLAG_SECURE` lu
sur la vraie fenêtre de l'activité** — *le compteur peut être parfait et le drapeau jamais posé*.

⚠️⚠️ **Deux contrôles positifs, chacun sur un cas et un seul** : `release()` qui remet le compteur à
zéro fait tomber le cas du jumeau superposé, et un garde qui rend même sans avoir pris fait tomber le
contrôle négatif. Aucun débordement d'un contrôle sur l'autre. Cf. `04-PIEGES.md` §111.

⚠️ **Noté, pas corrigé** : `forcePermanently()` **est** `force()`, donc un `release()` en trop
annulerait la demande du mode panique, et la borne à zéro n'y changerait rien. Ce n'est pas
atteignable aujourd'hui — `release()` n'a qu'un appelant, qui a toujours forcé d'abord. Séparer la
demande permanente serait un durcissement contre un défaut **futur**, pas la correction d'un défaut
présent.

## ✅ 2026-08-19 — les rétroliens : deux plafonds égaux, deux chemins pour les appliquer

`backlinks_service.dart` cochée. **9 → 8 cases restantes.**

Le relevé du 08-16 avait établi que les constantes concordent au chiffre près, et la ligne notait ce
qui restait : *« vérifier le comportement sur une note qui dépasse »*. C'était la bonne question.

Les deux codes appliquent la même règle par des mécanismes **différents** — le Dart teste son `break`
**avant** de traiter une correspondance, le portage enchaîne `mapNotNull` → `distinctBy` → `take`. La
règle commune est *« le plafond compte les liens retenus, pas les paires de crochets rencontrées »*,
et elle n'est écrite nulle part dans le portage autrement que par **l'ordre de trois appels**.
Intervertir deux d'entre eux ne casse rien de visible.

`WikiLinkParserBornesTest`, **6 cas** aux entrées engendrées (un vecteur de 50 000 caractères sur une
ligne de TSV serait illisible — c'est pour ça que ces bornes n'étaient pas couvertes) : lien collé à
la borne, lien à cheval avec son témoin, 257 liens ⇒ les **256 premiers**, doublons et titres vides
qui ne consomment pas le plafond, titre de 200 contre 201 caractères.

⚠️⚠️ **Deux contrôles positifs, et le second est le plus instructif** : `take` avant `distinctBy`
fait tomber un cas ; `take` avant `mapNotNull` en fait tomber **deux**. Les cas « doublons » et
« titres vides » ne sont donc **pas redondants** — deux fautes voisines, deux portées différentes, un
cas pour chacune. Cf. `04-PIEGES.md` §112.

## ✅ 2026-08-19 — l'écran de présentation, et les deux lignes sans homologue

**8 → 5 cases restantes.**

### `splash_screen.dart` — 230 lignes, zéro test, deux affirmations à vérifier

**L'idempotence des trois portes.** Touche, retour, échéance, déclenchées depuis trois contextes
différents. Le KDoc dit pourquoi la garde existe : *« un retour pressé pendant la fermeture
automatique produit deux navigations, et la seconde s'applique à l'écran d'accueil déjà affiché »*.
Un défaut de ce genre **ne lève pas** — il fait disparaître un écran que quelqu'un vient d'ouvrir.
⚠️ **Contrôle positif** : la garde `compareAndSet` retirée fait tomber les deux cas d'idempotence, et
eux seuls.

**Le chronomètre de la signature.** L'échéance de **5 500 ms** est mesurée à l'horloge de composition
pilotée à la main — rien à 5 400, fermeture à 5 500 — avec la valeur transcrite du Dart, jamais relue
depuis le portage (ses constantes sont privées, et c'est bien).

⚠️⚠️ **Une mesure faite hors suite, exprès.** Le portage affirme que l'échéance n'est pas raccourcie
quand les animations sont réduites. Le vérifier demande d'écrire dans les réglages **globaux de
l'appareil** ; un cas qui le ferait rendrait le fichier dépendant d'un état hors du test, et le
laisserait modifié en cas d'échec. Fait une fois à la main : `animator_duration_scale 0` ⇒ échéance
inchangée. ⚠️ Le réglage était **non défini** au départ, et un `settings put … null` y écrit la
chaîne « null » : c'est `settings delete` qui rend l'état d'origine, et il faut le **vérifier après
coup**. Cf. `04-PIEGES.md` §113.

⚠️ L'écran **ne masque pas** la KEK au sens d'une garde : `ContenuPrincipal` attend
`StartupState.Ready` avant d'afficher quoi que ce soit. Vérifié par lecture du câblage.

### Les deux lignes sans homologue — §114

`sheet_handle.dart` : la seule façon dont cette ligne pouvait mal tourner était qu'un appel désactive
la poignée par mégarde. Les **sept** `ModalBottomSheet` du portage passent `onDismissRequest` et
`sheetState`, **aucun** ne passe `dragHandle`.

`blocking_progress_dialog.dart` : les deux appelants sont mesurés séparément — `PanicEcransTest` pour
le recouvrement de panique, `FermetureDeFeuilleTest` (6 cas, dont le **balayage vers le bas**) pour
la feuille de conversion. C'est ce remplissage qui avait trouvé le défaut du 08-16.

## ✅ 2026-08-19 — la feuille de déplacement : une chaîne juste, au mauvais endroit

`move_to_folder_sheet.dart` cochée. **5 → 4 cases restantes.**

La feuille signalait un dossier coffre en réutilisant `note_card_locked` — « 🔒 Note verrouillée ». La
chaîne est juste et bien nommée ; posée sous le nom d'un **dossier**, sur l'écran où l'on choisit où
envoyer une note, elle faisait annoncer « Secrets. Note verrouillée » pour une destination qui n'est
pas une note.

> ⚠️ **Une chaîne réemployée n'est pas une chaîne partagée.** Rien ne signale l'emprunt : la clé
> existe, le texte s'affiche, la traduction est là. Seul le **sens** ne suit pas, et aucun outil ne
> le vérifie — ni le compilateur, ni le contrôle de parité FR/EN, ni le relevé des orphelines.

L'application publiée, elle, ne signale le coffre que par une **icône** — invisible à un lecteur
d'écran — alors que son propre commentaire dit que *« l'utilisateur doit voir où il envoie sa note »*.
`move_to_folder_vault` dit désormais la **conséquence** : la note sera chiffrée.

⚠️ Chaîne ajoutée par `outils/arb_vers_strings.py`, jamais dans le XML. **Contrôle positif** :
`note_card_locked` remise en place, le cas du coffre tombe, et lui seul. Cf. `04-PIEGES.md` §115.

⚠️⚠️ Le balayage a signalé la **poignée de material3** (`OnLongClick` seul, non nommable depuis
l'appelant). Idiome maison appliqué — exception ancrée sur la poignée **mesurée**, `containsExactly`
conservé. ✅ Effet de bord : la poignée est désormais **mesurée**, là où §114 ne l'établissait que par
la lecture des sept appels.

## ✅ 2026-08-19 — « À propos » et les mentions légales : le critère écrit était faux

Deux cases cochées. **4 → 2 cases restantes**, et ce sont les deux de la phase 7.

707 lignes de production à elles deux, **aucun test**.

⚠️⚠️ **La ligne de parité se trompait sur le publié.** Elle disait *« version lue dynamiquement via
`PackageInfo` »* ; l'application publiée affiche `AppConstants.appVersion`, une **constante
statique** qu'il faut bumper avec `pubspec.yaml` — le piège de release déjà connu du portefeuille. Le
portage lit `BuildConfig.VERSION_NAME`, qui **dérive** de Gradle et ne peut donc pas s'en écarter.

> **Un critère de parité est une affirmation sur le publié, et il se vérifie comme les autres.**
> Suivi littéralement, celui-ci aurait fait remplacer une garantie de compilation par une lecture à
> l'exécution — ou pire, par une constante tenue à la main « pour être fidèle ».

🔴 **Les mentions légales : un repli silencieux d'Android.** Une ressource `raw-fr` absente ne lève
pas — Android retombe sur `raw`, et l'écran affiche un texte lisible **dans la mauvaise langue**, sur
les deux seuls écrans qui engagent juridiquement. Le cas lit donc les quatre fichiers par des
`Resources` de langue **explicite** et exige qu'ils **diffèrent** : *c'est ce qui distingue « les deux
langues sont là » de « la résolution est retombée sur le défaut »*.

⚠️ **Contrôle positif, deux défauts d'un coup** : version écrite en dur ⇒ un cas tombe ;
`raw-fr/privacy.md` retiré ⇒ l'autre. Exactement les deux visés. Cf. `04-PIEGES.md` §116.

## ✅ 2026-08-19 — l'écran d'installation du modèle : six états inatteignables

`voice_setup_screen.dart` cochée. **2 → 1 case restante** : `voice_service.dart`.

462 lignes, **aucun test**. La vérification au démarrage, la progression, les **quatre** causes
d'échec et le dialogue de retrait ne s'obtenaient que par le magasin réel — les atteindre par la
Route demanderait un fichier de 57 Mo **par état**. D'où le découpage sans état, comme pour les
feuilles de coffre.

Le `when` du dialogue est exhaustif, donc une cause sans texte casse la compilation ; ce que le
compilateur ne dit pas, c'est que les quatre textes soient **dans le bon ordre**. ⚠️ **Contrôle
positif** : `EMPREINTE` et `PLACE_INSUFFISANTE` interverties ⇒ deux cas tombent.

⚠️⚠️ **Trois erreurs de mesure, aucune n'était un défaut du code** — et la plus traître :
`performClick` sur un nœud **hors fenêtre ne lève pas**, il touche des coordonnées absentes, et le
cas échoue sur un compteur à zéro *comme si le rappel n'était pas câblé*. Correctif :
`performScrollTo()`. Les deux autres : le catalogue compte **deux** modèles (donc deux boutons du
même libellé), et dans un `AlertDialog` **titre et boutons sont frères**, pas parent et enfant —
`hasAnyAncestor(isDialog())`. Cf. `04-PIEGES.md` §117.

## ✅ 2026-08-19 — **le tableau de parité est complet**, et ce que ça ne dit pas

`voice_service.dart` cochée. **0 case restante** : les 39 ouvertes le 08-16 le sont toutes.

### La dernière : 601 lignes de capture, trois cas JVM

`WhisperStt` et `SttModelStore` avaient 27 cas ; `VoiceCapture` n'était effleuré que par
`BorneDeDureeTest`, sur la seule fonction pure du fichier. Ce que rien ne mesurait appartient au
**mode panique** : `couperEtInterdire()` pose un **état** que rien n'efface, et doit répondre par un
**échec** et non par `null` — *`null` dit « vous n'avez rien dit », l'exception dit « le système a été
mis à l'arrêt »*.

⚠️ Un `ContextWrapper` refuse `RECORD_AUDIO` : déterministe, sans ouvrir le micro du S9, et il
mesure du même coup **l'ordre des deux gardes**. ⚠️ Contrôle positif : l'état d'interdiction retiré ⇒
deux cas tombent.

⚠️⚠️ **`withTimeoutOrNull(0)` n'exécute jamais son bloc.** Mon premier cas d'attente passait zéro et
échouait — *pas un défaut du code*. Il mesure désormais la valeur **et le temps écoulé**, et la
propriété de kotlinx a son propre cas : le jour où un appelant passera un **reliquat** de budget,
une capture arrêtée sera annoncée « encore en cours ». Cf. `04-PIEGES.md` §118.

### ⚠️⚠️ Ce que « tableau complet » ne veut PAS dire

| | |
|---|---|
| Critère de sortie de la **phase 4** | inchangé : ouvrir un coffre réellement créé par la version Flutter, sur un vrai téléphone |
| Bascule **3.0.0** | bloquée : le portage n'a **aucun `key.properties`** |
| Clés Keystore | liées à l'**UID** ; la build de portage est suffixée `.next` |

*Un tableau complet dit que chaque ligne a été regardée, pas que le produit est fini.* §119
