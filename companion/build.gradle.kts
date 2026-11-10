import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions

plugins {
    id("com.android.application")
    kotlin("android")
}

// Qonecta Unit Companion: la app del MÓVIL que habla con Qonecta Unit (coche).
// Fase 1: recibe la ruta del planificador del coche y la abre en Google Maps (Android Auto la proyecta).
android {
    compileSdk = 36
    namespace = "com.headqlink.unit.companion"

    defaultConfig {
        applicationId = "com.headqlink.unit.companion"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "0.1.17"
        setProperty("archivesBaseName", "QonectaUnitCompanion_${versionName}")
    }

    signingConfigs {
        getByName("debug") {
            // La misma clave portátil que Qonecta Unit (firma/), para poder actualizar encima desde cualquier PC.
            val portable = rootProject.file("firma/debug.keystore")
            if (portable.exists()) {
                storeFile = portable
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        (this as KotlinJvmOptions).jvmTarget = "11"
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(project(":bridge"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.0")
    implementation("androidx.annotation:annotation:1.7.1")
}
