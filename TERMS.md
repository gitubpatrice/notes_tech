# Conditions générales d'utilisation — Notes Tech

**Version** : v0.9.11 — 2026-05-07
**Éditeur** : Files Tech / Patrice Haltaya
**Contact** : contact@files-tech.com

---

## 1. Licence

Notes Tech est publié sous **Apache License 2.0**. Vous pouvez
librement utiliser, modifier, redistribuer et compiler le code source.
Voir `LICENSE` à la racine du dépôt.

## 2. Gratuité

Notes Tech est **gratuit, sans publicité, sans abonnement, sans
freemium**. Aucune fonctionnalité n'est verrouillée derrière un
paiement. Aucune intention commerciale future n'est prévue.

## 3. Aucune garantie

Apache License 2.0 — section 7 : **« THE WORK IS PROVIDED "AS IS" »**.
L'auteur ne peut être tenu responsable de :

- La perte de notes (sauvegardez régulièrement via l'export Markdown).
- L'inadéquation à un usage particulier.
- Les bugs, plantages, ou comportements inattendus.
- Les conséquences du **mode panique** : action irréversible que vous
  déclenchez en pleine connaissance de cause.

## 4. Données utilisateur

Vous êtes le seul propriétaire et responsable de vos notes. Voir
`PRIVACY.md` pour le détail du traitement (résumé : aucune donnée ne
quitte votre téléphone, aucun serveur n'est impliqué).

## 5. Modèles d'intelligence artificielle

Les modèles ML (Gemma, Whisper, MiniLM) sont **importés par vous**
depuis les sources officielles. Vous êtes soumis aux licences de ces
modèles :

- **Gemma 3** — licence Gemma de Google (consultable sur Kaggle).
- **Whisper** — MIT (OpenAI / Georgi Gerganov).
- **MiniLM-L6-v2** — MIT (Microsoft).

Notes Tech ne redistribue aucun de ces modèles.

## 6. Limitations techniques

- **APK ~327 Mo** : plusieurs runtimes ML embarqués (SQLCipher,
  ONNX Runtime, MediaPipe, Whisper.cpp, MiniLM bundlé). Ce n'est pas
  une app légère.
- **Modèles séparés** : à télécharger soi-même (~700 Mo cumulés pour
  Gemma + Whisper). Compromis pour rester offline.
- **Pas de synchronisation entre appareils** : volontaire (offline-
  first). Utilisez l'export Markdown pour partager entre vos appareils
  manuellement.

## 7. Mode panique

Le mode panique (Réglages → Mode panique) **détruit irréversiblement**
toutes vos données : notes, modèles IA, préférences, clé maître. Cette
action est volontairement irrécupérable. Utilisez-le en pleine
connaissance de cause. Aucune réclamation ne sera recevable suite à
son déclenchement.

## 8. Mise à jour de ces conditions

Cette politique évolue avec les versions de l'app. Vous serez informé
des changements substantiels via les release notes du dépôt GitHub.

---

**Code source** : https://github.com/gitubpatrice/notes_tech
**Site éditeur** : https://www.files-tech.com
