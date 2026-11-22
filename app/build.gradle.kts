plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.ultrascope"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.ultrascope"
        minSdk = 30          // CONTROL_ZOOM_RATIO + Multi-Kamera-Metadaten
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            // Galaxy S25 Ultra = arm64. Spart ~100 MB OpenCV-Bibliotheken anderer ABIs.
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // Fuer einen Prototyp signieren wir Release mit dem Debug-Schluessel,
            // damit "assembleRelease" ohne Keystore-Verwaltung ein installierbares APK liefert.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // OpenCV offizielles Android-AAR von Maven Central (Focus-Stacking, Registrierung, Stitching)
    implementation("org.opencv:opencv:4.12.0")

    testImplementation("junit:junit:4.13.2")
    // Desktop-OpenCV (Linux/Windows/macOS x86_64) nur fuer JVM-Unit-Tests der Bildverarbeitung
    testImplementation("org.openpnp:opencv:4.9.0-0")
}
