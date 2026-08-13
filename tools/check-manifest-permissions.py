#!/usr/bin/env python3
"""Contrôle les permissions du manifeste FUSIONNÉ de la build release.

Pourquoi ce script existe
-------------------------
« 100 % local, zéro permission Internet » est écrit sur files-tech.com, dans les métadonnées
F-Droid et dans PRIVACY.md. C'est donc une contrainte de build, pas une intention — et une promesse
qu'aucun build ne vérifie n'est qu'une intention.

Le précédent est dans le portefeuille : Agenda Tech a **publié** un APK portant
`ACCESS_NETWORK_STATE`, tiré transitivement par `androidx.work` via Glance. Personne ne l'a vu
avant un audit ultérieur.

Trois pièges que ce script évite
--------------------------------
1. **Le manifeste source ne prouve rien.** Il contient `INTERNET` *pour la retirer*
   (`tools:node="remove"`). Chercher dans la source rapporte donc la permission comme présente sur
   une build qui ne l'a pas. Seul le manifeste fusionné fait foi.
2. **`<uses-permission>` peut s'écrire sur plusieurs lignes.** Un `grep` par ligne rate un élément
   dont `android:name` est sur sa propre ligne — exactement la forme que produit la fusion pour
   certaines permissions. Ce script analyse le XML.
3. **Zéro permission est ici le résultat ATTENDU.** Le script d'Agenda Tech échoue si l'ensemble
   trouvé est vide, au motif qu'un manifeste sans permission ne peut pas être le sien. Recopier
   cette règle ici rendrait le contrôle systématiquement rouge. La contrepartie, c'est qu'il faut
   un autre moyen de distinguer « le contrôle a tourné et n'a rien trouvé » de « le contrôle n'a
   rien analysé » : on vérifie donc que le fichier est bien un manifeste d'application.

Usage
-----
    python3 tools/check-manifest-permissions.py [chemin-du-manifeste-fusionné]

Code de sortie 0 si le manifeste respecte la liste revue, 1 sinon.
"""

from __future__ import annotations

import os
import sys
import xml.etree.ElementTree as ET

# La console Windows est en cp1252 : sans ça, un simple caractère accentué dans un message ferait
# sortir le script en erreur, c'est-à-dire signaler un ÉCHEC du contrôle alors qu'il a réussi.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ANDROID_NS = "http://schemas.android.com/apk/res/android"

DEFAULT_MANIFEST = os.path.join(
    "app", "build", "intermediates", "merged_manifest", "release",
    "processReleaseMainManifest", "AndroidManifest.xml",
)

# Ne doivent JAMAIS atteindre l'APK, quoi que dise la liste des permissions autorisées. Séparées
# pour que le message d'échec dise *pourquoi*, et pas seulement « inattendue ».
FORBIDDEN = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
}

# Permissions que l'APK revu est censé porter.
#
# Vide aujourd'hui, et c'est le sujet : Notes Tech n'en demande aucune. Toute entrée ajoutée ici
# est un changement en DEUX parties — la permission doit AUSSI être documentée dans la table de
# PRIVACY.md et PRIVACY.fr.md. C'est cet appariement qui fait la valeur de la liste.
#
# ⚠️ En phase 7, la dictée vocale ajoutera `android.permission.RECORD_AUDIO`. La faire entrer ici
# sans l'écrire dans PRIVACY.md serait précisément la dérive que ce fichier existe pour empêcher.
ALLOWED: set[str] = set()

# Permissions de niveau signature générées par androidx pour ses propres receivers non exportés.
# Leur nom dérive de l'applicationId, qui varie selon que la build s'installe à côté de
# l'application Flutter ou à sa place (cf. docs/06-ISOLATION-PENDANT-LE-CHANTIER.md). On les
# reconnaît donc par leur suffixe plutôt que par une liste de noms complets.
ALLOWED_SUFFIXES = (
    ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
)

IN_CI = os.environ.get("GITHUB_ACTIONS") == "true"


def fail(lines: list[str]) -> None:
    prefix = "::error::" if IN_CI else "ERREUR: "
    for line in lines:
        print(f"{prefix}{line}")


def is_allowed(name: str) -> bool:
    return name in ALLOWED or name.endswith(ALLOWED_SUFFIXES)


def parse(manifest_path: str) -> tuple[ET.Element, set[str]]:
    """Rend la racine du manifeste et l'ensemble des permissions déclarées."""
    root = ET.parse(manifest_path).getroot()
    names = set()
    # Les deux orthographes : `<uses-permission-sdk-23>` accorde la même capacité sur API 23+, et
    # une dépendance pourrait faire passer une permission par cette porte-là.
    for tag in ("uses-permission", "uses-permission-sdk-23"):
        for element in root.iter(tag):
            name = element.get(f"{{{ANDROID_NS}}}name")
            if name:
                names.add(name)
    return root, names


def main(argv: list[str]) -> int:
    manifest_path = argv[1] if len(argv) > 1 else DEFAULT_MANIFEST

    if not os.path.isfile(manifest_path):
        fail([
            f"manifeste fusionné introuvable : {manifest_path}",
            "lancer d'abord : ./gradlew :app:processReleaseMainManifest",
        ])
        return 1

    try:
        root, found = parse(manifest_path)
    except ET.ParseError as error:
        fail([f"manifeste fusionné illisible ({manifest_path}) : {error}"])
        return 1

    # Contrôle de bon fonctionnement, à la place du « au moins une permission » d'Agenda Tech :
    # ici, zéro permission est le résultat attendu, donc un ensemble vide ne peut pas servir de
    # signal d'échec. Ce qu'on vérifie, c'est qu'on a bien analysé un manifeste d'application.
    package = root.get("package")
    if root.tag != "manifest" or root.find("application") is None:
        fail([
            f"{manifest_path} n'est pas un manifeste d'application —",
            "c'est un échec du contrôle, pas une absence de permission.",
        ])
        return 1

    print(f"Manifeste fusionné analysé : {manifest_path}")
    print(f"applicationId : {package}")
    if found:
        print(f"Permissions déclarées ({len(found)}) :")
        for name in sorted(found):
            marque = "ok " if is_allowed(name) else "!! "
            print(f"  {marque}{name}")
    else:
        print("Permissions déclarées : AUCUNE.")

    problems: list[str] = []

    forbidden_present = sorted(found & FORBIDDEN)
    if forbidden_present:
        problems.append("Permission RÉSEAU présente dans le manifeste fusionné release :")
        problems += [f"  {name}" for name in forbidden_present]
        problems.append(
            "Notes Tech promet publiquement « 100 % local, zéro permission Internet ». Soit la "
            "dépendance qui l'a tirée doit partir, soit elle doit être retirée par "
            "tools:node=\"remove\" et ce retrait justifié.",
        )

    unexpected = sorted(name for name in found - FORBIDDEN if not is_allowed(name))
    if unexpected:
        problems.append("Permission(s) hors de la liste revue :")
        problems += [f"  {name}" for name in unexpected]
        problems.append(
            "Une permission arrivée transitivement doit être un acte délibéré : l'ajouter à "
            "ALLOWED dans ce script ET à la table de PRIVACY.md et PRIVACY.fr.md, ou retirer la "
            "dépendance qui la tire.",
        )

    # Une permission qui disparaît n'est pas un échec, mais ne doit pas passer inaperçue : la liste
    # et PRIVACY.md décriraient alors un APK qui n'existe plus.
    missing = sorted(ALLOWED - found)
    if missing:
        print()
        print("Note — attendue(s) mais absente(s) du manifeste fusionné :")
        for name in missing:
            print(f"  {name}")
        print("  Retirer ces entrées d'ALLOWED et de PRIVACY.md si le retrait est voulu.")

    if problems:
        print()
        fail(problems)
        return 1

    print()
    print("OK : ni INTERNET ni ACCESS_NETWORK_STATE, et aucune permission hors liste revue.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
