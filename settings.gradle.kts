rootProject.name = "kataa"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// Shared, platform-independent core (database, search, categories, reminders, export).
include(":core")
