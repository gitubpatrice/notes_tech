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
  - DDL Room      : app/schemas/…/<version>.json, produits par KSP

Since 3.1.0 (schema 10, the notes' colour) it checks TWO things, both of which a user's phone needs:
  1. the Room schema 9 still describes the Flutter base EXACTLY — every installed base starts there;
  2. the Flutter base, with the migrations' SQL replayed in order ([MIGRATIONS]), gives EXACTLY what
     the latest Room schema expects — otherwise Room refuses the base after migrating it, that is
     right after the update.

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

# ── The migrations' SQL, copied from `NotesDatabase` (MIGRATION_9_10, …) ─────────────────────────
# Keyed by the version each one reaches. ⚠️ Kept in step with the Kotlin by hand: a migration written
# there and not here makes this script fail (schema N+1 has a column the replay lacks), which is the
# point — and the instrumented migration test replays the real Kotlin object on a real base.
MIGRATIONS = {
    10: ["ALTER TABLE notes ADD COLUMN color_id INTEGER"],
}

SCHEMA_DIR = pathlib.Path(__file__).resolve().parent.parent / "app" / "schemas"


def room_schemas():
    """The schemas exported by KSP, by version."""
    candidates = {int(p.stem): p for p in SCHEMA_DIR.glob("**/*.json")}
    if not candidates:
        sys.exit(
            "Schéma Room introuvable sous app/schemas — lancer `./gradlew assembleDebug` d'abord."
        )
    return candidates


def room_ddl(schema_file):
    """Extrait le DDL attendu par Room depuis un schéma exporté par KSP."""
    data = json.loads(schema_file.read_text(encoding="utf-8"))
    statements = []
    for entity in data["database"]["entities"]:
        table = entity["tableName"]
        statements.append(entity["createSql"].replace("${TABLE_NAME}", table))
        for index in entity.get("indices", []):
            statements.append(index["createSql"].replace("${TABLE_NAME}", table))
    return statements


def flutter_ddl_migrated_to(version):
    """The Flutter base (version 9), with every migration up to [version] replayed."""
    statements = list(FLUTTER_DDL)
    for target in range(10, version + 1):
        if target not in MIGRATIONS:
            sys.exit(f"Aucune migration vers {target} dans MIGRATIONS : à recopier de NotesDatabase.")
        statements += MIGRATIONS[target]
    return statements


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


NOTES_DATABASE = pathlib.Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "java" / \
    "com" / "filestech" / "notes_tech" / "data" / "local" / "NotesDatabase.kt"


def version_du_code():
    """`NotesDatabase.VERSION`, read in the Kotlin: the schema the app will ask Room for."""
    import re
    trouve = re.search(r"const val VERSION = (\d+)", NOTES_DATABASE.read_text(encoding="utf-8"))
    if not trouve:
        sys.exit("NotesDatabase.VERSION introuvable : le script ne sait plus quel schéma vérifier.")
    return int(trouve.group(1))


def main():
    schemas = room_schemas()
    if 9 not in schemas:
        sys.exit("Schéma Room 9 introuvable : c'est celui de toutes les bases installées.")
    # The schema of the version the CODE declares — not the highest file found, which would let a
    # missing export pass by checking only schema 9 (GPT-5.6 review, 2026-10-10).
    latest = version_du_code()
    if latest not in schemas:
        sys.exit(f"Schéma Room {latest} (NotesDatabase.VERSION) non exporté sous app/schemas : rien ne le vérifie.")
    checks = [(9, FLUTTER_DDL, "la base Flutter")]
    if latest > 9:
        checks.append((latest, flutter_ddl_migrated_to(latest), f"la base Flutter migrée jusqu'à {latest}"))

    failed = False
    for version, base, label in checks:
        print(f"Schéma Room {version} ({schemas[version].name}) contre {label} :")
        problems = report(introspect(base), introspect(room_ddl(schemas[version])))
        if problems:
            failed = True
            print(f"  {len(problems)} DIVERGENCE(S) — Room refuserait cette base :")
            for p in problems:
                print(f"  - {p}")
        else:
            print("  aucune divergence.")
    if failed:
        return 1
    print(
        "\nAucune divergence.\n"
        "Les entités Room décrivent exactement le schéma créé par la version Flutter, et ce schéma\n"
        "migré : colonnes, types, nullabilité, valeurs par défaut, clés primaires, clés étrangères,\n"
        "index et ordres de tri.\n\n"
        "⚠️ Ne prouve PAS que la base réelle s'ouvre : la clé SQLCipher et l'index FTS5 se\n"
        "vérifient sur appareil (cf. docs/06-ISOLATION-PENDANT-LE-CHANTIER.md §2)."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
