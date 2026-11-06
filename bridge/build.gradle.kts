plugins {
    id("com.android.library")
    kotlin("android")
}

// Qonecta Unit Bridge: protocolo compartido entre Qonecta Unit (coche) y Qonecta Unit Companion (móvil).
// Sin dependencias de Android en la lógica, para poder probarla en la JVM.
android {
    compileSdk = 36
    namespace = "com.headqlink.unit.bridge"

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.0")
    testImplementation("junit:junit:4.13.2")
    // org.json real para los tests en la JVM (en Android viene con el sistema).
    testImplementation("org.json:json:20231013")
}
