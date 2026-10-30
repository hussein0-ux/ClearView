plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.clearview.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.clearview.player"
        minSdk = 33
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    packaging {
        // QNN loads its HTP libraries by name from the app's native library directory,
        // so they must be extracted to disk instead of read from inside the APK.
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += setOf("**/libc++_shared.so")
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
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.24.3")
}
