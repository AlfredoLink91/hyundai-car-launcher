import java.util.Properties

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
        versionCode = 2
        versionName = "1.0"
        // La tablet es arm64: solo se incluye esa versión de las librerías nativas (APK más chico)
        ndk { abiFilters += "arm64-v8a" }
    }

    // Firma de la versión publicada: los datos viven en keystore.properties (no se sube a GitHub).
    // Si no existe, la versión "release" se compila sin firmar.
    val keystoreFile = rootProject.file("keystore.properties")
    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                val props = Properties().apply { keystoreFile.inputStream().use { load(it) } }
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }

    // La revisión automática (lint) de esta versión de las herramientas falla con el JDK de Android
    // Studio al compilar la versión publicada; no afecta al APK, así que se omite.
    lint {
        checkReleaseBuilds = false
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
