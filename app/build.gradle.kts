plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.phonedeck.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phonedeck.app"
        minSdk = 29          // Android 10+
        targetSdk = 34
        versionCode = 11
        versionName = "2.9.0"
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
