# F1 — angle IMPACT — verdict : VRAI POSITIF, abaissé à FAIBLE (reçu 2026-09-26)

**VERDICT : VRAI POSITIF. Gravité abaissée de MOYEN à FAIBLE.**

**Mécanique confirmée** : `NoteEditorViewModel.kt:797`, `:821-822` ; champs sans `PasswordVisualTransformation` (`NoteEditorScreen.kt:630-641`, `:738-763`) ; un champ ordinaire porte `CopyText` et `CutText` (`androidTest\...\ui\vault\FeuillesDeCoffreTest.kt:226-228`) ; aucun `LocalClipboard` ni `LocalTextToolbar` surchargé (seul `CompositionLocalProvider` : `MainActivity.kt:90-92`, `LocalSecureWindow`, `LocalExternalActivityGuard`). Compose foundation 1.11.3 (BOM `2026.06.00`, `libs.versions.toml:8`) désassemblé hors dépôt : `TextFieldSelectionManager$copy$1` → `ClipboardUtils.toClipEntry` → `ClipData.newPlainText("plain text", …)`, sans extra ; aucune occurrence de `IS_SENSITIVE` dans foundation ni ui (1.11.3, 1.11.4, 1.12.1). Seul appelant de la purge : `PanicService.kt:354` ; rien n'efface le presse-papiers au verrouillage du coffre ni à l'arrière-plan.

**Qui écrit, gain réel** : le propriétaire écrit l'entrée (Copier/Couper) ; l'attaquant lit un résidu.
- A (téléphone déverrouillé, app et coffre fermés) colle n'importe où. Gain étroit : la protection du menu est elle-même partielle — effacement à 60 s seulement si l'app est au premier plan à l'échéance (`SensitiveClipboard.kt:63-65` ; hors premier plan, `:192` réarme sans effacer). Flux dominant (copier puis coller ailleurs) : le menu laisse le même clair au même A.
- Différentiel réel : (1) **flux internes** (couper/coller pour réorganiser une note de coffre, puis ≥ 60 s dans l'app : le menu aurait effacé, la barre jamais) — fonde le verdict ; (2) `IS_SENSITIVE` seulement API 33+ (`SensitiveClipboard.kt:269` ; `minSdk = 24`), effet sur les claviers = indice (« censés », `:29-30`).
- B : l'IME lit déjà le champ ; app au premier plan : même différentiel ; depuis Android 10, une app en arrière-plan ne lit pas le presse-papiers.

**Conséquences corrigées**
- « Jamais effacé » faux : la panique vide le presse-papiers sans regarder d'où vient le texte (`SensitiveClipboard.kt:134-139` → `viderEtVerifier()` sans condition ; `:132`). Toute copie ultérieure écrase aussi.
- Aperçu d'Android 13+ : pendant que le propriétaire lit le même texte ; gain résiduel = partage ou enregistrement d'écran (`FLAG_SECURE` par défaut, `AppSettings.kt:112`, ne couvre pas SystemUI) — non vérifiable ici.
- Aucune promesse publique rompue : `raw-fr/privacy.md:45` (« le presse-papiers, où une note copiée attend en clair », parmi ce que la panique efface — tenu pour les deux chemins). « Presse-papiers sécurisé » seulement dans `error_clipboard_secure_unavailable` (`values-fr/strings.xml:536`), affiché si la copie par le menu échoue.
- Volume : jusqu'à la note entière (Tout sélectionner), plus le titre (scellé en format 2, `FolderVaultService.kt:530-532`). Durée : jusqu'à la copie suivante, la panique ou le redémarrage (effacement automatique d'Android 13 après une heure non lu, non retenu).

**FAIBLE et non MOYEN** : impact borné à l'extrait choisi par le propriétaire ; attaquant opportuniste ; téléphone déverrouillé avant toute nouvelle copie ; différentiel partiel (flux internes + marqueur API 33+).

Non retenu faute de vérification : historique des claviers (Gboard, Samsung), non purgé par `clearPrimaryClip`.
