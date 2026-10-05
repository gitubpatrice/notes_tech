# P4 — vérificateur unique, trois angles (Sonnet) — verdict : VRAI POSITIF, FAIBLE (reçu 2026-09-26 au soir)

**Gravité** FAIBLE, confiance moyenne-haute.

**Accessibilité** : une copie par le menu avant la panique (`NoteEditorViewModel.kt:590-597` →
`SensitiveClipboard.kt:89-108`), geste ordinaire ; A ouvre ensuite l'historique du clavier (Gboard,
Samsung) depuis n'importe quel champ.

**Impact** : clair de note, potentiellement complet, alors que l'écran dit « Toutes les données ont été
effacées » (`values-fr:368`) après « aucune récupération forensique possible » (`:379`).
`clairPeutSubsister` (`PanicService.kt:228-230`) ne dépend que de `viderEtVerifier()`, qui ne teste que le
clip principal (`SensitiveClipboard.kt:255-258`) : l'avertissement `panic_incomplete_plaintext`
(`values-fr:394`, qui nomme la note copiée) ne se déclenche jamais dans ce cas.

**Défenses** : `clearPrimaryClip()` (`:330`) n'atteint pas le magasin d'un clavier ; `EXTRA_IS_SENSITIVE`
seulement API 33+ (`:269-273`, minSdk 24), et même alors les claviers sont « censés » le respecter
(`:29-30`). Ni `privacy.md` (`:45` range le presse-papiers parmi ce qui est effacé), ni `terms.md`, ni
l'écran de fin ne disent la limite.

**Tranché** : l'app ne peut pas atteindre l'historique d'un clavier tiers ; le défaut est une promesse
excessive et non qualifiée dans un écran de sécurité (CWE-451).
