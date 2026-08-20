# 12 — Remplacer Notes Tech Flutter par Notes Tech Kotlin

> Écrit le **2026-08-20**, le jour où la bascule technique a été prouvée sur appareil
> (`04-PIEGES.md` §120-§124, `05-PARITE.md` : 0 case ◐).
>
> Ce document ne décrit **pas** comment migrer les données : c'est fait, mesuré, et documenté
> ailleurs. Il décrit comment **remplacer l'application publiée** sans casser ce qui l'entoure.

---

## 🔴 Le point de non-retour

Une fois une 3.0.0 publiée, **aucun utilisateur ne peut revenir en arrière** :

- réinstaller la 2.0.4 par-dessus est un **downgrade**, qu'Android refuse ;
- désinstaller efface la sandbox **et** détruit l'alias AndroidKeyStore qui protège la base
  SQLCipher et les coffres. Les notes ne sont pas récupérables, même avec une sauvegarde des
  fichiers : la clé, elle, est partie.

`allowBackup="false"` et `fullBackupContent="false"` : il n'y a pas de filet côté Android.

**Tout ce qui suit découle de cette phrase.** Chaque étape est ordonnée pour que le geste
irréversible arrive le plus tard possible, et sur le moins de monde possible.

---

## ✅ Ce qui est déjà acquis — ne pas le refaire

| Acquis | Preuve |
|---|---|
| La 3.0.0 s'installe **par-dessus** la 2.0.4 et la 2.0.3 | trois bascules réelles sur le S9, 2026-08-20 |
| La base SQLCipher héritée est relue | notes en clair retrouvées intactes |
| Les coffres **PIN et passphrase** créés en Flutter s'ouvrent en Kotlin | les deux mesurés, clair affiché |
| La signature autorise la mise à jour | `ddb385de…42e9`, vérifié contre l'**APK 2.0.4 installé** |
| Le `versionCode` dépasse celui des installations réelles | 1053 / 2053 / 3053, contre 1052 / 2052 / 3052 |

---

## ⚠️ Ce que la bascule casse, et qui n'est pas dans le code

**La recette F-Droid devient entièrement caduque.** Celle de la MR `!37885` décrit un build
Flutter :

```yaml
    output: build/app/outputs/flutter-apk/app-release.apk
    srclibs:
      - flutter@stable
    prebuild: $$flutter$$/bin/flutter config --no-analytics
    build:
      - $$flutter$$/bin/flutter pub get
      - $$flutter$$/bin/flutter build apk --release
```

Rien n'y survit : ni le `srclibs`, ni les commandes, ni le chemin de sortie. C'est une recette à
**réécrire**, pas à ajuster. Et le portage produit des **splits par ABI**, donc il faudra la même
ligne `output:` que sur Agenda Tech et SMS Tech (cf. `reference_fdroid_output_remplace_prebuild`).

---

## Trois décisions à prendre AVANT toute manipulation

### A. Où vit le code Kotlin ?

État au 2026-08-20 : le portage a **123 commits sur `master`, sans aucun remote**. `notes_tech` en
a **164 sur `main`**, avec ses tags, ses issues, et l'URL que le site et F-Droid référencent.

**Recommandation : pousser le portage dans `gitubpatrice/notes_tech`, sur une branche `kotlin`,
sans rien fusionner.** Le dépôt garde son identité — URL, historique, tags, MR F-Droid — et le
jour J se réduit à un merge.

⚠️ **Ne pas écraser l'histoire par un « gros commit de remplacement ».** Les 123 commits du portage
sont exactement là où vivent les 124 pièges de `04-PIEGES.md` ; les aplatir rendrait ce fichier
invérifiable.

### B. Le `versionCode` n'a qu'une marge de **1**

3.0.0 = 2053, la 2.0.4 publiée = 2052. **Si une 2.0.5 Flutter sort d'ici la bascule, elle prend
2053 et la 3.0.0 devient impossible à publier sans re-bumper.**

L'offset ×1000 a été choisi le 2026-08-20 pour la continuité, et il a rempli son office : il a
permis de mesurer la bascule le jour même. Pour une publication, un **palier franc** est plus sûr.
À trancher.

### C. Que fait-on de la MR F-Droid `!37885` ?

Elle est épinglée sur **2.0.3 / 51**, décrit un build Flutter, et attend en revue depuis juin.

**Recommandation : la laisser vivre.** Elle établit l'identité de mainteneur et le pipeline vert ;
la recette Kotlin sera une mise à jour ensuite. La convertir maintenant risque de renvoyer la
demande en bas de la file, pour une app qui n'est pas encore prête à être publiée.

---

## Le plan, dans l'ordre

### Phase 0 — Le rodage. **Le seul point qu'on ne peut pas accélérer.**

Le portage n'a **jamais servi une seule journée**. 595 tests et trois bascules mesurées ne
remplacent pas une semaine d'usage : ce qui reste à trouver est ergonomique, et aucun test ne voit
ça.

Installer la 3.0.0 sur le S9, y mettre de vraies notes, s'en servir. Tant que cette phase n'est pas
faite, les décisions A/B/C sont théoriques.

### Phase 1 — Trancher A, B, C.

### Phase 2 — Le dépôt

`git remote add` sur `notes_tech`, push de `master` en branche `kotlin`. Rien ne change pour
personne : ni tag, ni release, ni fusion. La CI de `notes_tech` cible `main`, donc **elle ne se
déclenchera pas** — c'est voulu à ce stade, mais il faudra l'étendre avant la fusion.

### Phase 3 — Les métadonnées, avant la première release

- `versionName` sans `-alpha01`
- changelog fastlane **FR + EN** pour le nouveau `versionCode` (⚠️ cap **500 caractères** F-Droid)
- `SECURITY.md`, `README`, `PRIVACY` : l'application change de **langage**, pas de promesses — mais
  ça se vérifie plutôt que se suppose. Contrôle : `~/.claude/tools/check-fastlane-markdown.py`
- ⚠️ vérifier le **manifeste fusionné**, pas le manifeste source, pour les permissions

### Phase 4 — Publication par paliers, du moins risqué au plus risqué

1. **Release GitHub marquée _pre-release_.** Ceux qui l'installent sont volontaires et savent
   revenir en arrière. C'est le seul palier où une erreur se rattrape.
2. Release normale.
3. **Le site** — cinq surfaces, et une omission y est publique : carte d'accueil `index.php`, page
   `notes-tech.php`, `download.php`, **meta-description + Open Graph**, version **EN**.
4. **F-Droid en dernier**, avec la recette réécrite.

---

## ⚠️ Ce qu'il ne faut pas faire

- **Publier sans rodage.** L'irréversibilité rend l'erreur définitive pour l'utilisateur.
- **Aplatir l'histoire du portage** — cf. décision A.
- **Convertir `!37885` maintenant** — cf. décision C.
- **Bumper le `versionCode` au dernier moment** : c'est ce qui a fait échouer la première tentative
  d'installation, le 2026-08-20 (§121).
