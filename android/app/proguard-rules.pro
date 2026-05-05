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

# Préserver Throwable.getMessage utilisé par notre couche d'erreur Dart
-keepattributes Exceptions, InnerClasses, Signature, Deprecated, SourceFile, LineNumberTable, *Annotation*
