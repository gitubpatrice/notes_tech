# -*- coding: utf-8 -*-
"""
Controle de non-regression de la transposition i18n, dans le sens INVERSE.

Le script `arb_vers_strings.py` produit du XML a partir de l'ARB. Relire ce XML et verifier qu'il
« a l'air bien » ne prouve rien : c'est le meme raisonnement qui l'a produit. Ici on refait le
chemin en sens inverse — XML → ARB — et on compare a la source. Une transposition qui perd un
caractere, inverse deux placeholders ou mange une apostrophe ne survit pas a ce controle.

⚠️ Trois cas sont des divergences ATTENDUES, et le script les nomme au lieu de les taire :
la branche `=1` d'un pluriel, dont le litteral « 1 » devient l'argument.
"""
import io
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8")

SRC = r"j:\applications\notes_tech\lib\l10n"
DST = r"j:\applications\notes_files_tech\app\src\main\res"


def snake(cle):
    s = re.sub(r"(?<=[a-z0-9])(?=[A-Z])", "_", cle)
    s = re.sub(r"(?<=[A-Za-z])(?=[0-9])", "_", s)
    return s.lower()


def desechappe(t):
    """Inverse exact de la fonction `echappe` du script de transposition."""
    out = []
    i = 0
    while i < len(t):
        if t[i] == "\\" and i + 1 < len(t):
            suivant = t[i + 1]
            out.append("\n" if suivant == "n" else suivant)
            i += 2
        else:
            out.append(t[i])
            i += 1
    return "".join(out)


def charge(nom):
    with io.open(os.path.join(SRC, nom), encoding="utf-8") as f:
        return json.load(f)


fr = charge("app_fr.arb")
en = charge("app_en.arb")
cles = [k for k in en if not k.startswith("@")]

index = {}
types = {}
for cle in cles:
    ph = (en.get("@" + cle, {}) or {}).get("placeholders", {}) or {}
    index[cle] = {i + 1: nom for i, nom in enumerate(ph)}
    types[cle] = {nom: (d or {}).get("type", "String") for nom, d in ph.items()}


def remets_les_trous(texte, cle):
    def sub(m):
        n = int(m.group(1))
        if n not in index[cle]:
            raise ValueError("%s : argument %%%d$ sans placeholder correspondant" % (cle, n))
        nom = index[cle][n]
        attendu = "d" if types[cle][nom] == "int" else "s"
        if m.group(2) != attendu:
            raise ValueError("%s : %%%d$%s alors que `%s` est %s"
                             % (cle, n, m.group(2), nom, types[cle][nom]))
        return "{%s}" % nom
    return re.sub(r"%([0-9]+)\$([sd])", sub, texte)


def lis(chemin):
    racine = ET.parse(chemin).getroot()
    plats, plur = {}, {}
    for e in racine:
        if e.tag == "string":
            plats[e.get("name")] = e.text or ""
        elif e.tag == "plurals":
            plur[e.get("name")] = {i.get("quantity"): (i.text or "") for i in e}
    return plats, plur


PLURIEL = re.compile(r"\{\s*(\w+)\s*,\s*plural\s*,")

ecarts = []
attendus = []
verifiees = 0

for langue, arb, dossier in (("en", en, "values"), ("fr", fr, "values-fr")):
    plats, plur = lis(os.path.join(DST, dossier, "strings.xml"))
    for cle in cles:
        nom = snake(cle)
        source = arb[cle].replace("''", "'")
        m = PLURIEL.search(source)

        if m is None:
            if nom not in plats:
                ecarts.append("%s [%s] : absente du XML" % (nom, langue))
                continue
            reconstruit = remets_les_trous(desechappe(plats[nom]), cle)
            if reconstruit != source:
                ecarts.append("%s [%s]\n    source : %r\n    retour : %r"
                              % (nom, langue, source, reconstruit))
            verifiees += 1
            continue

        # Pluriel : on reconstruit chaque branche a partir de la source ICU et on la confronte.
        if nom not in plur:
            ecarts.append("%s [%s] : pluriel absent du XML" % (nom, langue))
            continue

        def ferme(t, d):
            p = 0
            for i in range(d, len(t)):
                if t[i] == "{":
                    p += 1
                elif t[i] == "}":
                    p -= 1
                    if p == 0:
                        return i
            raise ValueError("accolade non fermee")

        fin = ferme(source, m.start())
        prefixe, suffixe = source[:m.start()], source[fin + 1:]
        corps = source[m.end():fin]
        branches, i = {}, 0
        while i < len(corps):
            if corps[i].isspace():
                i += 1
                continue
            j = i
            while j < len(corps) and not corps[j].isspace() and corps[j] != "{":
                j += 1
            categorie = corps[i:j].strip()
            while corps[j].isspace():
                j += 1
            k = ferme(corps, j)
            branches[categorie] = corps[j + 1:k]
            i = k + 1

        for categorie, quantite in (("=1", "one"), ("one", "one"), ("other", "other")):
            if categorie not in branches:
                continue
            attendu = prefixe + branches[categorie] + suffixe
            obtenu = remets_les_trous(desechappe(plur[nom][quantite]), cle)
            if obtenu == attendu:
                verifiees += 1
                continue
            # La seule divergence toleree : le litteral « 1 » remplace par l'argument.
            substitue = re.sub(r"(?<![0-9])1(?![0-9])", "{%s}" % m.group(1), attendu, count=1)
            if categorie == "=1" and obtenu == substitue:
                attendus.append("%s [%s] branche `=1` : %r → %r" % (nom, langue, attendu, obtenu))
                verifiees += 1
            else:
                ecarts.append("%s [%s] %s\n    source : %r\n    retour : %r"
                              % (nom, langue, quantite, attendu, obtenu))

print("segments verifies par retour arriere :", verifiees)
print()
print("DIVERGENCES ASSUMEES (%d) :" % len(attendus))
for a in attendus:
    print("  ", a)
print()
if ecarts:
    print("🔴 ECARTS NON EXPLIQUES (%d) :" % len(ecarts))
    for e in ecarts:
        print("  ", e)
    sys.exit(1)
print("✅ aucun ecart non explique")
