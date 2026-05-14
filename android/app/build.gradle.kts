plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

import java.util.Properties
import java.io.FileInputStream

val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

android {
    namespace = "com.filestech.notes_tech"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "com.filestech.notes_tech"
        minSdk = maxOf(flutter.minSdkVersion, 23)
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        // FR + EN seulement (économie ressources Material/AndroidX strings).
        // Cohérent avec generate:true Flutter qui packe nos ARB.
        resourceConfigurations += listOf("fr", "en")
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                keyAlias = keystoreProperties["keyAlias"] as String?
                keyPassword = keystoreProperties["keyPassword"] as String?
                storeFile = keystoreProperties["storeFile"]?.let { rootProject.file(it as String) }
                storePassword = keystoreProperties["storePassword"] as String?
            }
        }
    }

    // P2 v1.1.0 — Splits ABI obtenus via CLI `flutter build apk --release
    // --split-per-abi`, PAS via un bloc `splits.abi {}` ici. Cause : depuis
    // Flutter 3.41 le SDK pose `ndk.abiFilters = [armeabi-v7a, arm64-v8a,
    // x86_64]` par défaut au niveau projet. Avoir EN PLUS un bloc
    // `splits.abi { include(...) }` déclenche au build :
    //   `Conflicting configuration : '...' in ndk abiFilters cannot be
    //    present when splits abi filters are set`
    // (cf. CI run `25856790750` v1.1.0 fail).
    //
    // Solution : on s'appuie uniquement sur la CLI `--split-per-abi` qui
    // produit les 3 APKs par-ABI sans toucher au gradle. Pas d'APK
    // universel (économie ~70 Mo upload GitHub Releases). Même hotfix
    // appliqué à Pass Tech v2.4.3 et Read Files Tech v2.13.1.

    bundle {
        abi {
            enableSplit = true
        }
        language {
            // Ne PAS splitter par langue : avec generate:true Flutter, les
            // ARB sont packagés et l'utilisateur peut switcher la langue
            // dans Settings indépendamment de la locale système.
            enableSplit = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Si key.properties absent, on laisse signingConfig à null :
            // assembleDebug compile (ne touche pas ce buildType), assembleRelease
            // échouera proprement plus tard ("no signing config"). Le throw au
            // config-time cassait `flutter build apk --debug` en CI car Gradle
            // évalue tous les buildTypes même quand on en assemble qu'un seul.
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                null
            }
        }
    }
}

flutter {
    source = "../.."
}
