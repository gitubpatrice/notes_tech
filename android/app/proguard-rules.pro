# Notes Tech — Règles ProGuard / R8
# Conservation des points d'entrée Flutter et plugins critiques.

# Flutter
-keep class io.flutter.app.** { *; }
-keep class io.flutter.plugin.** { *; }
-keep class io.flutter.util.** { *; }
-keep class io.flutter.view.** { *; }
# ⚠️ Deux classes sont EXCLUES de ce -keep, et c'est intentionnel.
#
# `io.flutter.embedding.engine.deferredcomponents.PlayStoreDeferredComponentManager`
# porte un champ `SplitInstallManager` et une méthode `onStateUpdate(SplitInstallSessionState)`
# dont les types viennent de `com.google.android.play.core`. Avec `{ *; }`, R8 conservait
# ces membres, donc écrivait leurs descripteurs de types dans le dex — et le scanner
# F-Droid (`fdroid scanner`, `scan_binary()`) moissonne la sortie de `dexdump` à la regex :
# il ne distingue pas un descripteur mort d'une classe embarquée. D'où six « classes
# non libres » signalées sur la MR fdroiddata!37885 pour du code que rien n'atteint.
#
# Ce n'était PAS un artefact excluable : `exclude(group = "com.google.android.play")`
# ne changeait rien, Play Core n'ayant jamais été une dépendance. Seule cette règle
# retenait les références.
#
# Retrait sûr : l'app ne déclare aucun `deferred-components`, ne référence ni
# SplitInstall ni SplitCompat, et son manifeste fusionné résout
# `android:name` en `android.app.Application` — ni FlutterApplication ni
# FlutterPlayStoreSplitApplication ne sont utilisées. Les méthodes natives (JNI Whisper)
# restent protégées par `-keepclasseswithmembernames ... native <methods>` hérité de
# proguard-android-optimize.txt.
-keep class !io.flutter.embedding.engine.deferredcomponents.**,!io.flutter.embedding.android.FlutterPlayStoreSplitApplication,io.flutter.** { *; }
-keep class io.flutter.plugins.** { *; }
-dontwarn io.flutter.embedding.**

# Garde anti-régression : si une montée de Flutter, un nouveau plugin ou un
# `-keep` trop large refait entrer ces deux classes, le build ÉCHOUE ici plutôt
# que de repartir en revue F-Droid des mois plus tard. `-checkdiscard` vérifie à
# CHAQUE build que R8 les a bien jetées — contrairement à un contrôle manuel qui
# ne se retente jamais.
# ⚠️ Viser les deux classes NOMMÉMENT, pas le package : l'interface
# `DeferredComponentManager` du même package est légitimement conservée, car
# `FlutterInjector`, `FlutterJNI` et `DeferredComponentChannel` exposent des
# membres de ce type. Un `-checkdiscard` sur le package entier échouerait donc
# à tort. Cette interface est du `io.flutter`, pas du Play Core : elle ne
# déclenche rien côté scanner.
-checkdiscard class io.flutter.embedding.engine.deferredcomponents.PlayStoreDeferredComponentManager
-checkdiscard class io.flutter.embedding.android.FlutterPlayStoreSplitApplication

# Kotlin
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# AndroidX (déjà couvert par defaults, ceinture)
-dontwarn androidx.**

# sqflite
-keep class com.tekartik.sqflite.** { *; }

# ⚠️ Les règles MediaPipe / flutter_gemma / background_downloader /
# onnxruntime ont été RETIRÉES avec l'IA embarquée (v1.1.7). Ces classes
# n'existent plus dans le build : un `-keep` sans cible est silencieux, il
# aurait survécu indéfiniment en donnant l'illusion d'une protection utile
# et en masquant le jour où une vraie règle manquerait.

# files_tech_voice (Whisper natif via JNI) — appels dynamiques, ne pas
# stripper ni obfusquer.
-keep class com.filestech.files_tech_voice.** { *; }
-dontwarn com.filestech.files_tech_voice.**

# flutter_markdown (rendu PRIVACY/TERMS .md sur mentions_legales)
-keep class io.flutter.plugins.flutter_markdown.** { *; }

# package:cryptography est Dart pur — pas de règle ProGuard nécessaire.

# Apache Tika / XML — référence présente via dépendance transitive
# (ne pas tirer XMLStreamException qui n'est pas utilisé en runtime).
-dontwarn javax.xml.stream.**
-dontwarn org.apache.tika.**

# Préserver Throwable.getMessage utilisé par notre couche d'erreur Dart
-keepattributes Exceptions, InnerClasses, Signature, Deprecated, SourceFile, LineNumberTable, *Annotation*
