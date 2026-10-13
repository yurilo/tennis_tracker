pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = java.net.URI("https://maven-central.storage-download.googleapis.com/maven2/") }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = java.net.URI("https://maven-central.storage-download.googleapis.com/maven2/") }
    }
}

rootProject.name = "TennisTracker"
include(":app")
