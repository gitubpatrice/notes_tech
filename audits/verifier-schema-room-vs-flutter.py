"""
Compare le schéma que Room attend avec celui que la version Flutter a réellement créé.

Room valide la base à l'ouverture en lisant `PRAGMA table_info`, `PRAGMA index_list`,
`PRAGMA index_xinfo` et `PRAGMA foreign_key_list`. Une divergence sur n'importe lequel de ces
quatre points fait échouer l'ouverture chez l'utilisateur, avec un message « Expected: … Found: … ».

Ce script crée les deux schémas dans deux bases en mémoire et compare exactement ce que Room
comparera. Il répond donc, SANS APPAREIL et sans base réelle, à la question :
« les entités Kotlin décrivent-elles bien la base héritée ? »

Ce qu'il ne prouve pas, et qu'il faut mesurer sur appareil :
  - que SQLCipher ouvre bien le fichier avec la clé au format `x'<hex>'` ;
  - que les données réelles sont lisibles ;
  - que l'index FTS5 existant répond.

Sources :
  - DDL Flutter   : notes_tech/lib/data/db/database.dart, _createSchemaV1
  - DDL Room      : notes_files_tech/app/schemas/…/9.json, produit par KSP

Usage : python audits/verifier-schema-room-vs-flutter.py
Sortie : code 0 si les deux schémas sont indiscernables pour Room, 1 sinon.
"""

import json
import pathlib
import sqlite3
import sys

# La console Windows est en cp1252 : le moindre caractère hors de cette page fait échouer le
# `print` par `UnicodeEncodeError`, et le script sort en 1 — c'est-à-dire qu'une comparaison
# RÉUSSIE se signale comme un échec. Un outil de vérification dont la sortie ment sur son propre
# résultat est pire qu'inutile.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

# ── DDL de la version Flutter, recopié tel quel depuis `database.dart` ───────────────────────
FLUTTER_DDL = [
    """
    CREATE TABLE folders (
      id                  TEXT PRIMARY KEY NOT NULL,
      name                TEXT NOT NULL,
      parent_id           TEXT,
      color               INTEGER,
      icon                TEXT,
      created_at          INTEGER NOT NULL,
      updated_at          INTEGER NOT NULL,
      vault_salt          BLOB,
      vault_kek_wrapped   BLOB,
      vault_iv            BLOB,
      vault_verifier      BLOB,
      vault_mode          TEXT,
      vault_pin_blob      BLOB,
      vault_pin_iv        BLOB,
      vault_attempts      INTEGER NOT NULL DEFAULT 0,
      FOREIGN KEY (parent_id) REFERENCES folders(id) ON DELETE SET NULL
    )
    """,
    "CREATE INDEX idx_folders_parent ON folders(parent_id)",
    """
    CREATE TABLE notes (
      id                  TEXT PRIMARY KEY NOT NULL,
      title               TEXT NOT NULL,
      content             TEXT NOT NULL,
      encrypted_content   BLOB,
      folder_id           TEXT NOT NULL,
      tags                TEXT NOT NULL DEFAULT '',
      pinned              INTEGER NOT NULL DEFAULT 0,
      favorite            INTEGER NOT NULL DEFAULT 0,
      archived            INTEGER NOT NULL DEFAULT 0,
      trashed_at          INTEGER,
      created_at          INTEGER NOT NULL,
      updated_at          INTEGER NOT NULL,
      enc_v               INTEGER NOT NULL DEFAULT 1,
      FOREIGN KEY (folder_id) REFERENCES folders(id) ON DELETE CASCADE
    )
    """,
    """
    CREATE INDEX idx_notes_folder_active
    ON notes(folder_id, archived, trashed_at, updated_at DESC)
    """,
    "CREATE INDEX idx_notes_trashed ON notes(trashed_at)",
    "CREATE INDEX idx_notes_updated ON notes(updated_at)",
]

TABLES = ("folders", "notes")

SCHEMA_DIR = pathlib.Path(__file__).resolve().parent.parent / "app" / "schemas"


def room_ddl():
    """Extrait le DDL attendu par Room depuis le schéma exporté par KSP."""
    candidates = sorted(SCHEMA_DIR.glob("**/*.json"))
    if not candidates:
        sys.exit(
            "Schéma Room introuvable sous app/schemas — lancer `./gradlew assembleDebug` d'abord."
        )
    # Le plus haut numéro de version : c'est celui que la build courante produit.
    latest = max(candidates, key=lambda p: int(p.stem))
    data = json.loads(latest.read_text(encoding="utf-8"))
    statements = []
    for entity in data["database"]["entities"]:
        table = entity["tableName"]
        statements.append(entity["createSql"].replace("${TABLE_NAME}", table))
        for index in entity.get("indices", []):
            statements.append(index["createSql"].replace("${TABLE_NAME}", table))
    return latest, statements


def introspect(statements):
    """Rend ce que Room lira : colonnes, clés étrangères, index et leurs colonnes triées."""
    db = sqlite3.connect(":memory:")
    for sql in statements:
        db.execute(sql)

    snapshot = {}
    for table in TABLES:
        columns = {
            row[1]: {"type": row[2], "notnull": row[3], "default": row[4], "pk": row[5]}
            for row in db.execute(f"PRAGMA table_info({table})")
        }
        foreign_keys = sorted(
            (row[2], row[3], row[4], row[5], row[6])
            for row in db.execute(f"PRAGMA foreign_key_list({table})")
        )
        indices = {}
        for row in db.execute(f"PRAGMA index_list({table})"):
            name, unique, origin = row[1], row[2], row[3]
            # Room ignore les index implicites créés par SQLite pour les contraintes.
            if origin != "c":
                continue
            columns_in_index = [
                (c[2], c[3])  # nom de colonne, ordre décroissant (0/1)
                for c in db.execute(f"PRAGMA index_xinfo({name})")
                if c[2] is not None
            ]
            indices[name] = {"unique": unique, "columns": columns_in_index}
        snapshot[table] = {"columns": columns, "foreign_keys": foreign_keys, "indices": indices}
    db.close()
    return snapshot


def report(flutter, room):
    problems = []
    for table in TABLES:
        f, r = flutter[table], room[table]

        for name in sorted(set(f["columns"]) | set(r["columns"])):
            fc, rc = f["columns"].get(name), r["columns"].get(name)
            if fc is None:
                problems.append(f"{table}.{name} : présente pour Room, ABSENTE de la base Flutter")
            elif rc is None:
                problems.append(f"{table}.{name} : présente en base Flutter, ABSENTE pour Room")
            elif fc != rc:
                problems.append(f"{table}.{name} : Flutter={fc} vs Room={rc}")

        if f["foreign_keys"] != r["foreign_keys"]:
            problems.append(
                f"{table} : clés étrangères différentes\n"
                f"    Flutter={f['foreign_keys']}\n    Room   ={r['foreign_keys']}"
            )

        for name in sorted(set(f["indices"]) | set(r["indices"])):
            fi, ri = f["indices"].get(name), r["indices"].get(name)
            if fi is None:
                problems.append(f"{table} : index « {name} » attendu par Room, absent en base")
            elif ri is None:
                # ⚠️ Cas le plus insidieux : Room compare l'ENSEMBLE des index. Un index présent
                # en base mais non déclaré dans les entités fait échouer la validation.
                problems.append(f"{table} : index « {name} » en base, NON DÉCLARÉ dans les entités")
            elif fi != ri:
                problems.append(
                    f"{table} : index « {name} » différent\n"
                    f"    Flutter={fi}\n    Room   ={ri}"
                )
    return problems


def main():
    schema_file, statements = room_ddl()
    print(f"Schéma Room lu depuis : {schema_file.name}")
    problems = report(introspect(FLUTTER_DDL), introspect(statements))
    if problems:
        print(f"\n{len(problems)} DIVERGENCE(S) — Room refuserait d'ouvrir la base héritée :\n")
        for p in problems:
            print(f"  - {p}")
        return 1
    print(
        "\nAucune divergence.\n"
        "Les entités Room décrivent exactement le schéma créé par la version Flutter :\n"
        "colonnes, types, nullabilité, valeurs par défaut, clés primaires, clés étrangères,\n"
        "index et ordres de tri.\n\n"
        "⚠️ Ne prouve PAS que la base réelle s'ouvre : la clé SQLCipher et l'index FTS5 se\n"
        "vérifient sur appareil (cf. docs/06-ISOLATION-PENDANT-LE-CHANTIER.md §2)."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
