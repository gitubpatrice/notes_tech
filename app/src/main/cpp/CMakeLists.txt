# La bibliothèque native de la dictée : whisper.cpp, plus notre pont JNI.
#
# ⚠️ Ce fichier est le NÔTRE. Celui du greffon Flutter dont les sources proviennent produisait une
# bibliothèque `whisper` exposant une API Dart à base de JSON ; celui-ci produit `notes_stt` et
# n'expose que `notes_stt_jni.cpp`. Les `CMakeLists.txt` des sous-arbres `vendor/whisper/ggml` et
# `vendor/whisper/src` sont ceux de l'amont et ne doivent pas être touchés.
#
# Cf. `vendor/whisper/PROVENANCE.md` pour ce qui a été copié, et ce qui ne l'a pas été.

cmake_minimum_required(VERSION 3.22.1)

project(notes_stt LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(VENDOR "${CMAKE_CURRENT_LIST_DIR}/vendor/whisper")

# ── Ce que l'amont doit construire, et surtout ce qu'il ne doit pas ───────────────────────────────
#
# ⚠️ Ces valeurs sont celles du greffon dont viennent les sources : ce sont donc celles avec
# lesquelles ce code est réellement exécuté sur les appareils, et non une configuration devinée.
#
# 🔴 `GGML_NATIVE OFF` n'est pas une option de confort. À `ON`, ggml compile pour le processeur de
# la machine de build (`-march=native`) : la bibliothèque plante sur tout appareil au jeu
# d'instructions plus pauvre, et seulement là. Un défaut qui ne se voit jamais sur le poste qui l'a
# produit.
set(BUILD_SHARED_LIBS   OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_CPU            ON  CACHE BOOL "" FORCE)
set(GGML_NATIVE         OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP         OFF CACHE BOOL "" FORCE)
set(GGML_BLAS           OFF CACHE BOOL "" FORCE)
set(GGML_ACCELERATE     OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
# ⚠️ Les deux accélérateurs ci-dessous sont propres aux plateformes Apple et à Intel OpenVINO. Les
# laisser à `OFF` est ce qui garde `src/coreml/` hors du binaire.
set(WHISPER_COREML   OFF CACHE BOOL "" FORCE)
set(WHISPER_OPENVINO OFF CACHE BOOL "" FORCE)

add_subdirectory("${VENDOR}/ggml" "${CMAKE_CURRENT_BINARY_DIR}/ggml")
add_subdirectory("${VENDOR}/src"  "${CMAKE_CURRENT_BINARY_DIR}/whisper")

# ── Notre pont ───────────────────────────────────────────────────────────────────────────────────

add_library(notes_stt SHARED notes_stt_jni.cpp)

target_include_directories(notes_stt PRIVATE "${VENDOR}/include")

find_library(LOG_LIB log)
find_library(ATOMIC_LIB atomic)

target_link_libraries(notes_stt PRIVATE whisper ${LOG_LIB})
if (ATOMIC_LIB)
    # ⚠️ armeabi-v7a n'a pas toutes les opérations atomiques en matériel : sans cette bibliothèque,
    # l'édition de liens échoue sur cette seule architecture, et donc seulement pour les appareils
    # les plus anciens — ceux que `minSdk 24` sert précisément.
    target_link_libraries(notes_stt PRIVATE ${ATOMIC_LIB})
endif()

if (ANDROID)
    # 🔴 Alignement sur 16 Ko. Android 15 introduit des appareils dont les pages font 16 Ko et non
    # 4 Ko ; une bibliothèque alignée sur 4 Ko n'y CHARGE PAS. Le coût est nul sur les autres.
    target_link_options(notes_stt PRIVATE
        $<$<OR:$<STREQUAL:${ANDROID_ABI},arm64-v8a>,$<STREQUAL:${ANDROID_ABI},x86_64>>:LINKER:-z,max-page-size=16384>
        $<$<OR:$<STREQUAL:${ANDROID_ABI},arm64-v8a>,$<STREQUAL:${ANDROID_ABI},x86_64>>:LINKER:-z,common-page-size=16384>
    )
endif()
