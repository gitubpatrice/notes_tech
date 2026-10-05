# F1 — angle DÉFENSES (Sonnet) — verdict : VRAI POSITIF, FAIBLE (reçu 2026-09-26 au soir)

Chemin confirmé : `NoteEditorViewModel.kt:797`, `:819-827` → `NoteEditorScreen.kt:630-641` (corps),
`:738-763` (titre), `TextField` ordinaires ; aucun `readOnly`, aucune désactivation de la sélection liée au
coffre ; aucun `LocalTextToolbar`/`LocalClipboardManager`/`TextToolbar` redéfini dans
`com.filestech.notes_tech` ; seules compositions locales à la racine : `MainActivity.kt:90-93`
(`LocalSecureWindow`, `LocalExternalActivityGuard`). Jumeau protégé : menu Copier →
`SensitiveClipboard.copier()` (`:89-108`, marquage `:269-273` API 33+, effacement 60 s `:103-106`, `:338`),
atteint par aucune autre route. Aucune purge au verrouillage du coffre ; seule purge : panique.
Confirmation dans le dépôt : `docs/04-PIEGES.md:2611-2620` (§90) — `CopyText`/`CutText` présents sur un
champ ordinaire (témoin). Gain : persistance du clair au-delà de la consultation, sans marquage ; B borné
par les restrictions d'Android 10+.
