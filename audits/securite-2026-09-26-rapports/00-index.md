# Audit de sécurité ebee629 — index des rapports (reprise du 2026-09-26)

Copie figée : `scratchpad/audit-ebee629` (ebee629, arbre propre, vérifié le 2026-09-26).
Consignes d'hier : `scratchpad/prompts_audit_hier.txt` (26 dispatchs, texte exact).
Chaque rapport est écrit ici dès son arrivée. Nom : `<candidat>-<angle>.md` ou `celluleN-<angle>.md`.

## Rapports acquis

| Fichier | Origine |
|---|---|
| cellule1-stockage-cles.md | 2026-09-25, récupéré du journal (K1-K5, nommés C1-C5 dans le rapport) |
| cellule2-contrainte-verrouillage.md | 2026-09-25, récupéré du journal (V1, V2, nommés Constat 1 et 2) |

## Lancés le 2026-09-26 vers 10 h 45 (UTC), 18 agents

Cellules 3, 4, 5 ; vérificateurs V1, V2, K1, K2, K3 (accessibilité, impact, défenses) ; puis K4
accessibilité et impact (20 simultanés). Restent à lancer : K4 défenses, K5 (3), puis les candidats
des cellules 3-5.

Identifiants (transcriptions dans `../tasks/<id>.output` si la session tombe) :
a508ebb8d9cf2a4aa cellule3 · ade6157c1d45e5390 cellule4 · aa206c987b3b0102f cellule5 ·
a0b2f32db5cabebb8 V1-acc · a1b0fb78854ca2c7d V1-imp · a828f9ea43bfce870 V1-déf ·
a1b640671b099a8ec V2-acc · a41e97e557fc3172f V2-imp · aca9c69dcf9b68669 V2-déf ·
a669ca0daef77a0e0 K1-acc · ad39b0518f96fefbf K1-imp · ab08f65a94576bfb9 K1-déf ·
aab809f8223ab5496 K2-acc · ae11b8992c4ce32cb K2-imp · ab76d4c70f45c00c0 K2-déf ·
a7d8d405c72329e13 K3-acc · af619fd6d04eb7b89 K3-imp · a8063d3f983fbeb8f K3-déf ·
af9306fead6c8826e K4-acc · a9e4e0ee3d875f42d K4-imp

Vérification personnelle : `k3-perte-verification-personnelle.md` (pas de perte aujourd'hui).

Lancés ensuite : ad82af464a06cb1af K4-déf · ad1ca71e24a6f15fd K5-acc · a3beadb16e50d158b K5-imp ·
a4fe8980c00d65ab9 K5-déf.

Cellule 4 rendue (`cellule4-entrees-tiers.md`) : candidats E1, E2. Vérificateurs lancés :
a068dd9f682357e58 E1-acc · a0a7c5c889840efcd E1-imp · a923aee535802ac68 E1-déf ·
a272f8a2f90b04065 E2-acc · af24a3365b054a3c7 E2-imp · ad75961dcc0052cc2 E2-déf.
Notes hors angle de la cellule 4 (pas des candidats) : relais d'annulation de la dictée inopérant
(`WhisperStt.kt:143-150`) ; `EmojiCompatInitializer` au manifeste fusionné (requête de police aux
services Google au démarrage ; promesse « aucune permission Internet » intacte).

Cellule 5 rendue (`cellule5-fuites.md`) : F1 (MOYEN), F2, F3, F4 (FAIBLE). Vérificateurs :
F1 a3653a0273c035741 acc · a4cc99034c6763963 imp · a973701ec56c5e5d9 déf ;
F2 adfd5682e331f816c · a7c40f147d98e7ff1 · ac5451b85e88675e4 ;
F3 a0e1cbf216c4154ad · a6c32d9c56e947ae5 · a1006435d49068714 ;
F4 a776e4c024f457070 · a6a2bf67563cc529e · acfc9add262394131.
Cellule 3 rendue (`cellule3-suppression-purge.md`) : P1 (MOYEN), P2, P3, P4 (FAIBLE). Vérificateurs :
P1 a7b197f9a2d27bb0b acc · abc77129980afec3b imp. **File d'attente** (20 simultanés) : P1 déf,
P2 ×3, P3 ×3, P4 ×3 — consignes prêtes dans `01-file-attente.md`.

## Décompte (tenu ici, pas chez les vérificateurs ; profond = 2 sur 3 pour garder)

| Candidat | Accessibilité | Impact | Défenses | Décision |
|---|---|---|---|---|
| V1 | VP, MOYEN | VP, MOYEN | VP, MOYEN | **RETENU, MOYEN** (3/3) |
| V2 | VP, FAIBLE | VP, FAIBLE | VP, FAIBLE | **RETENU, FAIBLE** (3/3) — délai « immédiat » par défaut = obstacle, pas réfutation |
| K1 | VP, MOYEN (FTS5 ; liens seuls FAIBLE) | VP, MOYEN | VP, MOYEN | **RETENU, MOYEN** (3/3) — note_links : ne survit PAS à l'effacement (cascade) |
| K2 | VP, FAIBLE | VP, FAIBLE | VP, FAIBLE | **RETENU, FAIBLE** (3/3) |
| K3 | VP, MOYEN | **FP** (au plus FAIBLE) | VP, MOYEN | **RETENU (2/3), gravité FAIBLE** : le vérificateur d'impact la baisse, on prend le plus bas ; correctif = documenter, NE PAS lier la clé |
| K4 | VP, MOYEN | VP, MOYEN | VP, MOYEN | **RETENU, MOYEN** (3/3) |
| K5 | VP, FAIBLE (gain « coffre » à retirer) | **FP** | VP, FAIBLE | **RETENU, FAIBLE** (2/3) — gain = seulement la réutilisation du PIN hors de l'app (code d'écran), pas un coffre |
| E1 | VP, FAIBLE | **FP** (« informatif » : aucun effet, B ne gagne rien) | VP, FAIBLE (corriger l'intent, pas seulement la l. 190) | **RETENU, FAIBLE** (2/3) — plancher ; gain = plantage et écran d'ouverture |
| E2 | **FP** (capacité de lancement en arrière-plan non établie ; défaut de conception réel) | VP, FAIBLE | VP, FAIBLE | **RETENU, FAIBLE** (2/3) — dépend de la plateforme (API 24-28 ou superposition), non mesuré |
| F1 | … | VP, **FAIBLE** (abaissé ; flux internes couper/coller) | … | |
| F2 | … | … | … | |
| F3 | … | … | … | |
| F4 | VP, FAIBLE (Compose 1.11.3 n'expose même pas le drapeau) | VP, FAIBLE (fragments non attribuables) | VP, FAIBLE | **RETENU, FAIBLE** (3/3) — inclure la feuille de lien |
| P1 | VP, MOYEN (copie share_plus/ du dernier partage 2.x) | VP, **FAIBLE** (un fichier, choisi par l'utilisateur) | VP, MOYEN (copie figée indéfiniment par la 3.0.0) | **RETENU (3/3), FAIBLE** — le plus bas des votes, règle du skill |

**Coupure de quota vers 16 h 55 (reprise après 17 h 30)** : échoués sans rapport, à relancer : F1 acc, F1 déf ; F2 ×3 ; F3 acc, F3 imp ; P2 ×3 ; P3 ×3 ; P4 ×3 (16). F3 déf reçu (VP, FAIBLE).
**Reprise, panel réduit à la demande de Patrice (« attention que ça consomme pas trop »)** : 6 agents
au lieu de 16. F1 (+ défenses) et F3 (+ impact) : règle standard, 2 votes vrais sur 2 pour garder.
F2, P2, P3, P4 : un seul vérificateur (trois angles), règle rapide. À écrire dans le rapport.
acdb2e0e9c5b191ab F1-déf · a3acbbad46523ea42 F3-imp · a879994e31943408f F2 · a194562cf7beb2cf5 P2 ·
ac75fa887c64d9c74 P3 · a001fba9e2bbd50f8 P4.
| P2 | (à lancer) | | | |
| P3 | (à lancer) | | | |
| P4 | (à lancer) | | | |
