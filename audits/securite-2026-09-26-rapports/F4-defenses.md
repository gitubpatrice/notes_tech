# F4 — angle DÉFENSES — verdict : VRAI POSITIF, FAIBLE (reçu 2026-09-26)

## VERDICT : VRAI POSITIF. Gravité retenue : FAIBLE (inchangée)
Aucune défense, ni dans l'app ni dans les bibliothèques résolues.

1. **KeyboardOptions absentes** : corps `NoteEditorScreen.kt:630-641` (`value`, `onValueChange`, `label`, `placeholder`, `textStyle`, `colors`, `modifier`) ; titre `:738-763` (idem + `singleLine`). Material3 1.4.0 `TextField(TextFieldValue,…)` → `KeyboardOptions.Companion.getDefault()` (offsets 1464-1467) ; foundation `getAutoCorrectOrDefault()` rend `true` si `null` : autocorrection active par défaut.
2. **EditorInfo** : transformations Gradle en **1.11.3** (BOM `2026.06.00`, `libs.versions.toml:8`). `LegacyTextInputMethodRequest.createInputConnection` → `EditorInfo_androidKt.update-pLxbY9I` : `imeOptions` = action IME, `IME_FLAG_NO_ENTER_ACTION` (0x40000000), `IME_FLAG_NO_FULLSCREEN` (0x2000000) (offsets 650-658) ; `inputType` = `TYPE_CLASS_TEXT` + `MULTI_LINE` (0x20000) + `AUTO_CORRECT` (0x8000) (599-615). 1897 classes de foundation 1.11.3 balayées : 0 occurrence de `int 16777216` (`IME_FLAG_NO_PERSONALIZED_LEARNING`) ; pas de `TYPE_TEXT_FLAG_NO_SUGGESTIONS` (0x80000). ui 1.11.3 : `TextInputServiceAndroid.update` seul autre écrivain d'`imeOptions`, sans ce drapeau.
3. **Intercepteur** : aucun (`InterceptPlatformTextInput`, `PlatformTextInputInterceptor`, `PlatformImeOptions`, `privateImeOptions`, `onCreateInputConnection` absents de `app/src`, material3 1.4.0, foundation 1.11.3).
4. **Réglage « clavier incognito »** : aucun (`data/prefs/AppSettings.kt`) ; manifeste `adjustResize` seulement (`:68`).
5. **Décision documentée** : aucune (`04-PIEGES.md` §88, l. 2528-2575, concerne l'autoremplissage du champ de phrase secrète).
6. **`FLAG_SECURE`** (`MainActivity.kt:271`) : sans effet sur l'apprentissage.

**Chemin** : `NoteEditorViewModel.kt:797` → `:821-822` → champs `:631`, `:739` ; l'utilisateur tape en clair ; le clavier reçoit un EditorInfo sans demande d'abstention, autocorrection active. Attaquant (A, contrainte physique, `PanicScreens.kt:60`) : lit ce que le clavier a retenu par ses suggestions, dans n'importe quel champ ; contourne coffre et verrou. Gain : fragments du clair, qui survivent au reverrouillage et à la panique. L'app traite la mémorisation par un tiers comme une fuite ailleurs (presse-papiers sensible et purgé, `SensitiveClipboard.kt:25-33`) ; « Le clair ne vit qu'ici » (`NoteEditorViewModel.kt:164`).

**FAIBLE** : le drapeau n'est qu'une demande ; fuite fragmentaire ; téléphone déverrouillé en main + apprentissage actif (défaut de Gboard et Samsung ; non vérifiable depuis le dépôt). Si A = autre app sans privilège : le chemin tombe.
