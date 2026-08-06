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

    // Taille de l'APK — `flutter_gemma` 0.14.x tire tout le bundle MediaPipe
    // GenAI, y compris des tâches que Notes Tech n'appelle jamais. Mesuré sur
    // v1.1.6 : APK universel publié à 347 Mo, split arm64-v8a à 220 Mo, pour
    // une app de prise de notes.
    //
    // Ce qu'on retire et pourquoi c'est sûr ici :
    //   - `*_embedding_model_jni` (gemma, gecko) : les embeddings de Notes
    //     Tech viennent d'ONNX Runtime + MiniLM, embarqué séparément.
    //   - `*_vision_*`, `imagegenerator_gpu` : tâches vision et génération
    //     d'images, aucune surface dans l'app (permission caméra absente).
    //   - `text_chunker_jni` : le découpage RAG est fait par `RagService`.
    //   - `sqlite_vector_store_jni` : les vecteurs vivent dans notre propre
    //     table, pas dans le store MediaPipe.
    //   - `LiteRtWebGpuAccelerator` : back-end WebGPU, sans objet sur Android.
    //
    // ⚠️ `libLiteRtLm.so`, `libllm_inference_engine_jni.so` et
    // `libLiteRtGpuAccelerator.so` sont CONSERVÉS : c'est le moteur
    // d'inférence Gemma lui-même. Ne pas les ajouter à cette liste.
    //
    // ⚠️ Toute exclusion est un pari sur ce que MediaPipe charge en `dlopen`
    // à l'exécution. Vérifier sur appareil après chaque bump de
    // `flutter_gemma` : ouvrir le chat IA, charger un modèle, envoyer un
    // message. Un `UnsatisfiedLinkError` au logcat = remettre la lib.
    packaging {
        jniLibs {
            excludes += setOf(
                "**/libgemma_embedding_model_jni.so",
                "**/libgecko_embedding_model_jni.so",
                "**/libmediapipe_tasks_vision_jni.so",
                "**/libmediapipe_tasks_vision_image_generator_jni.so",
                "**/libimagegenerator_gpu.so",
                "**/libtext_chunker_jni.so",
                "**/libsqlite_vector_store_jni.so",
                "**/libLiteRtWebGpuAccelerator.so"
            )
        }
    }

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
