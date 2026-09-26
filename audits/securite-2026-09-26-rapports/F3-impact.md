# F3 — angle IMPACT (Sonnet) — verdict : FAUX POSITIF (reçu 2026-09-26 au soir)

Confirmé par lecture : réglage protégé par défaut (`AppSettings.kt:112`) ; passage chargement → clair en une
affectation (`NoteEditorViewModel.kt:819-827`, spinner seul avant, `NoteEditorScreen.kt:553`) ; `force()` dans
la même passe que le texte (`SecureWindowGuard.kt:54-59`) ; propagation par `MutableStateFlow` → `combine`
→ collecte dans `NotesTechApp` (`MainActivity.kt:157-162`) → `FenetreProtegee` (`:265-277`).

Non confirmé, et décisif : qu'une image **réellement affichée** sépare le rendu du clair de la pose du
drapeau. Question de planification (Recomposer, `AndroidUiDispatcher`), pas de lecture de source ; le
chercheur la laisse lui-même ouverte. Sans capture image par image sur appareil, pas d'« opération
dangereuse atteinte » citable. S'il était mesuré réel : FAIBLE au plus (réglage désactivé exprès, capture
active au moment d'ouvrir une note de coffre, quelques millisecondes).

**Décompte** (panel réduit, règle standard) : défenses vrai / impact faux → **1 sur 2 : non retenu**, à
mesurer (limites du rapport).
