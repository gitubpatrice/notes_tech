# F4 — angle IMPACT — verdict : VRAI POSITIF, FAIBLE (reçu 2026-09-26)

## VERDICT : VRAI POSITIF. Gravité retenue : FAIBLE (inchangée)
Aucune atténuation ; gain réel mais étroit ; comportement du clavier non observable sans exécution.

**Attaquant** : A, téléphone déverrouillé, app en arrière-plan : l'adversaire du coffre (`NotesTechApplication.kt:101-103`, `:109` ; `AndroidVaultKeystore.kt:270-272` « on peut contraindre un doigt, pas un mot mémorisé »). Coffre fermé, aucune autre voie (titre et corps scellés ensemble, `FolderVaultService.kt:493`, `:501`). Entrée : les préfixes que A tape n'importe où.

**Opération** : clair dans les champs (`NoteEditorViewModel.kt:797`, `:821-822`) ; `NoteEditorScreen.kt:630-641`, `:738-763` : `TextField` Material3 sans `keyboardOptions` (couleurs seulement, `champSansDecor()`, `:913`). `javap` de `EditorInfo_androidKt.update` (foundation-android 1.11.4 et 1.11.3 ; BOM `2026.06.00`, `libs.versions.toml:8`) : constantes 0x80000000, 0x40000000, 0x02000000, 0x20000, 0x8000 (autocorrection : suggestions demandées) ; jamais 0x01000000 ; appelée par `LegacyTextInputMethodRequest` et `AndroidTextInputSession` ; `KeyboardOptions` sans paramètre « incognito ».

**Défenses** : aucune (ni `InterceptPlatformTextInput`, `PlatformTextInputInterceptor`, `EditorInfo`, `IME_FLAG`, `PlatformImeOptions`, `onCreateInputConnection`) ; `FLAG_SECURE`, `lockAll`, panique ne touchent pas la mémoire du clavier.

**Impact** : réel (trace durable hors de portée du verrouillage et de la panique ; `raw-fr/privacy.md:39-48` énumère ce que la panique efface, sans le clavier) mais petit : fragments (mots rares, courts enchaînements), trois suggestions à la fois, préfixes à deviner, **mots non attribuables au coffre** (le clavier mélange tout ce qui est tapé) ; surtout un oracle « l'utilisateur a-t-il tapé tel mot ? » ; seulement le texte tapé (dictée et collé non vérifiés). Non confirmé : apprentissage effectif de Gboard / clavier Samsung (actif par défaut chez les deux ; le drapeau reste une demande) — obstacle, pas réfutation.

**Promesses** : aucune sur le clavier (strings, privacy, terms, README) ; `privacy.md:50` : l'écran de fin dit si du lisible peut subsister ; restes énumérés (`values-fr/strings.xml:394`) : export, dictée, presse-papiers. Aucune promesse rompue ; le constat tient par le modèle de menace du code.

**Parité Flutter** : non tranchée (dépôt Flutter non lu) ; régression si la 2.0.9 coupait l'apprentissage, défaut antérieur sinon.

**FAIBLE** : fragments non attribuables ; exploitation exigeante.

Hors constat : champ de lien `FeuilleDAutocompletion.kt:153-165`, même défaut : un correctif limité à `:630` et `:738` le laisserait ouvert. Bytecode extrait dans `scratchpad\f4-compose\`.
