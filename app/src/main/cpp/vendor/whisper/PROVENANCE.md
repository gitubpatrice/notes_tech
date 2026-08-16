# D'où vient ce répertoire, et ce qu'on n'en a pas pris

> ⚠️ **Code tiers. Ne le modifiez pas.** Un correctif appliqué ici serait perdu à la première mise à
> jour, et invisible d'ici là. S'il faut vraiment dévier de l'amont, la déviation se documente dans
> ce fichier, en tête, avec sa raison — pas dans le code.

## Identité

| | |
|---|---|
| Projet | **whisper.cpp** (`ggerganov/whisper.cpp`) et **ggml** |
| Version | **1.8.3** — `include/whisper-version.h` |
| Licence | **MIT** — texte complet dans `LICENSE`, à côté de ce fichier |
| Copié le | 2026-08-16 |
| Copié depuis | `J:/Pub/Cache/hosted/pub.dev/whisper_ggml_plus-1.5.2/android/src/whisper/src/` |
| Volume | 87 fichiers, **3,8 Mo** |

⚠️ **La source de la copie est le cache pub d'un greffon Flutter, pas le dépôt amont.** C'est ce qui
était disponible hors ligne, et ce sont les mêmes fichiers que l'application publiée exécute
aujourd'hui — donc le code exact dont on sait qu'il fonctionne sur les appareils de test. Le jour où
il faudra une mise à jour, la récupérer depuis l'amont directement et comparer.

## Ce qui a été pris

    ggml/        le moteur de calcul (74 fichiers, 3,4 Mo)
    src/         le moteur whisper proprement dit (11 fichiers, 405 Ko)
    include/     whisper.h et whisper-version.h

## 🔴 Ce qui a été délibérément LAISSÉ

| Écarté | Poids | Pourquoi |
|---|---|---|
| `main.cpp` / `main.h` | — | La couche FFI **Dart** du greffon : une fonction `request(char*)` qui prend et rend du JSON. Notre pont est en **JNI typé**, écrit dans `../../notes_stt_jni.cpp`. Reprendre le JSON aurait imposé le point suivant. |
| `json/` | 888 Ko | Un analyseur JSON qui n'existait que pour cette API. Sans elle, il n'a plus d'objet. |
| `examples/dr_wav.h` | 356 Ko | Un lecteur WAV tiers (Copyright 2023 David Reid). **Le WAV est décodé en Kotlin**, par `WavPcm16` — déjà écrit, déjà testé octet par octet, et qui n'accepte que le seul format que la capture produit. Un analyseur de format généraliste, en C, sur un fichier venu du disque, c'est une surface d'attaque pour un besoin qu'on n'a pas. |
| `coreml/` (racine) | 0 | Vide. |
| `src/whisper.h` | 35 Ko | **Doublon exact** de `include/whisper.h` — vérifié par `diff`. Une seule copie est conservée ; deux en-têtes identiques finissent par diverger. |

Total écarté : **1,3 Mo**, soit un quart de ce qui était disponible.

### Ce qui est là sans jamais être construit

`src/coreml/` — huit fichiers Objective-C, compilés **uniquement** si `WHISPER_COREML` est activé,
ce que notre CMake force à `OFF` et qui n'a aucun sens hors des plateformes Apple. Ils **restent**,
et c'est délibéré : la règle de tête de ce fichier — *ne pas modifier le code tiers* — vaut aussi
pour ce qui semble inutile. Un sous-arbre amputé se remarque à la première mise à jour, et rend
chaque comparaison avec l'amont plus coûteuse que les 60 Ko qu'on aurait gagnés.

## Comment il est construit

Par notre propre `app/src/main/cpp/CMakeLists.txt`, **pas** par celui du greffon. Le sien produisait
une bibliothèque nommée `whisper` exposant l'API Dart ; le nôtre produit `notes_stt` et n'expose que
le pont JNI. Les `CMakeLists.txt` internes des sous-arbres `ggml/` et `src/`, eux, sont ceux de
l'amont et ne sont pas touchés.

## Ce qu'il faut vérifier après toute mise à jour

1. `include/whisper-version.h` — et reporter la version dans ce fichier.
2. Les en-têtes de copyright : `grep -rh "Copyright" .` — si un nouveau nom apparaît, il rejoint
   `LICENSE`.
3. Que l'API utilisée par `notes_stt_jni.cpp` existe toujours à l'identique : `whisper_init_from_file_with_params`,
   `whisper_full`, `whisper_full_n_segments`, `whisper_full_get_segment_*`, `whisper_free`. Le
   compilateur le dira, mais un changement de **sémantique** ne se compile pas moins bien.
4. Que rien n'a réintroduit une dépendance réseau. `grep -rn "socket\|curl\|http" .` doit rester
   silencieux hors commentaires — l'application promet publiquement de n'avoir aucune permission
   réseau, et cette promesse porte aussi sur ce qu'elle embarque.
