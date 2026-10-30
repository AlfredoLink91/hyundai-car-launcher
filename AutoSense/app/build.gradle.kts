    plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.autosense.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.autosense.launcher"
        // Lenovo Tab 4 10 (TB-X304) trae Android 7.0/7.1
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        // La tablet es arm64: solo se incluye esa versión de las librerías nativas (APK más chico)
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Sin librerías externas: solo framework de Android, para que corra ligero en 2 GB de RAM.
}
