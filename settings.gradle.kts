pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
// A FOSS-only build (every task named is a foss one, e.g. assembleFossRelease, or -Prfs.foss=true) never
// declares or contacts Mapbox's server: the full flavor and its Mapbox SDK are left out (app/build.gradle.kts).
val fossOnly = providers.gradleProperty("rfs.foss").orNull == "true" ||
    gradle.startParameter.taskNames.filterNot { it.substringAfterLast(':') == "clean" }
        .let { t -> t.isNotEmpty() && t.all { it.contains("foss", ignoreCase = true) } }
(gradle as ExtensionAware).extra["fossOnly"] = fossOnly

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Mapbox Maps SDK (the optional Mapbox map, full flavor only); only Mapbox's own artifacts come from here.
        if (!fossOnly) maven {
            url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
            content { includeGroupByRegex("com[.]mapbox.*") }
        }
    }
}

rootProject.name = "RFSentinel"
include(":app")
