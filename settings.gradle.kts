pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
// No `plugins { foojay-resolver-convention }` here, on purpose (removed 2026-10-05).
//
// The project requests no JVM toolchain: it compiles to Java 17 through sourceCompatibility,
// targetCompatibility and jvmTarget, so the resolver had nothing to resolve. And F-Droid's scanner
// rejects it ("usual suspect"): it downloads a JDK from the network during the build. Agenda Tech
// and SMS Tech removed it for the same reason on 2026-08-14, and measured that its mere presence
// changes the classes.dex R8 produces.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Notes Tech"
include(":app")
