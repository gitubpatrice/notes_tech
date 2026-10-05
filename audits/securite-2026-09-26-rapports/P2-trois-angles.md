# P2 — vérificateur unique, trois angles (Sonnet) — verdict : VRAI POSITIF, FAIBLE (reçu 2026-09-26 au soir)

**Gravité** FAIBLE, confiance moyenne-haute.

**Accessibilité** : attaquant du modèle (PanicService.kt:243-244) ; séquence sous `NonCancellable` (:316),
donc seule une interruption externe (extinction, batterie, processus tué) la coupe. Ordre vérifié :
KEK_DESTROY (:386) → EXPORTS_WIPE (:412) → VOICE_CAPTURES_WIPE (:423-427) → DB_WIPE (:430) →
VOICE_MODEL_WIPE (:437-439) → LEGACY_MODELS_WIPE (:443) → PREFS_CLEAR (:446-449) → CACHE_PURGE (:452).
Aucun écrivain de `.md`/`.zip`/`.wav` hors `cache/exports/` (NoteExporter.kt:113, :194, :343, :360) et
`cache/captures/` (VoiceCapture.kt:504-507, :526) : le clair de la 3.0.0 part tôt et au démarrage. Le reste
(`cache/share_plus/`, `.md`/`.zip` à la racine, `stt_capture_*.wav`) = clair hérité de Flutter (P1), atteint
seulement par `viderLeCache` (:586-594, `deleteRecursively` sans filtre, :588), jamais au démarrage.

**Impact** : interruption entre KEK_DESTROY et CACHE_PURGE → résidus hérités lisibles indéfiniment ;
contredit `PanicService.kt:256-267` et `raw-fr/privacy.md:50`. Portée : clair hérité seulement ; fenêtre
de quelques secondes.

**Défenses** : `estUnArtefactSensible` (:649-669) et `clairSurLeDisque` (:627-640) mesurent sans agir avant
le rang 12 ; l'écran d'échec (MainActivity.kt:240-244, strings :545-546) n'offre aucune reprise.
