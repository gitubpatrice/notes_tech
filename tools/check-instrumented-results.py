#!/usr/bin/env python3
"""Juge les résultats des tests instrumentés lancés sur l'émulateur de la CI.

Pourquoi ce script existe
-------------------------
Les tests instrumentés de Notes Tech ont longtemps tourné sur des téléphones seulement : beaucoup
exigent un Keystore matériel, une empreinte enregistrée ou un modèle de dictée, qu'aucun émulateur
n'a. La 3.1.1 a montré le prix de ce choix : le plantage de l'appui long (#36) et le « Traduire »
qui sortait le texte d'une note n'étaient visibles QUE dans ces tests, et rien ne les lançait.

Sur l'émulateur, la tâche Gradle finit donc en échec quoi qu'il arrive. Ce script remplace son code
de sortie par un jugement test par test, et ne laisse passer que ce que l'émulateur ne PEUT pas
faire, nommé un par un, avec sa raison.

Trois pièges que ce script évite
--------------------------------
1. **Une hypothèse non remplie n'a pas la même forme selon la version d'AGP.** Jusqu'à AGP 9.4, un
   ÉCHEC (mesuré le 2026-10-29 : `skipped="0"`, et les `AssumptionViolatedException` sous
   `<failure>`) ; depuis AGP 9.5, un `<skipped>` dont le TEXTE porte l'exception et sa pile (mesuré
   le 2026-11-09, PR #51 — ce script, qui ne lisait que `<failure>`, a alors jugé « sans raison » les
   onze sauts admis). Les deux formes sont lues, et dans les deux la cause se lit ; le total ne dit
   rien. Un `<skipped>` sans hypothèse levée — un `@Ignore`, un saut sans raison — n'est jamais admis.
2. **Un test ignoré n'est pas un test vert** (`docs/04-PIEGES.md` §45). Seules les raisons de
   [RAISONS_ADMISES] passent ; toute autre hypothèse levée fait échouer le contrôle — une nouvelle
   raison s'ajoute ici, en revue, jamais par défaut.
3. **Le témoin du Keystore matériel DOIT échouer ici.**
   `AndroidVaultKeystoreTest.la_cle_creee_est_retenue_par_le_materiel_securise` exige une clé tenue
   par du matériel : sur le Keystore logiciel de l'émulateur, le coffre doit la refuser avec
   `KeystoreSoftwareOnlyException`. S'il passait, le coffre aurait accepté une clé logicielle ; s'il
   échouait pour une autre cause, il ne prouverait plus rien. Les deux font échouer le contrôle.

Et un plancher : moins de [MINIMUM_DE_TESTS] cas, c'est une suite qui n'a pas tourné en entier.

Usage
-----
    python3 tools/check-instrumented-results.py <dossier-des-XML>
    python3 tools/check-instrumented-results.py --self-test

Code de sortie 0 si les résultats sont acceptables, 1 sinon.
"""

from __future__ import annotations

import pathlib
import sys
import tempfile
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape

# 501 cas le 2026-10-29. Un plancher, pas un compte exact : retirer des tests le fait baisser ici,
# dans le même commit.
MINIMUM_DE_TESTS = 495

# Ce que l'émulateur ne peut pas fournir. Chaque raison est le message exact de l'hypothèse levée.
RAISONS_ADMISES = {
    "software-only Keystore: the vault refuses its keys":
        "Keystore logiciel : le coffre refuse ses clés exprès (creerOuIgnorer)",
    "no biometric enrolled on this device":
        "aucune empreinte enregistrée sur l'émulateur",
    "aucun modele installe sur cet appareil":
        "le modèle de dictée (32 à 57 Mo) s'importe à la main",
}

TEMOIN = (
    "com.filestech.notes_tech.security.vault.AndroidVaultKeystoreTest",
    "la_cle_creee_est_retenue_par_le_materiel_securise",
)
CAUSE_DU_TEMOIN = "KeystoreSoftwareOnlyException"


def lire(dossier: pathlib.Path) -> list[tuple[str, str, str | None]]:
    """(classe, test, texte de l'échec ou None) pour chaque cas de chaque XML du dossier."""
    cas = []
    for fichier in sorted(dossier.glob("*.xml")):
        for element in ET.parse(fichier).getroot().iter("testcase"):
            echec = element.find("failure")
            if echec is None:
                echec = element.find("error")
            texte = None if echec is None else (echec.get("message") or "") + "\n" + (echec.text or "")
            sautee = element.find("skipped")
            if sautee is not None:
                texte = raison_du_saut(sautee)
            cas.append((element.get("classname", ""), element.get("name", ""), texte))
    return cas


def raison_du_saut(sautee: ET.Element) -> str:
    """Pourquoi un test a été sauté, tel que le XML le dit — cf. le premier piège.

    AGP 9.5 met l'hypothèse levée, exception et pile, dans le texte du `<skipped>`. Un saut qui n'en
    porte pas n'a rien à juger : il est rendu sans le mot `AssumptionViolatedException`, donc compté
    comme un échec, même si son texte cite une raison admise.
    """
    raison = ((sautee.get("message") or "") + "\n" + (sautee.text or "")).strip()
    if "AssumptionViolatedException" in raison:
        return raison
    return f"<skipped> sans hypothèse levée : {raison or 'aucune raison'}"


def juger(cas: list[tuple[str, str, str | None]]) -> list[str]:
    """Les défauts, vide si les résultats sont acceptables."""
    defauts = []
    if len(cas) < MINIMUM_DE_TESTS:
        defauts.append(f"{len(cas)} cas seulement, plancher {MINIMUM_DE_TESTS} : la suite n'a pas tourné en entier")

    temoin_vu = False
    for classe, test, texte in cas:
        if (classe, test) == TEMOIN:
            temoin_vu = True
            if texte is None:
                defauts.append(f"{test} PASSE sur l'émulateur : le coffre a accepté une clé logicielle")
            elif CAUSE_DU_TEMOIN not in texte:
                defauts.append(f"{test} échoue sans {CAUSE_DU_TEMOIN} : le témoin ne prouve plus rien")
            continue
        if texte is None:
            continue
        if "AssumptionViolatedException" in texte:
            if not any(raison in texte for raison in RAISONS_ADMISES):
                defauts.append(f"{classe}.{test} ignoré pour une raison non admise : {texte.strip()[:200]}")
            continue
        defauts.append(f"{classe}.{test} ÉCHOUE : {texte.strip()[:300]}")

    if cas and not temoin_vu:
        defauts.append(f"le témoin {TEMOIN[1]} n'a pas tourné")
    return defauts


def resumer(cas: list[tuple[str, str, str | None]]) -> str:
    ignores = sum(1 for c in cas if c[2] and "AssumptionViolatedException" in c[2])
    echecs = sum(1 for c in cas if c[2] and "AssumptionViolatedException" not in c[2])
    return f"{len(cas)} cas : {len(cas) - ignores - echecs} réussis, {ignores} ignorés, {echecs} en échec"


# ── Contrôle négatif ────────────────────────────────────────────────────────────────────────────


class Saut(str):
    """Dans l'auto-contrôle, un cas écrit en `<skipped>` (AGP 9.5) au lieu de `<failure>` (AGP 9.4)."""


def _xml(cas: list[tuple[str, str, str | None]]) -> str:
    lignes = ['<?xml version="1.0" encoding="UTF-8"?>', "<testsuites><testsuite>"]
    for classe, test, texte in cas:
        if texte is None:
            lignes.append(f'<testcase name="{test}" classname="{classe}"/>')
        else:
            balise = "skipped" if isinstance(texte, Saut) else "failure"
            lignes.append(
                f'<testcase name="{test}" classname="{classe}"><{balise}>{escape(texte)}</{balise}></testcase>',
            )
    lignes.append("</testsuite></testsuites>")
    return "\n".join(lignes)


def auto_controle() -> int:
    ordinaires = [("com.x.ATest", f"t{i}", None) for i in range(MINIMUM_DE_TESTS)]
    temoin_ok = (*TEMOIN, f"com.filestech.notes_tech.security.vault.{CAUSE_DU_TEMOIN}: cle non retenue")
    admis = ("com.x.VoixTest", "v", "org.junit.AssumptionViolatedException: aucun modele installe sur cet appareil")
    scenarios = {
        "résultats sains": (ordinaires + [temoin_ok, admis], True),
        "le témoin passe": (ordinaires + [(*TEMOIN, None)], False),
        "le témoin échoue pour une autre cause": (ordinaires + [(*TEMOIN, "java.lang.NullPointerException")], False),
        "le témoin n'a pas tourné": (ordinaires, False),
        "un vrai échec": (ordinaires + [temoin_ok, ("com.x.ATest", "rouge", "AssertionError: expected 1")], False),
        "une raison d'ignorer non admise": (
            ordinaires + [temoin_ok, ("com.x.ATest", "i", "AssumptionViolatedException: presse-papiers illisible")],
            False,
        ),
        "trop peu de tests": (ordinaires[:10] + [temoin_ok], False),
        # AGP 9.5 : l'hypothèse dans le texte d'un <skipped>, suivie de sa pile.
        "AGP 9.5 : raison admise dans un <skipped>": (
            ordinaires + [temoin_ok, ("com.x.VoixTest", "v", Saut(admis[2] + "\n\tat org.junit.Assume.assumeTrue"))],
            True,
        ),
        "AGP 9.5 : raison non admise dans un <skipped>": (
            ordinaires + [temoin_ok, ("com.x.ATest", "i", Saut("org.junit.AssumptionViolatedException: autre"))],
            False,
        ),
        "AGP 9.5 : <skipped> vide": (ordinaires + [temoin_ok, ("com.x.ATest", "i", Saut(""))], False),
        "AGP 9.5 : @Ignore qui cite une raison admise": (
            ordinaires + [temoin_ok, ("com.x.ATest", "i", Saut("no biometric enrolled on this device"))],
            False,
        ),
    }
    rate = []
    with tempfile.TemporaryDirectory() as dossier:
        for nom, (cas, accepte) in scenarios.items():
            chemin = pathlib.Path(dossier) / "TEST-x.xml"
            chemin.write_text(_xml(cas), encoding="utf-8")
            obtenu = not juger(lire(pathlib.Path(dossier)))
            print(f"  {'OK ' if obtenu == accepte else 'RATÉ'} {nom} : attendu {'accepté' if accepte else 'refusé'}")
            if obtenu != accepte:
                rate.append(nom)
    if rate:
        print(f"contrôle négatif RATÉ : {rate}")
        return 1
    print(f"contrôle négatif : {len(scenarios)} scénarios, tous jugés comme attendu")
    return 0


def main(argv: list[str]) -> int:
    if argv[1:] == ["--self-test"]:
        return auto_controle()
    if len(argv) != 2:
        print(__doc__)
        return 1
    dossier = pathlib.Path(argv[1])
    cas = lire(dossier)
    if not cas:
        print(f"aucun résultat dans {dossier} : les tests n'ont pas tourné")
        return 1
    print(resumer(cas))
    defauts = juger(cas)
    for defaut in defauts:
        print(f"::error::{defaut}")
    if not defauts:
        print("résultats acceptables : seuls ont manqué ce que l'émulateur ne peut pas fournir")
    return 1 if defauts else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
