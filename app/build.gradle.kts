import com.android.build.api.variant.FilterConfiguration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // No `org.jetbrains.kotlin.android`: AGP 9 compiles Kotlin itself.
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
    // compileSdk 37 as in Agenda Tech and App Manager Tech: the AndroidX releases built for AGP 9
    // require it. targetSdk stays 36 (below), so the app's behaviour on the device does not change.
    compileSdk = 37

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

        // ATTENTION `ndk.abiFilters` a ete RETIRE, et son absence est voulue.
        //
        // Il repetait la liste du decoupage par ABI plus bas. AGP tolerait la redondance tant que
        // l'APK universel etait produit -- il avait besoin de savoir quoi y mettre. Des que
        // `isUniversalApk` est passe a false, la configuration a ete REFUSEE :
        //
        //     Conflicting configuration : 'armeabi-v7a,arm64-v8a,x86_64' in ndk abiFilters
        //     cannot be present when splits abi filters are set
        //
        // C'est `splits.abi.include(...)` qui fait autorite desormais, et il est seul : deux listes
        // qui divergeraient produiraient un APK annoncant une architecture dont il ne porte pas la
        // bibliotheque native, et un plantage au premier chargement sur ces appareils-la seulement.
        // Une seule liste ne peut pas diverger d'elle-meme.

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
        // ⚠️ This list FILTERS the APK: a language missing here has its `values-*` removed at build
        // time, without an error — the app would offer it and then speak English. It must be
        // `LocalePreference.LANGUES`, which `LanguesDeLApplicationTest` checks (Pass Tech lost its
        // German, Spanish and Italian this way, 2026-09-20: only `aapt2` on the APK showed it).
        localeFilters += listOf("en", "fr", "de", "es", "it")
    }

    bundle {
        language {
            // The app switches its own language (Settings): a bundle split by language would install
            // the phone's language only, and choosing another would fall back to English. Notes Tech
            // ships APKs today (GitHub, F-Droid); this keeps a future bundle honest (lint:
            // AppBundleLocaleChanges).
            enableSplit = false
        }
    }

    // Without this, AGP writes a "Dependency metadata" block into the APK signature, encrypted with a
    // key only Google holds: an opaque blob nobody else can read, which F-Droid's scanner rejects. It
    // only exists in a SIGNED APK, so an unsigned build never shows it. Same setting as SMS Tech and
    // Agenda Tech (found on every app of the portfolio on 2026-08-14).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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
    // architecture. A universal APK is produced too, since 2026-10-05 — see `isUniversalApk` below.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            // The universal APK is back (Patrice, 2026-10-05), with RANK 0: versionCode = base * 10.
            //
            // It had been removed on 2026-08-20 under the old `rank * 1000 + base` scheme, where it
            // carried rank 4 (4053), above every split: whoever installed it once could never take a
            // split again (2054 over 4053 is a downgrade), and the only way out, uninstalling,
            // destroys the Keystore alias with the database.
            //
            // Under `base * 10 + rank`, a version's codes stay below the next version's: universal
            // 5000 replaces 2.0.9 (4071-4073), and 5010 or 5011-5013 replace it in turn. Rank 0 rather
            // than 4: the universal never shadows a split; the one refused case — the universal of a
            // version over a split of the SAME version — has no reason to happen and loses nothing.
            //
            // GitHub releases only. F-Droid builds the three splits, one Builds block each.
            isUniversalApk = true
        }
    }
}

// -----------------------------------------------------------------------------
// Per-ABI versionCode: `base * 10 + ABI rank` — the scheme F-Droid requires.
//
// Three steps, each forced by a real constraint:
//
// 1. Until 2026-08-20 this block copied the offset of Flutter's `--split-per-abi`, `rank * 1000 +
//    base`, so that the 3.0.0 could replace the 2.0.4 installations (1052/2052/3052). A plain
//    `versionCode = 53` had failed on a device with INSTALL_FAILED_VERSION_DOWNGRADE (§121).
// 2. On 2026-09-13 F-Droid refused that scheme for Notes Tech (linsui, fdroiddata!37885): once
//    `fdroid rewritemeta` sorts the Builds, the codes of two versions interleave — 1055, 1056,
//    2055, 2056… — and `checkupdates` pairs the last three with the wrong ABI. Flutter 2.0.8 moved
//    to `base * 10 + rank` and jumped its base to 406, so that 4061 stays above 2.0.7's 4055.
// 3. Flutter 2.0.9 ships 4071/4072/4073. The 3.0.0 built by the step-1 block produced
//    1053/2053/3053, i.e. a DOWNGRADE for every installed user — refused by Android, and the only
//    way out (uninstalling) destroys the Keystore alias along with the database. Found on
//    2026-09-24, while the port had been idle for a month.
//
// The ranks are Flutter's own (`android/app/build.gradle.kts` in notes_tech), so that a given ABI
// keeps the same last digit across the switch, and the F-Droid recipe keeps `%c * 10 + 1/2/3`.
//
// ⚠️ `version.properties` stays the single source: the rank is applied to the OUTPUT only, and
// `BuildConfig.VERSION_CODE` keeps the base value — checked 2026-08-20, nothing reads it.
// -----------------------------------------------------------------------------
val rangsDAbi = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86_64" to 3)

// ⚠️ A rank must be a single digit, otherwise two ABIs of two consecutive bases collide: rank 10 of
// base N would equal rank 0 of base N + 1. Structural, so it cannot go stale.
require(rangsDAbi.values.all { it in 1..9 }) { "ABI ranks must be single digits: $rangsDAbi" }
// ⚠️ And distinct: two ABIs of the same rank would ship the same version code (GPT-5.6 review, 2026-09-25).
require(rangsDAbi.values.toSet().size == rangsDAbi.size) { "ABI ranks must be distinct: $rangsDAbi" }

// The universal APK's rank: below every split of the same version, above every code of the previous one.
val rangUniversel = 0
require(rangUniversel !in rangsDAbi.values) { "The universal rank must differ from every ABI rank" }

// ⚠️ A FLOOR, not a proof. 407 is the base of Flutter 2.0.9, the last release published when this
// was written (2026-09-14). It stops an accidental return to the old base 53; it cannot know about a
// Flutter release shipped since — the release checklist compares with the last published tag.
val derniereBaseFlutterPubliee = 407
require(appVersionCode > derniereBaseFlutterPubliee) {
    "versionCode = $appVersionCode is not above Flutter 2.0.9 ($derniereBaseFlutterPubliee): every " +
        "installed user would get INSTALL_FAILED_VERSION_DOWNGRADE."
}

androidComponents {
    onVariants { variante ->
        variante.outputs.forEach { sortie ->
            val abi = sortie.filters
                .find { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier
            // `getValue` et non `get` : une ABI ajoutee sans rang doit faire ECHOUER
            // la configuration, pas produire un APK silencieusement non installable.
            //
            // An output WITHOUT an ABI filter is the universal APK: rank 0, see `isUniversalApk`.
            // An ABI filter with no rank must still FAIL the configuration, not ship silently.
            val rang = if (abi == null) {
                rangUniversel
            } else {
                rangsDAbi[abi] ?: error("sortie d'ABI sans rang (abi=$abi) : lui en donner un dans rangsDAbi.")
            }
            sortie.versionCode.set(appVersionCode * 10 + rang)
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
    implementation(libs.androidx.biometric)

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
    implementation(libs.jetbrains.markdown)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
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
