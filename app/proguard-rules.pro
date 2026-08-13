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
# ⚠️ R8 supprime les appels Timber ET `android.util.Log` en release. Pour instrumenter un problème
# qui ne se reproduit qu'en build signée, le seul canal qui survit est `println` — cf. les notes
# de SMS Tech. Ne pas ajouter de `-keep` sur Timber pour contourner ça : ce serait garder du code
# de journalisation dans une application dont l'argument est de ne rien divulguer.
