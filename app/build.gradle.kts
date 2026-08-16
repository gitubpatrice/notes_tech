import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Version lue depuis `version.properties` : source unique, indépendante de l'historique git.
// Volontairement sans repli — cf. le commentaire en tête de ce fichier.
val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    require(f.exists()) { "version.properties est introuvable à la racine du projet." }
    f.inputStream().use { load(it) }
}
val appVersionCode: Int = requireNotNull(versionProps.getProperty("versionCode")?.trim()?.toIntOrNull()) {
    "versionCode absent ou non entier dans version.properties."
}
val appVersionName: String = requireNotNull(versionProps.getProperty("versionName")?.trim()?.ifBlank { null }) {
    "versionName absent ou vide dans version.properties."
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// ─────────────────────────────────────────────────────────────────────────────
// Prise de place de l'application installée — OPT-IN, jamais par défaut
//
// Tant que le portage n'est pas prouvé sûr, cette application ne doit PAS pouvoir remplacer la
// Notes Tech Flutter réellement installée. Sous son `applicationId` d'origine, une simple
// installation l'écraserait : même paquet, même sandbox, mêmes données.
//
// Par défaut, le paquet porte donc le suffixe `.next`. Conséquence structurelle, et c'est tout
// l'intérêt : le sandbox est différent, donc cette build **ne peut pas voir** la base de
// l'utilisateur, ni ses SharedPreferences, ni ses clés Keystore. L'isolation n'est pas une
// discipline à tenir, c'est une propriété du système.
//
// Le jour de la bascule (phase 8, après parité vérifiée) :
//     ./gradlew assembleRelease -Pnotestech.replaceInstalledApp=true
//
// ⚠️ Cette bascule est irréversible pour l'utilisateur : la build qui en sort s'installe PAR-DESSUS
// Notes Tech et ouvre ses données réelles. Ne la produire qu'une fois docs/05-PARITE.md complet.
// ─────────────────────────────────────────────────────────────────────────────
val replaceInstalledApp: Boolean =
    (project.findProperty("notestech.replaceInstalledApp") as String?)?.toBoolean() ?: false

val baseApplicationId = "com.filestech.notes_tech"
val effectiveApplicationId = if (replaceInstalledApp) baseApplicationId else "$baseApplicationId.next"

if (replaceInstalledApp) {
    logger.lifecycle(
        """
        ⚠️  BUILD EN PRISE DE PLACE — applicationId = $baseApplicationId
            Cet APK s'installe PAR-DESSUS la Notes Tech Flutter et ouvre les données réelles
            de l'utilisateur. À ne produire qu'après la phase 8 (parité vérifiée).
        """.trimIndent(),
    )
}

android {
    // Le `namespace` (paquet du code et de la classe R) reste celui de la version Flutter publiée
    // et ne bouge JAMAIS, indépendamment de l'`applicationId` : c'est lui qui détermine le nom des
    // classes, et donc les alias Keystore que `flutter_secure_storage` a dérivés du nom de paquet.
    // Cf. docs/01-DECISIONS.md D-007.
    namespace = "com.filestech.notes_tech"
    compileSdk = 36

    defaultConfig {
        // ⚠️ PAS `com.filestech.notes_tech` par défaut — voir le bloc « prise de place » ci-dessus.
        // Le suffixe `.next` isole cette build de l'application réelle, au niveau du système.
        applicationId = effectiveApplicationId

        // ⚠️ minSdk 24 — RELEVÉ sur le manifeste fusionné de la 2.0.3 publiée, pas choisi.
        // Le monter à 26 (le socle des autres apps Kotlin du portefeuille) exclurait de la mise à
        // jour les utilisateurs sous Android 7. Une mise à jour ne retire pas des appareils.
        minSdk = 24
        // Idem : la 2.0.3 cible déjà 36. Redescendre changerait le comportement de l'application
        // sur des appareils où elle tourne déjà.
        targetSdk = 36

        versionCode = appVersionCode
        versionName = appVersionName
        // Deux applications côte à côte dans le lanceur doivent être distinguables au premier coup
        // d'œil, sans quoi on finit par tester la mauvaise. Un placeholder de manifeste et non un
        // `resValue` : `app_name` est localisé, et une chaîne générée dans `values/` perd contre
        // `values-fr/` sur un téléphone français — le renommage serait silencieusement sans effet.
        manifestPlaceholders["appLabel"] = if (replaceInstalledApp) "Notes Tech" else "Notes Tech (Kotlin)"

        testInstrumentationRunner = "com.filestech.notes_tech.HiltTestRunner"
        vectorDrawables { useSupportLibrary = true }

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.incremental", "true")
            arg("room.generateKotlin", "true")
        }

        // ⚠️ Les mêmes trois architectures que le découpage par ABI plus bas. Deux listes qui
        // divergeraient produiraient un APK annonçant une architecture dont il ne porte pas la
        // bibliothèque native — et un plantage au premier chargement, sur ces appareils-là
        // seulement.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                // ⚠️ `c++_static` : la bibliothèque native est la seule de l'application à avoir
                // besoin de la STL. La lier statiquement évite d'embarquer `libc++_shared.so` et
                // la question de savoir qui, de deux bibliothèques, impose sa version.
                arguments += listOf("-DANDROID_STL=c++_static")
                // 🔴 `-DNDEBUG` retire les `assert` de ggml. Ils avortent le processus sur une
                // condition interne ; dans une application de prise de notes, une transcription
                // qui échoue doit rendre une erreur, jamais tuer l'application par laquelle
                // l'utilisateur accède à ses notes.
                cppFlags += listOf("-O3", "-DNDEBUG", "-fvisibility=hidden")
                cFlags += listOf("-O3", "-DNDEBUG")
            }
        }
    }

    // 🔴 **Le NDK est ÉPINGLÉ, et ce n'est pas de la prudence de principe.** SMS Tech a vu sa
    // reproductibilité refusée par F-Droid pour cette raison exacte : deux NDK différents produisent
    // des `.so` différents à partir des mêmes sources, alors que le `classes.dex`, lui, reste
    // identique. Le défaut est donc invisible à toute comparaison qui ne descend pas dans le binaire
    // natif. Changer cette valeur est un choix, pas une mise à jour de routine.
    ndkVersion = "27.0.12077973"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    androidResources {
        localeFilters += listOf("en", "fr")
    }

    signingConfigs {
        create("release") {
            // ⚠️ Le keystore DOIT être celui de `notes_tech`. Signer avec une autre clé romprait le
            // chemin de mise à jour de toutes les installations existantes — Android refuserait
            // l'installation par-dessus. Cf. docs/01-DECISIONS.md D-001.
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Le suffixe permet à debug et release de coexister. Sans le libellé distinct, ce sont
            // deux entrées « Notes Tech » identiques dans le lanceur, sans moyen de savoir laquelle
            // un test a réellement exercée.
            //
            // ⚠️ Le suffixe change l'applicationId, donc le sandbox : une build debug ne voit PAS
            // la base de la build release. C'est voulu — mais ça veut dire qu'un test de migration
            // sur base réelle doit être mené sur une build SIGNÉE, pas en debug.
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            isDebuggable = true
            buildConfigField("boolean", "LOG_ENABLED", "true")
            manifestPlaceholders["appLabel"] =
                if (replaceInstalledApp) "Notes Tech (debug)" else "Notes Tech (Kotlin debug)"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            buildConfigField("boolean", "LOG_ENABLED", "false")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // `java.time` n'est natif qu'à partir de l'API 26 ; le plancher est à 24.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                // Bouncy Castle publie des fichiers de signature dans son jar ; ils n'ont aucun
                // sens une fois le code réempaqueté dans un APK et font échouer la fusion.
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
        unitTests.all { test -> test.useJUnitPlatform() }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = true
    }

    // SQLCipher embarque du natif : le découpage par ABI réduit sensiblement la taille par
    // architecture. L'APK universel reste produit pour l'installation manuelle.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            // Kotlin 2.x : applique les annotations (qualifiers Hilt, @ApplicationContext) au
            // paramètre de constructeur ET à la propriété — l'opt-in recommandé qui éteint
            // l'avertissement de compatibilité KT-73255.
            "-Xannotation-default-target=param-property",
        )
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.sqlcipher.android)
    implementation(libs.bouncycastle.provider)

    implementation(libs.timber)

    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.mockk.android)
    kspAndroidTest(libs.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
