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

# Les chaines deja presentes, ecrites a la main en phase 1. Elles ne viennent PAS de l'ARB : la
# version Flutter n'a pas d'ecran d'echec au demarrage, c'est une addition du portage. Le script
# les reinjecte telles quelles pour ne pas les perdre.
CONSERVEES_EN = """\
    <!-- ── Écran d'échec au démarrage ─────────────────────────────────────────
         Ces chaînes n'ont PAS d'équivalent dans la version Flutter : l'écran qu'elles servent est
         une addition du portage, pour l'utilisateur dont la base ne s'ouvre pas. Un message vague
         à ce moment-là transforme une situation récupérable en abandon. -->
    <string name="startup_failure_title">Your notes could not be unlocked</string>
    <string name="startup_failure_notes_are_safe">Your notes are still on this device and have not been modified.</string>
    <string name="startup_failure_missing_key">The encryption key for this database was not found. Install Notes Tech 2.0.4 first, open it once, then update again.</string>
    <string name="startup_failure_key_unavailable">The device keystore is temporarily unavailable. Restart the app; if the problem persists, restart the device.</string>
    <string name="startup_failure_retry">Try again</string>
"""

CONSERVEES_FR = """\
    <!-- ── Écran d'échec au démarrage ───────────────────────────────────────── -->
    <string name="startup_failure_title">Vos notes n\\'ont pas pu être déverrouillées</string>
    <string name="startup_failure_notes_are_safe">Vos notes sont toujours sur cet appareil et n\\'ont pas été modifiées.</string>
    <string name="startup_failure_missing_key">La clé de chiffrement de cette base est introuvable. Installez d\\'abord Notes Tech 2.0.4, ouvrez-la une fois, puis remettez à jour.</string>
    <string name="startup_failure_key_unavailable">Le coffre-fort de clés de l\\'appareil est momentanément indisponible. Relancez l\\'application ; si le problème persiste, redémarrez l\\'appareil.</string>
    <string name="startup_failure_retry">Réessayer</string>
"""

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
PAS_ENCORE_CABLE = {"panic": "phase 6", "export": "phase 6", "voice": "phase 7"}


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


def rends(langue, entete, conservees):
    out = [entete]
    for prefixe, titre in SECTIONS:
        if prefixe not in groupes:
            continue
        note = PAS_ENCORE_CABLE.get(prefixe)
        marque = ("\n         ⚠️ Aucun écran ne consomme encore ces chaînes — %s. Elles sont "
                  "transposées\n         maintenant pour que la traduction se fasse en un seul "
                  "passage, pas pour laisser\n         croire que la fonctionnalité existe." % note) if note else ""
        out.append("    <!-- ── %s %s%s -->"
                   % (titre, "─" * max(2, 62 - len(titre)), marque))
        for cle in groupes[prefixe]:
            out.append(entrees[cle][langue][1])
        out.append("")
    out.append(conservees)
    out.append("</resources>")
    return "\n".join(out) + "\n"


texte_en = rends("en", ENTETE_EN, CONSERVEES_EN)
texte_fr = rends("fr", ENTETE_FR, CONSERVEES_FR)

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
