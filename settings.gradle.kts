pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Mapbox Maps SDK (the optional Mapbox map); only Mapbox's own artifacts come from here.
        maven {
            url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
            content { includeGroupByRegex("com[.]mapbox.*") }
        }
    }
}

rootProject.name = "RFSentinel"
include(":app")
