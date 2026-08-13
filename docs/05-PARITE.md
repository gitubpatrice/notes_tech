# Parité fonctionnelle 2.0.3 → 3.0.0

> Se remplit **au fil des phases 5-7**, pas à la fin. Une case cochée veut dire « vérifié sur
> appareil », pas « le code existe ».
>
> La bascule (phase 8) est bloquée tant qu'une ligne reste vide sans justification écrite.

---

## Écrans (10)

| Écran Flutter | Lignes | Kotlin | Vérifié | Notes |
|---|---:|---|:---:|---|
| `splash_screen.dart` | 259 | | ☐ | Signature Files Tech ; masque l'acquisition de la KEK |
| `home_screen.dart` | 564 | | ☐ | Bannière brouillons perdus (`vault_lost_drafts`) |
| `note_editor_screen.dart` | 1 123 | | ☐ | Auto-sauvegarde 500 ms, backlinks, autocomplétion `[[…]]` |
| `search_screen.dart` | 142 | | ☐ | FTS5, anti-rebond 200 ms |
| `trash_screen.dart` | 263 | | ☐ | Rétention 30 jours |
| `settings_screen.dart` | 802 | | ☐ | Thème, tri, fenêtre sécurisée, langue, auto-verrouillage |
| `about_screen.dart` | 622 | | ☐ | Version lue dynamiquement via `PackageInfo` |
| `mentions_legales_screen.dart` | 131 | | ☐ | Rend `PRIVACY.{fr,en}.md` / `TERMS.{fr,en}.md` |
| `voice_setup_screen.dart` | 465 | | ☐ | Phase 7 |
| `panic_complete_screen.dart` | 143 | | ☐ | Écran terminal du mode panique |

## Composants (16)

| Composant Flutter | Lignes | Kotlin | Vérifié |
|---|---:|---|:---:|
| `vault_pin_sheets.dart` | 857 | | ☐ |
| `folders_drawer.dart` | 780 | | ☐ |
| `voice_recording_overlay.dart` | 460 | | ☐ |
| `vault_passphrase_sheets.dart` | 382 | | ☐ |
| `folder_dialogs.dart` | 225 | | ☐ |
| `note_card.dart` | 222 | | ☐ |
| `backlinks_panel.dart` | 208 | | ☐ |
| `link_autocomplete_sheet.dart` | 206 | | ☐ |
| `panic_confirm_dialog.dart` | 167 | | ☐ |
| `move_to_folder_sheet.dart` | 165 | | ☐ |
| `passphrase_text_field.dart` | 105 | | ☐ |
| `voice_record_button.dart` | 85 | | ☐ |
| `blocking_progress_dialog.dart` | 51 | | ☐ |
| `empty_state.dart` | 50 | | ☐ |
| `vault_warning_banner.dart` | 43 | | ☐ |
| `sheet_handle.dart` | 27 | | ☐ |

## Services transverses

| Service | Lignes | Vérifié | Point de vigilance |
|---|---:|:---:|---|
| `folder_vault_service.dart` | 1 582 | ☐ | Ouvrir un coffre **créé par la version Flutter** |
| `note_export_service.dart` | 519 | ☐ | Export `.md` d'une note de coffre — le corps ne doit pas être vide |
| `panic_service.dart` | 478 | ☐ | Ordre des étapes ; un rapport ne doit jamais mentir sur un effacement |
| `voice_service.dart` | 450 | ☐ | Phase 7 |
| `backlinks_service.dart` | 401 | ☐ | Plafond de balayage 50 ko |
| `keystore_bridge.dart` | 207 | ☐ | Repris depuis `KeystoreBridge.kt`, sans MethodChannel |
| `secure_window_service.dart` | 103 | ☐ | `FLAG_SECURE` avec compteur de références |
| `settings_service.dart` | 116 | ☐ | Lire les clés `flutter.*` existantes |

## Promesses publiques à ne pas casser

Ce ne sont pas des fonctionnalités mais des engagements affichés sur files-tech.com et dans les
métadonnées F-Droid. Une régression ici est publique.

| Promesse | Contrôle | Vérifié |
|---|---|:---:|
| Zéro permission Internet | Contrôle CI sur le manifeste **fusionné** | ☐ |
| 100 % local, aucune donnée ne sort | Absence de toute dépendance réseau | ☐ |
| Base chiffrée au repos | SQLCipher, clé scellée par le Keystore | ☐ |
| Coffres par dossier | Argon2id + AES-256-GCM, paramètres identiques | ☐ |
| Dictée vocale sur l'appareil | Phase 7 | ☐ |

⚠️ Le manifeste à contrôler est le **fusionné**, jamais le manifeste source seul — une dépendance
peut y injecter une permission :
`app/build/intermediates/merged_manifest/release/…/AndroidManifest.xml`

## Migration des données

| Cas | Vérifié |
|---|:---:|
| 2.0.3 → 2.0.4 → 3.0.0 (chemin nominal, couche ①) | ☐ |
| 2.0.3 → 3.0.0 direct (couche ② de secours) | ☐ |
| KEK introuvable, base présente → refus, **base intacte après** (couche ③) | ☐ |
| Installation neuve | ☐ |
| Coffre passphrase créé en Flutter, ouvert en Kotlin | ☐ |
| Coffre PIN créé en Flutter, ouvert en Kotlin | ☐ |
| Auto-effacement interrompu (`vault_wipe_pending_*`) repris au démarrage | ☐ |
