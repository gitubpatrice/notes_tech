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

    // Splits ABI : un APK par architecture (arm64-v8a / armeabi-v7a / x86_64),
    // au lieu d'un universel embarquant les 3. Notes Tech embarque sqlcipher,
    // ONNX Runtime, Whisper natif, MediaPipe — réduit drastiquement la taille
    // d'APK livré par device (~30-50 Mo gagnés sur S9 / POCO C75).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            // P2 v1.1.0 — `isUniversalApk = false`. Avant : générait un 4ᵉ
            // APK universel ~294 Mo embarquant les libs natives des 3 ABIs
            // (sqlcipher + ONNX + Whisper + MediaPipe), upload GitHub +
            // bandwidth user pour rien (un seul ABI utile par device).
            // Désormais : 3 APKs par-ABI seuls. Distribution via GitHub
            // Releases (split-per-abi cohérent) ; pas de Play Store/AAB.
            isUniversalApk = false
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
