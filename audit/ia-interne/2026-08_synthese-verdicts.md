# Audit Notes Tech — synthèse et verdicts

Période : 2026-08-06 → 2026-08-07. Commits `62b5e60` → `0551481`.
Contrôle vert entre chaque lot : `flutter analyze` 0 issue, `flutter test`
84 → 121, `integration_test` sur Galaxy S9 0 → 11.

**Règle appliquée à tout ce qui suit : un constat externe se vérifie dans le
code avant d'être appliqué, et le correctif proposé peut être moins bon que
le sien.** Sur 17 constats externes, 5 étaient faux. Aucun n'a été appliqué
sans vérification.

---

## 1. Le motif qui explique presque tout

Les défauts de ce dépôt ne sont pas dispersés : ils ont une forme unique.
**L'invariant du coffre — « une note d'un dossier coffre n'est jamais
persistée en clair » — n'était porté par aucune couche.** Il était
réimplémenté à chaque site d'appel qui pensait à chiffrer. Chaque fois qu'un
geste existait en deux exemplaires, un seul était correct.

| Jumeau | Corrigé | Oublié |
|---|---|---|
| Mise au coffre | en masse | d'**une** note (embedding en clair conservé) |
| Création de note dans un coffre | écran d'accueil | lien fantôme et auto-complétion |
| Sortie de coffre | avertissement pour une note | rien pour un coffre entier |
| Écriture de métadonnées | — | épingler, favori, corbeille réécrivaient TOUT |
| Export | ZIP (mention + suffixe) | `.md` unitaire (rien) |
| Libellé « dérivation » | sheet PIN | sheet passphrase (affiché en permanence) |
| Clé de persistance | `themeMode` (littéraux) | `sortMode` (`enum.name`) |

Réponse structurelle, pas ponctuelle : l'invariant est passé dans
`NotesRepository` (`VaultPlaintextWriteException`), les gestes de métadonnées
ont leurs propres `UPDATE` ciblés au niveau DAO, et l'ouverture de session a
un **point d'entrée unique** appelé par les deux chemins de déverrouillage.

---

## 2. Les deux défauts les plus graves

**Épingler ou jeter une note de coffre ouverte la déchiffrait définitivement.**
`NotesDao.update` écrit la ligne entière depuis `toRow()`. L'éditeur détient
l'éphémère **déchiffrée** (`content` rempli, `encryptedContent` nul). Un tap
sur l'épingle réécrivait donc le contenu en clair et effaçait le blob. Coffre
reverrouillé, la note s'affichait sans cadenas, lisible sans passphrase, et
indexée en recherche plein-texte. Irréversible, silencieux.
Trouvé en préparant le chantier 1, par personne d'autre — j'avais lu ce
fichier pendant des heures la veille sans le voir.

**Une note créée dans un coffre n'était jamais chiffrée.** Le lien fantôme et
l'auto-complétion appelaient `_repo.create` sans chiffrer. La note naissait
non verrouillée, l'éditeur laissait `_wasLocked` à faux, et tout le texte
saisi ensuite partait en clair dans le coffre.
Trouvé par Gemini.

---

## 3. Verdicts sur les constats externes

### Gemini 3.1 Pro — 10 constats, 9 justes

Justes et appliqués : note créée non chiffrée · `isVaultExit` inatteignable ·
`VaultLockedException` avalée sans bookkeeping · famine d'auto-lock entre
coffres · `_saveNow` `if` au lieu de `while` · buffer Argon2id non effacé ·
`getOrCreateKek` partageant une instance · `_disambiguate` collision ZIP ·
noms réservés Windows sur les dossiers.

**Faux positif** : « `fresh.isLocked` est toujours faux à l'export ».
Confusion entre `_repo.get` (ligne brute, `isLocked` vrai) et `_note`
(éphémère déchiffrée, `isLocked` faux). Voir §5.

### Codex — 3 constats, 3 justes

Top-K borné avant le filtre d'éligibilité · `versionCode` incompatible entre
splits et universel · commentaire Gradle devenu faux. Les deux premiers
découlaient de mes propres correctifs.

### GPT-5.2 — 4 constats, 2 justes

Justes : coût du balayage au déverrouillage · critère de réparation trop
étroit (`!isLocked` au lieu de « contenu non vide »).

**Faux positifs** :
- « `updateFlags` ne met plus à jour `trashed_at` » → la corbeille n'expose ni
  épinglage ni favori, et l'ancien code réécrivait la valeur à l'identique.
- « les triggers FTS ne tirent pas sur un UPDATE partiel » → constat
  conditionné à des triggers `AFTER UPDATE OF colonnes…`. Ceux-ci sont
  `AFTER UPDATE ON notes`, sans liste.
- « `'title': ?title` ne compile pas » → syntaxe d'entrée de map null-aware
  de Dart 3.9, suggérée par l'analyseur lui-même. Analyse à 0 issue et
  exécution vérifiée sur appareil.

**Le correctif proposé par GPT pour le coût au déverrouillage était pire que
le problème** : passer en `unawaited` aurait fait courir la réparation en
concurrence de l'auto-lock, avec un `encryptNote` possible après fermeture de
session. Corrigé en resserrant la requête.

### Mes propres correctifs

Trois défauts trouvés en me relisant, dont un que personne n'avait signalé :
la réparation passait par `save()`, qui remet `updatedAt` à maintenant — les
notes réparées seraient remontées en tête de « modifiées récemment » à chaque
ouverture du coffre. Une réparation silencieuse qui réordonne l'écran n'est
pas silencieuse.

---

## 4. Deux faits que j'ai affirmés à tort

À retenir avant de réutiliser mes conclusions.

**« Les titres de coffre sont dans l'index FTS5 »** — faux. Les triggers les
masquent depuis `dbVersion 6` (`CASE WHEN encrypted_content IS NOT NULL THEN
''`) et `note_card` affiche « Note verrouillée ». Le résidu était la seule
colonne `notes.title`, lisible par qui obtient la clé de la base. J'ai posé
une décision produit sur cette prémisse fausse avant de la corriger.

**« Aucune clé GPT n'est disponible »** — j'avais vérifié les CLI, pas
l'environnement ni les fichiers. `GEMINI_API_KEY` est dans l'environnement,
la clé OpenAI dans `J:\applications\OPENAI_API_KEY\`.

---

## 5. Le piège de lecture de ce dépôt

Deux objets `Note` coexistent et se ressemblent :

- `_repo.get(id)` rend la **ligne DB brute**. Note de coffre : `content` vide,
  `encryptedContent` présent, donc **`isLocked == true`**.
- `_note` d'un écran est l'**éphémère déchiffrée** (`clearEncrypted: true`),
  donc **`isLocked == false`**.

Les confondre coûte dans les deux sens, et les deux se sont produits : un
vrai bug (`isVaultExit` testait `encryptedContent` sur `_note`, condition
toujours fausse, dialog destructif jamais affiché depuis la v1.1.0) et un
faux positif de Gemini. **Avant de juger un `isLocked`, remonter à l'origine
de l'objet.** Le signal qui survit au déchiffrement est `_wasLocked`.

---

## 6. Ce qui reste ouvert

- **La CI n'exécute pas les tests sur appareil.** Ils ne tournent que lancés à
  la main ; il faudrait un job avec émulateur. La garde repose sur la
  discipline, pas sur l'outillage.
- **Aucune action « retirer la protection d'un coffre »** sans supprimer le
  dossier. Manque réel, à traiter avec le même avertissement que la sortie de
  coffre — c'est un nouveau chemin qui déchiffre tout.
- **Taille de l'APK** : 127 Mo sur arm64 après retrait de 93 Mo de libs
  MediaPipe inutilisées. Le reste (moteur Gemma 51 Mo + modèle MiniLM 23 Mo)
  est livré à 100 % des utilisateurs alors que le chat IA exige un modèle
  importé à la main. Non compressible sans livraison à la demande, laquelle
  suppose Play Feature Delivery — hors de portée pour F-Droid et GitHub.
- **Historique Git jamais scanné** pour des secrets (point ouvert du
  CLAUDE.md §9.3, antérieur à cet audit).

---

## 7. Danger opérationnel découvert

`flutter test integration_test` construit un `assembleDebug`. Face à un APK
de release déjà installé, l'installation échoue en
`INSTALL_FAILED_VERSION_DOWNGRADE` et Flutter **désinstalle la version en
place** — emportant les données. Constaté sur le S9 le 2026-08-07.

⇒ Ces tests ne se lancent que sur le S9 `22dbb7390a057ece`, avec `-d`
explicite. Jamais sur le S24 FE `RZCY41EGKYL`, qui porte les données réelles.
Sans `-d`, la commande vise **tous** les appareils connectés.
