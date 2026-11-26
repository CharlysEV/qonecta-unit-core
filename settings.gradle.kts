import org.gradle.kotlin.dsl.maven
import org.gradle.kotlin.dsl.repositories

include(":app", ":contract", ":bridge", ":companion")


rootProject.name = "open-headunit"

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        maven {
            url = uri("https://maven-central.storage-download.googleapis.com/maven2")
        }
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven {
            url = uri("https://maven-central.storage-download.googleapis.com/maven2")
        }
        mavenCentral()

        maven {
            url = uri("https://jitpack.io")
        }
    }
}
