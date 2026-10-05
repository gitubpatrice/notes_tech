# ─────────────────────────────────────────────────────────────────────────────
# Notes Tech — règles R8
#
# Principe : on ne garde QUE ce qui est réellement atteint par réflexion ou par JNI. Une règle
# `-keep` trop large est une régression silencieuse — elle gonfle l'APK et, sur ce portefeuille,
# elle a déjà fait échouer un contrôle F-Droid en retenant des classes qui n'auraient jamais dû
# survivre (cf. Notes Tech 2.0.2, descripteurs Play Core retenus par `-keep class io.flutter.**`).
# ─────────────────────────────────────────────────────────────────────────────

# ── SQLCipher ────────────────────────────────────────────────────────────────
# Chargé par JNI depuis `System.loadLibrary("sqlcipher")` : le code natif résout ces noms de
# classes et de méthodes par chaîne de caractères, R8 ne peut donc pas les renommer.
#
# L'artefact embarque probablement ses propres `consumer-rules`. Cette règle est explicite quand
# même : une dépendance JNI dont la survie repose sur les règles d'un tiers est une régression qui
# attend un bump de version.
-keep class net.zetetic.database.** { *; }
#
# ⚠️ PAS de `-keep class net.sqlcipher.**` : ce paquet appartient à l'ancien artefact
# `android-database-sqlcipher`, qui n'est pas une dépendance de ce projet. Une règle sur un paquet
# absent ne protège rien et laisse croire que la question est traitée.

# ── Bouncy Castle ────────────────────────────────────────────────────────────
# Rien à retenir, et c'est le point : `Argon2BytesGenerator` est appelé DIRECTEMENT depuis le code
# Kotlin, donc R8 le conserve par analyse d'atteignabilité, avec tout ce qu'il utilise.
#
# Les `-keep ... { *; }` qui figuraient ici étaient inutiles et contredisaient la règle énoncée en
# tête de ce fichier. Pire : `{ *; }` interdit à R8 de renommer et d'élaguer les membres, donc ils
# faisaient grossir l'APK pour zéro bénéfice. Ne rien écrire est ici la bonne réponse.
#
# Ne garder QUE ce qui est atteint par réflexion ou par JNI — Argon2 n'est ni l'un ni l'autre.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# ── Contrôle de non-régression ───────────────────────────────────────────────
# `-checkdiscard` fait ÉCHOUER le build si la classe survit alors qu'elle devrait disparaître.
# C'est la contre-mesure au piège qui a coûté une MR F-Droid : une règle `-keep` trop large ne se
# voit pas, sauf si on demande explicitement à R8 de prouver la suppression.
-checkdiscard class org.bouncycastle.jce.provider.BouncyCastleProvider

# ── Room ─────────────────────────────────────────────────────────────────────
# Room génère son implémentation à la compilation ; rien à retenir par réflexion. Les avertissements
# portent sur des chemins Kotlin/Native et Paging que ce projet n'utilise pas.
-dontwarn androidx.room.paging.**

# ── Kotlin / Coroutines ──────────────────────────────────────────────────────
-dontwarn kotlinx.coroutines.**
-dontwarn org.jetbrains.annotations.**

# ── Désucrage ────────────────────────────────────────────────────────────────
-dontwarn java.lang.invoke.**
-dontwarn **$$serializer

# ── Diagnostic en release ────────────────────────────────────────────────────
# ⚠️ Corrected on 2026-09-26 (security audit, note of cell 5): this said R8 REMOVES the Timber and
# `android.util.Log` calls in release. It does not — no `-assumenosideeffects` rule here. What keeps
# release silent is that no Timber tree is planted there (`NotesTechApplication`, `LOG_ENABLED`), and
# that the code calls `android.util.Log` nowhere. Keep it that way; do not plant a tree in release.

# ── Dictée : la frontière JNI ────────────────────────────────────────────────
# 🔴 **Le code natif cherche ces méthodes PAR LEUR NOM**, décoré depuis le nom du paquet, de la
# classe et de la méthode (`Java_com_filestech_notes_1tech_data_voice_WhisperNatif_ouvrir`). R8 ne
# voit aucun appelant Java à une méthode `native` déclarée sans corps : il est donc parfaitement
# fondé à la renommer — et rien ne casse à la compilation. L'échec arrive à l'exécution, en release
# seulement, sous la forme d'un `UnsatisfiedLinkError` au premier usage de la dictée.
#
# ⚠️ La classe ET ses méthodes natives, pas seulement la classe : renommer `ouvrir` suffit à tout
# casser. `-keepclasseswithmembernames` conserve les noms des membres natifs et de leur classe.
-keepclasseswithmembernames,includedescriptorclasses class com.filestech.notes_tech.data.voice.WhisperNatif {
    native <methods>;
}

# ⚠️ Aucun `-keep` sur `WhisperStt` : elle est atteinte par Hilt, donc par des appelants réels, et
# R8 sait la suivre. Un `-keep` de plus ici serait du bruit qui masquerait le seul qui compte.
#
# 🔴 **ÉTAT AU 2026-08-16, à revérifier dès que l'interface de dictée existera.** Mesuré sur l'APK
# release : `WhisperNatif` et `WhisperStt` sont **absentes des dex**. Ce n'est pas un défaut de la
# règle ci-dessus — `-keepclasseswithmembernames` conserve les *noms*, il n'empêche pas la
# suppression — mais la conséquence du fait qu'**aucun écran n'appelle encore la dictée** : R8 a
# retiré la chaîne entière, jusqu'à la liaison Hilt.
#
# Deux conséquences à connaître :
#   1. la règle est aujourd'hui **sans effet observable**. Elle ne sera réellement éprouvée qu'une
#      fois un appelant en place. Contrôle à refaire alors, sur l'APK **release**, pas sur debug :
#      les tests instrumentés tournent sur une build non minifiée et ne prouvent rien là-dessus ;
#   2. `libnotes_stt.so` est **empaquetée quand même** — 2,5 Mo par architecture — parce que le
#      découpage des bibliothèques natives ne passe pas par R8. Une release faite aujourd'hui
#      embarquerait donc du code natif que rien ne peut atteindre.
