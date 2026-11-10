plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pl.jardol.e2stream"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.jardol.e2stream"
        minSdk = 21
        targetSdk = 35
        versionCode = 96
        versionName = "0.18.11-beta.2"
        // biblioteki natywne VLC tylko dla ARM (telewizory i przystawki) - mniejszy plik APK
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    // Stały klucz w repo => każda kolejna wersja instaluje się jako aktualizacja.
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("keystore/e2stream.jks")
            storePassword = "e2stream"
            keyAlias = "e2stream"
            keyPassword = "e2stream"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        disable += "UnsafeOptInUsageError"
    }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.core:core:1.13.1")
    // programowy dekoder MP2 (dźwięk MPEG Layer II) dla urządzeń bez sprzętowego, np. Mi Box 4
    implementation("com.googlecode.soundlibs:jlayer:1.0.1.4")
    // drugi silnik odtwarzania (wybór w ustawieniach odbiornika), LGPL 2.1+
    implementation("org.videolan.android:libvlc-all:3.6.0")
}
