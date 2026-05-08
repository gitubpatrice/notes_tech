# Notes Tech — Règles ProGuard / R8
# Conservation des points d'entrée Flutter et plugins critiques.

# Flutter
-keep class io.flutter.app.** { *; }
-keep class io.flutter.plugin.** { *; }
-keep class io.flutter.util.** { *; }
-keep class io.flutter.view.** { *; }
-keep class io.flutter.** { *; }
-keep class io.flutter.plugins.** { *; }
-dontwarn io.flutter.embedding.**

# Kotlin
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# AndroidX (déjà couvert par defaults, ceinture)
-dontwarn androidx.**

# sqflite
-keep class com.tekartik.sqflite.** { *; }

# MediaPipe (flutter_gemma) — appels JNI vers les libs natives, classes
# référencées dynamiquement, ne pas obfusquer/stripper.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# flutter_gemma plugin
-keep class dev.flutterberlin.flutter_gemma.** { *; }
-dontwarn dev.flutterberlin.flutter_gemma.**

# background_downloader (transitive de flutter_gemma — non utilisé mais
# conservé pour éviter un crash si une classe est touchée par réflexion).
-keep class com.bbflight.background_downloader.** { *; }
-dontwarn com.bbflight.background_downloader.**

# onnxruntime (recherche sémantique MiniLM)
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

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
