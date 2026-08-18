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
DST = r"j:\applications\notes_files_tech\app\src\main\res"

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
""",
    "note": """\
    <!--
      Ajout du portage : la note publiee copiait une chaine vide, ce qui EFFACE ce que
      l'utilisateur avait dans son presse-papiers. Meme decision que l'archive vide qui ne se
      partage plus.
    -->
    <string name="note_editor_copy_empty">Nothing to copy: this note is empty</string>
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
         echoue, rassurer serait decrire l'inverse de la situation. -->
    <string name="panic_incomplete_plaintext">Key destroyed: the database can no longer be decrypted. However %1$d cleanup step(s) failed, and READABLE export files may remain on this device. Do not part with it before checking.</string>
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
         donc dans l'ecran, seul endroit qui connait les deux. -->
    <string name="voice_model_base_notes">Recommended. Good French quality, about 3 s of compute for 5 s of speech.</string>
    <string name="voice_model_tiny_notes">Light and fast, rough French quality. For modest devices.</string>

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
    <string name="startup_failure_missing_key">The encryption key for this database was not found. Install Notes Tech 2.0.4 first, open it once, then update again.</string>
    <string name="startup_failure_key_unavailable">The device keystore is temporarily unavailable. Restart the app; if the problem persists, restart the device.</string>
    <string name="startup_failure_retry">Try again</string>
""",
}

AJOUTS_FR = {
    "common": """\
    <!-- Ajout du portage : Compose pose une fleche de retour la ou Flutter s'appuie sur le
         retour implicite de l'AppBar. « Fermer » decrivait mal ce geste. -->
    <string name="common_back">Retour</string>
    <string name="common_more_options">Plus d\\'options</string>
""",
    "note": """\
    <string name="note_editor_copy_empty">Rien à copier : cette note est vide</string>
""",
    "trash": """\
    <plurals name="trash_emptied">
        <item quantity="one">%1$d note supprimée définitivement</item>
        <item quantity="other">%1$d notes supprimées définitivement</item>
    </plurals>
""",
    "vault": """\
    <string name="vault_convert_escaped_cancellation">Annulation trop tardive : ce dossier est devenu un coffre. Ses notes seront chiffrées à sa première ouverture.</string>
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
    <string name="panic_incomplete_plaintext">Clé détruite : la base n\\'est plus déchiffrable. En revanche, %1$d étape(s) de nettoyage ont échoué, et des fichiers d\\'export LISIBLES peuvent subsister sur cet appareil. Ne vous en séparez pas sans vérifier.</string>
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

    <string name="voice_model_base_notes">Conseillé. Bonne qualité en français, environ 3 s de calcul pour 5 s de parole.</string>
    <string name="voice_model_tiny_notes">Léger et rapide, qualité en français approximative. Pour les appareils modestes.</string>

    <string name="voice_nothing_heard">Rien n\\'a été entendu, aucun texte inséré.</string>

    <string name="voice_permission_needed">La permission du micro est nécessaire pour dicter. Appuyez à nouveau sur le micro pour l\\'autoriser.</string>

    <string name="voice_system_settings_unavailable">Cet appareil ne propose pas cet écran de réglages. Ouvrez les réglages Android, cherchez Notes Tech, et autorisez le micro.</string>

    <string name="voice_setup_remove_confirm_title">Retirer le modèle ?</string>
    <string name="voice_setup_remove_confirm_body">Vous devrez le retélécharger et le réimporter pour dicter. Vos notes ne sont pas concernées.</string>
    <!-- Voir le commentaire cote EN : la borne de 2 min s'appliquait en silence (04-PIEGES.md §96). -->
    <string name="voice_limit_reached">Texte inséré. Limite de %1$s atteinte : la suite n\\'a pas été enregistrée.</string>
""",
    "app": """\
    <!-- Ajout du portage : l'application publiee n'offre pas de reveler le code. -->
    <string name="pin_show_tooltip">Afficher le code</string>
    <string name="pin_hide_tooltip">Masquer le code</string>

    <!-- ── Écran d'échec au démarrage ───────────────────────────────────────── -->
    <string name="startup_failure_title">Vos notes n\\'ont pas pu être déverrouillées</string>
    <string name="startup_failure_notes_are_safe">Vos notes sont toujours sur cet appareil et n\\'ont pas été modifiées.</string>
    <string name="startup_failure_missing_key">La clé de chiffrement de cette base est introuvable. Installez d\\'abord Notes Tech 2.0.4, ouvrez-la une fois, puis remettez à jour.</string>
    <string name="startup_failure_key_unavailable">Le coffre-fort de clés de l\\'appareil est momentanément indisponible. Relancez l\\'application ; si le problème persiste, redémarrez l\\'appareil.</string>
    <string name="startup_failure_retry">Réessayer</string>
""",
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
REMPLACEES = {
    "panicIncomplete",
    "voiceSetupSubtitle",
    "voiceSetupOfflineBanner",
    "voiceSetupSecurityFooterBody",
    # `voiceSetupSecurityFooterLabel` disait « Promesse ». Le mot annonce un engagement la ou la
    # phrase qu'il coiffe enonce un fonctionnement — et il surjoue un texte qui se suffit. Remplace
    # par un intitule qui DECRIT, dans le vocabulaire que la politique de confidentialite emploie
    # deja (« Donnees traitees »).
    "voiceSetupSecurityFooterLabel",
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
