# Politique de confidentialité — Notes Tech

**Version** : v0.7.0 — 2026-05-06
**Éditeur** : Files Tech / Patrice Haltaya
**Contact** : contact@files-tech.com

---

## TL;DR

Notes Tech ne collecte, n'envoie et ne stocke à distance **aucune
donnée personnelle**. L'application n'a même pas la permission Android
d'accéder à Internet (vérifiable dans `AndroidManifest.xml` :
`<uses-permission android:name="android.permission.INTERNET"
tools:node="remove" />`).

---

## 1. Données collectées

**Aucune.** Notes Tech ne possède aucun serveur. Aucune télémétrie,
aucun crash reporter tiers (Firebase, Sentry, Crashlytics), aucun
identifiant publicitaire, aucune mesure d'audience.

## 2. Données stockées localement

Sur votre téléphone, dans la zone privée de l'application
(`/data/data/com.filestech.notes_tech`, inaccessible aux autres apps
par les garanties d'isolation Android) :

- Titres et contenus de vos notes
- Vos paramètres (thème, tri, dossier actif, etc.)
- Modèles IA importés par vous (Gemma, Whisper)
- Embeddings sémantiques de vos notes (dérivés du contenu, ne sortent
  jamais de l'appareil)

La base de notes est **chiffrée AES-256** (SQLCipher) avec une clé
maître scellée par l'**Android Keystore** (hardware-backed sur
téléphones modernes). La désinstallation efface cette clé : sans elle,
la base devient cryptographiquement illisible.

## 3. Modèles d'intelligence artificielle

Vous les téléchargez vous-même depuis les sources officielles :

- **Gemma 3 1B int4** — Google Kaggle (licence Gemma)
- **Whisper** — HuggingFace ggerganov/whisper.cpp (MIT)

Notes Tech vérifie l'empreinte cryptographique SHA-256 de chaque
modèle avant chargement. Aucun modèle n'est envoyé à l'éditeur ni à un
service tiers.

## 4. Permissions Android

- **`RECORD_AUDIO`** — demandée au premier appui sur le bouton micro
  de la dictée vocale. Refusable, révocable à tout moment depuis les
  paramètres système. L'audio capturé reste dans la zone tmp privée
  de l'app et est supprimé immédiatement après transcription.

Aucune autre permission. Notamment **pas de** : `INTERNET`,
`ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS`,
`READ_EXTERNAL_STORAGE`. Les imports de fichiers passent par le
Storage Access Framework (SAF) qui ne nécessite pas de permission
globale.

## 5. Mode panique

Notes Tech intègre un **mode panique** (Réglages → Mode panique →
Tout effacer maintenant). Une fois confirmé par la saisie du mot
`EFFACER`, ce mode :

1. Détruit la clé maître Keystore (DB instantanément illisible)
2. Écrase et supprime le fichier de base de données
3. Désinstalle les modèles IA
4. Vide toutes les préférences
5. Purge les fichiers temporaires

Aucune sauvegarde, aucune récupération possible. Action irréversible
prévue pour les utilisateurs en situation de fouille / contrainte
physique.

## 6. Vos droits

Vous gardez la pleine maîtrise de vos données.

- **Droit d'accès** : vos notes sont sur votre téléphone, consultables
  à tout moment dans l'app.
- **Droit à l'effacement** : désinstallez l'application, ou utilisez le
  mode panique pour un effacement immédiat.
- **Droit à la portabilité** : Réglages → Exporter mes données — ZIP
  Markdown avec frontmatter YAML compatible Obsidian, Logseq, Bear.
- **Droit à la rectification** : édition libre dans l'app.

## 7. Mises à jour de cette politique

Cette politique évolue avec les versions de l'app. La version courante
est consultable dans Réglages → À propos → Voir les mentions légales,
ou sur https://www.files-tech.com/notes-tech.php.

---

**Apache License 2.0** — code source intégral disponible :
https://github.com/gitubpatrice/notes_tech
