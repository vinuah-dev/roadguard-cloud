// Top-level settings.gradle.kts
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
        // OSMDroid and other geo libraries
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "RoadGuard"
include(":app")
