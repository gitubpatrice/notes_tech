# Notes Tech 3.3.0 — archives, sélection multiple, curseur à l'ouverture

> Écrit le 2026-11-09. Origine : issue #48 (koad1x, 2026-11-08) — trois suggestions. Décision de
> Patrice le même jour : « fais les 3 maintenant pour la version 3.3.0, proprement, avec vigilance »,
> puis, à ses questions : appui long = sélection, archive hors de « Toutes les notes », recherche sans
> archives, et trois ajouts (archiver depuis l'éditeur, couleur et épingle/favori en lot).

## 0. Les trois demandes

1. **Curseur et clavier dans le corps d'une nouvelle note** — jusqu'à la 3.2.2, une note vide s'ouvrait
   côté écriture sans rien de focalisé (choix repris de la 2.0.9, qui n'avait pas d'`autofocus`).
2. **Archiver des notes** — la colonne `archived` existait depuis la base Flutter, `setArchived` aussi
   (sans appelant), et les filtres `archived = 0` partout sauf « Toutes les notes ». Aucune version,
   Flutter comprise, n'a jamais écrit `archived = 1` (vérifié dans `lib/` de la 2.0.9) : aucune note
   n'est donc cachée chez un utilisateur.
3. **Sélectionner plusieurs notes** pour archiver, supprimer ou déplacer.

## D-036 — L'archive est une vue de l'accueil ; une note archivée n'est plus que là

- **Revoit la décision du 2026-08-18** (`05-PARITE.md` §2) : « Toutes les notes » gardait les archives
  parce qu'aucun écran ne les montrait — les filtrer partout les aurait rendues introuvables. La 3.3.0
  donne l'écran ; la raison tombe. Une note archivée quitte son dossier, « Toutes les notes », les
  favoris, les récentes et la recherche (Patrice : recherche sans archives, comme la 2.0.9).
- **Une vue de l'accueil, pas un écran** : entrée « Archives » du tiroir, au-dessus de la Corbeille ; même
  liste, même tri, même sélection, même carte. Ni champ de recherche (il chercherait hors archives), ni
  « Nouvelle note » (la note créée ne serait pas archivée, donc invisible ici).
- `NoteDao.observeOutOfTrash(sort, archived)` sert les deux listes ; `listAllAlive` (export, sauvegarde,
  statistiques) garde les archives.
- Une note archivée mise à la corbeille n'est que dans la corbeille, et revient archivée.
- Archiver écrit **un seul drapeau** (`updateFlags`), comme l'épingle : ni contenu ni bloc scellé.
- **Éditeur** : « Archiver » / « Désarchiver » dans le ⋮, à côté de « Déplacer » ; l'éditeur reste ouvert
  et le dit par un message (l'épingle se voit, l'archive non).

## D-037 — Appui long = sélection ; un geste écrit tout ou rien

- **L'appui long sélectionne** (usage Android, choix de Patrice) ; un toucher en sélection ajoute ou
  retire ; Retour sort de la sélection. La feuille d'actions de la 3.1.0 disparaît : ses gestes passent
  dans la barre de sélection — Archiver, Déplacer, Corbeille ; dans le ⋮ : Tout sélectionner, Éditer
  (une seule note), Épingler, Favori, Couleur, Supprimer définitivement (en rouge, en dernier).
- **La sélection montrée est celle sur laquelle on agit** : `HomeUiState.selection` est la sélection
  coupée aux notes de la liste ; changer de dossier ou passer à l'archive la vide. Sauvegardée
  (`SavedStateHandle`), comme les questions ouvertes (`GestesDeNoteEnCours.Sauvegarde`).
- **Coffres — mêmes règles qu'une note seule** : une note d'un coffre fermé ne se sélectionne qu'après
  son secret (courtoisie, `GestesDeNoteEnCours.basculer`) ; **la protection est à l'exécution**
  (`HomeViewModel.executer`) : relecture fraîche, et une seule note protégée d'un coffre fermé retient
  **tout** le geste, repris après déverrouillage (un second coffre fermé demande son secret à son tour).
- **Déplacement** : coffre fermé en destination → son secret d'abord ; notes scellées qui sortent de leur
  coffre → la question de l'éditeur (`DialogueDeSortieDeCoffre`, déplacé dans `ui/common` pour qu'il
  n'y en ait qu'un), reposée par `NotesEnLot.moveToFolder` **dans** la transaction.
- **Tout ou rien** : `NotesEnLot` (nouvelle classe, `NotesRepository` dépassait le seuil detekt) passe
  chaque note par la fonction d'une note seule, dans **une** transaction Room (les transactions
  s'emboîtent). Un coffre qui se ferme en cours de route annule ce qui était écrit.
- **Messages** : une note dit ce qu'elle disait avant 3.3.0, mot pour mot (« Note mise à la corbeille »),
  plusieurs ont un pluriel (`selonLeNombre`). Annuler pour la corbeille et l'archive ; l'annulation
  d'un archivage ne remet que les notes que le geste avait changées. Épingle, favori, couleur se taisent,
  comme dans l'éditeur : la carte le montre.

## D-038 — Le curseur à l'ouverture côté écriture

- Une note qui s'ouvre côté écriture — vide, ou par « Éditer » de la sélection — reçoit le curseur **à la
  fin** du corps et le clavier, par le même chemin que le bouton « Éditer » de la lecture
  (`ChampDuCorps.focaliser`). Décidé une fois, avec le côté ; sauvegardé, pour qu'une rotation ne
  ramène pas le clavier.

## Contrôles

- Gate : compilation, ktlint, detekt, lint ; JVM (`GestesDeNoteEnCoursTest` réécrit) ; instrumentés sur
  le S9 : `GestesSurDesNotesTest` (vraie base SQLCipher, vrai coffre : refus et reprise, tout ou rien,
  sortie de coffre, destination fermée, archive scellée intacte), `AccueilTest`, `EditeurTest`,
  `TiroirDesDossiersTest`, `NotesRepositoryTest` (règle d'archive inversée, témoins avant).
- Textes : 14 chaînes et 10 pluriels × 5 langues ; registre Lei / usted ; `note_actions_label` retirée.
