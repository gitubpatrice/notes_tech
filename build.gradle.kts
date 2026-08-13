plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

allprojects {
    val detektPlugin = rootProject.libs.plugins.detekt.get()
    val ktlintPlugin = rootProject.libs.plugins.ktlint.get()
    apply(plugin = detektPlugin.pluginId)
    apply(plugin = ktlintPlugin.pluginId)

    detekt {
        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        autoCorrect = false
        parallel = true
    }

    // Le greffon `detekt-formatting` est présent mais son jeu de règles est **inactif**
    // (`formatting.active: false` dans config/detekt/detekt.yml).
    //
    // Il est conservé parce que detekt valide son fichier de configuration contre les jeux de
    // règles chargés : sans le greffon, la section `formatting:` devient « propriété inexistante »
    // et la tâche échoue avant même d'analyser quoi que ce soit.
    //
    // Pourquoi l'avoir désactivé : il fait double emploi avec ktlint, et les deux exigeaient des
    // mises en forme INCOMPATIBLES sur les mêmes fichiers. Un seul propriétaire du style — ktlint,
    // configuré dans .editorconfig. detekt ne fait plus que du fond.
    dependencies {
        add("detektPlugins", rootProject.libs.detekt.formatting)
    }

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.3.1")
        android.set(true)
        // Le projet démarre vide : on garde `ignoreFailures` à false dès le départ, contrairement à
        // Agenda Tech qui a dû l'assouplir sur du code déjà écrit. Un projet neuf n'a aucune raison
        // d'accumuler une dette de style — la basculer plus tard ne se fait jamais.
        ignoreFailures.set(false)
        reporters {
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.HTML)
        }
        filter {
            exclude("**/generated/**", "**/build/**")
        }
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
