# F2 — vérificateur unique, trois angles (Sonnet) — verdict : VRAI POSITIF, FAIBLE (reçu 2026-09-26 au soir)

**Gravité** FAIBLE, confiance moyenne-haute (bytecode de Material3 1.4.0 désassemblé ; durée non mesurée).

**Accessibilité** : aucune des feuilles (`VaultSheets.kt:388`, `:655`, `AppLockSettings.kt:251`) ne passe de
`properties` : politique par défaut `SecureFlagPolicy.Inherit` (`ModalBottomSheetDefaults.properties$default`).
`ModalBottomSheetDialogWrapper.<init>` appelle `updateParameters` puis `setSecurePolicy` **dans le
constructeur**, donc pendant la composition (dans le `remember{}` qui crée le wrapper), avant le
`DisposableEffect` de `SecureWindowGuard.kt:56-58`. `setSecurePolicy` lit
`isFlagSecureEnabled(composeView)` = `rootView.layoutParams.flags` de la fenêtre parente à cet instant ;
`shouldApplySecureFlag(Inherit, parent)` rend ce booléen. Seule `MainActivity.kt:271` pose le drapeau, plus
tard. Condition : réglage `AppSettings.kt:112` désactivé (défaut activé).

**Impact** : la fenêtre de la feuille reste capturable jusqu'à une recomposition qui relance le
`SideEffect updateParameters` (changement de paramètre, dont `onDismissRequest` recréé à chaque
recomposition de la feuille — pas la simple frappe, qui ne recompose qu'une sous-portée). Une capture
continue peut prendre l'ouverture et les premières frappes ; la position des touches du pavé donne le code
(`VaultSheets.kt:570`).

**Défenses** : aucune hors le réglage par défaut ; `AppLockRecents.kt:40` couvre la miniature des récents
(API 33+ avec verrou), pas cette fenêtre fille.

**Correction évidente** : `ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn)` sur les
trois feuilles de secret.
