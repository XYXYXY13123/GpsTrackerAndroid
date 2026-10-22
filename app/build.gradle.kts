plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.tachoansage"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.tachoansage"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.2.1"
    }

    // Fester Signaturschlüssel: Damit lassen sich neue Versionen über die alte installieren.
    // (Ohne ihn würde jeder GitHub-Bau einen neuen Zufallsschlüssel bekommen.)
    signingConfigs {
        create("tacho") {
            storeFile = file("tacho-signing.jks")
            storePassword = "tachoansage"
            keyAlias = "tacho"
            keyPassword = "tachoansage"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("tacho")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("tacho")
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

// Keine externen Bibliotheken nötig: GPS (LocationManager) und
// Sprachausgabe (TextToSpeech) sind direkt in Android eingebaut.
