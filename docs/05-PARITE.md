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

## Écarts assumés avec l'application publiée, relevés en phase 3

> Un écart n'est acceptable que s'il est **choisi**, écrit, et justifié. Ceux-là le sont.

| Sujet | Application publiée | Ce portage | Pourquoi |
|---|---|---|---|
| Auto-lien `[[son propre titre]]` | résolu vers elle-même — le garde-fou est défait par `resolveDangling` | reste fantôme | l'intention du code d'origine est explicite ; `target_id` n'est pas partagé entre versions |
| Rétroliens depuis une note verrouillée | pas de garde SQL ; les liens sont purgés au verrouillage | garde SQL **en plus** | la non-divulgation ne doit dépendre d'aucune donnée écrite par un autre programme |
| Rétroliens : liens fantômes | inclus (`links_dao.dart:64`) | inclus | une première version de ce portage les manquait — corrigé |
| Casse Unicode : osage, adlam | inchangés (table de casse figée) | passent en minuscules | écart mesuré, borné, se répare à la réindexation. Cf. `09-VECTEURS-DE-PARITE.md` |
| Longueur du nom de dossier | non plafonnée | non plafonnée | plafonner refuserait de renommer un dossier existant plus long. À traiter au champ de saisie |
| Déplacer une note verrouillée | passe par le service de coffres | **refusé** avec une exception nommée | chaque coffre a sa clé ; le blob ne se transporte pas. Sera levé en phase 4 |
| Flux d'événements de changement | `NoteChangeEvent` + service temporisé | invalidation Room + transaction | cf. `01-DECISIONS.md` D-009 |

## Reporté à la phase 5, avec l'interface

Ces éléments sont des **fonctions d'affichage** du modèle. Les porter avant l'écran qui les
consomme reviendrait à écrire du code qu'aucun test ne peut exercer.

| Élément | Source Dart | Piège connu |
|---|---|---|
| `Note.excerpt` | `note.dart:200` | cinq expressions régulières enchaînées, plafond à 200 caractères ; vide pour une note verrouillée |
| `Note.wordCount` | `note.dart:86` | ⚠️ rend **1** pour un contenu fait uniquement d'espaces — `"".split(\s+)` rend une liste d'un élément vide. Reproduire le quirk, ou le corriger sciemment |
| `Note.characterCount` | `note.dart:85` | compte les unités UTF-16, pas les caractères perçus |
| Libellés de `NoteSortMode` | `note.dart:230` | à localiser, pas à recopier en dur |

## Reporté à la phase 6

| Élément | Pourquoi il ne pouvait pas être fait en phase 3 |
|---|---|
| Réconciliation complète des liens au démarrage | l'indexation transactionnelle rend les incohérences impossibles **côté Kotlin**, mais la base vient de la version Flutter et peut en porter |
| Auto-complétion `[[…]]` dans l'éditeur | `NotesRepository.suggestTitles` existe et est testé ; il lui manque son écran |

## ⚠️ Correctifs appliqués à l'application Flutter le 2026-08-13

> Branche `fix/defauts-releves-pendant-le-portage` dans `notes_tech`, commit `ca72f2c`.
> **Non fusionnée, non publiée** — version et `versionCode` inchangés.

Le portage a servi de relecture ligne à ligne de l'original. Trois défauts en sont sortis, corrigés
côté Flutter puisque c'est **la version publiée**, celle que les utilisateurs font tourner.

| Défaut | Conséquence | Correctif |
|---|---|---|
| `resolveDangling` annulait le garde-fou anti-auto-lien | une note figurait dans ses **propres** rétroliens | `AND source_id <> ?` |
| `listPlaintextInFolder` ne voyait pas un titre en clair **sans corps** | une note de coffre héritée restait lisible au repos, indéfiniment | critère élargi, strictement additif |
| `wordCount` rendait **1** pour un contenu fait d'espaces | compteur faux | élaguer avant de tester la vacuité |

Renforcement : `backlinkSources` exclut désormais les notes verrouillées comme **source**.

### Ce qui n'a **pas** été corrigé, et pourquoi

**Une étiquette contenant une virgule serait coupée en deux.** Vérifié : **aucun chemin de
l'application n'écrit jamais d'étiquette**. La colonne `tags` est dormante — seules la lecture et
l'export existent. Le défaut est inatteignable, et corriger le codage changerait un format de
données partagé entre les deux versions.

⚠️ J'avais annoncé ce point comme « de la perte de donnée visible par l'utilisateur ». C'était faux :
je n'avais pas vérifié qu'il existait un chemin de saisie.

**`folders_dao.update` écrit la ligne entière, colonnes de coffre comprises.** Effacer
`vault_kek_wrapped` rendrait toutes les notes du coffre définitivement illisibles. Mais aucun défaut
aujourd'hui — les objets viennent de la base — et modifier un chemin d'écriture qui fonctionne dans
une application **publiée** coûte plus qu'il ne rapporte. Le portage Kotlin, lui, ne l'expose pas
(D-010).

### Ce que les tests couvrent, et ce qu'ils ne couvrent pas

`wordCount` est testé (5 cas). Les deux correctifs SQL ne le sont pas : la suite Flutter n'a **aucun
harnais de base de données**, un choix que ses auteurs documentent explicitement dans
`folder_vault_service_test.dart`.

Le portage Kotlin, lui, exerce la même sémantique contre du vrai SQLite —
`une_note_qui_se_cite_elle_meme_ne_produit_pas_de_lien_vers_elle_meme` et
`une_note_de_coffre_au_titre_en_clair_et_au_corps_vide_est_detectee`. C'est un contrôle croisé, pas
une couverture de la version Flutter.

**70 tests verts côté Flutter (65 avant), `flutter analyze` sans avertissement.**

### Quand publier ces correctifs — décision du 2026-08-13

**Attendre que la MR F-Droid !37885 soit tranchée.** Elle est ouverte, à **2.0.3 / versionCode 51**,
son blocage Play Core est résolu et documenté, et elle est en `waiting-for-upstream` — la balle est
chez le mainteneur.

| Raison | Détail |
|---|---|
| Ne pas bouger la cible | la MR vient d'être débloquée après 21 commentaires ; taguer 2.0.4 obligerait à la mettre à jour en plein examen, ou à la laisser en retard |
| Publier après coûte **moins** | `AutoUpdateMode: Version` + `UpdateCheckMode: Tags` ⇒ une fois fusionnée, **un tag suffit** |
| Exposition étroite | notes de coffre créées **avant la 2.0.0**, titre rempli, corps vide ; `_sealIfVault` protège les écritures depuis |

**Déclencheur** : MR fusionnée ou fermée. **Borne** : quatre à six semaines, après quoi publier
quand même — un correctif de confidentialité qui existe ne doit pas attendre indéfiniment un tiers.

⚠️ Le bump touche `pubspec.yaml` **et** `AppConstants.appVersion`, plus fastlane FR+EN, les trois
surfaces du site, et le `.yml` F-Droid.
