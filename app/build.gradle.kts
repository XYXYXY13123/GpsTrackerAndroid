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
        versionCode = 1
        versionName = "1.0"
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

// Keine externen Bibliotheken nötig: GPS (LocationManager) und
// Sprachausgabe (TextToSpeech) sind direkt in Android eingebaut.
