# Cellule 5 — « fuite par les journaux, le presse-papiers, les notifications, les récents » — rapport du chercheur (reçu 2026-09-26)

Quatre constats : un MOYEN, trois FAIBLE (renommés F1-F4 par l'orchestrateur ; C1-C4 dans le rapport d'origine). Journaux release, notifications, messages à l'écran et listes de notes : rien. Réponse à la cellule « contrainte » : non (F2).

Méthode : lecture seule ; tables de constantes des AAR du cache Gradle (`E:\.gradle\caches\modules-2\files-2.1\`) lues en mémoire : Material3 1.4.0, Compose foundation et ui 1.11.4 (BOM 2026.06.00 absent du cache ; voisins 2026.06.01 et 2026.09.00 → Material3 1.4.0, ui 1.11.4 / 1.12.1 ; résultats identiques sur foundation 1.11.3, 1.11.4, 1.12.1). Présence ou absence de symboles, pas le flux de contrôle.

Racine `R\` = `...\audit-ebee629\app\src\main\java\com\filestech\notes_tech\`.

## F1 (C1) — Copier/Couper depuis la barre de sélection de l'éditeur met le clair d'une note de coffre dans le presse-papiers ordinaire, sans marquage sensible ni effacement — MOYEN, CWE-359
- Opération : `R\ui\editor\NoteEditorScreen.kt:630-631` (`TextField(` / `value = state.content,`) ; jumeau titre `:738-739`.
1. `NoteEditorViewModel.kt:797` `vaults.decrypt(note)` ; `:819-827` état avec `title = claire.title`, `content = TextFieldValue(claire.content)`.
2. Deux `TextField` Material3 ordinaires (`NoteEditorScreen.kt:630-641`, `:738-763`) : ni `KeyboardOptions`, ni barre de sélection propre, ni presse-papiers propre. Appui long : Copier, Couper, Tout sélectionner (mesuré par le projet : `docs\04-PIEGES.md:2613`, `:2619`).
3. Aucune garde : zéro `LocalClipboard`, `LocalClipboardManager`, `LocalTextToolbar`, `TextToolbar` dans `app\src\main`.
4. Compose foundation/ui 1.11.4 : `foundation/internal/ClipboardUtils`, `ui/platform/AndroidClipboardManager` appellent `ClipData.newPlainText` et `setPrimaryClip` ; aucune classe ne référence `IS_SENSITIVE` : texte brut, sans marquage, sans minuterie.
5. Jumeau protégé : menu « Copier » (`NoteEditorViewModel.kt:596`) → `R\security\clipboard\SensitiveClipboard.kt` (sensible `:269-272`, effacement 60 s `:103-106`). Jumeau asymétrique.
- A (téléphone déverrouillé, app verrouillée, coffre fermé) : colle ailleurs ou ouvre l'historique du clavier ; lit l'extrait sans secret ; jamais effacé. B : lit le clip au focus (Android 10+ ; toast après coup à partir de 12) ; Android 7-9 par écouteur (minSdk 24). Android 13+ : la confirmation système affiche l'extrait en clair (fenêtre SystemUI, hors `FLAG_SECURE`) ; les claviers à historique s'appuient sur le marquage. Extension de V1 : après `lockAll()`, le texte resté affiché se copie aussi par ici.
- Gravité MOYEN ; confiance HAUTE sur le chemin, MOYENNE sur l'ampleur côté claviers.
- Recommandation : tout clair de note de coffre entrant au presse-papiers reçoit les protections du menu (marquage, effacement, purge de panique), barre comme menu, Copier comme Couper, titre comme corps ; sinon la barre ne propose pas ces actions. Test sur la barre elle-même.

## F2 (C2) — Feuilles de saisie d'un secret : le `FLAG_SECURE` « forcé » n'atteint pas la fenêtre de la feuille quand le réglage est désactivé — FAIBLE, CWE-696
- Opérations : `R\ui\vault\VaultSheets.kt:655` (`FeuilleDeCode`, `ModalBottomSheet(`), `:388` (`FeuilleDePhraseSecrete`), `R\ui\applock\AppLockSettings.kt:251` (`AppLockPinSheet`). Gardes inopérantes : `VaultSheets.kt:572`, `:296`, `AppLockSettings.kt:235` (`SecureWindowGuard()`).
1. La garde incrémente un compteur : `R\ui\secure\SecureWindowGuard.kt:57` → `R\ui\secure\SecureWindowController.kt:75` `fun force() = demandes.update { it + 1 }`.
2. Seule la fenêtre de l'activité reçoit le drapeau, après collecte et recomposition : `MainActivity.kt:157`, `:268`, `:271` `fenetre?.setFlags(FLAG_SECURE, FLAG_SECURE)`.
3. La feuille est une fenêtre de dialogue distincte (Material3 1.4.0 `ModalBottomSheetDialogWrapper`), créée dans la même composition que la garde (`HomeRoute.kt:304`, `:333`, `NoteEditorScreen.kt:258`, `:363`, `AppLockSettings.kt:141`) ; son drapeau vient de `setSecurePolicy` lisant `isFlagSecureEnabled()` de la fenêtre parente, politique par défaut (aucun `properties`/`securePolicy` passé) ; l'activité n'a pas encore le drapeau : la feuille naît sans.
4. Réévaluation incertaine : relu seulement par le `SideEffect { updateParameters }` du dialogue ; lambdas mémoïsées ; rien ne semble le recomposer pendant la saisie (non mesuré).
5. Jamais mesuré par le projet : `docs\04-PIEGES.md:1944` ; `SecureWindowGuardTest.kt:222-243` relit seulement la fenêtre de l'activité.
- Conditions : réglage « Masquer dans les apps récentes » désactivé (activé par défaut, `R\data\prefs\AppSettings.kt:112`) ; sous API 33, aucun verrou d'app (sinon `RecentsGuard` pose le drapeau d'avance, `R\ui\applock\AppLockRecents.kt:40`).
- B avec capture d'écran active (MediaProjection consentie, assistance à distance) enregistre pavé, pastilles, phrase révélée. Promesse : `VaultSheets.kt:293-295`, `:568-571` (« même si l'utilisateur l'a désactivé »). Exploiter exige ensuite l'appareil.
- Gravité FAIBLE ; confiance MOYENNE.
- Recommandation : `FLAG_SECURE` dès la première image de la fenêtre de saisie, quels que soient réglage et ordre des effets ; test relisant le drapeau de la fenêtre de la feuille.
- `PanicConfirmDialog` (`PanicScreens.kt:77`, `:84`) : même structure, aucun secret. Dialogues ouverts depuis un éditeur de coffre déjà protégé : héritent correctement.

## F3 (C3) — La première image d'une note de coffre déchiffrée s'affiche avant le `FLAG_SECURE` — FAIBLE, CWE-696
- `NoteEditorScreen.kt:630-631` composé dans le même passage que `NoteEditorScreen.kt:308` `SecureWindowGuard(active = state.isVaultNote)`.
- `NoteEditorViewModel.kt:111` `val isVaultNote: Boolean get() = folder?.isVault == true`, `folder = null` pendant le chargement ; `:819-827` pose en une mise à jour `folder = dossier` et le clair ; le drapeau n'atteint la fenêtre qu'à la recomposition suivante. Le dépôt tient ce délai pour une fuite (`MainActivity.kt:158-160`, `SecureWindowController.kt:55-58`) mais ne l'a traité que pour la valeur initiale.
- Même position que F2 ; gain : une ou deux images du premier écran (titre, premières lignes) de chaque note de coffre ouverte.
- Gravité FAIBLE ; confiance MOYENNE. Recommandation : le clair ne s'affiche que dans une fenêtre déjà protégée.

## F4 (C4) — Les champs d'une note de coffre laissent le clavier apprendre ce qu'on y tape — FAIBLE, CWE-524
- `NoteEditorScreen.kt:630` (corps), `:738` (titre), sans mode incognito : `foundation/text/input/internal/EditorInfo_androidKt` (1.11.4) sans la constante 0x01000000 (`IME_FLAG_NO_PERSONALIZED_LEARNING`) ; l'app ne l'ajoute nulle part (zéro `InterceptPlatformTextInput`, `PlatformTextInputInterceptor`, `NO_PERSONALIZED_LEARNING`, `imeOptions`).
- A tape les premières lettres dans une autre app : suggestions de mots des notes de coffre. Contraste : phrase secrète en `KeyboardType.Password` (`VaultSheets.kt:543`).
- Gravité FAIBLE ; confiance HAUTE sur l'absence, MOYENNE sur l'exposition (dépend du clavier).
- Recommandation : titre, corps et saisie de lien d'une note de coffre demandent au clavier de ne rien apprendre ; vérifiable sur l'`EditorInfo`.

## Notes (en deçà d'un constat)
- **N1 — commentaire qui ment** : `proguard-rules.pro:55-58` et `NotesTechApplication.kt:49-51` affirment que R8 retire Timber et `android.util.Log` en release ; aucune règle `-assumenosideeffects`. Seule protection réelle : aucun arbre planté en release (`NotesTechApplication.kt:42-44`, `build.gradle.kts:200` `LOG_ENABLED false`).
- **N2 — actions tierces dans la barre de sélection** : `<queries>` `PROCESS_TEXT` (`AndroidManifest.xml:103-109`, hérité du gabarit Flutter) ; foundation 1.11.4 contient `ProcessTextApi23Impl` ; une app malveillante peut s'appeler « Copier » ; exige un appui de l'utilisateur.
- **N3 — presse-papiers sous l'API 29** : API 24-28, toute app lit le presse-papiers en arrière-plan ; pas de marquage sous l'API 33 ; limite de plateforme non mentionnée dans la KDoc de `SensitiveClipboard`.
- **N4 — export d'une note de coffre** : nom de fichier = titre déchiffré + ` [unlocked]` (`NoteExporter.kt:193`), en `EXTRA_SUBJECT` (`Partage.kt:31`), en cache jusqu'au démarrage suivant ; partage voulu.

## Cherché sans rien trouver
- Journaux release : aucun arbre ; aucun `android.util.Log`, `println`, `System.out/err`, `printStackTrace` ; Timber : identifiants, tailles, classes ; messages d'exception construits : UUID, tailles, codes, raisons, classes seulement ; `Note.toString` (`Note.kt:54-62`), `EncryptedBody.toString` (`:97`) caviardés ; natif : sorties `print_*` coupées (`notes_stt_jni.cpp:148-151`), aucun `__android_log`.
- Notifications, toasts, raccourcis, widgets, `setTaskDescription` : aucun ; ni service de premier plan ni `POST_NOTIFICATIONS`.
- Récents : `FLAG_SECURE` par défaut ; verrou configuré : `setRecentsScreenshotEnabled(false)` API 33+, `FLAG_SECURE` dessous ; cartes d'une note verrouillée muettes, description d'accessibilité comprise (`NoteCard.kt:76-80`, `:106-114`, `:178`, `:200`) ; autocomplétion sans notes de coffre (`NoteDao.kt:258`).
- Messages à l'écran : ressources seulement (`UserMessages.kt:38-53`) ; aucun `.message` affiché.
- Presse-papiers ordinaire : `Liens.kt:46` (liens constants, `AboutScreen.kt:110`), `VoiceSetupScreen.kt:524` (adresse publique du modèle) ; liens de l'aperçu sans repli presse-papiers (`LienExterne.kt:25-32`).
- `SensitiveClipboard` : étiquette `"note"`, marquage API 33+, génération incrémentée après écriture réussie, réarmement sans borne, purge de panique vérifiée.
- Phrase secrète et pavés : type mot de passe (ni copie ni apprentissage, `04-PIEGES.md:2613`) ; pavés dessinés par l'app.
- Partage : FileProvider non exporté, accès limité à l'intention (`Partage.kt:27-54`), à l'initiative de l'utilisateur.
- État sauvegardé : identifiants et booléens, plus le titre de lien en saisie (`FeuilleDAutocompletion.kt:78`), dans le bundle système seulement.
- Accessibilité : annonces sans contenu (`NoteEditorScreen.kt:1058`, `VaultSheets.kt:1122`) ; un service d'accessibilité lit tout, position déjà privilégiée.
- Non tranché : l'autoremplissage de Compose inclut-il le texte des champs de l'éditeur ?
