# Audit de sécurité — état au 2026-09-25, interrompu (à reprendre)

> Lancé le 2026-09-25 au soir (Patrice : « tu peux lancer l'audit sécurité […] si c'est le bon moment »),
> méthode du skill `audit-securite-mobile`, **palier profond** : 5 chercheurs (un composant × un angle
> chacun), puis 3 vérificateurs par candidat (ACCESSIBILITÉ / IMPACT / DÉFENSES), un candidat gardé si
> 2 sur 3 échouent à le réfuter. **Interrompu** à la demande de Patrice (« je dois couper ») : tous les
> agents arrêtés. Ce fichier dit ce qui est acquis et ce qui reste. **Aucun constat n'est encore
> vérifié** : les sept candidats ci-dessous sont des CANDIDATS, pas des défauts.

## Révision auditée

- Commit **`ebee629`** (branche `master`, arbre propre). Les agents lisaient une copie figée :
  `git worktree add --detach <chemin> ebee629` (le 25 : `scratchpad/audit-ebee629`, à recréer si le
  scratchpad a disparu ; `git worktree prune` nettoie une copie supprimée).
- Manifeste fusionné : `app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml`
  (copié à côté de la copie figée). Composants exportés : `ui.MainActivity` (lanceur) et
  `ProfileInstallReceiver` (permission DUMP). FileProvider non exporté. Pas d'`INTERNET`.
- Code modifié depuis (`c9bad7d`, `7abf065`) : tests et chaînes de langue seulement — rien de la
  sécurité ; l'audit reste valable sur `ebee629`.

## Modèle de menace (donné à chaque agent)

Notes Markdown chiffrées, aucune permission réseau (promesse « 100 % local »). Base SQLCipher sous KEK
scellée Keystore. Coffres : phrase secrète (Argon2id 64 Mio) ou code 4-6 chiffres (Argon2id 32 Mio +
scellé Keystore `setUnlockedDeviceRequired`, effacement au 5e code faux ; clé supprimée par Android au
retrait du verrouillage d'écran — voulu, D-026). Verrou d'app : PIN (Argon2id + HMAC Keystore) +
biométrie classe 3. Mode panique. `allowBackup=false`. Attaquants : **A** téléphone déverrouillé en
main ; **B** app malveillante sans root ; **C** copie du stockage / téléphone saisi verrouillé (avec, pour
les candidats K, du code exécuté sous l'UID de l'app) ; **D** contenu hostile. Exclus d'office (connus) :
deux déverrouillages PIN concurrents ; échec du remboursement d'un essai (double panne).

## Les cinq cellules

| # | Angle | Composants | État |
|---|---|---|---|
| 1 | stockage au repos et où vit la clé | `security/vault`, `security/kek`, `core/crypto`, `data/local`, `data/prefs`, stockage du verrou d'app | ✅ fini — K1 à K5 |
| 2 | mode de contrainte et verrouillage | `security/applock`, déclenchement de `security/panic`, `ui/applock`, `ui/panic`, `ui/startup`, `MainActivity`, navigation, `VaultSessions`, `ui/vault` | ✅ fini — V1, V2 |
| 3 | suppression et purge (ce qui reste après) | séquence de panique, effacement des coffres, corbeille, suppression de dossiers, fichiers de l'app | ⛔ arrêté en cours — **à relancer** |
| 4 | entrée reçue d'un tiers | intents de `MainActivity`, FileProvider, modèle de dictée + JNI, aperçu Markdown et liens | ⛔ arrêté au moment de rendre — rapport perdu — **à relancer** |
| 5 | fuite (journaux, presse-papiers, notifications, récents) | journaux release, presse-papiers, FLAG_SECURE, notifications, erreurs, clavier, accessibilité, partage | ⛔ arrêté au moment de rendre — rapport perdu — **à relancer** |

## Candidats trouvés (NON vérifiés)

### V1 — MOYEN (cellule 2) — une note de coffre reste lisible et copiable dans l'éditeur après la fermeture du coffre
CWE-613. `NoteEditorViewModel.charger()` déchiffre (`ui/editor/NoteEditorViewModel.kt:797`) et met le
clair dans l'état (819-822). En arrière-plan, `NotesTechApplication.kt:109` appelle `vaults.lockAll()`,
qui n'efface **que les clés** (`security/vault/VaultSessions.kt:179-180`). Rien ne propage la fermeture à
l'éditeur (`unlockedFolderIds` lu seulement par `HomeRoute`, `FoldersDrawerViewModel`, `NoteExporter` ;
`lockedVault` posé au chargement ou sur échec d'écriture, 894-897 et 952). Au retour, même modèle de
vue : `NoteEditorScreen.kt:580` faux, 609-631 affichent le clair ; copie `NoteEditorViewModel.kt:591-596`
sans session. Attaquant A, sans verrou d'app ou dans son délai. Promesse écrite en
`NotesTechApplication.kt:101-103`. Recommandation du chercheur : à la fermeture d'un coffre, l'éditeur
jette titre et corps et passe à l'état verrouillé ; la copie refuse sans session vivante.

### V2 — FAIBLE (cellule 2) — la preuve du PIN survit à un passage en arrière-plan : changer le PIN d'app sans l'ancien
CWE-613. `ui/applock/AppLockSettingsViewModel.kt:241` garde la preuve (114) à l'étape NEW (262). Avec
un délai de reverrouillage, `security/applock/RelockPolicy.kt:56-59` → `LOCK_LATER`, l'époque ne change
pas (`AppLockManager.kt:195`) ; `forgetFlow()` (315-320) seulement à l'ouverture/fermeture de la feuille.
A revient dans le délai et < 2 min : `saveNewPin` (279) → `isValid` (`AppLockManager.kt:376`) vrai →
`replacePin` (296). Recommandation : aucune preuve ne survit à la sortie de l'app.

Notes de la cellule 2 (pas des constats) : course `lockAll()` / déverrouillage en cours inatteignable
tant qu'Argon2id tourne sur le fil principal — **la déplacer hors du fil principal ouvrirait la
course** ; `PanicScreens.kt:60-62` : commentaire trompeur (le mot-clé est affiché) ; à mesurer :
FLAG_SECURE sur les fenêtres de dialogue et de feuille.

### K1 — MOYEN (cellule 1) — l'ancien clair d'une note convertie reste dans l'index FTS5 et dans `note_links`
CWE-212. `encryptAllNotesInFolder` → `lockNote` (`FolderVaultService.kt:572-576`) → déclencheur
`notes_au` (`data/local/UnmanagedSchema.kt:118-119`) : FTS5 n'écrit qu'un marqueur de suppression, les
entrées restent dans `notes_fts_data` jusqu'à une fusion ; aucune purge (optimize/merge/secure-delete).
`note_links` (`target_title` = textes `[[…]]`) non supprimés par les gestes de masse
(`FolderVaultService.kt:576-586`, `:717`, `:748`) alors que l'édition le fait (`NotesRepository.kt:343`).
Attaquant C avec la clé de la base. Partie `note_links` certaine ; partie FTS5 selon l'activité.

### K2 — FAIBLE (cellule 1) — après conversion, un lien d'une note ordinaire reste résolu vers la note passée au coffre
CWE-1230. Pas d'équivalent de `resolveIncoming` (`NotesRepository.kt:783-787`) dans les gestes de masse
(édition :378 et déplacement :507 le font) ; `NoteLinkDao.kt:44-45` sans filtre ; `LiensDeLaNote.kt:138`
affiche un lien résolu. Attaquant A : apprend qu'un coffre contient une note de ce titre (règle de
l'issue #10).

### K3 — MOYEN (cellule 1) — la clé de la base reste utilisable sur un téléphone verrouillé
CWE-922. `KeystoreSealedKekSource.kt:243-253` sans `setUnlockedDeviceRequired` (installation neuve
`KekRepository.kt:339` ; migration sans 2.0.4 `:156`/`:227`). Utilisateurs passés par la 2.0.4 : leur
clé porterait l'attribut (`docs/10-PASSERELLE-2.0.4.md:88-91`, `PariteKeystoreAvecFlutterTest.kt:227`),
mais la copie `flutter_secure_storage` (clé RSA sans l'attribut) n'est jamais retirée hors panique
(`di/DatabaseModule.kt:69`, repli `KekRepository.kt:118-133`). `docs/10-PASSERELLE-2.0.4.md:169-170`
serait faux. Non-régression. ⚠️ Lier la clé de la base au déverrouillage la ferait **supprimer** au
retrait du verrouillage d'écran (mesuré le 25) : toute la base perdue.
**🔴 À VÉRIFIER MOI-MÊME, hors panel — risque de PERTE, pas de sécurité** : si la clé de la base des
utilisateurs de la 2.0.4 porte vraiment `setUnlockedDeviceRequired`, retirer le verrouillage d'écran
(Android 12+) la supprime ; seule la copie `flutter_secure_storage` sauve alors la base. Rien ne doit
retirer cette copie tant que ce cas n'est pas traité. Lire la passerelle et `PariteKeystoreAvecFlutterTest`.

### K4 — MOYEN (cellule 1) — coffre à code sous Android 7.0-8.1 : recherche hors ligne du code
CWE-307. `AndroidVaultKeystore.kt:273` : l'attribut de déverrouillage n'existe qu'en API 28+ ;
`minSdk = 24` ; mode code proposé sans condition (`ui/vault/VaultSheets.kt:207-220`). Avec la clé de la
base et le Keystore sous l'UID de l'app, recherche hors ligne (Argon2id t=2 32 Mio, ≤ 1,11 million de
codes). Même recette que la 2.0.9. Recommandation : ne pas présenter le code comme une protection contre
un téléphone saisi sous l'API 28, ou le retirer.

### K5 — FAIBLE (cellule 1) — le vérificateur du verrou d'app sert d'oracle illimité sur un téléphone verrouillé
CWE-307. `security/applock/AndroidAppLockKeystore.kt:27-31` (clé HMAC sans attribut de déverrouillage) ;
vérificateur dans les préférences (`AppLockStore.kt:148`) ; Argon2id précalculable (`AppLockPin.kt:134`),
un HMAC par candidat (`AndroidAppLockKeystore.kt:45-47`). Attaquant C. Choix documenté (D-023) qui
n'envisagerait pas cet attaquant.

Ce que la cellule 1 a cherché sans rien trouver (résumé) : copie du stockage sans Keystore → aucun
secret en clair ; sauvegardes fermées ; aucun composant exporté n'expose de fichier ; nonces jamais
réutilisés ; AAD liées aux identifiants ; vérificateur de coffre HMAC ; panique relit ses destructions ;
requêtes SQL paramétrées ; Timber non planté en release.

## Pour reprendre

1. Recréer la copie figée si besoin (`git worktree add --detach … ebee629`).
2. Relancer les cellules **3, 4, 5** (prompts : mêmes composants, même angle, même modèle de menace).
3. Vérifier **V1, V2, K1-K5** et les candidats des cellules 3-5 : 3 vérificateurs chacun.
   ⚠️ **20 agents simultanés au plus** (limite de l'outil, atteinte le 25) : lancer par lots ; et
   **écrire chaque rapport sur le disque dès qu'il arrive** — un arrêt perd ce qui n'est qu'en mémoire.
4. Vérifier moi-même les constats retenus (au moins les plus graves), puis le rapport au format
   `~/.claude/references/rapport-format-standard.md` : révision, couverture, retenus, réfutés, rien
   trouvé, limites.
5. Le point K3 « perte de la base au retrait du verrouillage pour les utilisateurs de la 2.0.4 » :
   à trancher par une lecture du code de la passerelle et, si besoin, une mesure sur l'émulateur.
