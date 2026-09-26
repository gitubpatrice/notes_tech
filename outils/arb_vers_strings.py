# -*- coding: utf-8 -*-
"""
Transpose `notes_tech/lib/l10n/app_{fr,en}.arb` vers `values/strings.xml` et `values-fr/strings.xml`.

Pourquoi un script et pas une transposition a la main : 311 cles x 2 langues, et les placeholders
ARB (`{count}`) ne s'ecrivent pas comme ceux d'Android (`%1$d`). Une transposition manuelle produit
des oublis silencieux — une cle absente d'une langue ne se voit qu'a l'ecran, dans cette langue.

⚠️ Ce script N'ECRIT RIEN tant que les deux fichiers ne sont pas integralement construits et
encodables. Le 06/08 un script a ouvert MEMORY.md puis echoue sur un UnicodeEncodeError : fichier
vide. On construit tout, on verifie, puis on ecrit.
"""
import io
import json
import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8")

SRC = r"j:\applications\notes_tech\lib\l10n"
# Resolved from this script's own location, not written out: the port's folder is about to be
# renamed (2026-09-24), and an absolute path here would make the generator write into a directory
# that no longer exists — or worse, into a stale copy that still does.
DST = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, "app", "src", "main", "res")

# ⚠️⚠️ LES AJOUTS DU PORTAGE, ET POURQUOI ILS SONT ICI ET PAS DANS `strings.xml`
#
# Ce script REGENERE les deux fichiers de A a Z. Tout ce qui a ete ajoute directement dans
# `strings.xml` en disparait au passage suivant, sans erreur ni avertissement — le fichier compile,
# et c'est le CODE qui casse, parce qu'il reference une ressource qui n'existe plus.
#
# C'est arrive le 2026-08-15 : une regeneration a efface HUIT chaines ecrites a la main au fil des
# phases, dont deux du jour meme (`note_editor_copy_empty`, `trash_emptied`). Le mecanisme de
# conservation existait pourtant depuis la phase 1 — mais il ne portait que l'ecran d'echec au
# demarrage, et personne n'y avait ajoute les suivantes. Un garde-fou qui existe ne protege que ce
# qu'on a pense a lui confier.
#
# ⚠️ **Toute chaine du portage s'ajoute ICI, jamais dans `strings.xml`.**
#
# Rendues dans leur section d'ecran, comme si elles venaient de l'ARB : un bloc en fin de fichier
# aurait separe les ajouts de l'ecran qu'ils servent, et c'est justement ce qui les a fait oublier.
AJOUTS_EN = {
    "common": """\
    <!-- Ajout du portage : Compose pose une fleche de retour la ou Flutter s'appuie sur le
         retour implicite de l'AppBar. « Fermer » decrivait mal ce geste. -->
    <string name="common_back">Back</string>
    <!--
      Ajout du portage, et REPARATION d'une divergence : le bouton ⋮ de l'accueil portait
      « Reglages » comme description, c'est-a-dire le nom d'UNE des deux entrees de son menu. Un
      lecteur d'ecran annoncait donc « Reglages, bouton » pour un bouton qui ouvre un menu.
      L'application publiee y met « moreButtonTooltip », la chaine de la plateforme
      (home_screen.dart:361). Compose n'expose pas d'equivalent public : d'ou cette chaine.
      Cf. 04-PIEGES.md §73.
    -->
    <string name="common_more_options">More options</string>
    <!--
      Port addition (2026-09-24): the wait imposed after too many wrong attempts, for a vault and
      for the app lock. notes_tech 2.0.9 removed `common_error_with`, through which the vault
      sheets showed "Error: 12 s"; it shows "Vault locked." instead, which drops how long to wait.
    -->
    <plurals name="common_retry_in_seconds">
        <item quantity="one">Too many attempts. Try again in %1$d second.</item>
        <item quantity="other">Too many attempts. Try again in %1$d seconds.</item>
    </plurals>
    <plurals name="common_retry_in_minutes">
        <item quantity="one">Too many attempts. Try again in %1$d minute.</item>
        <item quantity="other">Too many attempts. Try again in %1$d minutes.</item>
    </plurals>
""",
    "folder": """\
    <!-- ⚠️⚠️ REECRITURE, pas un ajout : voir REMPLACEES. La version Flutter ecrit « {n} note(s) »,
         le contournement qu'on emploie quand on n'a pas de pluriel — et Android en a un. -->
    <plurals name="folder_delete_decrypt_failed">
        <item quantity="one">Cannot decrypt %1$d note.</item>
        <item quantity="other">Cannot decrypt %1$d notes.</item>
    </plurals>

    <!-- Ajouts du portage : SUPPRIMER un dossier a une consequence qu'on ne VOIT pas.
         Renommer ou creer se lit dans le tiroir, sous les yeux de l'utilisateur, et n'a donc
         besoin d'aucun message — c'est le choix de l'application publiee, et il se tient.
         Supprimer, non : le dossier disparait de l'ecran, mais le sort de ses notes — deplacees
         vers la boite de reception, ou detruites avec lui — n'apparait nulle part. Le publie se
         taisait sur les deux. Cf. 04-PIEGES.md §97. -->
    <string name="folder_deleted">Folder deleted.</string>
    <plurals name="folder_deleted_notes_moved">
        <item quantity="one">Folder deleted, %1$d note moved to the Inbox.</item>
        <item quantity="other">Folder deleted, %1$d notes moved to the Inbox.</item>
    </plurals>
    <plurals name="folder_deleted_notes_removed">
        <item quantity="one">Folder deleted, along with %1$d note.</item>
        <item quantity="other">Folder deleted, along with %1$d notes.</item>
    </plurals>
""",
    "note": """\
    <!--
      Ajout du portage : la note publiee copiait une chaine vide, ce qui EFFACE ce que
      l'utilisateur avait dans son presse-papiers. Meme decision que l'archive vide qui ne se
      partage plus.
    -->
    <string name="note_editor_copy_empty">Nothing to copy: this note is empty</string>
    <!--
      Ajout du portage. La feuille de deplacement reutilisait `note_card_locked` pour signaler
      un dossier coffre : elle disait donc « Note verrouillee » a propos d un DOSSIER, sur
      l ecran ou l on choisit ou envoyer une note. L application publiee, elle, ne signale le
      coffre que par une icone — invisible a un lecteur d ecran. Cette chaine dit la bonne
      chose et dit la CONSEQUENCE, qui est la raison d etre du signal.
    -->
    <string name="move_to_folder_vault">Vault folder: the note will be encrypted</string>
    <string name="note_editor_menu_move">Move to another folder</string>
    <string name="move_to_folder_title">Move to another folder</string>
    <!--
      Port addition (2026-09-24): the note info panel. Planned for notes_tech after issue #10
      (dates of creation and modification), never shipped there. Read-only; the counts are taken
      from the text on screen, so a vault note is counted as the user reads it.
    -->
    <string name="note_editor_menu_info">Info</string>
    <string name="note_info_title">Note info</string>
    <string name="note_info_folder">Folder</string>
    <string name="note_info_created">Created</string>
    <string name="note_info_modified">Last modified</string>
    <string name="note_info_words">Words</string>
    <string name="note_info_characters">Characters</string>
    <string name="note_info_characters_hint">Spaces included, line breaks excluded</string>
    <!--
      Port additions (2026-09-25): the Markdown preview (D-024). A task box is read by a screen
      reader as its state — notes_tech 2.0.9 draws the icon with no label, so TalkBack said nothing
      of it. And a web link that no app can open says so, instead of doing nothing when tapped.
    -->
    <string name="note_preview_task_done">Done</string>
    <string name="note_preview_task_open">To do</string>
    <string name="note_preview_link_no_app">No app on this device can open this link.</string>
    <!-- Above a note the preview does not parse: too long, or shaped so that parsing it would
         freeze the screen (MarkdownPreviewReader). Says why the text below is not formatted. -->
    <string name="note_preview_as_written">Too long or too complex to preview: shown as written.</string>
    <!-- A note a [[Title]] link would create, in the preview or the links panel, could not be
         created. notes_tech 2.0.9 says "Vault creation failed" here, of any note, in a vault or not. -->
    <string name="note_editor_link_create_failed">Could not create the linked note: %1$s</string>
    <!-- In the [[ sheet, when the search itself failed (a vault locked meanwhile): nothing is
         offered, creation included, since the search could not see whether the note exists. -->
    <string name="link_autocomplete_failed">Could not search your notes.</string>
""",
    "trash": """\
    <!--
      Ecart assume avec l'application publiee : elle affiche « Note supprimee definitivement » au
      singulier apres avoir vide une corbeille de douze notes (trash_screen.dart:90). Rapporter le
      compte mesure est le seul retour honnete apres un geste irreversible.
    -->
    <plurals name="trash_emptied">
        <item quantity="one">%1$d note permanently deleted</item>
        <item quantity="other">%1$d notes permanently deleted</item>
    </plurals>
""",
    "vault": """\
    <!-- Ajout du portage : la version publiee n'offre pas d'annuler pendant la derivation, donc
         elle n'a pas cette course a annoncer. Ici l'annulation peut arriver APRES l'ecriture du
         materiel du coffre : le dossier EST un coffre, et le taire serait le defaut que
         `onConversionIncomplete` a deja ferme une fois — un cadenas dont personne ne dit qu'il ne
         protege pas encore. -->
    <string name="vault_convert_escaped_cancellation">Cancelled too late: this folder is now a vault. Its notes will be encrypted the first time you open it.</string>
    <!--
      Port additions (2026-09-25): when a PIN vault's key is gone. Removing the screen lock deletes
      it (measured on API 34), and 2.0.9 then says "Vault locked." for ever; if Android invalidates
      it instead, 2.0.9 says "Too many attempts" to someone who typed one correct PIN. Each case
      gets its own sentence, and the mode chooser warns before it happens: appended to
      `vault_mode_pin_desc`, whose terse style it follows.
    -->
    <string name="vault_pin_key_missing">This vault\\'s key cannot be found on this phone. Android deletes it, for example, when the screen lock is removed. No PIN can open this vault without it.</string>
    <string name="vault_pin_wiped_key_invalidated">Android invalidated this vault\\'s key: its notes could no longer be opened and have been wiped. Wrong PINs did not cause this.</string>
    <string name="vault_mode_pin_screen_lock">May no longer open if the phone\\'s screen lock is removed.</string>
    <!-- Security audit of 2026-09-26, K4: before Android 9 the PIN key cannot require an unlocked
         phone, so a seized phone lets its code be searched offline. Shown there only. -->
    <string name="vault_mode_pin_old_android">Needs Android 9 or later: before it, a PIN does not protect a vault on a seized phone.</string>
    <string name="error_vault_pin_needs_android_9">PIN vaults need Android 9 or later: before it, a PIN does not protect a vault on a seized phone. Use a passphrase vault instead.</string>
    <string name="vault_pin_unlock_old_android">On this Android version, this PIN does not protect the vault on a seized phone. To protect it, remove its protection, then make the folder a vault again with a passphrase.</string>
    <string name="error_vault_wipe_pending">An earlier wipe of this folder\\'s vault could not finish: its key is still on this phone. Try again later; restarting the phone may help.</string>
""",
    "panic": """\
    <!--
        Three outcomes, not two. `panic_key_survived*` is the only one that means "you are NOT
        protected": the key survived, so the notes are still decryptable. `panic_incomplete` is the
        far milder case where the key IS gone and only a cleanup step failed — saying "your data may
        have survived" there would frighten someone who is in fact safe.
    -->
    <string name="panic_key_survived_title">The key was NOT destroyed</string>
    <string name="panic_key_survived">Your notes are still decryptable on this device. %1$d step(s) failed. Do not part with the device.</string>
    <string name="panic_incomplete">Key destroyed: your notes can no longer be decrypted. However %1$d cleanup step(s) failed — unreadable files may remain on the device.</string>

    <!-- Ajout du portage, 2026-08-15. La phrase ci-dessus dit « unreadable », ce qui est vrai de
         toutes les etapes SAUF une : une archive d'export est du CLAIR. Quand c'est celle-la qui
         echoue, rassurer serait decrire l'inverse de la situation.

         ⚠️⚠️ REECRITE le 2026-08-19, pour DEUX raisons independantes.

         1. Elle ne nommait que les archives d'export, alors que le predicat qui la declenche
            — `PanicReport.clairPeutSubsister` — couvre TROIS sources : l'archive, l'enregistrement
            de dictee, et le presse-papiers. Dans le cas « seul le presse-papiers a resiste », cette
            phrase envoyait quelqu'un inspecter des fichiers qu'il ne trouverait pas, pour en
            conclure qu'il est tire d'affaire — pendant qu'une note reste lisible par toute
            application au premier plan. Le KDoc du predicat raconte lui-meme que son inventaire
            s'est trompe DEUX fois par omission ; il a ete corrige les deux fois, et la phrase
            affichee n'a jamais suivi. *Un inventaire corrige dans le code et pas dans le texte
            n'est corrige nulle part, puisque c'est le texte qu'on lit.*

         2. Le compteur `%1$d` est retire. Ce residu-la se MESURE a la fin de la sequence, il ne se
            deduit pas des etapes : il vaut vrai avec ZERO etape en echec — c'est exactement l'etat
            que construit `PanicReportTest.clairRestantEstSignale`. L'ecran annoncait donc
            « 0 etape(s) de nettoyage ont echoue » juste avant d'avertir qu'il reste du clair. Une
            phrase qui se contredit elle-meme ne sera pas crue, au moment ou elle doit l'etre. -->
    <string name="panic_incomplete_plaintext">Key destroyed: the database can no longer be decrypted. However READABLE content may remain on this device — an export archive, a dictation recording, or a note copied to the clipboard. Do not part with it before checking.</string>
    <!-- Security audit of 2026-09-26, P4: "All data has been wiped" said nothing of the keyboard\\'s
         own clipboard history, which keeps a copied note and which no app can reach. -->
    <string name="panic_complete_keyboard_history">A note you copied may still be in your keyboard\\'s clipboard history, which the app cannot reach: clear it from the keyboard.</string>
""",
    "voice": """\
    <!-- ⚠️⚠️ REECRITURES, pas des ajouts. Les trois chaines d'origine affirmaient que l'audio
         n'est « jamais persiste ». C'est FAUX : le moteur de transcription lit un FICHIER, donc la
         voix est ecrite sur le disque le temps de la transcription. « Efface des la transcription
         obtenue » est exact et se tient ; « jamais persiste » ne se tient pas, et l'ecran aurait
         contredit la politique de confidentialite, corrigee le meme jour pour la meme raison.
         Cf. `res/raw*/privacy.md` et 04-PIEGES.md. -->
    <string name="voice_setup_subtitle">Whisper, on this device. Audio is wiped as soon as the transcription comes back.</string>
    <!-- ⚠️ Sans signe « % » : Android lit un pourcentage isole comme un format invalide, et
         lint le refuse. L'echapper en « %% » l'afficherait tel quel, la chaine n'ayant aucun argument. -->
    <string name="voice_setup_offline_banner">Fully offline. Audio is wiped as soon as the transcription comes back.</string>
    <string name="voice_setup_security_footer_label">How your data is handled</string>
    <string name="voice_setup_security_footer_body">Audio wiped as soon as the transcription comes back, transcription done locally by whisper.cpp bundled in the app, model SHA-256 verified before every load.</string>

    <!-- Ajouts du portage : le fichier amont doit etre NOMME. La page source publie une trentaine
         de variantes dont les noms ne different que par un suffixe, et se tromper coute un
         telechargement de plusieurs dizaines de mega-octets pour finir sur une empreinte fausse. -->
    <string name="voice_setup_upstream_file">File to download: %1$s</string>
    <string name="voice_setup_model_installed">Installed</string>
    <string name="voice_setup_model_not_installed">Not installed</string>

    <!-- ⚠️ La progression couvre la copie ET la verification : le portage ne fait qu'un seul
         passage sur le fichier, la ou la version publiee en faisait deux. -->
    <string name="voice_setup_copying_progress">Copying and verifying: %1$d%%</string>

    <!--
      🔴 Un message par CAUSE, et jamais `exception.message`.
      C'est la lecon deja payee par `VaultAttempt` : elle transportait le message interne de
      l'exception, non traduit, et l'ecran le montrait tel quel dans les deux langues. Ici chaque
      cas appelle en plus un geste different — choisir un autre fichier, liberer de la place,
      retelecharger, reessayer — qu'un message unique rendrait impossible a proposer.
    -->
    <string name="voice_setup_error_source_invalid">This file does not match the selected model. Did you pick the right .bin?</string>
    <string name="voice_setup_error_storage_full">Not enough room on this device. Free some space, then try again.</string>
    <string name="voice_setup_error_checksum">The file fingerprint does not match. The download may have been interrupted, or the file comes from another source. It has been deleted.</string>
    <string name="voice_setup_error_import_failed">The import failed. Try again.</string>

    <!-- 🔴 La description de chaque modele etait un champ du CATALOGUE, donc du francais en dur
         affiche tel quel dans l'application anglaise. Un texte vu par l'utilisateur se traduit ;
         un catalogue de domaine ne connait pas les ressources Android. La correspondance se fait
         donc dans l'ecran, seul endroit qui connait les deux.

         2026-09-24: `voice_model_base_notes` and `voice_model_tiny_notes` are no longer added
         here. notes_tech 2.0.9 fixed the same defect on its side (`voice_localize.dart`) and
         ships both keys in its ARB, with its own wording. Keeping ours made the generator refuse
         to write (duplicate names) — which is the guard doing its job. The published wording
         wins: these are shared strings, and the ARB is their source. -->

    <!--
      🔴 Le silence n'est PAS un echec. L'ecran affichait « Transcription failed » a
      quelqu'un qui n'avait simplement rien dit : un echec systeme et un geste de l'utilisateur
      ne se classent pas ensemble — c'est deja la regle du `null` de `VoiceCapture`. Ne rien
      afficher serait pire : apres un appui sur « Arreter », l'absence de retour se lit comme
      une panne.
    -->
    <string name="voice_nothing_heard">Nothing was heard, no text inserted.</string>

    <!-- Un refus SIMPLE du micro n'affichait rien du tout : le bouton revenait, sans un mot.
         Seul le refus definitif etait traite. -->
    <string name="voice_permission_needed">Microphone permission is needed to dictate. Tap the microphone again to allow it.</string>

    <string name="voice_system_settings_unavailable">This device does not offer that settings screen. Open Android settings, find Notes Tech, and allow the microphone.</string>

    <string name="voice_setup_remove_confirm_title">Remove the model?</string>
    <string name="voice_setup_remove_confirm_body">You will have to download and import it again to dictate. Your notes are not affected.</string>
    <!-- Ajout du portage : la capture est bornee a 2 min (VoiceCapture.DUREE_MAX_SECONDES), la ou
         l'application publiee n'a AUCUNE borne. Elle s'appliquait en SILENCE : rien ne distinguait
         « la limite est atteinte » de « l'utilisateur a appuye sur Arreter », si bien qu'on dictait
         trois minutes et qu'il en manquait une. Cf. 04-PIEGES.md §96. L'argument porte la duree
         formatee (« 2:00 »), exactement celle que le compteur affichait pendant la dictee. -->
    <string name="voice_limit_reached">Text inserted. Limit of %1$s reached: the rest was not recorded.</string>
""",
    "settings": """\
    <!-- Port additions (2026-09-25): German, Spanish and Italian. Each language is named in itself,
         as Français and English are, and each announcement is spoken in the language chosen: it is
         said as the screen switches to it. The same six values in every language. -->
    <string name="settings_language_de">Deutsch</string>
    <string name="settings_language_es">Español</string>
    <string name="settings_language_it">Italiano</string>
    <string name="settings_language_changed_de">Sprache auf Deutsch umgestellt</string>
    <string name="settings_language_changed_es">Idioma cambiado a español</string>
    <string name="settings_language_changed_it">Lingua cambiata in italiano</string>
""",
    "applock": """\
    <!--
      Port addition (2026-09-24): the app lock, decision D-023. notes_tech never had one - the
      Flutter app has no `local_auth`. Two lessons of the portfolio are in the words: the biometric
      labels never promise the face (Class 3 only, which most Samsung face unlock is not), and
      forgetting the PIN is stated for what it costs.
    -->
    <string name="app_lock_prompt">Enter your PIN</string>
    <string name="app_lock_unlock">Unlock</string>
    <string name="app_lock_wrong_pin">Incorrect PIN.</string>
    <string name="app_lock_use_biometric">Unlock with biometrics</string>
    <string name="app_lock_biometric_prompt_title">Unlock Notes Tech</string>
    <string name="app_lock_biometric_prompt_use_pin">Use PIN</string>
    <string name="app_lock_biometric_failed">Biometric unlock did not work. Use your PIN.</string>
    <string name="app_lock_biometric_invalidated">The fingerprints or faces of this device have changed, so biometric unlock was turned off. Unlock with your PIN, then turn it back on in Settings.</string>
    <string name="app_lock_keystore_unavailable">The PIN could not be checked right now. Try again in a moment.</string>
    <string name="app_lock_forgot_pin">Forgot your PIN?</string>
    <string name="app_lock_forgot_body">The PIN cannot be recovered or reset: it never leaves this device. The only way to use Notes Tech again is to erase all your notes and settings with panic mode.</string>
    <string name="app_lock_forgot_erase">Erase everything…</string>
    <string name="app_lock_unverifiable">The PIN can no longer be checked on this device: the key that verifies it is missing or damaged. Your notes have not been modified, but the only way back into Notes Tech is to erase everything with panic mode.</string>
    <string name="app_lock_unverifiable_biometric">The PIN can no longer be checked on this device: the key that verifies it is missing or damaged. Your notes have not been modified. Biometric unlock still works; without it, the only way back into Notes Tech is to erase everything with panic mode.</string>
    <!-- A count that cannot be written means the attempt is not made: counted after the check, it
         was a free guess after every force-stop on a full disk (external review, 2026-09-24). -->
    <string name="app_lock_not_recorded">This attempt could not be recorded on the device, so the PIN was not checked. Free up some storage space, then try again.</string>

    <string name="app_lock_settings_title">App lock</string>
    <string name="app_lock_settings_toggle">Lock the app with a PIN</string>
    <string name="app_lock_settings_toggle_subtitle">Asked when you open Notes Tech</string>
    <string name="app_lock_settings_change_pin">Change PIN</string>
    <string name="app_lock_settings_biometric">Unlock with biometrics</string>
    <string name="app_lock_settings_biometric_subtitle">Fingerprint, or face where the device rates its face unlock as secure</string>
    <string name="app_lock_settings_biometric_not_enrolled">Add a fingerprint in the device settings first.</string>
    <string name="app_lock_settings_biometric_unavailable">This device has no biometric sensor secure enough.</string>
    <string name="app_lock_settings_delay">Lock after leaving the app</string>
    <string name="app_lock_delay_immediately">Immediately</string>
    <plurals name="app_lock_delay_seconds">
        <item quantity="one">After %1$d second</item>
        <item quantity="other">After %1$d seconds</item>
    </plurals>
    <plurals name="app_lock_delay_minutes">
        <item quantity="one">After %1$d minute</item>
        <item quantity="other">After %1$d minutes</item>
    </plurals>
    <!-- Below Android 13, FLAG_SECURE is the only way to keep the notes out of the recent apps
         screen, so the lock forces it: the switch must not claim that screenshots are allowed. -->
    <string name="app_lock_secure_window_forced">Always on while the app lock is on: on this version of Android, it is what hides your notes from the recent apps screen.</string>

    <string name="app_lock_pin_current_title">Enter your current PIN</string>
    <string name="app_lock_pin_new_title">Choose a PIN</string>
    <string name="app_lock_pin_confirm_title">Confirm the PIN</string>
    <string name="app_lock_pin_new_warning">If you forget this PIN, the only way back into Notes Tech will be to erase all your notes.</string>
    <!-- Security audit of 2026-09-26, K5: on a seized phone the app PIN can be searched offline; it
         opens no vault, but a PIN reused as the screen lock's or a card's would fall with it. -->
    <string name="app_lock_pin_new_not_reused">Choose a PIN you use nowhere else — not your phone\\'s, not a card\\'s.</string>

    <string name="app_lock_enabled">App lock on.</string>
    <string name="app_lock_disabled">App lock off.</string>
    <string name="app_lock_pin_changed">PIN changed.</string>
    <string name="app_lock_biometric_enabled">Biometric unlock on.</string>
    <string name="app_lock_not_saved">The change could not be saved. Try again.</string>
    <string name="app_lock_proof_expired">Too much time has passed. Enter your PIN again.</string>
    <string name="app_lock_biometric_setup_failed">Biometric unlock could not be set up on this device.</string>
""",
    "app": """\
    <!-- Ajout du portage : l'application publiee n'offre pas de reveler le code. -->
    <string name="pin_show_tooltip">Show PIN</string>
    <string name="pin_hide_tooltip">Hide PIN</string>

    <!-- ── Écran d'échec au démarrage ─────────────────────────────────────────
         Ces chaînes n'ont PAS d'équivalent dans la version Flutter : l'écran qu'elles servent est
         une addition du portage, pour l'utilisateur dont la base ne s'ouvre pas. Un message vague
         à ce moment-là transforme une situation récupérable en abandon. -->
    <string name="startup_failure_title">Your notes could not be unlocked</string>
    <string name="startup_failure_notes_are_safe">Your notes are still on this device and have not been modified.</string>
    <!-- 2026-09-24: the advice "install Notes Tech 2.0.4 first, then update again" had become
         impossible. Since 2.0.5 every published version is above 2.0.4, and so is this one: going
         back is a downgrade Android refuses, and uninstalling to get there deletes the notes. What
         stays true, and matters most, is what NOT to do. -->
    <string name="startup_failure_missing_key">The encryption key for this database was not found. Do not uninstall the app or clear its data: that would delete your notes for good. Write to contact@files-tech.com.</string>
    <string name="startup_failure_key_unavailable">The device keystore is temporarily unavailable. Restart the app; if the problem persists, restart the device.</string>
    <string name="startup_failure_unknown">The key was found but could not be used. Try again; if the problem persists, report it — your notes are not modified.</string>
    <string name="startup_failure_retry">Try again</string>
""",
}

AJOUTS_FR = {
    "common": """\
    <!-- Ajout du portage : Compose pose une fleche de retour la ou Flutter s'appuie sur le
         retour implicite de l'AppBar. « Fermer » decrivait mal ce geste. -->
    <string name="common_back">Retour</string>
    <string name="common_more_options">Plus d\\'options</string>
    <plurals name="common_retry_in_seconds">
        <item quantity="one">Trop d\\'essais. Réessayez dans %1$d seconde.</item>
        <item quantity="many">Trop d\\'essais. Réessayez dans %1$d de secondes.</item>
        <item quantity="other">Trop d\\'essais. Réessayez dans %1$d secondes.</item>
    </plurals>
    <plurals name="common_retry_in_minutes">
        <item quantity="one">Trop d\\'essais. Réessayez dans %1$d minute.</item>
        <item quantity="many">Trop d\\'essais. Réessayez dans %1$d de minutes.</item>
        <item quantity="other">Trop d\\'essais. Réessayez dans %1$d minutes.</item>
    </plurals>
""",
    "folder": """\
    <!-- ⚠️⚠️ REECRITURE, pas un ajout : voir REMPLACEES. -->
    <plurals name="folder_delete_decrypt_failed">
        <item quantity="one">Déchiffrement impossible pour %1$d note.</item>
        <item quantity="other">Déchiffrement impossible pour %1$d notes.</item>
        <item quantity="many">Déchiffrement impossible pour %1$d de notes.</item>
    </plurals>

    <!-- Voir le commentaire cote EN. -->
    <string name="folder_deleted">Dossier supprimé.</string>
    <plurals name="folder_deleted_notes_moved">
        <item quantity="one">Dossier supprimé, %1$d note déplacée vers la Boîte de réception.</item>
        <item quantity="other">Dossier supprimé, %1$d notes déplacées vers la Boîte de réception.</item>
        <item quantity="many">Dossier supprimé, %1$d de notes déplacées vers la Boîte de réception.</item>
    </plurals>
    <plurals name="folder_deleted_notes_removed">
        <item quantity="one">Dossier supprimé, ainsi que %1$d note.</item>
        <item quantity="other">Dossier supprimé, ainsi que %1$d notes.</item>
        <item quantity="many">Dossier supprimé, ainsi que %1$d de notes.</item>
    </plurals>
""",
    "note": """\
    <string name="note_editor_copy_empty">Rien à copier : cette note est vide</string>
    <string name="move_to_folder_vault">Dossier coffre : la note sera chiffrée</string>
    <string name="note_editor_menu_move">Déplacer vers un autre dossier</string>
    <string name="move_to_folder_title">Déplacer vers un autre dossier</string>
    <string name="note_editor_menu_info">Infos</string>
    <string name="note_info_title">Infos de la note</string>
    <string name="note_info_folder">Dossier</string>
    <string name="note_info_created">Créée le</string>
    <string name="note_info_modified">Modifiée le</string>
    <string name="note_info_words">Mots</string>
    <string name="note_info_characters">Caractères</string>
    <string name="note_info_characters_hint">Espaces compris, retours à la ligne exclus</string>
    <string name="note_preview_task_done">Fait</string>
    <string name="note_preview_task_open">À faire</string>
    <string name="note_preview_link_no_app">Aucune application de cet appareil ne peut ouvrir ce lien.</string>
    <string name="note_preview_as_written">Trop long ou trop complexe pour l\\'aperçu : affiché tel quel.</string>
    <string name="note_editor_link_create_failed">Impossible de créer la note liée : %1$s</string>
    <string name="link_autocomplete_failed">Impossible de chercher parmi vos notes.</string>
""",
    "trash": """\
    <plurals name="trash_emptied">
        <item quantity="one">%1$d note supprimée définitivement</item>
        <item quantity="other">%1$d notes supprimées définitivement</item>
        <item quantity="many">%1$d de notes supprimées définitivement</item>
    </plurals>
""",
    "vault": """\
    <string name="vault_convert_escaped_cancellation">Annulation trop tardive : ce dossier est devenu un coffre. Ses notes seront chiffrées à sa première ouverture.</string>
    <string name="vault_pin_key_missing">La clé de ce coffre est introuvable sur ce téléphone. Android la supprime, par exemple, quand le verrouillage d\\'écran est retiré. Aucun PIN ne peut ouvrir ce coffre sans elle.</string>
    <string name="vault_pin_wiped_key_invalidated">Android a invalidé la clé de ce coffre : ses notes ne pouvaient plus être ouvertes et ont été effacées. Ce n\\'est pas dû à des erreurs de PIN.</string>
    <string name="vault_mode_pin_screen_lock">Peut ne plus s\\'ouvrir si le verrouillage d\\'écran du téléphone est retiré.</string>
    <string name="vault_mode_pin_old_android">Demande Android 9 ou plus récent : avant, un PIN ne protège pas un coffre sur un téléphone saisi.</string>
    <string name="error_vault_pin_needs_android_9">Les coffres PIN exigent Android 9 ou plus récent : avant, un PIN ne protège pas un coffre sur un téléphone saisi. Utilisez plutôt un coffre à passphrase.</string>
    <string name="vault_pin_unlock_old_android">Sur cette version d\\'Android, ce PIN ne protège pas le coffre sur un téléphone saisi. Pour le protéger, retirez sa protection, puis refaites du dossier un coffre à passphrase.</string>
    <string name="error_vault_wipe_pending">L\\'effacement précédent du coffre de ce dossier n\\'a pas pu s\\'achever : sa clé est encore sur ce téléphone. Réessayez plus tard ; redémarrer le téléphone peut aider.</string>
""",
    "panic": """\
    <!--
        Trois issues, pas deux. `panic_key_survived*` est la seule qui veuille dire « vous n'êtes PAS
        protégé » : la clé a survécu, donc les notes restent déchiffrables. `panic_incomplete` est le
        cas bien plus doux où la clé est détruite et où seul un nettoyage a échoué — y écrire « vos
        données peuvent avoir survécu » effraierait quelqu'un qui est en réalité à l'abri.
    -->
    <string name="panic_key_survived_title">La clé n\\'a PAS été détruite</string>
    <string name="panic_key_survived">Vos notes restent déchiffrables sur cet appareil. %1$d étape(s) ont échoué. Ne vous séparez pas de l\\'appareil.</string>
    <string name="panic_incomplete">Clé détruite : vos notes ne sont plus déchiffrables. En revanche, %1$d étape(s) de nettoyage ont échoué — des fichiers illisibles peuvent subsister sur l\\'appareil.</string>
    <!-- ⚠️⚠️ REECRITE le 2026-08-19 — voir la version anglaise pour les deux raisons : elle ne
         nommait qu'une source de clair sur trois, et son compteur d'etapes pouvait afficher zero
         dans la phrase meme qui avertit. -->
    <string name="panic_incomplete_plaintext">Clé détruite : la base n\\'est plus déchiffrable. En revanche, du contenu LISIBLE peut subsister sur cet appareil — archive d\\'export, enregistrement de dictée, ou note copiée dans le presse-papiers. Ne vous en séparez pas sans vérifier.</string>
    <string name="panic_complete_keyboard_history">Une note copiée peut rester dans l\\'historique du presse-papiers de votre clavier, hors de portée de l\\'application : videz-le depuis le clavier.</string>
""",
    "voice": """\
    <!-- ⚠️⚠️ REECRITURES, pas des ajouts. Voir le commentaire de la version anglaise : « jamais
         persiste » etait faux, et l'ecran aurait contredit la politique de confidentialite. -->
    <string name="voice_setup_subtitle">Whisper, sur cet appareil. L\\'audio est effacé dès la transcription obtenue.</string>
    <string name="voice_setup_offline_banner">Entièrement hors-ligne. L\\'audio est effacé dès la transcription obtenue.</string>
    <string name="voice_setup_security_footer_label">Traitement de vos données</string>
    <string name="voice_setup_security_footer_body">Audio effacé dès la transcription obtenue, transcription locale par whisper.cpp inclus dans l\\'application, empreinte SHA-256 du modèle vérifiée avant chaque chargement.</string>

    <!-- Ajouts du portage : le fichier amont doit être NOMMÉ. -->
    <string name="voice_setup_upstream_file">Fichier à télécharger : %1$s</string>
    <string name="voice_setup_model_installed">Installé</string>
    <string name="voice_setup_model_not_installed">Non installé</string>

    <string name="voice_setup_copying_progress">Copie et vérification : %1$d %%</string>

    <!-- 🔴 Un message par CAUSE, et jamais `exception.message`. -->
    <string name="voice_setup_error_source_invalid">Ce fichier ne correspond pas au modèle choisi. Avez-vous sélectionné le bon .bin ?</string>
    <string name="voice_setup_error_storage_full">Il n\\'y a pas assez de place sur cet appareil. Libérez de l\\'espace, puis réessayez.</string>
    <string name="voice_setup_error_checksum">L\\'empreinte du fichier ne correspond pas. Le téléchargement a peut-être été interrompu, ou le fichier vient d\\'une autre source. Il a été supprimé.</string>
    <string name="voice_setup_error_import_failed">L\\'import a échoué. Réessayez.</string>


    <string name="voice_nothing_heard">Rien n\\'a été entendu, aucun texte inséré.</string>

    <string name="voice_permission_needed">La permission du micro est nécessaire pour dicter. Appuyez à nouveau sur le micro pour l\\'autoriser.</string>

    <string name="voice_system_settings_unavailable">Cet appareil ne propose pas cet écran de réglages. Ouvrez les réglages Android, cherchez Notes Tech, et autorisez le micro.</string>

    <string name="voice_setup_remove_confirm_title">Retirer le modèle ?</string>
    <string name="voice_setup_remove_confirm_body">Vous devrez le retélécharger et le réimporter pour dicter. Vos notes ne sont pas concernées.</string>
    <!-- Voir le commentaire cote EN : la borne de 2 min s'appliquait en silence (04-PIEGES.md §96). -->
    <string name="voice_limit_reached">Texte inséré. Limite de %1$s atteinte : la suite n\\'a pas été enregistrée.</string>
""",
    "settings": """\
    <!-- Port additions (2026-09-25): German, Spanish and Italian. Each language is named in itself,
         as Français and English are, and each announcement is spoken in the language chosen: it is
         said as the screen switches to it. The same six values in every language. -->
    <string name="settings_language_de">Deutsch</string>
    <string name="settings_language_es">Español</string>
    <string name="settings_language_it">Italiano</string>
    <string name="settings_language_changed_de">Sprache auf Deutsch umgestellt</string>
    <string name="settings_language_changed_es">Idioma cambiado a español</string>
    <string name="settings_language_changed_it">Lingua cambiata in italiano</string>
""",
    "applock": """\
    <!-- Voir le commentaire cote EN (D-023). -->
    <string name="app_lock_prompt">Saisissez votre PIN</string>
    <string name="app_lock_unlock">Déverrouiller</string>
    <string name="app_lock_wrong_pin">PIN incorrect.</string>
    <string name="app_lock_use_biometric">Déverrouiller par biométrie</string>
    <string name="app_lock_biometric_prompt_title">Déverrouiller Notes Tech</string>
    <string name="app_lock_biometric_prompt_use_pin">Utiliser le PIN</string>
    <string name="app_lock_biometric_failed">Le déverrouillage biométrique n\\'a pas abouti. Utilisez votre PIN.</string>
    <string name="app_lock_biometric_invalidated">Les empreintes ou visages de cet appareil ont changé : le déverrouillage biométrique a été désactivé. Déverrouillez avec votre PIN, puis réactivez-le dans les Réglages.</string>
    <string name="app_lock_keystore_unavailable">Le PIN n\\'a pas pu être vérifié pour l\\'instant. Réessayez dans un moment.</string>
    <string name="app_lock_forgot_pin">PIN oublié ?</string>
    <string name="app_lock_forgot_body">Le PIN ne peut être ni retrouvé ni réinitialisé : il ne quitte jamais cet appareil. La seule façon de réutiliser Notes Tech est d\\'effacer toutes vos notes et vos réglages avec le mode panique.</string>
    <string name="app_lock_forgot_erase">Tout effacer…</string>
    <string name="app_lock_unverifiable">Le PIN ne peut plus être vérifié sur cet appareil : la clé qui le contrôle est absente ou endommagée. Vos notes n\\'ont pas été modifiées, mais la seule façon de revenir dans Notes Tech est de tout effacer avec le mode panique.</string>
    <string name="app_lock_unverifiable_biometric">Le PIN ne peut plus être vérifié sur cet appareil : la clé qui le contrôle est absente ou endommagée. Vos notes n\\'ont pas été modifiées. Le déverrouillage biométrique fonctionne encore ; sans lui, la seule façon de revenir dans Notes Tech est de tout effacer avec le mode panique.</string>
    <string name="app_lock_not_recorded">Cet essai n\\'a pas pu être enregistré sur l\\'appareil : le PIN n\\'a donc pas été vérifié. Libérez de l\\'espace de stockage, puis réessayez.</string>

    <string name="app_lock_settings_title">Verrouillage de l\\'application</string>
    <string name="app_lock_settings_toggle">Verrouiller l\\'application par PIN</string>
    <string name="app_lock_settings_toggle_subtitle">Demandé à l\\'ouverture de Notes Tech</string>
    <string name="app_lock_settings_change_pin">Changer le PIN</string>
    <string name="app_lock_settings_biometric">Déverrouiller par biométrie</string>
    <string name="app_lock_settings_biometric_subtitle">Empreinte, ou visage si l\\'appareil juge son déverrouillage facial sûr</string>
    <string name="app_lock_settings_biometric_not_enrolled">Ajoutez d\\'abord une empreinte dans les réglages de l\\'appareil.</string>
    <string name="app_lock_settings_biometric_unavailable">Cet appareil n\\'a pas de capteur biométrique assez sûr.</string>
    <string name="app_lock_settings_delay">Verrouiller après avoir quitté l\\'application</string>
    <string name="app_lock_delay_immediately">Immédiatement</string>
    <plurals name="app_lock_delay_seconds">
        <item quantity="one">Après %1$d seconde</item>
        <item quantity="many">Après %1$d de secondes</item>
        <item quantity="other">Après %1$d secondes</item>
    </plurals>
    <plurals name="app_lock_delay_minutes">
        <item quantity="one">Après %1$d minute</item>
        <item quantity="many">Après %1$d de minutes</item>
        <item quantity="other">Après %1$d minutes</item>
    </plurals>
    <string name="app_lock_secure_window_forced">Toujours actif tant que le verrouillage l\\'est : sur cette version d\\'Android, c\\'est ce qui masque vos notes dans l\\'écran des applications récentes.</string>

    <string name="app_lock_pin_current_title">Saisissez votre PIN actuel</string>
    <string name="app_lock_pin_new_title">Choisissez un PIN</string>
    <string name="app_lock_pin_confirm_title">Confirmez le PIN</string>
    <string name="app_lock_pin_new_warning">Si vous oubliez ce PIN, la seule façon de revenir dans Notes Tech sera d\\'effacer toutes vos notes.</string>
    <string name="app_lock_pin_new_not_reused">Choisissez un PIN que vous n\\'utilisez nulle part ailleurs — ni celui du téléphone, ni celui d\\'une carte.</string>

    <string name="app_lock_enabled">Verrouillage activé.</string>
    <string name="app_lock_disabled">Verrouillage désactivé.</string>
    <string name="app_lock_pin_changed">PIN modifié.</string>
    <string name="app_lock_biometric_enabled">Déverrouillage biométrique activé.</string>
    <string name="app_lock_not_saved">La modification n\\'a pas pu être enregistrée. Réessayez.</string>
    <string name="app_lock_proof_expired">Trop de temps s\\'est écoulé. Saisissez de nouveau votre PIN.</string>
    <string name="app_lock_biometric_setup_failed">Le déverrouillage biométrique n\\'a pas pu être configuré sur cet appareil.</string>
""",
    "app": """\
    <!-- Ajout du portage : l'application publiee n'offre pas de reveler le code. -->
    <string name="pin_show_tooltip">Afficher le code</string>
    <string name="pin_hide_tooltip">Masquer le code</string>

    <!-- ── Écran d'échec au démarrage ───────────────────────────────────────── -->
    <string name="startup_failure_title">Vos notes n\\'ont pas pu être déverrouillées</string>
    <string name="startup_failure_notes_are_safe">Vos notes sont toujours sur cet appareil et n\\'ont pas été modifiées.</string>
    <string name="startup_failure_missing_key">La clé de chiffrement de cette base est introuvable. Ne désinstallez pas l\\'application et n\\'effacez pas ses données : vos notes seraient perdues pour de bon. Écrivez à contact@files-tech.com.</string>
    <string name="startup_failure_unknown">La clé existe mais reste inutilisable. Réessayez ; si le problème persiste, signalez-le — vos notes ne sont pas modifiées.</string>
    <string name="startup_failure_key_unavailable">Le coffre-fort de clés de l\\'appareil est momentanément indisponible. Relancez l\\'application ; si le problème persiste, redémarrez l\\'appareil.</string>
    <string name="startup_failure_retry">Réessayer</string>
""",
}

# ── La categorie CLDR `many` du francais, ecrite A LA MAIN ──────────────────────────────────────
#
# `lintDebug` la reclamait sur les quatre pluriels francais (MissingQuantity). Elle ne vaut que
# pour les multiples EXACTS d'un million, et Android retombe sur `other` quand elle manque : le
# comportement etait donc correct. Mais s'appuyer sur ce repli, c'est declarer une ressource
# incomplete et compter sur une regle de secours — et un avertissement que tout le monde apprend a
# ignorer finit par en cacher un vrai.
#
# ⚠️⚠️ **Ecrite a la main, cle par cle, et NON derivee de `other`.** La forme francaise insere
# « de » apres le nombre (« 1 000 000 de notes »), ce qui suppose que le nombre soit suivi d'un nom.
# La regle mecanique serait juste sur ces trois chaines et fausse des la premiere qui dirait
# « %1$d sur 5 ». Un `assert` plus bas exige une entree par pluriel : un pluriel neuf fait donc
# echouer ce script tant que personne n'a ecrit sa forme.
#
# ⚠️ **Apostrophes BRUTES ici**, contrairement aux AJOUTS_* : ces valeurs passent par `echappe`,
# les AJOUTS_* sont du XML deja echappe. Deux conventions, deux endroits — ne pas les melanger.
MANY_FR = {
    "homeVaultLostBanner":
        "%1$d de notes de coffre ont perdu leurs dernières modifications "
        "(coffre verrouillé pendant l'enregistrement).",
    "folderRemoveVaultDone": "%1$d de notes déchiffrées. Le dossier n'est plus un coffre.",
    "settingsVaultAutoLockMinutes": "%1$d de minutes",
}

# Cles de l'ARB dont le portage REECRIT la valeur. Elles sont sautees a la transposition et leur
# version reecrite vit dans AJOUTS_*. Sans cette liste, le controle de doublons leverait — ce qui
# est le bon comportement : un doublon silencieux serait pire.
#
# `panicIncomplete` : la version Flutter dit « des donnees peuvent avoir survecu » des qu'une etape
# echoue, y compris quand la CLE est detruite — c'est-a-dire quand l'utilisateur est en realite a
# l'abri. Le portage separe les deux issues, cf. le commentaire de la section panique.
# `voiceSetupSubtitle`, `voiceSetupOfflineBanner`, `voiceSetupSecurityFooterBody` : les trois
# affirment que l'audio n'est « jamais persiste ». C'est faux — le moteur de transcription lit un
# FICHIER — et c'est exactement la formulation corrigee le meme jour dans `privacy.md`. Les laisser
# ici aurait fait dire a l'ecran l'inverse de la politique de confidentialite de l'application.
# `folderDeleteDecryptFailed` : la version Flutter ecrit « pour {n} note(s). ». Le « (s) » est le
# contournement qu'on emploie quand on n'a pas de pluriel — `lintDebug` le signale d'ailleurs
# (PluralsCandidate) — et Android en a un. Reecrite en `<plurals>`, elle dit « 1 note » ou
# « 3 notes » au lieu de « 1 note(s) ».
REMPLACEES = {
    "panicIncomplete",
    "folderDeleteDecryptFailed",
    "voiceSetupSubtitle",
    "voiceSetupOfflineBanner",
    "voiceSetupSecurityFooterBody",
    # `voiceSetupSecurityFooterLabel` disait « Promesse ». Le mot annonce un engagement la ou la
    # phrase qu'il coiffe enonce un fonctionnement — et il surjoue un texte qui se suffit. Remplace
    # par un intitule qui DECRIT, dans le vocabulaire que la politique de confidentialite emploie
    # deja (« Donnees traitees »).
    "voiceSetupSecurityFooterLabel",
    # `noteEditorMenuMove` et `moveToFolderTitle` disaient « Déplacer dans un dossier ». Depuis une
    # note qui est DEJA dans un dossier -- un coffre, typiquement -- le libelle semble parler
    # d'autre chose que de la situation. Releve par Patrice en essayant l'application le
    # 2026-08-20 : « ce serait pas mieux : deplacer HORS du dossier ? »
    #
    # ⚠️ « Hors du dossier » aurait ete plus etroit que l'action. La feuille propose TOUTES les
    # destinations sauf le dossier courant, y compris un AUTRE coffre -- ou la note reste
    # chiffree. Un libelle de sortie aurait masque ce cas.
    #
    # « Vers un AUTRE dossier » dit exactement ce que le code fait : `FeuilleDeDeplacement` retire
    # le dossier courant de la liste. Et le geste dangereux -- sortir une note du chiffrement --
    # est deja garde par une confirmation dediee, pas par le libelle du menu.
    "noteEditorMenuMove",
    "moveToFolderTitle",
}

# ⚠️ ARB keys the port deliberately does NOT transpose (2026-09-24), each with its reason.
#
# notes_tech 2.0.9 added them for behaviours the port already had, differently and on purpose. A
# string translated in both languages and read nowhere is a signal in this repository — it pointed
# at the right label twice (04-PIEGES §79, §80) — so an unused key is not left to rot in
# `strings.xml`: it is either wired, or listed here with why it is not.
ECARTEES = {
    # Sharing ONE note: the port's subject is the file name, which says which note it is, and it
    # adds no body text. 2.0.9's generic subject + "Note exported from Notes Tech" would put a
    # sentence the user did not write into their message.
    "noteExportShareSubject": "subject = file name (NoteEditorScreen / Partage.kt)",
    "noteExportShareText": "no EXTRA_TEXT: nothing the user did not write goes into the message",
    # The model size is part of `voiceModel*Name` ("…, 57 MB)"); no screen shows a size on its own.
    "commonSizeMb": "the size is in the model names",
    # A simple microphone refusal says what to do next with the port's own sentence
    # (`voice_permission_needed`: "Tap the microphone again to allow it").
    "voicePermissionDeniedBody": "voice_permission_needed says the next gesture",
    # The port reports four import causes, each with its own gesture (VoiceSetupScreen.kt, §117);
    # 2.0.9's two generic sentences would merge them back.
    "voiceSetupImportFailedReason": "four typed causes instead",
    "voiceSetupChecksumMismatch": "voice_setup_error_checksum says the same and what happened to the file",
}

# Regroupement par prefixe de cle. L'ordre est celui de la navigation, pas l'alphabetique : un
# fichier de 311 chaines se relit par ecran.
SECTIONS = [
    ("common", "Commun — boutons et libellés partagés"),
    ("splash", "Écran de démarrage"),
    ("home", "Accueil — liste des notes"),
    ("drawer", "Tiroir des dossiers"),
    ("folder", "Dossiers — création, renommage, suppression, protection"),
    ("note", "Éditeur de note"),
    ("link", "Liens [[titre]] — autocomplétion"),
    ("search", "Recherche"),
    ("trash", "Corbeille"),
    ("vault", "Coffres — passphrase et code PIN"),
    ("settings", "Réglages"),
    ("applock", "Verrouillage de l'application"),
    ("export", "Export Markdown / ZIP"),
    ("panic", "Mode panique"),
    ("voice", "Dictée vocale"),
    ("about", "À propos"),
    ("legal", "Mentions légales"),
    ("a11y", "Accessibilité — libellés lus par les lecteurs d'écran"),
    ("app", "Divers"),
]

# Prefixes dont l'ecran n'existe pas encore. Les chaines sont transposees quand meme — un second
# passage du script sur un fichier deja edite a la main est exactement la maniere de perdre une
# correction — mais elles sont marquees, pour que personne ne lise « la fonctionnalite existe ».
#
# ⚠️ `panic` et `export` y figuraient encore le 2026-08-15, alors que leurs deux ecrans sont
# cablees depuis la cloture de la phase 6. Le fichier genere portait donc, en tete de ces deux
# sections, un avertissement affirmant le contraire de ce que le code fait. Une marque « pas encore
# cable » se retire quand ca l'est — sinon elle apprend a ne plus lire les marques.
# ⚠️ Vide depuis le 2026-08-16 : la dictée a ses écrans. Le dictionnaire RESTE, parce que sa
# fonction n'est pas de porter « voice » — c'est de marquer, dans le XML même, toute section dont
# aucun écran ne consomme les chaînes. Une section muette se lit sinon comme une fonctionnalité
# existante, et c'est exactement ce que l'audit i18n a déjà pris pour une régression.
PAS_ENCORE_CABLE = {}


def snake(cle):
    """`noteEditorBacklinkDangling` → `note_editor_backlink_dangling`."""
    s = re.sub(r"(?<=[a-z0-9])(?=[A-Z])", "_", cle)
    s = re.sub(r"(?<=[A-Za-z])(?=[0-9])", "_", s)
    return s.lower()


def bloc_ferme(texte, debut):
    """Index de l'accolade fermante appariee a `texte[debut] == '{'`."""
    assert texte[debut] == "{"
    profondeur = 0
    for i in range(debut, len(texte)):
        if texte[i] == "{":
            profondeur += 1
        elif texte[i] == "}":
            profondeur -= 1
            if profondeur == 0:
                return i
    raise ValueError("accolade non fermee dans %r" % texte)


PLURIEL = re.compile(r"\{\s*(\w+)\s*,\s*plural\s*,")


def decoupe_pluriel(valeur):
    """
    Rend `(prefixe, variable, {categorie: texte}, suffixe)` ou None si la chaine n'est pas un
    pluriel ICU.

    Volontairement limite a ce que l'ARB de Notes Tech contient reellement (`=1` et `other`, un
    seul bloc pluriel par chaine) et **verifie** cette limite au lieu de la supposer : une chaine
    qui sortirait du cadre leve, elle ne se transpose pas de travers en silence.
    """
    m = PLURIEL.search(valeur)
    if not m:
        return None
    debut = m.start()
    fin = bloc_ferme(valeur, debut)
    prefixe = valeur[:debut]
    suffixe = valeur[fin + 1:]
    if PLURIEL.search(suffixe):
        raise ValueError("deux blocs pluriels dans %r" % valeur)
    corps = valeur[m.end():fin]

    branches = {}
    i = 0
    while i < len(corps):
        if corps[i].isspace():
            i += 1
            continue
        j = i
        while j < len(corps) and not corps[j].isspace() and corps[j] != "{":
            j += 1
        categorie = corps[i:j].strip()
        while j < len(corps) and corps[j].isspace():
            j += 1
        if j >= len(corps) or corps[j] != "{":
            raise ValueError("branche %r sans corps dans %r" % (categorie, valeur))
        k = bloc_ferme(corps, j)
        branches[categorie] = corps[j + 1:k]
        i = k + 1

    inconnues = set(branches) - {"=1", "one", "other"}
    if inconnues:
        raise ValueError("categories non gerees %r dans %r" % (inconnues, valeur))
    if "other" not in branches:
        raise ValueError("pluriel sans branche `other` : %r" % valeur)
    return prefixe, m.group(1), branches, suffixe


def echappe(texte, a_des_arguments):
    """
    Echappement Android. **L'ordre compte** : le XML d'abord, l'apostrophe ensuite.

    ⚠️ L'apostrophe non echappee est le piege maison : aapt tronque la chaine au caractere fautif,
    sans erreur ni avertissement. La ressource compile et le texte affiche est ampute — un defaut
    qui ne se voit qu'a l'ecran, en francais, donc jamais dans un test anglais.
    """
    t = texte.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    t = t.replace("\\", "\\\\") if "\\" in t else t
    if a_des_arguments:
        # Un `%` litteral dans une chaine formatee serait lu comme un debut de conversion.
        t = re.sub(r"%(?![0-9]+\$[sd])", "%%", t)
    t = t.replace("'", "\\'").replace('"', '\\"')
    t = t.replace("\n", "\\n")
    if t[:1] in ("@", "?"):
        t = "\\" + t
    return t


def transpose(valeur, index_par_nom, type_par_nom):
    """Remplace les `{nom}` ARB par les `%N$s` / `%N$d` d'Android."""
    def sub(m):
        nom = m.group(1)
        if nom not in index_par_nom:
            raise ValueError("placeholder `%s` non declare" % nom)
        conv = "d" if type_par_nom[nom] == "int" else "s"
        return "%%%d$%s" % (index_par_nom[nom], conv)
    return re.sub(r"\{(\w+)\}", sub, valeur)


def deplie(valeur):
    """Retire l'echappement ICU `''` → `'`."""
    return valeur.replace("''", "'")


def charge(nom):
    with io.open(os.path.join(SRC, nom), encoding="utf-8") as f:
        return json.load(f)


fr = charge("app_fr.arb")
en = charge("app_en.arb")

cles = [k for k in en if not k.startswith("@")]
assert set(cles) == set(k for k in fr if not k.startswith("@")), "les deux ARB divergent en cles"

# ⚠️ Verifie AVANT de filtrer : une cle de REMPLACEES qui n'existe plus dans l'ARB signifierait que
# la reecriture n'a plus d'original, donc que personne ne saurait de quoi elle diverge.
inconnues = REMPLACEES - set(cles)
assert not inconnues, "REMPLACEES cite des cles absentes de l'ARB : %r" % sorted(inconnues)
cles = [k for k in cles if k not in REMPLACEES]

# Same rule for the keys deliberately NOT transposed: each one must still exist in the ARB, or its
# reason would be about a string nobody can look up any more.
inconnues = set(ECARTEES) - set(cles)
assert not inconnues, "ECARTEES cite des cles absentes de l'ARB : %r" % sorted(inconnues)
cles = [k for k in cles if k not in ECARTEES]

# ── Les index de placeholders viennent des METADONNEES, pas de l'ordre d'apparition ────────────
#
# ⚠️ Deriver l'index de l'ordre dans lequel les `{nom}` apparaissent dans le texte serait juste
# tant qu'une traduction ne les reordonne pas. Le jour ou l'anglais dit « {skipped} skipped of
# {count} » la ou le francais dit « {count} notes ({skipped} ignorees) », les deux langues
# n'affecteraient plus le meme argument au meme trou. La declaration `@cle.placeholders`, elle, est
# la meme des deux cotes par construction.
index_par_cle = {}
type_par_cle = {}
for cle in cles:
    meta_en = en.get("@" + cle, {}) or {}
    meta_fr = fr.get("@" + cle, {}) or {}
    ph_en = meta_en.get("placeholders", {}) or {}
    ph_fr = meta_fr.get("placeholders", {}) or {}
    if list(ph_en) != list(ph_fr):
        raise ValueError("placeholders declares differemment pour %s : %r vs %r"
                         % (cle, list(ph_en), list(ph_fr)))
    index_par_cle[cle] = {nom: i + 1 for i, nom in enumerate(ph_en)}
    type_par_cle[cle] = {nom: (d or {}).get("type", "String") for nom, d in ph_en.items()}

# ── Controle : tout `{nom}` du texte doit etre declare, dans les DEUX langues ──────────────────
#
# ⚠️ Le controle passe par la MEME decomposition que la transposition, jamais par un `re.findall`
# sur la chaine brute. Une branche de pluriel s'ecrit `=1{minute}` : au regard naif, `{minute}` est
# un placeholder non declare, alors que c'est le corps de la branche. Un controle qui ne comprend
# pas la grammaire qu'il controle produit de faux positifs — et on prend l'habitude de le
# desactiver, ce qui lui fait rater les vrais.
def segments(valeur):
    decoupe = decoupe_pluriel(valeur)
    if decoupe is None:
        return [valeur]
    prefixe, _, branches, suffixe = decoupe
    return [prefixe, suffixe] + list(branches.values())


for cle in cles:
    for langue, arb in (("fr", fr), ("en", en)):
        for segment in segments(arb[cle]):
            for nom in re.findall(r"\{(\w+)\}", segment):
                if nom not in index_par_cle[cle]:
                    raise ValueError("%s [%s] : `{%s}` utilise mais non declare" % (cle, langue, nom))

# ── Construction ───────────────────────────────────────────────────────────────────────────────
noms_android = {}
for cle in cles:
    n = snake(cle)
    if n in noms_android:
        raise ValueError("collision de nom Android : %s et %s → %s" % (noms_android[n], cle, n))
    noms_android[n] = cle

pluriels = set()
entrees = {}   # cle ARB → {langue: (genre, xml)}
for cle in cles:
    idx = index_par_cle[cle]
    typ = type_par_cle[cle]
    nom = snake(cle)
    a_des_args = bool(idx)
    pour_langue = {}
    for langue, arb in (("fr", fr), ("en", en)):
        brut = arb[cle]
        decoupe = decoupe_pluriel(brut)
        if decoupe is None:
            texte = echappe(deplie(transpose(brut, idx, typ)), a_des_args)
            # ── `formatted="false"` : un `%` litteral dans une chaine SANS argument ─────────────
            #
            # « 100 % hors-ligne » n'est pas une chaine de format, mais aapt et lint la lisent
            # comme telle et voient `% h` comme une conversion inachevee — `lintDebug` echoue
            # dessus, ce qui est un vrai controle et non un faux positif : la meme chaine avec un
            # argument planterait a l'execution.
            #
            # ⚠️ La correction n'est PAS de doubler en `%%`. `getString(int)` n'appelle jamais
            # `String.format` : le doublement s'afficherait tel quel, « 100 %% hors-ligne ». La
            # seule reponse juste est de declarer que la chaine n'est pas un format.
            declare_format = "" if a_des_args or "%" not in texte else ' formatted="false"'
            pour_langue[langue] = ("string",
                                   '    <string name="%s"%s>%s</string>'
                                   % (nom, declare_format, texte))
            continue

        pluriels.add(cle)
        prefixe, variable, branches, suffixe = decoupe
        if variable not in idx:
            raise ValueError("%s : variable de pluriel `%s` non declaree" % (cle, variable))

        lignes = ['    <plurals name="%s">' % nom]
        for categorie, quantite in (("=1", "one"), ("one", "one"), ("other", "other")):
            if categorie not in branches:
                continue
            corps = prefixe + branches[categorie] + suffixe
            # ⚠️ Le litteral « 1 » de la branche `=1` devient l'argument.
            #
            # ICU `=1` ne vaut QUE pour 1 ; la categorie CLDR `one` du francais couvre aussi 0.
            # Laisser « 1 note » en dur afficherait donc « 1 note » pour zero note. On substitue
            # la variable, ce qui rend « 0 note a perdu… » — singulier avec zero, ce qui est la
            # regle francaise. En anglais `one` ne vaut que pour 1 : rigoureusement identique a la
            # version Flutter. Divergence assumee, documentee dans docs/05-PARITE.md.
            if categorie == "=1":
                corps = re.sub(r"(?<![0-9])1(?![0-9])", "{%s}" % variable, corps, count=1)
            texte = echappe(deplie(transpose(corps, idx, typ)), True)
            lignes.append('        <item quantity="%s">%s</item>' % (quantite, texte))
        if langue == "fr":
            # Voir MANY_FR. L'absence de la cle est une erreur, pas un cas a ignorer.
            lignes.append('        <item quantity="many">%s</item>' % echappe(MANY_FR[cle], True))
        lignes.append("    </plurals>")
        pour_langue[langue] = ("plurals", "\n".join(lignes))
    if pour_langue["fr"][0] != pour_langue["en"][0]:
        raise ValueError("%s est un pluriel dans une langue et pas dans l'autre" % cle)
    entrees[cle] = pour_langue


def section_de(cle):
    for prefixe, _ in SECTIONS:
        if cle.startswith(prefixe):
            return prefixe
    return "app"


# ⚠️⚠️ Une section d'AJOUTS_* absente de SECTIONS ne serait JAMAIS rendue, et rien ne le dirait :
# `rends` parcourt SECTIONS, pas les cles d'AJOUTS_*. Une faute de frappe (`"notes"` pour `"note"`)
# supprimerait donc le bloc en silence — exactement le defaut que ce mecanisme repare. Le garde-fou
# du garde-fou.
_prefixes = {p for p, _ in SECTIONS}
for _nom, _ajouts in (("AJOUTS_EN", AJOUTS_EN), ("AJOUTS_FR", AJOUTS_FR)):
    _orphelines = set(_ajouts) - _prefixes
    assert not _orphelines, "%s cite des sections inconnues : %r" % (_nom, sorted(_orphelines))
assert set(AJOUTS_EN) == set(AJOUTS_FR), "AJOUTS_EN et AJOUTS_FR ne couvrent pas les memes sections"

# 🔴 Les AJOUTS sont recopies VERBATIM dans le XML, contrairement aux chaines venues de l'ARB qui
# passent par l'echappement. Rien ne les controlait donc, et une apostrophe nue y suffit a faire
# TRONQUER la chaine par aapt — silencieusement, et en francais seulement. Constate le 2026-08-17 sur
# « Plus d'options » : le fichier genere portait l'apostrophe nue, et l'en-tete du XML enonce
# pourtant la regle. Un garde-fou vaut mieux qu'une regle ecrite.
#
# ⚠️ Le controle ne porte que sur les lignes de VALEUR : les commentaires XML n'ont pas cette
# contrainte, et en exiger l'echappement rendrait les notes illisibles.
_VALEUR = re.compile(r"<(?:string|item)\b[^>]*>(.*?)</(?:string|item)>", re.S)
for _nom, _ajouts in (("AJOUTS_EN", AJOUTS_EN), ("AJOUTS_FR", AJOUTS_FR)):
    for _section, _bloc in _ajouts.items():
        for _valeur in _VALEUR.findall(_bloc):
            _nue = re.sub(r"\\'", "", _valeur)
            assert "'" not in _nue, (
                "%s[%r] : apostrophe non echappee dans une valeur — aapt tronquerait la chaine. "
                "Ecrire \\\\' dans la source Python pour produire \\' dans le XML. Valeur : %r"
                % (_nom, _section, _valeur)
            )

# ⚠️ Un pluriel de l'ARB sans forme `many` fait echouer ce script — et non pas passer en silence.
_sans_many = pluriels - set(MANY_FR)
assert not _sans_many, (
    "pluriel(s) sans forme `many` francaise : %r. Ajouter l'entree dans MANY_FR, ecrite a la main "
    "(voir le commentaire de MANY_FR) — la deriver de `other` serait faux des qu'une chaine ne "
    "place pas un nom apres le nombre." % sorted(_sans_many)
)
_many_orphelines = set(MANY_FR) - pluriels
assert not _many_orphelines, (
    "MANY_FR cite des cles qui ne sont plus des pluriels : %r" % sorted(_many_orphelines)
)

# ⚠️ Meme exigence pour les pluriels ecrits a la main dans AJOUTS_FR : le controle ci-dessus ne
# les voit pas, puisqu'ils ne passent pas par l'emetteur. Sans ce second garde, la moitie des
# pluriels du fichier echapperait a la regle — exactement le genre de trou qu'un garde partiel
# laisse en donnant l'impression d'etre couvert.
_PLURIEL_FR = re.compile(r'<plurals name="(\w+)">(.*?)</plurals>', re.S)
for _section, _bloc in AJOUTS_FR.items():
    for _nom_pl, _corps in _PLURIEL_FR.findall(_bloc):
        assert 'quantity="many"' in _corps, (
            "AJOUTS_FR[%r] : le pluriel `%s` n'a pas de forme `many` francaise. "
            "Voir le commentaire de MANY_FR." % (_section, _nom_pl)
        )

ordre = {p: i for i, (p, _) in enumerate(SECTIONS)}
groupes = {}
for cle in cles:
    groupes.setdefault(section_de(cle), []).append(cle)

ENTETE_EN = """<?xml version="1.0" encoding="utf-8"?>
<!--
  Chaînes anglaises (locale par défaut).

  ⚠️ FICHIER GÉNÉRÉ. Transposé depuis `notes_tech/lib/l10n/app_en.arb` par
  `outils/arb_vers_strings.py`. Une correction faite ici et pas dans l'ARB sera écrasée au
  prochain passage — corriger la source, puis regénérer.

  Les placeholders sont **positionnels** (`%1$s`, `%2$d`) et leur numéro vient de l'ordre de
  déclaration `@clé.placeholders` de l'ARB, pas de leur ordre d'apparition dans le texte. C'est ce
  qui permet à une traduction de les réordonner sans que les deux langues remplissent le mauvais
  trou.
-->
<resources>
"""

ENTETE_FR = """<?xml version="1.0" encoding="utf-8"?>
<!--
  Chaînes françaises.

  ⚠️ FICHIER GÉNÉRÉ — cf. l'en-tête de `values/strings.xml`.

  ⚠️ Toute apostrophe dans une VALEUR est échappée `\\'`. Sans l'antislash, aapt tronque la chaîne
  au caractère fautif, sans erreur ni avertissement : la ressource compile, et le texte affiché est
  amputé. C'est un défaut qui ne se voit qu'à l'écran, en français, donc jamais en test anglais.
-->
<resources>
"""


def rends(langue, entete, ajouts):
    out = [entete]
    for prefixe, titre in SECTIONS:
        if prefixe not in groupes and prefixe not in ajouts:
            continue
        note = PAS_ENCORE_CABLE.get(prefixe)
        marque = ("\n         ⚠️ Aucun écran ne consomme encore ces chaînes — %s. Elles sont "
                  "transposées\n         maintenant pour que la traduction se fasse en un seul "
                  "passage, pas pour laisser\n         croire que la fonctionnalité existe." % note) if note else ""
        out.append("    <!-- ── %s %s%s -->"
                   % (titre, "─" * max(2, 62 - len(titre)), marque))
        for cle in groupes.get(prefixe, []):
            out.append(entrees[cle][langue][1])
        # Les ajouts du portage ferment la section de leur ecran. Voir AJOUTS_EN.
        if prefixe in ajouts:
            out.append(ajouts[prefixe].rstrip("\n"))
        out.append("")
    out.append("</resources>")
    return "\n".join(out) + "\n"


texte_en = rends("en", ENTETE_EN, AJOUTS_EN)
texte_fr = rends("fr", ENTETE_FR, AJOUTS_FR)

# ── Controles AVANT toute ecriture ─────────────────────────────────────────────────────────────
import xml.etree.ElementTree as ET

for nom, texte in (("en", texte_en), ("fr", texte_fr)):
    racine = ET.fromstring(texte)                      # leve si le XML est mal forme
    noms = [e.get("name") for e in racine]
    if len(noms) != len(set(noms)):
        doubles = [n for n in set(noms) if noms.count(n) > 1]
        raise ValueError("noms dupliques dans %s : %r" % (nom, doubles))
    texte.encode("utf-8")

a = {e.get("name"): e.tag for e in ET.fromstring(texte_en)}
b = {e.get("name"): e.tag for e in ET.fromstring(texte_fr)}
if a != b:
    raise ValueError("les deux fichiers divergent : %r" % (set(a.items()) ^ set(b.items())))

os.makedirs(os.path.join(DST, "values"), exist_ok=True)
os.makedirs(os.path.join(DST, "values-fr"), exist_ok=True)
io.open(os.path.join(DST, "values", "strings.xml"), "w", encoding="utf-8", newline="\n").write(texte_en)
io.open(os.path.join(DST, "values-fr", "strings.xml"), "w", encoding="utf-8", newline="\n").write(texte_fr)

print("cles transposees :", len(cles))
print("dont pluriels    :", len(pluriels), sorted(snake(c) for c in pluriels))
print("dont formatees   :", sum(1 for c in cles if index_par_cle[c]))
print("ressources ecrites :", len(a))
