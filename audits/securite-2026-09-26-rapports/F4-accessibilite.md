# F4 — angle ACCESSIBILITÉ — verdict : VRAI POSITIF, FAIBLE (reçu 2026-09-26)

**VERDICT : VRAI POSITIF. Gravité FAIBLE, maintenue.** Tout ce que l'application contrôle est confirmé ; la partie clavier relève d'un comportement documenté.

**1. Champs ordinaires** : corps `NoteEditorScreen.kt:630-641` `TextField(value = state.content, …)` sans `keyboardOptions` ; titre `:738-763` (`singleLine = true`) sans `keyboardOptions` ; `TextField` Material3 (import `:60`) ; affichés seulement coffre ouvert (branche `else` `:609`, après `:580`) ; feuille de lien : `KeyboardOptions(imeAction = ImeAction.Done)` seulement (`ui/editor/FeuilleDAutocompletion.kt:165`).

**2. La bibliothèque ne permet pas de demander le mode incognito** : `gradle/libs.versions.toml:8` `compose-bom = "2026.06.00"` → `foundation-android` **1.11.3** (la 1.11.4 citée appartient au BOM 2026.06.01 ; même pool de constantes pour 1.11.3, 1.11.4, 1.12.1 ; graphe non résolu, aucun build). `EditorInfo_androidKt.update-pLxbY9I` (1.11.3) : trois `ior` sur `imeOptions` seulement — `0x80000000` (Ascii), `0x40000000` (NO_ENTER_ACTION), `0x02000000` (NO_FULLSCREEN) ; `0x01000000` absent du pool ; seules `privateImeOptions` et `hintLocales` transmises. `KeyboardOptions` 1.11.3 sans propriété d'apprentissage ; `Password` change la variation d'`inputType` (`sipush 129`) — d'où le contraste avec `VaultSheets.kt:543`. Toutes les classes de foundation, ui, ui-text 1.11.3 et material3 1.4.0 parcourues : seules `EditorInfo_androidKt` et `TextInputServiceAndroid_androidKt` écrivent `imeOptions`/`inputType`, sans `0x01000000`.

**3. Aucune atténuation** : grep `app/src` (main, test, androidTest) : aucun `InterceptPlatformTextInput`, `PlatformTextInputInterceptor`, `imeOptions`, `EditorInfo`, `onCreateInputConnection`, `PlatformImeOptions`. `SecureWindowGuard` (`NoteEditorScreen.kt:308`) ne pose que `FLAG_SECURE` (`SecureWindowGuard.kt:22`), sans effet sur l'apprentissage. Manifeste : `adjustResize` seulement (`AndroidManifest.xml:68`).

**Attaquant et gain** : A, téléphone déverrouillé, sans le secret (adversaire prévu, `PanicScreens.kt:60-61` ; panique promet « no forensic recovery possible », `values/strings.xml:444`). Gain partiel : en tapant des préfixes ailleurs, mots hors dictionnaire (noms, termes rares) et prédictions. Traces dans le stockage du clavier, hors de l'app : survivent au verrouillage du coffre, à sa suppression et à la panique.

**Non confirmé** (rien exécuté) : que le clavier apprenne et propose réellement (contrat Android de `IME_FLAG_NO_PERSONALIZED_LEARNING`, API 26, cité de mémoire ; Gboard passe en incognito avec le drapeau). Même posé, le drapeau n'est qu'une demande.

**FAIBLE** : fuite partielle ; extraction à l'aveugle, préfixe par préfixe ; dépend du clavier ; téléphone déverrouillé en main.

Hors constat : même absence pour la feuille de lien (`FeuilleDAutocompletion.kt:153-171`), où l'on tape un titre de note du coffre en entier.
