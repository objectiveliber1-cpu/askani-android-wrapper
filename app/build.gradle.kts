plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

android {
    namespace = "org.objectiveliberty.askani"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.objectiveliberty.askani"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // We are building DEBUG APK in CI (no signing needed).
            isMinifyEnabled = false
        }
        debug {
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
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
